package com.example.bot;

import java.io.File;
import java.util.Map;

/**
 * External tools (yt-dlp, deno). The desktop app downloads them into <config>/bin; Docker images have them on PATH.
 */
public final class Tools {
    private Tools() {
    }

    public static File binDir() {
        return new File(ConfigUtils.CONFIG_FOLDER, "bin");
    }

    private static String exe(String name) {
        return DesktopMode.windows() ? name + ".exe" : name;
    }

    /** yt-dlp to run: the app's own copy if present, else whatever is on PATH. */
    public static String ytdlp() {
        File f = new File(binDir(), exe("yt-dlp"));
        return f.canExecute() ? f.getAbsolutePath() : "yt-dlp";
    }

    /** Let yt-dlp find deno/ffmpeg in the app's bin folder too. */
    public static ProcessBuilder withPath(ProcessBuilder pb) {
        File bin = binDir();
        if (bin.isDirectory()) {
            Map<String, String> env = pb.environment();
            String key = env.containsKey("Path") ? "Path" : "PATH";
            env.put(key, bin.getAbsolutePath() + File.pathSeparator + env.getOrDefault(key, ""));
        }
        return pb;
    }
}
