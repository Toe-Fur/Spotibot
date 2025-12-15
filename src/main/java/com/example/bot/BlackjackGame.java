package com.example.bot;

import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.entities.MessageEmbed;
import net.dv8tion.jda.api.entities.User;
import net.dv8tion.jda.api.entities.channel.middleman.GuildMessageChannel;
import net.dv8tion.jda.api.interactions.components.ActionRow;
import net.dv8tion.jda.api.interactions.components.buttons.Button;

import java.awt.Color;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

public class BlackjackGame {

    // -------- Public API --------
    public enum Action { HIT, STAND, DOUBLE, SPLIT }

    private static final int START_BALANCE = 100;
    private static final int MIN_BET = 5;
    private static final int TURN_TIMEOUT_SECONDS = 45;
    private static final Set<Long> creatingTableMessage = ConcurrentHashMap.newKeySet();


    // Dealer “animation” delays
    private static final long DEALER_REVEAL_DELAY_MS = 800;
    private static final long DEALER_DRAW_DELAY_MS   = 700;

    // One game thread
    private static final ExecutorService GAME_THREAD =
            Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "blackjack-thread");
                t.setDaemon(true);
                return t;
            });

    private static final Random RNG = new Random();

    // Stable order seating per channel (single table per channel)
    // If you want multi-channel tables, you can promote these to Map<channelId, TableState>.
    private static final List<User> seated = new CopyOnWriteArrayList<>();
    private static final Set<User> waiting = ConcurrentHashMap.newKeySet();

    private static final Map<User, Integer> balances = new ConcurrentHashMap<>();
    private static final Map<User, Integer> wins = new ConcurrentHashMap<>();
    private static final Map<User, BlockingQueue<Action>> actionQueues = new ConcurrentHashMap<>();

    // Per-player hands for this round
    private static final Map<User, List<HandState>> playerHands = new ConcurrentHashMap<>();

    private static volatile boolean running = false;
    private static volatile boolean roundActive = false;

    private static volatile User currentPlayer = null;
    private static volatile int currentHandIndex = 0;

    private static List<String> dealerHand = new ArrayList<>();
    private static volatile boolean dealerHidden = true;

    // Single “table message” per channel
    private static final Map<Long, Long> tableMessageByChannel = new ConcurrentHashMap<>();
    private static final Map<Long, Long> lastUpdateByChannel = new ConcurrentHashMap<>();

    // Button IDs
    public static final String BTN_JOIN  = "bj:join";
    public static final String BTN_LEAVE = "bj:leave";
    public static final String BTN_HIT   = "bj:hit";
    public static final String BTN_STAND = "bj:stand";
    public static final String BTN_DOUBLE= "bj:double";
    public static final String BTN_SPLIT = "bj:split";

    // -------- Compatibility shims (so your old CommandHandler compiles) --------
    public static void startGame(User user, GuildMessageChannel channel) { join(user, channel); }

    public static void addBalance(User user, int amount, GuildMessageChannel channel) {
        balances.putIfAbsent(user, START_BALANCE);
        balances.merge(user, amount, Integer::sum);
        refreshTable(channel);
    }

    // -------- Core commands (works for !commands and slash) --------
    public static void join(User user, GuildMessageChannel channel) {
        balances.putIfAbsent(user, START_BALANCE);
        actionQueues.putIfAbsent(user, new ArrayBlockingQueue<>(16));

        if (seated.contains(user) || waiting.contains(user)) {
            ensureRunning(channel);
            refreshTable(channel);
            return;
        }

        if (roundActive) waiting.add(user);
        else seated.add(user);

        ensureRunning(channel);
        refreshTable(channel);
    }

    public static void leave(User user, GuildMessageChannel channel) {
        seated.remove(user);
        waiting.remove(user);
        actionQueues.remove(user);
        playerHands.remove(user);

        // If leaving on turn, auto-stand to keep game moving
        if (user.equals(currentPlayer)) {
            offerAction(user, Action.STAND);
        }

        if (seated.isEmpty() && waiting.isEmpty()) {
            running = false;
            roundActive = false;
            currentPlayer = null;
            currentHandIndex = 0;
        }

        refreshTable(channel);
    }

    public static void showLedger(User user, GuildMessageChannel channel) {
        channel.sendMessage(user.getAsMention()
                + " Wins: **" + wins.getOrDefault(user, 0)
                + "** | Balance: **$" + balances.getOrDefault(user, START_BALANCE) + "**")
                .queue();
    }

    // Push actions from commands/buttons
    public static void action(User user, Action action) {
        if (!roundActive) return;
        if (!user.equals(currentPlayer)) return;
        offerAction(user, action);
    }

    private static void offerAction(User user, Action action) {
        BlockingQueue<Action> q = actionQueues.get(user);
        if (q != null) q.offer(action);
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
                sleep(500);
                continue;
            }
            playRound(channel);
        }
    }

    private static void playRound(GuildMessageChannel channel) {
        roundActive = true;

        seated.addAll(waiting);
        waiting.clear();

        // Reset round state
        playerHands.clear();
        dealerHand = dealHand();
        dealerHidden = true;
        currentPlayer = null;
        currentHandIndex = 0;

        // Take bets + deal hands
        for (User u : new ArrayList<>(seated)) {
            int bal = balances.getOrDefault(u, START_BALANCE);
            if (bal < MIN_BET) {
                seated.remove(u);
                continue;
            }
            balances.put(u, bal - MIN_BET);

            List<HandState> hs = new ArrayList<>();
            hs.add(new HandState(dealHand(), MIN_BET));
            playerHands.put(u, hs);
        }

        refreshTable(channel);

        // Player turns in seating order
        for (User u : new ArrayList<>(seated)) {
            if (!running) break;
            takePlayerTurns(u, channel);
        }

        currentPlayer = null;
        currentHandIndex = 0;
        refreshTable(channel, true);

        // Dealer reveal + draw with delays
        dealerHidden = false;
        refreshTable(channel);
        sleep(DEALER_REVEAL_DELAY_MS);

        while (handValue(dealerHand) < 17) {
            dealerHand.add(dealCard());
            refreshTable(channel);
            sleep(DEALER_DRAW_DELAY_MS);
        }

        settleRound(channel);

        // End of round
        currentPlayer = null;
        currentHandIndex = 0;
        roundActive = false;
        refreshTable(channel);

        // tiny breather so it doesn’t insta-loop if only one player is sitting there forever
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

            // Natural blackjack (only on first hand, 2 cards, no split)
            if (i == 0 && hands.size() == 1 && h.cards.size() == 2 && handValue(h.cards) == 21) {
                // 3:2 payout (bet already deducted): pay 2.5x
                int payout = (int) Math.round(h.bet * 2.5);
                balances.merge(user, payout, Integer::sum);
                wins.merge(user, 1, Integer::sum);
                h.resolved.set(true);
                refreshTable(channel, true);
                continue;
            }

            while (true) {
                if (!running || !seated.contains(user)) return;

                int total = handValue(h.cards);
                if (total > 21) { // bust
                    h.resolved.set(true);
                    refreshTable(channel);
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
                        refreshTable(channel);
                        break;
                    }
                    case DOUBLE -> {
                        if (!canDouble(user, h)) {
                            // ignore invalid double, keep waiting for a valid action
                            continue;
                        }
                        // Pay extra bet, double bet, one card, then resolve hand
                        balances.put(user, balances.get(user) - h.bet);
                        h.bet *= 2;
                        h.cards.add(dealCard());
                        h.resolved.set(true);
                        refreshTable(channel);
                        break;
                    }
                    case SPLIT -> {
                        if (!canSplit(user, h)) {
                            continue;
                        }
                        // Pay extra bet for second hand
                        balances.put(user, balances.get(user) - h.bet);

                        // Create two hands
                        String c1 = h.cards.get(0);
                        String c2 = h.cards.get(1);

                        HandState h1 = new HandState(new ArrayList<>(List.of(c1, dealCard())), h.bet);
                        HandState h2 = new HandState(new ArrayList<>(List.of(c2, dealCard())), h.bet);

                        // Replace current hand with h1, insert h2 right after
                        hands.set(i, h1);
                        hands.add(i + 1, h2);

                        // Continue playing h1 (same index)
                        h = hands.get(i);
                        continue;
                    }
                }

                // break for STAND/DOUBLE resolution
                break;
            }
        }
    }

    private static void settleRound(GuildMessageChannel channel) {
        int dealerTotal = handValue(dealerHand);

        for (User u : new ArrayList<>(seated)) {
            List<HandState> hs = playerHands.get(u);
            if (hs == null) continue;

            for (HandState h : hs) {
                int t = handValue(h.cards);

                if (t > 21) {
                    // bust => already lost bet
                    continue;
                }

                if (dealerTotal > 21 || t > dealerTotal) {
                    balances.merge(u, h.bet * 2, Integer::sum);
                    wins.merge(u, 1, Integer::sum);
                } else if (t == dealerTotal) {
                    balances.merge(u, h.bet, Integer::sum);
                } else {
                    // lose => no payout
                }
            }
        }

        refreshTable(channel);
    }

    // -------- UI (single message + buttons) --------
    private static void refreshTable(GuildMessageChannel channel) {
        refreshTable(channel, false);
    }

    private static void refreshTable(GuildMessageChannel channel, boolean force) {
        MessageEmbed embed = buildEmbed();
        List<ActionRow> rows = buildButtons(channel);
        upsertTableMessage(channel, embed, rows, force);
    }

    private static void upsertTableMessage(GuildMessageChannel channel, MessageEmbed embed, List<ActionRow> rows, boolean force) {
        long cid = channel.getIdLong();

        long now = System.currentTimeMillis();
        if (!force && now - lastUpdateByChannel.getOrDefault(cid, 0L) < 350) return;
        lastUpdateByChannel.put(cid, now);

        Long mid = tableMessageByChannel.get(cid);

        // If we don't have a message yet, only allow ONE async create in-flight
        if (mid == null) {
            if (!creatingTableMessage.add(cid)) {
                // someone else already started creating it
                return;
            }

            channel.sendMessageEmbeds(embed)
                    .setComponents(rows)
                    .queue(m -> {
                        tableMessageByChannel.put(cid, m.getIdLong());
                        creatingTableMessage.remove(cid);
                    }, fail -> {
                        creatingTableMessage.remove(cid);
                    });

            return;
        }

        channel.retrieveMessageById(mid).queue(
                m -> m.editMessageEmbeds(embed).setComponents(rows).queue(),
                f -> {
                    // message missing (deleted) -> recreate cleanly
                    tableMessageByChannel.remove(cid);
                    creatingTableMessage.remove(cid);
                    upsertTableMessage(channel, embed, rows, true);
                }
        );
    }

    private static MessageEmbed buildEmbed() {
        EmbedBuilder eb = new EmbedBuilder();
        eb.setTitle("🃏 Blackjack");
        eb.setColor(roundActive ? new Color(0xf1c40f) : new Color(0x2ecc71));

        // Dealer line
        String dealerLine;
        if (dealerHand == null || dealerHand.isEmpty()) {
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
            List<HandState> hs = playerHands.get(u);
            if (hs == null) {
                p.append(u.getAsMention()).append("\n\n");
                continue;
            }

            boolean isTurn = roundActive && u.equals(currentPlayer);
            p.append(u.getAsMention()).append(isTurn ? "  ▶️" : "").append("\n");

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

            p.append("Balance: **$").append(balances.getOrDefault(u, START_BALANCE)).append("**")
                    .append(" | Wins: **").append(wins.getOrDefault(u, 0)).append("**\n\n");
        }

        if (p.length() == 0) p.append("—");
        eb.addField("Players", p.toString(), false);

        if (roundActive && currentPlayer != null) {
            eb.setFooter("Turn: " + currentPlayer.getName() + " | Use buttons or /blackjack");
        } else {
            eb.setFooter("Use Join to play. Leave to exit.");
        }

        return eb.build();
    }

    private static List<ActionRow> buildButtons(GuildMessageChannel channel) {
        // Global buttons
        Button join = Button.success(BTN_JOIN, "Join");
        Button leave = Button.danger(BTN_LEAVE, "Leave");

        // Turn buttons
        boolean turn = roundActive && currentPlayer != null;
        Button hit = Button.primary(BTN_HIT, "Hit").withDisabled(!turn);
        Button stand = Button.secondary(BTN_STAND, "Stand").withDisabled(!turn);

        boolean canDouble = turn && canDouble(currentPlayer, getActiveHand(currentPlayer));
        boolean canSplit = turn && canSplit(currentPlayer, getActiveHand(currentPlayer));

        Button dbl = Button.primary(BTN_DOUBLE, "Double").withDisabled(!canDouble);
        Button split = Button.primary(BTN_SPLIT, "Split").withDisabled(!canSplit);

        return List.of(
                ActionRow.of(join, leave),
                ActionRow.of(hit, stand, dbl, split)
        );
    }

    private static HandState getActiveHand(User user) {
        if (user == null) return null;
        List<HandState> hs = playerHands.get(user);
        if (hs == null || hs.isEmpty()) return null;
        int idx = Math.max(0, Math.min(currentHandIndex, hs.size() - 1));
        return hs.get(idx);
    }

    // -------- Rules helpers --------
    private static boolean canDouble(User user, HandState h) {
        if (user == null || h == null) return false;
        if (!roundActive) return false;
        if (!user.equals(currentPlayer)) return false;
        if (h.cards.size() != 2) return false;
        int bal = balances.getOrDefault(user, START_BALANCE);
        return bal >= h.bet;
    }

    private static boolean canSplit(User user, HandState h) {
        if (user == null || h == null) return false;
        if (!roundActive) return false;
        if (!user.equals(currentPlayer)) return false;
        if (h.cards.size() != 2) return false;
        if (!sameRank(h.cards.get(0), h.cards.get(1))) return false;
        int bal = balances.getOrDefault(user, START_BALANCE);
        return bal >= h.bet;
    }

    // -------- Action polling --------
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
}
