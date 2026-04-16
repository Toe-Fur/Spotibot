package com.example.bot;

import java.util.*;
import java.io.*;
import okhttp3.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.json.JSONObject;
import org.json.JSONArray;

public class SpotifyUtils {
    private static final org.slf4j.Logger logger = org.slf4j.LoggerFactory.getLogger(SpotifyUtils.class);
    private static final String CONFIG_PATH = "config/spotifyconfig.json";
    private static final MediaType FORM = MediaType.get("application/x-www-form-urlencoded");
    private static final OkHttpClient HTTP = new OkHttpClient();
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static String CLIENT_ID;
    private static String CLIENT_SECRET;
    private static boolean configLoaded = false;
    private static boolean configAvailable = false;

    private static String accessToken;
    private static long tokenExpiresAt = 0; // epoch ms

    private static synchronized void ensureConfig() {
        if (configLoaded) return;
        configLoaded = true;
        try {
            JsonNode config = MAPPER.readTree(new File(CONFIG_PATH));
            CLIENT_ID = config.path("client_id").asText(null);
            CLIENT_SECRET = config.path("client_secret").asText(null);
            configAvailable = CLIENT_ID != null && !CLIENT_ID.isBlank()
                           && CLIENT_SECRET != null && !CLIENT_SECRET.isBlank();
            if (configAvailable) logger.info("Spotify config loaded.");
            else logger.warn("spotifyconfig.json missing client_id/client_secret — Spotify disabled.");
        } catch (Exception e) {
            logger.warn("spotifyconfig.json not found or invalid — Spotify disabled. ({})", e.getMessage());
        }
    }

    public static synchronized void authenticate() throws IOException {
        ensureConfig();
        if (!configAvailable) throw new IOException("Spotify not configured.");

        String encoded = Base64.getEncoder()
                .encodeToString((CLIENT_ID + ":" + CLIENT_SECRET).getBytes());

        RequestBody body = RequestBody.create("grant_type=client_credentials", FORM);
        Request request = new Request.Builder()
                .url("https://accounts.spotify.com/api/token")
                .post(body)
                .addHeader("Authorization", "Basic " + encoded)
                .build();

        try (Response response = HTTP.newCall(request).execute()) {
            if (!response.isSuccessful())
                throw new IOException("Spotify auth failed: HTTP " + response.code());

            JsonNode json = MAPPER.readTree(response.body().string());
            accessToken = json.get("access_token").asText();
            int expiresIn = json.path("expires_in").asInt(3600);
            // Renew 60 s before actual expiry to avoid mid-request failures
            tokenExpiresAt = System.currentTimeMillis() + (expiresIn - 60) * 1000L;
            logger.info("Spotify authenticated. Token valid for {} s.", expiresIn);
        }
    }

    private static synchronized void authenticateIfNeeded() throws IOException {
        if (accessToken == null || System.currentTimeMillis() >= tokenExpiresAt) {
            authenticate();
        }
    }

    public static List<String> getPlaylistTracks(String playlistId) throws IOException {
        ensureConfig();
        if (!configAvailable) throw new IOException("Spotify not configured.");
        authenticateIfNeeded();

        List<String> trackTitles = new ArrayList<>();
        String url = "https://api.spotify.com/v1/playlists/" + playlistId + "/tracks?limit=50";

        while (url != null) {
            Request request = new Request.Builder()
                    .url(url)
                    .get()
                    .addHeader("Authorization", "Bearer " + accessToken)
                    .build();

            try (Response response = HTTP.newCall(request).execute()) {
                if (response.code() == 401) {
                    // Token expired mid-session — re-auth once and retry
                    authenticate();
                    return getPlaylistTracks(playlistId);
                }
                if (!response.isSuccessful())
                    throw new IOException("Spotify API error: HTTP " + response.code());

                JsonNode json = MAPPER.readTree(response.body().string());
                if (json.has("items")) {
                    json.get("items").forEach(item -> {
                        JsonNode track = item.get("track");
                        if (track != null && !track.isNull() && track.has("name")) {
                            String name   = track.path("name").asText("Unknown");
                            String artist = track.path("artists").path(0).path("name").asText("Unknown");
                            trackTitles.add(name + " " + artist);
                        }
                    });
                }
                url = json.has("next") && !json.get("next").isNull()
                        ? json.get("next").asText() : null;
            }
        }

        return trackTitles;
    }

    public static String getTrackTitle(String trackId) throws IOException {
        ensureConfig();
        if (!configAvailable) throw new IOException("Spotify not configured.");
        authenticateIfNeeded();

        Request request = new Request.Builder()
                .url("https://api.spotify.com/v1/tracks/" + trackId)
                .get()
                .addHeader("Authorization", "Bearer " + accessToken)
                .build();

        try (Response response = HTTP.newCall(request).execute()) {
            if (response.code() == 401) { authenticate(); return getTrackTitle(trackId); }
            if (!response.isSuccessful())
                throw new IOException("Spotify API error: HTTP " + response.code());

            JsonNode json = MAPPER.readTree(response.body().string());
            String name   = json.path("name").asText("Unknown");
            String artist = json.path("artists").path(0).path("name").asText("Unknown");
            return name + " " + artist;
        }
    }

    public static String extractSpotifyId(String url) {
        if (url.contains("/track/"))    return url.split("/track/")[1].split("\\?")[0];
        if (url.contains("/playlist/")) return url.split("/playlist/")[1].split("\\?")[0];
        return null;
    }

    public static boolean isPlaylist(String input) {
        return input.contains("list=");
    }

    public static List<String> getYouTubePlaylistTitles(String playlistUrl) throws IOException {
        List<String> titles = new ArrayList<>();
        ProcessBuilder pb = new ProcessBuilder("yt-dlp", "--flat-playlist", "-J", playlistUrl);
        Process process = pb.start();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            StringBuilder json = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) json.append(line);
            JSONArray entries = new JSONObject(json.toString()).getJSONArray("entries");
            for (int i = 0; i < entries.length(); i++)
                titles.add(entries.getJSONObject(i).getString("title"));
        }
        return titles;
    }

    public static String getYouTubeTitle(String videoUrl) throws IOException {
        ProcessBuilder pb = new ProcessBuilder("yt-dlp", "--get-title", videoUrl);
        Process process = pb.start();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            return reader.readLine();
        }
    }
}
