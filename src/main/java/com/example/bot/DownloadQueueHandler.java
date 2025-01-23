package com.example.bot;

import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.channel.middleman.GuildMessageChannel;
import java.util.concurrent.BlockingQueue;
import java.io.File;
import java.io.IOException;
import java.util.logging.Logger;
import java.util.concurrent.TimeUnit;
import java.io.BufferedReader;
import java.io.InputStreamReader;

public class DownloadQueueHandler {
    private static final Logger logger = Logger.getLogger(DownloadQueueHandler.class.getName());

    public static void queueAndPlay(String trackTitle, TrackScheduler trackScheduler, GuildMessageChannel messageChannel, Guild guild, String serverFolder, BlockingQueue<Runnable> downloadQueue) {
        downloadQueue.offer(() -> {
            try {
                String query = "ytsearch:" + trackTitle;
                String sanitizedSongName = sanitizeFileName(trackTitle);
                String outputFilePath = serverFolder + sanitizedSongName + ".webm";

                File downloadedFile = new File(outputFilePath);
                if (!downloadedFile.exists()) {
                    downloadAndQueueSong(query, outputFilePath, trackScheduler, messageChannel, guild);
                }

                if (downloadedFile.exists()) {
                    trackScheduler.queueSong(downloadedFile, trackTitle);
                    messageChannel.sendMessage(String.format("📍 **Queued:** `%s`", trackTitle)).queue(msg -> msg.suppressEmbeds(true).queue());
                } else {
                    messageChannel.sendMessage("Failed to download or queue the track: " + trackTitle).queue(msg -> msg.suppressEmbeds(true).queue());
                }
            } catch (IOException | InterruptedException e) {
                messageChannel.sendMessage("Error downloading or queuing track: " + e.getMessage()).queue(msg -> msg.suppressEmbeds(true).queue());
                logger.severe("Error processing track: " + trackTitle + " - " + e.getMessage());
            }
        });
    }

    public static void clearDownloadsFolder(String serverFolder) {
        File downloadFolder = new File(serverFolder);
        if (downloadFolder.exists() && downloadFolder.isDirectory()) {
            for (File file : downloadFolder.listFiles()) {
                if (file.isFile()) {
                    file.delete();
                }
            }
        }
    }

    private static void downloadAndQueueSong(String query, String outputFilePath, TrackScheduler trackScheduler, GuildMessageChannel messageChannel, Guild guild) throws IOException, InterruptedException {
        File cookieFile = new File(ConfigUtils.COOKIE_FILE_PATH);

        ProcessBuilder downloadBuilder;
        if (cookieFile.exists()) {
            downloadBuilder = new ProcessBuilder(
                "yt-dlp",
                "-4",
                "-f", "bestaudio",
                "--no-playlist",
                "--cookies", cookieFile.getAbsolutePath(),
                "-o", outputFilePath,
                query
            );
        } else {
            downloadBuilder = new ProcessBuilder(
                "yt-dlp",
                "-4",
                "-f", "bestaudio",
                "--no-playlist",
                "-o", outputFilePath,
                query
            );
        }

        Process downloadProcess = downloadBuilder.start();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(downloadProcess.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                logger.info(line);
            }
        }

        boolean completedInTime = downloadProcess.waitFor(120, TimeUnit.SECONDS);

        if (!completedInTime) {
            downloadProcess.destroyForcibly();
            logger.severe("Download process timed out for query: " + query);
            messageChannel.sendMessage("Failed to download song: " + query).queue(msg -> msg.suppressEmbeds(true).queue());
        }

        File downloadedFile = new File(outputFilePath);
        if (!downloadedFile.exists()) {
            messageChannel.sendMessage("Failed to download song: " + query).queue(msg -> msg.suppressEmbeds(true).queue());
        }
    }

    private static String sanitizeFileName(String input) {
        return input.replaceAll("[^a-zA-Z0-9_-]", "_");
    }
}
