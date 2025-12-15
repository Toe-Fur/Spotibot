package com.example.bot;

import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;

public class BlackjackUiListener extends ListenerAdapter {
    @Override
    public void onButtonInteraction(ButtonInteractionEvent event) {
        String id = event.getComponentId();
        if (id == null || !id.startsWith("bj:")) return;

        event.deferEdit().queue();

        var user = event.getUser();
        var channel = event.getChannel().asGuildMessageChannel();

        switch (id) {
            case BlackjackGame.BTN_JOIN -> BlackjackGame.join(user, channel);
            case BlackjackGame.BTN_LEAVE -> BlackjackGame.leave(user, channel);
            case BlackjackGame.BTN_HIT -> BlackjackGame.action(user, BlackjackGame.Action.HIT);
            case BlackjackGame.BTN_STAND -> BlackjackGame.action(user, BlackjackGame.Action.STAND);
            case BlackjackGame.BTN_DOUBLE -> BlackjackGame.action(user, BlackjackGame.Action.DOUBLE);
            case BlackjackGame.BTN_SPLIT -> BlackjackGame.action(user, BlackjackGame.Action.SPLIT);
        }
    }
}
