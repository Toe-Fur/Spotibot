package com.example.bot;

import com.sedmelluq.discord.lavaplayer.player.AudioPlayer;
import com.sedmelluq.discord.lavaplayer.player.AudioPlayerManager;
import com.sedmelluq.discord.lavaplayer.player.DefaultAudioPlayerManager;
import com.sedmelluq.discord.lavaplayer.source.AudioSourceManagers;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.channel.middleman.GuildMessageChannel;
import net.dv8tion.jda.api.entities.channel.concrete.VoiceChannel;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.requests.GatewayIntent;

import javax.security.auth.login.LoginException;
import java.io.File;
import java.io.IOException;

public class Spotibot extends ListenerAdapter {
    private final AudioPlayerManager playerManager = new DefaultAudioPlayerManager();
    private final AudioPlayer player = playerManager.createPlayer();
    private final TrackScheduler trackScheduler = new TrackScheduler(player);
    private volatile Process currentDownloadProcess = null; // Track the current download process

    private static final String BOT_TOKEN = System.getenv("DISCORD_BOT_TOKEN");

    public static void main(String[] args) throws LoginException {
        if (BOT_TOKEN == null || BOT_TOKEN.isEmpty()) {
            throw new IllegalArgumentException("Bot token is not set. Please set the DISCORD_BOT_TOKEN environment variable.");
        }

        JDA jda = JDABuilder.createDefault(BOT_TOKEN)
                .enableIntents(GatewayIntent.MESSAGE_CONTENT)
                .addEventListeners(new Spotibot())
                .build();
    }

    public Spotibot() {
        AudioSourceManagers.registerRemoteSources(playerManager);
        AudioSourceManagers.registerLocalSource(playerManager);
    }

    @Override
public void onMessageReceived(MessageReceivedEvent event) {
    String message = event.getMessage().getContentRaw();
    GuildMessageChannel messageChannel = event.getChannel().asGuildMessageChannel();
    Guild guild = event.getGuild();

    if (message.startsWith("!play ")) {
        String input = message.replace("!play ", "").trim();
        VoiceChannel voiceChannel = (VoiceChannel) event.getMember().getVoiceState().getChannel();

        if (voiceChannel == null) {
            messageChannel.sendMessage("You must be in a voice channel to use this command.").queue();
            return;
        }

        guild.getAudioManager().setSendingHandler(new AudioPlayerSendHandler(player));
        guild.getAudioManager().openAudioConnection(voiceChannel);

        try {
            File downloadedFile = downloadYouTubeAudio(input);
            if (downloadedFile != null) {
                playerManager.loadItem(downloadedFile.getAbsolutePath(), new AudioLoadResultHandlerImpl(player, messageChannel, trackScheduler, downloadedFile));
            } else {
                messageChannel.sendMessage("Download timed out. Skipping to the next track.").queue();
                trackScheduler.nextTrack();
            }
        } catch (IOException | InterruptedException e) {
            messageChannel.sendMessage("Error while downloading audio: " + e.getMessage()).queue();
            e.printStackTrace();
        }
    } else if (message.equalsIgnoreCase("!skip")) {
        if (player.getPlayingTrack() != null) {
            trackScheduler.nextTrack();
            messageChannel.sendMessage("Skipped to the next track.").queue();
        } else {
            messageChannel.sendMessage("No track is currently playing to skip.").queue();
        }
    } else if (message.equalsIgnoreCase("!forceskip")) {
        if (currentDownloadProcess != null && currentDownloadProcess.isAlive()) {
            currentDownloadProcess.destroyForcibly();
            currentDownloadProcess = null; // Clear the reference to avoid reuse
            messageChannel.sendMessage("Force skipped the current download.").queue();
        } else {
            messageChannel.sendMessage("No download is currently in progress to force skip.").queue();
        }
        trackScheduler.nextTrack();
    } else if (message.equalsIgnoreCase("!stop")) {
        guild.getAudioManager().closeAudioConnection();
        trackScheduler.clearQueueAndStop();
        clearDownloadsFolder();
        messageChannel.sendMessage("Stopped playback, cleared the queue, and deleted all downloads.").queue();
    } else if (message.equalsIgnoreCase("!help")) {
        messageChannel.sendMessage(getHelpMessage()).queue();
    }
}

private String getHelpMessage() {
    return "**Spotibot Commands**:\n" +
            "`!play <URL or search term>` - Plays a YouTube video. If something is already playing, it will queue the track.\n" +
            "`!skip` - Skips the currently playing track and plays the next one in the queue.\n" +
            "`!forceskip` - Force skips the current download or playback and moves to the next track in the queue.\n" +
            "`!stop` - Stops playback, clears the queue, and deletes all downloaded tracks.\n" +
            "`!help` - Shows this list of commands.";
}

    private File downloadYouTubeAudio(String input) throws IOException, InterruptedException {
        String outputFilePath = "downloads/" + System.currentTimeMillis() + ".mp3"; // Unique file for each download

        // Ensure the downloads directory exists
        File downloadsDir = new File("downloads");
        if (!downloadsDir.exists()) {
            downloadsDir.mkdir();
        }

        // Check if the input is a URL or a search query
        String query = input.startsWith("http://") || input.startsWith("https://") ? input : "ytsearch:" + input;

        // Command to invoke yt-dlp
        ProcessBuilder builder = new ProcessBuilder(
                "yt-dlp",                // yt-dlp command (installed via Chocolatey or in PATH)
                "-x",                    // Extract audio
                "--audio-format", "mp3", // Specify MP3 as the output format
                "-o", outputFilePath,    // Output file path for the downloaded audio
                query                    // YouTube URL or search query
        );

        builder.redirectErrorStream(true); // Combine stdout and stderr for easier debugging
        currentDownloadProcess = builder.start(); // Track the current process

        // Wait for the download process to complete with a timeout
        boolean completedInTime = currentDownloadProcess.waitFor(15, java.util.concurrent.TimeUnit.SECONDS);

        if (!completedInTime) {
            // If the process exceeds the timeout, destroy it
            currentDownloadProcess.destroyForcibly();
            currentDownloadProcess = null;
            System.err.println("Download timed out for: " + query);
            return null; // Indicate failure
        }

        currentDownloadProcess = null; // Clear the reference after successful download

        // Verify if the file exists after the process
        File downloadedFile = new File(outputFilePath);
        return downloadedFile.exists() ? downloadedFile : null;
    }

    private void clearDownloadsFolder() {
        File downloadsDir = new File("downloads");
        if (downloadsDir.exists() && downloadsDir.isDirectory()) {
            File[] files = downloadsDir.listFiles();
            if (files != null) {
                for (File file : files) {
                    if (file.isFile() && file.getName().endsWith(".mp3")) {
                        boolean deleted = file.delete();
                        if (!deleted) {
                            System.err.println("Failed to delete file: " + file.getAbsolutePath());
                        }
                    }
                }
            }
        }
    }
}
