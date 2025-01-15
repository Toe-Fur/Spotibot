package com.example.bot;

import com.sedmelluq.discord.lavaplayer.player.AudioPlayerManager;
import com.sedmelluq.discord.lavaplayer.player.DefaultAudioPlayerManager;
import com.sedmelluq.discord.lavaplayer.source.AudioSourceManagers;
import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.channel.middleman.GuildMessageChannel;
import net.dv8tion.jda.api.entities.channel.concrete.VoiceChannel;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.requests.GatewayIntent;
import net.dv8tion.jda.api.entities.Activity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import com.sedmelluq.discord.lavaplayer.track.AudioTrack;

public class Spotibot extends ListenerAdapter {
    private static final Logger logger = LoggerFactory.getLogger(Spotibot.class);
    private final AudioPlayerManager playerManager = new DefaultAudioPlayerManager();
    private ExecutorService downloadExecutor = Executors.newCachedThreadPool();
    private final BlockingQueue<Runnable> downloadQueue = new LinkedBlockingQueue<>();
    private Future<?> currentDownloadTask;
    private TrackSchedulerRegistry trackSchedulerRegistry;
    private static String BOT_TOKEN;
    private static String STATUS;
    private static int defaultVolume = 60;
    private static String nowPlayingFormat;
    private static String queuedFormat;
    private static String skipEmoji;
    private static String stopEmoji;
    private static String queueEmoji;
    private static final String BASE_DOWNLOAD_FOLDER = "config/downloads/";

    public final ConcurrentHashMap<Long, LinkedBlockingQueue<String>> serverQueues = new ConcurrentHashMap<>();
    public final ConcurrentHashMap<Long, LinkedBlockingQueue<String>> serverTitles = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Long, String> currentlyPlayingTitles = new ConcurrentHashMap<>();

    private static final String CONFIG_FOLDER = "./config/";
    private static final String CONFIG_FILE_PATH = CONFIG_FOLDER + "config.json";
    private static final String COOKIE_FILE_PATH = CONFIG_FOLDER + "cookies.txt";

    public LinkedBlockingQueue<String> getServerTitleQueue(long guildId) {
        return serverTitles.get(guildId);
    }

    public static void main(String[] args) {
        createConfigFolder();
        ensureConfigFileExists();
        loadConfig();

        File cookieFile = new File(COOKIE_FILE_PATH);
        if (cookieFile.exists()) {
            logger.info("Using cookies from: " + cookieFile.getAbsolutePath());
        } else {
            logger.warn("No cookies.txt found in the config folder. Proceeding without cookies.");
        }

        if (BOT_TOKEN == null || BOT_TOKEN.isEmpty()) {
            throw new IllegalArgumentException("Bot token is not set. Please set the bot token in the config file.");
        }

        try {
            JDABuilder.createDefault(BOT_TOKEN)
                .enableIntents(GatewayIntent.MESSAGE_CONTENT)
                .setActivity(Activity.playing(STATUS))
                .addEventListeners(new Spotibot())
                .build();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private static void createConfigFolder() {
        File configFolder = new File(CONFIG_FOLDER);
        if (!configFolder.exists()) {
            boolean created = configFolder.mkdir();
            if (created) {
                logger.info("Created config folder: " + CONFIG_FOLDER);
            } else {
                logger.error("Failed to create config folder: " + CONFIG_FOLDER);
            }
        }
    }

    private static void ensureConfigFileExists() {
        File configFile = new File(CONFIG_FILE_PATH);

        if (!configFile.exists()) {
            try (FileWriter writer = new FileWriter(configFile)) {
                writer.write("{\n" +
                        "  \"bot_token\": \"YOUR_BOT_TOKEN_HERE\",\n" +
                        "  \"status\": \"Playing music on Discord\",\n" +
                        "  \"default_volume\": 60,\n" +
                        "  \"queue_format\": {\n" +
                        "    \"now_playing\": \"🎶 **Now Playing:** {title}\",\n" +
                        "    \"queued\": \"📍 **{title}**\"\n" +
                        "  },\n" +
                        "  \"emojis\": {\n" +
                        "    \"skip\": \"⏩\",\n" +
                        "    \"stop\": \"⏹️\",\n" +
                        "    \"queue\": \"📝\"\n" +
                        "  }\n" +
                        "}");
                logger.info("Created default config.json file in the config folder.");
            } catch (IOException e) {
                logger.error("Failed to create config.json: " + e.getMessage(), e);
            }
        }
    }

    private static void loadConfig() {
        try {
            File configFile = new File(CONFIG_FILE_PATH);

            if (!configFile.exists()) {
                logger.error("Config file not found. Please create config.json and provide the necessary configuration.");
                System.exit(1);
            }

            ObjectMapper mapper = new ObjectMapper();
            JsonNode config = mapper.readTree(configFile);

            BOT_TOKEN = config.get("bot_token").asText();
            STATUS = config.get("status").asText();
            defaultVolume = config.get("default_volume").asInt(60);
            nowPlayingFormat = config.get("queue_format").get("now_playing").asText();
            queuedFormat = config.get("queue_format").get("queued").asText();
            skipEmoji = config.get("emojis").get("skip").asText();
            stopEmoji = config.get("emojis").get("stop").asText();
            queueEmoji = config.get("emojis").get("queue").asText();

            if (BOT_TOKEN == null || BOT_TOKEN.isEmpty()) {
                logger.error("Bot token is missing in the config file.");
                System.exit(1);
            }

            logger.info("Loaded bot token successfully. Default volume set to: " + defaultVolume);
        } catch (IOException e) {
            logger.error("Failed to load config file: " + e.getMessage(), e);
            System.exit(1);
        }
    }

    public Spotibot() {
        AudioSourceManagers.registerRemoteSources(playerManager);
        AudioSourceManagers.registerLocalSource(playerManager);
        trackSchedulerRegistry = new TrackSchedulerRegistry(playerManager);
        startDownloadQueueProcessor();
    }

    @Override
    public void onMessageReceived(MessageReceivedEvent event) {
        String message = event.getMessage().getContentRaw();
        GuildMessageChannel messageChannel = event.getChannel().asGuildMessageChannel();
        Guild guild = event.getGuild();
        TrackScheduler trackScheduler = trackSchedulerRegistry.getOrCreate(guild, this, playerManager.createPlayer());

        trackScheduler.getPlayer().setVolume(defaultVolume);
        String serverFolder = BASE_DOWNLOAD_FOLDER + guild.getId() + "/";

        if (message.startsWith("!play ")) {
            String input = message.replace("!play ", "").trim();
            VoiceChannel voiceChannel = (VoiceChannel) event.getMember().getVoiceState().getChannel();

            if (voiceChannel == null) {
                messageChannel.sendMessage("You must be in a voice channel to use this command.").queue();
                return;
            }

            guild.getAudioManager().setSendingHandler(new AudioPlayerSendHandler(trackScheduler.getPlayer()));
            guild.getAudioManager().openAudioConnection(voiceChannel);

            if (isPlaylist(input)) {
                downloadQueue.offer(() -> {
                    try {
                        downloadPlaylist(input, trackScheduler, messageChannel, guild); // Handle playlist
                    } catch (IOException | InterruptedException e) {
                        messageChannel.sendMessage("Error downloading playlist: " + e.getMessage()).queue();
                        logger.error("Error downloading playlist: " + input, e);
                    }
                });
            } else {
                downloadQueue.offer(() -> {
                    try {
                        String query = "ytsearch:" + input;
                        String sanitizedSongName = sanitizeFileName(input); // Sanitize the input
                        String outputFilePath = serverFolder + sanitizedSongName + ".webm";
                        downloadAndQueueSong(query, outputFilePath, trackScheduler, messageChannel, guild); // Handle single song
                    } catch (IOException | InterruptedException e) {
                        messageChannel.sendMessage("Error downloading song: " + e.getMessage()).queue();
                        logger.error("Error downloading song: " + input, e);
                    }
                });
            }
        } else if (message.equalsIgnoreCase("!skip")) {
            handleSkipCommand(guild, messageChannel, trackScheduler);
        } else if (message.equalsIgnoreCase("!stop")) {
            handleStopCommand(guild, messageChannel, trackScheduler, serverFolder);
        } else if (message.equalsIgnoreCase("!queue")) {
            showQueue(event, trackScheduler);
        } else if (message.equalsIgnoreCase("!help")) {
            messageChannel.sendMessage(getHelpMessage()).queue();
        }
    }

    private void handleSkipCommand(Guild guild, GuildMessageChannel messageChannel, TrackScheduler trackScheduler) {
        if (trackScheduler.getPlayer().getPlayingTrack() != null) {
            trackScheduler.nextTrack();
            messageChannel.sendMessage(skipEmoji + " Skipped to the next track.").queue();
        } else {
            messageChannel.sendMessage("No track is currently playing to skip.").queue();
        }
    }

    private void startDownloadQueueProcessor() {
        downloadExecutor.submit(() -> {
            // Line: Replace while(true) in startDownloadQueueProcessor
            while (!Thread.currentThread().isInterrupted()) {
                try {
                    Runnable downloadTask = downloadQueue.take();
                    currentDownloadTask = downloadExecutor.submit(downloadTask);
                    currentDownloadTask.get();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    logger.info("Download queue processor interrupted; shutting down...");
                    break;
                } catch (Exception e) {
                    logger.error("Error processing download task", e);
                }
            }
        });
    }

    private void handleStopCommand(Guild guild, GuildMessageChannel messageChannel, TrackScheduler trackScheduler, String serverFolder) {
        logger.info("Stopping bot and clearing all states...");

        // Clear the queue and stop playback
        trackScheduler.clearQueueAndStop();

        // Close the audio connection
        guild.getAudioManager().closeAudioConnection();

        // Cancel the current download task if any
        if (currentDownloadTask != null && !currentDownloadTask.isDone()) {
            currentDownloadTask.cancel(true);
            try {
                currentDownloadTask.get(); // Wait for cancellation
            } catch (CancellationException | InterruptedException | ExecutionException e) {
                logger.info("Handled cancellation exception: " + e.getMessage());
            }
            currentDownloadTask = null;
        }

        // Interrupt all queued downloads
        downloadExecutor.shutdownNow(); // Interrupt running tasks
        try {
            if (!downloadExecutor.awaitTermination(10, TimeUnit.SECONDS)) {
                logger.warn("Executor did not terminate in time; forcing shutdown...");
                downloadExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            logger.error("Executor shutdown interrupted", e);
        }

        // Restart executor service after stopping
        downloadExecutor = Executors.newCachedThreadPool();
        startDownloadQueueProcessor();

        // Clear the download queue
        downloadQueue.clear();

        // Clear the downloads folder
        clearDownloadsFolder(serverFolder);

        // Send a message to confirm the bot state has been reset
        messageChannel.sendMessage(stopEmoji + " Stopped playback and reset the bot state.").queue();
    }

    private List<String> getYouTubePlaylistTitles(String playlistUrl) throws IOException, InterruptedException {
        List<String> titles = new ArrayList<>();
        ProcessBuilder builder = new ProcessBuilder("yt-dlp", "--flat-playlist", "--get-title", playlistUrl);
        builder.redirectErrorStream(true);
        Process process = builder.start();

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                titles.add(line);
            }
        }

        process.waitFor();
        return titles;
    }

    private boolean isPlaylist(String input) {
        return input.contains("list="); // Detect playlist URLs
    }

    private static final String DEFAULT_COOKIE_FILE = new File(System.getProperty("user.dir"), "cookies.txt").getAbsolutePath();

    private void downloadPlaylist(String playlistUrl, TrackScheduler trackScheduler, GuildMessageChannel messageChannel, Guild guild) throws IOException, InterruptedException {
        ProcessBuilder downloadBuilder = new ProcessBuilder(
            "yt-dlp",
            "-4",
            "-f", "bestaudio",
            "--yes-playlist",
            "--cookies", COOKIE_FILE_PATH,
            "-o", BASE_DOWNLOAD_FOLDER + guild.getId() + "/%(title)s.%(ext)s",
            playlistUrl
        );

        Process downloadProcess = downloadBuilder.start();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(downloadProcess.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                logger.info(line);
            }
        }

        boolean completedInTime = downloadProcess.waitFor(300, TimeUnit.SECONDS); // Extend time for playlists

        if (!completedInTime || downloadProcess.exitValue() != 0) {
            downloadProcess.destroyForcibly();
            logger.error("Failed to download playlist: " + playlistUrl);
            messageChannel.sendMessage("Failed to download playlist: " + playlistUrl).queue();

            // Leave voice channel if the queue is empty
            if (trackScheduler.isQueueEmpty()) {
                messageChannel.sendMessage("Queue is empty and download failed. Leaving the voice channel.").queue();
                leaveVoiceChannel(guild);
            }
            return;
        }

        messageChannel.sendMessage("Playlist downloaded successfully and added to the queue!").queue();
    }

    private void downloadAndQueueSong(String query, String outputFilePath, TrackScheduler trackScheduler, GuildMessageChannel messageChannel, Guild guild) throws IOException, InterruptedException {
        File cookieFile = new File(COOKIE_FILE_PATH);

        if (!cookieFile.exists()) {
            logger.warn("No cookies.txt found in the config folder. Proceeding without cookies.");
            messageChannel.sendMessage("No cookies found. YouTube may restrict access to certain videos.").queue();
        }

        ProcessBuilder downloadBuilder = new ProcessBuilder(
            "yt-dlp",
            "-4",
            "-f", "bestaudio",
            "--no-playlist",
            "--cookies", cookieFile.exists() ? cookieFile.getAbsolutePath() : null,
            "-o", outputFilePath,
            query
        );

        Process downloadProcess = downloadBuilder.start();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(downloadProcess.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                logger.info(line);
            }
        }

        boolean completedInTime = downloadProcess.waitFor(120, TimeUnit.SECONDS);

        if (!completedInTime || downloadProcess.exitValue() != 0) {
            downloadProcess.destroyForcibly();
            logger.error("Download process failed or timed out for query: " + query);
            messageChannel.sendMessage("Failed to download song: " + query).queue();

            if (trackScheduler.isQueueEmpty()) {
                messageChannel.sendMessage("Queue is empty and download failed. Leaving the voice channel.").queue();
                leaveVoiceChannel(guild);
            }
            return;
        }

        File downloadedFile = new File(outputFilePath);
        if (downloadedFile.exists()) {
            trackScheduler.queueSong(downloadedFile, query);

            // If no track is currently playing, start playing immediately
            if (trackScheduler.getPlayer().getPlayingTrack() == null) {
                trackScheduler.nextTrack();
            }

            String displayTitle = query.replace("ytsearch:", "").trim();
            messageChannel.sendMessage(String.format("📍 **Queued:** `%s`", displayTitle)).queue();
        } else {
            messageChannel.sendMessage("Failed to download song: " + query).queue();

            if (trackScheduler.isQueueEmpty()) {
                messageChannel.sendMessage("Queue is empty and download failed. Leaving the voice channel.").queue();
                leaveVoiceChannel(guild);
            }
        }
    }

    private void leaveVoiceChannel(Guild guild) {
        if (guild != null && guild.getAudioManager().isConnected()) {
            guild.getAudioManager().closeAudioConnection();
            logger.info("Disconnected from voice channel for guild: " + guild.getId());
        }
    }

    private void showQueue(MessageReceivedEvent event, TrackScheduler trackScheduler) {
        LinkedBlockingQueue<AudioTrack> playbackQueue = trackScheduler.getQueue();
        StringBuilder queueMessage = new StringBuilder("🎶 **Now Playing and Queue** 🎶\n");

        AudioTrack currentTrack = trackScheduler.getCurrentTrack();
        if (currentTrack != null) {
            String title = trackScheduler.getTitle(currentTrack.getIdentifier()).replace("ytsearch:", "").trim();
            queueMessage.append("🎵 Now Playing: ").append(title != null ? title : "Unknown Title").append("\n");
        }

        int index = 1;
        for (AudioTrack track : playbackQueue) {
            String title = trackScheduler.getTitle(track.getIdentifier()).replace("ytsearch:", "").trim();
            queueMessage.append("📍 ").append(index++).append(". ").append(title != null ? title : "Unknown Title").append("\n");
        }

        if (playbackQueue.isEmpty()) {
            queueMessage.append("The queue is empty.");
        }

        event.getChannel().sendMessage(queueMessage.toString()).queue();
    }

    private String sanitizeFileName(String input) {
        return input.replaceAll("[^a-zA-Z0-9_-]", "_");
    }

    private void clearDownloadsFolder(String serverFolder) {
        File downloadFolder = new File(serverFolder);
        if (downloadFolder.exists() && downloadFolder.isDirectory()) {
            for (File file : downloadFolder.listFiles()) {
                if (file.isFile()) {
                    file.delete();
                }
            }
        }
    }

    private String getHelpMessage() {
        return "**Spotibot Commands**:\n" +
               "`!play <URL or search term>` - Plays a YouTube video.\n" +
               "`!skip` - Skips the current track.\n" +
               "`!stop` - Stops playback and resets the bot.\n" +
               "`!queue` - Shows the current queue.\n" +
               "`!help` - Shows this list of commands.\n" +
               "\nNote: Place your `cookies.txt` file in the `config` folder for YouTube authentication.";
    }
}