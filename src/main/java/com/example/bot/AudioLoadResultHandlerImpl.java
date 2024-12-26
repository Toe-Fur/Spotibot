package com.example.bot;

import com.sedmelluq.discord.lavaplayer.player.AudioPlayer;
import com.sedmelluq.discord.lavaplayer.player.AudioLoadResultHandler;
import com.sedmelluq.discord.lavaplayer.track.AudioPlaylist;
import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import com.sedmelluq.discord.lavaplayer.tools.FriendlyException;
import net.dv8tion.jda.api.entities.channel.middleman.GuildMessageChannel;

import java.io.File;

public class AudioLoadResultHandlerImpl implements AudioLoadResultHandler {
    private final AudioPlayer player;
    private final GuildMessageChannel messageChannel;
    private final TrackScheduler trackScheduler;
    private final File audioFile;

    public AudioLoadResultHandlerImpl(AudioPlayer player, GuildMessageChannel messageChannel, TrackScheduler trackScheduler, File audioFile) {
        this.player = player;
        this.messageChannel = messageChannel;
        this.trackScheduler = trackScheduler;
        this.audioFile = audioFile;
    }

    @Override
    public void trackLoaded(AudioTrack track) {
        track.setUserData(audioFile.getAbsolutePath());
        trackScheduler.queue(track);
        messageChannel.sendMessage("Queued: " + track.getInfo().title).queue();
    }

    @Override
    public void playlistLoaded(AudioPlaylist playlist) {
        if (!playlist.getTracks().isEmpty()) {
            AudioTrack firstTrack = playlist.getTracks().get(0);
            trackLoaded(firstTrack);
        } else {
            messageChannel.sendMessage("Playlist is empty or no search results found.").queue();
        }
    }

    @Override
    public void noMatches() {
        messageChannel.sendMessage("No matches found for the provided input.").queue();
    }

    @Override
    public void loadFailed(FriendlyException exception) {
        messageChannel.sendMessage("Failed to load track: " + exception.getMessage()).queue();
    }
}
