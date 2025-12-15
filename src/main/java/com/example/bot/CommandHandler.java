package com.example.bot;

import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.User;
import net.dv8tion.jda.api.entities.channel.middleman.GuildMessageChannel;
import net.dv8tion.jda.api.entities.channel.concrete.VoiceChannel;
import net.dv8tion.jda.api.entities.emoji.Emoji;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;
import net.dv8tion.jda.api.events.message.react.MessageReactionAddEvent;
import com.sedmelluq.discord.lavaplayer.player.AudioPlayerManager;
import com.sedmelluq.discord.lavaplayer.player.DefaultAudioPlayerManager;
import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import java.util.concurrent.BlockingQueue;
import java.util.Map;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.ArrayList;
import java.util.concurrent.TimeUnit;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class CommandHandler {
    private static final Logger logger = LoggerFactory.getLogger(CommandHandler.class);

    private static final Set<String> ADMIN_IDS = Set.of(
        "123456789012345678" // Gametopher
    );

    public static void handleMessage(MessageReceivedEvent event, Spotibot bot, AudioPlayerManager playerManager, TrackSchedulerRegistry trackSchedulerRegistry, BlockingQueue<Runnable> downloadQueue) {
        String message = event.getMessage().getContentRaw();
        GuildMessageChannel messageChannel = event.getChannel().asGuildMessageChannel();
        Guild guild = event.getGuild();
        TrackScheduler trackScheduler = trackSchedulerRegistry.getOrCreate(guild, bot, playerManager.createPlayer());

        trackScheduler.getPlayer().setVolume(ConfigUtils.defaultVolume);
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

                if (input.contains("spotify.com/track")) {
                    String trackId = SpotifyUtils.extractSpotifyId(input);
                    String trackTitle = SpotifyUtils.getTrackTitle(trackId);
                    DownloadQueueHandler.queueAndPlay(trackTitle, trackScheduler, messageChannel, guild, serverFolder, downloadQueue);
                } else if (input.contains("spotify.com/playlist")) {
                    String playlistId = SpotifyUtils.extractSpotifyId(input);
                    List<String> trackTitles = SpotifyUtils.getPlaylistTracks(playlistId);

                    for (String trackTitle : trackTitles) {
                        DownloadQueueHandler.queueAndPlay(trackTitle, trackScheduler, messageChannel, guild, serverFolder, downloadQueue);
                    }
                } else if (input.contains("youtube.com") || input.contains("youtu.be")) {
                    DownloadQueueHandler.queueAndPlay(input, trackScheduler, messageChannel, guild, serverFolder, downloadQueue);
                } else {
                    DownloadQueueHandler.queueAndPlay(input, trackScheduler, messageChannel, guild, serverFolder, downloadQueue);
                }
            } catch (Exception e) {
                messageChannel.sendMessage("An error occurred: " + e.getMessage()).queue();
            }
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
        } else if (message.startsWith("!add ")) {
            String[] parts = message.split(" ");
            if (parts.length == 3) {
                try {
                    int amount = Integer.parseInt(parts[1]);
                    User targetUser = event.getMessage().getMentions().getUsers().get(0);
                    BlackjackGame.addBalance(targetUser, amount, messageChannel);
                } catch (NumberFormatException e) {
                    messageChannel.sendMessage("Invalid amount specified.").queue();
                } catch (IndexOutOfBoundsException e) {
                    messageChannel.sendMessage("Please mention a user to add balance to.").queue();
                }
            } else {
                messageChannel.sendMessage("Usage: !add <amount> <@user>").queue();
            }
        } else if (message.toLowerCase().startsWith("!funds ")) {
            if (!ADMIN_IDS.contains(event.getAuthor().getId())) {
                messageChannel.sendMessage("❌ You don't have permission to use `!funds`.").queue();
                return;
            }

            String[] parts = message.split(" ");
            if (parts.length < 3 || event.getMessage().getMentions().getUsers().isEmpty()) {
                messageChannel.sendMessage("Usage: `!funds <amount> @user`").queue();
                return;
            }

            try {
                int amount = Integer.parseInt(parts[1]);
                User target = event.getMessage().getMentions().getUsers().get(0);

                BlackjackGame.addBalance(target, amount, messageChannel);

                messageChannel.sendMessage(
                    "💰 Funded " + target.getAsMention() + " **$" + amount + "**"
                ).queue();
            } catch (NumberFormatException e) {
                messageChannel.sendMessage("Invalid amount. Usage: `!funds <amount> @user`").queue();
            }
        } else if (message.equalsIgnoreCase("!hit") || message.equalsIgnoreCase("!stand") || message.equalsIgnoreCase("!double")
                || message.equalsIgnoreCase("!split") || message.equalsIgnoreCase("!quit") || message.equalsIgnoreCase("!ledger")) {
            BlackjackCommands.handleCommand(message, event.getAuthor(), messageChannel);
        }
    }

    public static void handleReaction(MessageReactionAddEvent event, TrackSchedulerRegistry trackSchedulerRegistry, Map<Long, Integer> queuePageMap) {
        if (event.getUser().isBot()) return;

        Guild guild = event.getGuild();
        TrackScheduler trackScheduler = trackSchedulerRegistry.getOrCreate(guild, new Spotibot(), new DefaultAudioPlayerManager().createPlayer());

        event.retrieveMessage().queue(message -> {
            if (!message.getAuthor().getId().equals(event.getJDA().getSelfUser().getId())) {
                // The message was not sent by the bot, so ignore the reaction
                return;
            }

            String emoji = event.getEmoji().getName();
            int currentPage = queuePageMap.getOrDefault(guild.getIdLong(), 0);

            if (emoji.equals("⬅️")) {
                if (currentPage > 0) {
                    showQueue(event, trackScheduler, currentPage - 1, null);
                }
            } else if (emoji.equals("➡️")) {
                showQueue(event, trackScheduler, currentPage + 1, null);
            }

            event.getReaction().removeReaction(event.getUser()).queue();
        });
    }

    private static void handleSkipCommand(Guild guild, GuildMessageChannel messageChannel, TrackScheduler trackScheduler) {
        if (trackScheduler.getPlayer().getPlayingTrack() != null) {
            trackScheduler.nextTrack();
            messageChannel.sendMessage(ConfigUtils.skipEmoji + " Skipped to the next track.").queue(msg -> msg.suppressEmbeds(true).queue());
            // Check if the queue is empty
            if (trackScheduler.isQueueEmpty()) {
                String serverFolder = ConfigUtils.BASE_DOWNLOAD_FOLDER + guild.getId() + "/"; // Use guild to construct serverFolder
                handleStopCommand(guild, messageChannel, trackScheduler, serverFolder, null, null, null); // Pass guild
            }
        } else {
            messageChannel.sendMessage("No track is currently playing to skip.").queue(msg -> msg.suppressEmbeds(true).queue());
        }
    }

    private static void handleStopCommand(Guild guild, GuildMessageChannel messageChannel, TrackScheduler trackScheduler, String serverFolder, Spotibot bot, TrackSchedulerRegistry trackSchedulerRegistry, BlockingQueue<Runnable> downloadQueue) {
        logger.info("Stopping bot and clearing all states...");

        // Clear the queue and stop playback
        trackScheduler.clearQueueAndStop();
        
        // Close the audio connection
        guild.getAudioManager().closeAudioConnection();
        
        // Clear the downloads folder
        DownloadQueueHandler.clearDownloadsFolder(serverFolder);
        
        // Cancel the current download task if any
        if (bot != null && bot.getCurrentDownloadTask() != null && !bot.getCurrentDownloadTask().isDone()) {
            bot.getCurrentDownloadTask().cancel(true);
            try {
                bot.getCurrentDownloadTask().get(); // Wait for cancellation
            } catch (CancellationException | InterruptedException | ExecutionException e) {
                logger.info("Handled cancellation exception: " + e.getMessage());
            }
            bot.setCurrentDownloadTask(null);
        }

        if (bot != null) {
            bot.getDownloadExecutor().shutdownNow(); // Interrupt running tasks
            try {
                if (!bot.getDownloadExecutor().awaitTermination(10, TimeUnit.SECONDS)) {
                    logger.warn("Executor did not terminate in time; forcing shutdown...");
                    bot.getDownloadExecutor().shutdownNow();
                }
            } catch (InterruptedException e) {
                logger.error("Executor shutdown interrupted", e);
            }

            // Restart executor service after stopping
            bot.setDownloadExecutor(Executors.newCachedThreadPool());
            bot.startDownloadQueueProcessorPublic();

            // Clear the download queue
            bot.getDownloadQueue().clear();
        }

        if (trackSchedulerRegistry != null) {
            trackSchedulerRegistry.reset();
        }
        
        // Send a message to confirm the bot state has been reset
        messageChannel.sendMessage(ConfigUtils.stopEmoji + " Stopped playback and reset the bot state. You can now add new songs.").queue(msg -> msg.suppressEmbeds(true).queue());
    }

    private static void showQueue(MessageReceivedEvent event, TrackScheduler trackScheduler, int page, Spotibot bot) {
        LinkedBlockingQueue<AudioTrack> playbackQueue = trackScheduler.getQueue();
        StringBuilder queueMessage = new StringBuilder("🎶 **Now Playing and Queue** 🎶\n");

        AudioTrack currentTrack = trackScheduler.getCurrentTrack();
        if (currentTrack != null) {
            String title = trackScheduler.getTitle(currentTrack.getIdentifier()).replace("ytsearch:", "").trim();
            queueMessage.append("🎵 Now Playing: ").append(title != null ? title : "Unknown Title").append("\n");
        }

        int startIndex = page * ConfigUtils.QUEUE_PAGE_SIZE;
        int endIndex = Math.min(startIndex + ConfigUtils.QUEUE_PAGE_SIZE, playbackQueue.size());

        List<AudioTrack> trackList = new ArrayList<>(playbackQueue);
        for (int i = startIndex; i < endIndex; i++) {
            AudioTrack track = trackList.get(i);
            String title = trackScheduler.getTitle(track.getIdentifier()).replace("ytsearch:", "").trim();
            queueMessage.append("📍 ").append(i + 1).append(". ").append(title != null ? title : "Unknown Title").append("\n");
        }

        if (bot != null) {
            bot.getQueuePageMap().put(event.getGuild().getIdLong(), page);
        }

        event.getChannel().sendMessage(queueMessage.toString()).queue(message -> {
            if (playbackQueue.size() > ConfigUtils.QUEUE_PAGE_SIZE) {
                if (page > 0) {
                    message.addReaction(Emoji.fromUnicode("⬅️")).queue();
                }
                if (endIndex < playbackQueue.size()) {
                    message.addReaction(Emoji.fromUnicode("➡️")).queue();
                }
            }
        });
    }

    private static void showQueue(MessageReactionAddEvent event, TrackScheduler trackScheduler, int page, Spotibot bot) {
        LinkedBlockingQueue<AudioTrack> playbackQueue = trackScheduler.getQueue();
        StringBuilder queueMessage = new StringBuilder("🎶 **Now Playing and Queue** 🎶\n");

        AudioTrack currentTrack = trackScheduler.getCurrentTrack();
        if (currentTrack != null) {
            String title = trackScheduler.getTitle(currentTrack.getIdentifier()).replace("ytsearch:", "").trim();
            queueMessage.append("🎵 Now Playing: ").append(title != null ? title : "Unknown Title").append("\n");
        }

        int startIndex = page * ConfigUtils.QUEUE_PAGE_SIZE;
        int endIndex = Math.min(startIndex + ConfigUtils.QUEUE_PAGE_SIZE, playbackQueue.size());

        List<AudioTrack> trackList = new ArrayList<>(playbackQueue);
        for (int i = startIndex; i < endIndex; i++) {
            AudioTrack track = trackList.get(i);
            String title = trackScheduler.getTitle(track.getIdentifier()).replace("ytsearch:", "").trim();
            queueMessage.append("📍 ").append(i + 1).append(". ").append(title != null ? title : "Unknown Title").append("\n");
        }

        if (bot != null) {
            bot.getQueuePageMap().put(event.getGuild().getIdLong(), page);
        }

        event.getChannel().retrieveMessageById(event.getMessageId()).queue(message -> {
            // Check if the message was sent by the bot
            if (message.getAuthor().getId().equals(event.getJDA().getSelfUser().getId())) {
                message.editMessage(queueMessage.toString()).queue();
                message.clearReactions().queue();
                if (playbackQueue.size() > ConfigUtils.QUEUE_PAGE_SIZE) {
                    if (page > 0) {
                        message.addReaction(Emoji.fromUnicode("⬅️")).queue();
                    }
                    if (endIndex < playbackQueue.size()) {
                        message.addReaction(Emoji.fromUnicode("➡️")).queue();
                    }
                }
            }
        });
}

    private static String getHelpMessage() {
        return "**Spotibot Commands**:\n" +
               "`!play <URL or search term>` - Plays a YouTube video.\n" +
               "`!skip` - Skips the current track.\n" +
               "`!stop` - Stops playback and resets the bot.\n" +
               "`!queue` - Shows the current queue.\n" +
               "`!help` - Shows this list of commands.\n" +
               "`!blackjack` - Join a game of blackjack.\n" +
               "`!blackjack help` - Shows blackjack commands.\n" +
               "\nNote: Place your `cookies.txt` file in the `config` folder for YouTube authentication.";
    }

    private static String getBlackjackHelpMessage() {
        return "**Blackjack Commands**:\n" +
               "`!blackjack` - Join a game of blackjack.\n" +
               "`!quit` - Quit the current game of blackjack.\n" +
               "`!ledger` - Show your blackjack ledger.\n" +
               "`!add <amount> <@user>` - Adds balance to a user's account.\n" +
               "`!hit` - Hit to draw another card.\n" +
               "`!stand` - Stand to end your turn.\n" +
               "`!double` - Double down your bet.\n" +
               "`!split` - Split your hand if you have a pair.";
    }
}
