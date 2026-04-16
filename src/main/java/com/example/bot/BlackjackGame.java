package com.example.bot;

import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.entities.MessageEmbed;
import net.dv8tion.jda.api.entities.User;
import net.dv8tion.jda.api.entities.channel.middleman.GuildMessageChannel;
import net.dv8tion.jda.api.components.actionrow.ActionRow;
import net.dv8tion.jda.api.components.buttons.Button;

import java.awt.Color;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

public class BlackjackGame {

    // -------- Public API --------
    public enum Action { HIT, STAND, DOUBLE, SPLIT }

    private static final int START_BALANCE = 100;
    private static final int TURN_TIMEOUT_SECONDS = 90;

    private static final int[] BET_OPTIONS = {5, 10, 25, 50, 100, 500, 1000};
    private static final int BUY_IN_AMOUNT = 100;
    private static final int BETTING_SECONDS = 20;
    private static final int DEFAULT_BET = 5;

    private static final long DEALER_REVEAL_DELAY_MS = 800;
    private static final long DEALER_DRAW_DELAY_MS   = 700;
    private static final long RESULTS_PAUSE_MS = 8000;
    private static final long IDLE_CLOSE_MS = 5000;
    private static final int  MAX_ROUND_HISTORY = 20;

    public static final String BTN_LEDGER_PREV  = "bj_ledger_prev";
    public static final String BTN_LEDGER_NEXT  = "bj_ledger_next";
    public static final String BTN_LEDGER_CLOSE = "bj_ledger_close";

    public static final String BTN_JOIN   = "bj:join";
    public static final String BTN_LEAVE  = "bj:leave";
    public static final String BTN_HIT    = "bj:hit";
    public static final String BTN_STAND  = "bj:stand";
    public static final String BTN_DOUBLE = "bj:double";
    public static final String BTN_SPLIT  = "bj:split";
    public static final String BTN_BUYIN       = "bj:buyin";
    public static final String BTN_BET_CLEAR   = "bj:bet_clear";
    public static final String BTN_BET_CONFIRM = "bj:bet_confirm";
    public static final String BTN_BET_PREFIX  = "bj:bet:";
    public static final String BTN_HIST_PREV = "bj:hist_prev";
    public static final String BTN_HIST_NEXT = "bj:hist_next";

    private static final Path SAVE_PATH = Paths.get("config", "blackjack.json");
    private static final Random RNG = new Random();

    // -------- Global: user stats travel across guilds --------
    private static final Map<String, Integer>           balancesById = new ConcurrentHashMap<>();
    private static final Map<String, Integer>           winsById     = new ConcurrentHashMap<>();
    private static final Map<String, Integer>           lossesById   = new ConcurrentHashMap<>();
    private static final Map<String, Deque<LedgerEntry>> ledgerById  = new ConcurrentHashMap<>();

    // -------- Global: per-channel ledger UI state --------
    private static final Map<Long, LedgerViewState> ledgerViews = new ConcurrentHashMap<>();

    // -------- Per-guild table registry --------
    private static final ConcurrentHashMap<Long, TableState> tables = new ConcurrentHashMap<>();

    private static TableState getTable(GuildMessageChannel channel) {
        return tables.computeIfAbsent(channel.getGuild().getIdLong(), TableState::new);
    }

    static {
        loadState();
    }

    // =====================================================================
    //  Per-guild table state
    // =====================================================================
    private static final class TableState {
        final long guildId;

        // Dedicated threads so two guilds never block each other
        final ExecutorService gameThread;
        final ScheduledExecutorService sched;

        // Seating
        final List<User>  seated  = new CopyOnWriteArrayList<>();
        final Set<User>   waiting = ConcurrentHashMap.newKeySet();

        // Action queues keyed by user
        final Map<User, BlockingQueue<Action>> actionQueues = new ConcurrentHashMap<>();

        // Round state
        final Map<User, List<HandState>> playerHands = new ConcurrentHashMap<>();
        volatile boolean running          = false;
        volatile boolean roundActive      = false;
        volatile User    currentPlayer    = null;
        volatile int     currentHandIndex = 0;
        List<String>  dealerHand   = new ArrayList<>();
        volatile boolean dealerHidden = true;

        // Phase
        volatile Phase phase = Phase.LOBBY;

        // Betting state (per round)
        final Map<String, Integer> pendingBetById = new ConcurrentHashMap<>();
        final Map<String, Integer> stagedBetById  = new ConcurrentHashMap<>();
        final Map<String, Integer> roundNetById   = new ConcurrentHashMap<>();

        // Message tracking (keyed by channel ID — one game can run per channel)
        final Map<Long, Long> tableMessageByChannel  = new ConcurrentHashMap<>();
        final Map<Long, Long> lastUpdateByChannel    = new ConcurrentHashMap<>();
        final Set<Long>  creatingTableMessage        = ConcurrentHashMap.newKeySet();
        final Map<Long, Long> ledgerMessageByChannel = new ConcurrentHashMap<>();
        final Set<Long>  creatingLedgerMessage       = ConcurrentHashMap.newKeySet();
        final Map<Long, Long> systemMessageByChannel = new ConcurrentHashMap<>();
        final Set<Long>  creatingSystemMessage       = ConcurrentHashMap.newKeySet();
        final Map<Long, String>           lastSystemTextByChannel    = new ConcurrentHashMap<>();
        final Map<Long, ScheduledFuture<?>> pendingSystemUpdateByChannel = new ConcurrentHashMap<>();
        final Map<Long, ScheduledFuture<?>> idleCloseTask            = new ConcurrentHashMap<>();

        // History (per channel)
        final Map<Long, Deque<RoundLog>> roundHistoryByChannel = new ConcurrentHashMap<>();
        final Map<Long, Integer>          historyPageByChannel  = new ConcurrentHashMap<>();

        TableState(long guildId) {
            this.guildId = guildId;
            this.gameThread = Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "blackjack-thread-" + guildId);
                t.setDaemon(true);
                return t;
            });
            this.sched = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "blackjack-sched-" + guildId);
                t.setDaemon(true);
                return t;
            });
        }
    }

    private enum Phase { LOBBY, BETTING, PLAYING, RESULTS }

    // =====================================================================
    //  Backwards-compatible public API
    // =====================================================================

    public static void pushRotator(GuildMessageChannel channel, String line) {
        if (channel == null) return;
        system(getTable(channel), channel, line);
    }

    public static void startGame(User user, GuildMessageChannel channel) { join(user, channel); }

    public static void join(User user, GuildMessageChannel channel) {
        TableState ts = getTable(channel);
        cancelIdleClose(ts, channel);

        String uid = user.getId();
        balancesById.putIfAbsent(uid, START_BALANCE);
        winsById.putIfAbsent(uid, 0);
        lossesById.putIfAbsent(uid, 0);
        ledgerById.putIfAbsent(uid, new ArrayDeque<>());
        ts.actionQueues.putIfAbsent(user, new ArrayBlockingQueue<>(16));

        if (ts.seated.contains(user)) {
            ensureRunning(ts, channel);
            system(ts, channel, "✅ You're already seated.");
            return;
        }
        if (ts.waiting.contains(user)) {
            ensureRunning(ts, channel);
            system(ts, channel, "🕒 You're already in the lobby — you'll join next round.");
            return;
        }

        if (ts.roundActive || ts.phase != Phase.LOBBY) {
            ts.waiting.add(user);
            system(ts, channel, "🕒 Added to lobby — you'll join next round.");
        } else {
            ts.seated.add(user);
            system(ts, channel, "✅ Seated! Betting starts shortly.");
        }

        ensureRunning(ts, channel);
        refreshTable(ts, channel, true);
    }

    public static void leave(User user, GuildMessageChannel channel) {
        TableState ts = getTable(channel);
        ts.seated.remove(user);
        ts.waiting.remove(user);
        ts.actionQueues.remove(user);
        ts.playerHands.remove(user);
        ts.pendingBetById.remove(user.getId());
        ts.stagedBetById.remove(user.getId());

        if (user.equals(ts.currentPlayer)) offerAction(ts, user, Action.STAND);

        if (ts.seated.isEmpty() && ts.waiting.isEmpty()) {
            ts.currentPlayer = null;
            ts.currentHandIndex = 0;
            system(ts, channel, "Table empty — closing soon.");
            scheduleIdleClose(ts, channel);
            refreshTable(ts, channel, true);
            return;
        }

        system(ts, channel, "👋 Removed **" + displayName(channel, user) + "** from the table.");
        refreshTable(ts, channel, true);
    }

    public static void action(User user, Action action, GuildMessageChannel channel) {
        if (user == null || action == null) return;
        TableState ts = getTable(channel);

        if (!ts.seated.contains(user) && !ts.waiting.contains(user)) {
            if (channel != null) system(ts, channel, "⚠️ You must **Join** before you can queue actions.");
            return;
        }

        BlockingQueue<Action> q = ts.actionQueues.computeIfAbsent(user, k -> new LinkedBlockingQueue<>(16));
        if (!q.offer(action)) { q.poll(); q.offer(action); }

        if (channel != null) {
            system(ts, channel, "⏳ Queued **" + action.name() + "** for **" + displayName(channel, user) + "**  (" + q.size() + " in queue)");
        }
    }

    public static void action(User user, Action action) { /* no-op without channel; requires channel context */ }

    public static void buyIn(User user, GuildMessageChannel channel) {
        TableState ts = getTable(channel);
        String uid = user.getId();
        int bal = balancesById.getOrDefault(uid, START_BALANCE);
        if (bal >= 50) { system(ts, channel, "❌ Buy-in only works if you're below **$50**."); return; }

        balancesById.put(uid, BUY_IN_AMOUNT);
        int topUp = Math.max(0, BUY_IN_AMOUNT - bal);
        appendLedger(uid, new LedgerEntry(Instant.now().toString(), "BUY_IN", -topUp, BUY_IN_AMOUNT));
        system(ts, channel, "🪙 **" + displayName(channel, user) + "** bought in to **$" + BUY_IN_AMOUNT + "**");
        saveState();
        refreshTable(ts, channel, true);
    }

    public static void clearBet(User user, GuildMessageChannel channel) {
        TableState ts = getTable(channel);
        if (ts.phase != Phase.BETTING) { system(ts, channel, "⚠️ You can only change bets during **BETTING**."); return; }
        if (!ts.seated.contains(user)) { system(ts, channel, "⚠️ You must be seated to bet."); return; }
        ts.stagedBetById.remove(user.getId());
        ts.pendingBetById.remove(user.getId());
        system(ts, channel, "🧹 **" + displayName(channel, user) + "** cleared their bet");
        refreshTable(ts, channel, true);
    }

    public static void placeBet(User user, int amount, GuildMessageChannel channel) {
        TableState ts = getTable(channel);
        if (ts.phase != Phase.BETTING) { system(ts, channel, "⚠️ You can only place chips during **BETTING**."); return; }
        if (!ts.seated.contains(user)) { system(ts, channel, "⚠️ You must be seated to bet."); return; }

        boolean ok = false;
        for (int b : BET_OPTIONS) if (b == amount) { ok = true; break; }
        if (!ok) return;

        String uid = user.getId();
        int bal    = balancesById.getOrDefault(uid, START_BALANCE);
        int staged = ts.stagedBetById.getOrDefault(uid, 0);
        int next   = Math.min(staged + amount, bal);
        if (next < 0) next = 0;
        ts.stagedBetById.put(uid, next);
        system(ts, channel, "🟡 Staged bet for **" + displayName(channel, user) + "**: **$" + next + "** (press **BET** to lock)");
        refreshTable(ts, channel, true);
    }

    public static void confirmBet(User user, GuildMessageChannel channel) {
        TableState ts = getTable(channel);
        if (ts.phase != Phase.BETTING) { system(ts, channel, "⚠️ You can only lock a bet during **BETTING**."); return; }
        if (!ts.seated.contains(user)) { system(ts, channel, "⚠️ You must be seated to bet."); return; }

        String uid    = user.getId();
        int    bal    = balancesById.getOrDefault(uid, START_BALANCE);
        int    staged = ts.stagedBetById.getOrDefault(uid, 0);

        if (staged <= 0) { system(ts, channel, "⚠️ Your staged bet is **$0**. Tap chips first."); return; }
        if (staged > bal) staged = bal;

        ts.pendingBetById.put(uid, staged);
        system(ts, channel, "✅ **" + displayName(channel, user) + "** locked in **$" + staged + "**");
        refreshTable(ts, channel, true);
    }

    public static void addBalance(User user, int amount, GuildMessageChannel channel) {
        TableState ts = getTable(channel);
        String uid = user.getId();
        balancesById.putIfAbsent(uid, START_BALANCE);
        int newBal = balancesById.merge(uid, amount, Integer::sum);
        appendLedger(uid, new LedgerEntry(Instant.now().toString(), "ADMIN_FUNDS", amount, newBal));
        system(ts, channel, "💰 Funded **" + displayName(channel, user) + "** **$" + amount + "**");
        saveState();
        refreshTable(ts, channel, true);
    }

    public static void showLedger(User user, GuildMessageChannel channel) {
        if (channel == null) return;
        TableState ts = getTable(channel);
        LedgerViewState st = ledgerViews.computeIfAbsent(channel.getIdLong(), k -> new LedgerViewState());
        st.order = buildLedgerOrder();
        if (st.order.isEmpty()) { system(ts, channel, "📒 Ledger is empty (no one has played yet)."); return; }
        st.page = clamp(st.page, 0, st.order.size() - 1);
        upsertLedgerPopup(channel, st);
    }

    public static void ledgerPrev(GuildMessageChannel channel) {
        if (channel == null) return;
        LedgerViewState st = ledgerViews.get(channel.getIdLong());
        if (st == null || st.order.isEmpty()) return;
        st.page = clamp(st.page - 1, 0, st.order.size() - 1);
        upsertLedgerPopup(channel, st);
    }

    public static void ledgerNext(GuildMessageChannel channel) {
        if (channel == null) return;
        LedgerViewState st = ledgerViews.get(channel.getIdLong());
        if (st == null || st.order.isEmpty()) return;
        st.page = clamp(st.page + 1, 0, st.order.size() - 1);
        upsertLedgerPopup(channel, st);
    }

    public static void ledgerClose(GuildMessageChannel channel) {
        if (channel == null) return;
        LedgerViewState st = ledgerViews.remove(channel.getIdLong());
        if (st == null || st.messageId == 0L) return;
        channel.editMessageEmbedsById(st.messageId, new EmbedBuilder()
                .setTitle("📒 Ledger (closed)")
                .setDescription("Use `!ledger` to open again.")
                .setColor(new Color(80, 80, 80))
                .build())
            .setComponents()
            .queue(null, e -> { });
    }

    public static void historyPrev(GuildMessageChannel channel) {
        TableState ts = getTable(channel);
        long cid = channel.getIdLong();
        Deque<RoundLog> hist = ts.roundHistoryByChannel.getOrDefault(cid, new ArrayDeque<>());
        if (hist.isEmpty()) return;
        int page = ts.historyPageByChannel.getOrDefault(cid, 0);
        ts.historyPageByChannel.put(cid, Math.min(hist.size() - 1, page + 1));
        refreshTable(ts, channel, true);
    }

    public static void historyNext(GuildMessageChannel channel) {
        TableState ts = getTable(channel);
        long cid = channel.getIdLong();
        int page = ts.historyPageByChannel.getOrDefault(cid, 0);
        ts.historyPageByChannel.put(cid, Math.max(0, page - 1));
        refreshTable(ts, channel, true);
    }

    // =====================================================================
    //  Game loop
    // =====================================================================

    private static void ensureRunning(TableState ts, GuildMessageChannel channel) {
        if (!ts.running) {
            ts.running = true;
            ts.gameThread.submit(() -> gameLoop(ts, channel));
        }
    }

    private static void gameLoop(TableState ts, GuildMessageChannel channel) {
        while (ts.running) {
            if (ts.seated.isEmpty()) { sleep(100); continue; }
            playRound(ts, channel);
        }
    }

    private static void playRound(TableState ts, GuildMessageChannel channel) {
        clearAllQueues(ts);
        ts.roundActive = true;
        ts.phase = Phase.BETTING;

        ts.seated.addAll(ts.waiting);
        ts.waiting.clear();

        ts.playerHands.clear();
        ts.dealerHand.clear();
        ts.dealerHidden = true;
        ts.currentPlayer = null;
        ts.currentHandIndex = 0;
        ts.pendingBetById.clear();
        ts.stagedBetById.clear();
        ts.roundNetById.clear();

        system(ts, channel, "🟣 Betting phase started — build your bet with chips, then press **BET**.");
        refreshTable(ts, channel, true);

        long end = System.currentTimeMillis() + (BETTING_SECONDS * 1000L);
        while (System.currentTimeMillis() < end && ts.running && !ts.seated.isEmpty()) {
            boolean allBet = true;
            for (User u : ts.seated) {
                if (!ts.pendingBetById.containsKey(u.getId())) { allBet = false; break; }
            }
            if (allBet) break;
            sleep(200);
        }

        if (ts.seated.isEmpty()) {
            ts.phase = Phase.LOBBY;
            ts.roundActive = false;
            refreshTable(ts, channel, true);
            return;
        }

        ts.phase = Phase.PLAYING;
        ts.dealerHand = dealHand();
        ts.dealerHidden = true;

        for (User u : new ArrayList<>(ts.seated)) {
            String uid = u.getId();
            int bet = ts.pendingBetById.getOrDefault(uid, 0);

            if (bet <= 0) {
                int bal = balancesById.getOrDefault(uid, START_BALANCE);
                if (bal >= DEFAULT_BET) {
                    bet = DEFAULT_BET;
                    ts.pendingBetById.put(uid, bet);
                    system(ts, channel, "🟡 **" + displayName(channel, u) + "** didn't lock a bet — defaulted to **$" + DEFAULT_BET + "**");
                } else {
                    ts.seated.remove(u);
                    system(ts, channel, "❌ **" + displayName(channel, u) + "** didn't have enough to bet and was removed.");
                    continue;
                }
            }

            int bal = balancesById.getOrDefault(uid, START_BALANCE);
            if (bal < bet) {
                ts.seated.remove(u);
                system(ts, channel, "❌ **" + displayName(channel, u) + "** couldn't cover their bet and was removed.");
                continue;
            }

            balancesById.put(uid, bal - bet);
            appendLedger(uid, new LedgerEntry(Instant.now().toString(), "BET", -bet, balancesById.get(uid)));

            List<HandState> hs = new ArrayList<>();
            hs.add(new HandState(dealHand(), bet));
            ts.playerHands.put(u, hs);
        }

        saveState();
        refreshTable(ts, channel, true);

        for (User u : new ArrayList<>(ts.seated)) {
            if (!ts.running) break;
            takePlayerTurns(ts, u, channel);
        }

        ts.currentPlayer = null;
        ts.currentHandIndex = 0;
        ts.dealerHidden = false;
        refreshTable(ts, channel, true);
        sleep(DEALER_REVEAL_DELAY_MS);

        while (handValue(ts.dealerHand) < 17) {
            ts.dealerHand.add(dealCard());
            refreshTable(ts, channel, true);
            sleep(DEALER_DRAW_DELAY_MS);
        }

        settleRound(ts, channel);

        ts.phase = Phase.RESULTS;
        showRoundResultsScreen(ts, channel);
        sleep(RESULTS_PAUSE_MS);

        ts.currentPlayer = null;
        ts.currentHandIndex = 0;
        ts.roundActive = false;
        ts.phase = Phase.LOBBY;

        system(ts, channel, "Next round will start shortly. Join/leave anytime.");
        refreshTable(ts, channel, true);
        saveState();

        if (ts.seated.isEmpty() && ts.waiting.isEmpty()) scheduleIdleClose(ts, channel);

        sleep(800);
    }

    private static void takePlayerTurns(TableState ts, User user, GuildMessageChannel channel) {
        List<HandState> hands = ts.playerHands.get(user);
        if (hands == null) return;
        if (!ts.seated.contains(user)) return;

        for (int i = 0; i < hands.size(); i++) {
            ts.currentPlayer = user;
            ts.currentHandIndex = i;

            HandState h = hands.get(i);
            refreshTable(ts, channel, true);

            if (i == 0 && hands.size() == 1 && h.cards.size() == 2 && handValue(h.cards) == 21) {
                String uid = user.getId();
                int payout = (int) Math.round(h.bet * 2.5);
                balancesById.merge(uid, payout, Integer::sum);
                winsById.merge(uid, 1, Integer::sum);
                appendLedger(uid, new LedgerEntry(Instant.now().toString(), "WIN_BLACKJACK", payout, balancesById.get(uid)));
                ts.roundNetById.merge(uid, (int) Math.round(h.bet * 1.5), Integer::sum);
                h.resolved.set(true);
                system(ts, channel, "🟢 **" + displayName(channel, user) + "** hit **BLACKJACK**!");
                refreshTable(ts, channel, true);
                continue;
            }

            while (true) {
                if (!ts.running || !ts.seated.contains(user)) return;

                int total = handValue(h.cards);
                if (total > 21) {
                    h.resolved.set(true);
                    system(ts, channel, "💥 **" + displayName(channel, user) + "** busted.");
                    refreshTable(ts, channel, true);
                    break;
                }

                Action act = pollAction(ts, user);
                if (act == null) act = Action.STAND;

                switch (act) {
                    case HIT -> {
                        h.cards.add(dealCard());
                        refreshTable(ts, channel, true);
                        continue;
                    }
                    case STAND -> {
                        h.resolved.set(true);
                        refreshTable(ts, channel, true);
                        break;
                    }
                    case DOUBLE -> {
                        if (!canDouble(ts, user, h)) { system(ts, channel, "❌ Can't double right now."); continue; }
                        String uid = user.getId();
                        balancesById.put(uid, balancesById.get(uid) - h.bet);
                        appendLedger(uid, new LedgerEntry(Instant.now().toString(), "DOUBLE_BET", -h.bet, balancesById.get(uid)));
                        h.bet *= 2;
                        h.cards.add(dealCard());
                        h.resolved.set(true);
                        system(ts, channel, "🟡 **" + displayName(channel, user) + "** doubled.");
                        refreshTable(ts, channel, true);
                        break;
                    }
                    case SPLIT -> {
                        if (!canSplit(ts, user, h)) { system(ts, channel, "❌ Can't split right now."); continue; }
                        String uid = user.getId();
                        balancesById.put(uid, balancesById.get(uid) - h.bet);
                        appendLedger(uid, new LedgerEntry(Instant.now().toString(), "SPLIT_BET", -h.bet, balancesById.get(uid)));

                        String c1 = h.cards.get(0);
                        String c2 = h.cards.get(1);
                        HandState h1 = new HandState(new ArrayList<>(List.of(c1, dealCard())), h.bet);
                        HandState h2 = new HandState(new ArrayList<>(List.of(c2, dealCard())), h.bet);
                        hands.set(i, h1);
                        hands.add(i + 1, h2);

                        system(ts, channel, "🟣 **" + displayName(channel, user) + "** split their hand.");
                        h = hands.get(i);
                        refreshTable(ts, channel, true);
                        continue;
                    }
                }
                break;
            }
        }
    }

    private static void settleRound(TableState ts, GuildMessageChannel channel) {
        int dealerTotal = handValue(ts.dealerHand);
        List<String> logLines = new ArrayList<>();

        for (User u : new ArrayList<>(ts.seated)) {
            String uid = u.getId();
            List<HandState> hs = ts.playerHands.get(u);
            if (hs == null) continue;

            for (int i = 0; i < hs.size(); i++) {
                HandState h = hs.get(i);
                int t = handValue(h.cards);
                String who = displayName(channel, u) + (hs.size() > 1 ? " (H" + (i + 1) + ")" : "");
                String outcome;
                int net;

                if (t > 21) net = -h.bet;
                else if (dealerTotal > 21 || t > dealerTotal) net = +h.bet;
                else if (t == dealerTotal) net = 0;
                else net = -h.bet;

                ts.roundNetById.merge(uid, net, Integer::sum);

                if (t > 21) {
                    lossesById.merge(uid, 1, Integer::sum);
                    appendLedger(uid, new LedgerEntry(Instant.now().toString(), "LOSS_BUST", 0, balancesById.getOrDefault(uid, START_BALANCE)));
                    outcome = "LOSS (bust)";
                } else if (dealerTotal > 21 || t > dealerTotal) {
                    int payout = h.bet * 2;
                    balancesById.merge(uid, payout, Integer::sum);
                    winsById.merge(uid, 1, Integer::sum);
                    appendLedger(uid, new LedgerEntry(Instant.now().toString(), "WIN", payout, balancesById.get(uid)));
                    outcome = "WIN";
                } else if (t == dealerTotal) {
                    int payout = h.bet;
                    balancesById.merge(uid, payout, Integer::sum);
                    appendLedger(uid, new LedgerEntry(Instant.now().toString(), "PUSH", payout, balancesById.get(uid)));
                    outcome = "PUSH";
                } else {
                    lossesById.merge(uid, 1, Integer::sum);
                    appendLedger(uid, new LedgerEntry(Instant.now().toString(), "LOSS", 0, balancesById.getOrDefault(uid, START_BALANCE)));
                    outcome = "LOSS";
                }

                logLines.add("• **" + who + "**: " + formatHand(h.cards) + " (**" + t + "**), Bet **$" + h.bet + "** → **" + outcome + "**");
            }
        }

        long cid = channel.getIdLong();
        Deque<RoundLog> hist = ts.roundHistoryByChannel.computeIfAbsent(cid, k -> new ArrayDeque<>());
        hist.addFirst(new RoundLog(Instant.now().toString(), new ArrayList<>(ts.dealerHand), dealerTotal, logLines));
        while (hist.size() > MAX_ROUND_HISTORY) hist.removeLast();
        ts.historyPageByChannel.put(cid, 0);

        saveState();
        refreshTable(ts, channel, true);
    }

    private static void showRoundResultsScreen(TableState ts, GuildMessageChannel channel) {
        int dealerTotal = handValue(ts.dealerHand);
        StringBuilder winners = new StringBuilder();
        StringBuilder losers  = new StringBuilder();
        StringBuilder pushes  = new StringBuilder();

        for (User u : new ArrayList<>(ts.seated)) {
            List<HandState> hs = ts.playerHands.get(u);
            if (hs == null) continue;

            int rn = ts.roundNetById.getOrDefault(u.getId(), 0);
            String rnStr = (rn >= 0 ? " 🟢 **+" : " 🔴 **") + rn + "**";

            for (int i = 0; i < hs.size(); i++) {
                HandState h = hs.get(i);
                int t = handValue(h.cards);
                String label = u.getAsMention() + (hs.size() > 1 ? " (H" + (i + 1) + ")" : "");
                String line  = "• " + label + ": " + formatHand(h.cards) + " (**" + t + "**)" + rnStr + "\n";

                if (t > 21) losers.append(line);
                else if (dealerTotal > 21 || t > dealerTotal) winners.append(line);
                else if (t == dealerTotal) pushes.append(line);
                else losers.append(line);
            }
        }

        EmbedBuilder eb = new EmbedBuilder().setTitle("📌 ROUND RESULTS").setColor(new Color(0x3498db));
        eb.addField("🟢 WINNERS", winners.length() == 0 ? "> _None this round_" : ">>> " + winners, false);
        eb.addField("🔵 PUSH",    pushes.length()  == 0 ? "> _None_"           : ">>> " + pushes,   false);
        eb.addField("🔴 LOSERS",  losers.length()  == 0 ? "> _None_"           : ">>> " + losers,   false);
        eb.addField("🧑‍⚖️ DEALER", ">>> **" + formatHand(ts.dealerHand) + "**  (**" + dealerTotal + "**)", false);
        eb.setFooter("Next round starts shortly…");

        upsertTableMessage(ts, channel, eb.build(), buildButtons(ts, channel.getIdLong()), true);
    }

    // =====================================================================
    //  UI helpers
    // =====================================================================

    private static void refreshTable(TableState ts, GuildMessageChannel channel, boolean force) {
        long cid = channel.getIdLong();

        boolean needLedger = ts.ledgerMessageByChannel.get(cid) == null;
        boolean needGame   = ts.tableMessageByChannel.get(cid) == null;
        boolean needSys    = ts.systemMessageByChannel.get(cid) == null;

        if (needLedger || needGame || needSys) {
            ensureMessageStack(ts, channel, force);
            return;
        }

        upsertLedgerMessage(ts, channel, buildLedgerEmbed(ts, channel));
        upsertTableMessage(ts, channel, buildEmbed(ts, cid), buildButtons(ts, cid), force);
        upsertSystemMessage(ts, channel, buildSystemEmbed(ts, channel));
    }

    private static void ensureMessageStack(TableState ts, GuildMessageChannel channel, boolean force) {
        long cid = channel.getIdLong();
        if (ts.seated.isEmpty() && ts.waiting.isEmpty() && !ts.roundActive) return;

        // 1) Ledger (top)
        if (ts.ledgerMessageByChannel.get(cid) == null) {
            if (!ts.creatingLedgerMessage.add(cid)) return;
            channel.sendMessageEmbeds(buildLedgerEmbed(ts, channel)).queue(m -> {
                ts.ledgerMessageByChannel.put(cid, m.getIdLong());
                ts.creatingLedgerMessage.remove(cid);
                ensureMessageStack(ts, channel, force);
            }, fail -> ts.creatingLedgerMessage.remove(cid));
            return;
        }

        // 2) Game
        if (ts.tableMessageByChannel.get(cid) == null) {
            if (!ts.creatingTableMessage.add(cid)) return;
            channel.sendMessageEmbeds(buildEmbed(ts, cid)).setComponents(buildButtons(ts, cid)).queue(m -> {
                ts.tableMessageByChannel.put(cid, m.getIdLong());
                ts.creatingTableMessage.remove(cid);
                ensureMessageStack(ts, channel, force);
            }, fail -> ts.creatingTableMessage.remove(cid));
            return;
        }

        // 3) System (bottom)
        if (ts.systemMessageByChannel.get(cid) == null) {
            if (!ts.creatingSystemMessage.add(cid)) return;
            channel.sendMessageEmbeds(buildSystemEmbed(ts, channel)).queue(m -> {
                ts.systemMessageByChannel.put(cid, m.getIdLong());
                ts.creatingSystemMessage.remove(cid);
            }, fail -> ts.creatingSystemMessage.remove(cid));
        }
    }

    private static void upsertLedgerMessage(TableState ts, GuildMessageChannel channel, MessageEmbed embed) {
        long cid = channel.getIdLong();
        Long mid = ts.ledgerMessageByChannel.get(cid);
        if (mid == null) return;
        channel.editMessageEmbedsById(mid, embed).queue(null,
            f -> { ts.ledgerMessageByChannel.remove(cid); ts.creatingLedgerMessage.remove(cid); refreshTable(ts, channel, true); });
    }

    private static void upsertTableMessage(TableState ts, GuildMessageChannel channel, MessageEmbed embed, List<ActionRow> rows, boolean force) {
        long cid = channel.getIdLong();
        long now = System.currentTimeMillis();
        if (!force && now - ts.lastUpdateByChannel.getOrDefault(cid, 0L) < 350) return;
        ts.lastUpdateByChannel.put(cid, now);

        Long mid = ts.tableMessageByChannel.get(cid);
        if (mid == null) return;
        channel.editMessageEmbedsById(mid, embed).setComponents(rows).queue(null,
            f -> { ts.tableMessageByChannel.remove(cid); ts.creatingTableMessage.remove(cid); refreshTable(ts, channel, true); });
    }

    private static void upsertSystemMessage(TableState ts, GuildMessageChannel channel, MessageEmbed embed) {
        long cid = channel.getIdLong();
        Long mid = ts.systemMessageByChannel.get(cid);
        if (mid == null) return;
        channel.editMessageEmbedsById(mid, embed).queue(null,
            f -> { ts.systemMessageByChannel.remove(cid); ts.creatingSystemMessage.remove(cid); refreshTable(ts, channel, true); });
    }

    // -------- System panel --------
    private static void system(TableState ts, GuildMessageChannel channel, String text) {
        if (channel == null) return;
        long cid = channel.getIdLong();
        String safe = (text == null || text.isBlank()) ? "—" : text;
        ts.lastSystemTextByChannel.put(cid, safe);
        requestSystemUpdate(ts, channel);
    }

    private static void requestSystemUpdate(TableState ts, GuildMessageChannel channel) {
        if (channel == null) return;
        long cid = channel.getIdLong();
        ScheduledFuture<?> prev = ts.pendingSystemUpdateByChannel.put(cid,
            ts.sched.schedule(() -> {
                if (ts.systemMessageByChannel.get(cid) == null) { refreshTable(ts, channel, true); return; }
                upsertSystemMessage(ts, channel, buildSystemEmbed(ts, channel));
            }, 80, TimeUnit.MILLISECONDS));
        if (prev != null && !prev.isDone()) prev.cancel(false);
    }

    private static MessageEmbed buildSystemEmbed(TableState ts, GuildMessageChannel channel) {
        long cid = channel.getIdLong();
        String msg = ts.lastSystemTextByChannel.getOrDefault(cid, "Ready. Join to play.  •  `!funds <amount> @user`");
        String queueLine = buildQueueLine(ts, channel);
        String desc = queueLine.isBlank() ? msg : (queueLine + "\n\n" + msg);
        return new EmbedBuilder()
                .setTitle("💬 System")
                .setColor(new Color(0xf1c40f))
                .setDescription(desc)
                .build();
    }

    private static String buildQueueLine(TableState ts, GuildMessageChannel channel) {
        if (channel == null) return "";
        if (ts.phase != Phase.PLAYING || !ts.roundActive || ts.currentPlayer == null) return "";

        BlockingQueue<Action> q = ts.actionQueues.get(ts.currentPlayer);
        if (q == null || q.isEmpty()) return "🎛️ **Action queue:** _(empty)_";

        List<String> items = new ArrayList<>();
        int i = 0;
        for (Action a : q) { items.add(a.name()); if (++i >= 6) break; }
        String more = (q.size() > items.size()) ? " …" : "";
        return "🎛️ **Action queue (" + displayName(channel, ts.currentPlayer) + "):** `" + String.join(" ▸ ", items) + "`" + more;
    }

    private static MessageEmbed buildLedgerEmbed(TableState ts, GuildMessageChannel channel) {
        EmbedBuilder eb = new EmbedBuilder().setTitle("🧾 Ledger").setColor(new Color(0x95a5a6));

        if (ts.seated.isEmpty() && ts.waiting.isEmpty()) {
            eb.setDescription("_No players seated._");
            return eb.build();
        }

        List<User> everyone = new ArrayList<>(ts.seated);
        for (User u : ts.waiting) if (!everyone.contains(u)) everyone.add(u);

        StringBuilder sb = new StringBuilder();
        for (User u : everyone) {
            String uid     = u.getId();
            String display = displayName(channel, u);
            int bal = balancesById.getOrDefault(uid, START_BALANCE);
            int w   = winsById.getOrDefault(uid, 0);
            int l   = lossesById.getOrDefault(uid, 0);
            sb.append("**").append(display).append("** — ")
              .append("Balance: **$").append(bal).append("**  ")
              .append("W/L: **").append(w).append("/").append(l).append("**\n");
        }
        eb.setDescription(sb.toString());
        eb.setFooter("Blackjack ledger • disappears when the game closes");
        return eb.build();
    }

    private static MessageEmbed buildEmbed(TableState ts, long cid) {
        EmbedBuilder eb = new EmbedBuilder().setTitle("🃏 Blackjack");

        if (ts.phase == Phase.BETTING)       eb.setColor(new Color(0x9b59b6));
        else if (ts.phase == Phase.PLAYING)  eb.setColor(new Color(0xf1c40f));
        else if (ts.phase == Phase.RESULTS)  eb.setColor(new Color(0x3498db));
        else                                  eb.setColor(new Color(0x2ecc71));

        String dealerLine;
        if (ts.phase == Phase.BETTING)                              dealerLine = "_Waiting for bets…_";
        else if (ts.dealerHand == null || ts.dealerHand.isEmpty()) dealerLine = "—";
        else if (ts.roundActive && ts.dealerHidden)                dealerLine = "**" + ts.dealerHand.get(0) + "**  🂠";
        else                                                         dealerLine = formatHandWithValue(ts.dealerHand);
        eb.addField("Dealer", dealerLine, false);

        StringBuilder p = new StringBuilder();
        for (User u : ts.seated) {
            String uid = u.getId();
            List<HandState> hs = ts.playerHands.get(u);
            boolean isTurn = (ts.phase == Phase.PLAYING) && u.equals(ts.currentPlayer);
            p.append(u.getAsMention()).append(isTurn ? "  ▶️" : "").append("\n");

            if (ts.phase == Phase.BETTING) {
                int staged     = ts.stagedBetById.getOrDefault(uid, 0);
                Integer locked = ts.pendingBetById.get(uid);
                p.append("• Staged: ").append(staged <= 0 ? "_$0_" : "**$" + staged + "**").append("\n");
                p.append("• Locked: ").append(locked == null ? "_not set_" : "**$" + locked + "**").append("\n");
            } else if (hs != null) {
                for (int i = 0; i < hs.size(); i++) {
                    HandState h   = hs.get(i);
                    int total     = handValue(h.cards);
                    boolean active = isTurn && (i == ts.currentHandIndex);
                    p.append("• Hand ").append(i + 1).append(active ? " **(active)**" : "")
                     .append(": ").append(formatHandWithValue(h.cards))
                     .append("  Bet: **$").append(h.bet).append("**")
                     .append(total > 21 ? "  💥 BUST" : "")
                     .append("\n");
                }
            } else {
                p.append("• (waiting next round)\n");
            }

            int bal = balancesById.getOrDefault(uid, START_BALANCE);
            int w   = winsById.getOrDefault(uid, 0);
            int l   = lossesById.getOrDefault(uid, 0);
            p.append("Balance: **$").append(bal).append("** | Wins: **").append(w).append("** | Losses: **").append(l).append("**\n\n");
        }

        if (!ts.waiting.isEmpty()) {
            p.append("**Lobby (next round):**\n");
            for (User u : ts.waiting) p.append("• ").append(u.getAsMention()).append("\n");
            p.append("\n");
        }

        if (p.length() == 0) p.append("—");
        eb.addField("Players", p.toString(), false);

        Deque<RoundLog> hist = ts.roundHistoryByChannel.getOrDefault(cid, new ArrayDeque<>());
        int page = ts.historyPageByChannel.getOrDefault(cid, 0);
        String historyText;
        if (hist.isEmpty()) {
            historyText = "_No rounds yet._";
        } else {
            page = Math.max(0, Math.min(page, hist.size() - 1));
            RoundLog rl = hist.stream().skip(page).findFirst().orElse(null);
            if (rl == null) {
                historyText = "_No rounds yet._";
            } else {
                StringBuilder hs2 = new StringBuilder();
                hs2.append("**Round ").append(page + 1).append("/").append(hist.size()).append("**\n");
                hs2.append("Dealer: ").append(rl.dealer).append(" (**").append(rl.dealerTotal).append("**)\n");
                int show = Math.min(10, rl.lines.size());
                for (int i = 0; i < show; i++) hs2.append(rl.lines.get(i)).append("\n");
                if (rl.lines.size() > show) hs2.append("_...and ").append(rl.lines.size() - show).append(" more_");
                historyText = hs2.toString();
            }
        }
        eb.addField("Last Round History", historyText, false);

        if (ts.phase == Phase.BETTING) {
            eb.setFooter("Betting phase — build a bet with chips, then press BET to lock it in.");
        } else if (ts.roundActive && ts.currentPlayer != null) {
            eb.setFooter("Turn: " + ts.currentPlayer.getName() + " | Buttons or !hit/!stand/!double/!split");
        } else if (ts.seated.isEmpty() && ts.waiting.isEmpty()) {
            eb.setFooter("Table empty — auto-closes in 5s.");
        } else {
            eb.setFooter("Join to play.");
        }

        return eb.build();
    }

    private static List<ActionRow> buildButtons(TableState ts, long cid) {
        Button join  = Button.success(BTN_JOIN,  "Join");
        Button leave = Button.danger(BTN_LEAVE,  "Leave");

        Deque<RoundLog> hist = ts.roundHistoryByChannel.getOrDefault(cid, new ArrayDeque<>());
        int size = hist.size();
        int page = ts.historyPageByChannel.getOrDefault(cid, 0);
        Button prev = Button.secondary(BTN_HIST_PREV, "Prev").withDisabled(size == 0 || page >= size - 1);
        Button next = Button.secondary(BTN_HIST_NEXT, "Next").withDisabled(size == 0 || page <= 0);

        if (ts.phase == Phase.BETTING) {
            List<Button> chips = new ArrayList<>();
            for (int b : BET_OPTIONS) chips.add(Button.secondary(BTN_BET_PREFIX + b, "+$" + b));

            Button clear = Button.secondary(BTN_BET_CLEAR, "Clear");
            Button bet   = Button.primary(BTN_BET_CONFIRM, "BET")
                    .withEmoji(net.dv8tion.jda.api.entities.emoji.Emoji.fromUnicode("🟡"));
            Button buyIn = Button.success(BTN_BUYIN, "Buy In $100");

            List<ActionRow> rows = new ArrayList<>();
            rows.add(ActionRow.of(join, leave));
            if (chips.size() <= 5) rows.add(ActionRow.of(chips));
            else {
                rows.add(ActionRow.of(chips.subList(0, 5)));
                rows.add(ActionRow.of(chips.subList(5, chips.size())));
            }
            rows.add(ActionRow.of(clear, bet, buyIn, prev, next));
            return rows;
        }

        boolean turn        = (ts.phase == Phase.PLAYING) && ts.roundActive && ts.currentPlayer != null;
        Button hit   = Button.primary(BTN_HIT,    "Hit").withDisabled(!turn);
        Button stand = Button.secondary(BTN_STAND, "Stand").withDisabled(!turn);

        boolean canDoubleBtn = turn && canDouble(ts, ts.currentPlayer, getActiveHand(ts, ts.currentPlayer));
        boolean canSplitBtn  = turn && canSplit(ts, ts.currentPlayer, getActiveHand(ts, ts.currentPlayer));

        Button dbl   = Button.primary(BTN_DOUBLE, "Double").withDisabled(!canDoubleBtn);
        Button split = Button.primary(BTN_SPLIT,  "Split").withDisabled(!canSplitBtn);

        return List.of(
                ActionRow.of(join, leave),
                ActionRow.of(hit, stand, dbl, split),
                ActionRow.of(prev, next)
        );
    }

    // =====================================================================
    //  Helpers
    // =====================================================================

    private static HandState getActiveHand(TableState ts, User user) {
        if (user == null) return null;
        List<HandState> hs = ts.playerHands.get(user);
        if (hs == null || hs.isEmpty()) return null;
        int idx = Math.max(0, Math.min(ts.currentHandIndex, hs.size() - 1));
        return hs.get(idx);
    }

    private static boolean canDouble(TableState ts, User user, HandState h) {
        if (user == null || h == null) return false;
        if (ts.phase != Phase.PLAYING || !ts.roundActive || !user.equals(ts.currentPlayer)) return false;
        if (h.cards.size() != 2) return false;
        return balancesById.getOrDefault(user.getId(), START_BALANCE) >= h.bet;
    }

    private static boolean canSplit(TableState ts, User user, HandState h) {
        if (user == null || h == null) return false;
        if (ts.phase != Phase.PLAYING || !ts.roundActive || !user.equals(ts.currentPlayer)) return false;
        if (h.cards.size() != 2) return false;
        if (!sameRank(h.cards.get(0), h.cards.get(1))) return false;
        return balancesById.getOrDefault(user.getId(), START_BALANCE) >= h.bet;
    }

    private static void offerAction(TableState ts, User user, Action action) {
        BlockingQueue<Action> q = ts.actionQueues.get(user);
        if (q != null) q.offer(action);
    }

    private static void clearAllQueues(TableState ts) {
        for (BlockingQueue<Action> q : ts.actionQueues.values()) if (q != null) q.clear();
    }

    private static Action pollAction(TableState ts, User u) {
        BlockingQueue<Action> q = ts.actionQueues.get(u);
        if (q == null) return null;
        try {
            return q.poll(TURN_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
    }

    // -------- Idle close --------
    private static void scheduleIdleClose(TableState ts, GuildMessageChannel channel) {
        long cid = channel.getIdLong();
        ScheduledFuture<?> old = ts.idleCloseTask.remove(cid);
        if (old != null) old.cancel(false);

        ts.idleCloseTask.put(cid, ts.sched.schedule(() -> {
            if (!ts.seated.isEmpty() || !ts.waiting.isEmpty()) return;

            ts.running = false;
            ts.roundActive = false;
            ts.phase = Phase.LOBBY;
            ts.currentPlayer = null;
            ts.currentHandIndex = 0;
            ts.playerHands.clear();
            ts.dealerHand.clear();
            ts.dealerHidden = true;

            Long mid  = ts.tableMessageByChannel.get(cid);
            Long lmid = ts.ledgerMessageByChannel.get(cid);
            Long smid = ts.systemMessageByChannel.get(cid);
            if (mid  != null) channel.retrieveMessageById(mid).queue( m -> m.delete().queue(), f -> {});
            if (lmid != null) channel.retrieveMessageById(lmid).queue(m -> m.delete().queue(), f -> {});
            if (smid != null) channel.retrieveMessageById(smid).queue(m -> m.delete().queue(), f -> {});

            ts.tableMessageByChannel.remove(cid);
            ts.ledgerMessageByChannel.remove(cid);
            ts.systemMessageByChannel.remove(cid);
            ts.creatingTableMessage.remove(cid);
            ts.creatingLedgerMessage.remove(cid);
            ts.creatingSystemMessage.remove(cid);
            ts.lastUpdateByChannel.remove(cid);
            ts.lastSystemTextByChannel.remove(cid);

        }, IDLE_CLOSE_MS, TimeUnit.MILLISECONDS));
    }

    private static void cancelIdleClose(TableState ts, GuildMessageChannel channel) {
        ScheduledFuture<?> old = ts.idleCloseTask.remove(channel.getIdLong());
        if (old != null) old.cancel(false);
    }

    // -------- Ledger popup (separate from the in-game ledger row) --------
    private static void upsertLedgerPopup(GuildMessageChannel channel, LedgerViewState st) {
        st.order = buildLedgerOrder();
        if (st.order.isEmpty()) return;
        st.page = clamp(st.page, 0, st.order.size() - 1);

        String uid = st.order.get(st.page);
        int bal    = balancesById.getOrDefault(uid, START_BALANCE);

        Deque<LedgerEntry> dq = ledgerById.getOrDefault(uid, new ArrayDeque<>());
        int wins = 0, losses = 0, pushes = 0, bj = 0;
        String last = "—";
        for (LedgerEntry e : dq) {
            if (e == null) continue;
            if (e.time != null && !e.time.isBlank()) last = e.time;
            if ("WIN".equals(e.type)) wins++;
            else if ("LOSS".equals(e.type) || "LOSS_BUST".equals(e.type)) losses++;
            else if ("PUSH".equals(e.type)) pushes++;
            else if ("WIN_BLACKJACK".equals(e.type)) bj++;
        }

        StringBuilder recent = new StringBuilder();
        int n = 0;
        for (LedgerEntry e : dq) {
            if (e == null) continue;
            recent.append("• ").append(e.type).append("  ");
            if (e.delta > 0) recent.append("+");
            recent.append("$").append(e.delta).append("  →  $").append(e.balanceAfter).append("\n");
            if (++n >= 10) break;
        }
        if (recent.length() == 0) recent.append("No entries yet.\n");

        MessageEmbed embed = new EmbedBuilder()
                .setTitle("📒 Ledger: <@" + uid + ">  (" + (st.page + 1) + "/" + st.order.size() + ")")
                .setColor(new Color(46, 204, 113))
                .addField("Balance", "$" + bal, true)
                .addField("W/L/P", wins + "/" + losses + "/" + pushes, true)
                .addField("Blackjacks", String.valueOf(bj), true)
                .addField("Recent", recent.toString(), false)
                .setFooter("Last entry: " + last + " • Use Prev/Next to cycle players")
                .build();

        List<ActionRow> rows = List.of(ActionRow.of(
                Button.secondary(BTN_LEDGER_PREV, "Prev"),
                Button.secondary(BTN_LEDGER_NEXT, "Next"),
                Button.danger(BTN_LEDGER_CLOSE, "Close")
        ));

        if (st.messageId == 0L) {
            channel.sendMessageEmbeds(embed).setComponents(rows)
                    .queue(m -> st.messageId = m.getIdLong(), e -> {});
        } else {
            channel.editMessageEmbedsById(st.messageId, embed).setComponents(rows).queue(null, e -> {
                st.messageId = 0L;
                channel.sendMessageEmbeds(embed).setComponents(rows)
                        .queue(m -> st.messageId = m.getIdLong(), err -> {});
            });
        }
    }

    private static List<String> buildLedgerOrder() {
        Set<String> ids = new HashSet<>();
        ids.addAll(ledgerById.keySet());
        ids.addAll(balancesById.keySet());
        List<String> order = new ArrayList<>(ids);
        order.sort((a, b) -> Integer.compare(
                balancesById.getOrDefault(b, START_BALANCE),
                balancesById.getOrDefault(a, START_BALANCE)));
        return order;
    }

    // -------- Card display --------
    private static String formatHand(List<String> cards) { return String.join("  ", cards); }

    private static String formatHandWithValue(List<String> cards) {
        return formatHand(cards) + "  **(" + handValue(cards) + ")**";
    }

    // -------- Card helpers --------
    private static List<String> dealHand() { return new ArrayList<>(List.of(dealCard(), dealCard())); }

    private static String dealCard() {
        String[] ranks = {"2","3","4","5","6","7","8","9","10","J","Q","K","A"};
        String[] suits  = {"♠","♥","♦","♣"};
        return ranks[RNG.nextInt(ranks.length)] + suits[RNG.nextInt(suits.length)];
    }

    private static boolean sameRank(String c1, String c2) { return rankOf(c1).equals(rankOf(c2)); }

    private static String rankOf(String card) { return card.substring(0, card.length() - 1); }

    private static int handValue(List<String> hand) {
        int value = 0, aces = 0;
        for (String card : hand) {
            switch (rankOf(card)) {
                case "J", "Q", "K" -> value += 10;
                case "A"           -> { value += 11; aces++; }
                default            -> value += Integer.parseInt(rankOf(card));
            }
        }
        while (value > 21 && aces > 0) { value -= 10; aces--; }
        return value;
    }

    private static void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
    }

    private static String displayName(GuildMessageChannel channel, User u) {
        if (channel == null || u == null) return "Unknown";
        try {
            var member = channel.getGuild().getMember(u);
            if (member != null && member.getEffectiveName() != null) return member.getEffectiveName();
        } catch (Exception ignored) {}
        return u.getName();
    }

    private static int clamp(int v, int lo, int hi) { if (hi < lo) return lo; return Math.max(lo, Math.min(hi, v)); }

    // =====================================================================
    //  Data classes
    // =====================================================================

    private static class HandState {
        final List<String> cards;
        int bet;
        final AtomicBoolean resolved = new AtomicBoolean(false);
        HandState(List<String> cards, int bet) { this.cards = cards; this.bet = bet; }
    }

    private static class RoundLog {
        final String ts;
        final List<String> dealer;
        final int dealerTotal;
        final List<String> lines;
        RoundLog(String ts, List<String> dealer, int dealerTotal, List<String> lines) {
            this.ts = ts; this.dealer = dealer; this.dealerTotal = dealerTotal; this.lines = lines;
        }
    }

    private static class LedgerEntry {
        public String time;
        final String type;
        final int delta;
        final int balanceAfter;
        LedgerEntry(String ts, String type, int delta, int balanceAfter) {
            this.time = String.valueOf(System.currentTimeMillis());
            this.type = type; this.delta = delta; this.balanceAfter = balanceAfter;
        }
    }

    private static final class LedgerViewState {
        volatile long messageId;
        volatile int page;
        volatile List<String> order = List.of();
    }

    // =====================================================================
    //  Persistence
    // =====================================================================

    private static void appendLedger(String uid, LedgerEntry e) {
        Deque<LedgerEntry> d = ledgerById.computeIfAbsent(uid, k -> new ArrayDeque<>());
        d.addFirst(e);
        while (d.size() > 100) d.removeLast();
    }

    private static void saveState() {
        try {
            Files.createDirectories(SAVE_PATH.getParent());
            String json = "{\n"
                    + "\"balances\":" + mapToJson(balancesById) + ",\n"
                    + "\"wins\":"     + mapToJson(winsById)     + ",\n"
                    + "\"losses\":"   + mapToJson(lossesById)   + "\n}\n";
            Files.writeString(SAVE_PATH, json, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        } catch (Exception ignored) {}
    }

    private static void loadState() {
        if (!Files.exists(SAVE_PATH)) return;
        try {
            String json = Files.readString(SAVE_PATH, StandardCharsets.UTF_8);
            balancesById.putAll(readIntMap(json, "\"balances\""));
            winsById.putAll(readIntMap(json, "\"wins\""));
            lossesById.putAll(readIntMap(json, "\"losses\""));
        } catch (IOException ignored) {}
    }

    private static Map<String, Integer> readIntMap(String json, String key) {
        Map<String, Integer> out = new HashMap<>();
        int idx = json.indexOf(key);
        if (idx < 0) return out;
        int start = json.indexOf("{", idx);
        int end   = json.indexOf("}", start);
        if (start < 0 || end < 0) return out;
        String body = json.substring(start + 1, end).trim();
        if (body.isEmpty()) return out;
        for (String p : body.split(",")) {
            String[] kv = p.split(":");
            if (kv.length != 2) continue;
            String k = kv[0].trim().replace("\"", "");
            try { out.put(k, Integer.parseInt(kv[1].trim())); } catch (Exception ignored) {}
        }
        return out;
    }

    private static String mapToJson(Map<String, Integer> m) {
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (var e : m.entrySet()) {
            if (!first) sb.append(",");
            first = false;
            sb.append("\"").append(escape(e.getKey())).append("\":").append(e.getValue());
        }
        return sb.append("}").toString();
    }

    private static String escape(String s) { return s.replace("\\", "\\\\").replace("\"", "\\\""); }
}
