package com.example.bot;

import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.channel.middleman.AudioChannel;
import net.dv8tion.jda.api.entities.channel.middleman.GuildMessageChannel;
import net.dv8tion.jda.api.events.interaction.command.CommandAutoCompleteInteractionEvent;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.interactions.commands.DefaultMemberPermissions;
import net.dv8tion.jda.api.interactions.commands.OptionMapping;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.CommandData;
import net.dv8tion.jda.api.interactions.commands.build.Commands;
import net.dv8tion.jda.api.interactions.commands.build.OptionData;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/** Slash commands (/play with autocomplete, /skip, /gaming ...). Same actions as the buttons and ! commands. */
public final class SlashCommands {
    private static final Logger logger = LoggerFactory.getLogger(SlashCommands.class);

    private SlashCommands() {
    }

    static List<CommandData> definitions() {
        List<CommandData> c = new ArrayList<>();
        c.add(Commands.slash("play", "Play a song, link or playlist URL")
                .addOptions(new OptionData(OptionType.STRING, "song", "Song name, YouTube or Spotify link", true, StreamTrack.enabled()),
                        new OptionData(OptionType.BOOLEAN, "next", "Play it right after the current song", false)));
        c.add(Commands.slash("join", "Join your voice channel and wait for requests"));
        c.add(Commands.slash("leave", "Dismiss the bot from the voice channel"));
        c.add(Commands.slash("skip", "Skip the current song"));
        c.add(Commands.slash("pause", "Pause"));
        c.add(Commands.slash("resume", "Resume"));
        c.add(Commands.slash("stop", "Stop music and clear the queue (the bot stays in the channel)"));
        c.add(Commands.slash("queue", "Bring the queue message to the bottom of the chat"));
        c.add(Commands.slash("np", "What's playing right now"));
        c.add(Commands.slash("shuffle", "Shuffle the queue"));
        c.add(Commands.slash("repeat", "Repeat mode")
                .addOptions(new OptionData(OptionType.STRING, "mode", "off, this song, or the whole queue", true)
                        .addChoice("off", "off").addChoice("song", "song").addChoice("queue", "queue")));
        c.add(Commands.slash("volume", "Set the volume")
                .addOptions(new OptionData(OptionType.INTEGER, "level", "0-100", true).setRequiredRange(0, 100)));
        if (StreamTrack.enabled()) {
            c.add(Commands.slash("radio", "Keep playing similar songs when the queue runs out")
                    .addOptions(new OptionData(OptionType.STRING, "mode", "on, off, or now (songs like this one, next)", true)
                            .addChoice("on", "on").addChoice("off", "off").addChoice("now", "now")));
        }
        if (VoiceListener.available()) {
            c.add(Commands.slash("gaming", "Gaming mode: control the bot by voice (\"Spotibot, play ...\")")
                    .addOptions(new OptionData(OptionType.STRING, "mode", "on or off", true)
                            .addChoice("on", "on").addChoice("off", "off")));
        }
        if (JellyfinClient.enabled()) {
            c.add(Commands.slash("playlist", "Play one of your Jellyfin playlists")
                    .addOptions(new OptionData(OptionType.STRING, "name", "Playlist name", true, true)));
            c.add(Commands.slash("save", "Save the current song to your Jellyfin favorites (downloads it)"));
        }
        c.add(Commands.slash("settings", "Server settings for Spotibot")
                .addOptions(new OptionData(OptionType.INTEGER, "idle_minutes", "Leave after this many idle minutes (0 = never)", false).setRequiredRange(0, 240),
                        new OptionData(OptionType.BOOLEAN, "tidy", "Delete typed control commands", false))
                .setDefaultPermissions(DefaultMemberPermissions.enabledFor(Permission.MANAGE_SERVER)));
        c.add(Commands.slash("help", "How to use Spotibot"));
        return c;
    }

    public static void register(Guild guild) {
        guild.updateCommands().addCommands(definitions()).queue(
                ok -> logger.info("Slash commands registered for {}", guild.getName()),
                e -> logger.warn("Slash commands not registered for {} (re-invite the bot with the applications.commands scope): {}",
                        guild.getName(), e.getMessage()));
    }

    public static void autocomplete(CommandAutoCompleteInteractionEvent e) {
        String value = e.getFocusedOption().getValue();
        List<String> out = switch (e.getName()) {
            case "play" -> StreamTrack.suggest(value);
            case "playlist" -> JellyfinClient.suggestPlaylists(value);
            default -> List.of();
        };
        e.replyChoiceStrings(out.stream().filter(s -> !s.isBlank()).map(s -> s.length() > 100 ? s.substring(0, 100) : s)
                .limit(25).toList()).queue(null, x -> {});
    }

    public static void handle(SlashCommandInteractionEvent e, Spotibot bot) {
        Guild guild = e.getGuild();
        if (guild == null || !(e.getChannel() instanceof GuildMessageChannel ch)) {
            e.reply("Use this in a server channel.").setEphemeral(true).queue();
            return;
        }
        Member member = e.getMember();
        String who = member != null ? member.getEffectiveName() : e.getUser().getName();
        AudioChannel vc = member != null && member.getVoiceState() != null ? member.getVoiceState().getChannel() : null;
        TrackScheduler ts = bot.getTrackSchedulerRegistry().getOrCreate(guild, bot);
        String result;

        switch (e.getName()) {
            case "play" -> {
                if (vc == null) {
                    e.reply("Join a voice channel first.").setEphemeral(true).queue();
                    return;
                }
                String song = opt(e, "song");
                boolean next = e.getOption("next") != null && e.getOption("next").getAsBoolean();
                e.reply((next ? "Up next: " : "Adding: ") + song).setEphemeral(true).queue();
                CommandHandler.play(guild, vc, ch, song, bot, bot.getDownloadQueue(), null, next);
                return;
            }
            case "playlist" -> {
                if (vc == null) {
                    e.reply("Join a voice channel first.").setEphemeral(true).queue();
                    return;
                }
                e.reply("Loading playlist " + opt(e, "name") + "...").setEphemeral(true).queue();
                CommandHandler.playPlaylist(guild, vc, ch, opt(e, "name"), bot, who);
                return;
            }
            case "join" -> {
                if (vc == null) {
                    e.reply("Join a voice channel first.").setEphemeral(true).queue();
                    return;
                }
                TrackScheduler t = VoiceSession.join(guild, vc, ch);
                QueuePanel.of(guild.getIdLong()).attach(ch, t, "Joined " + vc.getName() + " (" + who + ")");
                result = "Joined " + vc.getName();
            }
            case "leave" -> {
                if (!VoiceSession.isConnected(guild)) {
                    e.reply("I'm not in a voice channel.").setEphemeral(true).queue();
                    return;
                }
                e.reply("Bye!").setEphemeral(true).queue();
                VoiceSession.leave(guild, "dismissed by " + who);
                return;
            }
            case "stop" -> {
                e.reply("Stopped.").setEphemeral(true).queue();
                CommandHandler.handleStopCommand(guild, ch, ts, PlayerControls.serverFolder(guild), bot,
                        bot.getTrackSchedulerRegistry(), bot.getDownloadQueue(), who, true);
                return;
            }
            case "skip" -> result = PlayerControls.skip(ts, who);
            case "pause" -> result = PlayerControls.setPaused(ts, true, who);
            case "resume" -> result = PlayerControls.setPaused(ts, false, who);
            case "np" -> result = PlayerControls.nowPlaying(ts);
            case "shuffle" -> result = PlayerControls.shuffle(ts, who);
            case "repeat" -> result = PlayerControls.repeat(ts, switch (opt(e, "mode")) {
                case "song" -> TrackScheduler.Repeat.ONE;
                case "queue" -> TrackScheduler.Repeat.ALL;
                default -> TrackScheduler.Repeat.OFF;
            }, who);
            case "volume" -> result = PlayerControls.volume(ts, e.getOption("level").getAsInt(), who);
            case "radio" -> result = "now".equals(opt(e, "mode")) ? PlayerControls.radioNow(ts, ch, who)
                    : PlayerControls.radio(ts, "on".equals(opt(e, "mode")), who);
            case "gaming" -> result = PlayerControls.gaming(guild, "on".equals(opt(e, "mode")), who);
            case "save" -> {
                e.deferReply(true).queue();
                String r = PlayerControls.save(ts, who);
                e.getHook().sendMessage(r == null ? "Nothing is playing right now." : r).queue();
                notice(guild, r);
                return;
            }
            case "queue" -> {
                QueuePanel.of(guild.getIdLong()).attach(ch, ts, null);
                result = "Queue moved to the bottom of the chat";
            }
            case "settings" -> {
                GuildSettings gs = GuildSettings.of(guild.getIdLong());
                OptionMapping idle = e.getOption("idle_minutes"), tidy = e.getOption("tidy");
                if (idle != null) gs.idleMinutes = idle.getAsInt();
                if (tidy != null) gs.tidy = tidy.getAsBoolean();
                if (idle != null || tidy != null) GuildSettings.save();
                e.reply(gs.describe()).setEphemeral(true).queue();
                return;
            }
            case "help" -> {
                e.reply("Everything Spotibot can do (buttons, gaming mode, radio, playlists): " + QueuePanel.GUIDE_URL)
                        .setEphemeral(true).queue();
                return;
            }
            default -> {
                e.reply("Unknown command.").setEphemeral(true).queue();
                return;
            }
        }
        String text = result == null ? "Nothing is playing right now." : result;
        e.reply(text).setEphemeral(true).queue();
        notice(guild, result);
    }

    private static void notice(Guild guild, String text) {
        QueuePanel p = QueuePanel.peek(guild.getIdLong());
        if (p != null && text != null) p.setNotice(text);
    }

    private static String opt(SlashCommandInteractionEvent e, String name) {
        OptionMapping o = e.getOption(name);
        return o == null ? "" : o.getAsString().trim();
    }
}
