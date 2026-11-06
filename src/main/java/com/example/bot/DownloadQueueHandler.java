package com.example.bot;

import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.channel.middleman.GuildMessageChannel;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

/**
 * Loads requested songs into a guild's queue, in order, one at a time.
 *
 * Two modes:
 *  - Streaming (STREAM_SERVER_URL set): songs stream from a ytmusic-stream-server and start as soon as the
 *    server has the first bytes; the next 5 songs are pre-warmed while one loads.
 *  - Classic (default): each song is downloaded with yt-dlp, then played from disk.
 * Progress shows on the single live queue panel instead of a message per song.
 */
public class DownloadQueueHandler {
    private static final Logger logger = Logger.getLogger(DownloadQueueHandler.class.getName());

    public static void queueAndPlay(String input, TrackScheduler trackScheduler, GuildMessageChannel messageChannel, Guild guild, String serverFolder, BlockingQueue<Runnable> downloadQueue) {
        if (input == null || input.isEmpty()) {
            PlayerControls.flash(messageChannel, "Invalid track title.");
            return;
        }
        queueAll(List.of(input), null, trackScheduler, messageChannel, guild, downloadQueue);
    }

    public static void queueAll(List<String> inputs, String notice, TrackScheduler trackScheduler,
                                GuildMessageChannel messageChannel, Guild guild, BlockingQueue<Runnable> downloadQueue) {
        queueAll(inputs, notice, false, trackScheduler, messageChannel, guild, downloadQueue);
    }

    public static void queueAll(List<String> inputs, String notice, boolean next, TrackScheduler trackScheduler,
                                GuildMessageChannel messageChannel, Guild guild, BlockingQueue<Runnable> downloadQueue) {
        queueAll(inputs, notice, next, trackScheduler, messageChannel, guild, downloadQueue, null);
    }

    public static void queueAll(List<String> inputs, String notice, boolean next, TrackScheduler trackScheduler,
                                GuildMessageChannel messageChannel, Guild guild, BlockingQueue<Runnable> downloadQueue,
                                net.dv8tion.jda.api.entities.Member requester) {
        List<StreamTrack> tracks = new ArrayList<>();
        for (String in : inputs) {
            if (in != null && !in.isBlank()) tracks.add(StreamTrack.parse(in));
        }
        if (requester != null) {
            for (StreamTrack t : tracks) {
                t.requesterId = requester.getIdLong();
                t.requesterName = requester.getEffectiveName();
            }
        }
        queueTracks(tracks, notice, next, trackScheduler, messageChannel, guild, downloadQueue);
    }

    public static void queueTracks(List<StreamTrack> tracks, String notice, boolean next, TrackScheduler trackScheduler,
                                   GuildMessageChannel messageChannel, Guild guild, BlockingQueue<Runnable> downloadQueue) {
        if (tracks.isEmpty()) return;
        QueuePanel panel = QueuePanel.of(guild.getIdLong());
        if (panel.loading.get() == 0 && trackScheduler.isQueueEmpty()) panel.reset(); // fresh session: clear old counters
        panel.loading.addAndGet(tracks.size());
        String n = notice != null ? notice
                : tracks.size() == 1 ? (next ? "Up next: " : "Added: ") + tracks.get(0).display : "Added " + tracks.size() + " songs";
        panel.attach(messageChannel, trackScheduler, n);
        boolean streaming = StreamTrack.enabled();
        if (streaming) StreamTrack.prefetch(tracks.subList(0, Math.min(5, tracks.size())));
        String serverFolder = PlayerControls.serverFolder(guild);
        // "next" batches keep their own order at the front of the queue: 1st at 0, 2nd at 1, ...
        final java.util.concurrent.atomic.AtomicInteger nextPos = new java.util.concurrent.atomic.AtomicInteger();
        final java.util.function.IntSupplier position = next ? nextPos::getAndIncrement : null;

        for (int i = 0; i < tracks.size(); i++) {
            final StreamTrack t = tracks.get(i);
            final List<StreamTrack> ahead = tracks.subList(Math.min(i + 1, tracks.size()), Math.min(i + 6, tracks.size()));
            trackScheduler.incrementPendingDownloads();
            downloadQueue.offer(() -> {
                boolean ok = false;
                try {
                    t.fetchTitleIfLink();
                    long t0 = System.currentTimeMillis();
                    if (streaming) {
                        if (!ahead.isEmpty()) StreamTrack.prefetch(ahead);
                        ok = t.warm() && trackScheduler.load(t.streamUrl(), t, position);
                    } else {
                        File f = download(t, serverFolder);
                        ok = f != null && trackScheduler.load(f.getAbsolutePath(), t, position);
                    }
                    logger.info((ok ? "Ready in " : "Failed after ") + (System.currentTimeMillis() - t0) + " ms: " + t.display);
                } catch (Exception e) {
                    logger.warning("Error loading " + t.display + ": " + e);
                } finally {
                    panel.loading.decrementAndGet();
                    if (!ok) panel.failed.incrementAndGet();
                    trackScheduler.decrementPendingDownloads();
                    panel.update();
                    if (!ok) trackScheduler.leaveIfIdle();
                }
            });
        }
    }

    /** Classic mode: download one song with yt-dlp (cookies from config/cookies.txt if present). */
    private static File download(StreamTrack t, String serverFolder) throws IOException, InterruptedException {
        new File(serverFolder).mkdirs();
        String out = serverFolder + sanitizeFileName(t.videoId != null ? t.videoId : t.display) + ".webm";
        File f = new File(out);
        if (f.exists() && f.length() > 0) return f;
        if (downloadSong(t.legacyQuery(), out, null)) return f;
        // YouTube now 403s audio-only streams without a PO token; these clients still serve a playable format.
        logger.info("Retrying with fallback YouTube clients: " + t.display);
        return downloadSong(t.legacyQuery(), out, "youtube:player_client=web_music,mweb") ? f : null;
    }

    /**
     * Uses yt-dlp --flat-playlist to fetch all video URLs from a YouTube playlist without downloading audio.
     * Fast operation (~1-5s depending on playlist size).
     */
    public static List<String> getYouTubePlaylistUrls(String playlistUrl) throws IOException, InterruptedException {
        List<String> urls = new ArrayList<>();
        ProcessBuilder pb = new ProcessBuilder(
                Tools.ytdlp(),
                "--flat-playlist",
                "--print", "webpage_url",
                "--no-warnings",
                playlistUrl
        );
        pb.redirectErrorStream(false);
        Process process = Tools.withPath(pb).start();
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
            File[] files = downloadFolder.listFiles();
            if (files == null) return;
            for (File file : files) {
                if (file.isFile()) {
                    file.delete();
                }
            }
        }
    }

    private static boolean downloadSong(String query, String outputFilePath, String extractorArgs) throws IOException, InterruptedException {
        // Resolve cookie path (exporter writes to /app/config/cookies.txt)
        String cookiePathToUse = null;
        try {
            String p = ConfigUtils.COOKIE_FILE_PATH;
            if (p != null && !p.isBlank()) {
                File cookieFile = new File(p);
                if (cookieFile.exists() && cookieFile.isFile() && cookieFile.length() > 0) {
                    // Copy to temp so yt-dlp doesn't read a file while exporter might be rewriting it
                    File tmp = File.createTempFile("spotibot-cookies-", ".txt");
                    java.nio.file.Files.copy(cookieFile.toPath(), tmp.toPath(),
                            java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                    tmp.deleteOnExit();
                    cookiePathToUse = tmp.getAbsolutePath();
                }
            }
        } catch (Exception e) {
            logger.warning("Failed to copy cookies file; continuing without cookies. " + e.getMessage());
        }

        List<String> cmd = new ArrayList<>(List.of(Tools.ytdlp(), "-4", "--js-runtimes", "deno", "-f", "bestaudio/best", "--no-playlist"));
        if (cookiePathToUse != null) cmd.addAll(List.of("--cookies", cookiePathToUse));
        if (extractorArgs != null) cmd.addAll(List.of("--extractor-args", extractorArgs));
        cmd.addAll(List.of("-o", outputFilePath, query));

        ProcessBuilder downloadBuilder = Tools.withPath(new ProcessBuilder(cmd));
        downloadBuilder.redirectErrorStream(true);
        logger.info("Running: " + String.join(" ", cmd));

        Process downloadProcess = downloadBuilder.start();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(downloadProcess.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                logger.info(line);
            }
        } finally {
            if (cookiePathToUse != null) new File(cookiePathToUse).delete();
        }

        if (!downloadProcess.waitFor(180, TimeUnit.SECONDS)) {
            downloadProcess.destroyForcibly();
            logger.severe("Download process timed out for query: " + query);
            return false;
        }
        int exit = downloadProcess.exitValue();
        logger.info("yt-dlp exit code: " + exit);
        return exit == 0 && new File(outputFilePath).exists();
    }

    private static String sanitizeFileName(String input) {
        return input.replaceAll("[^a-zA-Z0-9_-]", "_");
    }
}
