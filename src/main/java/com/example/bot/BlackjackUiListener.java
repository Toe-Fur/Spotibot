package com.example.bot;

import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.entities.channel.middleman.GuildMessageChannel;

public class BlackjackUiListener extends ListenerAdapter {

    @Override
    public void onButtonInteraction(ButtonInteractionEvent event) {
        String id = event.getComponentId();
        if (id == null || !id.startsWith("bj:")) return;

        // prevent "This interaction failed"
        event.deferEdit().queue();

        GuildMessageChannel channel = event.getChannel().asGuildMessageChannel();

        // Handle bet buttons like bj:bet:50 (increment staged bet)
        if (id.startsWith(BlackjackGame.BTN_BET_PREFIX)) {
            try {
                int amt = Integer.parseInt(id.substring(BlackjackGame.BTN_BET_PREFIX.length()));
                BlackjackGame.placeBet(event.getUser(), amt, channel);
            } catch (Exception ignored) { }
            return;
        }

        switch (id) {
            case BlackjackGame.BTN_JOIN -> BlackjackGame.join(event.getUser(), channel);
            case BlackjackGame.BTN_LEAVE -> BlackjackGame.leave(event.getUser(), channel);

            case BlackjackGame.BTN_BUYIN -> BlackjackGame.buyIn(event.getUser(), channel);
            case BlackjackGame.BTN_BET_CLEAR -> BlackjackGame.clearBet(event.getUser(), channel);
            case BlackjackGame.BTN_BET_CONFIRM -> BlackjackGame.confirmBet(event.getUser(), channel);

            case BlackjackGame.BTN_HIT -> BlackjackGame.action(event.getUser(), BlackjackGame.Action.HIT);
            case BlackjackGame.BTN_STAND -> BlackjackGame.action(event.getUser(), BlackjackGame.Action.STAND);
            case BlackjackGame.BTN_DOUBLE -> BlackjackGame.action(event.getUser(), BlackjackGame.Action.DOUBLE);
            case BlackjackGame.BTN_SPLIT -> BlackjackGame.action(event.getUser(), BlackjackGame.Action.SPLIT);

            case BlackjackGame.BTN_HIST_PREV -> BlackjackGame.historyPrev(channel);
            case BlackjackGame.BTN_HIST_NEXT -> BlackjackGame.historyNext(channel);

            default -> { }
        }
    }
}
