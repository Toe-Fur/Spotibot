package com.example.bot;

import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-server settings, saved to config/guild-settings.json so they survive restarts.
 *  - gamingMode:  listen for "Spotibot, ..." voice commands while in a voice channel
 *  - radio:       when the queue runs out, keep playing similar songs
 *  - idleMinutes: leave the voice channel after this long with nothing playing
 *  - tidy:        delete typed control commands (!skip, !pause, ...) to keep the channel clean
 */
public final class GuildSettings {
    private static final Logger logger = LoggerFactory.getLogger(GuildSettings.class);
    private static final Path FILE = Path.of(ConfigUtils.CONFIG_FOLDER, "guild-settings.json");
    private static final Map<Long, GuildSettings> ALL = new ConcurrentHashMap<>();
    private static volatile boolean loaded;

    public volatile boolean gamingMode = VoiceListener.available() && VoiceSession.VOICE_DEFAULT;
    public volatile boolean radio = StreamTrack.enabled();
    public volatile int idleMinutes = VoiceSession.IDLE_MIN;
    public volatile boolean tidy = true;

    public static GuildSettings of(long guildId) {
        load();
        return ALL.computeIfAbsent(guildId, id -> new GuildSettings());
    }

    private static synchronized void load() {
        if (loaded) return;
        loaded = true;
        try {
            if (!Files.exists(FILE)) return;
            JSONObject root = new JSONObject(Files.readString(FILE, StandardCharsets.UTF_8));
            for (String k : root.keySet()) {
                JSONObject o = root.getJSONObject(k);
                GuildSettings s = new GuildSettings();
                s.gamingMode = o.optBoolean("gamingMode", s.gamingMode) && VoiceListener.available();
                s.radio = o.optBoolean("radio", s.radio) && StreamTrack.enabled();
                s.idleMinutes = Math.max(1, Math.min(240, o.optInt("idleMinutes", s.idleMinutes)));
                s.tidy = o.optBoolean("tidy", s.tidy);
                ALL.put(Long.parseLong(k), s);
            }
        } catch (Exception e) {
            logger.warn("Could not read {}: {}", FILE, e.toString());
        }
    }

    public static synchronized void save() {
        try {
            JSONObject root = new JSONObject();
            ALL.forEach((id, s) -> root.put(String.valueOf(id), new JSONObject()
                    .put("gamingMode", s.gamingMode).put("radio", s.radio)
                    .put("idleMinutes", s.idleMinutes).put("tidy", s.tidy)));
            Path tmp = Path.of(FILE + ".tmp");
            Files.writeString(tmp, root.toString(2), StandardCharsets.UTF_8);
            Files.move(tmp, FILE, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (Exception e) {
            logger.warn("Could not save {}: {}", FILE, e.toString());
        }
    }

    public String describe() {
        return "Gaming mode " + (gamingMode ? "on" : "off") + "  |  Radio " + (radio ? "on" : "off")
                + "  |  Leave after " + idleMinutes + " min idle  |  Tidy commands " + (tidy ? "on" : "off");
    }
}
