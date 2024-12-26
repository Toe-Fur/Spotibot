package com.example.bot;

import com.sedmelluq.discord.lavaplayer.player.AudioPlayer;
import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import com.sedmelluq.discord.lavaplayer.track.AudioTrackEndReason;

import java.io.File;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

public class TrackScheduler implements com.sedmelluq.discord.lavaplayer.player.event.AudioEventListener {
    private final AudioPlayer player;
    private final BlockingQueue<AudioTrack> queue;
    private AudioTrack currentTrack;

    public TrackScheduler(AudioPlayer player) {
        this.player = player;
        this.queue = new LinkedBlockingQueue<>();
        this.player.addListener(this);
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
        player.startTrack(currentTrack, false);
    }

    public void clearQueueAndStop() {
        deleteCurrentTrackFile();
        queue.forEach(this::deleteTrackFile);
        queue.clear();
        player.stopTrack();
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
                if (!deleted) {
                    System.err.println("Failed to delete file: " + trackFile.getAbsolutePath());
                }
            }
        }
    }

    @Override
    public void onEvent(com.sedmelluq.discord.lavaplayer.player.event.AudioEvent event) {
        if (event instanceof com.sedmelluq.discord.lavaplayer.player.event.TrackEndEvent) {
            com.sedmelluq.discord.lavaplayer.player.event.TrackEndEvent trackEndEvent = (com.sedmelluq.discord.lavaplayer.player.event.TrackEndEvent) event;
            if (trackEndEvent.endReason.mayStartNext) {
                nextTrack();
            }
        }
    }
}
