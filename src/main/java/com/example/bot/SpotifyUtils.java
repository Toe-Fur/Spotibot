package com.example.bot;

import java.util.*;
import java.io.*;
import okhttp3.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

public class SpotifyUtils {
    private static final org.slf4j.Logger logger = org.slf4j.LoggerFactory.getLogger(SpotifyUtils.class);
    private static final String CONFIG_PATH = "config/spotifyconfig.json";
    private static final MediaType FORM = MediaType.get("application/x-www-form-urlencoded");

    private static String CLIENT_ID;
    private static String CLIENT_SECRET;
    private static String accessToken;

    static {
        try {
            ObjectMapper mapper = new ObjectMapper();
            JsonNode config = mapper.readTree(new File(CONFIG_PATH));
            CLIENT_ID = config.get("client_id").asText();
            CLIENT_SECRET = config.get("client_secret").asText();
        } catch (IOException e) {
            throw new RuntimeException("Failed to load Spotify configuration", e);
        }
    }

    public static void authenticate() throws IOException {
        OkHttpClient client = new OkHttpClient();

        String credentials = CLIENT_ID + ":" + CLIENT_SECRET;
        String encodedCredentials = Base64.getEncoder().encodeToString(credentials.getBytes());

        String tokenUrl = "https://accounts.spotify.com/api/token";
        RequestBody body = RequestBody.create("grant_type=client_credentials", FORM);

        Request request = new Request.Builder()
            .url(tokenUrl)
            .post(body)
            .addHeader("Authorization", "Basic " + encodedCredentials)
            .build();

        try (Response response = client.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                logger.error("Failed to authenticate: " + response.body().string());
                throw new IOException("Unexpected response code: " + response.code());
            }

            String responseBody = response.body().string();
            JsonNode json = new ObjectMapper().readTree(responseBody);
            accessToken = json.get("access_token").asText();
            logger.info("Successfully authenticated. Access Token: " + accessToken);
        }
    }

    private static void authenticateIfNeeded() throws IOException {
        if (accessToken == null || isTokenExpired()) {
            authenticate();
        }
    }

    private static boolean isTokenExpired() {
        // Implement token expiration logic if needed
        return false;
    }

    public static List<String> getPlaylistTracks(String playlistId) throws IOException {
        authenticateIfNeeded();

        List<String> trackTitles = new ArrayList<>();
        String url = "https://api.spotify.com/v1/playlists/" + playlistId + "/tracks?limit=20";
        logger.info("Constructed API URL: " + url);

        OkHttpClient client = new OkHttpClient();

        while (url != null) {
            // Build the HTTP request
            Request request = new Request.Builder()
                .url(url)
                .get()
                .addHeader("Authorization", "Bearer " + accessToken)
                .build();

            // Execute the request and process the response
            try (Response response = client.newCall(request).execute()) {
                if (!response.isSuccessful()) {
                    logger.error("Failed Spotify API Request. Response Code: " + response.code());
                    logger.error("Response Body: " + response.body().string());
                    throw new IOException("Unexpected response code: " + response.code());
                }

                // Parse the response body
                String responseBody = response.body().string();
                JsonNode json = new ObjectMapper().readTree(responseBody);
                logger.info("Spotify API Response: " + responseBody);

                // Extract track titles
                if (json.has("items")) {
                    json.get("items").forEach(item -> {
                        JsonNode track = item.get("track");
                        if (track != null && track.has("name")) {
                            String trackName = track.get("name").asText();
                            String artistName = track.get("artists").get(0).get("name").asText();
                            trackTitles.add(trackName + " by " + artistName);
                        } else {
                            logger.warn("Track information is missing in API response.");
                        }
                    });
                }

                // Check for the next page of results
                url = json.has("next") && !json.get("next").isNull() ? json.get("next").asText() : null;
                logger.info("Next URL: " + (url != null ? url : "No more pages."));
            }
        }

        return trackTitles;
    }

    public static String getTrackTitle(String trackId) throws IOException {
        authenticateIfNeeded();

        String url = "https://api.spotify.com/v1/tracks/" + trackId;
        logger.info("Constructed API URL: " + url);

        OkHttpClient client = new OkHttpClient();
        Request request = new Request.Builder()
            .url(url)
            .get()
            .addHeader("Authorization", "Bearer " + accessToken)
            .build();

        try (Response response = client.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                logger.error("Failed Spotify API Request. Response Code: " + response.code());
                logger.error("Response Body: " + response.body().string());
                throw new IOException("Unexpected response code: " + response.code());
            }

            String responseBody = response.body().string();
            JsonNode json = new ObjectMapper().readTree(responseBody);
            return json.get("name").asText() + " by " + json.get("artists").get(0).get("name").asText();
        }
    }
}
