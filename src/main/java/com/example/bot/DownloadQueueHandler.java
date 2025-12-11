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
                                    String serverFolderBase,
                                    BlockingQueue<Runnable> downloadQueue) {

        if (input == null || input.isEmpty()) {
            messageChannel.sendMessage("Invalid track title.").queue();
            return;
        }

        // serverFolderBase is expected to be something like:
        // "config/downloads" or "/app/config/downloads"
        if (serverFolderBase == null || serverFolderBase.isEmpty()) {
            // sensible default inside the container
            serverFolderBase = "config/downloads";
        }

        // Build the per-guild/server folder: <base>/<guildId>/
        String guildId = guild != null ? guild.getId() : "global";
        File guildFolder = new File(serverFolderBase, guildId);

        // Make sure the guild folder exists
        if (!guildFolder.exists() && !guildFolder.mkdirs()) {
            logger.severe("Failed to create download folder for guild: " + guildId
                    + " at: " + guildFolder.getAbsolutePath());
        } else {
            logger.info("Using download folder: " + guildFolder.getAbsolutePath());
        }

        final File finalGuildFolder = guildFolder;
        final String finalServerFolderBase = serverFolderBase;
        final String originalInput = input;

        downloadQueue.offer(() -> {
            try {
                // If it's a YT URL, use it directly; otherwise use ytsearch:
                String query = (originalInput.contains("youtube.com") || originalInput.contains("youtu.be"))
                        ? originalInput
                        : "ytsearch:" + originalInput;

                String sanitizedSongName = sanitizeFileName(originalInput);

                // Output file inside the guild folder
                File outputFile = new File(finalGuildFolder, sanitizedSongName + ".webm");
                String outputFilePath = outputFile.getPath();

                logger.info("Expected output file path: " + outputFilePath);

                if (!outputFile.exists()) {
                    downloadAndQueueSong(query, outputFilePath, messageChannel);
                }

                if (outputFile.exists()) {
                    trackScheduler.queueSong(outputFile, originalInput);
                    messageChannel
                            .sendMessage(String.format("📍 **Queued:** `%s`", originalInput))
                            .queue(msg -> msg.suppressEmbeds(true).queue());
                } else {
                    messageChannel
                            .sendMessage("Failed to download or queue the track: " + originalInput)
                            .queue(msg -> msg.suppressEmbeds(true).queue());
                }
            } catch (IOException | InterruptedException e) {
                messageChannel
                        .sendMessage("Error downloading or queuing track: " + e.getMessage())
                        .queue(msg -> msg.suppressEmbeds(true).queue());
                logger.severe("Error processing track: " + originalInput + " - " + e.getMessage());
            }
        });
    }

    public static void clearDownloadsFolder(String serverFolderBase) {
        if (serverFolderBase == null || serverFolderBase.isEmpty()) {
            serverFolderBase = "config/downloads";
        }

        File downloadFolder = new File(serverFolderBase);
        if (downloadFolder.exists() && downloadFolder.isDirectory()) {
            File[] guildFolders = downloadFolder.listFiles();
            if (guildFolders != null) {
                for (File guildFolder : guildFolders) {
                    if (guildFolder.isDirectory()) {
                        File[] files = guildFolder.listFiles();
                        if (files != null) {
                            for (File file : files) {
                                if (file.isFile()) {
                                    file.delete();
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private static void downloadAndQueueSong(String query,
                                             String outputFilePath,
                                             GuildMessageChannel messageChannel)
            throws IOException, InterruptedException {

        File cookieFile = new File(ConfigUtils.COOKIE_FILE_PATH);

        List<String> command = new ArrayList<>();
        command.add("yt-dlp");

        if (cookieFile.exists()) {
            command.add("--cookies");
            command.add(cookieFile.getAbsolutePath());
        }

        command.add("--no-playlist");
        command.add("-o");
        command.add(outputFilePath);
        command.add(query);

        logger.info("Running yt-dlp command: " + String.join(" ", command));

        ProcessBuilder downloadBuilder = new ProcessBuilder(command);
        downloadBuilder.redirectErrorStream(true);
        // Make sure relative paths like "config/downloads/..." resolve from /app
        downloadBuilder.directory(new File("/app"));

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
