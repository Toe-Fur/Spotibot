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

    public enum Action { HIT, STAND, DOUBLE, SPLIT }

    private static final int START_BALANCE = 100;
    private static final int MIN_BET = 5;
    private static final int TURN_TIMEOUT_SECONDS = 45;

    private static final long DEALER_REVEAL_DELAY_MS = 800;
    private static final long DEALER_DRAW_DELAY_MS   = 700;

    // Auto-close after 5s idle (no players)
    private static final long IDLE_CLOSE_MS = 5000;

    // Persist stats (balances/wins/losses + ledger entries we append)
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

    // Seating (single shared table behavior; you already used one message per channel)
    private static final List<User> seated = new CopyOnWriteArrayList<>();
    private static final Set<User> waiting = ConcurrentHashMap.newKeySet();

    // Persistent stats keyed by userId
    private static final Map<String, Integer> balancesById = new ConcurrentHashMap<>();
    private static final Map<String, Integer> winsById     = new ConcurrentHashMap<>();
    private static final Map<String, Integer> lossesById   = new ConcurrentHashMap<>();
    private static final Map<String, Deque<LedgerEntry>> ledgerById = new ConcurrentHashMap<>();

    // Runtime queues keyed by User
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

    // Button IDs
    public static final String BTN_JOIN   = "bj:join";
    public static final String BTN_LEAVE  = "bj:leave";
    public static final String BTN_HIT    = "bj:hit";
    public static final String BTN_STAND  = "bj:stand";
    public static final String BTN_DOUBLE = "bj:double";
    public static final String BTN_SPLIT  = "bj:split";
    public static final String BTN_HIST_PREV = "bj:hist_prev";
    public static final String BTN_HIST_NEXT = "bj:hist_next";

    static {
        loadState();
    }

    // ---- Compatibility shims (keep your older CommandHandler happy) ----
    public static void startGame(User user, GuildMessageChannel channel) { join(user, channel); }

    public static void addBalance(User user, int amount, GuildMessageChannel channel) {
        String uid = user.getId();
        balancesById.putIfAbsent(uid, START_BALANCE);
        balancesById.merge(uid, amount, Integer::sum);
        appendLedger(uid, new LedgerEntry(Instant.now().toString(), "ADMIN_FUNDS", amount, balancesById.get(uid)));
        saveState();
        refreshTable(channel, true);
    }

    // ---- Public API ----
    public static void join(User user, GuildMessageChannel channel) {
        cancelIdleClose(channel);

        String uid = user.getId();
        balancesById.putIfAbsent(uid, START_BALANCE);
        winsById.putIfAbsent(uid, 0);
        lossesById.putIfAbsent(uid, 0);
        ledgerById.putIfAbsent(uid, new ArrayDeque<>());

        actionQueues.putIfAbsent(user, new ArrayBlockingQueue<>(16));

        if (seated.contains(user) || waiting.contains(user)) {
            ensureRunning(channel);
            refreshTable(channel, true);
            return;
        }

        if (roundActive) waiting.add(user);
        else seated.add(user);

        ensureRunning(channel);

        // Force UI update immediately (fixes “had to leave/rejoin”)
        refreshTable(channel, true);
    }

    public static void leave(User user, GuildMessageChannel channel) {
        seated.remove(user);
        waiting.remove(user);
        actionQueues.remove(user);
        playerHands.remove(user);

        if (user.equals(currentPlayer)) {
            offerAction(user, Action.STAND);
        }

        // If table is empty, don't post a new empty message; just update existing (if any)
        if (seated.isEmpty() && waiting.isEmpty()) {
            // Stop showing “Turn: …” nonsense
            currentPlayer = null;
            currentHandIndex = 0;
            scheduleIdleClose(channel); // will delete the message after 5s
            refreshTable(channel, true); // edits existing message if present
            return;
        }

        refreshTable(channel, true);
    }

    private static void offerAction(User user, Action action) {
        BlockingQueue<Action> q = actionQueues.get(user);
        if (q != null) q.offer(action);
    }

    public static void action(User user, Action action) {
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

        channel.sendMessage(sb.toString()).queue();
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

    // ---- Game loop ----
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

        seated.addAll(waiting);
        waiting.clear();

        playerHands.clear();
        dealerHand = dealHand();
        dealerHidden = true;
        currentPlayer = null;
        currentHandIndex = 0;

        // Bets + deal
        for (User u : new ArrayList<>(seated)) {
            String uid = u.getId();
            int bal = balancesById.getOrDefault(uid, START_BALANCE);

            if (bal < MIN_BET) {
                seated.remove(u);
                continue;
            }

            balancesById.put(uid, bal - MIN_BET);
            appendLedger(uid, new LedgerEntry(Instant.now().toString(), "BET", -MIN_BET, balancesById.get(uid)));

            List<HandState> hs = new ArrayList<>();
            hs.add(new HandState(dealHand(), MIN_BET));
            playerHands.put(u, hs);
        }

        saveState();
        refreshTable(channel, true);

        // Player turns
        for (User u : new ArrayList<>(seated)) {
            if (!running) break;
            takePlayerTurns(u, channel);
        }

        // Dealer phase: disable action buttons while dealer animates
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

        showRoundResultsScreen(channel);
        sleep(8000); // 4 seconds to read

        currentPlayer = null;
        currentHandIndex = 0;
        roundActive = false;
        refreshTable(channel, true);

        saveState();

        // If everyone left during the round, schedule close
        if (seated.isEmpty() && waiting.isEmpty()) {
            scheduleIdleClose(channel);
        }

        sleep(800);
    }

    private static void showRoundResultsScreen(GuildMessageChannel channel) {
        int dealerTotal = handValue(dealerHand);

        StringBuilder winners = new StringBuilder();
        StringBuilder losers  = new StringBuilder();
        StringBuilder pushes  = new StringBuilder();

        for (User u : new ArrayList<>(seated)) {
            List<HandState> hs = playerHands.get(u);
            if (hs == null) continue;

            for (int i = 0; i < hs.size(); i++) {
                HandState h = hs.get(i);
                int t = handValue(h.cards);

                String label = u.getAsMention() + (hs.size() > 1 ? " (H" + (i + 1) + ")" : "");
                String line = "• " + label + ": " + h.cards + " (**" + t + "**)\n";

                if (t > 21) {
                    losers.append(line);
                } else if (dealerTotal > 21 || t > dealerTotal) {
                    winners.append(line);
                } else if (t == dealerTotal) {
                    pushes.append(line);
                } else {
                    losers.append(line);
                }
            }
        }

        String w = winners.length() == 0 ? "—\n" : winners.toString();
        String l = losers.length()  == 0 ? "—\n" : losers.toString();
        String p = pushes.length()  == 0 ? "—\n" : pushes.toString();

        EmbedBuilder eb = new EmbedBuilder()
            .setTitle("📌 ROUND RESULTS")
            .setColor(new Color(0x3498db)); // neutral blue frame

        eb.addField(
            "🟢 WINNERS",
            winners.length() == 0
                ? "> _None this round_"
                : ">>> **" + winners + "**",
            false
        );

        eb.addField(
            "🔵 PUSH",
            pushes.length() == 0
                ? "> _None_"
                : ">>> **" + pushes + "**",
            false
        );

        eb.addField(
            "🔴 LOSERS",
            losers.length() == 0
                ? "> _None_"
                : ">>> **" + losers + "**",
            false
        );

        eb.addField(
            "🧑‍⚖️ DEALER",
            ">>> **" + dealerHand + "**  (**" + dealerTotal + "**)",
            false
        );

        eb.setFooter("Next round starts shortly…");

        // force update table message to the results screen
        upsertTableMessage(channel, eb.build(), buildButtons(channel.getIdLong()), true);
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

            // Natural blackjack (first hand only, no split)
            if (i == 0 && hands.size() == 1 && h.cards.size() == 2 && handValue(h.cards) == 21) {
                String uid = user.getId();
                int payout = (int) Math.round(h.bet * 2.5);
                balancesById.merge(uid, payout, Integer::sum);
                winsById.merge(uid, 1, Integer::sum);
                appendLedger(uid, new LedgerEntry(Instant.now().toString(), "WIN_BLACKJACK", payout, balancesById.get(uid)));
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

                refreshTable(channel, true);

                Action act = pollAction(user);
                if (act == null) act = Action.STAND;

                switch (act) {
                    case HIT -> {
                        h.cards.add(dealCard());
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

        // Build round log lines (includes dealer + each hand outcome)
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

        // Store round history per channel
        long cid = channel.getIdLong();
        Deque<RoundLog> hist = roundHistoryByChannel.computeIfAbsent(cid, k -> new ArrayDeque<>());
        hist.addFirst(new RoundLog(Instant.now().toString(), new ArrayList<>(dealerHand), dealerTotal, logLines));
        while (hist.size() > MAX_ROUND_HISTORY) hist.removeLast();
        historyPageByChannel.put(cid, 0);

        saveState();
        refreshTable(channel, true);
    }

    // ---- UI ----
    private static void refreshTable(GuildMessageChannel channel, boolean force) {
        long cid = channel.getIdLong();
        MessageEmbed embed = buildEmbed(cid);
        List<ActionRow> rows = buildButtons(cid);
        upsertTableMessage(channel, embed, rows, force);
    }

    private static void refreshTable(GuildMessageChannel channel) { refreshTable(channel, false); }

    private static void upsertTableMessage(GuildMessageChannel channel, MessageEmbed embed, List<ActionRow> rows, boolean force) {
        long cid = channel.getIdLong();

        long now = System.currentTimeMillis();
        if (!force && now - lastUpdateByChannel.getOrDefault(cid, 0L) < 350) return;
        lastUpdateByChannel.put(cid, now);

        Long mid = tableMessageByChannel.get(cid);

        if (mid == null) {
            // IMPORTANT: if table is empty, do NOT post a brand new “empty table” message.
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
                    // Retry once (will create a fresh message IF table isn't empty)
                    upsertTableMessage(channel, embed, rows, true);
                }
        );
    }

    private static MessageEmbed buildEmbed(long cid) {
        EmbedBuilder eb = new EmbedBuilder();
        eb.setTitle("🃏 Blackjack");
        eb.setColor(roundActive ? new Color(0xf1c40f) : new Color(0x2ecc71));

        // Dealer
        String dealerLine;
        if (dealerHand == null || dealerHand.isEmpty()) dealerLine = "—";
        else if (roundActive && dealerHidden) dealerLine = "**" + dealerHand.get(0) + "**, [hidden]";
        else dealerLine = dealerHand + "  **(" + handValue(dealerHand) + ")**";
        eb.addField("Dealer", dealerLine, false);

        // Players
        StringBuilder p = new StringBuilder();
        for (User u : seated) {
            String uid = u.getId();
            List<HandState> hs = playerHands.get(u);

            boolean isTurn = roundActive && u.equals(currentPlayer);
            p.append(u.getAsMention()).append(isTurn ? "  ▶️" : "").append("\n");

            if (hs != null) {
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
        if (roundActive && currentPlayer != null) {
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

        boolean turn = roundActive && currentPlayer != null;
        Button hit = Button.primary(BTN_HIT, "Hit").withDisabled(!turn);
        Button stand = Button.secondary(BTN_STAND, "Stand").withDisabled(!turn);

        boolean canDouble = turn && canDouble(currentPlayer, getActiveHand(currentPlayer));
        boolean canSplit  = turn && canSplit(currentPlayer, getActiveHand(currentPlayer));

        Button dbl = Button.primary(BTN_DOUBLE, "Double").withDisabled(!canDouble);
        Button split = Button.primary(BTN_SPLIT, "Split").withDisabled(!canSplit);

        Deque<RoundLog> hist = roundHistoryByChannel.getOrDefault(cid, new ArrayDeque<>());
        int size = hist.size();
        int page = historyPageByChannel.getOrDefault(cid, 0);

        Button prev = Button.secondary(BTN_HIST_PREV, "Prev").withDisabled(size == 0 || page >= size - 1);
        Button next = Button.secondary(BTN_HIST_NEXT, "Next").withDisabled(size == 0 || page <= 0);

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
        if (!roundActive) return false;
        if (!user.equals(currentPlayer)) return false;
        if (h.cards.size() != 2) return false;
        int bal = balancesById.getOrDefault(user.getId(), START_BALANCE);
        return bal >= h.bet;
    }

    private static boolean canSplit(User user, HandState h) {
        if (user == null || h == null) return false;
        if (!roundActive) return false;
        if (!user.equals(currentPlayer)) return false;
        if (h.cards.size() != 2) return false;
        if (!sameRank(h.cards.get(0), h.cards.get(1))) return false;
        int bal = balancesById.getOrDefault(user.getId(), START_BALANCE);
        return bal >= h.bet;
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

    // ---- Idle close (fixes duplicate tables in chat) ----
    private static void scheduleIdleClose(GuildMessageChannel channel) {
        long cid = channel.getIdLong();

        ScheduledFuture<?> old = idleCloseTask.remove(cid);
        if (old != null) old.cancel(false);

        idleCloseTask.put(cid, SCHED.schedule(() -> {
            if (!seated.isEmpty() || !waiting.isEmpty()) return;

            // STOP the game loop
            running = false;
            roundActive = false;
            currentPlayer = null;
            currentHandIndex = 0;
            playerHands.clear();
            dealerHand.clear();
            dealerHidden = true;

            // DELETE the table message so it doesn't leave ghosts in chat
            Long mid = tableMessageByChannel.get(cid);
            if (mid != null) {
                channel.retrieveMessageById(mid).queue(
                        msg -> msg.delete().queue(),
                        fail -> { /* ignore */ }
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

    // ---- Cards ----
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

    // ---- HandState ----
    private static class HandState {
        final List<String> cards;
        int bet;
        final AtomicBoolean resolved = new AtomicBoolean(false);

        HandState(List<String> cards, int bet) {
            this.cards = cards;
            this.bet = bet;
        }
    }

    // ---- History model ----
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

    // ---- Ledger persistence ----
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

    // Minimal JSON write (no deps). This saves balances/wins/losses and the most recent ledger entries.
    // NOTE: The loader below only restores balances/wins/losses (to keep it dependency-free).
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
