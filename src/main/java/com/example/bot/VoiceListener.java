package com.example.bot;

import net.dv8tion.jda.api.audio.AudioReceiveHandler;
import net.dv8tion.jda.api.audio.UserAudio;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.User;
import net.dv8tion.jda.api.entities.channel.middleman.AudioChannel;
import net.dv8tion.jda.api.entities.channel.middleman.GuildMessageChannel;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Gaming mode: hears "Spotibot, play <song>" (and skip / pause / resume / stop / leave / louder / quieter ...).
 * Each speaker's audio is cut into utterances at short pauses, the first few seconds are sent to the
 * local spotibot-stt service (Whisper, in memory only), and only phrases containing the wake word act.
 * Nothing is recorded to disk; only recognised commands are logged.
 */
public final class VoiceListener implements AudioReceiveHandler {
    private static final Logger logger = LoggerFactory.getLogger(VoiceListener.class);

    /** Speech-to-text server (spotibot-stt). Unset = gaming mode unavailable. */
    private static final String STT_BASE = envOr("STT_URL", "");
    private static final String STT_URL = STT_BASE + "/transcribe";
    private static final int BYTES_PER_SEC = 16000 * 2;          // 16 kHz mono s16le
    private static final long GAP_MS = 700;                       // pause that ends an utterance
    private static final int MIN_BYTES = BYTES_PER_SEC / 2;       // ignore < 0.5 s
    private static final int SEND_BYTES = BYTES_PER_SEC * 7;      // commands fit in the first 7 s
    private static final int MAX_BYTES = BYTES_PER_SEC * 12;      // force a cut for non-stop talkers
    private static final long ARM_MS = 8000;                      // "Spotibot" ... (pause) ... "play X"

    private static final Pattern WAKE = Pattern.compile(
            "(?i)\\b(?:hey\\s+|ok(?:ay)?\\s+)?(?:spo(?:t|tt|d|dd)?(?:i|y|ie|ee|a|e)?\\s?(?:a\\s|the\\s|it\\s)?bots?|spotify\\s?bot|potty\\s?bot|spot\\s?bot)\\b[\\s,.!?]*(.*)");
    private static final Pattern PLAYLIST = Pattern.compile(
            "(?i)^(?:please\\s+)?play\\s+(?:my|the|our)\\s+(.+?)\\s+(?:playlist|play list|mix)$|^(?:please\\s+)?play\\s+(?:the\\s+)?(?:playlist|play list)\\s+(.+)$");
    private static final Pattern PLAY = Pattern.compile("(?i)^(?:please\\s+|can you\\s+|could you\\s+)?(?:play|put on|queue(?: up)?|add)\\s+(.+?)(?:\\s+please)?$");

    private static final ScheduledExecutorService TICK = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "voice-tick");
        t.setDaemon(true);
        return t;
    });
    // One transcription at a time; while busy keep at most 4 waiting clips (oldest dropped).
    private static final ThreadPoolExecutor STT = new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS,
            new LinkedBlockingQueue<>(4), r -> {
        Thread t = new Thread(r, "voice-stt");
        t.setDaemon(true);
        return t;
    }, new ThreadPoolExecutor.DiscardOldestPolicy());
    private static final HttpClient HTTP = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).connectTimeout(Duration.ofSeconds(3)).build(); // uvicorn drops POST bodies on h2c upgrade

    private final Guild guild;
    private final Map<Long, Clip> clips = new ConcurrentHashMap<>();
    private final Map<Long, Long> armedUntil = new ConcurrentHashMap<>();
    private volatile boolean enabled;

    VoiceListener(Guild guild, boolean enabled) {
        this.guild = guild;
        this.enabled = enabled;
        TICK.scheduleAtFixedRate(this::tick, 200, 200, TimeUnit.MILLISECONDS);
    }

    public static boolean available() {
        return !STT_BASE.isEmpty();
    }

    public boolean isEnabled() {
        return enabled && available();
    }

    public void setEnabled(boolean on) {
        enabled = on;
        if (!on) clips.clear();
    }

    @Override
    public boolean canReceiveUser() {
        return isEnabled();
    }

    @Override
    public void handleUserAudio(UserAudio ua) {
        User u = ua.getUser();
        if (!isEnabled() || u == null || u.isBot()) return;
        clips.computeIfAbsent(u.getIdLong(), id -> new Clip(id)).append(ua.getAudioData(1.0));
    }

    private void tick() {
        long now = System.currentTimeMillis();
        for (Clip c : clips.values()) {
            if (c.size() > 0 && now - c.last > GAP_MS) c.flush();
        }
    }

    private final class Clip {
        final long userId;
        final ByteArrayOutputStream out = new ByteArrayOutputStream(BYTES_PER_SEC * 4);
        volatile long last;

        Clip(long userId) {
            this.userId = userId;
        }

        synchronized int size() {
            return out.size();
        }

        /** 48 kHz stereo s16 big-endian (JDA OUTPUT_FORMAT) -> 16 kHz mono s16 little-endian. */
        synchronized void append(byte[] pcm) {
            last = System.currentTimeMillis();
            for (int i = 0; i + 12 <= pcm.length; i += 12) {
                int sum = 0;
                for (int f = 0; f < 3; f++) {
                    int o = i + f * 4;
                    short l = (short) ((pcm[o] << 8) | (pcm[o + 1] & 0xff));
                    short r = (short) ((pcm[o + 2] << 8) | (pcm[o + 3] & 0xff));
                    sum += l + r;
                }
                int s = sum / 6;
                out.write(s & 0xff);
                out.write((s >> 8) & 0xff);
            }
            if (out.size() >= MAX_BYTES) flush();
        }

        synchronized void flush() {
            byte[] audio = out.toByteArray();
            out.reset();
            if (audio.length < MIN_BYTES) return;
            final byte[] send = audio.length > SEND_BYTES ? Arrays.copyOf(audio, SEND_BYTES) : audio;
            STT.execute(() -> transcribe(userId, send));
        }
    }

    private void transcribe(long userId, byte[] audio) {
        String text;
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(STT_URL))
                    .header("Content-Type", "application/octet-stream")
                    .timeout(Duration.ofSeconds(20))
                    .POST(HttpRequest.BodyPublishers.ofByteArray(audio)).build();
            HttpResponse<String> r = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
            if (r.statusCode() != 200) return;
            text = new JSONObject(r.body()).optString("text", "").trim();
        } catch (Exception e) {
            logger.warn("STT failed: {}", e.toString());
            return;
        }
        if (text.isEmpty()) return;
        try {
            handleText(userId, text);
        } catch (Exception e) {
            logger.warn("voice command failed: {}", e.toString());
        }
    }

    private void handleText(long userId, String text) {
        String clean = text.replaceAll("[^\\p{L}\\p{N}'&\\s,]", " ").replaceAll("\\s+", " ").trim();
        String cmd;
        Matcher w = WAKE.matcher(clean);
        long now = System.currentTimeMillis();
        if (w.find()) {
            cmd = w.group(1).replaceAll("^[\\s,]+|[\\s,]+$", "");
            if (cmd.isEmpty()) {              // just "Spotibot" -> wait for the command
                armedUntil.put(userId, now + ARM_MS);
                notice(userId, "Yes? Say a command, e.g. \"play Africa by Toto\"");
                return;
            }
        } else if (armedUntil.getOrDefault(userId, 0L) > now) {
            cmd = clean.replaceAll("^[\\s,]+|[\\s,]+$", "");
        } else {
            return; // ordinary conversation: ignored, never logged
        }
        armedUntil.remove(userId);
        logger.info("[VOICE] command from {}: {}", userId, cmd);
        run(userId, cmd);
    }

    private void run(long userId, String cmd) {
        Member member = guild.getMemberById(userId);
        String who = member != null ? member.getEffectiveName() : "someone";
        Spotibot bot = VoiceSession.bot();
        TrackScheduler ts = bot.getTrackSchedulerRegistry().getOrCreate(guild, bot);
        VoiceSession.get(guild).touch();
        String c = cmd.toLowerCase().replaceAll(",", "").replaceAll("\\s+", " ").trim();

        AudioChannel vc0 = guild.getAudioManager().getConnectedChannel();
        GuildMessageChannel text0 = panelChannel();

        // "play my Vibes playlist" / "play the playlist Vibes"
        Matcher pl = PLAYLIST.matcher(cmd.replaceAll(",", ""));
        if (pl.find() && JellyfinClient.enabled() && vc0 != null && text0 != null) {
            String name = pl.group(1) != null ? pl.group(1) : pl.group(2);
            CommandHandler.playPlaylist(guild, vc0, text0, name, bot, who);
            return;
        }
        // "play something like this" / "start a radio"
        if (c.matches("(play )?(something|more|songs?) like (this|that|this one)|(start )?(a )?radio( from this)?|more like this")) {
            String r = PlayerControls.radioNow(ts, text0, who);
            notice(userId, r == null ? "Play a song first, then ask for more like it" : r);
            return;
        }
        Matcher p = PLAY.matcher(cmd.replaceAll(",", ""));
        if (p.find() && !p.group(1).isBlank()) {
            String q = p.group(1).trim();
            boolean next = false;
            if (q.toLowerCase().endsWith(" next")) {
                q = q.substring(0, q.length() - 5).trim();
                next = true;
            }
            String input = q;
            int by = q.toLowerCase().lastIndexOf(" by ");
            if (by > 0 && by + 4 < q.length()) input = q.substring(0, by).trim() + StreamTrack.SEP + q.substring(by + 4).trim();
            AudioChannel vc = guild.getAudioManager().getConnectedChannel();
            GuildMessageChannel text = panelChannel();
            if (vc == null || text == null) return;
            CommandHandler.play(guild, vc, text, input, bot, bot.getDownloadQueue(),
                    "Heard " + who + ": play " + q + (next ? " next" : ""), next);
            return;
        }
        String result;
        if (c.matches("(skip|next|next song|skip (this|it|this song|the song)|skip song)( please)?")) {
            result = PlayerControls.skip(ts, who);
        } else if (c.matches("(pause|pause (it|the music|music)|hold on|wait)( please)?")) {
            result = PlayerControls.setPaused(ts, true, who);
        } else if (c.matches("(resume|continue|unpause|play|keep playing|go|resume (the )?music)( please)?")) {
            result = PlayerControls.setPaused(ts, false, who);
        } else if (c.matches("(stop|stop (it|the music|music|playing)|clear( the)? queue)( please)?")) {
            CommandHandler.handleStopCommand(guild, panelChannel(), ts, PlayerControls.serverFolder(guild), bot,
                    bot.getTrackSchedulerRegistry(), bot.getDownloadQueue(), who, true);
            return;
        } else if (c.matches("(leave|go away|get out|bye|goodbye|good bye|dismiss(ed)?|disconnect|you can (go|leave)|that's all|thats all)( please| now)?")) {
            VoiceSession.leave(guild, "dismissed by " + who);
            return;
        } else if (c.matches("(shuffle|shuffle (it|the queue|everything|the playlist))( please)?")) {
            result = PlayerControls.shuffle(ts, who);
        } else if (c.matches("(repeat|loop)( (this|this song|it|the song|one))?( please)?")) {
            result = PlayerControls.repeat(ts, TrackScheduler.Repeat.ONE, who);
        } else if (c.matches("(repeat|loop) (all|everything|the queue|the playlist)( please)?")) {
            result = PlayerControls.repeat(ts, TrackScheduler.Repeat.ALL, who);
        } else if (c.matches("(stop repeating|repeat off|no repeat|stop looping|loop off)( please)?")) {
            result = PlayerControls.repeat(ts, TrackScheduler.Repeat.OFF, who);
        } else if (c.matches("(what's|whats|what is) (playing|this|this song|the song|that)|what song is (this|that)|(name|which) (of )?(this|the) song")) {
            result = PlayerControls.nowPlaying(ts);
        } else if (c.matches("(save|like|favorite|favourite|heart|download) (this|it|this song|the song|that)( song)?( please)?|i like this( song)?")) {
            result = PlayerControls.save(ts, who);
        } else if (c.matches("radio (on|off)|(turn|switch) (on|off) (the )?radio|(turn|switch) (the )?radio (on|off)")) {
            result = PlayerControls.radio(ts, c.contains(" on") || c.startsWith("radio on"), who);
        } else if (c.matches("(gaming mode off|stop listening|(turn|switch) off gaming mode|(turn|switch) gaming mode off)")) {
            result = PlayerControls.gaming(guild, false, who);
        } else if (c.matches("(volume up|louder|turn it up|turn up( the)?( volume| music)?)( please)?")) {
            result = PlayerControls.volume(ts, Math.min(100, ts.getPlayer().getVolume() + 15), who);
        } else if (c.matches("(volume down|quieter|softer|turn it down|turn down( the)?( volume| music)?)( please)?")) {
            result = PlayerControls.volume(ts, Math.max(5, ts.getPlayer().getVolume() - 15), who);
        } else {
            result = "Didn't catch \"" + cmd + "\" (" + who + "). Try \"Spotibot, play <song>\"";
        }
        notice(userId, result == null ? "Nothing is playing right now" : result);
    }

    private GuildMessageChannel panelChannel() {
        QueuePanel p = QueuePanel.peek(guild.getIdLong());
        return p != null ? p.channel() : null;
    }

    private void notice(long userId, String text) {
        QueuePanel p = QueuePanel.peek(guild.getIdLong());
        if (p != null) p.setNotice(text);
    }

    private static String envOr(String k, String d) {
        String v = System.getenv(k);
        return v == null || v.isBlank() ? d : v.replaceAll("/+$", "");
    }
}
