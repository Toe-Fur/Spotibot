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
    private final ExecutorService downloadExecutor = Executors.newCachedThreadPool();
    private TrackSchedulerRegistry trackSchedulerRegistry;
    private static String BOT_TOKEN;
    private static String STATUS;
    private static int defaultVolume = 60;
    private static String nowPlayingFormat;
    private static String queuedFormat;
    private static String skipEmoji;
    private static String stopEmoji;
    private static String queueEmoji;
    private static final String BASE_DOWNLOAD_FOLDER = "downloads/";
    private static final String CONFIG_FILE_NAME = "config.json";

    public final ConcurrentHashMap<Long, LinkedBlockingQueue<String>> serverQueues = new ConcurrentHashMap<>();
    public final ConcurrentHashMap<Long, LinkedBlockingQueue<String>> serverTitles = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Long, String> currentlyPlayingTitles = new ConcurrentHashMap<>();

    public LinkedBlockingQueue<String> getServerTitleQueue(long guildId) {
        return serverTitles.get(guildId);
    }

    public static void main(String[] args) {
        ensureConfigFileExists();
        loadConfig();

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

    private static void ensureConfigFileExists() {
        File configFile = new File(CONFIG_FILE_NAME);

        if (!configFile.exists()) {
            try (FileWriter writer = new FileWriter(configFile)) {
                writer.write("{\n" +
                        "  \"bot_token\": \"YOUR_BOT_TOKEN_HERE\",\n" +
                        "  \"status\": \"Playing music on Discord\",\n" +
                        "  \"default_volume\": 60,\n" +
                        "  \"queue_format\": {\n" +
                        "    \"now_playing\": \"🎶 **Now Playing:** (title)\",\n" +
                        "    \"queued\": \"📍 **{title}**\"\n" +
                        "  },\n" +
                        "  \"emojis\": {\n" +
                        "    \"skip\": \"⏩\",\n" +
                        "    \"stop\": \"⏹️\",\n" +
                        "    \"queue\": \"📝\"\n" +
                        "  }\n" +
                        "}");
                System.out.println("Created default config.json file in the current directory.");
            } catch (IOException e) {
                System.err.println("Failed to create config.json: " + e.getMessage());
            }
        } else {
            System.out.println("Config file already exists. Skipping creation.");
        }
    }

    private static void loadConfig() {
        try {
            File configFile = new File(CONFIG_FILE_NAME);

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

            downloadExecutor.submit(() -> {
                try {
                    if (isPlaylist(input)) {
                        handlePlaylist(input, guild, messageChannel, trackScheduler, serverFolder);
                    } else {
                        File downloadedFile = downloadWebmFile(input, serverFolder);
                        if (downloadedFile != null) {
                            playWebmDirectly(downloadedFile.getAbsolutePath(), trackScheduler, messageChannel);
                        } else {
                            messageChannel.sendMessage("Failed to download the file.").queue();
                        }
                    }
                } catch (IOException | InterruptedException e) {
                    messageChannel.sendMessage("Error while processing your request: " + e.getMessage()).queue();
                    logger.error("Error processing request for input: " + input, e);
                }
            });
        } else if (message.equalsIgnoreCase("!skip") || message.equalsIgnoreCase(skipEmoji)) {
            handleSkipCommand(guild, messageChannel, trackScheduler);
        } else if (message.equalsIgnoreCase("!stop") || message.equalsIgnoreCase(stopEmoji)) {
            handleStopCommand(guild, messageChannel, trackScheduler, serverFolder);
        } else if (message.equalsIgnoreCase("!queue") || message.equalsIgnoreCase(queueEmoji)) {
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

    private void handleStopCommand(Guild guild, GuildMessageChannel messageChannel, TrackScheduler trackScheduler, String serverFolder) {
        trackScheduler.clearQueueAndStop();
        guild.getAudioManager().closeAudioConnection();
        clearDownloadsFolder(serverFolder);
        messageChannel.sendMessage(stopEmoji + " Stopped playback and cleared the queue.").queue();
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
        return input.contains("list=");
    }

    private void handlePlaylist(String input, Guild guild, GuildMessageChannel messageChannel, TrackScheduler trackScheduler, String serverFolder) throws IOException, InterruptedException {
        List<String> playlistTitles = getYouTubePlaylistTitles(input);
        for (String song : playlistTitles) {
            File downloadedFile = downloadWebmFile(song, serverFolder);
            if (downloadedFile != null) {
                playWebmDirectly(downloadedFile.getAbsolutePath(), trackScheduler, messageChannel);
            } else {
                messageChannel.sendMessage("Failed to download song from playlist: " + song).queue();
            }
        }
    }

    private void showQueue(MessageReceivedEvent event, TrackScheduler trackScheduler) {
        LinkedBlockingQueue<AudioTrack> playbackQueue = trackScheduler.getQueue();
        StringBuilder queueMessage = new StringBuilder("🎶 **Now Playing and Queue** 🎶\n");

        // Add the currently playing track
        AudioTrack currentTrack = trackScheduler.getCurrentTrack();
        if (currentTrack != null) {
            queueMessage.append("🎵 Now Playing: (Title)").append("\n");
        } else {
            queueMessage.append("🎵 Nothing is currently playing.").append("\n");
        }

        // Add upcoming tracks
        int index = 1;
        for (AudioTrack track : playbackQueue) {
            queueMessage.append("📍 ").append(index++).append(". ").append(track.getInfo().title).append("\n");
        }

        if (playbackQueue.isEmpty()) {
            queueMessage.append("The queue is empty.");
        }

        event.getChannel().sendMessage(queueMessage.toString()).queue();
    }

    private File downloadWebmFile(String input, String serverFolder) throws IOException, InterruptedException {
        String title = getYouTubeTitle(input);
        if (title == null) return null;

        String sanitizedTitle = sanitizeFileName(title);
        String outputFilePath = serverFolder + sanitizedTitle + ".webm";

        File downloadsDir = new File(serverFolder);
        if (!downloadsDir.exists()) {
            downloadsDir.mkdirs();
        }

        String query = input.startsWith("http://") || input.startsWith("https://") ? input : "ytsearch:" + input;

        ProcessBuilder downloadBuilder = new ProcessBuilder("yt-dlp", "-f", "bestaudio", "-o", outputFilePath, query);
        downloadBuilder.redirectErrorStream(true);
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
            logger.error("Download process timed out.");
            return null;
        }

        File downloadedFile = new File(outputFilePath);
        if (!downloadedFile.exists()) {
            logger.error("File not found after download.");
            return null;
        }

        return downloadedFile;
    }

    private void playWebmDirectly(String filePath, TrackScheduler trackScheduler, GuildMessageChannel messageChannel) {
        File file = new File(filePath);
        String fileName = file.getName(); // Extracts the file name from the full path
        String sanitizedTitle = fileName.replaceFirst("\\.webm$", ""); // Removes the file extension for display

        playerManager.loadItem(filePath, new AudioLoadResultHandlerImpl(trackScheduler.getPlayer(), messageChannel, trackScheduler, file, filePath));
        messageChannel.sendMessage(String.format("🎶 **Now Playing:** `%s`", sanitizedTitle)).queue();
    }


    private String getYouTubeTitle(String input) throws IOException, InterruptedException {
        String query = input.startsWith("http://") || input.startsWith("https://") ? input : "ytsearch:" + input;

        ProcessBuilder builder = new ProcessBuilder("yt-dlp", "--get-title", query);
        builder.redirectErrorStream(true);
        Process metadataProcess = builder.start();

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(metadataProcess.getInputStream()))) {
            String title = reader.readLine();
            return title != null ? title.trim() : null;
        }
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
                "`!play <URL or search term>` - Plays a YouTube video. If something is already playing, it will queue the track.\n" +
                "`!skip` or `" + skipEmoji + "` - Skips the currently playing track and plays the next one in the queue.\n" +
                "`!stop` or `" + stopEmoji + "` - Stops playback, clears the queue, and deletes all downloaded tracks.\n" +
                "`!queue` or `" + queueEmoji + "` - Shows the current queue.\n" +
                "`!help` - Shows this list of commands.";
    }
}
