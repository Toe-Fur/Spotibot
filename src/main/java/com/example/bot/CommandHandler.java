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
               "`!funds <amount> <@user>` - Adds balance to a user's account (admins/owner only).\n" +
               "`!hit` - Hit to draw another card.\n" +
               "`!stand` - Stand to end your turn.\n" +
               "`!double` - Double down your bet.\n" +
               "`!split` - Split your hand if you have a pair.";
    }
}
