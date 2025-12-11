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
import java.util.ArrayList;
import java.util.List;

public class DownloadQueueHandler {
    private static final Logger logger = Logger.getLogger(DownloadQueueHandler.class.getName());

    public static void queueAndPlay(String input,
                                    TrackScheduler trackScheduler,
                                    GuildMessageChannel messageChannel,
                                    Guild guild,
                                    String serverFolder,
                                    BlockingQueue<Runnable> downloadQueue) {

        if (input == null || input.isEmpty()) {
            messageChannel.sendMessage("Invalid track title.").queue();
            return;
        }

        // Make sure the download directory exists
        if (serverFolder != null && !serverFolder.isEmpty()) {
            File folder = new File(serverFolder);
            if (!folder.exists() && !folder.mkdirs()) {
                logger.severe("Failed to create download folder: " + serverFolder);
            }
        }

        downloadQueue.offer(() -> {
            try {
                // If it's a YT URL, use it directly; otherwise use ytsearch:
                String query = (input.contains("youtube.com") || input.contains("youtu.be"))
                        ? input
                        : "ytsearch:" + input;

                String sanitizedSongName = sanitizeFileName(input);
                String outputFilePath = serverFolder + sanitizedSongName + ".webm";

                File downloadedFile = new File(outputFilePath);

                if (!downloadedFile.exists()) {
                    downloadAndQueueSong(query, outputFilePath, messageChannel);
                }

                if (downloadedFile.exists()) {
                    trackScheduler.queueSong(downloadedFile, input);
                    messageChannel
                            .sendMessage(String.format("📍 **Queued:** `%s`", input))
                            .queue(msg -> msg.suppressEmbeds(true).queue());
                } else {
                    messageChannel
                            .sendMessage("Failed to download or queue the track: " + input)
                            .queue(msg -> msg.suppressEmbeds(true).queue());
                }
            } catch (IOException | InterruptedException e) {
                messageChannel
                        .sendMessage("Error downloading or queuing track: " + e.getMessage())
                        .queue(msg -> msg.suppressEmbeds(true).queue());
                logger.severe("Error processing track: " + input + " - " + e.getMessage());
            }
        });
    }

    public static void clearDownloadsFolder(String serverFolder) {
        File downloadFolder = new File(serverFolder);
        if (downloadFolder.exists() && downloadFolder.isDirectory()) {
            File[] files = downloadFolder.listFiles();
            if (files != null) {
                for (File file : files) {
                    if (file.isFile()) {
                        // We don't care if delete fails silently here
                        file.delete();
                    }
                }
            }
        }
    }

    /**
     * Actually calls yt-dlp and logs everything.
     * This is now tuned to match the working command you ran in the container:
     * yt-dlp --cookies /app/config/cookies.txt "https://youtu.be/qrm8w-pV120"
     */
    private static void downloadAndQueueSong(String query,
                                             String outputFilePath,
                                             GuildMessageChannel messageChannel)
            throws IOException, InterruptedException {

        File cookieFile = new File(ConfigUtils.COOKIE_FILE_PATH);

        // Build yt-dlp command as close as possible to the known-good one
        List<String> command = new ArrayList<>();
        command.add("yt-dlp");

        // Use cookies if they exist (e.g. /app/config/cookies.txt)
        if (cookieFile.exists()) {
            command.add("--cookies");
            command.add(cookieFile.getAbsolutePath());
        }

        // Avoid over-specifying formats; let yt-dlp pick best (like your working run)
        command.add("--no-playlist");   // still safe to keep
        command.add("-o");
        command.add(outputFilePath);    // absolute or full path

        // Finally, the URL or ytsearch: query
        command.add(query);

        logger.info("Running yt-dlp command: " + String.join(" ", command));

        ProcessBuilder downloadBuilder = new ProcessBuilder(command);
        downloadBuilder.redirectErrorStream(true);  // merge stderr into stdout

        Process downloadProcess = downloadBuilder.start();

        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(downloadProcess.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                logger.info("[yt-dlp] " + line);
            }
        }

        boolean completedInTime = downloadProcess.waitFor(120, TimeUnit.SECONDS);
        int exitCode = downloadProcess.exitValue();

        logger.info("yt-dlp exited with code: " + exitCode + " for query: " + query);

        if (!completedInTime) {
            downloadProcess.destroyForcibly();
            logger.severe("Download process timed out for query: " + query);
            messageChannel
                    .sendMessage("Failed to download song (timeout): " + query)
                    .queue(msg -> msg.suppressEmbeds(true).queue());
        } else if (exitCode != 0) {
            logger.severe("yt-dlp failed with exit code " + exitCode + " for query: " + query);
            messageChannel
                    .sendMessage("Failed to download song: " + query)
                    .queue(msg -> msg.suppressEmbeds(true).queue());
        }

        File downloadedFile = new File(outputFilePath);
        if (!downloadedFile.exists()) {
            logger.severe("Expected downloaded file not found at: " + outputFilePath);
            messageChannel
                    .sendMessage("Failed to download song: " + query)
                    .queue(msg -> msg.suppressEmbeds(true).queue());
        }
    }

    private static String sanitizeFileName(String input) {
        return input.replaceAll("[^a-zA-Z0-9_-]", "_");
    }
}
