package com.example.bot;

import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.components.actionrow.ActionRow;
import net.dv8tion.jda.api.components.buttons.Button;
import net.dv8tion.jda.api.components.selections.StringSelectMenu;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.channel.middleman.AudioChannel;
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;
import net.dv8tion.jda.api.events.interaction.component.GenericComponentInteractionCreateEvent;
import net.dv8tion.jda.api.events.interaction.component.StringSelectInteractionEvent;
import net.dv8tion.jda.api.interactions.InteractionHook;

import java.awt.Color;
import java.util.ArrayList;
import java.util.List;

/** Buttons and dropdowns on the queue panel and on the private views (More, Lyrics, History, Search). */
public final class Interactions {
    private Interactions() {
    }

    // ------------------------------------------------------------------------------------------- buttons

    public static void button(ButtonInteractionEvent e, Spotibot bot) {
        String id = e.getComponentId();
        boolean paging = id.equals(CommandHandler.BTN_QUEUE_PREV) || id.equals(CommandHandler.BTN_QUEUE_NEXT);
        if (!paging && !id.startsWith(PlayerControls.BTN_PREFIX)) return;
        Guild guild = e.getGuild();
        if (guild == null) return;
        Member m = e.getMember();
        String who = m != null ? m.getEffectiveName() : e.getUser().getName();
        TrackScheduler ts = bot.getTrackSchedulerRegistry().getOrCreate(guild, bot);
        QueuePanel panel = QueuePanel.of(guild.getIdLong());
        panel.bindScheduler(ts);
        boolean privateView = e.getMessage().isEphemeral();

        // views that open privately
        if (id.equals(PlayerControls.BTN_MORE)) {
            e.replyEmbeds(panel.moreEmbed().build()).setComponents(panel.moreComponents()).setEphemeral(true).queue();
            return;
        }
        if (id.equals(PlayerControls.BTN_LYRICS)) {
            e.deferReply(true).queue(h -> sendLyrics(h, ts));
            return;
        }
        if (id.equals(PlayerControls.BTN_HISTORY)) {
            showHistory(e, guild);
            return;
        }
        if (id.equals("sb:replay")) {
            AudioChannel vc = voice(m);
            if (vc == null) {
                e.reply("Join a voice channel first.").setEphemeral(true).queue();
                return;
            }
            List<String> reqs = History.previousSession(guild.getIdLong()).stream().map(History.Entry::request).toList();
            if (reqs.isEmpty()) {
                e.reply("No earlier session to replay yet.").setEphemeral(true).queue();
                return;
            }
            e.reply("Replaying " + reqs.size() + " songs from last time.").setEphemeral(true).queue();
            TrackScheduler t = VoiceSession.join(guild, vc, e.getGuildChannel());
            DownloadQueueHandler.queueAll(reqs, "Replaying last session (" + reqs.size() + " songs) - " + who, false,
                    t, e.getGuildChannel(), guild, bot.getDownloadQueue(), m);
            return;
        }
        if (id.startsWith(PlayerControls.BTN_QACT)) {
            String[] p = id.substring(PlayerControls.BTN_QACT.length()).split(":");
            String r = PlayerControls.queueAction(ts, m, Long.parseLong(p[1]), p[0]);
            e.editMessage(r).setEmbeds().setComponents().queue();
            panel.setNotice(r);
            return;
        }

        String action = paging ? "page" : actionOf(id);
        String denied = PlayerControls.check(m, action);
        if (denied != null) {
            e.reply(denied).setEphemeral(true).queue();
            return;
        }
        switch (action) {
            case "leave" -> {
                e.deferEdit().queue();
                VoiceSession.leave(guild, "dismissed by " + who);
                return;
            }
            case "stop" -> {
                e.deferEdit().queue();
                CommandHandler.handleStopCommand(guild, e.getGuildChannel(), ts, PlayerControls.serverFolder(guild), bot,
                        bot.getTrackSchedulerRegistry(), bot.getDownloadQueue(), who, true);
                return;
            }
            case "save" -> {
                e.deferEdit().queue();
                String r = PlayerControls.save(ts, who);
                panel.setNotice(r == null ? "Nothing is playing right now." : r);
                return;
            }
            default -> { }
        }
        String result = switch (action) {
            case "page" -> {
                panel.setPage(id.equals(CommandHandler.BTN_QUEUE_NEXT) ? panel.page() + 1 : panel.page() - 1);
                yield "";
            }
            case "pause" -> PlayerControls.togglePause(ts, who);
            case "skip" -> PlayerControls.skipBy(ts, m);
            case "gaming" -> PlayerControls.gaming(guild, null, who);
            case "radio" -> PlayerControls.radio(ts, null, who);
            case "shuffle" -> PlayerControls.shuffle(ts, who);
            case "repeat" -> PlayerControls.repeat(ts, null, who);
            case "restart" -> PlayerControls.restartSong(ts, who);
            case "dj" -> PlayerControls.djMode(m, null);
            case "volume" -> PlayerControls.volume(ts, Math.max(0, Math.min(100,
                    ts.getPlayer().getVolume() + Integer.parseInt(id.substring(PlayerControls.BTN_VOL.length())))), who);
            case "seek" -> PlayerControls.seek(ts, Long.parseLong(id.substring(PlayerControls.BTN_SEEK.length())), who);
            default -> "";
        };
        if (result == null) result = "Nothing is playing right now.";
        if (privateView) {
            panel.setNotice(result);                       // shared panel catches up
            e.editMessageEmbeds(panel.moreEmbed().build()).setComponents(panel.moreComponents()).queue(null, x -> {});
        } else {
            if (!result.isEmpty()) panel.setNoticeQuiet(result);
            e.editMessageEmbeds(panel.render().build()).setComponents(panel.components()).queue(null, x -> {});
        }
    }

    private static String actionOf(String id) {
        if (id.startsWith(PlayerControls.BTN_SEEK)) return "seek";
        if (id.startsWith(PlayerControls.BTN_VOL)) return "volume";
        return switch (id) {
            case PlayerControls.BTN_PAUSE -> "pause";
            case PlayerControls.BTN_SKIP -> "skip";
            case PlayerControls.BTN_STOP -> "stop";
            case PlayerControls.BTN_LEAVE -> "leave";
            case PlayerControls.BTN_GAMING -> "gaming";
            case PlayerControls.BTN_RADIO -> "radio";
            case PlayerControls.BTN_SHUFFLE -> "shuffle";
            case PlayerControls.BTN_REPEAT -> "repeat";
            case PlayerControls.BTN_SAVE -> "save";
            case PlayerControls.BTN_RESTART -> "restart";
            case PlayerControls.BTN_DJ -> "dj";
            default -> "unknown";
        };
    }

    // ------------------------------------------------------------------------------------------- dropdowns

    public static void select(StringSelectInteractionEvent e, Spotibot bot) {
        String id = e.getComponentId();
        if (!id.startsWith(PlayerControls.BTN_PREFIX) || e.getGuild() == null) return;
        Guild guild = e.getGuild();
        Member m = e.getMember();
        String who = m != null ? m.getEffectiveName() : e.getUser().getName();
        String value = e.getValues().isEmpty() ? "" : e.getValues().get(0);
        TrackScheduler ts = bot.getTrackSchedulerRegistry().getOrCreate(guild, bot);
        QueuePanel panel = QueuePanel.of(guild.getIdLong());
        panel.bindScheduler(ts);

        switch (id) {
            case PlayerControls.MENU_IDLE, PlayerControls.MENU_FILTER -> {
                String denied = PlayerControls.check(m, id.equals(PlayerControls.MENU_IDLE) ? "idle" : "filter");
                if (denied != null) {
                    e.reply(denied).setEphemeral(true).queue();
                    return;
                }
                String r = id.equals(PlayerControls.MENU_IDLE)
                        ? PlayerControls.setIdle(guild, parseInt(value), who)
                        : PlayerControls.filter(ts, SoundFilters.Filter.valueOf(value), who);
                panel.setNotice(r);
                if (e.getMessage().isEphemeral()) {
                    e.editMessageEmbeds(panel.moreEmbed().build()).setComponents(panel.moreComponents()).queue(null, x -> {});
                } else {
                    e.editMessageEmbeds(panel.render().build()).setComponents(panel.components()).queue(null, x -> {});
                }
            }
            case PlayerControls.MENU_QUEUE -> {
                long uid = Long.parseLong(value);
                AudioTrack t = ts.findQueued(uid);
                if (t == null) {
                    e.reply("That song isn't in the queue any more.").setEphemeral(true).queue();
                    return;
                }
                StreamTrack meta = ts.metaOf(t);
                String title = ts.getTitle(t.getIdentifier());
                e.reply("**" + (title != null ? title : t.getInfo().title) + "**"
                                + (meta != null && meta.requesterName != null ? "  (added by " + meta.requesterName + ")" : ""))
                        .setComponents(ActionRow.of(
                                Button.primary(PlayerControls.BTN_QACT + "next:" + uid, "Play next"),
                                Button.secondary(PlayerControls.BTN_QACT + "last:" + uid, "Move to end"),
                                Button.danger(PlayerControls.BTN_QACT + "remove:" + uid, "Remove")))
                        .setEphemeral(true).queue();
                // reset the dropdown on the shared panel
                panel.update();
            }
            case PlayerControls.MENU_HISTORY, PlayerControls.MENU_SEARCH -> {
                AudioChannel vc = voice(m);
                if (vc == null) {
                    e.reply("Join a voice channel first.").setEphemeral(true).queue();
                    return;
                }
                String request = id.equals(PlayerControls.MENU_SEARCH) ? "https://www.youtube.com/watch?v=" + value : value;
                e.editMessage("Adding it to the queue.").setEmbeds().setComponents().queue();
                CommandHandler.play(guild, vc, e.getGuildChannel(), request, bot, bot.getDownloadQueue(), null, false, m);
            }
            default -> { }
        }
    }

    // ------------------------------------------------------------------------------------------- views

    static void sendLyrics(InteractionHook h, TrackScheduler ts) {
        AudioTrack cur = ts.getPlayer().getPlayingTrack();
        if (cur == null) {
            h.sendMessage("Nothing is playing right now.").queue();
            return;
        }
        StreamTrack meta = ts.metaOf(cur);
        String title = meta != null ? meta.songTitle : ts.getTitle(cur.getIdentifier());
        String artist = meta != null ? meta.songArtist : "";
        if ((artist == null || artist.isBlank()) && title != null && title.contains(" - ")) {
            artist = title.substring(title.indexOf(" - ") + 3);
            title = title.substring(0, title.indexOf(" - "));
        }
        Lyrics.Result r = Lyrics.find(artist, title);
        if (r == null) {
            h.sendMessage("No lyrics found for " + (title == null ? "this song" : title) + ".").queue();
            return;
        }
        List<EmbedBuilder> pages = new ArrayList<>();
        String text = r.text();
        while (!text.isEmpty() && pages.size() < 3) {
            int cut = Math.min(text.length(), 3900);
            if (cut < text.length()) {
                int nl = text.lastIndexOf('\n', cut);
                if (nl > 2000) cut = nl;
            }
            pages.add(new EmbedBuilder().setColor(new Color(0x1db954)).setDescription(text.substring(0, cut)));
            text = text.substring(cut).trim();
        }
        pages.get(0).setTitle(r.title() + " - " + r.artist());
        pages.get(pages.size() - 1).setFooter("Lyrics from lrclib.net");
        h.sendMessageEmbeds(pages.stream().map(EmbedBuilder::build).toList()).queue();
    }

    static void showHistory(GenericComponentInteractionCreateEvent e, Guild guild) {
        List<History.Entry> recent = History.recent(guild.getIdLong(), 25);
        if (recent.isEmpty()) {
            e.reply("Nothing played yet.").setEphemeral(true).queue();
            return;
        }
        e.replyEmbeds(historyEmbed(recent).build()).setComponents(historyComponents(recent)).setEphemeral(true).queue();
    }

    static EmbedBuilder historyEmbed(List<History.Entry> recent) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < Math.min(15, recent.size()); i++) {
            History.Entry h = recent.get(i);
            sb.append("`").append(History.when(h.time())).append("` ").append(h.display())
                    .append(h.by() == null || h.by().isBlank() ? "" : "  _(" + h.by() + ")_").append('\n');
        }
        return new EmbedBuilder().setColor(new Color(0x1db954)).setTitle("Recently played").setDescription(sb.toString())
                .setFooter("Pick one to play it again, or replay the whole last session");
    }

    static List<ActionRow> historyComponents(List<History.Entry> recent) {
        StringSelectMenu.Builder menu = StringSelectMenu.create(PlayerControls.MENU_HISTORY).setPlaceholder("Play one again");
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (History.Entry h : recent) {
            String req = h.request();
            if (req.length() > 100 || !seen.add(req)) continue;
            String label = h.display().length() > 100 ? h.display().substring(0, 97) + "..." : h.display();
            menu.addOption(label, req, History.when(h.time()));
            if (menu.getOptions().size() >= 25) break;
        }
        List<ActionRow> rows = new ArrayList<>();
        if (!menu.getOptions().isEmpty()) rows.add(ActionRow.of(menu.build()));
        rows.add(ActionRow.of(Button.secondary("sb:replay", "Replay last session")));
        return rows;
    }

    static AudioChannel voice(Member m) {
        return m == null || m.getVoiceState() == null ? null : m.getVoiceState().getChannel();
    }

    private static int parseInt(String s) {
        try {
            return Integer.parseInt(s);
        } catch (Exception e) {
            return 30;
        }
    }
}
