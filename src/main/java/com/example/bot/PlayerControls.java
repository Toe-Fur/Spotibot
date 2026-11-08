package com.example.bot;

import com.sedmelluq.discord.lavaplayer.player.AudioPlayer;
import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.channel.middleman.GuildMessageChannel;

import java.util.concurrent.TimeUnit;

/**
 * metzfam: playback actions shared by the queue panel buttons and the typed commands.
 * Each returns a short status line for the panel footer, or null when nothing is playing.
 */
public final class PlayerControls {
    public static final String BTN_PREFIX = "sb:";
    public static final String BTN_PAUSE = "sb:pause";
    public static final String BTN_SKIP = "sb:skip";
    public static final String BTN_STOP = "sb:stop";
    public static final String BTN_LEAVE = "sb:leave";
    public static final String BTN_GAMING = "sb:gaming";
    public static final String BTN_RADIO = "sb:radio";
    public static final String BTN_SHUFFLE = "sb:shuffle";
    public static final String BTN_REPEAT = "sb:repeat";
    public static final String BTN_SAVE = "sb:save";
    public static final String MENU_IDLE = "sb:idle";
    public static final String BTN_MORE = "sb:more";
    public static final String BTN_LYRICS = "sb:lyrics";
    public static final String BTN_HISTORY = "sb:history";
    public static final String BTN_RESTART = "sb:restart";
    public static final String BTN_VOL = "sb:vol:";          // + signed step
    public static final String MENU_FILTER = "sb:filter";
    public static final String MENU_QUEUE = "sb:queue";
    public static final String MENU_HISTORY = "sb:histpick";
    public static final String MENU_SEARCH = "sb:pick";
    public static final String BTN_DJ = "sb:dj";
    public static final String BTN_QACT = "sb:q:";            // + action:uid  (next | last | remove)

    /** Actions only DJs may do while DJ mode is on (everyone can add songs, vote skip, lyrics, history, save). */
    private static final java.util.Set<String> DJ_ONLY = java.util.Set.of(
            "stop", "leave", "pause", "seek", "shuffle", "repeat", "radio", "gaming", "filter", "volume", "idle", "settings", "restart");

    /** null = allowed; otherwise the message to show. */
    public static String check(net.dv8tion.jda.api.entities.Member m, String action) {
        if (m == null) return null;
        if (DJ_ONLY.contains(action) && !Perms.allowed(m)) return Perms.denied(m.getGuild());
        return null;
    }

    /** People listening with the bot (not bots, not deafened). */
    public static int listeners(Guild g) {
        var ch = g.getAudioManager().getConnectedChannel();
        if (ch == null) return 0;
        return (int) ch.getMembers().stream()
                .filter(x -> !x.getUser().isBot() && (x.getVoiceState() == null || !x.getVoiceState().isDeafened())).count();
    }

    /**
     * Skip with voting: with 3+ listeners a majority must vote, unless you asked for this song or you're a DJ
     * (server managers/owner always count as DJs).
     */
    public static String skipBy(TrackScheduler ts, net.dv8tion.jda.api.entities.Member m) {
        AudioTrack cur = ts.getPlayer().getPlayingTrack();
        if (cur == null) return null;
        String who = m == null ? null : m.getEffectiveName();
        int n = listeners(ts.getGuild());
        StreamTrack meta = ts.metaOf(cur);
        boolean own = m != null && meta != null && meta.requesterId == m.getIdLong();
        if (m == null || n < 3 || own || Perms.isDj(m)) return skip(ts, who);
        ts.skipVotes().add(m.getIdLong());
        int need = n / 2 + 1;
        if (ts.skipVotes().size() >= need) return skip(ts, who) + " - vote passed";
        return "Vote to skip: " + ts.skipVotes().size() + "/" + need + by(who);
    }

    /** DJ mode on/off: server owner or bot owner only. */
    public static String djMode(net.dv8tion.jda.api.entities.Member m, Boolean on) {
        if (m == null || !Perms.canManageDj(m)) return "Only the server owner can turn DJ mode on or off";
        GuildSettings gs = GuildSettings.of(m.getGuild().getIdLong());
        gs.djMode = on == null ? !gs.djMode : on;
        GuildSettings.save();
        if (!gs.djMode) return "DJ mode off: everyone can use all controls" + by(m.getEffectiveName());
        boolean roleExists = gs.djRoleId != 0 ? m.getGuild().getRoleById(gs.djRoleId) != null
                : !m.getGuild().getRolesByName("DJ", true).isEmpty();
        return "DJ mode on: only the " + Perms.djRoleName(m.getGuild()) + " role (and server managers) can stop, skip without a vote, "
                + "change modes and remove other people's songs" + (roleExists ? "" : ". Create a role named DJ (or pick one with /dj role)")
                + by(m.getEffectiveName());
    }

    public static String filter(TrackScheduler ts, SoundFilters.Filter f, String who) {
        ts.setFilter(f);
        return (f == SoundFilters.Filter.OFF ? "Sound filter off" : "Sound filter: " + f.label) + by(who);
    }

    public static String restartSong(TrackScheduler ts, String who) {
        AudioTrack cur = ts.getPlayer().getPlayingTrack();
        if (cur == null) return null;
        if (!cur.isSeekable()) return "This song can't be restarted";
        cur.setPosition(0);
        return "Restarted the song" + by(who);
    }

    /** Move/remove a queued song; you can always act on songs you added, others need DJ rights in DJ mode. */
    public static String queueAction(TrackScheduler ts, net.dv8tion.jda.api.entities.Member m, long uid, String action) {
        AudioTrack t = ts.findQueued(uid);
        if (t == null) return "That song isn't in the queue any more";
        StreamTrack meta = ts.metaOf(t);
        boolean own = m != null && meta != null && meta.requesterId == m.getIdLong();
        if (!own && m != null && !Perms.allowed(m)) return Perms.denied(m.getGuild());
        String title = ts.moveQueued(uid, action);
        String who = m == null ? null : m.getEffectiveName();
        return switch (action) {
            case "next" -> "Up next: " + title;
            case "last" -> "Moved to the end: " + title;
            default -> "Removed " + title;
        } + by(who);
    }
    public static final String BTN_SEEK = "sb:seek:"; // + signed milliseconds

    private PlayerControls() {
    }

    public static String togglePause(TrackScheduler ts, String who) {
        AudioPlayer p = ts.getPlayer();
        if (p.getPlayingTrack() == null) return null;
        p.setPaused(!p.isPaused());
        return (p.isPaused() ? "Paused" : "Resumed") + by(who);
    }

    public static String setPaused(TrackScheduler ts, boolean paused, String who) {
        AudioPlayer p = ts.getPlayer();
        if (p.getPlayingTrack() == null) return null;
        if (p.isPaused() == paused) return paused ? "Already paused" : "Not paused";
        p.setPaused(paused);
        return (paused ? "Paused" : "Resumed") + by(who);
    }

    public static String skip(TrackScheduler ts, String who) {
        AudioTrack cur = ts.getPlayer().getPlayingTrack();
        if (cur == null) return null;
        String title = ts.getTitle(cur.getIdentifier());
        ts.getPlayer().setPaused(false);
        if (!ts.getQueue().isEmpty()) {
            ts.nextTrack();
        } else {
            // Nothing queued yet (maybe still loading): stop this song; the next one starts when it arrives.
            ts.getPlayer().stopTrack();
            ts.leaveIfIdle();
        }
        return "Skipped " + (title != null ? title : cur.getInfo().title) + by(who);
    }

    public static String seek(TrackScheduler ts, long deltaMs, String who) {
        AudioTrack cur = ts.getPlayer().getPlayingTrack();
        if (cur == null) return null;
        if (!cur.isSeekable()) return "This song can't be seeked";
        long dur = cur.getDuration();
        long max = dur > 0 && dur != Long.MAX_VALUE ? Math.max(0, dur - 1000) : Long.MAX_VALUE;
        long pos = Math.max(0, Math.min(max, cur.getPosition() + deltaMs));
        cur.setPosition(pos);
        return (deltaMs < 0 ? "Back " : "Forward ") + Math.abs(deltaMs / 1000) + "s to " + clock(pos) + by(who);
    }

    public static String volume(TrackScheduler ts, int vol, String who) {
        ts.getPlayer().setVolume(vol);
        return "Volume " + vol + by(who);
    }

    public static String shuffle(TrackScheduler ts, String who) {
        int n = ts.shuffle();
        return n < 2 ? "Not enough songs queued to shuffle" : "Shuffled " + n + " songs" + by(who);
    }

    public static String repeat(TrackScheduler ts, TrackScheduler.Repeat mode, String who) {
        TrackScheduler.Repeat r = mode == null ? ts.cycleRepeat() : mode;
        if (mode != null) ts.setRepeat(mode);
        return switch (r) {
            case OFF -> "Repeat off";
            case ONE -> "Repeating this song";
            case ALL -> "Repeating the queue";
        } + by(who);
    }

    public static String gaming(Guild guild, Boolean on, String who) {
        if (!VoiceListener.available()) return "Gaming mode needs a speech server (STT_URL) - see the guide";
        GuildSettings gs = GuildSettings.of(guild.getIdLong());
        gs.gamingMode = on == null ? !gs.gamingMode : on;
        GuildSettings.save();
        VoiceSession.get(guild).listener.setEnabled(gs.gamingMode);
        return "Gaming mode " + (gs.gamingMode ? "on: say \"Spotibot, play <song>\"" : "off") + by(who);
    }

    public static String radio(TrackScheduler ts, Boolean on, String who) {
        if (!StreamTrack.enabled()) return "Radio needs streaming mode (STREAM_SERVER_URL) - see the guide";
        GuildSettings gs = GuildSettings.of(ts.getGuild().getIdLong());
        gs.radio = on == null ? !gs.radio : on;
        GuildSettings.save();
        if (gs.radio) ts.leaveIfIdle(); // starts radio right away if nothing is queued
        return "Radio " + (gs.radio ? "on: similar songs play when the queue runs out" : "off") + by(who);
    }

    /** "Play something like this": queue radio songs for the current song right after it. */
    public static String radioNow(TrackScheduler ts, GuildMessageChannel ch, String who) {
        if (!StreamTrack.enabled()) return "Radio needs streaming mode (STREAM_SERVER_URL)";
        StreamTrack seed = ts.radioSeed();
        if (seed == null) return null;
        Spotibot bot = VoiceSession.bot();
        java.util.concurrent.CompletableFuture.runAsync(() -> {
            var more = StreamTrack.radio(seed, 10, ts.seen().ids(), ts.seen());
            if (more.isEmpty()) {
                QueuePanel p = QueuePanel.peek(ts.getGuild().getIdLong());
                if (p != null) p.setNotice("No similar songs found for " + seed.display);
                return;
            }
            DownloadQueueHandler.queueTracks(more, "Radio: songs like " + seed.display + by(who), true,
                    ts, ch, ts.getGuild(), bot.getDownloadQueue());
        });
        return "Finding songs like " + seed.display + "...";
    }

    /** Save = favorite in Jellyfin (with the discovery plugin, songs you don't own get downloaded). */
    public static String save(TrackScheduler ts, String who) {
        if (!JellyfinClient.enabled()) return "Save needs Jellyfin (JELLYFIN_URL) - see the guide";
        AudioTrack cur = ts.getPlayer().getPlayingTrack();
        if (cur == null) return null;
        StreamTrack m = ts.metaOf(cur);
        String title = m != null ? m.songTitle : cur.getInfo().title;
        String artist = m != null ? m.songArtist : "";
        if (title == null || title.isBlank()) title = ts.getTitle(cur.getIdentifier());
        String saved = JellyfinClient.favorite(artist, title);
        return saved == null ? "Couldn't find " + title + " in Jellyfin" : "Saved " + saved + " to Jellyfin favorites" + by(who);
    }

    /** Idle timer from the panel dropdown (minutes, 0 = never). */
    public static String setIdle(Guild guild, int minutes, String who) {
        GuildSettings gs = GuildSettings.of(guild.getIdLong());
        gs.idleMinutes = Math.max(0, Math.min(240, minutes));
        GuildSettings.save();
        VoiceSession.get(guild).touch(); // the new timer counts from now
        return (gs.idleMinutes > 0 ? "Leaves after " + GuildSettings.idleLabel(gs.idleMinutes) + " with nothing playing"
                : "Won't leave on idle (only when dismissed or everyone leaves)") + by(who);
    }

    public static String nowPlaying(TrackScheduler ts) {
        AudioTrack cur = ts.getPlayer().getPlayingTrack();
        if (cur == null) return null;
        String t = ts.getTitle(cur.getIdentifier());
        return "Now playing: " + (t != null ? t : cur.getInfo().title) + "  (" + clock(cur.getPosition()) + " / " + clock(cur.getDuration()) + ")";
    }

    /** Short-lived reply for when there is no panel to show a status on. */
    public static void flash(GuildMessageChannel ch, String text) {
        ch.sendMessage(text).queue(m -> m.delete().queueAfter(6, TimeUnit.SECONDS, null, e -> {}), e -> {});
    }

    public static String serverFolder(Guild guild) {
        return ConfigUtils.BASE_DOWNLOAD_FOLDER + guild.getId() + "/";
    }

    static String clock(long ms) {
        long s = ms / 1000, h = s / 3600, m = (s % 3600) / 60, sec = s % 60;
        return h > 0 ? String.format("%d:%02d:%02d", h, m, sec) : String.format("%d:%02d", m, sec);
    }

    private static String by(String who) {
        return who == null || who.isBlank() ? "" : " (" + who + ")";
    }
}
