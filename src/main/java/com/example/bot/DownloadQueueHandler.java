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

    public static void queueAndPlay(String input, TrackScheduler trackScheduler, GuildMessageChannel messageChannel, Guild guild, String serverFolder, BlockingQueue<Runnable> downloadQueue) {
        if (input == null || input.isEmpty()) {
            messageChannel.sendMessage("Invalid track title.").queue();
            return;
        }

        downloadQueue.offer(() -> {
            try {
                String query = input.contains("youtube.com") || input.contains("youtu.be") ? input : "ytsearch:" + input;
                String sanitizedSongName = sanitizeFileName(input);
                String outputFilePath = serverFolder + sanitizedSongName + ".webm";

                File downloadedFile = new File(outputFilePath);
                if (!downloadedFile.exists()) {
                    downloadAndQueueSong(query, outputFilePath, trackScheduler, messageChannel, guild);
                }

                if (downloadedFile.exists()) {
                    trackScheduler.queueSong(downloadedFile, input);
                    messageChannel.sendMessage(String.format("📍 **Queued:** `%s`", input)).queue(msg -> msg.suppressEmbeds(true).queue());
                } else {
                    messageChannel.sendMessage("Failed to download or queue the track: " + input).queue(msg -> msg.suppressEmbeds(true).queue());
                }
            } catch (IOException | InterruptedException e) {
                messageChannel.sendMessage("Error downloading or queuing track: " + e.getMessage()).queue(msg -> msg.suppressEmbeds(true).queue());
                logger.severe("Error processing track: " + input + " - " + e.getMessage());
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

    private static void downloadAndQueueSong(
            String query,
            String outputFilePath,
            TrackScheduler trackScheduler,
            GuildMessageChannel messageChannel,
            Guild guild
    ) throws IOException, InterruptedException {

        // Resolve cookie path (exporter writes to /app/config/cookies.txt)
        String cookiePathToUse = null;
        File cookieFile = null;

        try {
            String p = ConfigUtils.COOKIE_FILE_PATH; // set this to "/app/config/cookies.txt"
            if (p != null && !p.isBlank()) {
                cookieFile = new File(p);
                if (cookieFile.exists() && cookieFile.isFile() && cookieFile.length() > 0) {
                    // Copy to temp so yt-dlp doesn't read a file while exporter might be rewriting it
                    File tmp = File.createTempFile("spotibot-cookies-", ".txt");
                    java.nio.file.Files.copy(
                            cookieFile.toPath(),
                            tmp.toPath(),
                            java.nio.file.StandardCopyOption.REPLACE_EXISTING
                    );
                    tmp.deleteOnExit();
                    cookiePathToUse = tmp.getAbsolutePath();
                    logger.info("Using cookies for yt-dlp (temp copy): " + cookiePathToUse);
                } else {
                    logger.warning("cookies.txt missing/empty at: " + p + " (continuing without cookies)");
                }
            }
        } catch (Exception e) {
            logger.warning("Failed to validate/copy cookies file; continuing without cookies. " + e.getMessage());
        }

        ProcessBuilder downloadBuilder;

        if (cookiePathToUse != null) {
            downloadBuilder = new ProcessBuilder(
                    "yt-dlp",
                    "-4",
                    "--js-runtimes", "deno",
                    "-f", "bestaudio/best",
                    "--no-playlist",
                    "--cookies", cookiePathToUse,
                    "-o", outputFilePath,
                    query
            );
        } else {
            downloadBuilder = new ProcessBuilder(
                    "yt-dlp",
                    "-4",
                    "--js-runtimes", "deno",
                    "-f", "bestaudio/best",
                    "--no-playlist",
                    "-o", outputFilePath,
                    query
            );
        }

        downloadBuilder.redirectErrorStream(true);
        logger.info("Running: " + String.join(" ", downloadBuilder.command()));

        Process downloadProcess = downloadBuilder.start();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(downloadProcess.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                logger.info(line);
            }
        }

        boolean completedInTime = downloadProcess.waitFor(180, TimeUnit.SECONDS);
        if (!completedInTime) {
            downloadProcess.destroyForcibly();
            logger.severe("Download process timed out for query: " + query);
            messageChannel.sendMessage("Failed to download song (timeout): " + query)
                    .queue(msg -> msg.suppressEmbeds(true).queue());
            return;
        }

        int exit = downloadProcess.exitValue();
        logger.info("yt-dlp exit code: " + exit);

        File downloadedFile = new File(outputFilePath);
        if (exit != 0 || !downloadedFile.exists()) {
            messageChannel.sendMessage("Failed to download song: " + query)
                    .queue(msg -> msg.suppressEmbeds(true).queue());
        }
    }

    private static String sanitizeFileName(String input) {
        return input.replaceAll("[^a-zA-Z0-9_-]", "_");
    }
}
