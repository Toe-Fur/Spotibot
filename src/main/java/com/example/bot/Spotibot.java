package com.example.bot;

import com.sedmelluq.discord.lavaplayer.player.AudioPlayerManager;
import com.sedmelluq.discord.lavaplayer.player.DefaultAudioPlayerManager;
import com.sedmelluq.discord.lavaplayer.source.AudioSourceManagers;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.audio.AudioModuleConfig;
import net.dv8tion.jda.api.components.actionrow.ActionRow;
import net.dv8tion.jda.api.components.buttons.Button;
import net.dv8tion.jda.api.entities.Activity;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;
import net.dv8tion.jda.api.events.guild.voice.GuildVoiceUpdateEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.requests.GatewayIntent;
import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import java.awt.Color;
import moe.kyokobot.libdave.NativeDaveFactory;
import moe.kyokobot.libdave.jda.LDJDADaveSessionFactory;
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
                .setAudioModuleConfig(new AudioModuleConfig()
                    .withDaveSessionFactory(new LDJDADaveSessionFactory(new NativeDaveFactory())))
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
        VoiceSession.init(this);
        startDownloadQueueProcessor();
    }

    @Override
    public void onMessageReceived(MessageReceivedEvent event) {
        CommandHandler.handleMessage(event, this, playerManager, trackSchedulerRegistry, downloadQueue);
    }

    @Override
    public void onButtonInteraction(ButtonInteractionEvent event) {
        String id = event.getComponentId();
        boolean paging = id.equals(CommandHandler.BTN_QUEUE_PREV) || id.equals(CommandHandler.BTN_QUEUE_NEXT);
        if (!paging && !id.startsWith(PlayerControls.BTN_PREFIX)) return;

        Guild guild = event.getGuild();
        if (guild == null) return;

        TrackScheduler scheduler = trackSchedulerRegistry.getOrCreate(guild, this);
        String who = event.getMember() != null ? event.getMember().getEffectiveName() : event.getUser().getName();

        // metzfam: Stop needs to cancel downloads (can block briefly), so acknowledge first.
        if (id.equals(PlayerControls.BTN_LEAVE)) {
            event.deferEdit().queue();
            VoiceSession.leave(guild, "dismissed by " + who);
            return;
        }
        if (id.equals(PlayerControls.BTN_STOP)) {
            event.deferEdit().queue();
            CommandHandler.handleStopCommand(guild, event.getGuildChannel(), scheduler, PlayerControls.serverFolder(guild),
                    this, trackSchedulerRegistry, downloadQueue, who, true);
            return;
        }

        QueuePanel panel = QueuePanel.of(guild.getIdLong());
        panel.bindScheduler(scheduler);
        String result = "";
        if (paging) {
            panel.setPage(id.equals(CommandHandler.BTN_QUEUE_NEXT) ? panel.page() + 1 : panel.page() - 1);
        } else if (id.equals(PlayerControls.BTN_PAUSE)) {
            result = PlayerControls.togglePause(scheduler, who);
        } else if (id.equals(PlayerControls.BTN_SKIP)) {
            result = PlayerControls.skip(scheduler, who);
        } else if (id.equals(PlayerControls.BTN_GAMING)) {
            result = PlayerControls.gaming(guild, null, who);
        } else if (id.equals(PlayerControls.BTN_RADIO)) {
            result = PlayerControls.radio(scheduler, null, who);
        } else if (id.equals(PlayerControls.BTN_SHUFFLE)) {
            result = PlayerControls.shuffle(scheduler, who);
        } else if (id.equals(PlayerControls.BTN_REPEAT)) {
            result = PlayerControls.repeat(scheduler, null, who);
        } else if (id.equals(PlayerControls.BTN_SAVE)) {
            // Jellyfin lookup can take a moment: acknowledge, then edit the panel when done
            event.deferEdit().queue();
            String r = PlayerControls.save(scheduler, who);
            panel.setNotice(r == null ? "Nothing is playing right now." : r);
            return;
        } else if (id.startsWith(PlayerControls.BTN_SEEK)) {
            try {
                result = PlayerControls.seek(scheduler, Long.parseLong(id.substring(PlayerControls.BTN_SEEK.length())), who);
            } catch (NumberFormatException e) {
                result = "";
            }
        }
        if (result == null) result = "Nothing is playing right now.";
        if (!result.isEmpty()) panel.setNoticeQuiet(result);
        event.editMessageEmbeds(panel.render().build()).setComponents(panel.components()).queue(null, e -> {});
    }

    @Override
    public void onStringSelectInteraction(net.dv8tion.jda.api.events.interaction.component.StringSelectInteractionEvent event) {
        if (!event.getComponentId().equals(PlayerControls.MENU_IDLE) || event.getGuild() == null) return;
        Guild guild = event.getGuild();
        String who = event.getMember() != null ? event.getMember().getEffectiveName() : event.getUser().getName();
        int minutes;
        try {
            minutes = Integer.parseInt(event.getValues().get(0));
        } catch (Exception e) {
            event.deferEdit().queue();
            return;
        }
        QueuePanel panel = QueuePanel.of(guild.getIdLong());
        panel.bindScheduler(trackSchedulerRegistry.getOrCreate(guild, this));
        panel.setNoticeQuiet(PlayerControls.setIdle(guild, minutes, who));
        event.editMessageEmbeds(panel.render().build()).setComponents(panel.components()).queue(null, e -> {});
    }

    @Override
    public void onGuildReady(net.dv8tion.jda.api.events.guild.GuildReadyEvent event) {
        SlashCommands.register(event.getGuild());
    }

    @Override
    public void onGuildJoin(net.dv8tion.jda.api.events.guild.GuildJoinEvent event) {
        SlashCommands.register(event.getGuild());
    }

    @Override
    public void onSlashCommandInteraction(net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent event) {
        try {
            SlashCommands.handle(event, this);
        } catch (Exception e) {
            logger.error("Slash command failed", e);
            if (!event.isAcknowledged()) event.reply("Something went wrong: " + e.getMessage()).setEphemeral(true).queue();
        }
    }

    @Override
    public void onCommandAutoCompleteInteraction(net.dv8tion.jda.api.events.interaction.command.CommandAutoCompleteInteractionEvent event) {
        SlashCommands.autocomplete(event);
    }

    @Override
    public void onGuildVoiceUpdate(GuildVoiceUpdateEvent event) {
        if (!event.getMember().getUser().equals(event.getJDA().getSelfUser())) return;
        if (event.getChannelLeft() != null && event.getChannelJoined() != null) {
            logger.info("[VOICE] Bot moved: {} -> {}", event.getChannelLeft().getName(), event.getChannelJoined().getName());
        } else if (event.getChannelLeft() != null) {
            logger.warn("[VOICE] Bot LEFT voice channel: {} — caller: {}",
                event.getChannelLeft().getName(),
                Thread.currentThread().getStackTrace()[2]);
        } else if (event.getChannelJoined() != null) {
            logger.info("[VOICE] Bot JOINED voice channel: {}", event.getChannelJoined().getName());
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

    public TrackSchedulerRegistry getTrackSchedulerRegistry() {
        return trackSchedulerRegistry;
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