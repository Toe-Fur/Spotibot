package com.example.bot;

import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.User;
import net.dv8tion.jda.api.entities.channel.middleman.GuildMessageChannel;
import net.dv8tion.jda.api.entities.channel.concrete.VoiceChannel;
import net.dv8tion.jda.api.entities.channel.middleman.AudioChannel;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;
import net.dv8tion.jda.api.components.actionrow.ActionRow;
import net.dv8tion.jda.api.components.buttons.Button;
import com.sedmelluq.discord.lavaplayer.player.AudioPlayerManager;
import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import java.awt.Color;
import java.io.IOException;
import java.util.concurrent.BlockingQueue;
import java.util.Map;
import java.util.List;
import java.util.ArrayList;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.Member;

public class CommandHandler {
    private static final Logger logger = LoggerFactory.getLogger(CommandHandler.class);

    public static final String BTN_QUEUE_PREV = "queue:prev";
    public static final String BTN_QUEUE_NEXT = "queue:next";

    public static void handleMessage(MessageReceivedEvent event, Spotibot bot, AudioPlayerManager playerManager, TrackSchedulerRegistry trackSchedulerRegistry, BlockingQueue<Runnable> downloadQueue) {
        String message = event.getMessage().getContentRaw();
        String lower = message.trim().toLowerCase();
        GuildMessageChannel messageChannel = event.getChannel().asGuildMessageChannel();
        Guild guild = event.getGuild();
        TrackScheduler trackScheduler = trackSchedulerRegistry.getOrCreate(guild, bot);

        String serverFolder = ConfigUtils.BASE_DOWNLOAD_FOLDER + guild.getId() + "/";

        // DJ mode: typed control commands follow the same rules as the buttons
        String djAction = lower.matches("!(pause|resume)") ? "pause"
                : lower.matches("!(stop|leave|shuffle)") ? lower.substring(1)
                : lower.startsWith("!repeat") || lower.startsWith("!loop") ? "repeat"
                : lower.startsWith("!volume ") ? "volume"
                : lower.startsWith("!radio") ? "radio"
                : lower.startsWith("!gaming") || lower.startsWith("!listen") ? "gaming"
                : lower.startsWith("!filter") ? "filter" : "";
        String denied = PlayerControls.check(event.getMember(), djAction);
        if (denied != null) {
            status(event, trackScheduler, denied);
            return;
        }

        if (message.startsWith("!play ")) {
            String input = message.replace("!play ", "").trim();
            AudioChannel voiceChannel = event.getMember() == null || event.getMember().getVoiceState() == null
                    ? null : event.getMember().getVoiceState().getChannel();
            if (voiceChannel == null) {
                PlayerControls.flash(messageChannel, "You must be in a voice channel to use this command.");
                return;
            }
            play(guild, voiceChannel, messageChannel, input, bot, downloadQueue, null, false, event.getMember());

        } else if (message.equalsIgnoreCase("!join")) {
            tidy(event);
            AudioChannel voiceChannel = event.getMember() == null || event.getMember().getVoiceState() == null
                    ? null : event.getMember().getVoiceState().getChannel();
            if (voiceChannel == null) {
                PlayerControls.flash(messageChannel, "Join a voice channel first, then type !join.");
                return;
            }
            TrackScheduler ts = VoiceSession.join(guild, voiceChannel, messageChannel);
            QueuePanel.of(guild.getIdLong()).attach(messageChannel, ts, "Joined " + voiceChannel.getName() + " (" + who(event) + ")");

        } else if (message.equalsIgnoreCase("!leave")) {
            tidy(event);
            if (VoiceSession.isConnected(guild)) VoiceSession.leave(guild, "dismissed by " + who(event));
            else PlayerControls.flash(messageChannel, "I'm not in a voice channel.");

        } else if (lower.startsWith("!gaming") || lower.startsWith("!listen")) {
            String arg = lower.replaceFirst("^!(gaming|listen)\\s*", "").replace("mode", "").trim();
            Boolean on = arg.equals("on") ? Boolean.TRUE : arg.equals("off") ? Boolean.FALSE : null;
            status(event, trackScheduler, PlayerControls.gaming(guild, on, who(event)));

        } else if (lower.startsWith("!radio")) {
            String arg = lower.substring(6).trim();
            if (arg.equals("now") || arg.equals("like this")) {
                status(event, trackScheduler, PlayerControls.radioNow(trackScheduler, messageChannel, who(event)));
            } else {
                Boolean on = arg.equals("on") ? Boolean.TRUE : arg.equals("off") ? Boolean.FALSE : null;
                status(event, trackScheduler, PlayerControls.radio(trackScheduler, on, who(event)));
            }

        } else if (lower.equals("!shuffle")) {
            status(event, trackScheduler, PlayerControls.shuffle(trackScheduler, who(event)));

        } else if (lower.startsWith("!repeat") || lower.startsWith("!loop")) {
            String arg = lower.replaceFirst("^!(repeat|loop)\\s*", "");
            TrackScheduler.Repeat r = switch (arg) {
                case "off" -> TrackScheduler.Repeat.OFF;
                case "one", "song", "this" -> TrackScheduler.Repeat.ONE;
                case "all", "queue" -> TrackScheduler.Repeat.ALL;
                default -> null;
            };
            status(event, trackScheduler, PlayerControls.repeat(trackScheduler, r, who(event)));

        } else if (lower.equals("!save") || lower.equals("!like")) {
            status(event, trackScheduler, PlayerControls.save(trackScheduler, who(event)));

        } else if (lower.startsWith("!playnext ")) {
            AudioChannel vc = event.getMember() == null || event.getMember().getVoiceState() == null
                    ? null : event.getMember().getVoiceState().getChannel();
            if (vc == null) {
                PlayerControls.flash(messageChannel, "You must be in a voice channel to use this command.");
                return;
            }
            play(guild, vc, messageChannel, message.substring(10).trim(), bot, downloadQueue, null, true, event.getMember());

        } else if (lower.equals("!playlists")) {
            tidy(event);
            if (!JellyfinClient.enabled()) {
                PlayerControls.flash(messageChannel, "Playlists need Jellyfin (JELLYFIN_URL) - see the guide.");
            } else {
                StringBuilder sb = new StringBuilder("**Playlists** (play one with `!playlist <name>`)\n");
                for (JellyfinClient.Playlist p : JellyfinClient.playlists()) sb.append("- ").append(p.name()).append('\n');
                String txt = sb.length() > 1900 ? sb.substring(0, 1900) + "..." : sb.toString();
                messageChannel.sendMessage(txt).queue(m -> m.delete().queueAfter(60, TimeUnit.SECONDS, null, e -> {}), e -> {});
            }

        } else if (lower.startsWith("!playlist ")) {
            AudioChannel vc = event.getMember() == null || event.getMember().getVoiceState() == null
                    ? null : event.getMember().getVoiceState().getChannel();
            if (vc == null) {
                PlayerControls.flash(messageChannel, "You must be in a voice channel to use this command.");
                return;
            }
            playPlaylist(guild, vc, messageChannel, message.substring(10).trim(), bot, who(event), event.getMember());

        } else if (lower.startsWith("!settings")) {
            String[] parts = lower.split("\\s+");
            GuildSettings gs = GuildSettings.of(guild.getIdLong());
            Member mem = event.getMember();
            boolean admin = mem != null && (mem.hasPermission(Permission.MANAGE_SERVER) || mem.isOwner());
            if (parts.length >= 3 && admin) {
                switch (parts[1]) {
                    case "idle" -> {
                        try {
                            gs.idleMinutes = parts[2].equals("never") ? 0 : Math.max(0, Math.min(240, Integer.parseInt(parts[2])));
                        } catch (NumberFormatException ignored) {
                        }
                    }
                    case "tidy" -> gs.tidy = parts[2].equals("on");
                    case "recap" -> gs.recap = parts[2].equals("on");
                    case "gaming" -> PlayerControls.gaming(guild, parts[2].equals("on"), who(event));
                    case "radio" -> gs.radio = parts[2].equals("on") && StreamTrack.enabled();
                    default -> { }
                }
                GuildSettings.save();
            } else if (parts.length >= 3) {
                status(event, trackScheduler, "Only server managers can change settings");
                return;
            }
            status(event, trackScheduler, gs.describe() + "  (change: !settings idle <min|never> | tidy on/off | recap on/off | gaming on/off | radio on/off)");

        } else if (lower.startsWith("!filter")) {
            SoundFilters.Filter f = SoundFilters.Filter.parse(lower.substring(7).trim());
            status(event, trackScheduler, PlayerControls.filter(trackScheduler, f, who(event)) + "  (filters: bass, nightcore, slowed, 8d, off)");

        } else if (lower.equals("!lyrics")) {
            tidy(event);
            Lyrics.Result r = null;
            var cur = trackScheduler.getPlayer().getPlayingTrack();
            if (cur != null) {
                StreamTrack m = trackScheduler.metaOf(cur);
                r = Lyrics.find(m != null ? m.songArtist : "", m != null ? m.songTitle : cur.getInfo().title);
            }
            if (r == null) {
                PlayerControls.flash(messageChannel, cur == null ? "Nothing is playing right now." : "No lyrics found for this song.");
            } else {
                String txt = r.text().length() > 3900 ? r.text().substring(0, 3900) + "\n..." : r.text();
                messageChannel.sendMessageEmbeds(new EmbedBuilder().setColor(new Color(0x1db954)).setTitle(r.title() + " - " + r.artist())
                        .setDescription(txt).setFooter("Lyrics from lrclib.net  |  /lyrics shows them only to you").build())
                        .queue(m -> m.delete().queueAfter(5, TimeUnit.MINUTES, null, x -> {}), x -> {});
            }

        } else if (lower.equals("!history") || lower.equals("!replay")) {
            tidy(event);
            if (lower.equals("!history")) {
                var recent = History.recent(guild.getIdLong(), 25);
                if (recent.isEmpty()) PlayerControls.flash(messageChannel, "Nothing played yet.");
                else messageChannel.sendMessageEmbeds(Interactions.historyEmbed(recent).build())
                        .setComponents(Interactions.historyComponents(recent))
                        .queue(m -> m.delete().queueAfter(3, TimeUnit.MINUTES, null, x -> {}), x -> {});
            } else {
                AudioChannel vc = event.getMember() == null || event.getMember().getVoiceState() == null
                        ? null : event.getMember().getVoiceState().getChannel();
                List<String> reqs = History.previousSession(guild.getIdLong()).stream().map(History.Entry::request).toList();
                if (vc == null) PlayerControls.flash(messageChannel, "Join a voice channel first.");
                else if (reqs.isEmpty()) PlayerControls.flash(messageChannel, "No earlier session to replay yet.");
                else {
                    TrackScheduler t = VoiceSession.join(guild, vc, messageChannel);
                    DownloadQueueHandler.queueAll(reqs, "Replaying last session (" + reqs.size() + " songs) - " + who(event), false,
                            t, messageChannel, guild, downloadQueue, event.getMember());
                }
            }

        } else if (lower.startsWith("!dj")) {
            String arg = lower.substring(3).trim();
            status(event, trackScheduler, PlayerControls.djMode(event.getMember(), arg.equals("on") ? Boolean.TRUE : arg.equals("off") ? Boolean.FALSE : null));

        } else if (message.equalsIgnoreCase("!pause") || message.equalsIgnoreCase("!resume")) {
            // metzfam: control commands report on the live panel (and the command message is tidied away)
            status(event, trackScheduler, PlayerControls.setPaused(trackScheduler, message.equalsIgnoreCase("!pause"), who(event)));

        } else if (message.toLowerCase().startsWith("!volume")) {
            String[] parts = message.trim().split("\\s+");
            if (parts.length < 2) {
                status(event, trackScheduler, "Volume is " + trackScheduler.getPlayer().getVolume() + "  (use !volume 0-100)");
            } else {
                try {
                    int vol = Integer.parseInt(parts[1]);
                    status(event, trackScheduler, vol < 0 || vol > 100 ? "Volume must be 0-100"
                            : PlayerControls.volume(trackScheduler, vol, who(event)));
                } catch (NumberFormatException e) {
                    status(event, trackScheduler, "Usage: !volume 0-100");
                }
            }

        } else if (message.equalsIgnoreCase("!skip")) {
            status(event, trackScheduler, PlayerControls.skipBy(trackScheduler, event.getMember()));

        } else if (message.equalsIgnoreCase("!stop")) {
            tidy(event);
            handleStopCommand(guild, messageChannel, trackScheduler, serverFolder, bot, trackSchedulerRegistry, downloadQueue, who(event), true);

        } else if (lower.equals("!np") || lower.equals("!nowplaying")) {
            String np = PlayerControls.nowPlaying(trackScheduler);
            status(event, trackScheduler, np);

        } else if (message.equalsIgnoreCase("!queue")) {
            tidy(event);
            showQueue(event, trackScheduler, 0, bot);

        } else if (message.equalsIgnoreCase("!help")) {
            messageChannel.sendMessage(getHelpMessage()).queue();

        } else if (message.equalsIgnoreCase("!blackjack")) {
            BlackjackGame.startGame(event.getAuthor(), messageChannel);

        } else if (message.equalsIgnoreCase("!blackjack help")) {
            messageChannel.sendMessage(getBlackjackHelpMessage()).queue();

        } else if (message.toLowerCase().startsWith("!funds")) {
            // Usage MUST be: !funds <amount> @user
            Member member = event.getMember();
            if (member == null || event.getGuild() == null) {
                messageChannel.sendMessage("❌ `!funds` can only be used in a server.").queue();
                return;
            }

            boolean isOwner = event.getGuild().getOwnerIdLong() == member.getIdLong();
            boolean isAdmin = member.hasPermission(Permission.ADMINISTRATOR);
            boolean canManageServer = member.hasPermission(Permission.MANAGE_SERVER);
            boolean hasBotAdminRole = member.getRoles().stream()
                    .anyMatch(r -> r.getName().equalsIgnoreCase("Bot Admin"));

            if (!(isOwner || isAdmin || canManageServer || hasBotAdminRole)) {
                messageChannel.sendMessage("❌ You don't have permission to use `!funds`.").queue();
                BlackjackGame.pushRotator(messageChannel, "❌ You don't have permission to use `!funds`.");
                return;
            }

            String[] parts = message.trim().split("\\s+");
            if (parts.length < 3 || event.getMessage().getMentions().getUsers().isEmpty()) {
                messageChannel.sendMessage("Usage: `!funds <amount> @user`").queue();
                BlackjackGame.pushRotator(messageChannel, "Usage: `!funds <amount> @user`");
                return;
            }

            int amount;
            try {
                amount = Integer.parseInt(parts[1]);
            } catch (NumberFormatException e) {
                messageChannel.sendMessage("Invalid amount. Usage: `!funds <amount> @user`").queue();
                BlackjackGame.pushRotator(messageChannel, "`!funds @User 10` → ❌ Invalid amount. Usage: `!funds <amount> @user`");
                return;
            }

            User target = event.getMessage().getMentions().getUsers().get(0);

            BlackjackGame.addBalance(target, amount, messageChannel);

            messageChannel.sendMessage("💰 Funded " + target.getAsMention() + " **$" + amount + "**").queue();
            BlackjackGame.pushRotator(messageChannel, "💰 Funded " + target.getAsMention() + " **$" + amount + "**");

        } else if (message.equalsIgnoreCase("!hit") || message.equalsIgnoreCase("!stand") || message.equalsIgnoreCase("!double")
                || message.equalsIgnoreCase("!split") || message.equalsIgnoreCase("!quit") || message.equalsIgnoreCase("!ledger")) {
            BlackjackCommands.handleCommand(message, event.getAuthor(), messageChannel);
        }
    }

    /**
     * metzfam: shared by !play and the voice command "Spotibot, play ...". Joins/stays in the channel
     * (listening), then queues the request; the live panel shows progress.
     */
    public static void play(Guild guild, AudioChannel voiceChannel, GuildMessageChannel messageChannel, String input,
                            Spotibot bot, BlockingQueue<Runnable> downloadQueue, String notice) {
        play(guild, voiceChannel, messageChannel, input, bot, downloadQueue, notice, false);
    }

    /** Play a Jellyfin playlist by (fuzzy) name. */
    public static void playPlaylist(Guild guild, AudioChannel voiceChannel, GuildMessageChannel messageChannel, String name,
                                    Spotibot bot, String who) {
        playPlaylist(guild, voiceChannel, messageChannel, name, bot, who, null);
    }

    public static void playPlaylist(Guild guild, AudioChannel voiceChannel, GuildMessageChannel messageChannel, String name,
                                    Spotibot bot, String who, Member requester) {
        QueuePanel panel = QueuePanel.peek(guild.getIdLong());
        if (!JellyfinClient.enabled()) {
            PlayerControls.flash(messageChannel, "Playlists need Jellyfin (JELLYFIN_URL) - see the guide.");
            return;
        }
        java.util.concurrent.CompletableFuture.runAsync(() -> {
            JellyfinClient.Playlist pl = JellyfinClient.findPlaylist(name);
            if (pl == null) {
                String msg = "No playlist called \"" + name + "\". Try !playlists";
                if (panel != null) panel.setNotice(msg); else PlayerControls.flash(messageChannel, msg);
                return;
            }
            List<String> tracks = JellyfinClient.playlistTracks(pl);
            if (tracks.isEmpty()) {
                PlayerControls.flash(messageChannel, "Playlist " + pl.name() + " is empty.");
                return;
            }
            TrackScheduler ts = VoiceSession.join(guild, voiceChannel, messageChannel);
            DownloadQueueHandler.queueAll(tracks, "Playlist " + pl.name() + " (" + tracks.size() + " songs)" + (who == null ? "" : " - " + who),
                    false, ts, messageChannel, guild, bot.getDownloadQueue(), requester);
        });
    }

    public static void play(Guild guild, AudioChannel voiceChannel, GuildMessageChannel messageChannel, String input,
                            Spotibot bot, BlockingQueue<Runnable> downloadQueue, String notice, boolean next) {
        play(guild, voiceChannel, messageChannel, input, bot, downloadQueue, notice, next, null);
    }

    public static void play(Guild guild, AudioChannel voiceChannel, GuildMessageChannel messageChannel, String input,
                            Spotibot bot, BlockingQueue<Runnable> downloadQueue, String notice, boolean next, Member requester) {
        TrackScheduler trackScheduler = VoiceSession.join(guild, voiceChannel, messageChannel);
        String serverFolder = PlayerControls.serverFolder(guild);
        try {
            if (input.contains("spotify.com/track")) {
                String trackId = SpotifyUtils.extractSpotifyId(input);
                String trackTitle = SpotifyUtils.getTrackTitle(trackId);
                DownloadQueueHandler.queueAll(List.of(trackTitle), notice, next, trackScheduler, messageChannel, guild, downloadQueue, requester);
            } else if (input.contains("spotify.com/playlist")) {
                String playlistId = SpotifyUtils.extractSpotifyId(input);
                try {
                    List<String> trackTitles = SpotifyUtils.getPlaylistTracks(playlistId);
                    if (trackTitles.isEmpty()) {
                        messageChannel.sendMessage("❌ That Spotify playlist is empty or has no playable tracks.").queue();
                        return;
                    }
                    DownloadQueueHandler.queueAll(trackTitles, "Added Spotify playlist (" + trackTitles.size() + " songs)",
                            false, trackScheduler, messageChannel, guild, downloadQueue, requester);
                } catch (IOException e) {
                    if ("SPOTIFY_NOT_ACCESSIBLE".equals(e.getMessage())) {
                        messageChannel.sendMessage(
                            "❌ **That playlist isn't accessible.**\n" +
                            "Spotify's curated playlists (Discover Weekly, Daily Mixes, etc.) are locked to your account and can't be read by bots.\n\n" +
                            "**Workaround:** Copy the songs into your own Spotify playlist, make it public, and share that link instead."
                        ).queue();
                    } else {
                        messageChannel.sendMessage("❌ Spotify error: " + e.getMessage()).queue();
                    }
                    return;
                }
            } else if (input.contains("youtube.com/playlist")) {
                final GuildMessageChannel ch = messageChannel;
                QueuePanel.of(guild.getIdLong()).attach(messageChannel, trackScheduler, "Fetching YouTube playlist...");
                trackScheduler.incrementPendingDownloads();
                downloadQueue.offer(() -> {
                    try {
                        List<String> videoUrls = DownloadQueueHandler.getYouTubePlaylistUrls(input);
                        if (videoUrls.isEmpty()) {
                            ch.sendMessage("❌ Could not fetch playlist or it's empty.").queue();
                        } else {
                            DownloadQueueHandler.queueAll(videoUrls, "Added YouTube playlist (" + videoUrls.size() + " songs)",
                                    false, trackScheduler, ch, guild, downloadQueue, requester);
                        }
                    } catch (Exception e) {
                        ch.sendMessage("❌ Error fetching playlist: " + e.getMessage()).queue();
                    } finally {
                        trackScheduler.decrementPendingDownloads();
                    }
                });
            } else if (input.contains("youtube.com") || input.contains("youtu.be")) {
                DownloadQueueHandler.queueAll(List.of(input), notice, next, trackScheduler, messageChannel, guild, downloadQueue, requester);
            } else {
                DownloadQueueHandler.queueAll(List.of(input), notice, next, trackScheduler, messageChannel, guild, downloadQueue, requester);
            }
        } catch (Exception e) {
            PlayerControls.flash(messageChannel, "An error occurred: " + e.getMessage());
        }
    }

    /** metzfam: delete a user's control command (needs Manage Messages; silently skipped otherwise). */
    static void tidy(MessageReceivedEvent event) {
        try {
            if (GuildSettings.of(event.getGuild().getIdLong()).tidy
                    && event.getGuild().getSelfMember().hasPermission(event.getGuildChannel(), Permission.MESSAGE_MANAGE)) {
                event.getMessage().delete().queue(null, e -> {});
            }
        } catch (Exception ignored) {
        }
    }

    static String who(MessageReceivedEvent event) {
        Member m = event.getMember();
        return m != null ? m.getEffectiveName() : event.getAuthor().getName();
    }

    /** Show a control result on the live panel, or as a short-lived message when there is no panel. */
    private static void status(MessageReceivedEvent event, TrackScheduler ts, String text) {
        tidy(event);
        GuildMessageChannel ch = event.getChannel().asGuildMessageChannel();
        QueuePanel panel = QueuePanel.peek(event.getGuild().getIdLong());
        if (text == null) {
            PlayerControls.flash(ch, "Nothing is playing right now.");
        } else if (panel != null) {
            panel.bindScheduler(ts);
            panel.setNotice(text);
        } else {
            PlayerControls.flash(ch, text);
        }
    }

    /** Stop music + clear the queue. The bot stays in the channel (listening) until dismissed or idle. */
    public static void handleStopCommand(Guild guild, GuildMessageChannel messageChannel, TrackScheduler trackScheduler, String serverFolder, Spotibot bot, TrackSchedulerRegistry trackSchedulerRegistry, BlockingQueue<Runnable> downloadQueue, String who, boolean announce) {
        logger.info("Stopping playback and clearing the queue...");

        QueuePanel panel = QueuePanel.peek(guild.getIdLong());
        if (panel != null) panel.reset();
        trackScheduler.clearQueueAndStop();
        DownloadQueueHandler.clearDownloadsFolder(serverFolder);

        if (bot != null && bot.getCurrentDownloadTask() != null && !bot.getCurrentDownloadTask().isDone()) {
            bot.getCurrentDownloadTask().cancel(true);
            try {
                bot.getCurrentDownloadTask().get();
            } catch (CancellationException | InterruptedException | ExecutionException e) {
                logger.info("Handled cancellation exception: " + e.getMessage());
            }
            bot.setCurrentDownloadTask(null);
        }

        if (bot != null) {
            bot.getDownloadExecutor().shutdownNow();
            try {
                if (!bot.getDownloadExecutor().awaitTermination(10, TimeUnit.SECONDS)) {
                    logger.warn("Executor did not terminate in time; forcing shutdown...");
                    bot.getDownloadExecutor().shutdownNow();
                }
            } catch (InterruptedException e) {
                logger.error("Executor shutdown interrupted", e);
            }

            bot.getDownloadQueue().clear();
            bot.setDownloadExecutor(Executors.newCachedThreadPool());
            bot.startDownloadQueueProcessorPublic();
        }

        if (trackSchedulerRegistry != null) {
            trackSchedulerRegistry.reset();
        }

        if (panel != null) {
            panel.bindScheduler(trackSchedulerRegistry != null ? trackSchedulerRegistry.getOrCreate(guild, bot) : trackScheduler);
            panel.setNotice("Stopped" + (who == null ? "" : " (" + who + ")"));
        } else if (announce && messageChannel != null) {
            PlayerControls.flash(messageChannel, "Stopped playback and cleared the queue.");
        }
    }

    static void showQueue(MessageReceivedEvent event, TrackScheduler trackScheduler, int page, Spotibot bot) {
        // metzfam: the queue is the single live panel; !queue just brings it to the bottom of the chat
        QueuePanel.of(event.getGuild().getIdLong()).attach(event.getChannel().asGuildMessageChannel(), trackScheduler, null);
    }

    private static String getHelpMessage() {
        StringBuilder h = new StringBuilder("**Spotibot**  -  full guide: <" + QueuePanel.GUIDE_URL + ">\n");
        h.append("`!play <song or link>` (or `/play`) - YouTube/Spotify songs and playlists. `!playnext <song>` puts it next.\n");
        h.append("The queue message has buttons for pause, skip, stop, leave, -10s/-5s/+5s/+10s, shuffle and repeat.\n");
        h.append("`!queue` - bring the queue message down  |  `!np` - what's playing  |  `!volume <0-100>`\n");
        h.append("`!join` / `!leave` - the bot stays in voice until dismissed or idle\n");
        h.append("`!shuffle`  |  `!repeat off|song|queue`  |  `!filter bass|nightcore|slowed|8d|off`  |  `!lyrics`\n");
        h.append("`/search <song>` - pick the right version  |  `!history` / `!replay` - play last session again\n");
        h.append("`!dj on|off` (server owner) - only DJs control playback; others vote to skip\n");
        if (StreamTrack.enabled()) h.append("`!radio on|off|now` - keep playing similar songs when the queue ends\n");
        if (JellyfinClient.enabled()) h.append("`!playlists` / `!playlist <name>` - Jellyfin playlists  |  `!save` - favorite in Jellyfin\n");
        if (VoiceListener.available()) h.append("`!gaming on|off` - Gaming mode: say \"Spotibot, play <song>\", \"Spotibot, skip\" ...\n");
        h.append("`!settings` - server settings  |  `!blackjack help` - blackjack");
        return h.toString();
    }

    private static String getBlackjackHelpMessage() {
        return "**Blackjack Commands**:\n" +
               "`!blackjack` - Join a game of blackjack.\n" +
               "`!quit` - Quit the current game of blackjack.\n" +
               "`!ledger` - Show your blackjack ledger.\n" +
               "`!funds <amount> <@user>` - Adds balance to a user's account (admins/owner only).\n" +
               "`!hit` - Hit to draw another card.\n" +
               "`!stand` - Stand to end your turn.\n" +
               "`!double` - Double down your bet.\n" +
               "`!split` - Split your hand if you have a pair.";
    }
}
