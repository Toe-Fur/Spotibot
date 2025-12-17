package com.example.bot;

import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.entities.channel.middleman.GuildMessageChannel;

public class BlackjackUiListener extends ListenerAdapter {

    @Override
    public void onButtonInteraction(ButtonInteractionEvent event) {
        String id = event.getComponentId();
        String ack = null;
        if (id == null || !id.startsWith("bj:")) return;

        // prevent "This interaction failed"
        event.deferReply(true).queue();

        GuildMessageChannel channel = event.getChannel().asGuildMessageChannel();

        // Handle bet buttons like bj:bet:50 (increment staged bet)
        if (id.startsWith(BlackjackGame.BTN_BET_PREFIX)) {
            try {
                int amt = Integer.parseInt(id.substring(BlackjackGame.BTN_BET_PREFIX.length()));
                BlackjackGame.placeBet(event.getUser(), amt, channel);
                ack = "Queued bet: **$" + amt + "**";
            } catch (Exception ignored) { }
            return;
        }

        switch (id) {
            case BlackjackGame.BTN_JOIN -> { BlackjackGame.join(event.getUser(), channel); ack = "Joined the table."; }
            case BlackjackGame.BTN_LEAVE -> { BlackjackGame.leave(event.getUser(), channel); ack = "Left the table."; }

            case BlackjackGame.BTN_BUYIN -> { BlackjackGame.buyIn(event.getUser(), channel); ack = "Buy-in requested."; }
            case BlackjackGame.BTN_BET_CLEAR -> { BlackjackGame.clearBet(event.getUser(), channel); ack = "Cleared bet."; }
            case BlackjackGame.BTN_BET_CONFIRM -> { BlackjackGame.confirmBet(event.getUser(), channel); ack = "Bet confirmed."; }

            case BlackjackGame.BTN_HIT -> { BlackjackGame.action(event.getUser(), BlackjackGame.Action.HIT, channel); ack = "Queued: **HIT**"; }
            case BlackjackGame.BTN_STAND -> { BlackjackGame.action(event.getUser(), BlackjackGame.Action.STAND, channel); ack = "Queued: **STAND**"; }
            case BlackjackGame.BTN_DOUBLE -> { BlackjackGame.action(event.getUser(), BlackjackGame.Action.DOUBLE, channel); ack = "Queued: **DOUBLE**"; }
            case BlackjackGame.BTN_SPLIT -> { BlackjackGame.action(event.getUser(), BlackjackGame.Action.SPLIT, channel); ack = "Queued: **SPLIT**"; }

            case BlackjackGame.BTN_HIST_PREV -> { BlackjackGame.historyPrev(channel); ack = "History: prev"; }
            case BlackjackGame.BTN_HIST_NEXT -> { BlackjackGame.historyNext(channel); ack = "History: next"; }

            case BlackjackGame.BTN_LEDGER_PREV -> { BlackjackGame.ledgerPrev(channel); ack = "Ledger: prev"; }
            case BlackjackGame.BTN_LEDGER_NEXT -> { BlackjackGame.ledgerNext(channel); ack = "Ledger: next"; }
            case BlackjackGame.BTN_LEDGER_CLOSE -> { BlackjackGame.ledgerClose(channel); ack = "Ledger closed."; }

            default -> { ack = ""; }
        }

        if (ack != null && !ack.isBlank()) {
            event.getHook().editOriginal(ack).queue();
        } else {
            // keep interaction acknowledged even when we ignore
            event.getHook().editOriginal("✅").queue();
        }
    }
}
