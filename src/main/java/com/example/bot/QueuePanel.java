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
    /** At most one panel edit per this many ms per server (Discord rate-limits message edits; 429s were common). */
    private static final long MIN_INTERVAL_MS = 3000;

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
    private volatile long lastFlush;
    private volatile String lastSent = "";      // rendered panel last sent; identical renders are skipped
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
        GuildSettings gs = GuildSettings.of(guildId);
        if (gs.recapChannelId != ch.getIdLong()) {   // weekly recap goes where the music happens
            gs.recapChannelId = ch.getIdLong();
            GuildSettings.save();
        }
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
        long wait = Math.max(DEBOUNCE_MS, lastFlush + MIN_INTERVAL_MS - System.currentTimeMillis());
        EXEC.schedule(this::flush, wait, TimeUnit.MILLISECONDS);
    }

    /** A button handler just edited the panel itself (interaction reply): count it toward the edit rate. */
    public void touched() {
        lastFlush = System.currentTimeMillis();
        lastSent = "";
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
            String sig = embed.toData().toString() + rows;
            if (messageId != 0 && sig.equals(lastSent)) return; // nothing changed
            lastFlush = System.currentTimeMillis();
            if (messageId != 0) {
                try {
                    ch.editMessageEmbedsById(messageId, embed).setComponents(rows).complete();
                    lastSent = sig;
                    return;
                } catch (Exception e) {
                    logger.info("Queue panel edit failed ({}), posting a new one", e.getMessage());
                    messageId = 0;
                }
            }
            Message m = ch.sendMessageEmbeds(embed).setComponents(rows).complete();
            messageId = m.getIdLong();
            lastSent = sig;
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
        List<AudioTrack> list = upcoming();
        int total = list.size();
        int pages = totalPages(total);
        boolean connected = ts != null && VoiceSession.isConnected(ts.getGuild());
        GuildSettings gs = GuildSettings.of(guildId);
        List<ActionRow> rows = new ArrayList<>();

        if (cur != null || total > 0 || loading.get() > 0 || connected) {
            // Row 1: playback
            boolean paused = cur != null && ts.getPlayer().isPaused();
            int votes = ts == null ? 0 : ts.skipVotes().size();
            rows.add(ActionRow.of(
                    paused ? Button.success(PlayerControls.BTN_PAUSE, "Resume") : Button.primary(PlayerControls.BTN_PAUSE, "Pause").withDisabled(cur == null),
                    Button.secondary(PlayerControls.BTN_SKIP, votes > 0 ? "Skip (" + votes + " voted)" : "Skip").withDisabled(cur == null),
                    Button.secondary(PlayerControls.BTN_STOP, "Stop").withDisabled(cur == null && total == 0 && loading.get() == 0),
                    Button.danger(PlayerControls.BTN_LEAVE, "Leave"),
                    Button.secondary(PlayerControls.BTN_LYRICS, "Lyrics").withDisabled(cur == null)));

            // Row 2: modes (only the ones this install supports)
            List<Button> r2 = new ArrayList<>();
            r2.add(Button.secondary(PlayerControls.BTN_SHUFFLE, "Shuffle").withDisabled(total < 2));
            TrackScheduler.Repeat rp = ts == null ? TrackScheduler.Repeat.OFF : ts.getRepeat();
            String rl = switch (rp) { case OFF -> "Repeat: off"; case ONE -> "Repeat: song"; case ALL -> "Repeat: queue"; };
            r2.add(rp == TrackScheduler.Repeat.OFF ? Button.secondary(PlayerControls.BTN_REPEAT, rl) : Button.success(PlayerControls.BTN_REPEAT, rl));
            if (StreamTrack.enabled()) r2.add(toggle(PlayerControls.BTN_RADIO, "Radio", gs.radio));
            if (VoiceListener.available()) r2.add(toggle(PlayerControls.BTN_GAMING, "Gaming", gs.gamingMode));
            if (JellyfinClient.enabled()) r2.add(Button.secondary(PlayerControls.BTN_SAVE, "Save").withDisabled(cur == null));
            rows.add(ActionRow.of(r2));

            // Row 3: pick a waiting song to move or remove
            if (total > 0) {
                int start = page * ConfigUtils.QUEUE_PAGE_SIZE;
                StringSelectMenu.Builder q = StringSelectMenu.create(PlayerControls.MENU_QUEUE)
                        .setPlaceholder("Pick a song in the queue to move or remove");
                for (int i = start; i < Math.min(total, start + 25); i++) {
                    StreamTrack m = ts.metaOf(list.get(i));
                    if (m == null) continue;
                    String label = (i + 1) + ". " + titleOf(ts, list.get(i));
                    q.addOption(label.length() > 100 ? label.substring(0, 97) + "..." : label, String.valueOf(m.uid),
                            m.requesterName == null ? (m.fromRadio ? "radio" : null) : "added by " + m.requesterName);
                }
                if (!q.getOptions().isEmpty()) rows.add(ActionRow.of(q.build()));
            }
        }

        // Row 4: paging, more controls, guide
        List<Button> r4 = new ArrayList<>();
        if (total > ConfigUtils.QUEUE_PAGE_SIZE) {
            r4.add(Button.secondary(BTN_PREV, "◀ Prev").withDisabled(page <= 0));
            r4.add(Button.secondary(BTN_NEXT, "Next ▶").withDisabled(page >= pages - 1));
        }
        if (ts != null && (cur != null || connected)) r4.add(Button.secondary(PlayerControls.BTN_MORE, "More"));
        r4.add(Button.link(GUIDE_URL, "Guide & updates"));
        rows.add(ActionRow.of(r4));
        return rows;
    }

    /** "More" opens privately (only the person who pressed it sees it): seek, filters, volume, idle timer, history. */
    public EmbedBuilder moreEmbed() {
        TrackScheduler ts = scheduler;
        GuildSettings gs = GuildSettings.of(guildId);
        AudioTrack cur = ts == null ? null : ts.getPlayer().getPlayingTrack();
        EmbedBuilder eb = new EmbedBuilder().setColor(new Color(0x2b2d31)).setTitle("More controls");
        if (cur != null) eb.setDescription("**" + titleOf(ts, cur) + "**  `" + dur(cur.getPosition()) + " / " + dur(cur.getDuration()) + "`");
        eb.addField("Sound", ts == null ? "Normal" : ts.getFilter().label, true);
        eb.addField("Volume", ts == null ? "-" : String.valueOf(ts.getPlayer().getVolume()), true);
        eb.addField("Leave when idle", GuildSettings.idleLabel(gs.idleMinutes), true);
        if (gs.djMode) eb.addField("DJ mode", "on (" + Perms.djRoleName(ts.getGuild()) + " role)", true);
        if (!notice.isBlank()) eb.setFooter(notice);
        return eb;
    }

    public List<ActionRow> moreComponents() {
        TrackScheduler ts = scheduler;
        AudioTrack cur = ts == null ? null : ts.getPlayer().getPlayingTrack();
        boolean seek = cur != null && cur.isSeekable();
        GuildSettings gs = GuildSettings.of(guildId);
        List<ActionRow> rows = new ArrayList<>();
        rows.add(ActionRow.of(
                Button.secondary(PlayerControls.BTN_SEEK + "-10000", "-10s").withDisabled(!seek),
                Button.secondary(PlayerControls.BTN_SEEK + "-5000", "-5s").withDisabled(!seek),
                Button.secondary(PlayerControls.BTN_RESTART, "Restart song").withDisabled(!seek),
                Button.secondary(PlayerControls.BTN_SEEK + "5000", "+5s").withDisabled(!seek),
                Button.secondary(PlayerControls.BTN_SEEK + "10000", "+10s").withDisabled(!seek)));
        StringSelectMenu.Builder f = StringSelectMenu.create(PlayerControls.MENU_FILTER).setPlaceholder("Sound filter");
        for (SoundFilters.Filter x : SoundFilters.Filter.values()) f.addOption(x.label, x.name());
        f.setDefaultValues((ts == null ? SoundFilters.Filter.OFF : ts.getFilter()).name());
        rows.add(ActionRow.of(f.build()));
        int cur0 = gs.idleMinutes;
        StringSelectMenu.Builder menu = StringSelectMenu.create(PlayerControls.MENU_IDLE).setPlaceholder("Leave after idle");
        for (int m : GuildSettings.IDLE_CHOICES) {
            menu.addOption(m > 0 ? "Leave after " + GuildSettings.idleLabel(m) + " idle" : "Never leave on idle (only when dismissed or alone)", String.valueOf(m));
        }
        if (java.util.Arrays.stream(GuildSettings.IDLE_CHOICES).noneMatch(m -> m == cur0)) {
            menu.addOption("Leave after " + GuildSettings.idleLabel(cur0) + " idle", String.valueOf(cur0));
        }
        menu.setDefaultValues(String.valueOf(cur0));
        rows.add(ActionRow.of(menu.build()));
        rows.add(ActionRow.of(
                Button.secondary(PlayerControls.BTN_VOL + "-10", "Volume -"),
                Button.secondary(PlayerControls.BTN_VOL + "10", "Volume +"),
                Button.secondary(PlayerControls.BTN_HISTORY, "History"),
                gs.djMode ? Button.success(PlayerControls.BTN_DJ, "DJ mode: on") : Button.secondary(PlayerControls.BTN_DJ, "DJ mode: off")));
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
