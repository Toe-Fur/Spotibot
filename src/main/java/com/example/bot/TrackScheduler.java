package com.example.bot;

import com.sedmelluq.discord.lavaplayer.player.AudioPlayer;
import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import com.sedmelluq.discord.lavaplayer.track.AudioTrackEndReason;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.util.concurrent.LinkedBlockingQueue;
import net.dv8tion.jda.api.entities.Guild;

// Handles track playback and queue management for a guild
public class TrackScheduler implements com.sedmelluq.discord.lavaplayer.player.event.AudioEventListener {
    private static final Logger logger = LoggerFactory.getLogger(TrackScheduler.class);

    private final AudioPlayer player; // The audio player responsible for playback
    private final LinkedBlockingQueue<AudioTrack> queue; // Queue for managing tracks
    private AudioTrack currentTrack; // The track currently playing
    private final Guild guild; // The guild associated with this scheduler
    private final Spotibot bot; // Reference to the main bot instance

    // Constructor to initialize the scheduler
    public TrackScheduler(AudioPlayer player, Spotibot bot, Guild guild) {
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

    // Plays the next track in the queue or stops playback if the queue is empty
    public void nextTrack() {
        deleteCurrentTrackFile(); // Clean up the file for the current track
        currentTrack = queue.poll(); // Fetch the next track from the queue

        if (currentTrack != null) {
            // Play the next track
            player.startTrack(currentTrack, false);
            logger.info("Started next track: " + currentTrack.getInfo().title);
        } else {
            // Stop playback if the queue is empty
            logger.info("Queue is empty. Stopping playback.");
            player.stopTrack();
        }
    }

    // Checks if the queue is empty and no track is currently playing
    public boolean isQueueEmpty() {
        return queue.isEmpty() && currentTrack == null;
    }

    // Deletes the file for the current track
    private void deleteCurrentTrackFile() {
        if (currentTrack != null) {
            deleteTrackFile(currentTrack);
            currentTrack = null;
        }
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
