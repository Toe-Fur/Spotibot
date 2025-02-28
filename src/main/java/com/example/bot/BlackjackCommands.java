package com.example.bot;

import net.dv8tion.jda.api.entities.User;
import net.dv8tion.jda.api.entities.channel.middleman.GuildMessageChannel;

public class BlackjackCommands {

    public static void handleCommand(String command, User user, GuildMessageChannel messageChannel) {
        switch (command.toLowerCase()) {
            case "!hit":
                BlackjackGame.hit(user, messageChannel);
                break;
            case "!stand":
                BlackjackGame.stand(user, messageChannel);
                break;
            case "!double":
                BlackjackGame.doubleDown(user, messageChannel);
                break;
            case "!split":
                BlackjackGame.split(user, messageChannel);
                break;
            case "!quit":
                BlackjackGame.quitGame(user, messageChannel);
                break;
            case "!ledger":
                BlackjackGame.showLedger(user, messageChannel);
                break;
            default:
                messageChannel.sendMessage(user.getAsMention() + " Invalid command.").queue();
                break;
        }
    }
}
