package com.example.bot;

import com.sedmelluq.discord.lavaplayer.player.AudioPlayerManager;
import com.sedmelluq.discord.lavaplayer.player.DefaultAudioPlayerManager;
import com.sedmelluq.discord.lavaplayer.source.AudioSourceManagers;
import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.channel.middleman.GuildMessageChannel;
import net.dv8tion.jda.api.entities.channel.concrete.VoiceChannel;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;
import net.dv8tion.jda.api.events.message.react.MessageReactionAddEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.requests.GatewayIntent;
import net.dv8tion.jda.api.entities.Activity;
import net.dv8tion.jda.api.entities.emoji.Emoji;
import net.dv8tion.jda.api.entities.Message;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import org.json.JSONArray;
import org.json.JSONObject;

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
    private static String skipEmoji;
    private static String stopEmoji;
    private static final String BASE_DOWNLOAD_FOLDER = "config/downloads/";
    private static final int QUEUE_PAGE_SIZE = 5;

    public final ConcurrentHashMap<Long, LinkedBlockingQueue<String>> serverQueues = new ConcurrentHashMap<>();
    public final ConcurrentHashMap<Long, LinkedBlockingQueue<String>> serverTitles = new ConcurrentHashMap<>();
    private final Map<Long, Integer> queuePageMap = new ConcurrentHashMap<>();
    private final Map<Long, Message> queueMessages = new ConcurrentHashMap<>();

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
            skipEmoji = config.get("emojis").get("skip").asText();
            stopEmoji = config.get("emojis").get("stop").asText();

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

            try {
                VoiceChannel voiceChannel = (VoiceChannel) event.getMember().getVoiceState().getChannel();
                if (voiceChannel == null) {
                    messageChannel.sendMessage("You must be in a voice channel to use this command.").queue();
                    return;
                }

                guild.getAudioManager().setSendingHandler(new AudioPlayerSendHandler(trackScheduler.getPlayer()));
                guild.getAudioManager().openAudioConnection(voiceChannel);

                if (input.contains("spotify.com/track")) {
                    String trackId = extractSpotifyId(input);
                    String trackTitle = SpotifyUtils.getTrackTitle(trackId);
                    queueAndPlay(trackTitle, trackScheduler, messageChannel, guild, serverFolder);
                } else if (input.contains("spotify.com/playlist")) {
                    String playlistId = extractSpotifyId(input);
                    List<String> trackTitles = SpotifyUtils.getPlaylistTracks(playlistId);

                    for (String trackTitle : trackTitles) {
                        queueAndPlay(trackTitle, trackScheduler, messageChannel, guild, serverFolder);
                    }
                } else {
                    if (isPlaylist(input)) {
                        List<String> playlistTitles;
                        try {
                            playlistTitles = getYouTubePlaylistTitles(input);
                            for (String trackTitle : playlistTitles) {
                                queueAndPlay(trackTitle, trackScheduler, messageChannel, guild, serverFolder);
                            }
                        } catch (Exception e) {
                            messageChannel.sendMessage("Failed to retrieve playlist: " + e.getMessage()).queue();
                        }
                    } else {
                        queueAndPlay(input, trackScheduler, messageChannel, guild, serverFolder);
                    }
                }
            } catch (Exception e) {
                messageChannel.sendMessage("An error occurred: " + e.getMessage()).queue();
            }
        } else if (message.equalsIgnoreCase("!skip")) {
            handleSkipCommand(guild, messageChannel, trackScheduler);
        } else if (message.equalsIgnoreCase("!stop")) {
            handleStopCommand(guild, messageChannel, trackScheduler, serverFolder);
        } else if (message.equalsIgnoreCase("!queue")) {
            showQueue(event, trackScheduler, 0);
        } else if (message.equalsIgnoreCase("!help")) {
            messageChannel.sendMessage(getHelpMessage()).queue();
        }
    }

    @Override
    public void onMessageReactionAdd(MessageReactionAddEvent event) {
        if (event.getUser().isBot()) return;

        String emoji = event.getEmoji().getName();
        Guild guild = event.getGuild();
        TrackScheduler trackScheduler = trackSchedulerRegistry.getOrCreate(guild, this, playerManager.createPlayer());

        int currentPage = queuePageMap.getOrDefault(guild.getIdLong(), 0);

        if (emoji.equals("⬅️")) {
            if (currentPage > 0) {
                showQueue(event, trackScheduler, currentPage - 1);
            }
        } else if (emoji.equals("➡️")) {
            showQueue(event, trackScheduler, currentPage + 1);
        }

        event.getReaction().removeReaction(event.getUser()).queue();
    }

    private void queueAndPlay(String trackTitle, TrackScheduler trackScheduler, GuildMessageChannel messageChannel, Guild guild, String serverFolder) {
        downloadQueue.offer(() -> {
            try {
                String query = "ytsearch:" + trackTitle;
                String sanitizedSongName = sanitizeFileName(trackTitle);
                String outputFilePath = serverFolder + sanitizedSongName + ".webm";

                // Download the track
                File downloadedFile = new File(outputFilePath);
                if (!downloadedFile.exists()) {
                    downloadAndQueueSong(query, outputFilePath, trackScheduler, messageChannel, guild);
                }

                // Queue the track only if the file exists and hasn't been queued already
                if (downloadedFile.exists()) {
                    trackScheduler.queueSong(downloadedFile, trackTitle);
                    updateQueueMessage(guild, trackScheduler, 0);
                } else {
                    messageChannel.sendMessage("Failed to download or queue the track: " + trackTitle).queue();
                }
            } catch (IOException | InterruptedException e) {
                messageChannel.sendMessage("Error downloading or queuing track: " + e.getMessage()).queue();
                logger.error("Error processing track: " + trackTitle, e);
            }
        });
    }

    private String extractSpotifyId(String url) {
        if (url.contains("/track/")) {
            return url.split("/track/")[1].split("\\?")[0];
        } else if (url.contains("/playlist/")) {
            String playlistId = url.split("/playlist/")[1].split("\\?")[0];
            logger.info("Extracted Spotify Playlist ID: " + playlistId);
            return playlistId;
        }
        return null;
    }

    private void handleSkipCommand(Guild guild, GuildMessageChannel messageChannel, TrackScheduler trackScheduler) {
        if (trackScheduler.getPlayer().getPlayingTrack() != null) {
            trackScheduler.nextTrack();
            messageChannel.sendMessage(skipEmoji + " Skipped to the next track.").queue();
            // Check if the queue is empty
            if (trackScheduler.isQueueEmpty()) {
                String serverFolder = BASE_DOWNLOAD_FOLDER + guild.getId() + "/"; // Use guild to construct serverFolder
                handleStopCommand(guild, messageChannel, trackScheduler, serverFolder); // Pass guild
            }
        } else {
            messageChannel.sendMessage("No track is currently playing to skip.").queue();
        }
    }

    private void startDownloadQueueProcessor() {
        downloadExecutor.submit(() -> {
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
        
        // Clear the downloads folder
        clearDownloadsFolder(serverFolder);
        
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

        trackSchedulerRegistry.reset();
        
        // Send a message to confirm the bot state has been reset
        messageChannel.sendMessage(stopEmoji + " Stopped playback and reset the bot state. You can now add new songs.").queue();
    }

    private List<String> getYouTubePlaylistTitles(String playlistUrl) throws IOException {
        List<String> titles = new ArrayList<>();
        // Use yt-dlp to fetch playlist details
        ProcessBuilder pb = new ProcessBuilder("yt-dlp", "--flat-playlist", "-J", playlistUrl);
        Process process = pb.start();
        BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
        StringBuilder jsonOutput = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null) {
            jsonOutput.append(line);
        }
        reader.close();

        // Parse the JSON output
        JSONObject json = new JSONObject(jsonOutput.toString());
        JSONArray entries = json.getJSONArray("entries");
        for (int i = 0; i < entries.length(); i++) {
            JSONObject entry = entries.getJSONObject(i);
            titles.add(entry.getString("title"));
        }

        return titles;
    }

    private boolean isPlaylist(String input) {
        return input.contains("list=");
    }

    private void downloadAndQueueSong(String query, String outputFilePath, TrackScheduler trackScheduler, GuildMessageChannel messageChannel, Guild guild) throws IOException, InterruptedException {
        File cookieFile = new File(COOKIE_FILE_PATH);

        if (!cookieFile.exists()) {
            logger.warn("No cookies.txt found in the config folder. Proceeding without cookies.");
            messageChannel.sendMessage("No cookies found. YouTube may restrict access to certain videos.").queue();
        }

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
            logger.error("Download process timed out for query: " + query);
            messageChannel.sendMessage("Failed to download song: " + query).queue();
        }

        File downloadedFile = new File(outputFilePath);
        if (!downloadedFile.exists()) {
            messageChannel.sendMessage("Failed to download song: " + query).queue();
        }
    }

    private void showQueue(MessageReceivedEvent event, TrackScheduler trackScheduler, int page) {
        LinkedBlockingQueue<AudioTrack> playbackQueue = trackScheduler.getQueue();
        StringBuilder queueMessage = new StringBuilder("🎶 **Now Playing and Queue** 🎶\n");

        AudioTrack currentTrack = trackScheduler.getCurrentTrack();
        if (currentTrack != null) {
            String title = trackScheduler.getTitle(currentTrack.getIdentifier()).replace("ytsearch:", "").trim();
            queueMessage.append("🎵 Now Playing: ").append(title != null ? title : "Unknown Title").append("\n");
        }

        int startIndex = page * QUEUE_PAGE_SIZE;
        int endIndex = Math.min(startIndex + QUEUE_PAGE_SIZE, playbackQueue.size());

        List<AudioTrack> trackList = new ArrayList<>(playbackQueue);
        for (int i = startIndex; i < endIndex; i++) {
            AudioTrack track = trackList.get(i);
            String title = trackScheduler.getTitle(track.getIdentifier()).replace("ytsearch:", "").trim();
            queueMessage.append("📍 ").append(i + 1).append(". ").append(title != null ? title : "Unknown Title").append("\n");
        }

        queuePageMap.put(event.getGuild().getIdLong(), page);

        event.getChannel().sendMessage(queueMessage.toString()).queue(message -> {
            if (playbackQueue.size() > QUEUE_PAGE_SIZE) {
                message.addReaction(Emoji.fromUnicode("⬅️")).queue();
                message.addReaction(Emoji.fromUnicode("➡️")).queue();
            }
        });
    }

    private void showQueue(MessageReactionAddEvent event, TrackScheduler trackScheduler, int page) {
        LinkedBlockingQueue<AudioTrack> playbackQueue = trackScheduler.getQueue();
        StringBuilder queueMessage = new StringBuilder("🎶 **Now Playing and Queue** 🎶\n");

        AudioTrack currentTrack = trackScheduler.getCurrentTrack();
        if (currentTrack != null) {
            String title = trackScheduler.getTitle(currentTrack.getIdentifier()).replace("ytsearch:", "").trim();
            queueMessage.append("🎵 Now Playing: ").append(title != null ? title : "Unknown Title").append("\n");
        }

        int startIndex = page * QUEUE_PAGE_SIZE;
        int endIndex = Math.min(startIndex + QUEUE_PAGE_SIZE, playbackQueue.size());

        List<AudioTrack> trackList = new ArrayList<>(playbackQueue);
        for (int i = startIndex; i < endIndex; i++) {
            AudioTrack track = trackList.get(i);
            String title = trackScheduler.getTitle(track.getIdentifier()).replace("ytsearch:", "").trim();
            queueMessage.append("📍 ").append(i + 1).append(". ").append(title != null ? title : "Unknown Title").append("\n");
        }

        queuePageMap.put(event.getGuild().getIdLong(), page);

        event.getChannel().sendMessage(queueMessage.toString()).queue(message -> {
            if (playbackQueue.size() > QUEUE_PAGE_SIZE) {
                message.addReaction(Emoji.fromUnicode("⬅️")).queue();
                message.addReaction(Emoji.fromUnicode("➡️")).queue();
            }
        });
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

    private void updateQueueMessage(Guild guild, TrackScheduler trackScheduler, int page) {
        LinkedBlockingQueue<AudioTrack> playbackQueue = trackScheduler.getQueue();
        StringBuilder queueMessage = new StringBuilder("🎶 **Now Playing and Queue** 🎶\n");

        AudioTrack currentTrack = trackScheduler.getCurrentTrack();
        if (currentTrack != null) {
            String title = trackScheduler.getTitle(currentTrack.getIdentifier()).replace("ytsearch:", "").trim();
            queueMessage.append("🎵 Now Playing: ").append(title != null ? title : "Unknown Title").append("\n");
        }

        int startIndex = page * QUEUE_PAGE_SIZE;
        int endIndex = Math.min(startIndex + QUEUE_PAGE_SIZE, playbackQueue.size());

        List<AudioTrack> trackList = new ArrayList<>(playbackQueue);
        for (int i = startIndex; i < endIndex; i++) {
            AudioTrack track = trackList.get(i);
            String title = trackScheduler.getTitle(track.getIdentifier()).replace("ytsearch:", "").trim();
            queueMessage.append("📍 ").append(i + 1).append(". ").append(title != null ? title : "Unknown Title").append("\n");
        }

        queuePageMap.put(guild.getIdLong(), page);

        Message queueMessageObj = queueMessages.get(guild.getIdLong());
        if (queueMessageObj == null) {
            guild.getDefaultChannel().sendMessage(queueMessage.toString()).queue(message -> {
                queueMessages.put(guild.getIdLong(), message);
                if (playbackQueue.size() > QUEUE_PAGE_SIZE) {
                    message.addReaction(Emoji.fromUnicode("⬅️")).queue();
                    message.addReaction(Emoji.fromUnicode("➡️")).queue();
                }
            });
        } else {
            queueMessageObj.editMessage(queueMessage.toString()).queue();
        }
    }
}