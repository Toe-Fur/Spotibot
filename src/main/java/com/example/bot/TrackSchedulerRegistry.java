package com.example.bot;

import net.dv8tion.jda.api.entities.Guild;
import com.sedmelluq.discord.lavaplayer.player.AudioPlayerManager;

import java.util.concurrent.ConcurrentHashMap; // For thread-safe management of schedulers
import com.sedmelluq.discord.lavaplayer.player.AudioPlayer; // For AudioPlayer

// Handles the creation and management of TrackSchedulers for multiple guilds
public class TrackSchedulerRegistry {
    private final AudioPlayerManager playerManager; // Manages the audio players
    private final ConcurrentHashMap<Long, TrackScheduler> trackSchedulers = new ConcurrentHashMap<>(); // Map of guild IDs to their TrackSchedulers

    // Constructor to initialize the registry with the player manager
    public TrackSchedulerRegistry(AudioPlayerManager playerManager) {
        this.playerManager = playerManager;
    }

    public void reset() {
        trackSchedulers.clear();
    }

    /**
     * Returns the existing TrackScheduler for a guild, or null if none exists.
     */
    public TrackScheduler get(Guild guild) {
        return trackSchedulers.get(guild.getIdLong());
    }

    /**
     * Retrieves an existing TrackScheduler for a guild or creates a new one if it doesn't exist.
     * The AudioPlayer is created internally only when a new scheduler is actually needed.
     *
     * @param guild The guild for which the scheduler is needed.
     * @param bot   The main bot instance.
     * @return The TrackScheduler for the given guild.
     */
    public TrackScheduler getOrCreate(Guild guild, Spotibot bot) {
        return trackSchedulers.computeIfAbsent(guild.getIdLong(), guildId ->
            new TrackScheduler(playerManager, playerManager.createPlayer(), bot, guild)
        );
    }
}
