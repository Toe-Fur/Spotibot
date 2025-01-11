package com.example.bot;

import com.sedmelluq.discord.lavaplayer.player.AudioPlayer;
import com.sedmelluq.discord.lavaplayer.player.AudioPlayerManager;
import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import com.sedmelluq.discord.lavaplayer.track.AudioTrackEndReason;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.util.concurrent.LinkedBlockingQueue;
import net.dv8tion.jda.api.entities.Guild;

import com.sedmelluq.discord.lavaplayer.player.AudioLoadResultHandler;
import com.sedmelluq.discord.lavaplayer.track.AudioPlaylist;
import com.sedmelluq.discord.lavaplayer.tools.FriendlyException;
import java.io.FileOutputStream;

import java.io.InputStream;

public class TrackScheduler implements com.sedmelluq.discord.lavaplayer.player.event.AudioEventListener {
    private static final Logger logger = LoggerFactory.getLogger(TrackScheduler.class);

    private final AudioPlayerManager playerManager; // The audio player manager
    private final AudioPlayer player; // The audio player responsible for playback
    private final LinkedBlockingQueue<AudioTrack> queue; // Queue for managing tracks
    private AudioTrack currentTrack; // The track currently playing
    private final Guild guild; // The guild associated with this scheduler
    private final Spotibot bot; // Reference to the main bot instance

    // Constructor to initialize the scheduler
    public TrackScheduler(AudioPlayerManager playerManager, AudioPlayer player, Spotibot bot, Guild guild) {
        this.playerManager = playerManager; // Initialize playerManager
        this.player = player;
        this.queue = new LinkedBlockingQueue<>();
        this.bot = bot;
        this.guild = guild;
        this.player.addListener(this); // Register this scheduler as an event listener
    }

    // Getter for the audio player
    public AudioPlayer getPlayer() {
        return player;
    }

    // Adds a track to the queue or plays it immediately if nothing is playing
    public void queue(AudioTrack track) {
        if (!player.startTrack(track, true)) {
            // If a track is already playing, add the new track to the queue
            queue.offer(track);
        } else {
            // Otherwise, set the new track as the current track
            currentTrack = track;
        }
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
        // Store the file name of the current track before moving to the next one
        String previousTrackFileName = getCurrentTrackFileName();

        // Fetch the next track from the queue
        currentTrack = queue.poll();

        if (currentTrack != null) {
            // Play the next track
            boolean trackStarted = player.startTrack(currentTrack, false);

            if (trackStarted) {
                logger.info("Started next track: " + currentTrack.getInfo().title);
            } else {
                logger.warn("Failed to start the next track: " + currentTrack.getInfo().title);
            }

            // Now delete the file for the previous track (after playback has started)
            if (previousTrackFileName != null) {
                deleteFile(previousTrackFileName);
            }
        } else {
            // Stop playback if the queue is empty
            logger.info("Queue is empty. Stopping playback.");
            player.stopTrack();
            leaveVoiceChannel(); // Disconnect from the voice channel

            // Clean up the file for the previous track
            if (previousTrackFileName != null) {
                deleteFile(previousTrackFileName);
            }
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

    public void queueSong(File file, String title) {
        if (!file.exists() || (!file.getName().endsWith(".mp3") && !file.getName().endsWith(".webm") && !file.getName().endsWith(".mp3"))) {
            logger.error("Unsupported file format or file does not exist: " + file.getAbsolutePath());
            return;
        }

        logger.info("Attempting to load file: " + file.getAbsolutePath());

        playerManager.loadItem(file.getAbsolutePath(), new AudioLoadResultHandler() {
            @Override
            public void trackLoaded(AudioTrack track) {
                logger.info("Track loaded successfully: " + title);
                track.setUserData(file.getAbsolutePath());

                if (player.getPlayingTrack() == null) {
                    player.startTrack(track, false);
                    logger.info("Started playing: " + title);
                } else {
                    queue.offer(track);
                    logger.info("Queued track: " + title);
                }
            }

            @Override
            public void playlistLoaded(AudioPlaylist playlist) {
                logger.warn("Unexpected playlist loaded: " + playlist.getName());
            }

            @Override
            public void noMatches() {
                logger.error("No matches found for file: " + file.getAbsolutePath());
            }

            @Override
            public void loadFailed(FriendlyException exception) {
                logger.error("Failed to load track: " + title, exception);
            }
        });
    }

    // Delete the file for the current track after playback ends
    private void deleteCurrentTrackFile() {
        if (currentTrack != null) {
            Object userData = currentTrack.getUserData();
            if (userData instanceof String) {
                String filePath = (String) userData;
                File file = new File(filePath);
                if (file.exists() && file.isFile()) {
                    if (file.delete()) {
                        logger.info("Deleted track file: " + filePath);
                    } else {
                        logger.error("Failed to delete track file: " + filePath);
                    }
                }
            }
            currentTrack = null;
        }
    }

    public void queueStream(InputStream stream, String title) {
        try {
            // Save the stream to a temporary file
            File tempFile = File.createTempFile("stream_", ".mp3");
            tempFile.deleteOnExit(); // Ensure cleanup on JVM exit
            try (FileOutputStream outputStream = new FileOutputStream(tempFile)) {
                byte[] buffer = new byte[8192];
                int bytesRead;
                while ((bytesRead = stream.read(buffer)) != -1) {
                    outputStream.write(buffer, 0, bytesRead);
                }
            }

            // Queue the track using the temporary file
            queueSong(tempFile, title);
            logger.info("Stream queued successfully: " + title);
        } catch (Exception e) {
            logger.error("Failed to process stream for: " + title, e);
        }
    }

    // Checks if the queue is empty and no track is currently playing
    public boolean isQueueEmpty() {
        return queue.isEmpty() && currentTrack == null;
    }

    // Deletes the file associated with a specific track
    private void deleteTrackFile(AudioTrack track) {
        Object userData = track.getUserData();
        if (userData instanceof String) {
            File trackFile = new File((String) userData);
            if (trackFile.exists() && trackFile.isFile()) {
                boolean deleted = trackFile.delete();
                if (deleted) {
                    logger.info("Deleted track file: " + trackFile.getAbsolutePath());
                } else {
                    logger.error("Failed to delete track file: " + trackFile.getAbsolutePath());
                }
            }
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
        deleteCurrentTrackFile(); // Clean up the current track
        queue.forEach(this::deleteTrackFile); // Delete all queued tracks
        queue.clear(); // Clear the queue
        player.stopTrack(); // Stop the player
        leaveVoiceChannel(); // Disconnect from the voice channel
    }

    // Disconnects the bot from the guild's voice channel
    private void leaveVoiceChannel() {
        logger.info("Leaving voice channel for guild: " + guild.getId());
        // Logic to disconnect from the voice channel goes here
    }

    // Handles events from the audio player
    @Override
    public void onEvent(com.sedmelluq.discord.lavaplayer.player.event.AudioEvent event) {
        if (event instanceof com.sedmelluq.discord.lavaplayer.player.event.TrackEndEvent) {
            com.sedmelluq.discord.lavaplayer.player.event.TrackEndEvent trackEndEvent = (com.sedmelluq.discord.lavaplayer.player.event.TrackEndEvent) event;
            if (trackEndEvent.endReason.mayStartNext) {
                logger.info("Track ended: " + (currentTrack != null ? currentTrack.getInfo().title : "Unknown"));
                nextTrack(); // Play the next track in the queue
            }
        }
    }
}
