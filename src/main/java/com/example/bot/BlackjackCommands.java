package com.example.bot;

import net.dv8tion.jda.api.entities.User;
import net.dv8tion.jda.api.entities.channel.middleman.GuildMessageChannel;

public class BlackjackCommands {

    public static void handleCommand(String rawMessage, User user, GuildMessageChannel channel) {
        if (rawMessage == null) return;

        String msg = rawMessage.trim();
        String cmd = msg.toLowerCase();

        // Allow both styles
        if (cmd.equals("!blackjack") || cmd.equals("!join")) {
            BlackjackGame.join(user, channel);
            return;
        }

        if (cmd.equals("!quit") || cmd.equals("!leave") || cmd.equals("!exit")) {
            BlackjackGame.leave(user, channel);
            return;
        }

        if (cmd.equals("!ledger") || cmd.equals("!balance")) {
            BlackjackGame.showLedger(user, channel);
            return;
        }

        // Actions
        if (cmd.equals("!hit")) {
            BlackjackGame.action(user, BlackjackGame.Action.HIT);
            return;
        }

        if (cmd.equals("!stand")) {
            BlackjackGame.action(user, BlackjackGame.Action.STAND);
            return;
        }

        if (cmd.equals("!double")) {
            BlackjackGame.action(user, BlackjackGame.Action.DOUBLE);
            return;
        }

        if (cmd.equals("!split")) {
            BlackjackGame.action(user, BlackjackGame.Action.SPLIT);
            return;
        }

        // Unknown blackjack command (ignore quietly or message if you want)
        // channel.sendMessage("Unknown blackjack command. Try `!blackjack help`.").queue();
    }
}
