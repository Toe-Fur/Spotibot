package com.example.bot;

import java.io.BufferedInputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

public class StreamingDownloader {
    /**
     * Downloads a track and provides an InputStream for playback.
     *
     * @param trackUrl The URL of the track to be downloaded.
     * @return An InputStream for the downloaded track.
     * @throws Exception If an error occurs during the download.
     */
    public static InputStream getStream(String trackUrl) throws Exception {
        URL url = new URL(trackUrl);
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setRequestMethod("GET");
        connection.setDoInput(true);
        connection.connect();

        if (connection.getResponseCode() != HttpURLConnection.HTTP_OK) {
            throw new Exception("Failed to connect: " + connection.getResponseMessage());
        }

        return new BufferedInputStream(connection.getInputStream());
    }
}
