package com.example.bot;

import com.sedmelluq.discord.lavaplayer.player.AudioPlayer;
import com.sedmelluq.discord.lavaplayer.player.AudioLoadResultHandler;
import com.sedmelluq.discord.lavaplayer.track.AudioPlaylist;
import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import com.sedmelluq.discord.lavaplayer.tools.FriendlyException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.dv8tion.jda.api.entities.channel.middleman.GuildMessageChannel;

import java.io.File;

// Handles the results of loading audio tracks or playlists
public class AudioLoadResultHandlerImpl implements AudioLoadResultHandler {
    private static final Logger logger = LoggerFactory.getLogger(AudioLoadResultHandlerImpl.class);

    // The audio player responsible for playback
    private final AudioPlayer player;
    // The channel to send user messages
    private final GuildMessageChannel messageChannel;
    // The track scheduler managing the queue
    private final TrackScheduler trackScheduler;
    // The file where the audio is stored
    private final File audioFile;
    // The title of the track
    private final String trackTitle;

    // Constructor to initialize handler components
    public AudioLoadResultHandlerImpl(AudioPlayer player, GuildMessageChannel messageChannel, TrackScheduler trackScheduler, File audioFile, String trackTitle) {
        this.player = player;
        this.messageChannel = messageChannel;
        this.trackScheduler = trackScheduler;
        this.audioFile = audioFile;
        this.trackTitle = trackTitle;
    }

    // Handles loading a single track
    @Override
    public void trackLoaded(AudioTrack track) {
        // Attach the file path as user data to the track
        track.setUserData(audioFile.getAbsolutePath());
        // Queue the track for playback
        trackScheduler.queue(track);
        // Notify the user that the track is now playing
        messageChannel.sendMessage("🎶 Now playing: (Title).").queue();
        // Log the loaded track info
        logger.info("Track loaded: " + trackTitle + " | File path: " + audioFile.getAbsolutePath());
    }

    // Handles loading a playlist
    @Override
    public void playlistLoaded(AudioPlaylist playlist) {
        if (!playlist.getTracks().isEmpty()) {
            // Play the first track in the playlist
            AudioTrack firstTrack = playlist.getTracks().get(0);
            trackLoaded(firstTrack);
        } else {
            // Notify the user if the playlist is empty
            messageChannel.sendMessage("Playlist is empty or no search results found.").queue();
            // Log the empty playlist warning
            logger.warn("Playlist is empty for the input.");
        }
    }

    // Handles cases where no matches are found
    @Override
    public void noMatches() {
        // Notify the user that no matches were found
        messageChannel.sendMessage("No matches found for the provided input.").queue();
        // Log the no-match scenario
        logger.warn("No matches found for input.");
    }

    // Handles failures during track loading
    @Override
    public void loadFailed(FriendlyException exception) {
        // Notify the user about the failure
        messageChannel.sendMessage("Failed to load track: " + exception.getMessage()).queue();
        // Log the error details
        logger.error("Failed to load track: " + exception.getMessage(), exception);
    }
}
