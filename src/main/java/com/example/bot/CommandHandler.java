package com.example.bot;

import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.User;
import net.dv8tion.jda.api.entities.channel.middleman.GuildMessageChannel;
import net.dv8tion.jda.api.entities.channel.concrete.VoiceChannel;
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
        GuildMessageChannel messageChannel = event.getChannel().asGuildMessageChannel();
        Guild guild = event.getGuild();
        TrackScheduler trackScheduler = trackSchedulerRegistry.getOrCreate(guild, bot);

        String serverFolder = ConfigUtils.BASE_DOWNLOAD_FOLDER + guild.getId() + "/";

        if (message.startsWith("!play ")) {
            String input = message.replace("!play ", "").trim();

            try {
                VoiceChannel voiceChannel = (VoiceChannel) event.getMember().getVoiceState().getChannel();
                if (voiceChannel == null) {
                    messageChannel.sendMessage("You must be in a voice channel to use this command.").queue();
                    return;
                }

                guild.getAudioManager().setSendingHandler(new AudioPlayerSendHandler(trackScheduler.getPlayer()));
                guild.getAudioManager().openAudioConnection(voiceChannel);
                trackScheduler.getPlayer().setVolume(ConfigUtils.defaultVolume);
                trackScheduler.setNotifyChannel(messageChannel);

                if (input.contains("spotify.com/track")) {
                    String trackId = SpotifyUtils.extractSpotifyId(input);
                    String trackTitle = SpotifyUtils.getTrackTitle(trackId);
                    DownloadQueueHandler.queueAndPlay(trackTitle, trackScheduler, messageChannel, guild, serverFolder, downloadQueue);
                } else if (input.contains("spotify.com/playlist")) {
                    String playlistId = SpotifyUtils.extractSpotifyId(input);
                    try {
                        List<String> trackTitles = SpotifyUtils.getPlaylistTracks(playlistId);
                        if (trackTitles.isEmpty()) {
                            messageChannel.sendMessage("❌ That Spotify playlist is empty or has no playable tracks.").queue();
                            return;
                        }
                        messageChannel.sendMessage("📋 Found **" + trackTitles.size() + "** tracks. Queuing...").queue();
                        for (String trackTitle : trackTitles) {
                            DownloadQueueHandler.queueAndPlay(trackTitle, trackScheduler, messageChannel, guild, serverFolder, downloadQueue);
                        }
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
                    messageChannel.sendMessage("📋 Fetching YouTube playlist...").queue();
                    trackScheduler.incrementPendingDownloads();
                    downloadQueue.offer(() -> {
                        try {
                            List<String> videoUrls = DownloadQueueHandler.getYouTubePlaylistUrls(input);
                            if (videoUrls.isEmpty()) {
                                ch.sendMessage("❌ Could not fetch playlist or it's empty.").queue();
                            } else {
                                ch.sendMessage("📋 Found **" + videoUrls.size() + "** tracks. Queuing...").queue();
                                for (String url : videoUrls) {
                                    DownloadQueueHandler.queueAndPlay(url, trackScheduler, ch, guild, serverFolder, downloadQueue);
                                }
                            }
                        } catch (Exception e) {
                            ch.sendMessage("❌ Error fetching playlist: " + e.getMessage()).queue();
                        } finally {
                            trackScheduler.decrementPendingDownloads();
                        }
                    });
                } else if (input.contains("youtube.com") || input.contains("youtu.be")) {
                    DownloadQueueHandler.queueAndPlay(input, trackScheduler, messageChannel, guild, serverFolder, downloadQueue);
                } else {
                    DownloadQueueHandler.queueAndPlay(input, trackScheduler, messageChannel, guild, serverFolder, downloadQueue);
                }
            } catch (Exception e) {
                messageChannel.sendMessage("An error occurred: " + e.getMessage()).queue();
            }

        } else if (message.equalsIgnoreCase("!pause")) {
            handlePauseCommand(messageChannel, trackScheduler);

        } else if (message.equalsIgnoreCase("!resume")) {
            handleResumeCommand(messageChannel, trackScheduler);

        } else if (message.toLowerCase().startsWith("!volume")) {
            handleVolumeCommand(message, messageChannel, trackScheduler);

        } else if (message.equalsIgnoreCase("!np") || message.equalsIgnoreCase("!nowplaying")) {
            handleNowPlayingCommand(messageChannel, trackScheduler);

        } else if (message.equalsIgnoreCase("!skip")) {
            handleSkipCommand(guild, messageChannel, trackScheduler);

        } else if (message.equalsIgnoreCase("!stop")) {
            handleStopCommand(guild, messageChannel, trackScheduler, serverFolder, bot, trackSchedulerRegistry, downloadQueue);

        } else if (message.equalsIgnoreCase("!queue")) {
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

private static void handlePauseCommand(GuildMessageChannel messageChannel, TrackScheduler trackScheduler) {
        if (trackScheduler.getPlayer().getPlayingTrack() == null) {
            messageChannel.sendMessage("Nothing is playing right now.").queue();
            return;
        }
        if (trackScheduler.getPlayer().isPaused()) {
            messageChannel.sendMessage("Already paused. Use `!resume` to continue.").queue();
            return;
        }
        trackScheduler.getPlayer().setPaused(true);
        messageChannel.sendMessage("⏸ Paused.").queue();
    }

    private static void handleResumeCommand(GuildMessageChannel messageChannel, TrackScheduler trackScheduler) {
        if (!trackScheduler.getPlayer().isPaused()) {
            messageChannel.sendMessage("Not paused.").queue();
            return;
        }
        trackScheduler.getPlayer().setPaused(false);
        messageChannel.sendMessage("▶ Resumed.").queue();
    }

    private static void handleVolumeCommand(String message, GuildMessageChannel messageChannel, TrackScheduler trackScheduler) {
        String[] parts = message.trim().split("\\s+");
        if (parts.length < 2) {
            int current = trackScheduler.getPlayer().getVolume();
            messageChannel.sendMessage("🔊 Current volume: **" + current + "**. Usage: `!volume <0-100>`").queue();
            return;
        }
        try {
            int vol = Integer.parseInt(parts[1]);
            if (vol < 0 || vol > 100) {
                messageChannel.sendMessage("Volume must be between 0 and 100.").queue();
                return;
            }
            trackScheduler.getPlayer().setVolume(vol);
            messageChannel.sendMessage("🔊 Volume set to **" + vol + "**.").queue();
        } catch (NumberFormatException e) {
            messageChannel.sendMessage("Invalid volume. Usage: `!volume <0-100>`").queue();
        }
    }

    private static void handleNowPlayingCommand(GuildMessageChannel messageChannel, TrackScheduler trackScheduler) {
        AudioTrack current = trackScheduler.getCurrentTrack();
        if (current == null || trackScheduler.getPlayer().getPlayingTrack() == null) {
            messageChannel.sendMessage("Nothing is playing right now.").queue();
            return;
        }
        String title = trackScheduler.getTitle(current.getIdentifier());
        title = title != null ? title.replace("ytsearch:", "").trim() : current.getInfo().title;
        EmbedBuilder eb = new EmbedBuilder()
                .setColor(new Color(0x1db954))
                .setTitle("🎵 Now Playing")
                .setDescription("**" + title + "**")
                .setFooter("!pause  •  !skip  •  !stop  •  !queue");
        long durationMs = current.getDuration();
        if (durationMs > 0 && durationMs != Long.MAX_VALUE) {
            long secs = durationMs / 1000;
            long h = secs / 3600, m = (secs % 3600) / 60, s = secs % 60;
            String dur = h > 0 ? String.format("%d:%02d:%02d", h, m, s) : String.format("%d:%02d", m, s);
            eb.addField("Duration", dur, true);
        }
        messageChannel.sendMessageEmbeds(eb.build()).queue();
    }

    private static void handleSkipCommand(Guild guild, GuildMessageChannel messageChannel, TrackScheduler trackScheduler) {
        if (trackScheduler.getPlayer().getPlayingTrack() != null) {
            trackScheduler.nextTrack();
            messageChannel.sendMessage(ConfigUtils.skipEmoji + " Skipped.").queue(msg -> msg.suppressEmbeds(true).queue());
        } else {
            messageChannel.sendMessage("Nothing is playing right now.").queue(msg -> msg.suppressEmbeds(true).queue());
        }
    }

    private static void handleStopCommand(Guild guild, GuildMessageChannel messageChannel, TrackScheduler trackScheduler, String serverFolder, Spotibot bot, TrackSchedulerRegistry trackSchedulerRegistry, BlockingQueue<Runnable> downloadQueue) {
        logger.info("Stopping bot and clearing all states...");

        trackScheduler.clearQueueAndStop();
        guild.getAudioManager().closeAudioConnection();
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

        messageChannel.sendMessage(ConfigUtils.stopEmoji + " Stopped playback and reset the bot state. You can now add new songs.").queue(msg -> msg.suppressEmbeds(true).queue());
    }

    static void showQueue(MessageReceivedEvent event, TrackScheduler trackScheduler, int page, Spotibot bot) {
        LinkedBlockingQueue<AudioTrack> playbackQueue = trackScheduler.getQueue();

        if (bot != null) bot.getQueuePageMap().put(event.getGuild().getIdLong(), page);

        EmbedBuilder eb = buildQueueEmbed(trackScheduler, playbackQueue, page);
        List<ActionRow> components = buildQueueComponents(playbackQueue.size(), page);

        if (components.isEmpty()) {
            event.getChannel().sendMessageEmbeds(eb.build()).queue();
        } else {
            event.getChannel().sendMessageEmbeds(eb.build()).setComponents(components).queue();
        }
    }

    public static EmbedBuilder buildQueueEmbed(TrackScheduler trackScheduler,
                                                LinkedBlockingQueue<AudioTrack> playbackQueue,
                                                int page) {
        EmbedBuilder eb = new EmbedBuilder()
                .setTitle("🎶 Queue")
                .setColor(new Color(0x1db954));

        AudioTrack currentTrack = trackScheduler.getCurrentTrack();
        if (currentTrack != null) {
            String title = trackScheduler.getTitle(currentTrack.getIdentifier());
            title = title != null ? title.replace("ytsearch:", "").trim() : "Unknown";
            eb.appendDescription("🎵 **Now Playing:** " + title + "\n\n");
        }

        int total = playbackQueue.size();
        int startIndex = page * ConfigUtils.QUEUE_PAGE_SIZE;
        int endIndex = Math.min(startIndex + ConfigUtils.QUEUE_PAGE_SIZE, total);

        List<AudioTrack> trackList = new ArrayList<>(playbackQueue);
        if (trackList.isEmpty()) {
            eb.appendDescription("_Queue is empty._");
        } else {
            for (int i = startIndex; i < endIndex; i++) {
                String title = trackScheduler.getTitle(trackList.get(i).getIdentifier());
                title = title != null ? title.replace("ytsearch:", "").trim() : "Unknown";
                eb.appendDescription("📍 **" + (i + 1) + ".** " + title + "\n");
            }
        }

        int totalPages = total == 0 ? 1 : (int) Math.ceil((double) total / ConfigUtils.QUEUE_PAGE_SIZE);
        eb.setFooter("Page " + (page + 1) + "/" + totalPages + "  •  " + total + " track(s) queued");
        return eb;
    }

    public static List<ActionRow> buildQueueComponents(int totalTracks, int page) {
        if (totalTracks <= ConfigUtils.QUEUE_PAGE_SIZE) return List.of();
        int endIndex = Math.min((page + 1) * ConfigUtils.QUEUE_PAGE_SIZE, totalTracks);
        Button prev = Button.secondary(BTN_QUEUE_PREV, "◀ Prev").withDisabled(page == 0);
        Button next = Button.secondary(BTN_QUEUE_NEXT, "Next ▶").withDisabled(endIndex >= totalTracks);
        return List.of(ActionRow.of(prev, next));
    }

    private static String getHelpMessage() {
        return "**Spotibot Commands**:\n" +
               "`!play <URL or search term>` - Play a YouTube video, playlist, or Spotify track/playlist.\n" +
               "`!np` / `!nowplaying` - Show what's currently playing.\n" +
               "`!pause` - Pause playback.\n" +
               "`!resume` - Resume playback.\n" +
               "`!volume <0-100>` - Set or view volume.\n" +
               "`!skip` - Skip the current track.\n" +
               "`!stop` - Stop playback and reset the bot.\n" +
               "`!queue` - Show the current queue.\n" +
               "`!help` - Show this list of commands.\n" +
               "`!blackjack` - Join a game of blackjack.\n" +
               "`!blackjack help` - Show blackjack commands.\n" +
               "\nNote: Place your `cookies.txt` file in the `config` folder for YouTube authentication.";
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
