package dev.agentcraft.gtnh.write.mc;

import java.io.File;

import net.minecraftforge.common.config.Configuration;

/**
 * config/agentcraftgtnhwrite.cfg, category "write". Server side values; nothing here is a secret
 * (the HMAC key lives in the file named by keyFile, never in this config).
 */
public final class WriteConfig {

    public static String owner = "";
    public static String controlUrl = "ws://127.0.0.1:7879/";
    public static String keyFile = "";
    public static int presenceRadius = 8;
    public static boolean lockAlsoLocksEdit = true;
    public static String auditLog = "agentcraft-write-audit.log";
    public static String lockFile = "write-lock.json";

    private WriteConfig() {}

    public static void load(File cfg) {
        Configuration c = new Configuration(cfg);
        String w = "write";
        owner = c.getString(
            "owner",
            w,
            owner,
            "Server: the ONE player allowed to write, as a lowercase version 4 UUID (not a name). The whitelist must hold exactly this UUID. Empty = writes stay disarmed.").trim();
        controlUrl = c.getString(
            "controlUrl",
            w,
            controlUrl,
            "Server: ws:// URL of the Hermes control service (NOT the read adapter's port). Loopback by default; reach another host through an SSH or VPN tunnel.").trim();
        keyFile = c.getString(
            "keyFile",
            w,
            keyFile,
            "Server: path of the HMAC key file (one line, 64+ hex characters). Refused when group or others can read or write it. The key is never put in this config.").trim();
        presenceRadius = c.getInt(
            "presenceRadius",
            w,
            presenceRadius,
            0,
            256,
            "Server: tier 2 requests (dispatch, restart, run) need the player within this many blocks of an HQ anchor in the HQ dimension. No anchors = refused. 0 turns the rule off.");
        lockAlsoLocksEdit = c.getBoolean(
            "lockAlsoLocksEdit",
            w,
            lockAlsoLocksEdit,
            "Server: while the write lock is set the card 6 office edit tool is locked too (one panic button).");
        auditLog = c.getString("auditLog", w, auditLog, "Server: write audit log, relative to the server directory (kept 0600, rolls at 8 MiB).").trim();
        lockFile = c.getString("lockFile", w, lockFile, "Server: where the write lock is persisted, relative to the server directory. An unreadable file counts as locked.").trim();
        if (c.hasChanged()) c.save();
    }
}
