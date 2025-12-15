package com.example.bot;

import com.sedmelluq.discord.lavaplayer.player.AudioPlayerManager;
import com.sedmelluq.discord.lavaplayer.player.DefaultAudioPlayerManager;
import com.sedmelluq.discord.lavaplayer.source.AudioSourceManagers;
import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;
import net.dv8tion.jda.api.events.message.react.MessageReactionAddEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.requests.GatewayIntent;
import net.dv8tion.jda.api.entities.Activity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;

public class Spotibot extends ListenerAdapter {
    private static final Logger logger = LoggerFactory.getLogger(Spotibot.class);
    private final AudioPlayerManager playerManager = new DefaultAudioPlayerManager();
    private ExecutorService downloadExecutor = Executors.newCachedThreadPool();
    private final BlockingQueue<Runnable> downloadQueue = new LinkedBlockingQueue<>();
    private Future<?> currentDownloadTask;
    private TrackSchedulerRegistry trackSchedulerRegistry;
    private static String BOT_TOKEN;
    private static String STATUS;
    public final ConcurrentHashMap<Long, LinkedBlockingQueue<String>> serverQueues = new ConcurrentHashMap<>();
    public final ConcurrentHashMap<Long, LinkedBlockingQueue<String>> serverTitles = new ConcurrentHashMap<>();
    private final Map<Long, Integer> queuePageMap = new ConcurrentHashMap<>();
    public LinkedBlockingQueue<String> getServerTitleQueue(long guildId) {
        return serverTitles.get(guildId);
    }

    public static void main(String[] args) {
        ConfigUtils.createConfigFolder();
        ConfigUtils.ensureConfigFileExists();
        ConfigUtils.loadConfig();

        BOT_TOKEN = ConfigUtils.BOT_TOKEN;
        STATUS = ConfigUtils.STATUS;
        File cookieFile = new File(ConfigUtils.COOKIE_FILE_PATH);
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
                .addEventListeners(
                    new Spotibot(),
                    new BlackjackUiListener()
                )
                .build();
        } catch (Exception e) {
            e.printStackTrace();
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
        CommandHandler.handleMessage(event, this, playerManager, trackSchedulerRegistry, downloadQueue);
    }

    @Override
    public void onMessageReactionAdd(MessageReactionAddEvent event) {
        CommandHandler.handleReaction(event, trackSchedulerRegistry, queuePageMap);
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

    public Future<?> getCurrentDownloadTask() {
        return currentDownloadTask;
    }

    public void setCurrentDownloadTask(Future<?> currentDownloadTask) {
        this.currentDownloadTask = currentDownloadTask;
    }

    public ExecutorService getDownloadExecutor() {
        return downloadExecutor;
    }

    public void setDownloadExecutor(ExecutorService downloadExecutor) {
        this.downloadExecutor = downloadExecutor;
    }

    public BlockingQueue<Runnable> getDownloadQueue() {
        return downloadQueue;
    }

    public void startDownloadQueueProcessorPublic() {
        startDownloadQueueProcessor();
    }

    public Map<Long, Integer> getQueuePageMap() {
        return queuePageMap;
    }
}