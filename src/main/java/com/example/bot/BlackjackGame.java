package com.example.bot;

import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.entities.MessageEmbed;
import net.dv8tion.jda.api.entities.User;
import net.dv8tion.jda.api.entities.channel.middleman.GuildMessageChannel;
import net.dv8tion.jda.api.interactions.components.ActionRow;
import net.dv8tion.jda.api.interactions.components.buttons.Button;

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
    private static final int TURN_TIMEOUT_SECONDS = 45;

    // Betting
    private static final int[] BET_OPTIONS = {5, 10, 25, 50, 100, 500, 1000};
    private static final int BUY_IN_AMOUNT = 100;
    private static final int BETTING_SECONDS = 20;
    private static final int DEFAULT_BET = 5;

    // Dealer “animation” delays
    private static final long DEALER_REVEAL_DELAY_MS = 800;
    private static final long DEALER_DRAW_DELAY_MS   = 700;

    // Result pause
    private static final long RESULTS_PAUSE_MS = 8000;

    // Auto-close after 5s idle (no players)
    private static final long IDLE_CLOSE_MS = 5000;

    // Persist stats
    private static final Path SAVE_PATH = Paths.get("config", "blackjack.json");

    private static final ExecutorService GAME_THREAD =
            Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "blackjack-thread");
                t.setDaemon(true);
                return t;
            });

    private static final ScheduledExecutorService SCHED =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "blackjack-scheduler");
                t.setDaemon(true);
                return t;
            });

    private static final Random RNG = new Random();

    // Seating (single shared table)
    private static final List<User> seated = new CopyOnWriteArrayList<>();
    private static final Set<User> waiting = ConcurrentHashMap.newKeySet();

    // Persistent stats keyed by userId
    private static final Map<String, Integer> balancesById = new ConcurrentHashMap<>();
    private static final Map<String, Integer> winsById     = new ConcurrentHashMap<>();
    private static final Map<String, Integer> lossesById   = new ConcurrentHashMap<>();
    private static final Map<String, Deque<LedgerEntry>> ledgerById = new ConcurrentHashMap<>();

    // Runtime action queues keyed by User
    private static final Map<User, BlockingQueue<Action>> actionQueues = new ConcurrentHashMap<>();

    // Round state
    private static final Map<User, List<HandState>> playerHands = new ConcurrentHashMap<>();

    private static volatile boolean running = false;
    private static volatile boolean roundActive = false;
    private static volatile User currentPlayer = null;
    private static volatile int currentHandIndex = 0;

    private static List<String> dealerHand = new ArrayList<>();
    private static volatile boolean dealerHidden = true;

    // One table message per channel
    private static final Map<Long, Long> tableMessageByChannel = new ConcurrentHashMap<>();
    private static final Map<Long, Long> lastUpdateByChannel   = new ConcurrentHashMap<>();
    private static final Set<Long> creatingTableMessage        = ConcurrentHashMap.newKeySet();

    // Idle close task per channel
    private static final Map<Long, ScheduledFuture<?>> idleCloseTask = new ConcurrentHashMap<>();

    // -------- Round history (per channel) --------
    private static final int MAX_ROUND_HISTORY = 20;
    private static final Map<Long, Deque<RoundLog>> roundHistoryByChannel = new ConcurrentHashMap<>();
    private static final Map<Long, Integer> historyPageByChannel = new ConcurrentHashMap<>();

    private static final Map<Long, Long> noticeMessageByChannel = new ConcurrentHashMap<>();
    private static final Map<Long, ScheduledFuture<?>> noticeDeleteTaskByChannel = new ConcurrentHashMap<>();
    private static final long NOTICE_TTL_MS = 6000;

    // -------- Betting state (per round) --------
    private enum Phase { LOBBY, BETTING, PLAYING, RESULTS }
    private static volatile Phase phase = Phase.LOBBY;

    private static final Map<String, Integer> pendingBetById = new ConcurrentHashMap<>();
    private static final Map<String, Integer> roundNetById = new ConcurrentHashMap<>();

    // Button IDs
    public static final String BTN_JOIN   = "bj:join";
    public static final String BTN_LEAVE  = "bj:leave";
    public static final String BTN_HIT    = "bj:hit";
    public static final String BTN_STAND  = "bj:stand";
    public static final String BTN_DOUBLE = "bj:double";
    public static final String BTN_SPLIT  = "bj:split";

    public static final String BTN_BUYIN     = "bj:buyin";
    public static final String BTN_BET_CLEAR = "bj:bet_clear";
    public static final String BTN_BET_PREFIX = "bj:bet:"; // bj:bet:50

    public static final String BTN_HIST_PREV = "bj:hist_prev";
    public static final String BTN_HIST_NEXT = "bj:hist_next";

    static {
        loadState();
    }

    // -------- Compatibility shims --------
    public static void startGame(User user, GuildMessageChannel channel) { join(user, channel); }

    public static void addBalance(User user, int amount, GuildMessageChannel channel) {
        String uid = user.getId();
        balancesById.putIfAbsent(uid, START_BALANCE);
        balancesById.merge(uid, amount, Integer::sum);
        appendLedger(uid, new LedgerEntry(Instant.now().toString(), "ADMIN_FUNDS", amount, balancesById.get(uid)));
        saveState();
        refreshTable(channel, true);
    }

    // -------- Public API --------
    public static void buyIn(User user, GuildMessageChannel channel) {
        String uid = user.getId();
        int bal = balancesById.getOrDefault(uid, START_BALANCE);

        if (bal >= 50) {
            notice(channel, user.getAsMention() + " you have **$" + bal + "** — buy-in is only if you're below **$50**.");
            return;
        }

        balancesById.put(uid, BUY_IN_AMOUNT);
        appendLedger(uid, new LedgerEntry(Instant.now().toString(), "BUY_IN", -(BUY_IN_AMOUNT - bal), BUY_IN_AMOUNT));
        saveState();
        refreshTable(channel, true);

        notice(channel, user.getAsMention() + " buy-in complete. Balance set to **$" + BUY_IN_AMOUNT + "**.");
    }

    public static void clearBet(User user, GuildMessageChannel channel) {
        if (phase != Phase.BETTING) return;
        if (!seated.contains(user)) return;
        pendingBetById.remove(user.getId());
        refreshTable(channel, true);
    }

    public static void placeBet(User user, int amount, GuildMessageChannel channel) {
        if (phase != Phase.BETTING) return;
        if (!seated.contains(user)) return;

        boolean ok = false;
        for (int b : BET_OPTIONS) if (b == amount) { ok = true; break; }
        if (!ok) return;

        String uid = user.getId();
        int bal = balancesById.getOrDefault(uid, START_BALANCE);

        if (bal < amount) {
            notice(channel, user.getAsMention() + " not enough funds for $" + amount + ".");
            return;
        }

        pendingBetById.put(uid, amount);
        refreshTable(channel, true);
    }

    public static void join(User user, GuildMessageChannel channel) {
        cancelIdleClose(channel);

        String uid = user.getId();
        balancesById.putIfAbsent(uid, START_BALANCE);
        winsById.putIfAbsent(uid, 0);
        lossesById.putIfAbsent(uid, 0);
        ledgerById.putIfAbsent(uid, new ArrayDeque<>());

        actionQueues.putIfAbsent(user, new ArrayBlockingQueue<>(16));

        if (seated.contains(user) || waiting.contains(user)) {
            notice(channel, user.getAsMention() + " already seated.");
            ensureRunning(channel);
            refreshTable(channel, true);
            return;
        }

        // If we're in betting, let them join THIS round (and give them a default bet so round can start)
        if (phase == Phase.BETTING) {
            seated.add(user);
            pendingBetById.putIfAbsent(uid, DEFAULT_BET);
        } else if (roundActive) {
            waiting.add(user);
        } else {
            seated.add(user);
        }

        ensureRunning(channel);
        refreshTable(channel, true);
    }

    public static void leave(User user, GuildMessageChannel channel) {
        seated.remove(user);
        waiting.remove(user);
        actionQueues.remove(user);
        playerHands.remove(user);
        pendingBetById.remove(user.getId());

        if (user.equals(currentPlayer)) {
            offerAction(user, Action.STAND);
        }

        if (seated.isEmpty() && waiting.isEmpty()) {
            currentPlayer = null;
            currentHandIndex = 0;
            scheduleIdleClose(channel);
            refreshTable(channel, true);
            return;
        }

        refreshTable(channel, true);
    }

    public static void action(User user, Action action) {
        if (phase != Phase.PLAYING) return;
        if (!roundActive) return;
        if (!user.equals(currentPlayer)) return;
        offerAction(user, action);
    }

    public static void showLedger(User user, GuildMessageChannel channel) {
        String uid = user.getId();
        int bal = balancesById.getOrDefault(uid, START_BALANCE);
        int w = winsById.getOrDefault(uid, 0);
        int l = lossesById.getOrDefault(uid, 0);

        StringBuilder sb = new StringBuilder();
        sb.append(user.getAsMention())
                .append(" 🧾 **Ledger**\n")
                .append("Balance: **$").append(bal).append("** | Wins: **").append(w).append("** | Losses: **").append(l).append("**\n\n");

        Deque<LedgerEntry> entries = ledgerById.get(uid);
        if (entries == null || entries.isEmpty()) {
            sb.append("_No history yet._");
        } else {
            int shown = 0;
            for (LedgerEntry e : entries) {
                sb.append("• ").append(e.ts).append(" — ").append(e.type)
                        .append(" (").append(e.delta >= 0 ? "+" : "").append(e.delta).append(") → $").append(e.balanceAfter)
                        .append("\n");
                if (++shown >= 15) break;
            }
            if (entries.size() > 15) sb.append("\n_...and ").append(entries.size() - 15).append(" more_");
        }

        channel.sendMessage(sb.toString());
    }

    public static void historyPrev(GuildMessageChannel channel) {
        long cid = channel.getIdLong();
        Deque<RoundLog> hist = roundHistoryByChannel.getOrDefault(cid, new ArrayDeque<>());
        if (hist.isEmpty()) return;

        int page = historyPageByChannel.getOrDefault(cid, 0);
        historyPageByChannel.put(cid, Math.min(hist.size() - 1, page + 1));
        refreshTable(channel, true);
    }

    public static void historyNext(GuildMessageChannel channel) {
        long cid = channel.getIdLong();
        int page = historyPageByChannel.getOrDefault(cid, 0);
        historyPageByChannel.put(cid, Math.max(0, page - 1));
        refreshTable(channel, true);
    }

    // -------- Game loop --------
    private static void ensureRunning(GuildMessageChannel channel) {
        if (!running) {
            running = true;
            GAME_THREAD.submit(() -> gameLoop(channel));
        }
    }

    private static void gameLoop(GuildMessageChannel channel) {
        while (running) {
            if (seated.isEmpty()) {
                sleep(100);
                continue;
            }
            playRound(channel);
        }
    }

    private static void playRound(GuildMessageChannel channel) {
        roundActive = true;
        phase = Phase.BETTING;

        seated.addAll(waiting);
        waiting.clear();

        playerHands.clear();
        dealerHand.clear();
        dealerHidden = true;
        currentPlayer = null;
        currentHandIndex = 0;

        pendingBetById.clear();
        roundNetById.clear();

        // Betting UI (NO dummy hands)
        refreshTable(channel, true);

        long end = System.currentTimeMillis() + (BETTING_SECONDS * 1000L);
        while (System.currentTimeMillis() < end && running && !seated.isEmpty()) {
            boolean allBet = true;
            for (User u : seated) {
                if (!pendingBetById.containsKey(u.getId())) { allBet = false; break; }
            }
            if (allBet) break;
            sleep(200);
        }

        if (seated.isEmpty()) {
            phase = Phase.LOBBY;
            roundActive = false;
            refreshTable(channel, true);
            return;
        }

        // Deal phase
        phase = Phase.PLAYING;

        dealerHand = dealHand();
        dealerHidden = true;

        for (User u : new ArrayList<>(seated)) {
            String uid = u.getId();
            int bet = pendingBetById.getOrDefault(uid, DEFAULT_BET);
            int bal = balancesById.getOrDefault(uid, START_BALANCE);

            if (bal < bet) { seated.remove(u); continue; }

            balancesById.put(uid, bal - bet);
            appendLedger(uid, new LedgerEntry(Instant.now().toString(), "BET", -bet, balancesById.get(uid)));

            List<HandState> hs = new ArrayList<>();
            hs.add(new HandState(dealHand(), bet));
            playerHands.put(u, hs);
        }

        saveState();
        refreshTable(channel, true);

        for (User u : new ArrayList<>(seated)) {
            if (!running) break;
            takePlayerTurns(u, channel);
        }

        // Dealer phase
        currentPlayer = null;
        currentHandIndex = 0;

        dealerHidden = false;
        refreshTable(channel, true);
        sleep(DEALER_REVEAL_DELAY_MS);

        while (handValue(dealerHand) < 17) {
            dealerHand.add(dealCard());
            refreshTable(channel, true);
            sleep(DEALER_DRAW_DELAY_MS);
        }

        settleRound(channel);

        // Show results screen (with net +/-)
        phase = Phase.RESULTS;
        showRoundResultsScreen(channel);
        sleep(RESULTS_PAUSE_MS);

        currentPlayer = null;
        currentHandIndex = 0;
        roundActive = false;
        phase = Phase.LOBBY;
        refreshTable(channel, true);

        saveState();

        if (seated.isEmpty() && waiting.isEmpty()) {
            scheduleIdleClose(channel);
        }

        sleep(800);
    }

    private static void takePlayerTurns(User user, GuildMessageChannel channel) {
        List<HandState> hands = playerHands.get(user);
        if (hands == null) return;
        if (!seated.contains(user)) return;

        for (int i = 0; i < hands.size(); i++) {
            currentPlayer = user;
            currentHandIndex = i;

            HandState h = hands.get(i);
            refreshTable(channel, true);

            // Natural blackjack: pays 3:2 (net +1.5x bet)
            if (i == 0 && hands.size() == 1 && h.cards.size() == 2 && handValue(h.cards) == 21) {
                String uid = user.getId();
                int payout = (int) Math.round(h.bet * 2.5); // bet already deducted
                balancesById.merge(uid, payout, Integer::sum);
                winsById.merge(uid, 1, Integer::sum);
                appendLedger(uid, new LedgerEntry(Instant.now().toString(), "WIN_BLACKJACK", payout, balancesById.get(uid)));
                roundNetById.merge(uid, (int) Math.round(h.bet * 1.5), Integer::sum);

                h.resolved.set(true);
                refreshTable(channel, true);
                continue;
            }

            while (true) {
                if (!running || !seated.contains(user)) return;

                int total = handValue(h.cards);
                if (total > 21) {
                    h.resolved.set(true);
                    refreshTable(channel, true);
                    break;
                }

                Action act = pollAction(user);
                if (act == null) act = Action.STAND;

                switch (act) {
                    case HIT -> {
                        h.cards.add(dealCard());
                        refreshTable(channel, true); // force update immediately
                        // optional: tiny pause so Discord/JDA rate limits don’t visually “skip”
                        sleep(150);
                        continue;
                    }
                    case STAND -> {
                        h.resolved.set(true);
                        refreshTable(channel, true);
                        break;
                    }
                    case DOUBLE -> {
                        if (!canDouble(user, h)) continue;

                        String uid = user.getId();
                        balancesById.put(uid, balancesById.get(uid) - h.bet);
                        appendLedger(uid, new LedgerEntry(Instant.now().toString(), "DOUBLE_BET", -h.bet, balancesById.get(uid)));
                        h.bet *= 2;
                        h.cards.add(dealCard());
                        h.resolved.set(true);
                        refreshTable(channel, true);
                        break;
                    }
                    case SPLIT -> {
                        if (!canSplit(user, h)) continue;

                        String uid = user.getId();
                        balancesById.put(uid, balancesById.get(uid) - h.bet);
                        appendLedger(uid, new LedgerEntry(Instant.now().toString(), "SPLIT_BET", -h.bet, balancesById.get(uid)));

                        String c1 = h.cards.get(0);
                        String c2 = h.cards.get(1);

                        HandState h1 = new HandState(new ArrayList<>(List.of(c1, dealCard())), h.bet);
                        HandState h2 = new HandState(new ArrayList<>(List.of(c2, dealCard())), h.bet);

                        hands.set(i, h1);
                        hands.add(i + 1, h2);

                        h = hands.get(i);
                        refreshTable(channel, true);
                        continue;
                    }
                }
                break;
            }
        }
    }

    private static void settleRound(GuildMessageChannel channel) {
        int dealerTotal = handValue(dealerHand);

        List<String> logLines = new ArrayList<>();

        for (User u : new ArrayList<>(seated)) {
            String uid = u.getId();
            List<HandState> hs = playerHands.get(u);
            if (hs == null) continue;

            for (int i = 0; i < hs.size(); i++) {
                HandState h = hs.get(i);
                int t = handValue(h.cards);

                String who = u.getName() + (hs.size() > 1 ? " (H" + (i + 1) + ")" : "");
                String outcome;

                // per-hand net relative to the bet already deducted
                int net;
                if (t > 21) net = -h.bet;
                else if (dealerTotal > 21 || t > dealerTotal) net = +h.bet;
                else if (t == dealerTotal) net = 0;
                else net = -h.bet;

                roundNetById.merge(uid, net, Integer::sum);

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

                logLines.add("• **" + who + "**: " + h.cards + " (**" + t + "**), Bet **$" + h.bet + "** → **" + outcome + "**");
            }
        }

        long cid = channel.getIdLong();
        Deque<RoundLog> hist = roundHistoryByChannel.computeIfAbsent(cid, k -> new ArrayDeque<>());
        hist.addFirst(new RoundLog(Instant.now().toString(), new ArrayList<>(dealerHand), dealerTotal, logLines));
        while (hist.size() > MAX_ROUND_HISTORY) hist.removeLast();
        historyPageByChannel.put(cid, 0);

        saveState();
        refreshTable(channel, true);
    }

    private static void showRoundResultsScreen(GuildMessageChannel channel) {
        int dealerTotal = handValue(dealerHand);

        StringBuilder winners = new StringBuilder();
        StringBuilder losers  = new StringBuilder();
        StringBuilder pushes  = new StringBuilder();

        for (User u : new ArrayList<>(seated)) {
            List<HandState> hs = playerHands.get(u);
            if (hs == null) continue;

            int rn = roundNetById.getOrDefault(u.getId(), 0);
            String rnStr = (rn >= 0 ? " 🟢 **+" : " 🔴 **") + rn + "**";

            for (int i = 0; i < hs.size(); i++) {
                HandState h = hs.get(i);
                int t = handValue(h.cards);

                String label = u.getAsMention() + (hs.size() > 1 ? " (H" + (i + 1) + ")" : "");
                String line = "• " + label + ": " + h.cards + " (**" + t + "**)" + rnStr + "\n";

                if (t > 21) losers.append(line);
                else if (dealerTotal > 21 || t > dealerTotal) winners.append(line);
                else if (t == dealerTotal) pushes.append(line);
                else losers.append(line);
            }
        }

        EmbedBuilder eb = new EmbedBuilder()
                .setTitle("📌 ROUND RESULTS")
                .setColor(new Color(0x3498db));

        eb.addField("🟢 WINNERS", winners.length() == 0 ? "> _None this round_" : ">>> " + winners, false);
        eb.addField("🔵 PUSH",    pushes.length() == 0 ? "> _None_" : ">>> " + pushes, false);
        eb.addField("🔴 LOSERS",  losers.length() == 0 ? "> _None_" : ">>> " + losers, false);

        eb.addField("🧑‍⚖️ DEALER", ">>> **" + dealerHand + "**  (**" + dealerTotal + "**)", false);
        eb.setFooter("Next round starts shortly…");

        upsertTableMessage(channel, eb.build(), buildButtons(channel.getIdLong()), true);
    }

    // -------- UI --------
    private static void refreshTable(GuildMessageChannel channel) { refreshTable(channel, false); }

    private static void refreshTable(GuildMessageChannel channel, boolean force) {
        long cid = channel.getIdLong();
        MessageEmbed embed = buildEmbed(cid);
        List<ActionRow> rows = buildButtons(cid);
        upsertTableMessage(channel, embed, rows, force);
    }

    private static void upsertTableMessage(GuildMessageChannel channel, MessageEmbed embed, List<ActionRow> rows, boolean force) {
        long cid = channel.getIdLong();

        long now = System.currentTimeMillis();
        if (!force && now - lastUpdateByChannel.getOrDefault(cid, 0L) < 350) return;
        lastUpdateByChannel.put(cid, now);

        Long mid = tableMessageByChannel.get(cid);

        if (mid == null) {
            if (seated.isEmpty() && waiting.isEmpty() && !roundActive) return;
            if (!creatingTableMessage.add(cid)) return;

            channel.sendMessageEmbeds(embed).setComponents(rows).queue(m -> {
                tableMessageByChannel.put(cid, m.getIdLong());
                creatingTableMessage.remove(cid);
            }, fail -> creatingTableMessage.remove(cid));

            return;
        }

        channel.retrieveMessageById(mid).queue(
                m -> m.editMessageEmbeds(embed).setComponents(rows).queue(),
                f -> {
                    tableMessageByChannel.remove(cid);
                    creatingTableMessage.remove(cid);
                    upsertTableMessage(channel, embed, rows, true);
                }
        );
    }

    private static MessageEmbed buildEmbed(long cid) {
        EmbedBuilder eb = new EmbedBuilder();
        eb.setTitle("🃏 Blackjack");

        if (phase == Phase.BETTING) eb.setColor(new Color(0x9b59b6));
        else if (phase == Phase.PLAYING) eb.setColor(new Color(0xf1c40f));
        else if (phase == Phase.RESULTS) eb.setColor(new Color(0x3498db));
        else eb.setColor(new Color(0x2ecc71));

        // Dealer
        String dealerLine;
        if (phase == Phase.BETTING) {
            dealerLine = "_Waiting for bets…_";
        } else if (dealerHand == null || dealerHand.isEmpty()) {
            dealerLine = "—";
        } else if (roundActive && dealerHidden) {
            dealerLine = "**" + dealerHand.get(0) + "**, [hidden]";
        } else {
            dealerLine = dealerHand + "  **(" + handValue(dealerHand) + ")**";
        }
        eb.addField("Dealer", dealerLine, false);

        // Players
        StringBuilder p = new StringBuilder();
        for (User u : seated) {
            String uid = u.getId();
            List<HandState> hs = playerHands.get(u);

            boolean isTurn = (phase == Phase.PLAYING) && u.equals(currentPlayer);
            p.append(u.getAsMention()).append(isTurn ? "  ▶️" : "").append("\n");

            if (phase == Phase.BETTING) {
                Integer bet = pendingBetById.get(uid);
                p.append("• Bet: ").append(bet == null ? "_not set_" : "**$" + bet + "**").append("\n");
            } else if (hs != null) {
                for (int i = 0; i < hs.size(); i++) {
                    HandState h = hs.get(i);
                    int total = handValue(h.cards);
                    boolean activeHand = isTurn && (i == currentHandIndex);

                    p.append("• Hand ").append(i + 1).append(activeHand ? " **(active)**" : "")
                            .append(": ").append(h.cards)
                            .append("  **(").append(total).append(")**")
                            .append("  Bet: **$").append(h.bet).append("**")
                            .append(total > 21 ? " 💥" : "")
                            .append("\n");
                }
            } else {
                p.append("• (waiting next round)\n");
            }

            int bal = balancesById.getOrDefault(uid, START_BALANCE);
            int w = winsById.getOrDefault(uid, 0);
            int l = lossesById.getOrDefault(uid, 0);
            p.append("Balance: **$").append(bal).append("** | Wins: **").append(w).append("** | Losses: **").append(l).append("**\n\n");
        }
        if (p.length() == 0) p.append("—");
        eb.addField("Players", p.toString(), false);

        // History panel
        Deque<RoundLog> hist = roundHistoryByChannel.getOrDefault(cid, new ArrayDeque<>());
        int page = historyPageByChannel.getOrDefault(cid, 0);

        String historyText;
        if (hist.isEmpty()) {
            historyText = "_No rounds yet._";
        } else {
            page = Math.max(0, Math.min(page, hist.size() - 1));
            RoundLog rl = hist.stream().skip(page).findFirst().orElse(null);

            if (rl == null) {
                historyText = "_No rounds yet._";
            } else {
                StringBuilder hs = new StringBuilder();
                hs.append("**Round ").append(page + 1).append("/").append(hist.size()).append("**\n");
                hs.append("Dealer: ").append(rl.dealer).append(" (**").append(rl.dealerTotal).append("**)\n");
                int show = Math.min(10, rl.lines.size());
                for (int i = 0; i < show; i++) hs.append(rl.lines.get(i)).append("\n");
                if (rl.lines.size() > show) hs.append("_...and ").append(rl.lines.size() - show).append(" more_");
                historyText = hs.toString();
            }
        }
        eb.addField("Last Round History", historyText, false);

        // Footer
        if (phase == Phase.BETTING) {
            eb.setFooter("Betting phase — pick a chip. Round starts soon.");
        } else if (roundActive && currentPlayer != null) {
            eb.setFooter("Turn: " + currentPlayer.getName() + " | Buttons or !hit/!stand/!double/!split");
        } else if (seated.isEmpty() && waiting.isEmpty()) {
            eb.setFooter("Table empty — auto-closes in 5s.");
        } else {
            eb.setFooter("Join to play.");
        }

        return eb.build();
    }

    private static List<ActionRow> buildButtons(long cid) {
        Button join = Button.success(BTN_JOIN, "Join");
        Button leave = Button.danger(BTN_LEAVE, "Leave");

        // history
        Deque<RoundLog> hist = roundHistoryByChannel.getOrDefault(cid, new ArrayDeque<>());
        int size = hist.size();
        int page = historyPageByChannel.getOrDefault(cid, 0);
        Button prev = Button.secondary(BTN_HIST_PREV, "Prev").withDisabled(size == 0 || page >= size - 1);
        Button next = Button.secondary(BTN_HIST_NEXT, "Next").withDisabled(size == 0 || page <= 0);

        // ===== BETTING UI =====
        if (phase == Phase.BETTING) {
            List<Button> betButtons = new ArrayList<>();
            for (int b : BET_OPTIONS) {
                betButtons.add(Button.secondary(BTN_BET_PREFIX + b, "$" + b));
            }

            List<Button> row1 = betButtons.subList(0, Math.min(5, betButtons.size()));
            List<Button> row2 = betButtons.size() > 5 ? betButtons.subList(5, betButtons.size()) : List.of();

            Button clear = Button.secondary(BTN_BET_CLEAR, "Clear Bet");
            Button buyIn  = Button.success(BTN_BUYIN, "Buy In $100");

            List<ActionRow> rows = new ArrayList<>();
            rows.add(ActionRow.of(row1));
            if (!row2.isEmpty()) rows.add(ActionRow.of(row2));
            rows.add(ActionRow.of(clear, buyIn, leave));
            rows.add(ActionRow.of(prev, next));
            return rows;
        }

        // ===== PLAYING UI =====
        boolean turn = (phase == Phase.PLAYING) && roundActive && currentPlayer != null;

        Button hit = Button.primary(BTN_HIT, "Hit").withDisabled(!turn);
        Button stand = Button.secondary(BTN_STAND, "Stand").withDisabled(!turn);

        boolean canDoubleNow = turn && canDouble(currentPlayer, getActiveHand(currentPlayer));
        boolean canSplitNow  = turn && canSplit(currentPlayer, getActiveHand(currentPlayer));

        Button dbl = Button.primary(BTN_DOUBLE, "Double").withDisabled(!canDoubleNow);
        Button split = Button.primary(BTN_SPLIT, "Split").withDisabled(!canSplitNow);

        return List.of(
                ActionRow.of(join, leave),
                ActionRow.of(hit, stand, dbl, split),
                ActionRow.of(prev, next)
        );
    }

    private static HandState getActiveHand(User user) {
        if (user == null) return null;
        List<HandState> hs = playerHands.get(user);
        if (hs == null || hs.isEmpty()) return null;
        int idx = Math.max(0, Math.min(currentHandIndex, hs.size() - 1));
        return hs.get(idx);
    }

    private static boolean canDouble(User user, HandState h) {
        if (user == null || h == null) return false;
        if (phase != Phase.PLAYING) return false;
        if (!roundActive) return false;
        if (!user.equals(currentPlayer)) return false;
        if (h.cards.size() != 2) return false;
        int bal = balancesById.getOrDefault(user.getId(), START_BALANCE);
        return bal >= h.bet;
    }

    private static boolean canSplit(User user, HandState h) {
        if (user == null || h == null) return false;
        if (phase != Phase.PLAYING) return false;
        if (!roundActive) return false;
        if (!user.equals(currentPlayer)) return false;
        if (h.cards.size() != 2) return false;
        if (!sameRank(h.cards.get(0), h.cards.get(1))) return false;
        int bal = balancesById.getOrDefault(user.getId(), START_BALANCE);
        return bal >= h.bet;
    }

    private static void offerAction(User user, Action action) {
        BlockingQueue<Action> q = actionQueues.get(user);
        if (q != null) q.offer(action);
    }

    private static Action pollAction(User u) {
        BlockingQueue<Action> q = actionQueues.get(u);
        if (q == null) return null;
        try {
            return q.poll(TURN_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
    }

    private static void notice(GuildMessageChannel channel, String text) {
        long cid = channel.getIdLong();

        // cancel prior delete timer
        ScheduledFuture<?> oldTask = noticeDeleteTaskByChannel.remove(cid);
        if (oldTask != null) oldTask.cancel(false);

        Long mid = noticeMessageByChannel.get(cid);

        Runnable armDelete = () -> {
            ScheduledFuture<?> t = SCHED.schedule(() -> {
                Long m = noticeMessageByChannel.remove(cid);
                if (m == null) return;
                channel.retrieveMessageById(m).queue(
                        msg -> msg.delete().queue(),
                        fail -> { }
                );
            }, NOTICE_TTL_MS, TimeUnit.MILLISECONDS);
            noticeDeleteTaskByChannel.put(cid, t);
        };

        if (mid == null) {
            channel.sendMessage(text).queue(m -> {
                noticeMessageByChannel.put(cid, m.getIdLong());
                armDelete.run();
            }, fail -> armDelete.run());
            return;
        }

        channel.retrieveMessageById(mid).queue(
                m -> m.editMessage(text).queue(v -> armDelete.run(), fail -> armDelete.run()),
                fail -> {
                    noticeMessageByChannel.remove(cid);
                    channel.sendMessage(text).queue(m2 -> {
                        noticeMessageByChannel.put(cid, m2.getIdLong());
                        armDelete.run();
                    }, f2 -> armDelete.run());
                }
        );
    }


    // -------- Idle close --------
    private static void scheduleIdleClose(GuildMessageChannel channel) {
        long cid = channel.getIdLong();

        ScheduledFuture<?> old = idleCloseTask.remove(cid);
        if (old != null) old.cancel(false);

        idleCloseTask.put(cid, SCHED.schedule(() -> {
            if (!seated.isEmpty() || !waiting.isEmpty()) return;

            running = false;
            roundActive = false;
            phase = Phase.LOBBY;
            currentPlayer = null;
            currentHandIndex = 0;
            playerHands.clear();
            dealerHand.clear();
            dealerHidden = true;

            Long mid = tableMessageByChannel.get(cid);
            if (mid != null) {
                channel.retrieveMessageById(mid).queue(
                        msg -> msg.delete().queue(),
                        fail -> { }
                );
            }

            tableMessageByChannel.remove(cid);
            creatingTableMessage.remove(cid);
            lastUpdateByChannel.remove(cid);
        }, IDLE_CLOSE_MS, TimeUnit.MILLISECONDS));
    }

    private static void cancelIdleClose(GuildMessageChannel channel) {
        long cid = channel.getIdLong();
        ScheduledFuture<?> old = idleCloseTask.remove(cid);
        if (old != null) old.cancel(false);
    }

    // -------- Card helpers --------
    private static List<String> dealHand() {
        return new ArrayList<>(List.of(dealCard(), dealCard()));
    }

    private static String dealCard() {
        String[] ranks = {"2","3","4","5","6","7","8","9","10","J","Q","K","A"};
        String[] suits = {"♠","♥","♦","♣"};
        return ranks[RNG.nextInt(ranks.length)] + suits[RNG.nextInt(suits.length)];
    }

    private static boolean sameRank(String c1, String c2) {
        return rankOf(c1).equals(rankOf(c2));
    }

    private static String rankOf(String card) {
        return card.substring(0, card.length() - 1);
    }

    private static int handValue(List<String> hand) {
        int value = 0;
        int aces = 0;

        for (String card : hand) {
            String rank = rankOf(card);
            switch (rank) {
                case "J", "Q", "K" -> value += 10;
                case "A" -> { value += 11; aces++; }
                default -> value += Integer.parseInt(rank);
            }
        }

        while (value > 21 && aces > 0) {
            value -= 10;
            aces--;
        }
        return value;
    }

    private static void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
    }

    // -------- HandState --------
    private static class HandState {
        final List<String> cards;
        int bet;
        final AtomicBoolean resolved = new AtomicBoolean(false);

        HandState(List<String> cards, int bet) {
            this.cards = cards;
            this.bet = bet;
        }
    }

    // -------- History model --------
    private static class RoundLog {
        final String ts;
        final List<String> dealer;
        final int dealerTotal;
        final List<String> lines;

        RoundLog(String ts, List<String> dealer, int dealerTotal, List<String> lines) {
            this.ts = ts;
            this.dealer = dealer;
            this.dealerTotal = dealerTotal;
            this.lines = lines;
        }
    }

    // -------- Ledger persistence --------
    private static class LedgerEntry {
        final String ts;
        final String type;
        final int delta;
        final int balanceAfter;

        LedgerEntry(String ts, String type, int delta, int balanceAfter) {
            this.ts = ts;
            this.type = type;
            this.delta = delta;
            this.balanceAfter = balanceAfter;
        }
    }

    private static void appendLedger(String uid, LedgerEntry e) {
        Deque<LedgerEntry> d = ledgerById.computeIfAbsent(uid, k -> new ArrayDeque<>());
        d.addFirst(e);
        while (d.size() > 100) d.removeLast();
    }

    private static void saveState() {
        try {
            Files.createDirectories(SAVE_PATH.getParent());

            StringBuilder sb = new StringBuilder();
            sb.append("{\n");
            sb.append("\"balances\":").append(mapToJson(balancesById)).append(",\n");
            sb.append("\"wins\":").append(mapToJson(winsById)).append(",\n");
            sb.append("\"losses\":").append(mapToJson(lossesById)).append(",\n");

            sb.append("\"ledger\":{\n");
            boolean firstUser = true;
            for (var ent : ledgerById.entrySet()) {
                if (!firstUser) sb.append(",\n");
                firstUser = false;
                sb.append("\"").append(escape(ent.getKey())).append("\":[");
                boolean first = true;
                for (LedgerEntry le : ent.getValue()) {
                    if (!first) sb.append(",");
                    first = false;
                    sb.append("{\"ts\":\"").append(escape(le.ts)).append("\",")
                            .append("\"type\":\"").append(escape(le.type)).append("\",")
                            .append("\"delta\":").append(le.delta).append(",")
                            .append("\"bal\":").append(le.balanceAfter).append("}");
                }
                sb.append("]");
            }
            sb.append("\n}\n");
            sb.append("}\n");

            Files.writeString(SAVE_PATH, sb.toString(), StandardCharsets.UTF_8,
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

    private static Map<String,Integer> readIntMap(String json, String key) {
        Map<String,Integer> out = new HashMap<>();
        int idx = json.indexOf(key);
        if (idx < 0) return out;
        int start = json.indexOf("{", idx);
        int end = json.indexOf("}", start);
        if (start < 0 || end < 0) return out;

        String body = json.substring(start + 1, end).trim();
        if (body.isEmpty()) return out;

        String[] parts = body.split(",");
        for (String p : parts) {
            String[] kv = p.split(":");
            if (kv.length != 2) continue;
            String k = kv[0].trim().replace("\"", "");
            String v = kv[1].trim();
            try { out.put(k, Integer.parseInt(v)); } catch (Exception ignored) {}
        }
        return out;
    }

    private static String mapToJson(Map<String,Integer> m) {
        StringBuilder sb = new StringBuilder();
        sb.append("{");
        boolean first = true;
        for (var e : m.entrySet()) {
            if (!first) sb.append(",");
            first = false;
            sb.append("\"").append(escape(e.getKey())).append("\":").append(e.getValue());
        }
        sb.append("}");
        return sb.toString();
    }

    private static String escape(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
