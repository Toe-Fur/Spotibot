package com.example.bot;

import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.channel.middleman.AudioChannel;
import net.dv8tion.jda.api.entities.channel.middleman.GuildMessageChannel;
import net.dv8tion.jda.api.managers.AudioManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * The bot stays in the voice channel (and, with gaming mode on, listens for "Spotibot, ...") until someone
 * dismisses it, nothing has played for the server's idle timeout (default IDLE_TIMEOUT_MIN=30), or nobody
 * else has been in the channel for ALONE_TIMEOUT_MIN (default 5).
 */
public final class VoiceSession {
    private static final Logger logger = LoggerFactory.getLogger(VoiceSession.class);

    public static final int IDLE_MIN = envInt("IDLE_TIMEOUT_MIN", 30);
    public static final int ALONE_MIN = envInt("ALONE_TIMEOUT_MIN", 5);
    public static final boolean VOICE_DEFAULT = !"off".equalsIgnoreCase(System.getenv("VOICE_COMMANDS"));

    private static final Map<Long, VoiceSession> SESSIONS = new ConcurrentHashMap<>();
    private static final ScheduledExecutorService TICK = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "voice-session");
        t.setDaemon(true);
        return t;
    });
    private static volatile Spotibot bot;

    final Guild guild;
    final VoiceListener listener;
    volatile long lastActive = System.currentTimeMillis();
    volatile long aloneSince = 0;

    private VoiceSession(Guild guild) {
        this.guild = guild;
        this.listener = new VoiceListener(guild, GuildSettings.of(guild.getIdLong()).gamingMode);
    }

    public static void init(Spotibot b) {
        bot = b;
        TICK.scheduleAtFixedRate(VoiceSession::checkAll, 30, 30, TimeUnit.SECONDS);
    }

    static Spotibot bot() {
        return bot;
    }

    public static VoiceSession get(Guild guild) {
        return SESSIONS.computeIfAbsent(guild.getIdLong(), id -> new VoiceSession(guild));
    }

    public static VoiceSession peek(long guildId) {
        return SESSIONS.get(guildId);
    }

    /** Join (or stay in) the member's channel with sending + listening wired up. */
    public static TrackScheduler join(Guild guild, AudioChannel channel, GuildMessageChannel text) {
        TrackScheduler ts = bot.getTrackSchedulerRegistry().getOrCreate(guild, bot);
        VoiceSession s = get(guild);
        AudioManager am = guild.getAudioManager();
        am.setSendingHandler(new AudioPlayerSendHandler(ts.getPlayer()));
        am.setReceivingHandler(s.listener);
        am.setSelfDeafened(false);
        if (!am.isConnected() || am.getConnectedChannel() == null
                || am.getConnectedChannel().getIdLong() != channel.getIdLong()) {
            am.openAudioConnection(channel);
        }
        if (ts.getPlayer().getPlayingTrack() == null) ts.getPlayer().setVolume(ConfigUtils.defaultVolume);
        if (text != null) ts.setNotifyChannel(text);
        s.touch();
        return ts;
    }

    public static boolean isConnected(Guild guild) {
        return guild.getAudioManager().isConnected() || guild.getAudioManager().getConnectedChannel() != null;
    }

    /** Stop everything and disconnect; the panel is frozen with the reason. */
    public static void leave(Guild guild, String reason) {
        logger.info("[VOICE] Leaving guild {}: {}", guild.getId(), reason);
        QueuePanel.left(guild.getIdLong(), reason);
        TrackScheduler ts = bot.getTrackSchedulerRegistry().get(guild);
        if (ts != null) {
            CommandHandler.handleStopCommand(guild, null, ts, PlayerControls.serverFolder(guild), bot,
                    bot.getTrackSchedulerRegistry(), bot.getDownloadQueue(), null, false);
        }
        guild.getAudioManager().closeAudioConnection();
        VoiceSession s = SESSIONS.get(guild.getIdLong());
        if (s != null) s.aloneSince = 0; // session object (and its listener) is reused on the next join
    }

    public void touch() {
        lastActive = System.currentTimeMillis();
    }

    public String statusLine() {
        int idle = GuildSettings.of(guild.getIdLong()).idleMinutes;
        if (!VoiceListener.available()) return "Leaves after " + idle + " min idle";
        String l = listener.isEnabled() ? "Gaming mode on: say \"Spotibot, play <song>\"" : "Gaming mode off";
        return l + "  |  leaves after " + idle + " min idle";
    }

    private static void checkAll() {
        for (VoiceSession s : SESSIONS.values()) {
            try {
                s.check();
            } catch (Exception e) {
                logger.warn("voice session check failed: {}", e.toString());
            }
        }
    }

    private void check() {
        AudioChannel ch = guild.getAudioManager().getConnectedChannel();
        if (ch == null) return;
        long now = System.currentTimeMillis();
        TrackScheduler ts = bot.getTrackSchedulerRegistry().get(guild);
        boolean active = ts != null && !ts.isQueueEmpty() && !ts.getPlayer().isPaused();
        if (active) lastActive = now;

        long humans = ch.getMembers().stream().map(Member::getUser).filter(u -> !u.isBot()).count();
        if (humans > 0) {
            aloneSince = 0;
        } else if (aloneSince == 0) {
            aloneSince = now;
        } else if (now - aloneSince > ALONE_MIN * 60_000L) {
            leave(guild, "everyone left the channel");
            return;
        }
        int idle = GuildSettings.of(guild.getIdLong()).idleMinutes;
        if (now - lastActive > idle * 60_000L) {
            leave(guild, "nothing played for " + idle + " minutes");
        }
    }

    private static int envInt(String k, int d) {
        try {
            return Integer.parseInt(System.getenv(k));
        } catch (Exception e) {
            return d;
        }
    }
}
