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

public class AudioLoadResultHandlerImpl implements AudioLoadResultHandler {
    private static final Logger logger = LoggerFactory.getLogger(AudioLoadResultHandlerImpl.class);

    private final AudioPlayer player;
    private final GuildMessageChannel messageChannel;
    private final TrackScheduler trackScheduler;
    private final File audioFile;
    private final String trackTitle;

    public AudioLoadResultHandlerImpl(AudioPlayer player, GuildMessageChannel messageChannel, TrackScheduler trackScheduler, File audioFile, String trackTitle) {
        this.player = player;
        this.messageChannel = messageChannel;
        this.trackScheduler = trackScheduler;
        this.audioFile = audioFile;
        this.trackTitle = trackTitle;
    }

    @Override
    public void trackLoaded(AudioTrack track) {
        track.setUserData(audioFile.getAbsolutePath());
        trackScheduler.queue(track);
        messageChannel.sendMessage("🎶 Now playing: " + trackTitle).queue();
        logger.info("Track loaded: " + trackTitle + " | File path: " + audioFile.getAbsolutePath());
    }

    @Override
    public void playlistLoaded(AudioPlaylist playlist) {
        if (!playlist.getTracks().isEmpty()) {
            AudioTrack firstTrack = playlist.getTracks().get(0);
            trackLoaded(firstTrack);
        } else {
            messageChannel.sendMessage("Playlist is empty or no search results found.").queue();
            logger.warn("Playlist is empty for the input.");
        }
    }

    @Override
    public void noMatches() {
        messageChannel.sendMessage("No matches found for the provided input.").queue();
        logger.warn("No matches found for input.");
    }

    @Override
    public void loadFailed(FriendlyException exception) {
        messageChannel.sendMessage("Failed to load track: " + exception.getMessage()).queue();
        logger.error("Failed to load track: " + exception.getMessage(), exception);
    }
}
