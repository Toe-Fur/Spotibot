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

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.Arrays;
import java.util.Comparator;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ConcurrentHashMap;

import java.util.List;
import java.util.ArrayList;
import java.io.BufferedReader;
import java.io.InputStreamReader;
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
                        "    \"now_playing\": \"🎶 **Now Playing:** {title}\",\n" +
                        "    \"queued\": \"📍 **{title}**\"\n" +
                        "  },\n" +
                        "  \"emojis\": {\n" +
                        "    \"skip\": \"⏩\",\n" +
                        "    \"stop\": \"⏹️\",\n" +
                        "    \"queue\": \"📝\"\n" +
                        "  }\n" +
                        "}\n");
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
        playerManager.registerSourceManager(new InputStreamAudioSourceManager());
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
                        handleSingleSong(input, guild, messageChannel, trackScheduler, serverFolder);
                    }
                } catch (IOException | InterruptedException e) {
                    messageChannel.sendMessage("Error while processing your request: " + e.getMessage()).queue();
                    logger.error("Error processing request for input: " + input, e);
                }
            });
        } else if (message.equalsIgnoreCase("!skip") || message.equalsIgnoreCase(skipEmoji)) {
            handleSkipCommand(guild, messageChannel, trackScheduler);
        } else if (message.equalsIgnoreCase("!stop") || message.equalsIgnoreCase(stopEmoji)) {
            handleStopCommand(guild, messageChannel, trackScheduler);
        } else if (message.equalsIgnoreCase("!queue") || message.equalsIgnoreCase(queueEmoji)) {
            showQueue(event, trackScheduler);
        } else if (message.equalsIgnoreCase("!help")) {
            messageChannel.sendMessage(getHelpMessage()).queue();
        }
    }

    private void handlePlaylist(String input, Guild guild, GuildMessageChannel messageChannel, TrackScheduler trackScheduler, String serverFolder) throws IOException, InterruptedException {
        List<String> playlistTitles = getYouTubePlaylistTitles(input);
        LinkedBlockingQueue<String> queue = serverQueues.computeIfAbsent(guild.getIdLong(), k -> new LinkedBlockingQueue<>());
        LinkedBlockingQueue<String> titleQueue = serverTitles.computeIfAbsent(guild.getIdLong(), k -> new LinkedBlockingQueue<>());

        queue.addAll(playlistTitles);
        titleQueue.addAll(playlistTitles);

        messageChannel.sendMessage("Playlist added to the queue. Songs will play as they are ready.").queue();

        for (String song : playlistTitles) {
            downloadExecutor.submit(() -> {
                try {
                    File downloadedFile = downloadYouTubeAudio(song, serverFolder);
                    if (downloadedFile != null) {
                        trackScheduler.queueSong(downloadedFile, song);
                        messageChannel.sendMessage("Added to the queue: " + song).queue();
                    } else {
                        messageChannel.sendMessage("Failed to download: " + song).queue();
                    }
                } catch (Exception e) {
                    logger.error("Error downloading song: " + song, e);
                }
            });
        }
    }

    private void handleSingleSong(String input, Guild guild, GuildMessageChannel messageChannel, TrackScheduler trackScheduler, String serverFolder) throws IOException, InterruptedException {
        // Get the title of the YouTube video
        String title = getYouTubeTitle(input);
        if (title != null) {
            // Get or create queues for the server
            LinkedBlockingQueue<String> queue = serverQueues.computeIfAbsent(guild.getIdLong(), k -> new LinkedBlockingQueue<>());
            LinkedBlockingQueue<String> titleQueue = serverTitles.computeIfAbsent(guild.getIdLong(), k -> new LinkedBlockingQueue<>());

            // Add the input and title to their respective queues
            queue.offer(input);
            titleQueue.offer(title);

            // Update the currently playing title
            currentlyPlayingTitles.put(guild.getIdLong(), title);

            // Send a formatted message about the queued track
            int index = queue.size();
            String formattedMessage = queuedFormat.replace("{title}", title).replace("{index}", String.valueOf(index));
            messageChannel.sendMessage(formattedMessage).queue();

            // Download the YouTube audio
            File downloadedFile = downloadYouTubeAudio(input, serverFolder);

            // Queue the track or notify of a timeout
            if (downloadedFile != null) {
                trackScheduler.queueSong(downloadedFile, title);
            } else {
                messageChannel.sendMessage("Download timed out.").queue();
            }
        }
    }

    private void handleSkipCommand(Guild guild, GuildMessageChannel messageChannel, TrackScheduler trackScheduler) {
        if (trackScheduler.getPlayer().getPlayingTrack() != null) {
            String currentTrackFileName = trackScheduler.getCurrentTrackFileName();
            if (currentTrackFileName != null) {
                logger.info("Currently playing track file: " + currentTrackFileName);
            }
            trackScheduler.nextTrack();
            messageChannel.sendMessage(skipEmoji + " Skipped to the next track.").queue();

            if (trackScheduler.isQueueEmpty()) {
                messageChannel.sendMessage("No more tracks in the queue. Leaving the voice channel.").queue();

                // Call handleStopCommand
                handleStopCommand(guild, messageChannel, trackScheduler);
            }
        } else {
            messageChannel.sendMessage("No track is currently playing to skip.").queue();

            // Call handleStopCommand
            handleStopCommand(guild, messageChannel, trackScheduler);
        }
    }


    public void handleStopCommand(Guild guild, GuildMessageChannel messageChannel, TrackScheduler trackScheduler) {
        String serverFolder = BASE_DOWNLOAD_FOLDER + guild.getId() + "/";

        guild.getAudioManager().closeAudioConnection();
        trackScheduler.clearQueueAndStop();
        clearDownloadsFolder(serverFolder);

        serverQueues.remove(guild.getIdLong());
        serverTitles.remove(guild.getIdLong());

        messageChannel.sendMessage(stopEmoji + " Stopped playback and cleared the queue.").queue();
    }

    private boolean isPlaylist(String input) {
        return input.contains("list="); // Simple check for YouTube playlist URLs
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

    private void showQueue(MessageReceivedEvent event, TrackScheduler trackScheduler) {
        LinkedBlockingQueue<AudioTrack> playbackQueue = trackScheduler.getQueue();
        StringBuilder queueMessage = new StringBuilder("🎶 **Now Playing and Queue** 🎶\n");

        // Add the currently playing track
        AudioTrack currentTrack = trackScheduler.getCurrentTrack();
        if (currentTrack != null) {
            queueMessage.append("🎵 Now Playing: ").append(currentTrack.getInfo().title).append("\n");
        } else {
            queueMessage.append("🎵 Now Playing: Nothing is currently playing.\n");
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

    private File downloadYouTubeAudio(String input, String serverFolder) throws IOException, InterruptedException {
        String title = getYouTubeTitle(input);
        if (title == null) return null;

        String sanitizedTitle = sanitizeFileName(title);
        String outputFilePath = serverFolder + sanitizedTitle + ".mp3"; // Save as .mp3 file

        File downloadsDir = new File(serverFolder);
        if (!downloadsDir.exists()) {
            downloadsDir.mkdirs();
        }

        String query = input.startsWith("http://") || input.startsWith("https://") ? input : "ytsearch:" + input;

        ProcessBuilder downloadBuilder = new ProcessBuilder(
                "yt-dlp", 
                "-f", "bestaudio", 
                "--audio-format", "mp3", 
                "--no-part",  // Avoid .part files
                "-o", outputFilePath, 
                query
        );
        downloadBuilder.redirectErrorStream(true);
        Process downloadProcess = downloadBuilder.start();

        boolean completedInTime = downloadProcess.waitFor(60, TimeUnit.SECONDS);

        if (!completedInTime) {
            downloadProcess.destroyForcibly();
            return null;
        }

        File downloadedFile = new File(outputFilePath);
        if (!downloadedFile.exists()) {
            logger.error("Download failed: File not found at " + outputFilePath);
            return null;
        }

        return downloadedFile;
    }

    private String getYouTubeTitle(String input) throws IOException, InterruptedException {
        String query = input.startsWith("http://") || input.startsWith("https://") ? input : "ytsearch:" + input;

        ProcessBuilder builder = new ProcessBuilder("yt-dlp", "--get-title", query);
        builder.redirectErrorStream(true);
        Process metadataProcess = builder.start();
        String title = new String(metadataProcess.getInputStream().readAllBytes()).trim();

        return title.isEmpty() ? null : title;
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
