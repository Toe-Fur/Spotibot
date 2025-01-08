package com.example.bot;

import com.sedmelluq.discord.lavaplayer.player.AudioPlayer;
import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import com.sedmelluq.discord.lavaplayer.track.AudioTrackEndReason;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.util.concurrent.LinkedBlockingQueue;
import net.dv8tion.jda.api.entities.Guild;

public class TrackScheduler implements com.sedmelluq.discord.lavaplayer.player.event.AudioEventListener {
    private static final Logger logger = LoggerFactory.getLogger(TrackScheduler.class);

    private final AudioPlayer player;
    private final LinkedBlockingQueue<AudioTrack> queue;
    private AudioTrack currentTrack;
    private final Guild guild;
    private final Spotibot bot;

    public TrackScheduler(AudioPlayer player, Spotibot bot, Guild guild) {
        this.player = player;
        this.queue = new LinkedBlockingQueue<>();
        this.bot = bot;
        this.guild = guild;
        this.player.addListener(this);
    }

    public AudioPlayer getPlayer() {
        return player;
    }

    public void queue(AudioTrack track) {
        if (!player.startTrack(track, true)) {
            queue.offer(track);
        } else {
            currentTrack = track;
        }
    }

    public void nextTrack() {
        deleteCurrentTrackFile();
        currentTrack = queue.poll();

        if (currentTrack != null) {
            player.startTrack(currentTrack, false);
            logger.info("Started next track: " + currentTrack.getInfo().title);
        } else {
            logger.info("Queue is empty. Stopping playback.");
            player.stopTrack();
            // leaveVoiceChannel();
        }
    }

    public boolean isQueueEmpty() {
        return queue.isEmpty() && currentTrack == null;
    }

    private void deleteCurrentTrackFile() {
        if (currentTrack != null) {
            deleteTrackFile(currentTrack);
            currentTrack = null;
        }
    }

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

    public void clearQueueAndStop() {
        deleteCurrentTrackFile();
        queue.forEach(this::deleteTrackFile);
        queue.clear();
        player.stopTrack();
        leaveVoiceChannel();
    }

    private void leaveVoiceChannel() {
        logger.info("Leaving voice channel for guild: " + guild.getId());
        // Logic to disconnect from the voice channel
    }

    @Override
    public void onEvent(com.sedmelluq.discord.lavaplayer.player.event.AudioEvent event) {
        if (event instanceof com.sedmelluq.discord.lavaplayer.player.event.TrackEndEvent) {
            com.sedmelluq.discord.lavaplayer.player.event.TrackEndEvent trackEndEvent = (com.sedmelluq.discord.lavaplayer.player.event.TrackEndEvent) event;
            if (trackEndEvent.endReason.mayStartNext) {
                logger.info("Track ended: " + (currentTrack != null ? currentTrack.getInfo().title : "Unknown"));
                nextTrack();
            }
        }
    }
}
