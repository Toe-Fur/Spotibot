package com.example.bot;

import com.github.natanbc.lavadsp.rotation.RotationPcmAudioFilter;
import com.github.natanbc.lavadsp.timescale.TimescalePcmAudioFilter;
import com.sedmelluq.discord.lavaplayer.filter.AudioFilter;
import com.sedmelluq.discord.lavaplayer.filter.FloatPcmAudioFilter;
import com.sedmelluq.discord.lavaplayer.filter.PcmFilterFactory;
import com.sedmelluq.discord.lavaplayer.filter.UniversalPcmAudioFilter;
import com.sedmelluq.discord.lavaplayer.filter.equalizer.Equalizer;
import com.sedmelluq.discord.lavaplayer.player.AudioPlayer;
import com.sedmelluq.discord.lavaplayer.format.AudioDataFormat;
import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Fun sound filters for the whole player (applies to every song until turned off). */
public final class SoundFilters {
    private static final Logger logger = LoggerFactory.getLogger(SoundFilters.class);

    public enum Filter {
        OFF("Normal"), BASS("Bass boost"), NIGHTCORE("Nightcore"), SLOWED("Slowed"), EIGHT_D("8D audio");

        public final String label;

        Filter(String label) {
            this.label = label;
        }

        public static Filter parse(String s) {
            String x = s == null ? "" : s.toLowerCase().replaceAll("[^a-z0-9]", "");
            return switch (x) {
                case "bass", "bassboost", "boost" -> BASS;
                case "nightcore", "fast", "spedup" -> NIGHTCORE;
                case "slowed", "slow", "slowedreverb", "slowedandreverb" -> SLOWED;
                case "8d", "8daudio", "eightd", "rotate", "rotation" -> EIGHT_D;
                default -> OFF;
            };
        }
    }

    private SoundFilters() {
    }

    public static void apply(AudioPlayer player, Filter f) {
        if (f == null || f == Filter.OFF) {
            player.setFilterFactory(null);
            return;
        }
        player.setFilterFactory(new Factory(f));
        player.setFrameBufferDuration(500); // filter changes are heard within ~0.5 s
    }

    private record Factory(Filter f) implements PcmFilterFactory {
        @Override
        public List<AudioFilter> buildChain(AudioTrack track, AudioDataFormat format, UniversalPcmAudioFilter output) {
            try {
                List<AudioFilter> chain = new ArrayList<>();
                FloatPcmAudioFilter next = output;
                switch (f) {
                    case BASS -> {
                        Equalizer eq = new Equalizer(format.channelCount, next);
                        float[] gains = {0.20f, 0.15f, 0.10f, 0.05f, 0.0f};
                        for (int band = 0; band < gains.length; band++) eq.setGain(band, gains[band]);
                        chain.add(eq);
                    }
                    case NIGHTCORE -> chain.add(new TimescalePcmAudioFilter(next, format.channelCount, format.sampleRate)
                            .setSpeed(1.2).setPitch(1.2));
                    case SLOWED -> chain.add(new TimescalePcmAudioFilter(next, format.channelCount, format.sampleRate)
                            .setSpeed(0.85).setPitch(0.9));
                    case EIGHT_D -> chain.add(new RotationPcmAudioFilter(next, format.sampleRate).setRotationSpeed(0.15));
                    default -> {
                        return Collections.emptyList();
                    }
                }
                return chain;
            } catch (Throwable t) { // e.g. native timescale library unavailable on this platform
                logger.warn("Sound filter {} unavailable: {}", f, t.toString());
                return Collections.emptyList();
            }
        }
    }
}
