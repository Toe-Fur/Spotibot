package com.example.bot;

import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.channel.middleman.GuildMessageChannel;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.Color;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * What each server played (config/history/<guild>.jsonl): powers /history, "play what we played last night"
 * (/replay) and the weekly recap post (Sundays 18:00, server time zone TZ).
 */
public final class History {
    private static final Logger logger = LoggerFactory.getLogger(History.class);
    private static final Path DIR = Path.of(ConfigUtils.CONFIG_FOLDER, "history");
    private static final long SESSION_GAP_MS = 2 * 3600_000L; // a 2 h break starts a new session
    private static final ScheduledExecutorService EXEC = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "history");
        t.setDaemon(true);
        return t;
    });

    public record Entry(long time, String display, String title, String artist, String videoId, String by) {
        /** Request string for re-queueing. */
        public String request() {
            if (videoId != null && !videoId.isBlank()) return "https://www.youtube.com/watch?v=" + videoId;
            if (title != null && !title.isBlank()) return title + StreamTrack.SEP + (artist == null ? "" : artist);
            return display;
        }
    }

    private History() {
    }

    public static void record(long guildId, StreamTrack t) {
        if (t == null) return;
        EXEC.execute(() -> {
            try {
                Files.createDirectories(DIR);
                JSONObject o = new JSONObject().put("t", System.currentTimeMillis()).put("d", t.display)
                        .put("title", t.songTitle == null ? "" : t.songTitle).put("artist", t.songArtist == null ? "" : t.songArtist)
                        .put("vid", t.videoId == null ? "" : t.videoId).put("by", t.requesterName == null ? "" : t.requesterName)
                        .put("radio", t.fromRadio);
                Files.writeString(file(guildId), o + "\n", StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            } catch (Exception e) {
                logger.warn("history write failed: {}", e.toString());
            }
        });
    }

    /** All entries, oldest first (files stay small: one line per song played). */
    public static List<Entry> all(long guildId) {
        List<Entry> out = new ArrayList<>();
        try {
            if (!Files.exists(file(guildId))) return out;
            for (String line : Files.readAllLines(file(guildId), StandardCharsets.UTF_8)) {
                if (line.isBlank()) continue;
                JSONObject o = new JSONObject(line);
                out.add(new Entry(o.optLong("t"), o.optString("d"), o.optString("title"), o.optString("artist"),
                        o.optString("vid"), o.optString("by")));
            }
        } catch (Exception e) {
            logger.warn("history read failed: {}", e.toString());
        }
        return out;
    }

    public static List<Entry> recent(long guildId, int n) {
        List<Entry> a = all(guildId);
        List<Entry> r = new ArrayList<>(a.subList(Math.max(0, a.size() - n), a.size()));
        Collections.reverse(r);
        return r;
    }

    /**
     * The previous listening session ("what we played last night"): sessions are split at 2-hour breaks; if a
     * session is going on right now, the one before it.
     */
    public static List<Entry> previousSession(long guildId) {
        List<Entry> a = all(guildId);
        if (a.isEmpty()) return a;
        List<List<Entry>> sessions = new ArrayList<>();
        List<Entry> cur = new ArrayList<>();
        long last = 0;
        for (Entry e : a) {
            if (!cur.isEmpty() && e.time() - last > SESSION_GAP_MS) {
                sessions.add(cur);
                cur = new ArrayList<>();
            }
            cur.add(e);
            last = e.time();
        }
        sessions.add(cur);
        boolean ongoing = System.currentTimeMillis() - last < SESSION_GAP_MS;
        int idx = ongoing ? sessions.size() - 2 : sessions.size() - 1;
        if (idx < 0) return List.of();
        // de-duplicate (repeat mode can play a song many times)
        Map<String, Entry> uniq = new LinkedHashMap<>();
        for (Entry e : sessions.get(idx)) uniq.putIfAbsent(e.request(), e);
        return new ArrayList<>(uniq.values());
    }

    // ---- weekly recap ----------------------------------------------------------------------------

    public static void startRecaps(JDA jda) {
        EXEC.scheduleAtFixedRate(() -> {
            try {
                ZonedDateTime now = ZonedDateTime.now();
                if (now.getDayOfWeek() != DayOfWeek.SUNDAY || now.getHour() != 18) return;
                for (Guild g : jda.getGuilds()) postRecapIfDue(g, now);
            } catch (Exception e) {
                logger.warn("recap failed: {}", e.toString());
            }
        }, 5, 10, TimeUnit.MINUTES);
    }

    private static void postRecapIfDue(Guild g, ZonedDateTime now) throws Exception {
        GuildSettings gs = GuildSettings.of(g.getIdLong());
        if (!gs.recap || gs.recapChannelId == 0) return;
        Path marker = DIR.resolve(g.getId() + ".recap");
        String week = now.getYear() + "-" + now.getDayOfYear() / 7;
        if (Files.exists(marker) && Files.readString(marker).trim().equals(week)) return;
        GuildMessageChannel ch = g.getChannelById(GuildMessageChannel.class, gs.recapChannelId);
        if (ch == null) return;
        EmbedBuilder eb = recap(g.getIdLong(), 7);
        if (eb == null) return; // too quiet a week to bother
        Files.createDirectories(DIR);
        Files.writeString(marker, week);
        ch.sendMessageEmbeds(eb.build()).queue(null, e -> logger.warn("recap post failed: {}", e.toString()));
    }

    /** Top songs and requesters for the last `days`; null if fewer than 10 plays. */
    public static EmbedBuilder recap(long guildId, int days) {
        long since = System.currentTimeMillis() - days * 86_400_000L;
        List<Entry> week = all(guildId).stream().filter(e -> e.time() >= since).toList();
        if (week.size() < 10) return null;
        Map<String, Long> songs = week.stream().collect(Collectors.groupingBy(Entry::display, Collectors.counting()));
        Map<String, Long> people = week.stream().filter(e -> e.by() != null && !e.by().isBlank())
                .collect(Collectors.groupingBy(Entry::by, Collectors.counting()));
        StringBuilder top = new StringBuilder();
        songs.entrySet().stream().sorted(Map.Entry.<String, Long>comparingByValue().reversed()).limit(5)
                .forEach(e -> top.append("`").append(e.getValue()).append("x` ").append(e.getKey()).append('\n'));
        StringBuilder who = new StringBuilder();
        people.entrySet().stream().sorted(Map.Entry.<String, Long>comparingByValue().reversed()).limit(3)
                .forEach(e -> who.append(e.getKey()).append(" (").append(e.getValue()).append(" songs)\n"));
        long hours = Math.round(week.size() * 3.5 / 60.0);
        return new EmbedBuilder().setColor(new Color(0x1db954)).setTitle("Your week in music")
                .setDescription(week.size() + " songs played (about " + hours + " hours)")
                .addField("Most played", top.toString(), false)
                .addField("Top DJs", who.length() == 0 ? "-" : who.toString(), false)
                .setFooter("/history to see and replay recent songs  |  turn off: !settings recap off")
                .setTimestamp(Instant.now());
    }

    public static String when(long t) {
        ZonedDateTime z = Instant.ofEpochMilli(t).atZone(ZoneId.systemDefault());
        long ago = (System.currentTimeMillis() - t) / 60000;
        if (ago < 60) return ago + " min ago";
        if (ago < 24 * 60) return (ago / 60) + " h ago";
        return z.getMonthValue() + "/" + z.getDayOfMonth();
    }

    private static Path file(long guildId) {
        return DIR.resolve(guildId + ".jsonl");
    }
}
