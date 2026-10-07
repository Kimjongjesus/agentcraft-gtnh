package dev.agentcraft.gtnh;

import java.io.File;

import net.minecraftforge.common.config.Configuration;

public class Config {

    public static boolean enabled = true;
    public static String adapterUrl = "ws://127.0.0.1:7878";
    public static String[] displayAgents = new String[0];
    public static int maxAgents = 12;
    public static int spawnDimension = 0;
    public static boolean spawnAtWorldSpawn = true;
    public static int spawnX = 0;
    public static int spawnY = -1;
    public static int spawnZ = 0;
    public static int spacing = 2;
    public static boolean verboseLog = true;
    public static String anchorsFile = "config/agentcraftgtnh/hq-anchors.json";
    public static int monitorLines = 9;
    public static int monitorRenderDistance = 24;
    public static double walkSpeed = 0.3D;
    public static int teleportAfterSeconds = 12;
    public static int boardSyncSeconds = 2;
    public static int wallRenderDistance = 32;
    public static int wallPageSeconds = 8;
    // card 6: the office edit tool
    public static boolean editEnabled = true;
    public static int editOpLevel = 2;
    public static String[] editPlayers = new String[0];
    public static String[] editProtected = new String[0];
    public static int editMaxPanels = 256;
    public static int editMaxLayoutKB = 256;
    public static int editUndoSteps = 64;
    public static double editPerSecond = 4;
    public static int editMaxImportSpan = 48;
    public static String editAuditLog = "agentcraft-edit-audit.log";
    // card 5b: ops feeds + decision toast
    public static boolean opsFeeds = true;
    public static int opsSyncSeconds = 2;
    public static boolean toastEnabled = true;
    public static float toastVolume = 0.8F;
    public static String toastSound = "note.pling";
    public static float toastPitch = 1.2F;
    public static int toastSeconds = 9;
    public static int toastMaxAgeMinutes = 30;
    public static int toastCooldownMinutes = 10;
    public static int toastPerMinute = 3;

    public static void synchronizeConfiguration(File configFile) {
        Configuration c = new Configuration(configFile);
        String ops = "ops";
        opsFeeds = c.getBoolean(
            "opsFeeds",
            ops,
            opsFeeds,
            "Server: ask the adapter for the ops.* feeds (fleet health, jobs, provider usage, alerts) for the ops panels. Read-only.");
        opsSyncSeconds = c.getInt("opsSyncSeconds", ops, opsSyncSeconds, 1, 30, "Server: ops panel data is sent to clients at most this often, and only when it changed.");
        String toast = "toast";
        toastEnabled = c.getBoolean(
            "enabled",
            toast,
            toastEnabled,
            "Client: a HUD toast + sound when a NEW decision or approval needs you (alerts and ops events never toast). /agentcraft toast mute|unmute also works.");
        toastVolume = c.getFloat("volume", toast, toastVolume, 0.0F, 1.0F, "Client: toast sound volume (0 = silent).");
        toastSound = c.getString(
            "sound",
            toast,
            toastSound,
            "Client: sound event for the toast. Default is a vanilla sound (no asset needed); any sound event name works, e.g. from a resource pack (\"mypack:ding\").");
        toastPitch = c.getFloat("pitch", toast, toastPitch, 0.5F, 2.0F, "Client: toast sound pitch.");
        toastSeconds = c.getInt("seconds", toast, toastSeconds, 3, 60, "Client: how long a toast stays on screen.");
        toastMaxAgeMinutes = c.getInt(
            "maxAgeMinutes",
            toast,
            toastMaxAgeMinutes,
            1,
            1440,
            "Client: a decision older than this when the client first sees it is not toasted (no replay of old decisions after joining).");
        toastCooldownMinutes = c.getInt("cooldownMinutes", toast, toastCooldownMinutes, 1, 1440, "Client: a decision that closed and re-opened toasts again only after this long.");
        toastPerMinute = c.getInt("perMinute", toast, toastPerMinute, 1, 20, "Client: at most this many toasts per minute; the rest are folded into \"+N more\".");
        String cat = "bridge";
        enabled = c.getBoolean("enabled", cat, enabled, "Connect to the Hermes adapter and show agents.");
        adapterUrl = c.getString(
            "adapterUrl",
            cat,
            adapterUrl,
            "WebSocket URL of the Hermes adapter (AgentCraft protocol v1). ws:// only. The adapter must allowlist this server's IP.");
        verboseLog = c.getBoolean("verboseLog", cat, verboseLog, "Log every agent state change to the server log.");
        String place = "display";
        displayAgents = c.getStringList(
            "displayAgents",
            place,
            displayAgents,
            "Agent ids (Hermes profile names) to show as NPCs, in order. Empty = every agent (most urgent first), up to maxAgents.");
        maxAgents = c.getInt(
            "maxAgents",
            place,
            maxAgents,
            0,
            64,
            "Maximum number of agent NPCs in the world. Agents over the cap are counted on the overflow sign.");
        spawnDimension = c.getInt(
            "spawnDimension",
            place,
            spawnDimension,
            -1000,
            1000,
            "Dimension for the NPCs while hq-anchors.json has no anchors (the anchors file names its own dimension).");
        spawnAtWorldSpawn = c.getBoolean(
            "spawnAtWorldSpawn",
            place,
            spawnAtWorldSpawn,
            "Only while hq-anchors.json has no anchors at all: a row next to the world spawn. false = use spawnX/Y/Z.");
        spawnX = c.getInt("spawnX", place, spawnX, -30000000, 30000000, "Used when spawnAtWorldSpawn=false.");
        spawnY = c.getInt("spawnY", place, spawnY, -1, 255, "-1 = top solid block at X/Z.");
        spawnZ = c.getInt("spawnZ", place, spawnZ, -30000000, 30000000, "Used when spawnAtWorldSpawn=false.");
        spacing = c.getInt("spacing", place, spacing, 1, 16, "Blocks between NPCs in the no-anchors row (along +X).");
        String hq = "hq";
        anchorsFile = c.getString(
            "anchorsFile",
            hq,
            anchorsFile,
            "Station anchors (JSON), relative to the server directory. Edit by hand or with /agentcraft anchor set <name>.");
        walkSpeed = c.getFloat("walkSpeed", hq, (float) walkSpeed, 0.1F, 0.6F, "NPC walking speed (movement attribute).");
        teleportAfterSeconds = c.getInt(
            "teleportAfterSeconds",
            hq,
            teleportAfterSeconds,
            3,
            120,
            "An NPC with no path, or no progress for this long, is teleported to its spot.");
        monitorLines = c.getInt("monitorLines", hq, monitorLines, 2, 24, "Log lines sent to (and shown on) a desk monitor.");
        monitorRenderDistance = c.getInt(
            "monitorRenderDistance",
            hq,
            monitorRenderDistance,
            4,
            128,
            "Client: monitors and lamps draw only within this many blocks of the player.");
        String gui = "interface";
        boardSyncSeconds = c.getInt(
            "boardSyncSeconds",
            gui,
            boardSyncSeconds,
            1,
            30,
            "Server: task wall / atrium / library data is sent to clients at most this often, and only when it changed.");
        wallRenderDistance = c.getInt(
            "wallRenderDistance",
            gui,
            wallRenderDistance,
            4,
            128,
            "Client: task walls and atrium panels draw only within this many blocks of the player.");
        wallPageSeconds = c.getInt(
            "wallPageSeconds",
            gui,
            wallPageSeconds,
            2,
            120,
            "Client: a task wall column with more cards than fit flips to its next page this often.");
        String ed = "edit";
        editEnabled = c.getBoolean(
            "enabled",
            ed,
            editEnabled,
            "Card 6 office edit tool. false = the tool, the edit commands and every layout write are off (anchor/bind commands still work as before).");
        editOpLevel = c.getInt("opLevel", ed, editOpLevel, 1, 4, "Op permission level needed to use the edit tool and /agentcraft edit (and to toggle edit mode).");
        editPlayers = c.getStringList(
            "allowedPlayers",
            ed,
            editPlayers,
            "If not empty: only these player names (who must also be ops at opLevel) may edit. The server console always may.");
        editProtected = c.getStringList(
            "protectedAreas",
            ed,
            editProtected,
            "Exclusion points in the HQ dimension, \"x,y,z,radius # note\": the tool never places or removes anything within radius blocks. Empty by default.");
        editMaxPanels = c.getInt("maxPanels", ed, editMaxPanels, 1, 4096, "Most panels one layout may hold (placements over it are refused).");
        editMaxLayoutKB = c.getInt("maxLayoutKB", ed, editMaxLayoutKB, 16, 4096, "Largest layout / snapshot / import JSON in KiB; bigger files are neither written nor read.");
        editUndoSteps = c.getInt("undoSteps", ed, editUndoSteps, 50, 1000, "Undo steps kept until the server stops (the last 20 are saved in hq-layout.json).");
        editPerSecond = c.getFloat("editsPerSecond", ed, (float) editPerSecond, 0.5F, 50F, "Most edits per second per player (bursts of twice that).");
        editMaxImportSpan = c.getInt("maxImportSpan", ed, editMaxImportSpan, 4, 128, "Imports and presets wider/taller/deeper than this many blocks are refused.");
        editAuditLog = c.getString("auditLog", ed, editAuditLog, "Audit log of every tool placement, removal and change (relative to the server directory).");
        if (c.hasChanged()) {
            c.save();
        }
    }
}
