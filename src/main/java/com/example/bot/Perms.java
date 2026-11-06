package com.example.bot;

import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Role;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashSet;
import java.util.Set;

/**
 * DJ mode (off by default; only the server owner or the bot's owner can turn it on).
 * When on, only DJs can stop, leave, force-skip, remove other people's songs, change modes/filters/volume
 * and settings. Everyone can still add songs, vote to skip, and skip or remove songs they added.
 * DJs = members with the DJ role (the role chosen in /dj, or a role named "DJ"), anyone with Manage Server,
 * the server owner, and the bot owner.
 */
public final class Perms {
    private static final Logger logger = LoggerFactory.getLogger(Perms.class);
    private static final Set<Long> BOT_OWNERS = new HashSet<>();

    private Perms() {
    }

    /** Bot owner(s): the Discord application's owner (or team members) plus BOT_OWNER_IDS. */
    public static void loadOwners(JDA jda) {
        String env = System.getenv("BOT_OWNER_IDS");
        if (env != null) {
            for (String s : env.split("[,\\s]+")) {
                try {
                    if (!s.isBlank()) BOT_OWNERS.add(Long.parseLong(s.trim()));
                } catch (NumberFormatException ignored) {
                }
            }
        }
        jda.retrieveApplicationInfo().queue(info -> {
            if (info.getTeam() != null) info.getTeam().getMembers().forEach(m -> BOT_OWNERS.add(m.getUser().getIdLong()));
            else BOT_OWNERS.add(info.getOwner().getIdLong());
            logger.info("Bot owner(s): {}", BOT_OWNERS);
        }, e -> logger.warn("Could not read application owner: {}", e.toString()));
    }

    public static boolean isBotOwner(long userId) {
        return BOT_OWNERS.contains(userId);
    }

    /** Server owner or bot owner: the only people who can switch DJ mode on/off. */
    public static boolean canManageDj(Member m) {
        return m != null && (m.isOwner() || isBotOwner(m.getIdLong()));
    }

    public static boolean isDj(Member m) {
        if (m == null) return false;
        if (m.isOwner() || isBotOwner(m.getIdLong()) || m.hasPermission(Permission.MANAGE_SERVER)) return true;
        GuildSettings gs = GuildSettings.of(m.getGuild().getIdLong());
        for (Role r : m.getRoles()) {
            if (gs.djRoleId != 0 ? r.getIdLong() == gs.djRoleId : r.getName().equalsIgnoreCase("DJ")) return true;
        }
        return false;
    }

    /** True if DJ mode is off or the member is a DJ. */
    public static boolean allowed(Member m) {
        return m == null ? false : !GuildSettings.of(m.getGuild().getIdLong()).djMode || isDj(m);
    }

    public static String djRoleName(Guild g) {
        GuildSettings gs = GuildSettings.of(g.getIdLong());
        if (gs.djRoleId != 0) {
            Role r = g.getRoleById(gs.djRoleId);
            if (r != null) return r.getName();
        }
        return "DJ";
    }

    public static String denied(Guild g) {
        return "DJ mode is on: only people with the " + djRoleName(g) + " role can do that";
    }
}
