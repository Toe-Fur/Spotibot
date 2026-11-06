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
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Optional Jellyfin integration (set JELLYFIN_URL, JELLYFIN_API_KEY, JELLYFIN_USER):
 *  - play your Jellyfin playlists ("!playlist Vibes", "Spotibot, play my Vibes playlist")
 *  - Save button: favorites the song in Jellyfin. With the JellyMusicDiscovery plugin that also
 *    downloads songs you don't own yet into the music library.
 */
public final class JellyfinClient {
    private static final Logger logger = LoggerFactory.getLogger(JellyfinClient.class);
    private static final String URL = env("JELLYFIN_URL").replaceAll("/+$", "");
    private static final String KEY = env("JELLYFIN_API_KEY");
    private static final String USER = env("JELLYFIN_USER");
    private static final HttpClient HTTP = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(5)).build();

    private static volatile String userId;
    private static volatile List<Playlist> playlistCache = List.of();
    private static volatile long playlistCacheAt;

    public record Playlist(String id, String name, int count) {
    }

    private JellyfinClient() {
    }

    public static boolean enabled() {
        return !URL.isEmpty() && !KEY.isEmpty();
    }

    public static List<Playlist> playlists() {
        if (!enabled()) return List.of();
        if (System.currentTimeMillis() - playlistCacheAt < 10 * 60_000 && !playlistCache.isEmpty()) return playlistCache;
        try {
            JSONObject r = get("/Users/" + user() + "/Items?IncludeItemTypes=Playlist&Recursive=true&Fields=ChildCount&SortBy=SortName");
            List<Playlist> out = new ArrayList<>();
            JSONArray items = r.optJSONArray("Items");
            for (int i = 0; items != null && i < items.length(); i++) {
                JSONObject it = items.getJSONObject(i);
                out.add(new Playlist(it.getString("Id"), it.optString("Name"), it.optInt("ChildCount", 0)));
            }
            playlistCache = out;
            playlistCacheAt = System.currentTimeMillis();
        } catch (Exception e) {
            logger.warn("Jellyfin playlists failed: {}", e.toString());
        }
        return playlistCache;
    }

    /** Best playlist for a spoken/typed name: exact, then starts-with, then contains, then word overlap. */
    public static Playlist findPlaylist(String query) {
        String q = norm(query).replaceAll("^(my|the) ", "").replaceAll(" (playlist|play list|mix)$", "").trim();
        if (q.isEmpty()) return null;
        Playlist best = null;
        int bestScore = 0;
        for (Playlist p : playlists()) {
            String n = norm(p.name());
            int s = n.equals(q) ? 100 : n.startsWith(q) ? 80 : n.contains(q) ? 60 : overlap(n, q);
            if (s > bestScore) {
                bestScore = s;
                best = p;
            }
        }
        return bestScore >= 30 ? best : null;
    }

    public static List<String> suggestPlaylists(String prefix) {
        String q = norm(prefix);
        return playlists().stream()
                .filter(p -> q.isEmpty() || norm(p.name()).contains(q))
                .sorted(Comparator.comparing((Playlist p) -> !norm(p.name()).startsWith(q)).thenComparing(Playlist::name))
                .map(Playlist::name).limit(25).toList();
    }

    /** Songs in a playlist as "title SEP artist" requests for the stream server. */
    public static List<String> playlistTracks(Playlist p) {
        List<String> out = new ArrayList<>();
        try {
            JSONObject r = get("/Playlists/" + p.id() + "/Items?UserId=" + user());
            JSONArray items = r.optJSONArray("Items");
            for (int i = 0; items != null && i < items.length(); i++) {
                JSONObject it = items.getJSONObject(i);
                String title = it.optString("Name", "").trim();
                String artist = it.optString("AlbumArtist", "");
                if (artist.isBlank()) {
                    JSONArray a = it.optJSONArray("Artists");
                    artist = a != null && a.length() > 0 ? a.optString(0) : "";
                }
                if (!title.isEmpty()) out.add(title + StreamTrack.SEP + artist);
            }
        } catch (Exception e) {
            logger.warn("Jellyfin playlist items failed: {}", e.toString());
        }
        return out;
    }

    /**
     * Favorite a song in Jellyfin. Searches the library (the discovery plugin also returns songs you
     * don't own yet) and hearts the best match. Returns the matched "title - artist", or null.
     */
    public static String favorite(String artist, String title) {
        if (!enabled() || title == null || title.isBlank()) return null;
        try {
            String term = (title + " " + (artist == null ? "" : artist)).trim();
            JSONObject r = get("/Users/" + user() + "/Items?IncludeItemTypes=Audio&Recursive=true&Limit=8&searchTerm=" + enc(term));
            JSONArray items = r.optJSONArray("Items");
            if (items == null || items.isEmpty()) return null;
            JSONObject best = null;
            int bestScore = -1;
            for (int i = 0; i < items.length(); i++) {
                JSONObject it = items.getJSONObject(i);
                String n = norm(it.optString("Name")), a = norm(it.optString("AlbumArtist"));
                int s = (n.equals(norm(title)) ? 4 : n.contains(norm(title)) ? 2 : 0)
                        + (artist != null && !artist.isBlank() && a.contains(norm(artist)) ? 3 : 0);
                if (s > bestScore) {
                    bestScore = s;
                    best = it;
                }
            }
            HttpRequest req = HttpRequest.newBuilder(URI.create(URL + "/Users/" + user() + "/FavoriteItems/" + best.getString("Id")))
                    .header("X-Emby-Token", KEY).timeout(Duration.ofSeconds(15))
                    .POST(HttpRequest.BodyPublishers.noBody()).build();
            int code = HTTP.send(req, HttpResponse.BodyHandlers.discarding()).statusCode();
            if (code >= 300) return null;
            return best.optString("Name") + " - " + best.optString("AlbumArtist");
        } catch (Exception e) {
            logger.warn("Jellyfin favorite failed: {}", e.toString());
            return null;
        }
    }

    private static String user() throws Exception {
        if (userId != null) return userId;
        HttpRequest req = HttpRequest.newBuilder(URI.create(URL + "/Users")).header("X-Emby-Token", KEY)
                .timeout(Duration.ofSeconds(10)).build();
        JSONArray users = new JSONArray(HTTP.send(req, HttpResponse.BodyHandlers.ofString()).body());
        for (int i = 0; i < users.length(); i++) {
            JSONObject u = users.getJSONObject(i);
            if (USER.isEmpty() || u.optString("Name").equalsIgnoreCase(USER)) {
                userId = u.getString("Id");
                return userId;
            }
        }
        throw new IllegalStateException("Jellyfin user not found: " + USER);
    }

    private static JSONObject get(String path) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create(URL + path)).header("X-Emby-Token", KEY)
                .timeout(Duration.ofSeconds(20)).build();
        HttpResponse<String> r = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
        if (r.statusCode() != 200) throw new IllegalStateException("HTTP " + r.statusCode() + " for " + path.split("\\?")[0]);
        return new JSONObject(r.body());
    }

    private static int overlap(String a, String b) {
        List<String> wa = List.of(a.split(" ")), wb = List.of(b.split(" "));
        long common = wb.stream().filter(w -> w.length() > 1 && wa.contains(w)).count();
        return wb.isEmpty() ? 0 : (int) (50 * common / wb.size());
    }

    static String norm(String s) {
        return s == null ? "" : s.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]+", " ").trim();
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    private static String env(String k) {
        String v = System.getenv(k);
        return v == null ? "" : v.trim();
    }
}
