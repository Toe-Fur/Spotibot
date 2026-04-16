package com.example.bot;

import com.sedmelluq.discord.lavaplayer.player.AudioPlayer;
import com.sedmelluq.discord.lavaplayer.player.AudioPlayerManager;
import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import com.sedmelluq.discord.lavaplayer.track.AudioTrackEndReason;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.io.File;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.LinkedBlockingQueue;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.channel.middleman.GuildMessageChannel;
import com.sedmelluq.discord.lavaplayer.player.AudioLoadResultHandler;
import com.sedmelluq.discord.lavaplayer.track.AudioPlaylist;
import com.sedmelluq.discord.lavaplayer.tools.FriendlyException;
import java.awt.Color;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

public class TrackScheduler implements com.sedmelluq.discord.lavaplayer.player.event.AudioEventListener {
    private static final Logger logger = LoggerFactory.getLogger(TrackScheduler.class);

    private final Map<String, String> trackTitles = new ConcurrentHashMap<>();
    private final AudioPlayerManager playerManager;
    private final AudioPlayer player;
    private final LinkedBlockingQueue<AudioTrack> queue;
    private AudioTrack currentTrack;
    private final Guild guild;
    private final AtomicInteger pendingDownloadCount = new AtomicInteger(0);
    private volatile GuildMessageChannel notifyChannel;
    // Constructor to initialize the scheduler
    public TrackScheduler(AudioPlayerManager playerManager, AudioPlayer player, Spotibot bot, Guild guild) {
        this.playerManager = playerManager; // Initialize playerManager
        this.player = player;
        this.queue = new LinkedBlockingQueue<>();
        this.guild = guild;
        this.player.addListener(this); // Register this scheduler as an event listener
    }

    public void setNotifyChannel(GuildMessageChannel channel) {
        this.notifyChannel = channel;
    }

    // Getter for the audio player
    public AudioPlayer getPlayer() {
        return player;
    }

    // Adds a track to the queue or plays it immediately if nothing is playing
    public void queue(AudioTrack track) {
        track.setUserData(track.getIdentifier());
        String timestamp = getCurrentTimestamp();
        logger.info("[" + timestamp + "] Queueing track: " + track.getInfo().title);
        if (!player.startTrack(track, true)) {
            queue.offer(track);
            logger.info("[" + timestamp + "] Track added to queue: " + track.getInfo().title);
        } else {
            currentTrack = track;
            logger.info("[" + timestamp + "] Now playing: " + track.getInfo().title);
            sendNowPlaying(track);
        }
    }

    private void sendNowPlaying(AudioTrack track) {
        if (notifyChannel == null) return;
        String title = trackTitles.getOrDefault(track.getIdentifier(), track.getInfo().title);
        title = title.replace("ytsearch:", "").trim();
        EmbedBuilder eb = new EmbedBuilder()
                .setColor(new Color(0x1db954))
                .setTitle("🎵 Now Playing")
                .setDescription("**" + title + "**")
                .setFooter("!pause  •  !skip  •  !stop  •  !queue");
        notifyChannel.sendMessageEmbeds(eb.build()).queue(null, e -> {});
    }

    // Method to get the file name of the currently playing track
    public String getCurrentTrackFileName() {
        if (currentTrack != null) {
            Object userData = currentTrack.getUserData();
            if (userData instanceof String) {
                String fileName = (String) userData;
                logger.info("Current track file name: " + fileName);
                return fileName;
            } else {
                logger.warn("Current track does not have a valid file name in user data.");
                return null;
            }
        } else {
            logger.warn("No track is currently playing.");
            return null;
        }
    }

    // Method to delete the file for the currently stored track file name
    public boolean deleteTrackFileByName() {
        if (currentTrack != null) {
            Object userData = currentTrack.getUserData();
            if (userData instanceof String) {
                String fileName = (String) userData;
                File file = new File(fileName);
                if (file.exists() && file.isFile()) {
                    if (file.delete()) {
                        logger.info("Successfully deleted file: " + fileName);
                        return true;
                    } else {
                        logger.error("Failed to delete file: " + fileName);
                        return false;
                    }
                } else {
                    logger.warn("File does not exist or is not a valid file: " + fileName);
                    return false;
                }
            } else {
                logger.warn("Current track does not have a valid file name in user data.");
                return false;
            }
        } else {
            logger.warn("No track is currently playing to delete.");
            return false;
        }
    }

    // Plays the next track in the queue or stops playback if the queue is empty
    public void nextTrack() {
        // Save the current track file name for deletion
        String previousTrackFileName = getCurrentTrackFileName();
        String timestamp = getCurrentTimestamp();
        logger.info("[" + timestamp + "] Moving to next track. Current track: " + (currentTrack != null ? currentTrack.getInfo().title : "None"));

        // Poll the next track from the queue
        currentTrack = queue.poll();

        if (currentTrack != null) {
            player.startTrack(currentTrack, false);
            logger.info("[" + timestamp + "] Started next track: " + currentTrack.getInfo().title);
            sendNowPlaying(currentTrack);
        } else if (pendingDownloadCount.get() > 0) {
            // Downloads still in flight — stay in the channel and wait
            logger.info("[" + timestamp + "] Scheduler queue empty but {} download(s) still pending; staying in channel.", pendingDownloadCount.get());
        } else {
            // Nothing queued and nothing downloading — leave
            logger.info("[" + timestamp + "] Queue empty, no pending downloads. Leaving voice channel.");
            leaveVoiceChannel();
        }

        // Delete the file for the previous track
        if (previousTrackFileName != null) {
            deleteFile(previousTrackFileName);
        }
    }

    // Helper method to delete a file
    private void deleteFile(String fileName) {
        File file = new File(fileName);
        if (file.exists() && file.isFile()) {
            if (file.delete()) {
                logger.info("Successfully deleted file: " + fileName);
            } else {
                logger.error("Failed to delete file: " + fileName);
            }
        } else {
            logger.warn("File does not exist or is not a valid file: " + fileName);
        }
    }

    // Add a method to get the title
    public String getTitle(String trackId) {
        return trackTitles.get(trackId);
    }

    public void queueSong(File file, String title) {
        playerManager.loadItem(file.getAbsolutePath(), new AudioLoadResultHandler() {
            @Override
            public void trackLoaded(AudioTrack track) {
                track.setUserData(file.getAbsolutePath());
                queue(track);
                trackTitles.put(track.getIdentifier(), title.replace("ytsearch:", "").trim());
                logger.info("Loaded and queued track: " + title);
                decrementPendingDownloads(); // decrement only after track is actually queued
            }

            @Override
            public void playlistLoaded(AudioPlaylist playlist) {
                logger.warn("Unexpected playlist loaded: " + playlist.getName());
                decrementPendingDownloads();
            }

            @Override
            public void noMatches() {
                logger.warn("No matches found for file: " + file.getAbsolutePath());
                decrementPendingDownloads();
            }

            @Override
            public void loadFailed(FriendlyException exception) {
                logger.error("Failed to load track: " + title, exception);
                decrementPendingDownloads();
            }
        });
    }

    // Called before a download is submitted so the scheduler knows a track is incoming
    public void incrementPendingDownloads() {
        pendingDownloadCount.incrementAndGet();
    }

    // Called after a download completes (success or failure)
    public void decrementPendingDownloads() {
        pendingDownloadCount.decrementAndGet();
    }

    // Checks if the queue is empty, no track is playing, and no downloads are pending
    public boolean isQueueEmpty() {
        return queue.isEmpty() && player.getPlayingTrack() == null && pendingDownloadCount.get() == 0;
    }

    // Deletes the file for the current track
    private void deleteCurrentTrackFile() {
        if (currentTrack != null) {
            Object userData = currentTrack.getUserData();
            if (userData instanceof String) {
                String fileName = (String) userData;
                deleteFile(fileName);
            }
            currentTrack = null;
        }
    }

    public LinkedBlockingQueue<AudioTrack> getQueue() {
        return queue; // Expose the playback queue
    }

    public AudioTrack getCurrentTrack() {
        return currentTrack; // Expose the currently playing track
    }

    // Clears the queue, stops playback, and leaves the voice channel
    public void clearQueueAndStop() {
        // Stop the current track
        player.stopTrack();
        
        // Clear the playback queue
        queue.clear();
        
        // Reset the current track
        currentTrack = null;
        
        // Perform any other necessary cleanup
        leaveVoiceChannel();
    }

    // Disconnects the bot from the guild's voice channel
    private void leaveVoiceChannel() {
        logger.info("[VOICE] Queue empty — leaving voice channel for guild {}", guild.getId());
        guild.getAudioManager().closeAudioConnection();
    }

    // Handles events from the audio player
    @Override
    public void onEvent(com.sedmelluq.discord.lavaplayer.player.event.AudioEvent event) {
        if (event instanceof com.sedmelluq.discord.lavaplayer.player.event.TrackEndEvent) {
            AudioTrackEndReason reason = ((com.sedmelluq.discord.lavaplayer.player.event.TrackEndEvent) event).endReason;
            if (reason.mayStartNext) {
                // Advance to the next track when the current track ends
                deleteCurrentTrackFile();
                nextTrack();
            } else if (isQueueEmpty()) {
                // Leave the voice channel if the queue is empty
                leaveVoiceChannel();
            }
        }
    }

    // Helper method to get the current timestamp
    private String getCurrentTimestamp() {
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
        return LocalDateTime.now().format(formatter);
    }
}