package com.example.bot;

import com.sedmelluq.discord.lavaplayer.player.AudioPlayer;  // Import AudioPlayer
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

import javax.security.auth.login.LoginException;
import java.io.File;
import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ConcurrentHashMap;

public class Spotibot extends ListenerAdapter {
    private static final Logger logger = LoggerFactory.getLogger(Spotibot.class); // Added logger
    private final AudioPlayerManager playerManager = new DefaultAudioPlayerManager();
    private final ExecutorService downloadExecutor = Executors.newCachedThreadPool();
    private TrackSchedulerRegistry trackSchedulerRegistry;
    private static String BOT_TOKEN;  // Removed the hardcoded token
    private static String STATUS;  // Bot status
    private static int defaultVolume = 60;  // Default volume value
    private static String nowPlayingFormat;
    private static String queuedFormat;
    private static String skipEmoji;
    private static String stopEmoji;
    private static String queueEmoji;

    // Queue management for each server
    public final ConcurrentHashMap<Long, LinkedBlockingQueue<String>> serverQueues = new ConcurrentHashMap<>();
    public final ConcurrentHashMap<Long, LinkedBlockingQueue<String>> serverTitles = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Long, String> currentlyPlayingTitles = new ConcurrentHashMap<>();

    // Main method to start the bot
    public static void main(String[] args) {
        loadConfig();  // Load the configuration before using BOT_TOKEN

        if (BOT_TOKEN == null || BOT_TOKEN.isEmpty()) {
            throw new IllegalArgumentException("Bot token is not set. Please set the bot token in the config file.");
        }

        try {
            JDABuilder.createDefault(BOT_TOKEN)
                    .enableIntents(GatewayIntent.MESSAGE_CONTENT)
                    .setActivity(Activity.playing(STATUS))  // Set the bot status
                    .addEventListeners(new Spotibot()) // Register the listener
                    .build();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private static void loadConfig() {
        try {
            // Load the config file from the target directory (adjust path as necessary)
            File configFile = new File("src/resources/config.json");  // Path to your config.json

            if (!configFile.exists()) {
                logger.error("Config file not found. Please ensure the config.json file is in the target directory.");
                System.exit(1);
            }

            ObjectMapper mapper = new ObjectMapper();
            JsonNode config = mapper.readTree(configFile);

            // Load bot token and other settings from config.json
            BOT_TOKEN = config.get("bot_token").asText(); // Ensure this matches the key in your JSON
            STATUS = config.get("status").asText();
            defaultVolume = config.get("default_volume").asInt(60);  // Load default volume from config
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

        // Set the default volume when a track is loaded
        trackScheduler.getPlayer().setVolume(defaultVolume);

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
                    String title = getYouTubeTitle(input);  // Get title from YouTube URL or search
                    if (title != null) {
                        // Manage queue for this server
                        LinkedBlockingQueue<String> queue = serverQueues.computeIfAbsent(guild.getIdLong(), k -> new LinkedBlockingQueue<>());
                        LinkedBlockingQueue<String> titleQueue = serverTitles.computeIfAbsent(guild.getIdLong(), k -> new LinkedBlockingQueue<>());

                        // Store the title in the queue
                        queue.offer(input);  // Keep original input (URL or search term)
                        titleQueue.offer(title);  // Store actual title in the title queue

                        currentlyPlayingTitles.put(guild.getIdLong(), title);
                        messageChannel.sendMessage(nowPlayingFormat.replace("{title}", title)).queue();

                        File downloadedFile = downloadYouTubeAudio(input, guild);  
                        if (downloadedFile != null) {
                            playerManager.loadItem(downloadedFile.getAbsolutePath(), new AudioLoadResultHandlerImpl(trackScheduler.getPlayer(), messageChannel, trackScheduler, downloadedFile, title));
                        } else {
                            messageChannel.sendMessage("Download timed out. Skipping to the next track.").queue();
                            trackScheduler.nextTrack();
                        }
                    }
                } catch (IOException | InterruptedException e) {
                    messageChannel.sendMessage("Error while downloading audio: " + e.getMessage()).queue();
                    e.printStackTrace();
                }
            });
        } else if (message.equalsIgnoreCase("!skip") || message.equalsIgnoreCase(skipEmoji)) {
            if (trackScheduler.getPlayer().getPlayingTrack() != null) {
                String currentTrackTitle = currentlyPlayingTitles.get(guild.getIdLong());
                if (currentTrackTitle != null) {
                    LinkedBlockingQueue<String> titleQueue = serverTitles.get(guild.getIdLong());
                    if (titleQueue != null) {
                        titleQueue.remove(currentTrackTitle);  // Remove played track from queue
                    }
                }
                trackScheduler.nextTrack();
                messageChannel.sendMessage(skipEmoji + " Skipped to the next track.").queue();
            } else {
                messageChannel.sendMessage("No track is currently playing to skip.").queue();
            }
        } else if (message.equalsIgnoreCase("!stop") || message.equalsIgnoreCase(stopEmoji)) {
            guild.getAudioManager().closeAudioConnection();
            trackScheduler.clearQueueAndStop();
            clearDownloadsFolder(guild);

            serverQueues.remove(guild.getIdLong());
            serverTitles.remove(guild.getIdLong());

            messageChannel.sendMessage(stopEmoji + " Stopped playback, cleared the queue, and deleted all downloads for this server.").queue();
        } else if (message.equalsIgnoreCase("!queue") || message.equalsIgnoreCase(queueEmoji)) {
            showQueue(event);
        } else if (message.equalsIgnoreCase("!help")) {
            messageChannel.sendMessage(getHelpMessage()).queue();
        }
    }

    // Implement the getServerTitleQueue method to avoid the compilation error
    public LinkedBlockingQueue<String> getServerTitleQueue(long guildId) {
        return serverTitles.get(guildId);  // This assumes `serverTitles` is a map of guild IDs to queues
    }

    // Show queue
    private void showQueue(MessageReceivedEvent event) {
        Guild guild = event.getGuild();
        LinkedBlockingQueue<String> titleQueue = serverTitles.get(guild.getIdLong());

        if (titleQueue != null && !titleQueue.isEmpty()) {
            StringBuilder queueMessage = new StringBuilder("🎶 **Now Playing and Queue** 🎶\n");
            int index = 1;

            for (String title : titleQueue) {
                queueMessage.append(queuedFormat.replace("{index}", String.valueOf(index)).replace("{title}", title)).append("\n");
                index++;
            }

            event.getChannel().sendMessage(queueMessage.toString()).queue();
        } else {
            event.getChannel().sendMessage("The queue is currently empty.").queue();
        }
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

    private File downloadYouTubeAudio(String input, Guild guild) throws IOException, InterruptedException {
        String title = getYouTubeTitle(input);
        if (title == null) return null;

        String sanitizedTitle = sanitizeFileName(title);
        String outputFilePath = "downloads/" + guild.getIdLong() + "/" + sanitizedTitle + ".mp3";

        File downloadsDir = new File("downloads/" + guild.getIdLong());
        if (!downloadsDir.exists()) {
            downloadsDir.mkdir();
        }

        String query = input.startsWith("http://") || input.startsWith("https://") ? input : "ytsearch:" + input;

        ProcessBuilder downloadBuilder = new ProcessBuilder("yt-dlp", "-x", "--audio-format", "mp3", "--no-check-certificate", "-o", outputFilePath, query);
        downloadBuilder.redirectErrorStream(true);
        Process downloadProcess = downloadBuilder.start();

        boolean completedInTime = downloadProcess.waitFor(15, TimeUnit.SECONDS);

        if (!completedInTime) {
            downloadProcess.destroyForcibly();
            return null;
        }

        return new File(outputFilePath);
    }

    private void clearDownloadsFolder(Guild guild) {
        File serverFolder = new File("downloads/" + guild.getIdLong());
        if (serverFolder.exists() && serverFolder.isDirectory()) {
            File[] files = serverFolder.listFiles();
            if (files != null) {
                for (File file : files) {
                    if (file.isFile() && file.getName().endsWith(".mp3")) {
                        file.delete();
                    }
                }
            }
        }
    }

    private String getHelpMessage() {
        return "**Spotibot Commands**:\n" +
                "`!play <URL or search term>` - Plays a YouTube video. If something is already playing, it will queue the track.\n" +
                "`!skip` or `" + skipEmoji + "` - Skips the currently playing track and plays the next one in the queue.\n" +
                "`!forceskip` - Force skips the current track and moves to the next track in the queue.\n" +
                "`!stop` or `" + stopEmoji + "` - Stops playback, clears the queue, and deletes all downloaded tracks.\n" +
                "`!queue` or `" + queueEmoji + "` - Shows the current queue.\n" +
                "`!help` - Shows this list of commands.";
    }
}
