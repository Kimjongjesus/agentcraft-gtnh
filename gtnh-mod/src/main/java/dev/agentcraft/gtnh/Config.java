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

    public static void synchronizeConfiguration(File configFile) {
        Configuration c = new Configuration(configFile);
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
        if (c.hasChanged()) {
            c.save();
        }
    }
}
