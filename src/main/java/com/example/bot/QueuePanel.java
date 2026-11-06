package com.example.bot;

import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.components.actionrow.ActionRow;
import net.dv8tion.jda.api.components.buttons.Button;
import net.dv8tion.jda.api.components.selections.StringSelectMenu;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.MessageEmbed;
import net.dv8tion.jda.api.entities.channel.middleman.GuildMessageChannel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.Color;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * ONE live message per server: now playing (with elapsed time), the queue (5 per page) and all the
 * controls as buttons. It is edited as songs load/play instead of posting a message per song.
 * Edits are coalesced (at most one every ~1.5 s) to stay under Discord's rate limits; while a song
 * plays the panel refreshes every 15 s so the elapsed time stays current.
 */
public final class QueuePanel {
    private static final Logger logger = LoggerFactory.getLogger(QueuePanel.class);
    private static final long DEBOUNCE_MS = 1500;

    public static final String BTN_PREV = CommandHandler.BTN_QUEUE_PREV;
    public static final String BTN_NEXT = CommandHandler.BTN_QUEUE_NEXT;

    private static final ScheduledExecutorService EXEC = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "queue-panel");
        t.setDaemon(true);
        return t;
    });
    private static final Map<Long, QueuePanel> PANELS = new ConcurrentHashMap<>();
    /** Link button target (guide + changelog). */
    public static final String GUIDE_URL = envOr("SPOTIBOT_GUIDE_URL", "https://github.com/Toe-Fur/Spotibot#readme");

    static {
        EXEC.scheduleAtFixedRate(() -> PANELS.values().forEach(p -> {
            TrackScheduler ts = p.scheduler;
            if (ts != null && ts.getPlayer().getPlayingTrack() != null && !ts.getPlayer().isPaused()) p.update();
        }), 15, 15, TimeUnit.SECONDS);
    }

    private final long guildId;
    private volatile GuildMessageChannel channel;
    private volatile TrackScheduler scheduler;
    private volatile long messageId;            // 0 = not sent yet
    private volatile boolean repost;            // next flush: delete old message, post a new one at the bottom
    private volatile boolean scheduled;
    private volatile boolean checkBottom;
    private volatile int page;
    private volatile String notice = "";        // e.g. "Added 50 songs from Spotify playlist"
    final AtomicInteger loading = new AtomicInteger();
    final AtomicInteger failed = new AtomicInteger();

    private QueuePanel(long guildId) {
        this.guildId = guildId;
    }

    public static QueuePanel of(long guildId) {
        return PANELS.computeIfAbsent(guildId, QueuePanel::new);
    }

    public static QueuePanel peek(long guildId) {
        return PANELS.get(guildId);
    }

    /** A new !play / !queue: bind channel + scheduler and move the panel to the bottom of the chat. */
    public void attach(GuildMessageChannel ch, TrackScheduler ts, String newNotice) {
        if (messageId != 0) {
            if (channel == null || ch.getIdLong() != channel.getIdLong()) repost = true;
            else checkBottom = true;   // checked on the panel thread (needs a REST call)
        }
        channel = ch;
        scheduler = ts;
        if (newNotice != null) notice = newNotice;
        update();
    }

    public void setNotice(String n) {
        notice = n == null ? "" : n;
        update();
    }

    /** Set the status line without scheduling an edit (the caller edits via the interaction). */
    public void setNoticeQuiet(String n) {
        notice = n == null ? "" : n;
    }

    public void bindScheduler(TrackScheduler ts) {
        scheduler = ts;
    }

    public int page() {
        return page;
    }

    public void setPage(int p) {
        page = Math.max(0, p);
    }

    /** Request a refresh; coalesced. */
    public void update() {
        if (channel == null) return;
        synchronized (this) {
            if (scheduled) return;
            scheduled = true;
        }
        EXEC.schedule(this::flush, DEBOUNCE_MS, TimeUnit.MILLISECONDS);
    }

    /** !stop: freeze the panel as "Stopped" and forget it. */
    /** Bot left the voice channel: freeze the panel with the reason and forget it. */
    public static void left(long guildId, String reason) {
        QueuePanel p = PANELS.remove(guildId);
        if (p == null || p.channel == null || p.messageId == 0) return;
        EmbedBuilder eb = new EmbedBuilder().setColor(new Color(0x535353)).setTitle("Left the voice channel")
                .setDescription(reason == null ? "" : "Reason: " + reason)
                .setFooter("!join or !play <song> to bring me back");
        p.channel.editMessageEmbedsById(p.messageId, eb.build()).setComponents().queue(null, e -> {});
    }

    public GuildMessageChannel channel() {
        return channel;
    }

    private void flush() {
        synchronized (this) {
            scheduled = false;
        }
        GuildMessageChannel ch = channel;
        if (ch == null) return;
        try {
            MessageEmbed embed = render().build();
            List<ActionRow> rows = components();
            if (checkBottom && messageId != 0 && !isNearBottom(ch)) repost = true;
            checkBottom = false;
            if (repost && messageId != 0) {
                long old = messageId;
                messageId = 0;
                ch.deleteMessageById(old).queue(null, e -> {});
            }
            repost = false;
            if (messageId != 0) {
                try {
                    ch.editMessageEmbedsById(messageId, embed).setComponents(rows).complete();
                    return;
                } catch (Exception e) {
                    logger.info("Queue panel edit failed ({}), posting a new one", e.getMessage());
                    messageId = 0;
                }
            }
            Message m = ch.sendMessageEmbeds(embed).setComponents(rows).complete();
            messageId = m.getIdLong();
        } catch (Exception e) {
            logger.warn("Queue panel update failed: {}", e.toString());
        }
    }

    private boolean isNearBottom(GuildMessageChannel ch) {
        try {
            for (Message m : ch.getHistory().retrievePast(4).complete()) {
                if (m.getIdLong() == messageId) return true;
            }
        } catch (Exception ignored) {
        }
        return false;
    }

    private List<AudioTrack> upcoming() {
        TrackScheduler ts = scheduler;
        return ts == null ? List.of() : new ArrayList<>(ts.getQueue());
    }

    private int totalPages(int total) {
        return Math.max(1, (int) Math.ceil(total / (double) ConfigUtils.QUEUE_PAGE_SIZE));
    }

    public EmbedBuilder render() {
        TrackScheduler ts = scheduler;
        List<AudioTrack> list = upcoming();
        int total = list.size();
        int pages = totalPages(total);
        if (page >= pages) page = pages - 1;

        EmbedBuilder eb = new EmbedBuilder().setColor(new Color(0x1db954));
        AudioTrack cur = ts == null ? null : ts.getPlayer().getPlayingTrack();
        if (cur != null) {
            boolean paused = ts.getPlayer().isPaused();
            eb.setTitle(paused ? "Paused" : "Now Playing");
            eb.appendDescription("**" + titleOf(ts, cur) + "**\n`" + dur(cur.getPosition()) + " / " + dur(cur.getDuration()) + "`  "
                    + bar(cur.getPosition(), cur.getDuration()) + tag(ts, cur) + "\n");
        } else if (loading.get() > 0) {
            eb.setTitle("Starting...");
            eb.appendDescription("_Getting the first song ready..._\n");
        } else {
            eb.setTitle("Nothing playing");
        }

        eb.appendDescription("\n**Up next**\n");
        if (total == 0) {
            eb.appendDescription(loading.get() > 0 ? "_Loading..._" : "_Queue is empty._");
        } else {
            int start = page * ConfigUtils.QUEUE_PAGE_SIZE;
            int end = Math.min(start + ConfigUtils.QUEUE_PAGE_SIZE, total);
            for (int i = start; i < end; i++) {
                AudioTrack t = list.get(i);
                eb.appendDescription("`" + (i + 1) + ".` " + titleOf(ts, t) + "  `" + dur(t.getDuration()) + "`" + tag(ts, t) + "\n");
            }
        }

        StringBuilder foot = new StringBuilder("Page " + (page + 1) + "/" + pages + "  |  " + total + " queued");
        if (ts != null && ts.getRepeat() != TrackScheduler.Repeat.OFF)
            foot.append("  |  repeat ").append(ts.getRepeat() == TrackScheduler.Repeat.ONE ? "song" : "queue");
        if (loading.get() > 0) foot.append("  |  ").append(loading.get()).append(" loading");
        if (failed.get() > 0) foot.append("  |  ").append(failed.get()).append(" not found");
        if (!notice.isBlank()) foot.append("\n").append(notice);
        VoiceSession vs = VoiceSession.peek(guildId);
        if (vs != null && ts != null && VoiceSession.isConnected(ts.getGuild())) foot.append("\n").append(vs.statusLine());
        foot.append("\n/play or !play <song or link>  |  !queue brings this panel down");
        eb.setFooter(foot.toString());
        return eb;
    }

    public List<ActionRow> components() {
        TrackScheduler ts = scheduler;
        AudioTrack cur = ts == null ? null : ts.getPlayer().getPlayingTrack();
        int total = upcoming().size();
        List<ActionRow> rows = new ArrayList<>();
        // Row 1: playback
        if (cur != null) {
            boolean paused = ts.getPlayer().isPaused();
            boolean seek = cur.isSeekable();
            rows.add(ActionRow.of(
                    Button.secondary(PlayerControls.BTN_SEEK + "-10000", "-10s").withDisabled(!seek),
                    Button.secondary(PlayerControls.BTN_SEEK + "-5000", "-5s").withDisabled(!seek),
                    paused ? Button.success(PlayerControls.BTN_PAUSE, "Resume") : Button.primary(PlayerControls.BTN_PAUSE, "Pause"),
                    Button.secondary(PlayerControls.BTN_SEEK + "5000", "+5s").withDisabled(!seek),
                    Button.secondary(PlayerControls.BTN_SEEK + "10000", "+10s").withDisabled(!seek)));
        }
        boolean connected = ts != null && VoiceSession.isConnected(ts.getGuild());
        if (cur != null || total > 0 || loading.get() > 0 || connected) {
            // Row 2: queue
            int pages = totalPages(total);
            List<Button> r2 = new ArrayList<>();
            r2.add(Button.secondary(PlayerControls.BTN_SKIP, "Skip").withDisabled(cur == null));
            r2.add(Button.secondary(PlayerControls.BTN_STOP, "Stop").withDisabled(cur == null && total == 0 && loading.get() == 0));
            r2.add(Button.danger(PlayerControls.BTN_LEAVE, "Leave"));
            if (total > ConfigUtils.QUEUE_PAGE_SIZE) {
                r2.add(Button.secondary(BTN_PREV, "◀ Prev").withDisabled(page <= 0));
                r2.add(Button.secondary(BTN_NEXT, "Next ▶").withDisabled(page >= pages - 1));
            }
            rows.add(ActionRow.of(r2));

            // Row 3: modes (only the ones this install supports)
            GuildSettings gs = GuildSettings.of(guildId);
            List<Button> r3 = new ArrayList<>();
            if (VoiceListener.available()) r3.add(toggle(PlayerControls.BTN_GAMING, "Gaming mode", gs.gamingMode));
            if (StreamTrack.enabled()) r3.add(toggle(PlayerControls.BTN_RADIO, "Radio", gs.radio));
            r3.add(Button.secondary(PlayerControls.BTN_SHUFFLE, "Shuffle").withDisabled(total < 2));
            TrackScheduler.Repeat rp = ts == null ? TrackScheduler.Repeat.OFF : ts.getRepeat();
            String rl = switch (rp) { case OFF -> "Repeat: off"; case ONE -> "Repeat: song"; case ALL -> "Repeat: queue"; };
            r3.add(rp == TrackScheduler.Repeat.OFF ? Button.secondary(PlayerControls.BTN_REPEAT, rl) : Button.success(PlayerControls.BTN_REPEAT, rl));
            if (JellyfinClient.enabled()) r3.add(Button.secondary(PlayerControls.BTN_SAVE, "Save").withDisabled(cur == null));
            rows.add(ActionRow.of(r3));

            // Row 4: idle timer (how long the bot waits in the channel with nothing playing before it leaves)
            int cur0 = gs.idleMinutes;
            StringSelectMenu.Builder menu = StringSelectMenu.create(PlayerControls.MENU_IDLE).setPlaceholder("Leave after idle");
            for (int m : GuildSettings.IDLE_CHOICES) {
                String label = m > 0 ? "Leave after " + GuildSettings.idleLabel(m) + " idle" : "Never leave on idle (only when dismissed or alone)";
                menu.addOption(label, String.valueOf(m));
            }
            if (java.util.Arrays.stream(GuildSettings.IDLE_CHOICES).noneMatch(m -> m == cur0)) {
                menu.addOption("Leave after " + GuildSettings.idleLabel(cur0) + " idle", String.valueOf(cur0));
            }
            menu.setDefaultValues(String.valueOf(cur0));
            rows.add(ActionRow.of(menu.build()));
        }
        // Row 4: guide + changelog
        rows.add(ActionRow.of(Button.link(GUIDE_URL, "Guide & updates")));
        return rows;
    }

    private static Button toggle(String id, String label, boolean on) {
        return on ? Button.success(id, label + ": on") : Button.secondary(id, label + ": off");
    }

    private static String tag(TrackScheduler ts, AudioTrack t) {
        StreamTrack m = ts == null ? null : ts.metaOf(t);
        return m != null && m.fromRadio ? "  _(radio)_" : "";
    }

    private static String bar(long pos, long dur) {
        if (dur <= 0 || dur == Long.MAX_VALUE) return "";
        int n = 12, f = (int) Math.min(n, Math.round(n * (double) pos / dur));
        return "`" + "━".repeat(f) + "○" + "─".repeat(n - f) + "`";
    }

    private static String envOr(String k, String d) {
        String v = System.getenv(k);
        return v == null || v.isBlank() ? d : v.trim();
    }

    /** Called when all work for this panel is finished and the bot leaves. */
    public void reset() {
        loading.set(0);
        failed.set(0);
        notice = "";
        page = 0;
    }

    private static String titleOf(TrackScheduler ts, AudioTrack t) {
        String title = ts == null ? null : ts.getTitle(t.getIdentifier());
        if (title == null) title = t.getInfo().title;
        title = title == null ? "Unknown" : title.replace("ytsearch:", "").trim();
        return title.length() > 80 ? title.substring(0, 77) + "..." : title;
    }

    private static String dur(long ms) {
        if (ms <= 0 || ms == Long.MAX_VALUE) return "--:--";
        long s = ms / 1000, h = s / 3600, m = (s % 3600) / 60, sec = s % 60;
        return h > 0 ? String.format("%d:%02d:%02d", h, m, sec) : String.format("%d:%02d", m, sec);
    }

    long guildId() {
        return guildId;
    }
}
