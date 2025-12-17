package com.example.bot;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;

public class ConfigUtils {
    private static final Logger logger = LoggerFactory.getLogger(ConfigUtils.class);

    // Use CONFIG_DIR if set, otherwise default to /app/config/ (Docker friendly)
    public static final String CONFIG_FOLDER =
            ensureTrailingSlash(System.getenv().getOrDefault("CONFIG_DIR", "/app/config/"));

    public static final String CONFIG_FILE_PATH = CONFIG_FOLDER + "config.json";
    public static final String COOKIE_FILE_PATH = CONFIG_FOLDER + "cookies.txt";
    public static final String BASE_DOWNLOAD_FOLDER = CONFIG_FOLDER + "downloads/";
    public static final int QUEUE_PAGE_SIZE = 5;

    public static String BOT_TOKEN;
    public static String STATUS;
    public static int defaultVolume = 60;
    public static String skipEmoji;
    public static String stopEmoji;

    private static String ensureTrailingSlash(String path) {
        if (path == null || path.isEmpty()) return "./config/";
        return path.endsWith("/") || path.endsWith("\\") ? path : path + "/";
    }

    public static void createConfigFolder() {
        File configFolder = new File(CONFIG_FOLDER);
        if (!configFolder.exists()) {
            boolean created = configFolder.mkdirs();
            if (created) logger.info("Created config folder: " + CONFIG_FOLDER);
            else logger.error("Failed to create config folder: " + CONFIG_FOLDER);
        }
    }

    public static void ensureConfigFileExists() {
        File configFile = new File(CONFIG_FILE_PATH);

        if (!configFile.exists()) {
            try (FileWriter writer = new FileWriter(configFile)) {
                writer.write("{\n" +
                        "  \"bot_token\": \"YOUR_BOT_TOKEN_HERE\",\n" +
                        "  \"status\": \"Playing music on Discord\",\n" +
                        "  \"default_volume\": 60,\n" +
                        "  \"queue_format\": {\n" +
                        "    \"now_playing\": \"🎶 **Now Playing:** {title}\",\n" +
                        "    \"queued\": \"📍 **{title}**\"\n" +
                        "  },\n" +
                        "  \"emojis\": {\n" +
                        "    \"skip\": \"⏩\",\n" +
                        "    \"stop\": \"⏹️\",\n" +
                        "    \"queue\": \"📝\"\n" +
                        "  }\n" +
                        "}");
                logger.info("Created default config.json file in the config folder.");
            } catch (IOException e) {
                logger.error("Failed to create config.json: " + e.getMessage(), e);
            }
        }
    }

    public static void loadConfig() {
        try {
            File configFile = new File(CONFIG_FILE_PATH);

            if (!configFile.exists()) {
                logger.error("Config file not found at: " + configFile.getAbsolutePath());
                System.exit(1);
            }

            ObjectMapper mapper = new ObjectMapper();
            JsonNode config = mapper.readTree(configFile);

            BOT_TOKEN = config.get("bot_token").asText();
            STATUS = config.get("status").asText();
            defaultVolume = config.get("default_volume").asInt(60);
            skipEmoji = config.get("emojis").get("skip").asText();
            stopEmoji = config.get("emojis").get("stop").asText();

            if (BOT_TOKEN == null || BOT_TOKEN.isEmpty() || BOT_TOKEN.equals("YOUR_BOT_TOKEN_HERE")) {
                logger.error("Bot token is missing in the config file.");
                System.exit(1);
            }

            logger.info("Loaded config successfully. Default volume set to: " + defaultVolume);
        } catch (IOException e) {
            logger.error("Failed to load config file: " + e.getMessage(), e);
            System.exit(1);
        }
    }
}
