package com.example.bot;

import com.sedmelluq.discord.lavaplayer.player.AudioPlayerManager;
import com.sedmelluq.discord.lavaplayer.source.AudioSourceManager;
import com.sedmelluq.discord.lavaplayer.track.AudioItem;
import com.sedmelluq.discord.lavaplayer.track.AudioReference;
import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import com.sedmelluq.discord.lavaplayer.track.AudioTrackInfo;
import com.sedmelluq.discord.lavaplayer.tools.FriendlyException;

import java.io.DataInput;
import java.io.DataOutput;

public class InputStreamAudioSourceManager implements AudioSourceManager {

    @Override
    public String getSourceName() {
        return "InputStream";
    }

    @Override
    public AudioItem loadItem(AudioPlayerManager manager, AudioReference reference) {
        // This implementation does not support loading from AudioReference
        return null;
    }

    @Override
    public AudioTrack decodeTrack(AudioTrackInfo trackInfo, DataInput input) throws FriendlyException {
        // This implementation does not support decoding tracks
        throw new UnsupportedOperationException("Decoding from DataInput is not supported.");
    }

    @Override
    public void encodeTrack(AudioTrack track, DataOutput output) throws FriendlyException {
        // This implementation does not support encoding tracks
        throw new UnsupportedOperationException("Encoding is not supported.");
    }

    @Override
    public boolean isTrackEncodable(AudioTrack track) {
        // Return false as this implementation does not support track encoding
        return false;
    }

    @Override
    public void shutdown() {
        // No resources to clean up
    }
}
