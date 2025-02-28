package com.example.bot;

import net.dv8tion.jda.api.entities.User;
import net.dv8tion.jda.api.entities.channel.middleman.GuildMessageChannel;
import java.util.*;
import java.util.concurrent.*;

public class BlackjackGame {
    private static final Map<User, Integer> ledger = new HashMap<>();
    private static final Map<User, Integer> balances = new HashMap<>();
    private static final Set<User> currentPlayers = new HashSet<>();
    private static final Map<User, List<String>> playerHands = new HashMap<>();
    private static final Map<User, Integer> playerBets = new HashMap<>();
    private static final Map<User, CountDownLatch> playerLatches = new HashMap<>();
    private static boolean gameInProgress = false;
    private static final Random random = new Random();
    private static List<String> dealerHand;

    public static void startGame(User author, GuildMessageChannel messageChannel) {
        if (currentPlayers.contains(author)) {
            messageChannel.sendMessage(author.getAsMention() + " You are already in the game!").queue();
            return;
        }
        if (!balances.containsKey(author)) {
            balances.put(author, 100); // Initialize balance with $100
        }
        currentPlayers.add(author);
        messageChannel.sendMessage(author.getAsMention() + " 🎉 Joined the game of blackjack!").queue();

        if (!gameInProgress) {
            gameInProgress = true;
            messageChannel.sendMessage("🃏 Starting a new game of blackjack!").queue();
            new Thread(() -> gameLoop(messageChannel)).start();
        }
    }

    public static void quitGame(User author, GuildMessageChannel messageChannel) {
        if (!currentPlayers.contains(author)) {
            messageChannel.sendMessage(author.getAsMention() + " You are not in the game!").queue();
            return;
        }
        currentPlayers.remove(author);
        playerHands.remove(author);
        playerBets.remove(author);
        playerLatches.remove(author);
        if (currentPlayers.isEmpty()) {
            gameInProgress = false;
            messageChannel.sendMessage("No players left. The game has ended.").queue();
        }
    }

    public static void showLedger(User author, GuildMessageChannel messageChannel) {
        int wins = ledger.getOrDefault(author, 0);
        int balance = balances.getOrDefault(author, 100);
        messageChannel.sendMessage(author.getAsMention() + " 🏆 You have " + wins + " wins and a balance of $" + balance + " in blackjack.").queue();
    }

    public static void recordWin(User author, boolean blackjack) {
        ledger.put(author, ledger.getOrDefault(author, 0) + 1);
        int bet = playerBets.get(author);
        int winnings = blackjack ? (int) (bet * 1.5) : bet;
        balances.put(author, balances.getOrDefault(author, 100) + winnings); // Add winnings
    }

    public static void addBalance(User author, int amount, GuildMessageChannel messageChannel) {
        balances.put(author, balances.getOrDefault(author, 100) + amount);
        messageChannel.sendMessage(author.getAsMention() + " 💰 Your balance has been updated to $" + balances.get(author) + ".").queue();
    }

    private static void gameLoop(GuildMessageChannel messageChannel) {
        while (gameInProgress) {
            playHand(messageChannel);
            if (currentPlayers.isEmpty()) {
                gameInProgress = false;
                messageChannel.sendMessage("No players left. The game has ended.").queue();
            }
        }
    }

    private static void playHand(GuildMessageChannel messageChannel) {
        if (currentPlayers.isEmpty()) {
            return;
        }

        // Deal cards to each player and the dealer
        dealerHand = dealHand();
        messageChannel.sendMessage("Dealer's hand: " + dealerHand.get(0) + " and [hidden]").queue();

        for (User player : currentPlayers) {
            List<String> hand = dealHand();
            playerHands.put(player, hand);
            playerBets.put(player, 5); // Default bet is $5
            balances.put(player, balances.get(player) - 5); // Deduct minimum bet from balance
            messageChannel.sendMessage(player.getAsMention() + " Your hand: " + hand).queue();
        }

        // Start the game for each player
        for (User player : new HashSet<>(currentPlayers)) {
            playTurn(player, messageChannel);
        }

        // Check if all players have busted
        boolean allPlayersBusted = true;
        for (User player : currentPlayers) {
            if (calculateHandValue(playerHands.get(player)) <= 21) {
                allPlayersBusted = false;
                break;
            }
        }

        if (!allPlayersBusted) {
            // Dealer's turn
            dealerTurn(messageChannel);
        }

        // Determine the winners
        determineWinners(messageChannel);
    }

    private static void playTurn(User player, GuildMessageChannel messageChannel) {
        if (!currentPlayers.contains(player)) {
            return;
        }

        List<String> hand = playerHands.get(player);
        messageChannel.sendMessage(formatGameState(player)).queue();
        messageChannel.sendMessage(player.getAsMention() + " Options: `!hit`, `!stand`, `!double`, `!split`").queue();

        if (calculateHandValue(hand) == 21 && hand.size() == 2) {
            messageChannel.sendMessage(player.getAsMention() + " 🎉 Blackjack! You win!").queue();
            recordWin(player, true);
            nextPlayer(player, messageChannel);
            return;
        }

        CountDownLatch latch = new CountDownLatch(1);
        playerLatches.put(player, latch);

        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    public static void hit(User player, GuildMessageChannel messageChannel) {
        if (!currentPlayers.contains(player)) {
            return;
        }

        List<String> hand = playerHands.get(player);
        hand.add(dealCard());
        messageChannel.sendMessage(formatGameState(player)).queue();
        if (calculateHandValue(hand) > 21) {
            messageChannel.sendMessage(player.getAsMention() + " 💥 You busted! Your turn is over.").queue();
            nextPlayer(player, messageChannel);
        } else {
            playTurn(player, messageChannel); // Continue player's turn
        }
    }

    public static void stand(User player, GuildMessageChannel messageChannel) {
        if (!currentPlayers.contains(player)) {
            return;
        }

        messageChannel.sendMessage(formatGameState(player)).queue();
        nextPlayer(player, messageChannel);
    }

    public static void doubleDown(User player, GuildMessageChannel messageChannel) {
        if (!currentPlayers.contains(player)) {
            return;
        }

        int bet = playerBets.get(player);
        if (balances.get(player) >= bet) {
            playerBets.put(player, bet * 2);
            balances.put(player, balances.get(player) - bet);
            messageChannel.sendMessage(player.getAsMention() + " You doubled down. Your new bet is $" + playerBets.get(player)).queue();
            hit(player, messageChannel);
            // End player's turn after double down
            stand(player, messageChannel);
        } else {
            messageChannel.sendMessage(player.getAsMention() + " You don't have enough balance to double down.").queue();
            playTurn(player, messageChannel); // Continue player's turn
        }
    }

    public static void split(User player, GuildMessageChannel messageChannel) {
        if (!currentPlayers.contains(player)) {
            return;
        }

        // Split logic
        List<String> hand = playerHands.get(player);
        if (hand.size() == 2 && hand.get(0).substring(0, hand.get(0).length() - 2).equals(hand.get(1).substring(0, hand.get(1).length() - 2))) {
            List<String> newHand1 = new ArrayList<>(Collections.singletonList(hand.get(0)));
            List<String> newHand2 = new ArrayList<>(Collections.singletonList(hand.get(1)));
            newHand1.add(dealCard());
            newHand2.add(dealCard());
            playerHands.put(player, newHand1);
            playerHands.put(player, newHand2);
            messageChannel.sendMessage(player.getAsMention() + " You split your hand. Your new hands: " + newHand1 + " and " + newHand2).queue();
            if (hand.get(0).startsWith("A")) {
                // Auto hit and stand for each ace
                messageChannel.sendMessage(player.getAsMention() + " You split aces. You get one card for each hand and then stand.").queue();
                stand(player, messageChannel);
                stand(player, messageChannel);
            } else {
                playTurn(player, messageChannel); // Play the first split hand
            }
        } else {
            messageChannel.sendMessage(player.getAsMention() + " You can't split this hand.").queue();
            playTurn(player, messageChannel); // Continue player's turn
        }
    }

    private static void nextPlayer(User currentPlayer, GuildMessageChannel messageChannel) {
        boolean foundCurrent = false;
        for (User player : currentPlayers) {
            if (foundCurrent) {
                playTurn(player, messageChannel);
                return;
            }
            if (player.equals(currentPlayer)) {
                foundCurrent = true;
            }
        }
        dealerTurn(messageChannel);
        determineWinners(messageChannel);
    }

    private static void dealerTurn(GuildMessageChannel messageChannel) {
        messageChannel.sendMessage("Dealer's hand: " + dealerHand).queue();
        while (calculateHandValue(dealerHand) < 17) {
            dealerHand.add(dealCard());
        }
        messageChannel.sendMessage("Dealer's final hand: " + dealerHand + " (Total: " + calculateHandValue(dealerHand) + ")").queue();
    }

    private static void determineWinners(GuildMessageChannel messageChannel) {
        int dealerValue = calculateHandValue(dealerHand);
        for (User player : new HashSet<>(currentPlayers)) {
            List<String> hand = playerHands.get(player);
            int playerValue = calculateHandValue(hand);
            if (playerValue > 21) {
                messageChannel.sendMessage(player.getAsMention() + " 💥 You busted with a total of " + playerValue + "!").queue();
                balances.put(player, balances.get(player) - playerBets.get(player));
            } else if (dealerValue > 21 || playerValue > dealerValue) {
                messageChannel.sendMessage(player.getAsMention() + " 🎉 You win with a total of " + playerValue + "!").queue();
                recordWin(player, false);
            } else if (playerValue == dealerValue) {
                messageChannel.sendMessage(player.getAsMention() + " 🤝 It's a tie with a total of " + playerValue + "!").queue();
            } else {
                messageChannel.sendMessage(player.getAsMention() + " 😞 You lose with a total of " + playerValue + ".").queue();
                balances.put(player, balances.get(player) - playerBets.get(player));
            }
        }
        messageChannel.sendMessage("Dealer's final total: " + dealerValue).queue();
    }

    private static List<String> dealHand() {
        List<String> hand = new ArrayList<>();
        hand.add(dealCard());
        hand.add(dealCard());
        return hand;
    }

    private static String dealCard() {
        String[] suits = {"♥️", "♦️", "♣️", "♠️"};
        String[] ranks = {"2", "3", "4", "5", "6", "7", "8", "9", "10", "J", "Q", "K", "A"};
        return ranks[random.nextInt(ranks.length)] + suits[random.nextInt(suits.length)];
    }

    private static int calculateHandValue(List<String> hand) {
        int value = 0;
        int aces = 0;
        for (String card : hand) {
            String rank = card.substring(0, card.length() - 2);
            switch (rank) {
                case "2": case "3": case "4": case "5": case "6": case "7": case "8": case "9": case "10":
                    value += Integer.parseInt(rank);
                    break;
                case "J": case "Q": case "K":
                    value += 10;
                    break;
                case "A":
                    aces++;
                    value += 11;
                    break;
            }
        }
        while (value > 21 && aces > 0) {
            value -= 10;
            aces--;
        }
        return value;
    }

    private static String formatGameState(User player) {
        List<String> playerHand = playerHands.get(player);
        int playerTotal = calculateHandValue(playerHand);
        String dealerShownCard = dealerHand.get(0);
        return "Dealer: " + dealerShownCard + "/? || " + player.getAsMention() + ": " + playerHand + " (Total: " + playerTotal + ")";
    }

    // ...additional blackjack game logic...
}
