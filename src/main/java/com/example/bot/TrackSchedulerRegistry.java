package com.example.bot;

import net.dv8tion.jda.api.entities.Guild;
import com.sedmelluq.discord.lavaplayer.player.AudioPlayerManager;

import java.util.concurrent.ConcurrentHashMap; // For thread-safe management of schedulers
import com.sedmelluq.discord.lavaplayer.player.AudioPlayer; // For AudioPlayer
import com.example.bot.Spotibot; // Add this import

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
     * Retrieves an existing TrackScheduler for a guild or creates a new one if it doesn't exist.
     *
     * @param guild The guild for which the scheduler is needed.
     * @param bot   The main bot instance.
     * @param player The AudioPlayer associated with this scheduler.
     * @return The TrackScheduler for the given guild.
     */
    public TrackScheduler getOrCreate(Guild guild, Spotibot bot, AudioPlayer player) {
        // Check if a scheduler already exists for the guild
        return trackSchedulers.computeIfAbsent(guild.getIdLong(), guildId ->
            new TrackScheduler(playerManager, player, bot, guild) // Create a new scheduler
        );
    }
}
