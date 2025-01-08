package com.example.bot;

import com.sedmelluq.discord.lavaplayer.player.AudioPlayer;
import com.sedmelluq.discord.lavaplayer.track.playback.AudioFrame;
import net.dv8tion.jda.api.audio.AudioSendHandler;

import java.nio.ByteBuffer;

// This class connects the audio player to Discord's voice channel
public class AudioPlayerSendHandler implements AudioSendHandler {
    private final AudioPlayer audioPlayer; // The audio player for playback
    private AudioFrame lastFrame;         // Stores the most recent audio frame

    // Constructor to initialize the audio player
    public AudioPlayerSendHandler(AudioPlayer audioPlayer) {
        this.audioPlayer = audioPlayer;
    }

    // Checks if there's audio data available to send
    @Override
    public boolean canProvide() {
        lastFrame = audioPlayer.provide(); // Fetch the next audio frame
        return lastFrame != null;         // Return true if a frame exists
    }

    // Provides the audio data to Discord
    @Override
    public ByteBuffer provide20MsAudio() {
        return ByteBuffer.wrap(lastFrame.getData()); // Convert audio frame to ByteBuffer
    }

    // Indicates that the audio being sent is in Opus format
    @Override
    public boolean isOpus() {
        return true; // Discord requires Opus-encoded audio
    }
}
