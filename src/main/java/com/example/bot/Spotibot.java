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

import javax.security.auth.login.LoginException;
import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.Comparator;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ConcurrentHashMap;

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

    public final ConcurrentHashMap<Long, LinkedBlockingQueue<String>> serverQueues = new ConcurrentHashMap<>();
    public final ConcurrentHashMap<Long, LinkedBlockingQueue<String>> serverTitles = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Long, String> currentlyPlayingTitles = new ConcurrentHashMap<>();

    public LinkedBlockingQueue<String> getServerTitleQueue(long guildId) {
        return serverTitles.get(guildId);
    }

    public static void main(String[] args) {
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

    private static void loadConfig() {
        try {
            File configFile = new File("config.json"); // Updated path

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
                    String title = getYouTubeTitle(input);
                    if (title != null) {
                        LinkedBlockingQueue<String> queue = serverQueues.computeIfAbsent(guild.getIdLong(), k -> new LinkedBlockingQueue<>());
                        LinkedBlockingQueue<String> titleQueue = serverTitles.computeIfAbsent(guild.getIdLong(), k -> new LinkedBlockingQueue<>());

                        queue.offer(input);
                        titleQueue.offer(title);

                        currentlyPlayingTitles.put(guild.getIdLong(), title);
                        messageChannel.sendMessage(queuedFormat.replace("{title}", title)).queue();

                        File downloadedFile = downloadYouTubeAudio(input, serverFolder);
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
                trackScheduler.nextTrack(); // Skip to the next track
                messageChannel.sendMessage(skipEmoji + " Skipped to the next track.").queue();

                if (trackScheduler.isQueueEmpty()) {
                    guild.getAudioManager().closeAudioConnection();
                    messageChannel.sendMessage("No more tracks in the queue. Leaving the voice channel.").queue();
                }
            } else {
                messageChannel.sendMessage("No track is currently playing to skip.").queue();
            }
        } else if (message.equalsIgnoreCase("!stop") || message.equalsIgnoreCase(stopEmoji)) {
            guild.getAudioManager().closeAudioConnection();
            trackScheduler.clearQueueAndStop();
            clearDownloadsFolder(serverFolder);

            serverQueues.remove(guild.getIdLong());
            serverTitles.remove(guild.getIdLong());

            messageChannel.sendMessage(stopEmoji + " Stopped playback, cleared the queue, and deleted all downloads for this server.").queue();
        } else if (message.equalsIgnoreCase("!queue") || message.equalsIgnoreCase(queueEmoji)) {
            showQueue(event, serverFolder);
        } else if (message.equalsIgnoreCase("!help")) {
            messageChannel.sendMessage(getHelpMessage()).queue();
        }
    }

    private void showQueue(MessageReceivedEvent event, String serverFolder) {
        File downloadFolder = new File(serverFolder);
        if (downloadFolder.exists() && downloadFolder.isDirectory()) {
            File[] files = downloadFolder.listFiles();
            if (files != null && files.length > 0) {
                Arrays.sort(files, Comparator.comparingLong(File::lastModified));
                StringBuilder queueMessage = new StringBuilder("🎶 **Now Playing and Queue** 🎶\n");
                int index = 1;
                for (File file : files) {
                    queueMessage.append(index++).append(". ").append(file.getName()).append("\n");
                }
                event.getChannel().sendMessage(queueMessage.toString()).queue();
            } else {
                event.getChannel().sendMessage("The queue is empty.").queue();
            }
        } else {
            event.getChannel().sendMessage("The downloads folder does not exist for this server.").queue();
        }
    }

    private File getCurrentTrackFile(String serverFolder) {
        return new File(serverFolder + "current_track.mp3");
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

    private File downloadYouTubeAudio(String input, String serverFolder) throws IOException, InterruptedException {
        String title = getYouTubeTitle(input);
        if (title == null) return null;

        String sanitizedTitle = sanitizeFileName(title);
        String outputFilePath = serverFolder + sanitizedTitle + ".mp3";

        File downloadsDir = new File(serverFolder);
        if (!downloadsDir.exists()) {
            downloadsDir.mkdirs();
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

    private String getHelpMessage() {
        return "**Spotibot Commands**:\n" +
                "`!play <URL or search term>` - Plays a YouTube video. If something is already playing, it will queue the track.\n" +
                "`!skip` or `" + skipEmoji + "` - Skips the currently playing track and plays the next one in the queue.\n" +
                "`!stop` or `" + stopEmoji + "` - Stops playback, clears the queue, and deletes all downloaded tracks.\n" +
                "`!queue` or `" + queueEmoji + "` - Shows the current queue.\n" +
                "`!help` - Shows this list of commands.";
    }
}
