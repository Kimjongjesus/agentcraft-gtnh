package dev.agentcraft.gtnh.command;

import java.io.IOException;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import net.minecraft.block.Block;
import net.minecraft.command.CommandBase;
import net.minecraft.command.ICommandSender;
import net.minecraft.command.WrongUsageException;
import net.minecraft.entity.EntityTracker;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.item.ItemStack;
import net.minecraft.server.MinecraftServer;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.tileentity.TileEntitySign;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.IntHashMap;
import net.minecraft.util.MathHelper;
import net.minecraft.util.MovingObjectPosition;
import net.minecraft.util.Vec3;
import net.minecraft.world.World;
import net.minecraft.world.WorldServer;
import net.minecraftforge.common.DimensionManager;

import dev.agentcraft.gtnh.CommonProxy;
import dev.agentcraft.gtnh.Config;
import dev.agentcraft.gtnh.block.BlockAgentCraft;
import dev.agentcraft.gtnh.block.TileAgentCraft;
import dev.agentcraft.gtnh.bridge.ForemanBridge;
import dev.agentcraft.gtnh.edit.EditEngine;
import dev.agentcraft.gtnh.edit.Json;
import dev.agentcraft.gtnh.server.EditService;
import dev.agentcraft.gtnh.entity.EntityHermesAgent;
import dev.agentcraft.gtnh.hq.Anchor;
import dev.agentcraft.gtnh.hq.HqAnchors;
import dev.agentcraft.gtnh.hq.StationAssigner;
import dev.agentcraft.gtnh.net.Net;
import dev.agentcraft.gtnh.server.AgentWorldSync;
import dev.agentcraft.gtnh.server.BoardSync;
import dev.agentcraft.gtnh.state.AgentInfo;
import dev.agentcraft.gtnh.state.HqData;

/**
 * /agentcraft (op level 2; also from the server console):
 *
 * <pre>
 * status | agents
 * anchor set &lt;name&gt; [facing]                      stand on the spot (players)
 * anchor set &lt;name&gt; &lt;x&gt; &lt;y&gt; &lt;z&gt; [facing]          console / exact coordinates
 * anchor remove &lt;name&gt; | list [prefix] | tp &lt;name&gt; [player] | missing | show [seconds] | reload
 * bind &lt;agent|fleet|overflow|clear&gt; [w h]           the block you look at (monitor, lamp, sign)
 * bind &lt;agent|fleet|overflow|clear&gt; &lt;x&gt; &lt;y&gt; &lt;z&gt; [w h]
 * give &lt;monitor|lamp|beacon&gt; [count]
 * </pre>
 *
 * Nothing here places or breaks blocks: anchors are a JSON file, bindings are a field on the player's own
 * monitor/lamp blocks, the overflow anchor points at a sign he placed.
 */
public class CommandAgentCraft extends CommandBase {

    private static final String USAGE = "/agentcraft <status|agents|board|ops|anchor|bind|give|edit|toast|cap|help>";

    @Override
    public String getCommandName() {
        return "agentcraft";
    }

    @Override
    public String getCommandUsage(ICommandSender sender) {
        return USAGE;
    }

    @Override
    public int getRequiredPermissionLevel() {
        return 2;
    }

    private static void say(ICommandSender s, String text) {
        s.addChatMessage(new ChatComponentText(text));
    }

    @Override
    public void processCommand(ICommandSender sender, String[] args) {
        String sub = args.length > 0 ? args[0].toLowerCase(Locale.ROOT) : "status";
        switch (sub) {
            case "agents":
                agents(sender);
                return;
            case "anchor":
            case "anchors":
                anchor(sender, args);
                return;
            case "bind":
                bind(sender, args);
                return;
            case "give":
                give(sender, args);
                return;
            case "cap": {
                // runtime NPC cap (not saved; the config's maxAgents applies after a restart)
                if (args.length < 2) throw new WrongUsageException("/agentcraft cap <0..64>");
                Config.maxAgents = parseIntBounded(sender, args[1], 0, 64);
                say(sender, "[AgentCraft] NPC cap is " + Config.maxAgents + " until the next restart");
                return;
            }
            case "status":
                status(sender);
                return;
            case "board":
            case "boards":
                board(sender);
                return;
            case "edit":
                edit(sender, args);
                return;
            case "toast": {
                // card 5b: the setting lives on the player's client (each player mutes for themself);
                // the console form names the player: /agentcraft toast <action> <player>
                String a = args.length > 1 ? args[1].toLowerCase(Locale.ROOT) : "status";
                if (!a.equals("mute") && !a.equals("unmute") && !a.equals("test") && !a.equals("status") && !a.equals("open")) {
                    throw new WrongUsageException("/agentcraft toast <mute|unmute|test|status|open> [player]");
                }
                EntityPlayerMP target = args.length > 2 ? getPlayer(sender, args[2]) : getCommandSenderAsPlayer(sender);
                Net.sendTo(new Net.ToastCtl(a), target);
                return;
            }
            case "ops":
                ops(sender);
                return;
            case "help":
                help(sender);
                return;
            default:
                throw new WrongUsageException(USAGE);
        }
    }

    private static void help(ICommandSender s) {
        say(s, "\u00a76[AgentCraft] read-only HQ view of Hermes (op level 2):");
        say(s, " status | agents | board          link, NPCs, task wall / library data");
        say(s, " anchor set|remove|list|tp|missing|show|reload   station spots (NPCs)");
        say(s, " give monitor|lamp|beacon|taskwall|library|atrium [n]");
        say(s, " bind <agent|fleet|clear> [w h]   monitor (w h = screen size) / lamp, at the crosshair");
        say(s, " bind <board|all|clear> [w h]     task wall / atrium / library (board slug, all = every board)");
        say(s, " bind overflow                    a vanilla sign: +N more agents");
        say(s, " bind ... <x> <y> <z> [w h]       console form; cap <0..64> NPC cap until restart");
        say(s, " Right-click a task wall / atrium (cards + details) or library (notes): read-only screens.");
        say(s, "\u00a76 Ops panels (card 5b, read-only): give fleetboard|cronboard|usage|alerts [n]");
        say(s, " bind <all|group|source|provider> [w h]   an ops panel: filter what it shows; ops = feed status");
        say(s, " toast mute|unmute|test|status|open      decision toast (your client); key N opens decisions");
        say(s, "\u00a76 Office edit tool (card 6): give edittool, then sneak + right-click to edit");
        say(s, " edit status|undo|redo|history|lock [why]|unlock|scan [r]|audit [n]|reload");
        say(s, " edit snapshot save|diff|restore <name> | snapshot list");
        say(s, " edit preset list | preset <name> [add|replace] | import <name> [add|replace] | imports");
        say(s, " edit export <name> [radius] [keep] | apply <token>   (presets/imports are dry runs until applied)");
    }

    private static void board(ICommandSender sender) {
        BoardSync b = CommonProxy.sync.board;
        say(
            sender,
            "[AgentCraft] board data: tasks=" + b.tasks()
                .size()
                + " goals="
                + b.goals()
                    .size()
                + " notes="
                + b.notes()
                    .size()
                + " | adapter msgs task="
                + b.taskMessages
                + " goal="
                + b.goalMessages
                + " memory="
                + b.memoryMessages
                + " | blobs board="
                + b.boardBlobs
                + " ("
                + b.lastBoardBytes
                + " B) library="
                + b.libraryBlobs
                + " ("
                + b.lastLibraryBytes
                + " B) packets="
                + b.blobPackets
                + " droppedTasks="
                + b.droppedTasks);
        for (HqData.Goal g : b.goals()
            .values()) say(sender, "  " + g.board + ": " + BlockAgentCraft.summaryLine(g));
        if (b.goals()
            .size() > 1) say(sender, "  " + BlockAgentCraft.summaryLine(b.summary("all")));
    }

    // ---- status / agents ------------------------------------------------------------------

    /** Card 5b: what the server holds from the ops feeds, and the open decisions. */
    private static void ops(ICommandSender sender) {
        dev.agentcraft.gtnh.server.OpsSync o = CommonProxy.sync.ops;
        dev.agentcraft.gtnh.ops.OpsModel m = o.model();
        say(
            sender,
            "[AgentCraft] ops feed " + (m.live() ? "LIVE" : "offline")
                + " worst="
                + o.opsWorst()
                + " fleet="
                + CommonProxy.sync.fleetFamily()
                + " | services="
                + m.count("service")
                + " jobs="
                + m.count("job")
                + " usage="
                + m.count("usage")
                + " alerts="
                + m.count("alert")
                + " sources="
                + m.count("source")
                + " | msgs="
                + m.messages
                + " ignored="
                + m.ignored
                + " evicted="
                + m.evicted
                + " | blobs ops="
                + o.opsBlobs
                + " ("
                + o.lastOpsBytes
                + " B) decisions="
                + o.decisionBlobs
                + " ("
                + o.lastDecisionBytes
                + " B) openDecisions="
                + o.openDecisionCount());
        java.util.List<String> f = o.filters();
        if (!f.isEmpty()) say(sender, "  filters: " + String.join(", ", f));
    }

    private static void status(ICommandSender sender) {
        AgentWorldSync sync = CommonProxy.sync;
        ForemanBridge b = CommonProxy.bridge;
        say(
            sender,
            "[AgentCraft] link " + (b == null ? "disabled"
                : (b.connected ? "UP" : "DOWN") + " "
                    + b.url()
                    + " thread="
                    + (b.threadAlive() ? "alive" : "DEAD")
                    + " received="
                    + b.received.get()
                    + (b.lastError.isEmpty() ? "" : " lastError=" + b.lastError)));
        say(
            sender,
            "[AgentCraft] snapshots=" + sync.snapshots
                + " upserts="
                + sync.upserts
                + " logs="
                + sync.logMessages
                + " decisions="
                + sync.decisionMessages
                + " other="
                + sync.otherMessages
                + " npcStateChanges="
                + sync.stateChanges
                + " agents="
                + sync.agents()
                    .size()
                + " npcs="
                + sync.entities()
                    .size()
                + " overflow="
                + sync.overflowIds()
                    .size()
                + " openDecisions="
                + sync.openDecisionCount()
                + " fleet="
                + sync.fleetFamily()
                + " syncPackets="
                + Net.sentPackets
                + " logPackets="
                + Net.sentLogPackets
                + " strayRemoved="
                + sync.duplicatesRemoved);
        HqAnchors an = sync.anchors();
        say(
            sender,
            "[AgentCraft] anchors=" + an.all()
                .size() + " dim=" + an.dimension() + " file=" + an.file()
                    .getPath() + (an.lastError()
                        .isEmpty() ? "" : " ERROR " + an.lastError()));
        for (Map.Entry<String, EntityHermesAgent> en : sync.entities()
            .entrySet()) {
            EntityHermesAgent e = en.getValue();
            StationAssigner.Target t = sync.targets()
                .get(en.getKey());
            say(
                sender,
                String.format(
                    Locale.ROOT,
                    "[AgentCraft] NPC #%d %s at %.1f %.1f %.1f dim %d alive=%s tracked=%s walking=%s target=%s (walks=%d teleports=%d) | name=\"%s\" dw: state=%s activity=\"%s\"",
                    e.getEntityId(),
                    en.getKey(),
                    e.posX,
                    e.posY,
                    e.posZ,
                    e.worldObj.provider.dimensionId,
                    !e.isDead,
                    isTracked(e),
                    e.isWalking(),
                    t == null ? "row" : t.toString(),
                    e.walks,
                    e.teleports,
                    e.getCustomNameTag(),
                    e.getAgentState(),
                    e.getAgentActivity()));
        }
    }

    private static void agents(ICommandSender sender) {
        AgentWorldSync sync = CommonProxy.sync;
        for (AgentInfo a : sync.allViews()) {
            StationAssigner.Target t = sync.targets()
                .get(a.id);
            String where = sync.entities()
                .containsKey(a.id) ? (t == null ? "row" : t.toString()) : "no NPC (over the cap)";
            say(
                sender,
                AgentInfo.familyChat(a.family()) + a.id
                    + "\u00a7r ("
                    + a.role
                    + ") @"
                    + a.station
                    + " -> "
                    + where
                    + (a.waiting ? " \u00a76! waiting on you\u00a7r" : "")
                    + " | "
                    + a.stateLine());
        }
    }

    // ---- anchors --------------------------------------------------------------------------

    private void anchor(ICommandSender sender, String[] args) {
        AgentWorldSync sync = CommonProxy.sync;
        HqAnchors an = sync.anchors();
        String op = args.length > 1 ? args[1].toLowerCase(Locale.ROOT) : "list";
        switch (op) {
            case "set": {
                if (args.length < 3) throw new WrongUsageException("/agentcraft anchor set <name> [facing] | <name> <x> <y> <z> [facing]");
                String name = checkName(args[2]);
                Anchor a;
                int dim;
                if (args.length >= 6) {
                    double x = parseDouble(sender, args[3]), y = parseDouble(sender, args[4]), z = parseDouble(sender, args[5]);
                    Float f = args.length >= 7 ? facing(args[6]) : Float.valueOf(0.0F);
                    float pitch = args.length >= 8 ? (float) parseDoubleBounded(sender, args[7], -90, 90) : 0.0F;
                    a = new Anchor(name, x, y, z, f, pitch);
                    dim = sender instanceof EntityPlayerMP ? ((EntityPlayerMP) sender).dimension : an.dimension();
                } else {
                    EntityPlayerMP p = getCommandSenderAsPlayer(sender);
                    Float f = args.length >= 4 ? facing(args[3]) : null;
                    if (name.startsWith("cam_")) {
                        a = new Anchor(name, p.posX, p.posY, p.posZ, f != null ? f : p.rotationYaw, p.rotationPitch);
                    } else {
                        float yaw = f != null ? f : Math.round(p.rotationYaw / 90.0F) * 90.0F;
                        a = new Anchor(
                            name,
                            MathHelper.floor_double(p.posX) + 0.5,
                            MathHelper.floor_double(p.posY + 0.01),
                            MathHelper.floor_double(p.posZ) + 0.5,
                            yaw,
                            0.0F);
                    }
                    dim = p.dimension;
                }
                if (EditService.instance != null && EditService.instance.routes()) {
                    // card 6: recorded (undoable, audited) and refused while editing is locked
                    if (!an.all()
                        .isEmpty() && dim != an.dimension()) {
                        say(sender, "\u00a7c[AgentCraft] not saved: the HQ is in dimension " + an.dimension() + "; anchors cannot span dimensions");
                        return;
                    }
                    EditEngine.Result r = EditService.instance.recordAnchor(sender instanceof EntityPlayerMP ? (EntityPlayerMP) sender : null, dim, name, a);
                    if (!r.ok || !r.lines.isEmpty() && r.lines.get(r.lines.size() - 1)
                        .startsWith("skip")) {
                        say(sender, "\u00a7c[AgentCraft] not saved: " + r.message + (r.lines.isEmpty() ? "" : " " + r.lines.get(r.lines.size() - 1)));
                        return;
                    }
                } else {
                    try {
                        an.put(a, dim);
                    } catch (IOException e) {
                        say(sender, "\u00a7c[AgentCraft] not saved: " + e.getMessage());
                        return;
                    }
                    sync.anchorsChanged();
                }
                say(sender, "[AgentCraft] anchor " + a + " (dim " + dim + ") saved to " + an.file()
                    .getPath());
                if (!StationAssigner.isStandingAnchor(name) && !name.startsWith("cam_")
                    && !HqAnchors.OVERFLOW_SIGN.equals(name)) {
                    say(sender, "\u00a7e[AgentCraft] note: '" + name + "' is not a station name; agents only use " + String.join(", ", StationAssigner.STATIONS) + " (+ _2.._N or _<agent>)");
                }
                return;
            }
            case "remove":
            case "rm": {
                if (args.length < 3) throw new WrongUsageException("/agentcraft anchor remove <name>");
                String rmName = args[2].toLowerCase(Locale.ROOT);
                if (EditService.instance != null && EditService.instance.routes()) {
                    if (an.get(rmName) == null) {
                        say(sender, "\u00a7c[AgentCraft] no anchor " + args[2]);
                        return;
                    }
                    EditEngine.Result r = EditService.instance.recordAnchor(sender instanceof EntityPlayerMP ? (EntityPlayerMP) sender : null, an.dimension(), rmName, null);
                    say(sender, r.ok ? "[AgentCraft] removed anchor " + args[2] + " (undo: /agentcraft edit undo)" : "\u00a7c[AgentCraft] not removed: " + r.message);
                    return;
                }
                try {
                    boolean ok = an.remove(rmName);
                    say(sender, ok ? "[AgentCraft] removed anchor " + args[2] : "\u00a7c[AgentCraft] no anchor " + args[2]);
                    if (ok) sync.anchorsChanged();
                } catch (IOException e) {
                    say(sender, "\u00a7c[AgentCraft] not saved: " + e.getMessage());
                }
                return;
            }
            case "reload":
                sync.reloadAnchors();
                say(sender, "[AgentCraft] reloaded " + an.all()
                    .size() + " anchors from " + an.file()
                        .getPath() + (an.lastError()
                            .isEmpty() ? "" : " \u00a7cERROR " + an.lastError()));
                return;
            case "tp": {
                if (args.length < 3) throw new WrongUsageException("/agentcraft anchor tp <name> [player]");
                Anchor a = an.get(args[2].toLowerCase(Locale.ROOT));
                if (a == null) {
                    say(sender, "\u00a7c[AgentCraft] no anchor " + args[2]);
                    return;
                }
                EntityPlayerMP p = args.length >= 4 ? getPlayer(sender, args[3]) : getCommandSenderAsPlayer(sender);
                if (p.dimension != an.dimension()) {
                    say(sender, "\u00a7c[AgentCraft] the HQ is in dimension " + an.dimension() + "; go there first");
                    return;
                }
                p.mountEntity(null);
                p.playerNetServerHandler.setPlayerLocation(a.x, a.y, a.z, a.yaw, a.pitch);
                say(sender, "[AgentCraft] teleported " + p.getCommandSenderName() + " to " + a);
                return;
            }
            case "missing": {
                List<String> none = missingStations(an);
                say(sender, none.isEmpty() ? "[AgentCraft] every station has at least one anchor" : "\u00a7e[AgentCraft] stations with no anchor: " + String.join(", ", none));
                int n = 0;
                for (Map.Entry<String, StationAssigner.Target> e : sync.targets()
                    .entrySet()) {
                    StationAssigner.Target t = e.getValue();
                    if (!"slot".equals(t.mode) && !"personal".equals(t.mode)) {
                        say(sender, "\u00a7e[AgentCraft] " + e.getKey() + " wants " + t.wantedStation + " -> " + t);
                        n++;
                    }
                }
                if (n == 0) say(sender, "[AgentCraft] every NPC stands on a slot of its own station");
                return;
            }
            case "show": {
                int secs = args.length >= 3 ? parseIntBounded(sender, args[2], 0, 3600) : 60;
                EntityPlayerMP p = args.length >= 4 ? getPlayer(sender, args[3]) : getCommandSenderAsPlayer(sender);
                Net.AnchorOverlay msg = new Net.AnchorOverlay();
                msg.seconds = secs;
                for (Anchor a : an.all()
                    .values()) {
                    msg.names.add(a.name);
                    msg.spots.add(new double[] { a.x, a.y, a.z, a.yaw });
                }
                msg.missing.addAll(missingStations(an));
                Net.sendTo(msg, p);
                say(sender, "[AgentCraft] showing " + msg.names.size() + " anchors for " + secs + " s (0 hides)");
                return;
            }
            case "list":
            default: {
                String prefix = "list".equals(op) && args.length >= 3 ? args[2].toLowerCase(Locale.ROOT) : "";
                say(sender, "[AgentCraft] " + an.all()
                    .size() + " anchors, dimension " + an.dimension() + ", file " + an.file()
                        .getPath());
                for (Anchor a : an.all()
                    .values()) {
                    if (a.name.startsWith(prefix)) say(sender, "  " + a);
                }
            }
        }
    }

    /** Stations with neither a shared slot nor any personal anchor. */
    public static List<String> missingStations(HqAnchors an) {
        List<String> out = new ArrayList<>();
        for (String st : StationAssigner.STATIONS) {
            boolean any = false;
            for (String n : an.all()
                .keySet()) {
                if (n.equals(st) || n.startsWith(st + "_")) {
                    any = true;
                    break;
                }
            }
            if (!any) out.add(st);
        }
        return out;
    }

    private static String checkName(String s) {
        String n = s.toLowerCase(Locale.ROOT);
        if (!HqAnchors.NAME.matcher(n)
            .matches()) throw new WrongUsageException("anchor names: a-z 0-9 _ . : - (e.g. desk_builder-a, lounge_2)");
        return n;
    }

    private static Float facing(String s) {
        Float f = Anchor.parseFacing(s);
        if (f == null) throw new WrongUsageException("facing: north|south|east|west or degrees");
        return f;
    }

    // ---- bind -----------------------------------------------------------------------------

    private void bind(ICommandSender sender, String[] args) {
        if (args.length < 2) throw new WrongUsageException("/agentcraft bind <agent|fleet|overflow|clear> [w h] | ... <x> <y> <z> [w h]");
        String what = args[1].toLowerCase(Locale.ROOT);
        World world;
        int x, y, z;
        int rest;
        if (args.length >= 5 && isInt(args[2]) && isInt(args[3]) && isInt(args[4])) {
            x = parseInt(sender, args[2]);
            y = parseInt(sender, args[3]);
            z = parseInt(sender, args[4]);
            world = sender instanceof EntityPlayerMP ? ((EntityPlayerMP) sender).worldObj
                : DimensionManager.getWorld(
                    CommonProxy.sync.hqDimension());
            rest = 5;
        } else {
            EntityPlayerMP p = getCommandSenderAsPlayer(sender);
            MovingObjectPosition mop = lookedAt(p, 8.0D);
            if (mop == null || mop.typeOfHit != MovingObjectPosition.MovingObjectType.BLOCK) {
                say(sender, "\u00a7c[AgentCraft] look at a monitor, lamp or sign (within 8 blocks)");
                return;
            }
            world = p.worldObj;
            x = mop.blockX;
            y = mop.blockY;
            z = mop.blockZ;
            rest = 2;
        }
        if (world == null) {
            say(sender, "\u00a7c[AgentCraft] world not loaded");
            return;
        }
        TileEntity te = world.getTileEntity(x, y, z);
        if ("overflow".equals(what)) {
            if (!(te instanceof TileEntitySign)) {
                say(sender, "\u00a7c[AgentCraft] the overflow count goes on a vanilla sign you placed; that block is not one");
                return;
            }
            if (EditService.instance != null && EditService.instance.routes()) {
                EditEngine.Result r = EditService.instance.recordAnchor(
                    sender instanceof EntityPlayerMP ? (EntityPlayerMP) sender : null,
                    world.provider.dimensionId,
                    HqAnchors.OVERFLOW_SIGN,
                    new Anchor(HqAnchors.OVERFLOW_SIGN, x, y, z, 0.0F, 0.0F));
                if (!r.ok) {
                    say(sender, "\u00a7c[AgentCraft] not saved: " + r.message);
                    return;
                }
            } else {
                try {
                    CommonProxy.sync.anchors()
                        .put(new Anchor(HqAnchors.OVERFLOW_SIGN, x, y, z, 0.0F, 0.0F), world.provider.dimensionId);
                } catch (IOException e) {
                    say(sender, "\u00a7c[AgentCraft] not saved: " + e.getMessage());
                    return;
                }
                CommonProxy.sync.anchorsChanged();
            }
            say(sender, "[AgentCraft] overflow sign set at " + x + " " + y + " " + z);
            return;
        }
        if (!(te instanceof TileAgentCraft)) {
            say(sender, "\u00a7c[AgentCraft] that block is not an AgentCraft monitor, lamp or beacon");
            return;
        }
        String binding = "clear".equals(what) ? "" : what;
        Block block = world.getBlock(x, y, z);
        boolean opsBlock = te instanceof TileAgentCraft.OpsScreen;
        boolean boardBlock = te instanceof TileAgentCraft.TaskWall || te instanceof TileAgentCraft.Library;
        if (opsBlock) {
            // card 5b: an ops panel: "all" (default) or a filter (service group, ops source, provider)
            if ("fleet".equals(binding) || binding.isEmpty()) binding = "all";
            if (!"all".equals(binding) && !CommonProxy.sync.ops.filters()
                .contains(binding)) {
                say(sender, "\u00a7e[AgentCraft] note: nothing in the ops data matches '" + binding + "' right now (bound anyway); filters: " + String.join(", ", CommonProxy.sync.ops.filters()));
            }
        } else if (boardBlock) {
            // task wall / atrium / library: a board slug, or "all" (= every board, also the default)
            if ("fleet".equals(binding)) binding = "all";
            if (!binding.isEmpty() && !"all".equals(binding) && !CommonProxy.sync.board.boards()
                .contains(binding)) {
                say(sender, "\u00a7e[AgentCraft] note: no board '" + binding + "' in the current snapshot (bound anyway); boards: " + String.join(", ", CommonProxy.sync.board.boards()));
            }
        } else if (!binding.isEmpty() && !"fleet".equals(binding) && !CommonProxy.sync.agents()
            .containsKey(binding)) {
            say(sender, "\u00a7e[AgentCraft] note: no agent '" + binding + "' in the current snapshot (bound anyway)");
        }
        int w = args.length > rest ? parseIntBounded(sender, args[rest], 1, 8) : 0;
        int h = args.length > rest + 1 ? parseIntBounded(sender, args[rest + 1], 1, 6) : 0;
        EditEngine.Result rec = EditService.instance != null && EditService.instance.routes()
            ? EditService.instance.recordBind(sender instanceof EntityPlayerMP ? (EntityPlayerMP) sender : null, world, x, y, z, binding, w, h)
            : null;
        if (rec != null && !rec.ok) {
            say(sender, "\u00a7c[AgentCraft] not bound: " + rec.message);
            return;
        }
        if (rec == null) ((TileAgentCraft) te).setBinding(binding, w, h);
        boolean screen = te instanceof TileAgentCraft.Monitor || te instanceof TileAgentCraft.TaskWall || opsBlock;
        say(
            sender,
            "[AgentCraft] " + block.getLocalizedName()
                + " at "
                + x
                + " "
                + y
                + " "
                + z
                + " now shows "
                + (opsBlock ? ("all".equals(binding) ? "every ops source" : "the ops filter " + binding)
                    : binding.isEmpty() ? (boardBlock ? "all boards" : "nothing (unbound)") : "all".equals(binding) ? "all boards" : binding)
                + (screen ? " (" + ((TileAgentCraft) te).screenW + "x" + ((TileAgentCraft) te).screenH + " screen)" : ""));
    }

    private static boolean isInt(String s) {
        return s.matches("-?\\d+");
    }

    /** Server-side ray trace along the player's view (EntityPlayer#rayTrace is client-only in 1.7.10). */
    private static MovingObjectPosition lookedAt(EntityPlayerMP p, double reach) {
        Vec3 eye = Vec3.createVectorHelper(p.posX, p.posY + p.getEyeHeight(), p.posZ);
        float yaw = p.rotationYaw, pitch = p.rotationPitch;
        float f1 = MathHelper.cos(-yaw * 0.017453292F - (float) Math.PI);
        float f2 = MathHelper.sin(-yaw * 0.017453292F - (float) Math.PI);
        float f3 = -MathHelper.cos(-pitch * 0.017453292F);
        float f4 = MathHelper.sin(-pitch * 0.017453292F);
        Vec3 end = eye.addVector(f2 * f3 * reach, f4 * reach, f1 * f3 * reach);
        return p.worldObj.rayTraceBlocks(eye, end);
    }

    // ---- give -----------------------------------------------------------------------------

    private void give(ICommandSender sender, String[] args) {
        if (args.length < 2) throw new WrongUsageException("/agentcraft give <monitor|lamp|beacon|taskwall|library|atrium|edittool> [count]");
        EntityPlayerMP p = getCommandSenderAsPlayer(sender);
        Block b;
        switch (args[1].toLowerCase(Locale.ROOT)) {
            case "monitor":
                b = CommonProxy.monitor;
                break;
            case "lamp":
                b = CommonProxy.lamp;
                break;
            case "beacon":
                b = CommonProxy.beacon;
                break;
            case "taskwall":
            case "wall":
                b = CommonProxy.taskWall;
                break;
            case "library":
                b = CommonProxy.library;
                break;
            case "atrium":
                b = CommonProxy.atrium;
                break;
            case "fleetboard":
            case "fleet_board":
                b = CommonProxy.fleetBoard;
                break;
            case "cronboard":
            case "cron_board":
                b = CommonProxy.cronBoard;
                break;
            case "usage":
            case "usage_panel":
                b = CommonProxy.usagePanel;
                break;
            case "alerts":
            case "alert_feed":
                b = CommonProxy.alertFeed;
                break;
            case "edittool":
            case "tool": {
                String deny = EditService.instance == null ? "edit tool not running" : EditService.instance.denied(p);
                if (deny != null) {
                    say(sender, "\u00a7c[AgentCraft] " + deny);
                    return;
                }
                p.inventory.addItemStackToInventory(EditService.toolStack());
                p.inventoryContainer.detectAndSendChanges();
                say(sender, "[AgentCraft] gave the Office Edit Tool. Sneak + right-click to enter edit mode.");
                return;
            }
            default:
                throw new WrongUsageException("/agentcraft give <monitor|lamp|beacon|taskwall|library|atrium|edittool> [count]");
        }
        int n = args.length >= 3 ? parseIntBounded(sender, args[2], 1, 64) : 1;
        p.inventory.addItemStackToInventory(new ItemStack(b, n));
        p.inventoryContainer.detectAndSendChanges();
        say(sender, "[AgentCraft] gave " + n + " " + args[1]);
    }

    // ---- edit (card 6) --------------------------------------------------------------------

    private void edit(ICommandSender sender, String[] args) {
        EditService es = EditService.instance;
        if (es == null || es.engine == null) {
            say(sender, "\u00a7c[AgentCraft] the edit tool is not running");
            return;
        }
        EntityPlayerMP p = sender instanceof EntityPlayerMP ? (EntityPlayerMP) sender : null;
        String op = args.length > 1 ? args[1].toLowerCase(Locale.ROOT) : "status";
        String a1 = args.length > 2 ? args[2].toLowerCase(Locale.ROOT) : "";
        String a2 = args.length > 3 ? args[3].toLowerCase(Locale.ROOT) : "";
        String a3 = args.length > 4 ? args[4].toLowerCase(Locale.ROOT) : "";
        switch (op) {
            case "lock": {
                // the panic switch: any op who may run /agentcraft (level 2), even if not on the editors list
                StringBuilder why = new StringBuilder();
                for (int i = 2; i < args.length; i++) why.append(i > 2 ? " " : "")
                    .append(args[i]);
                say(sender, "[AgentCraft] " + es.engine.setLock(EditService.who(p), true, why.toString()).message);
                return;
            }
            case "unlock": {
                String deny = es.denied(p);
                if (deny != null) {
                    say(sender, "\u00a7c[AgentCraft] " + deny);
                    return;
                }
                say(sender, "[AgentCraft] " + es.engine.setLock(EditService.who(p), false, "").message);
                return;
            }
            case "status": {
                EditEngine e = es.engine;
                say(
                    sender,
                    "[AgentCraft] edit tool: " + (e.locked() ? "\u00a7cLOCKED\u00a7r (" + e.lockInfo() + ")" : "unlocked")
                        + (e.readOnly() != null ? " \u00a7cREAD-ONLY\u00a7r " + e.readOnly() : "")
                        + " | panels "
                        + e.panels()
                            .size()
                        + "/"
                        + es.rules.maxPanels
                        + " | undo "
                        + e.undoStack()
                            .size()
                        + " redo "
                        + e.redoStack()
                            .size()
                        + " | snapshots "
                        + es.store.snapshots()
                            .size()
                        + " | protected areas "
                        + es.rules.exclusions.size()
                        + " | layout "
                        + es.store.layoutFile()
                            .getPath()
                        + " | audit "
                        + es.audit.file()
                            .getPath());
                for (String n : e.notes()) say(sender, "\u00a7e  " + n);
                return;
            }
            case "history": {
                say(sender, "[AgentCraft] undo (newest first):");
                int i = 0;
                for (dev.agentcraft.gtnh.edit.Op o : es.engine.undoStack()) {
                    if (i++ >= 10) break;
                    say(sender, "  " + o.label + " (" + o.changes.size() + " change(s), " + o.who + ")");
                }
                say(sender, "[AgentCraft] redo: " + es.engine.redoStack()
                    .size() + " step(s)");
                return;
            }
            case "audit": {
                int n = a1.isEmpty() ? 8 : parseIntBounded(sender, a1, 1, 30);
                for (String l : es.audit.recent(n)) say(sender, "  " + l);
                return;
            }
            case "reload": {
                String deny = es.denied(p);
                if (deny != null) {
                    say(sender, "\u00a7c[AgentCraft] " + deny);
                    return;
                }
                es.start(MinecraftServer.getServer());
                say(sender, "[AgentCraft] edit layout reloaded: " + es.engine.panels()
                    .size() + " panels" + (es.engine.readOnly() != null ? " \u00a7cREAD-ONLY " + es.engine.readOnly() : ""));
                return;
            }
            default:
        }
        java.util.Map<String, Object> m = new java.util.LinkedHashMap<>();
        String action;
        switch (op) {
            case "undo":
            case "redo":
                action = op;
                break;
            case "scan":
                action = "scan";
                if (!a1.isEmpty()) m.put("radius", (double) parseIntBounded(sender, a1, 1, 48));
                break;
            case "snapshot":
            case "snap":
                if ("list".equals(a1) || a1.isEmpty()) {
                    say(sender, "[AgentCraft] snapshots: " + String.join(", ", es.store.snapshots()));
                    return;
                }
                if (a2.isEmpty()) throw new WrongUsageException("/agentcraft edit snapshot save|diff|restore <name>");
                action = "save".equals(a1) ? "snap.save" : "diff".equals(a1) ? "snap.diff" : "restore".equals(a1) ? "snap.restore" : null;
                if (action == null) throw new WrongUsageException("/agentcraft edit snapshot save|diff|restore <name>");
                m.put("name", a2);
                break;
            case "preset":
            case "presets":
                if ("list".equals(a1) || a1.isEmpty()) {
                    say(sender, "[AgentCraft] presets: " + String.join(", ", es.presetIds()) + "  (dry run: /agentcraft edit preset <name> add|replace)");
                    return;
                }
                action = "preset.dry";
                m.put("name", a1);
                m.put("mode", a2.isEmpty() ? "add" : a2);
                break;
            case "imports":
                say(sender, "[AgentCraft] importable: " + String.join(", ", es.importNames()) + " (from " + es.store.dir.getPath() + "/imports and /exports)");
                return;
            case "import":
                if (a1.isEmpty()) throw new WrongUsageException("/agentcraft edit import <name> [add|replace]");
                action = "import.dry";
                m.put("name", a1);
                m.put("mode", a2.isEmpty() ? "add" : a2);
                break;
            case "export":
                if (a1.isEmpty()) throw new WrongUsageException("/agentcraft edit export <name> [radius] [keep]");
                action = "export";
                m.put("name", a1);
                if (!a2.isEmpty() && isInt(a2)) m.put("radius", (double) parseIntBounded(sender, a2, 2, 64));
                m.put("keepNames", "keep".equals(a2) || "keep".equals(a3));
                break;
            case "apply":
                if (a1.isEmpty()) throw new WrongUsageException("/agentcraft edit apply <token>");
                action = "plan.apply";
                m.put("token", args[2]);
                break;
            default:
                throw new WrongUsageException("/agentcraft edit <status|undo|redo|history|lock|unlock|snapshot|preset|import|imports|export|apply|scan|audit|reload>");
        }
        for (String line : es.command(p, action, m)) say(sender, line);
    }

    // ---- tab completion -------------------------------------------------------------------

    @Override
    @SuppressWarnings("rawtypes")
    public List addTabCompletionOptions(ICommandSender sender, String[] args) {
        if (args.length == 1) return getListOfStringsMatchingLastWord(args, "status", "agents", "board", "anchor", "bind", "give", "edit", "cap", "help");
        if (args.length == 2 && "edit".equalsIgnoreCase(args[0])) {
            return getListOfStringsMatchingLastWord(args, "status", "undo", "redo", "history", "lock", "unlock", "snapshot", "preset", "import", "imports", "export", "apply", "scan", "audit", "reload");
        }
        if (args.length == 3 && "edit".equalsIgnoreCase(args[0]) && "snapshot".equalsIgnoreCase(args[1])) {
            return getListOfStringsMatchingLastWord(args, "save", "diff", "restore", "list");
        }
        if (args.length == 3 && "edit".equalsIgnoreCase(args[0]) && "preset".equalsIgnoreCase(args[1]) && EditService.instance != null && EditService.instance.engine != null) {
            return getListOfStringsFromIterableMatchingLastWord(args, EditService.instance.presetIds());
        }
        if (args.length == 2 && "anchor".equalsIgnoreCase(args[0])) {
            return getListOfStringsMatchingLastWord(args, "set", "remove", "list", "tp", "missing", "show", "reload");
        }
        if (args.length == 3 && "anchor".equalsIgnoreCase(args[0])) {
            List<String> names = new ArrayList<>(
                CommonProxy.sync.anchors()
                    .all()
                    .keySet());
            if ("set".equalsIgnoreCase(args[1])) {
                Collections.addAll(names, StationAssigner.STATIONS);
                for (String id : CommonProxy.sync.agents()
                    .keySet()) names.add("desk_" + id);
            }
            return getListOfStringsFromIterableMatchingLastWord(args, names);
        }
        if (args.length == 4 && "anchor".equalsIgnoreCase(args[0]) && "tp".equalsIgnoreCase(args[1])) {
            return getListOfStringsMatchingLastWord(
                args,
                MinecraftServer.getServer()
                    .getAllUsernames());
        }
        if (args.length == 2 && "bind".equalsIgnoreCase(args[0])) {
            List<String> names = new ArrayList<>(
                CommonProxy.sync.agents()
                    .keySet());
            names.addAll(CommonProxy.sync.board.boards());
            Collections.addAll(names, "fleet", "all", "overflow", "clear");
            return getListOfStringsFromIterableMatchingLastWord(args, names);
        }
        if (args.length == 2 && "give".equalsIgnoreCase(args[0])) {
            return getListOfStringsMatchingLastWord(args, "monitor", "lamp", "beacon", "taskwall", "library", "atrium", "edittool");
        }
        return null;
    }

    @Override
    public boolean isUsernameIndex(String[] args, int i) {
        return args.length >= 2 && "anchor".equalsIgnoreCase(args[0]) && "tp".equalsIgnoreCase(args[1]) && i == 3;
    }

    /** True if the entity tracker will send this entity to clients (proves registerModEntity tracking). */
    private static boolean isTracked(EntityHermesAgent e) {
        if (!(e.worldObj instanceof WorldServer)) return false;
        EntityTracker t = ((WorldServer) e.worldObj).getEntityTracker();
        // the tracker's only IntHashMap is entity id -> EntityTrackerEntry (name differs dev vs obf)
        for (Field f : EntityTracker.class.getDeclaredFields()) {
            if (f.getType() != IntHashMap.class) continue;
            try {
                f.setAccessible(true);
                return ((IntHashMap) f.get(t)).containsItem(e.getEntityId());
            } catch (ReflectiveOperationException | RuntimeException ignored) {}
        }
        return false;
    }
}
