package com.example.bot;

import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import org.json.JSONArray;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/** Song lyrics from LRCLIB (https://lrclib.net, free, no API key). */
public final class Lyrics {
    private static final Logger logger = LoggerFactory.getLogger(Lyrics.class);
    private static final HttpClient HTTP = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(5)).build();

    public record Result(String title, String artist, String text) {
    }

    private Lyrics() {
    }

    /** Lyrics for the song that is playing right now (null = nothing playing or none found). */
    public static Result forTrack(TrackScheduler ts, AudioTrack cur) {
        if (cur == null) return null;
        StreamTrack meta = ts.metaOf(cur);
        String title = meta != null && meta.songTitle != null && !meta.songTitle.isBlank() ? meta.songTitle : ts.getTitle(cur.getIdentifier());
        if (title == null || title.isBlank()) title = cur.getInfo().title;
        String artist = meta != null ? meta.songArtist : "";
        int secs = meta != null ? meta.durationSec : 0;
        if (secs <= 0 && cur.getDuration() > 0 && cur.getDuration() < 24L * 3600_000) secs = (int) (cur.getDuration() / 1000);
        return find(artist, title, secs);
    }

    public static Result find(String artist, String title) {
        return find(artist, title, 0);
    }

    /**
     * Search LRCLIB and only accept a result that really is this song: the title must match and either
     * the artist or the song length (within a few seconds) must agree. Video-style titles
     * ("Ram Jam - Black Betty (Lyrics)" uploaded by a lyrics channel) are tried both ways round.
     * Better "no lyrics found" than somebody else's song.
     */
    public static Result find(String artist, String title, int durationSec) {
        if (title == null || title.isBlank()) return null;
        String t = clean(title);
        String a = artist == null ? "" : artist.replaceAll("(?i) - topic$", "").split(",| & | feat")[0].trim();
        java.util.LinkedHashMap<String, String> cands = new java.util.LinkedHashMap<>(); // title -> artist
        int dash = t.indexOf(" - ");
        if (dash > 0) {
            String left = t.substring(0, dash).trim(), right = clean(t.substring(dash + 3));
            cands.put(right, left);   // "Artist - Title" (usual on YouTube)
            cands.putIfAbsent(left, right.isEmpty() ? a : right);
        }
        cands.putIfAbsent(t, a);
        String known = StreamTrack.norm((t + " " + a).toLowerCase(java.util.Locale.ROOT));
        java.util.Map<Long, JSONObject> seen = new java.util.LinkedHashMap<>();
        try {
            for (var c : cands.entrySet()) {
                String ct = c.getKey(), ca = c.getValue();
                if (ct.isBlank()) continue;
                collect(seen, "track_name=" + enc(ct) + (ca.isBlank() ? "" : "&artist_name=" + enc(ca)));
                Result best = pick(seen.values(), cands, known, durationSec);
                if (best != null) return best;
            }
            for (var c : cands.entrySet()) {
                collect(seen, "q=" + enc((c.getKey() + " " + c.getValue()).trim()));
                Result best = pick(seen.values(), cands, known, durationSec);
                if (best != null) return best;
            }
        } catch (Exception e) {
            logger.warn("lyrics lookup failed: {}", e.toString());
        }
        return null;
    }

    private static void collect(java.util.Map<Long, JSONObject> into, String query) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create("https://lrclib.net/api/search?" + query))
                .header("User-Agent", "Spotibot (https://github.com/Toe-Fur/Spotibot)")
                .timeout(Duration.ofSeconds(10)).build();
        HttpResponse<String> r = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
        if (r.statusCode() != 200) return;
        JSONArray arr = new JSONArray(r.body());
        for (int i = 0; i < arr.length() && i < 20; i++) {
            JSONObject o = arr.getJSONObject(i);
            into.putIfAbsent(o.optLong("id", -i - 1L - into.size()), o);
        }
    }

    private static Result pick(java.util.Collection<JSONObject> results, java.util.Map<String, String> cands, String known, int durationSec) {
        JSONObject best = null;
        int bestScore = Integer.MIN_VALUE;
        for (JSONObject o : results) {
            String plain = o.optString("plainLyrics", "");
            if (plain.isBlank() && !o.optBoolean("instrumental")) continue;
            String rt = StreamTrack.norm(clean(o.optString("trackName")).toLowerCase(java.util.Locale.ROOT));
            String ra = StreamTrack.norm(o.optString("artistName").toLowerCase(java.util.Locale.ROOT).split(",| & | feat")[0]);
            if (rt.isEmpty()) continue;
            int titleSim = 0;
            boolean artistOk = false;
            for (var c : cands.entrySet()) {
                String ct = StreamTrack.norm(c.getKey().toLowerCase(java.util.Locale.ROOT));
                String ca = StreamTrack.norm(c.getValue().toLowerCase(java.util.Locale.ROOT));
                int sim = ct.equals(rt) ? 3 : (!ct.isEmpty() && (ct.contains(rt) || rt.contains(ct))) ? 2 : 0;
                if (sim > titleSim) titleSim = sim;
                if (!ra.isEmpty() && !ca.isEmpty() && (ca.contains(ra) || ra.contains(ca))) artistOk = true;
            }
            if (!ra.isEmpty() && (" " + known + " ").contains(" " + ra + " ")) artistOk = true;
            int durScore = 0;
            double rd = o.optDouble("duration", 0);
            if (durationSec > 0 && rd > 0) {
                double diff = Math.abs(rd - durationSec);
                durScore = diff <= 3 ? 3 : diff <= 10 ? 1 : diff > 30 ? -3 : 0;
            }
            if (titleSim < 2 || (!artistOk && durScore < 3) || durScore < 0 && !artistOk) continue;
            int score = titleSim * 2 + (artistOk ? 4 : 0) + durScore + (plain.isBlank() ? 0 : 1);
            if (score > bestScore) {
                bestScore = score;
                best = o;
            }
        }
        if (best == null) return null;
        String plain = best.optString("plainLyrics", "");
        return new Result(best.optString("trackName"), best.optString("artistName"), plain.isBlank() ? "(instrumental)" : plain.trim());
    }

    /** Drop "(Official Video)", "[Lyrics]", "- Remastered 2011" and similar from a title. */
    static String clean(String title) {
        return title.replaceAll("(?i)\\s*[\\(\\[][^\\)\\]]*(official|lyric|audio|video|visuali[sz]er|remaster|hd|4k)[^\\)\\]]*[\\)\\]]", "")
                .replaceAll("(?i)\\s+-\\s+(remaster(ed)?|\\d{4} remaster).*$", "").trim();
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }
}
