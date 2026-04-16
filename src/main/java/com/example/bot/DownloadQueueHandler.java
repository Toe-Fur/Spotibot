package com.example.bot;

import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.channel.middleman.GuildMessageChannel;
import java.util.concurrent.BlockingQueue;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
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

        String displayTitle = input.replace("ytsearch:", "").trim();

        // Send "Searching…" immediately so the user gets instant feedback, then edit it to the final status.
        messageChannel.sendMessage("🔍 Searching for `" + displayTitle + "`...").queue(searchMsg -> {
            trackScheduler.incrementPendingDownloads();
            downloadQueue.offer(() -> {
                boolean delegatedToQueueSong = false;
                long durationSeconds = 0;
                try {
                    String query = input.contains("youtube.com") || input.contains("youtu.be") ? input : "ytsearch:" + input;
                    String sanitizedSongName = sanitizeFileName(input);
                    String outputFilePath = serverFolder + sanitizedSongName + ".webm";

                    File downloadedFile = new File(outputFilePath);
                    if (!downloadedFile.exists()) {
                        // Fetch duration + approx file size before downloading so we can show progress info
                        long[] meta = fetchMetadata(query);
                        if (meta != null) {
                            durationSeconds = meta[0];
                            long filesize = meta[1];
                            StringBuilder dlMsg = new StringBuilder("⏳ Downloading `").append(displayTitle).append("`");
                            if (durationSeconds > 0 || filesize > 0) {
                                dlMsg.append("  —  ");
                                if (durationSeconds > 0) dlMsg.append(formatDuration(durationSeconds));
                                if (filesize > 0) {
                                    if (durationSeconds > 0) dlMsg.append(", ");
                                    dlMsg.append(formatFileSize(filesize));
                                }
                            }
                            final String finalDlMsg = dlMsg.toString();
                            searchMsg.editMessage(finalDlMsg).queue(null, e -> {});
                        }
                        downloadAndQueueSong(query, outputFilePath, trackScheduler, messageChannel, guild);
                    }

                    if (downloadedFile.exists()) {
                        delegatedToQueueSong = true; // queueSong's load callbacks will decrement
                        trackScheduler.queueSong(downloadedFile, input);
                        String queuedMsg = String.format("📍 **Queued:** `%s`", displayTitle);
                        if (durationSeconds > 0) queuedMsg += "  [" + formatDuration(durationSeconds) + "]";
                        final String finalQueuedMsg = queuedMsg;
                        searchMsg.editMessage(finalQueuedMsg)
                                .queue(m -> m.suppressEmbeds(true).queue(), e -> {});
                    } else {
                        searchMsg.editMessage("❌ Failed to download: `" + displayTitle + "`")
                                .queue(null, e -> {});
                    }
                } catch (IOException | InterruptedException e) {
                    searchMsg.editMessage("❌ Error downloading `" + displayTitle + "`: " + e.getMessage())
                            .queue(null, e2 -> {});
                    logger.severe("Error processing track: " + input + " - " + e.getMessage());
                } finally {
                    if (!delegatedToQueueSong) {
                        trackScheduler.decrementPendingDownloads();
                    }
                }
            });
        });
    }

    /**
     * Fetches duration (seconds) and approximate file size (bytes) from yt-dlp without downloading.
     * Returns [durationSeconds, filesizeBytes], or null if the fetch fails or times out.
     */
    private static long[] fetchMetadata(String query) {
        try {
            ProcessBuilder pb = new ProcessBuilder(
                    "yt-dlp",
                    "--print", "duration",
                    "--print", "filesize_approx",
                    "--no-download",
                    "--no-warnings",
                    "--no-playlist",
                    "-f", "bestaudio/best",
                    query
            );
            pb.redirectErrorStream(false);
            Process process = pb.start();
            List<String> lines = new ArrayList<>();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    line = line.trim();
                    if (!line.isBlank()) lines.add(line);
                }
            }
            boolean done = process.waitFor(15, TimeUnit.SECONDS);
            if (!done) { process.destroyForcibly(); return null; }

            long duration = 0, filesize = 0;
            if (lines.size() >= 1 && !lines.get(0).equals("NA")) {
                duration = (long) Double.parseDouble(lines.get(0));
            }
            if (lines.size() >= 2 && !lines.get(1).equals("NA")) {
                filesize = Long.parseLong(lines.get(1));
            }
            return new long[]{duration, filesize};
        } catch (Exception e) {
            logger.warning("Metadata fetch failed (non-fatal): " + e.getMessage());
            return null;
        }
    }

    private static String formatDuration(long totalSeconds) {
        long h = totalSeconds / 3600;
        long m = (totalSeconds % 3600) / 60;
        long s = totalSeconds % 60;
        if (h > 0) return String.format("%d:%02d:%02d", h, m, s);
        return String.format("%d:%02d", m, s);
    }

    private static String formatFileSize(long bytes) {
        if (bytes >= 1024 * 1024) return String.format("%.1f MB", bytes / (1024.0 * 1024.0));
        return String.format("%d KB", bytes / 1024);
    }

    /**
     * Uses yt-dlp --flat-playlist to fetch all video URLs from a YouTube playlist without downloading audio.
     * Fast operation (~1-5s depending on playlist size).
     */
    public static List<String> getYouTubePlaylistUrls(String playlistUrl) throws IOException, InterruptedException {
        List<String> urls = new ArrayList<>();
        ProcessBuilder pb = new ProcessBuilder(
                "yt-dlp",
                "--flat-playlist",
                "--print", "webpage_url",
                "--no-warnings",
                playlistUrl
        );
        pb.redirectErrorStream(false);
        Process process = pb.start();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (!line.isBlank()) urls.add(line);
            }
        }
        boolean done = process.waitFor(60, TimeUnit.SECONDS);
        if (!done) process.destroyForcibly();
        return urls;
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

        try {
            String p = ConfigUtils.COOKIE_FILE_PATH;
            if (p != null && !p.isBlank()) {
                File cookieFile = new File(p);
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
