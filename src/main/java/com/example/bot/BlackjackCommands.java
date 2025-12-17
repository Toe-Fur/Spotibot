package com.example.bot;

import net.dv8tion.jda.api.entities.User;
import net.dv8tion.jda.api.entities.channel.middleman.GuildMessageChannel;

public class BlackjackCommands {
    public static void handleCommand(String rawMessage, User user, GuildMessageChannel channel) {
        if (rawMessage == null) return;

        String cmd = rawMessage.trim().toLowerCase();

        switch (cmd) {
            case "!blackjack", "!join" -> BlackjackGame.join(user, channel);
            case "!quit", "!leave", "!exit" -> BlackjackGame.leave(user, channel);
            case "!ledger", "!balance" -> BlackjackGame.showLedger(user, channel);
            case "!hit" -> BlackjackGame.action(user, BlackjackGame.Action.HIT, channel);
            case "!stand" -> BlackjackGame.action(user, BlackjackGame.Action.STAND, channel);
            case "!double" -> BlackjackGame.action(user, BlackjackGame.Action.DOUBLE, channel);
            case "!split" -> BlackjackGame.action(user, BlackjackGame.Action.SPLIT, channel);
            default -> { /* ignore */ }
        }
    }
}
