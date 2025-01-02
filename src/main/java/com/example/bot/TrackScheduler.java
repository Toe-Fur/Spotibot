package com.example.bot;

import com.sedmelluq.discord.lavaplayer.player.AudioPlayer;
import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import com.sedmelluq.discord.lavaplayer.track.AudioTrackEndReason;
import com.sedmelluq.discord.lavaplayer.player.AudioPlayer;  // Import AudioPlayer

import java.io.File;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import net.dv8tion.jda.api.entities.Guild;  


public class TrackScheduler implements com.sedmelluq.discord.lavaplayer.player.event.AudioEventListener {
    private final AudioPlayer player;
    private final LinkedBlockingQueue<AudioTrack> queue;
    private AudioTrack currentTrack;
    private final Guild guild;  // Reference to the guild
    private Spotibot bot;  // Reference to Spotibot instance

    // Constructor
    public TrackScheduler(AudioPlayer player, Spotibot bot, Guild guild) {
        this.player = player;
        this.queue = new LinkedBlockingQueue<>();
        this.bot = bot;
        this.guild = guild;  // Store the guild object
        this.player.addListener(this);
    }

    public AudioPlayer getPlayer() {
        return player;
    }

    // Adds track to the queue
    public void queue(AudioTrack track) {
        if (!player.startTrack(track, true)) {
            queue.offer(track);
        } else {
            currentTrack = track;
        }
    }

    // Plays the next track in the queue
    public void nextTrack() {
        removeCurrentTrackFromQueue();
        currentTrack = queue.poll();
        if (currentTrack != null) {
            player.startTrack(currentTrack, false);
        } else {
            leaveVoiceChannel();
        }
    }

    // Removes the current track from the queue
    private void removeCurrentTrackFromQueue() {
        if (currentTrack != null) {
            String title = (String) currentTrack.getUserData();
            if (title != null) {
                // Correctly passing the guild ID (as Long) to getServerTitleQueue
                LinkedBlockingQueue<String> titleQueue = bot.getServerTitleQueue(guild.getIdLong());
                if (titleQueue != null) {
                    titleQueue.remove(title);
                }
            }
        }
    }

    // Leaves the voice channel
    private void leaveVoiceChannel() {
        // Logic to disconnect from the voice channel
    }

    // Clears the queue and stops the track
    public void clearQueueAndStop() {
        deleteCurrentTrackFile();
        queue.forEach(this::deleteTrackFile);
        queue.clear();
        player.stopTrack();
        leaveVoiceChannel();
    }

    // Deletes the current track file
    private void deleteCurrentTrackFile() {
        if (currentTrack != null) {
            deleteTrackFile(currentTrack);
            currentTrack = null;
        }
    }

    // Deletes a track file
    private void deleteTrackFile(AudioTrack track) {
        Object userData = track.getUserData();
        if (userData instanceof String) {
            File trackFile = new File((String) userData);
            if (trackFile.exists() && trackFile.isFile()) {
                trackFile.delete();
            }
        }
    }

    // Handles events (like track ending)
    @Override
    public void onEvent(com.sedmelluq.discord.lavaplayer.player.event.AudioEvent event) {
        if (event instanceof com.sedmelluq.discord.lavaplayer.player.event.TrackEndEvent) {
            com.sedmelluq.discord.lavaplayer.player.event.TrackEndEvent trackEndEvent = (com.sedmelluq.discord.lavaplayer.player.event.TrackEndEvent) event;
            if (trackEndEvent.endReason.mayStartNext) {
                removeCurrentTrackFromQueue();
                nextTrack();
            }
        }
    }
}
