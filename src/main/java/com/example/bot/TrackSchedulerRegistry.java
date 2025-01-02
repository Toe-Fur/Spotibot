package com.example.bot;

import net.dv8tion.jda.api.entities.Guild;
import com.sedmelluq.discord.lavaplayer.player.AudioPlayerManager;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;  // For ConcurrentHashMap
import com.sedmelluq.discord.lavaplayer.player.AudioPlayer;  // For AudioPlayer


public class TrackSchedulerRegistry {
    private final AudioPlayerManager playerManager;
    private final ConcurrentHashMap<Long, TrackScheduler> trackSchedulers = new ConcurrentHashMap<>();

    public TrackSchedulerRegistry(AudioPlayerManager playerManager) {
        this.playerManager = playerManager;
    }

    // Ensure that both AudioPlayer and Spotibot are passed
    public TrackScheduler getOrCreate(Guild guild, Spotibot bot, AudioPlayer player) {
        TrackScheduler trackScheduler = trackSchedulers.get(guild.getIdLong());
        if (trackScheduler == null) {
            trackScheduler = new TrackScheduler(player, bot, guild); 
            trackSchedulers.put(guild.getIdLong(), trackScheduler);
        }
        return trackScheduler;
    }
}