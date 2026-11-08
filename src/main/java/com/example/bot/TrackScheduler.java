package com.example.bot;

import com.sedmelluq.discord.lavaplayer.player.AudioPlayer;
import com.sedmelluq.discord.lavaplayer.player.AudioPlayerManager;
import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import com.sedmelluq.discord.lavaplayer.track.AudioTrackEndReason;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.io.File;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.LinkedBlockingDeque;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.channel.middleman.GuildMessageChannel;
import com.sedmelluq.discord.lavaplayer.player.AudioLoadResultHandler;
import com.sedmelluq.discord.lavaplayer.track.AudioPlaylist;
import com.sedmelluq.discord.lavaplayer.tools.FriendlyException;
import java.awt.Color;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

public class TrackScheduler implements com.sedmelluq.discord.lavaplayer.player.event.AudioEventListener {
    private static final Logger logger = LoggerFactory.getLogger(TrackScheduler.class);

    private final Map<String, String> trackTitles = new ConcurrentHashMap<>();
    private final AudioPlayerManager playerManager;
    private final AudioPlayer player;
    private final LinkedBlockingDeque<AudioTrack> queue;
    /** Request info per loaded track (keyed by AudioTrack identity) for Save / Radio / panel labels. */
    private final Map<AudioTrack, StreamTrack> meta = java.util.Collections.synchronizedMap(new java.util.WeakHashMap<>());
    /** Video ids played or queued this session (radio avoids repeats). */
    private final StreamTrack.SongSet seen = new StreamTrack.SongSet();
    private volatile StreamTrack lastPlayed;
    private volatile boolean radioBusy;

    public enum Repeat { OFF, ONE, ALL }
    private volatile SoundFilters.Filter filter = SoundFilters.Filter.OFF;
    /** Members who voted to skip the current song. */
    private final java.util.Set<Long> skipVotes = ConcurrentHashMap.newKeySet();
    private volatile Repeat repeat = Repeat.OFF;
    private AudioTrack currentTrack;
    private final Guild guild;
    private final AtomicInteger pendingDownloadCount = new AtomicInteger(0);
    private volatile GuildMessageChannel notifyChannel;
    // Constructor to initialize the scheduler
    public TrackScheduler(AudioPlayerManager playerManager, AudioPlayer player, Spotibot bot, Guild guild) {
        this.playerManager = playerManager; // Initialize playerManager
        this.player = player;
        this.queue = new LinkedBlockingDeque<>();
        this.guild = guild;
        this.player.addListener(this); // Register this scheduler as an event listener
    }

    public Guild getGuild() {
        return guild;
    }

    public void setNotifyChannel(GuildMessageChannel channel) {
        this.notifyChannel = channel;
    }

    // Getter for the audio player
    public AudioPlayer getPlayer() {
        return player;
    }

    // Adds a track to the queue or plays it immediately if nothing is playing
    public void queue(AudioTrack track) {
        queue(track, -1);
    }

    /** position -1 = end of the queue, 0 = play next, n = after n waiting songs. */
    public synchronized void queue(AudioTrack track, int position) {
        track.setUserData(track.getIdentifier());
        String timestamp = getCurrentTimestamp();
        logger.info("[" + timestamp + "] Queueing track: " + track.getInfo().title);
        if (!player.startTrack(track, true)) {
            if (position < 0 || position >= queue.size()) {
                queue.offer(track);
            } else {
                List<AudioTrack> list = new ArrayList<>();
                queue.drainTo(list);
                list.add(position, track);
                queue.addAll(list);
            }
            logger.info("[" + timestamp + "] Track added to queue: " + track.getInfo().title);
            refreshPanel();
        } else {
            currentTrack = track;
            noteStarted(track);
            logger.info("[" + timestamp + "] Now playing: " + track.getInfo().title);
            refreshPanel();
        }
    }

    private void noteStarted(AudioTrack track) {
        skipVotes.clear();
        StreamTrack m = meta.get(track);
        if (m != null) {
            lastPlayed = m;
            History.record(guild.getIdLong(), m);
        }
    }

    public java.util.Set<Long> skipVotes() {
        return skipVotes;
    }

    public SoundFilters.Filter getFilter() {
        return filter;
    }

    public void setFilter(SoundFilters.Filter f) {
        filter = f == null ? SoundFilters.Filter.OFF : f;
        SoundFilters.apply(player, filter);
        refreshPanel();
    }

    /** Waiting songs with their request info (for the panel's queue picker). */
    public synchronized List<AudioTrack> snapshot() {
        return new ArrayList<>(queue);
    }

    public synchronized AudioTrack findQueued(long uid) {
        for (AudioTrack t : queue) {
            StreamTrack m = meta.get(t);
            if (m != null && m.uid == uid) return t;
        }
        return null;
    }

    /** Move a waiting song: to the front (play next) or the back; or remove it. Returns its title, or null. */
    public synchronized String moveQueued(long uid, String action) {
        AudioTrack t = findQueued(uid);
        if (t == null) return null;
        List<AudioTrack> list = new ArrayList<>();
        queue.drainTo(list);
        list.remove(t);
        switch (action) {
            case "next" -> list.add(0, t);
            case "last" -> list.add(t);
            default -> { } // remove
        }
        queue.addAll(list);
        refreshPanel();
        String title = trackTitles.get(t.getIdentifier());
        return title != null ? title : t.getInfo().title;
    }

    public StreamTrack metaOf(AudioTrack t) {
        return t == null ? null : meta.get(t);
    }

    public Repeat getRepeat() {
        return repeat;
    }

    public Repeat cycleRepeat() {
        repeat = Repeat.values()[(repeat.ordinal() + 1) % Repeat.values().length];
        refreshPanel();
        return repeat;
    }

    public void setRepeat(Repeat r) {
        repeat = r;
        refreshPanel();
    }

    /** Shuffle what's waiting in the queue (the current song keeps playing). */
    public int shuffle() {
        List<AudioTrack> list = new ArrayList<>();
        queue.drainTo(list);
        Collections.shuffle(list);
        queue.addAll(list);
        refreshPanel();
        return list.size();
    }

    /** Radio from the current (or last) song now, in front of anything already queued. */
    public StreamTrack radioSeed() {
        AudioTrack cur = player.getPlayingTrack();
        StreamTrack m = cur != null ? meta.get(cur) : null;
        return m != null ? m : lastPlayed;
    }

    public StreamTrack.SongSet seen() {
        return seen;
    }

    private void sendNowPlaying(AudioTrack track) {
        if (notifyChannel == null) return;
        String title = trackTitles.getOrDefault(track.getIdentifier(), track.getInfo().title);
        title = title.replace("ytsearch:", "").trim();
        EmbedBuilder eb = new EmbedBuilder()
                .setColor(new Color(0x1db954))
                .setTitle("🎵 Now Playing")
                .setDescription("**" + title + "**")
                .setFooter("!pause  •  !skip  •  !stop  •  !queue");
        long durationMs = track.getDuration();
        if (durationMs > 0 && durationMs != Long.MAX_VALUE) {
            long secs = durationMs / 1000;
            long h = secs / 3600, m = (secs % 3600) / 60, s = secs % 60;
            String dur = h > 0 ? String.format("%d:%02d:%02d", h, m, s) : String.format("%d:%02d", m, s);
            eb.addField("Duration", dur, true);
        }
        notifyChannel.sendMessageEmbeds(eb.build()).queue(null, e -> {});
    }

    // Method to get the file name of the currently playing track
    public String getCurrentTrackFileName() {
        if (currentTrack != null) {
            Object userData = currentTrack.getUserData();
            if (userData instanceof String) {
                String fileName = (String) userData;
                logger.info("Current track file name: " + fileName);
                return fileName;
            } else {
                logger.warn("Current track does not have a valid file name in user data.");
                return null;
            }
        } else {
            logger.warn("No track is currently playing.");
            return null;
        }
    }

    // Method to delete the file for the currently stored track file name
    public boolean deleteTrackFileByName() {
        if (currentTrack != null) {
            Object userData = currentTrack.getUserData();
            if (userData instanceof String) {
                String fileName = (String) userData;
                File file = new File(fileName);
                if (file.exists() && file.isFile()) {
                    if (file.delete()) {
                        logger.info("Successfully deleted file: " + fileName);
                        return true;
                    } else {
                        logger.error("Failed to delete file: " + fileName);
                        return false;
                    }
                } else {
                    logger.warn("File does not exist or is not a valid file: " + fileName);
                    return false;
                }
            } else {
                logger.warn("Current track does not have a valid file name in user data.");
                return false;
            }
        } else {
            logger.warn("No track is currently playing to delete.");
            return false;
        }
    }

    // Plays the next track in the queue or stops playback if the queue is empty
    public void nextTrack() {
        // Save the current track file name for deletion
        String previousTrackFileName = getCurrentTrackFileName();
        String timestamp = getCurrentTimestamp();
        logger.info("[" + timestamp + "] Moving to next track. Current track: " + (currentTrack != null ? currentTrack.getInfo().title : "None"));

        // Poll the next track from the queue
        currentTrack = queue.poll();

        if (currentTrack != null) {
            player.startTrack(currentTrack, false);
            noteStarted(currentTrack);
            logger.info("[" + timestamp + "] Started next track: " + currentTrack.getInfo().title);
        } else if (pendingDownloadCount.get() > 0) {
            // Downloads still in flight — stay in the channel and wait
            logger.info("[" + timestamp + "] Scheduler queue empty but {} download(s) still pending; staying in channel.", pendingDownloadCount.get());
        } else {
            // Nothing queued and nothing downloading — leave
            logger.info("[" + timestamp + "] Queue empty, no pending downloads. Staying in channel (idle timeout handles leaving).");
            maybeStartRadio();
        }

        refreshPanel();

        // Delete the file for the previous track
        if (previousTrackFileName != null && !previousTrackFileName.startsWith("http") && !stillQueued(previousTrackFileName)) {
            deleteFile(previousTrackFileName);
        }
    }

    /** Classic mode: a downloaded file may be queued again (repeat, same song twice) - keep it then. */
    private boolean stillQueued(String identifier) {
        if (repeat != Repeat.OFF) return true;
        AudioTrack cur = player.getPlayingTrack();
        if (cur != null && identifier.equals(cur.getIdentifier())) return true;
        for (AudioTrack t : queue) if (identifier.equals(t.getIdentifier())) return true;
        return false;
    }

    private boolean stillQueuedElsewhere(AudioTrack t) {
        for (AudioTrack q : queue) if (t.getIdentifier().equals(q.getIdentifier())) return true;
        return false;
    }

    // Helper method to delete a file
    private void deleteFile(String fileName) {
        if (fileName == null || fileName.startsWith("http")) return; // metzfam: streamed, nothing on disk
        File file = new File(fileName);
        if (file.exists() && file.isFile()) {
            if (file.delete()) {
                logger.info("Successfully deleted file: " + fileName);
            } else {
                logger.error("Failed to delete file: " + fileName);
            }
        } else {
            logger.warn("File does not exist or is not a valid file: " + fileName);
        }
    }

    // Add a method to get the title
    public String getTitle(String trackId) {
        return trackTitles.get(trackId);
    }

    public void queueSong(File file, String title) {
        playerManager.loadItem(file.getAbsolutePath(), new AudioLoadResultHandler() {
            @Override
            public void trackLoaded(AudioTrack track) {
                track.setUserData(file.getAbsolutePath());
                trackTitles.put(track.getIdentifier(), title.replace("ytsearch:", "").trim());
                queue(track);
                logger.info("Loaded and queued track: " + title);
                decrementPendingDownloads(); // decrement only after track is actually queued
            }

            @Override
            public void playlistLoaded(AudioPlaylist playlist) {
                logger.warn("Unexpected playlist loaded: " + playlist.getName());
                decrementPendingDownloads();
            }

            @Override
            public void noMatches() {
                logger.warn("No matches found for file: " + file.getAbsolutePath());
                decrementPendingDownloads();
            }

            @Override
            public void loadFailed(FriendlyException exception) {
                logger.error("Failed to load track: " + title, exception);
                decrementPendingDownloads();
            }
        });
    }

    /**
     * Load a stream URL or a downloaded file (blocking, keeps queue order). True if the track was queued.
     * next = put it at the front of the queue.
     */
    public boolean load(String identifier, StreamTrack req, java.util.function.IntSupplier position) {
        final boolean[] ok = {false};
        final String title = req.display;
        final String url = identifier;
        try {
            playerManager.loadItemOrdered(this, identifier, new AudioLoadResultHandler() {
                @Override
                public void trackLoaded(AudioTrack track) {
                    trackTitles.put(track.getIdentifier(), title);
                    meta.put(track, req);
                    seen.add(req);
                    queue(track, position == null ? -1 : position.getAsInt());
                    ok[0] = true;
                }

                @Override
                public void playlistLoaded(AudioPlaylist playlist) {
                    logger.warn("Unexpected playlist for stream {}", url);
                }

                @Override
                public void noMatches() {
                    logger.warn("No playable audio at {}", url);
                }

                @Override
                public void loadFailed(FriendlyException exception) {
                    logger.error("Failed to load stream: " + title, exception);
                }
            }).get(150, java.util.concurrent.TimeUnit.SECONDS);
        } catch (Exception e) {
            logger.error("Stream load error for {}: {}", title, e.toString());
        }
        return ok[0];
    }

    public void refreshPanel() {
        QueuePanel p = QueuePanel.peek(guild.getIdLong());
        if (p != null) {
            p.bindScheduler(this);
            p.update();
        }
    }

    /** Nothing left (e.g. the last pending song failed): start radio if it's on, else just refresh. */
    public void leaveIfIdle() {
        if (isQueueEmpty()) {
            maybeStartRadio();
            refreshPanel(); // the bot stays; VoiceSession leaves after the idle timeout
        }
    }

    /** Radio autoplay: when the queue runs dry, queue songs similar to the last one. */
    void maybeStartRadio() {
        if (radioBusy || !StreamTrack.enabled() || !GuildSettings.of(guild.getIdLong()).radio) return;
        StreamTrack seed = lastPlayed;
        QueuePanel panel = QueuePanel.peek(guild.getIdLong());
        Spotibot bot = VoiceSession.bot();
        if (seed == null || panel == null || panel.channel() == null || bot == null) return;
        radioBusy = true;
        java.util.concurrent.CompletableFuture.runAsync(() -> {
            try {
                List<StreamTrack> more = StreamTrack.radio(seed, 10, seen.ids(), seen);
                if (!more.isEmpty() && isQueueEmpty()) {
                    DownloadQueueHandler.queueTracks(more, "Radio: songs like " + seed.display, false,
                            this, panel.channel(), guild, bot.getDownloadQueue());
                }
            } finally {
                radioBusy = false;
            }
        });
    }

    // Called before a download is submitted so the scheduler knows a track is incoming
    public void incrementPendingDownloads() {
        pendingDownloadCount.incrementAndGet();
    }

    // Called after a download completes (success or failure)
    public void decrementPendingDownloads() {
        pendingDownloadCount.decrementAndGet();
    }

    // Checks if the queue is empty, no track is playing, and no downloads are pending
    public boolean isQueueEmpty() {
        return queue.isEmpty() && player.getPlayingTrack() == null && pendingDownloadCount.get() == 0;
    }

    // Deletes the file for the current track
    private void deleteCurrentTrackFile() {
        if (currentTrack != null && currentTrack.getIdentifier() != null && stillQueuedElsewhere(currentTrack)) {
            currentTrack = null;
            return;
        }
        if (currentTrack != null) {
            Object userData = currentTrack.getUserData();
            if (userData instanceof String) {
                String fileName = (String) userData;
                deleteFile(fileName);
            }
            currentTrack = null;
        }
    }

    public LinkedBlockingDeque<AudioTrack> getQueue() {
        return queue; // Expose the playback queue
    }

    public AudioTrack getCurrentTrack() {
        return currentTrack; // Expose the currently playing track
    }

    // Clears the queue, stops playback, and leaves the voice channel
    public void clearQueueAndStop() {
        lastPlayed = null; // Stop also stops radio
        // Stop the current track
        player.stopTrack();
        
        // Clear the playback queue
        queue.clear();
        
        // Reset the current track
        currentTrack = null;
        
        refreshPanel(); // metzfam: stay in the channel
    }

    // Disconnects the bot from the guild's voice channel
    private void leaveVoiceChannel() {
        logger.info("[VOICE] Queue empty — leaving voice channel for guild {}", guild.getId());
        guild.getAudioManager().closeAudioConnection();
    }

    // Handles events from the audio player
    @Override
    public void onEvent(com.sedmelluq.discord.lavaplayer.player.event.AudioEvent event) {
        if (event instanceof com.sedmelluq.discord.lavaplayer.player.event.TrackEndEvent) {
            AudioTrackEndReason reason = ((com.sedmelluq.discord.lavaplayer.player.event.TrackEndEvent) event).endReason;
            if (reason.mayStartNext) {
                AudioTrack ended = ((com.sedmelluq.discord.lavaplayer.player.event.TrackEndEvent) event).track;
                // Repeat: replay the same song, or send it to the back of the queue
                if (repeat != Repeat.OFF && ended != null) {
                    AudioTrack again = ended.makeClone();
                    StreamTrack m = meta.get(ended);
                    if (m != null) meta.put(again, m);
                    if (repeat == Repeat.ONE) {
                        currentTrack = again;
                        player.startTrack(again, false);
                        refreshPanel();
                        return;
                    }
                    queue.offer(again);
                } else {
                    deleteCurrentTrackFile();
                }
                nextTrack();
            } else {
                refreshPanel();
            }
        }
    }

    // Helper method to get the current timestamp
    private String getCurrentTimestamp() {
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
        return LocalDateTime.now().format(formatter);
    }
}