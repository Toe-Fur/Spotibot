package com.example.bot;

import java.io.File;

/**
 * Desktop app (Windows / Linux installer) support. Enabled by the installer's launcher with
 * -Dspotibot.desktop=true; Docker and plain `java -jar` runs are unaffected.
 */
public final class DesktopMode {
    private DesktopMode() {
    }

    public static boolean enabled() {
        return Boolean.getBoolean("spotibot.desktop");
    }

    public static boolean windows() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }

    /** %APPDATA%\Spotibot on Windows, $XDG_CONFIG_HOME/spotibot or ~/.config/spotibot on Linux. */
    public static String defaultConfigDir() {
        if (windows()) {
            String appData = System.getenv("APPDATA");
            if (appData == null) appData = System.getProperty("user.home") + File.separator + "AppData" + File.separator + "Roaming";
            return appData + File.separator + "Spotibot" + File.separator;
        }
        String xdg = System.getenv("XDG_CONFIG_HOME");
        String base = xdg != null && !xdg.isBlank() ? xdg : System.getProperty("user.home") + "/.config";
        return base + "/spotibot/";
    }
}
