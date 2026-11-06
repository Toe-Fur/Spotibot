package com.example.bot;

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

    public static Result find(String artist, String title) {
        if (title == null || title.isBlank()) return null;
        String t = clean(title);
        String a = artist == null ? "" : artist.split(",| & | feat")[0].trim();
        try {
            Result r = search("track_name=" + enc(t) + (a.isEmpty() ? "" : "&artist_name=" + enc(a)));
            if (r == null) r = search("q=" + enc((t + " " + a).trim()));
            return r;
        } catch (Exception e) {
            logger.warn("lyrics lookup failed: {}", e.toString());
            return null;
        }
    }

    private static Result search(String query) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create("https://lrclib.net/api/search?" + query))
                .header("User-Agent", "Spotibot (https://github.com/Toe-Fur/Spotibot)")
                .timeout(Duration.ofSeconds(10)).build();
        HttpResponse<String> r = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
        if (r.statusCode() != 200) return null;
        JSONArray arr = new JSONArray(r.body());
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.getJSONObject(i);
            String plain = o.optString("plainLyrics", "");
            if (!plain.isBlank()) return new Result(o.optString("trackName"), o.optString("artistName"), plain.trim());
            if (o.optBoolean("instrumental")) return new Result(o.optString("trackName"), o.optString("artistName"), "(instrumental)");
        }
        return null;
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
