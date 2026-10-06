package dev.agentcraft.gtnh.server;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentLinkedQueue;

import net.minecraft.block.Block;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.init.Blocks;
import net.minecraft.init.Items;
import net.minecraft.item.ItemStack;
import net.minecraft.server.MinecraftServer;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.tileentity.TileEntitySign;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.MathHelper;
import net.minecraft.util.MovingObjectPosition;
import net.minecraft.util.Vec3;
import net.minecraft.world.World;
import net.minecraft.world.WorldServer;
import net.minecraftforge.common.DimensionManager;
import net.minecraftforge.event.world.BlockEvent;

import dev.agentcraft.gtnh.AgentCraftGTNH;
import dev.agentcraft.gtnh.CommonProxy;
import dev.agentcraft.gtnh.Config;
import dev.agentcraft.gtnh.block.TileAgentCraft;
import dev.agentcraft.gtnh.edit.AuditLog;
import dev.agentcraft.gtnh.edit.EditEngine;
import dev.agentcraft.gtnh.edit.EditRules;
import dev.agentcraft.gtnh.edit.FileStore;
import dev.agentcraft.gtnh.edit.Json;
import dev.agentcraft.gtnh.edit.Op;
import dev.agentcraft.gtnh.edit.PanelSpec;
import dev.agentcraft.gtnh.edit.PanelType;
import dev.agentcraft.gtnh.edit.PanelTypes;
import dev.agentcraft.gtnh.edit.Ports;
import dev.agentcraft.gtnh.edit.Pos;
import dev.agentcraft.gtnh.edit.RelLayout;
import dev.agentcraft.gtnh.hq.Anchor;
import dev.agentcraft.gtnh.hq.HqAnchors;
import dev.agentcraft.gtnh.hq.StationAssigner;
import dev.agentcraft.gtnh.item.ItemEditTool;
import dev.agentcraft.gtnh.net.Net;
import cpw.mods.fml.common.eventhandler.EventPriority;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.PlayerEvent;
import cpw.mods.fml.common.gameevent.TickEvent;

/**
 * Card 6, server side of the office edit tool: owns the {@link EditEngine} for the HQ world, the
 * Forge world port (this mod's panel blocks only), the anchors port over hq-anchors.json, the
 * audit log, the permission check and the request queue. Every request (tool packet or command)
 * is checked here: edit enabled, op level (+ optional allowlist), HQ dimension; then the engine
 * checks lock, rate, limits and cell safety. Nothing here talks to Hermes.
 */
public final class EditService {

    private static final ConcurrentLinkedQueue<Object[]> QUEUE = new ConcurrentLinkedQueue<>();
    private static final int QUEUE_MAX = 256;
    private static final int QUEUE_PER_PLAYER = 16;
    /** Last "denied" audit line per player (server thread): at most one every 10 s each. */
    private final Map<String, Long> deniedAudit = new java.util.HashMap<>();
    /** Panel kind id -> its block (the card 2-4 blocks; later cards add theirs with {@link #registerBlock}). */
    private static final Map<String, Block> BLOCKS = new LinkedHashMap<>();
    private static final Map<Block, String> TYPE_OF = new HashMap<>();

    public static EditService instance;

    public final EditRules rules = new EditRules();
    public EditEngine engine;
    public AuditLog audit;
    public FileStore store;
    private final java.util.Set<String> editors = new java.util.HashSet<>(); // player names that opened the editor
    private int actorDim;
    private String lastDisplay = "";

    public static void registerBlock(String typeId, Block b) {
        BLOCKS.put(typeId, b);
        TYPE_OF.put(b, typeId);
    }

    public static String typeOf(Block b) {
        return b == null ? null : TYPE_OF.get(b);
    }

    public static Block blockOf(String typeId) {
        return BLOCKS.get(typeId);
    }

    /**
     * Netty thread: queue a tool request for the server tick (bounded: 256 in all and 16 per
     * player, so one client flooding requests cannot crowd out the others).
     */
    public static void enqueue(EntityPlayerMP p, String json) {
        if (p == null || QUEUE.size() >= QUEUE_MAX) return;
        int mine = 0;
        for (Object[] q : QUEUE) if (q[0] == p && ++mine >= QUEUE_PER_PLAYER) return;
        QUEUE.add(new Object[] { p, json });
    }

    // ---- lifecycle ---------------------------------------------------------------------------

    public void start(MinecraftServer server) {
        rules.maxPanels = Config.editMaxPanels;
        rules.maxLayoutBytes = Config.editMaxLayoutKB * 1024;
        rules.undoSteps = Config.editUndoSteps;
        rules.editsPerSecond = Config.editPerSecond;
        rules.burst = Config.editPerSecond * 2;
        rules.maxImportSpan = Config.editMaxImportSpan;
        for (String bad : rules.setExclusions(Config.editProtected)) AgentCraftGTNH.LOG.warn("edit.protectedAreas: ignoring bad entry '{}' (want x,y,z,radius)", bad);
        File anchorsFile = new File(Config.anchorsFile).getAbsoluteFile();
        File dir = anchorsFile.getParentFile();
        store = new FileStore(dir, rules.maxLayoutBytes);
        audit = new AuditLog(new File(Config.editAuditLog), 8L * 1024 * 1024);
        engine = new EditEngine(rules, new ForgeWorld(), new AnchorPort(), audit, store, System::currentTimeMillis);
        String worldName = server.worldServers.length > 0 && server.worldServers[0] != null ? server.worldServers[0].getSaveHandler()
            .getWorldDirectoryName() : server.getFolderName();
        engine.load(worldName, hq());
        for (String n : engine.notes()) AgentCraftGTNH.LOG.warn("edit tool: {}", n);
        AgentCraftGTNH.LOG.info(
            "edit tool: {} panels in {}, {}{}",
            engine.panels()
                .size(),
            store.layoutFile(),
            engine.locked() ? "LOCKED " + engine.lockInfo() : "unlocked",
            Config.editEnabled ? "" : " (disabled in config)");
        QUEUE.clear();
        editors.clear();
        lastDisplay = "";
    }

    public void stop() {
        QUEUE.clear();
        editors.clear();
    }

    static int hq() {
        return CommonProxy.sync == null ? Config.spawnDimension : CommonProxy.sync.hqDimension();
    }

    // ---- permission --------------------------------------------------------------------------

    public static String who(EntityPlayerMP p) {
        return p == null ? "console" : p.getCommandSenderName() + "/" + p.getUniqueID()
            .toString()
            .substring(0, 8);
    }

    /** null when the player may edit, else why not. */
    public String denied(EntityPlayerMP p) {
        if (!Config.editEnabled) return "the edit tool is disabled in the server config (edit.enabled)";
        if (engine == null) return "the edit tool is not running";
        if (p == null) return null;
        if (!p.canCommandSenderUseCommand(Config.editOpLevel, "agentcraft")) return "only ops (level " + Config.editOpLevel + ") may use the edit tool";
        if (Config.editPlayers.length > 0) {
            boolean listed = false;
            for (String n : Config.editPlayers) if (n.equalsIgnoreCase(p.getCommandSenderName())) listed = true;
            if (!listed) return "you are not on the edit tool's allowedPlayers list";
        }
        return null;
    }

    private String wrongDim(EntityPlayerMP p) {
        if (p == null) return null;
        if (CommonProxy.sync != null && !CommonProxy.sync.anchors()
            .all()
            .isEmpty() && p.dimension != hq()) return "the HQ is in dimension " + hq() + "; edit it from there";
        return null;
    }

    // ---- server tick + world events ----------------------------------------------------------

    @SubscribeEvent
    public void onTick(TickEvent.ServerTickEvent e) {
        if (e.phase != TickEvent.Phase.END || engine == null) return;
        Object[] q;
        int n = 0;
        while (n++ < 32 && (q = QUEUE.poll()) != null) {
            EntityPlayerMP p = (EntityPlayerMP) q[0];
            if (p.playerNetServerHandler == null || p.isDead) continue;
            try {
                handle(p, (String) q[1]);
            } catch (RuntimeException ex) {
                AgentCraftGTNH.LOG.warn("edit tool: bad request from {}: {}", p.getCommandSenderName(), ex.toString());
                sendView(p, EditEngineResult.no("bad request"));
            }
        }
        String d = displayJson();
        if (!d.equals(lastDisplay)) {
            lastDisplay = d;
            Net.sendToAll(new Net.Display(d));
        }
    }

    @SubscribeEvent
    public void onLogin(PlayerEvent.PlayerLoggedInEvent e) {
        if (e.player instanceof EntityPlayerMP && engine != null) Net.sendTo(new Net.Display(displayJson()), (EntityPlayerMP) e.player);
    }

    /**
     * A panel block placed by hand joins the layout (not an undoable edit; the audit log notes it).
     * Lowest priority and never for a cancelled event, so a protection mod that refuses the
     * placement leaves the layout alone; the player may be a fake player of another mod.
     */
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public void onPlace(BlockEvent.PlaceEvent e) {
        if (engine == null || e.world.isRemote || e.world.provider.dimensionId != hq()) return;
        String t = typeOf(e.placedBlock);
        if (t == null) return;
        Pos p = new Pos(e.x, e.y, e.z);
        PanelType pt = PanelTypes.get(t);
        String facing = pt != null && pt.faced ? PanelSpec.facingOfMeta(e.blockMetadata) : "north";
        // metadata is set by onBlockPlacedBy after the event; derive the facing like the block does
        if (pt != null && pt.faced && e.player != null) {
            int l = MathHelper.floor_double(e.player.rotationYaw * 4.0F / 360.0F + 0.5D) & 3;
            facing = PanelSpec.facingOfMeta(l == 0 ? 2 : l == 1 ? 5 : l == 2 ? 3 : 4);
        }
        PanelSpec s = engineWorld().read(p);
        if (s == null && pt != null) s = pt.fresh(facing);
        engine.adopt(p, s);
        audit.record(whoOf(e.player), "hand-place", p.key(), "-", Block.blockRegistry.getNameForObject(e.placedBlock), "placed by hand (joins the layout)");
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public void onBreak(BlockEvent.BreakEvent e) {
        if (engine == null || e.world.isRemote || e.world.provider.dimensionId != hq()) return;
        if (typeOf(e.block) == null) return;
        Pos p = new Pos(e.x, e.y, e.z);
        engine.forget(p);
        audit.record(whoOf(e.getPlayer()), "hand-break", p.key(), Block.blockRegistry.getNameForObject(e.block), "-", "broken by hand (leaves the layout)");
    }

    private static String whoOf(net.minecraft.entity.player.EntityPlayer pl) {
        if (pl instanceof EntityPlayerMP) return who((EntityPlayerMP) pl);
        return pl == null ? "?" : pl.getCommandSenderName();
    }

    String displayJson() {
        return Json.write(new LinkedHashMap<String, Object>(engine.display()));
    }

    // ---- requests ----------------------------------------------------------------------------

    /** Small adapter so this class can build failure results without engine internals. */
    static final class EditEngineResult {

        static Map<String, Object> no(String m) {
            return Json.map("ok", Boolean.FALSE, "msg", m);
        }
    }

    private void handle(EntityPlayerMP p, String json) {
        Map<String, Object> m;
        try {
            m = Json.parseObject(json);
        } catch (Json.ParseException e) {
            return;
        }
        String a = Json.str(m, "a", "");
        String deny = denied(p);
        if (deny != null) {
            // a client without permission can send requests as fast as it likes: log the refusal,
            // but not one line per packet
            Long last = deniedAudit.get(who(p));
            long now = System.currentTimeMillis();
            if (last == null || now - last >= 10_000L) {
                if (deniedAudit.size() > 1024) deniedAudit.clear();
                deniedAudit.put(who(p), now);
                audit.record(who(p), "denied", "-", "-", "-", a + ": " + deny);
            }
            sendView(p, EditEngineResult.no(deny));
            return;
        }
        editors.add(p.getCommandSenderName());
        if ("hello".equals(a)) {
            sendView(p, null);
            return;
        }
        String dim = wrongDim(p);
        if (dim != null) {
            sendView(p, EditEngineResult.no(dim));
            return;
        }
        Map<String, Object> res = act(p, a, m);
        sendView(p, res);
        if (res != null && Boolean.TRUE.equals(res.get("changed"))) refreshOthers(p);
    }

    /** One action; the result map carries ok, msg, lines and (for dry runs) the plan. */
    Map<String, Object> act(EntityPlayerMP p, String a, Map<String, Object> m) {
        String w = who(p);
        actorDim = p == null ? hq() : p.dimension;
        if (p == null && NEEDS_PLAYER.contains(a)) return EditEngineResult.no("players only (it uses where you stand or look)");
        EditEngine.Result r;
        switch (a) {
            case "panel.set": {
                Pos pos = Pos.parse(Json.str(m, "pos", ""));
                PanelSpec cur = pos == null ? null : engineWorld().read(pos);
                if (cur == null) return EditEngineResult.no("no AgentCraft panel there");
                PanelSpec s = cur;
                if (m.containsKey("binding")) s = s.withBinding(Json.str(m, "binding", ""));
                if (m.containsKey("w") || m.containsKey("h")) s = s.withSize(Json.integer(m, "w", s.w), Json.integer(m, "h", s.h));
                if (m.containsKey("label")) s = s.withLabel(Json.str(m, "label", ""));
                if (m.containsKey("theme")) s = s.withTheme(Json.str(m, "theme", ""));
                if (m.containsKey("facing")) s = s.withFacing(Json.str(m, "facing", s.facing));
                r = engine.edit(w, "edit " + name(cur) + " at " + pos, one(Op.Change.panel(pos, s)));
                break;
            }
            case "panel.delete": {
                Pos pos = Pos.parse(Json.str(m, "pos", ""));
                PanelSpec cur = pos == null ? null : engineWorld().read(pos);
                if (cur == null) return EditEngineResult.no("no AgentCraft panel there");
                r = engine.edit(w, "delete " + name(cur) + " at " + pos, one(Op.Change.panel(pos, null)));
                break;
            }
            case "panel.duplicate": {
                Pos pos = Pos.parse(Json.str(m, "pos", ""));
                PanelSpec cur = pos == null ? null : engineWorld().read(pos);
                if (cur == null) return EditEngineResult.no("no AgentCraft panel there");
                Pos to = besideFree(pos, cur);
                if (to == null) return EditEngineResult.no("no free cell beside it (left or right, within 9 blocks)");
                r = engine.edit(w, "duplicate " + name(cur) + " to " + to, one(Op.Change.panel(to, cur)));
                break;
            }
            case "panel.move": {
                Pos from = Pos.parse(Json.str(m, "pos", "")), to = Pos.parse(Json.str(m, "to", ""));
                PanelSpec cur = from == null ? null : engineWorld().read(from);
                if (cur == null || to == null) return EditEngineResult.no("no AgentCraft panel to move");
                if (to.equals(from)) return EditEngineResult.no("that is where it is");
                if (to.distSq(p.posX, p.posY, p.posZ) > 24 * 24) return EditEngineResult.no("too far away");
                PanelSpec moved = m.containsKey("facing") ? cur.withFacing(Json.str(m, "facing", cur.facing)) : cur;
                List<Op.Change> mv = new ArrayList<>();
                mv.add(Op.Change.panel(from, null));
                mv.add(Op.Change.panel(to, moved));
                r = engine.edit(w, "move " + name(cur) + " to " + to, mv);
                break;
            }
            case "palette.give": {
                PanelType t = PanelTypes.get(Json.str(m, "type", ""));
                if (t == null) return EditEngineResult.no("unknown panel kind");
                ItemStack st = PanelType.SIGN.equals(t.source) ? new ItemStack(Items.sign) : blockOf(t.id) == null ? null : new ItemStack(blockOf(t.id));
                if (st == null) return EditEngineResult.no("no block registered for " + t.name);
                if (p == null) return EditEngineResult.no("players only");
                p.inventory.addItemStackToInventory(st);
                p.inventoryContainer.detectAndSendChanges();
                audit.record(w, "give", "-", "-", "-", t.id);
                return Json.map("ok", Boolean.TRUE, "msg", "gave you a " + t.name + (PanelType.SIGN.equals(t.source) ? " (place it, then use the tool on it: \"use as overflow sign\")" : " (place it like any block; it joins the layout)"));
            }
            case "sign.overflow": {
                Pos pos = Pos.parse(Json.str(m, "pos", ""));
                World wd = DimensionManager.getWorld(hq());
                if (pos == null || wd == null || !(wd.getTileEntity(pos.x, pos.y, pos.z) instanceof TileEntitySign)) return EditEngineResult.no("that is not a sign");
                r = engine.edit(w, "overflow sign at " + pos, one(Op.Change.anchor(HqAnchors.OVERFLOW_SIGN, new Anchor(HqAnchors.OVERFLOW_SIGN, pos.x, pos.y, pos.z, 0, 0))));
                break;
            }
            case "anchor.move":
            case "anchor.create":
            case "anchor.addslot": {
                String n = Json.str(m, "name", "")
                    .toLowerCase(java.util.Locale.ROOT);
                Anchor cur = CommonProxy.sync.anchors()
                    .get(n);
                String mode = Json.str(m, "mode", "me");
                if ("anchor.addslot".equals(a)) {
                    String st = stationOf(n);
                    if (st == null) return EditEngineResult.no("'" + n + "' is not a station slot");
                    n = nextSlot(st);
                    if (n == null) return EditEngineResult.no("station " + st + " has 32 slots");
                    cur = null;
                } else if ("anchor.move".equals(a) && cur == null) return EditEngineResult.no("no anchor " + n);
                if (!EditEngine.ANCHOR_NAME.matcher(n)
                    .matches()) return EditEngineResult.no("anchor names: a-z 0-9 _ . : -");
                double[] at = "crosshair".equals(mode) ? crosshairSpot(p) : feet(p);
                if (at == null) return EditEngineResult.no("look at a block within 16 blocks first");
                float yaw = cur != null ? cur.yaw : Math.round(p.rotationYaw / 90.0F) * 90.0F;
                r = engine.edit(w, ("anchor.move".equals(a) ? "move anchor " : "add anchor ") + n, one(Op.Change.anchor(n, new Anchor(n, at[0], at[1], at[2], yaw, cur == null ? 0 : cur.pitch))));
                break;
            }
            case "anchor.rotate": {
                String n = Json.str(m, "name", "");
                Anchor cur = CommonProxy.sync.anchors()
                    .get(n);
                if (cur == null) return EditEngineResult.no("no anchor " + n);
                int dir = Json.integer(m, "dir", 1) >= 0 ? 1 : -1;
                float yaw = Math.round(cur.yaw / 90.0F) * 90.0F + 90.0F * dir;
                r = engine.edit(w, "rotate anchor " + n, one(Op.Change.anchor(n, new Anchor(n, cur.x, cur.y, cur.z, yaw, cur.pitch))));
                break;
            }
            case "anchor.remove": {
                String n = Json.str(m, "name", "");
                if (CommonProxy.sync.anchors()
                    .get(n) == null) return EditEngineResult.no("no anchor " + n);
                r = engine.edit(w, "remove anchor " + n, one(Op.Change.anchor(n, null)));
                break;
            }
            case "anchor.tp": {
                Anchor an = CommonProxy.sync.anchors()
                    .get(Json.str(m, "name", ""));
                if (an == null || p == null) return EditEngineResult.no("no such anchor");
                p.mountEntity(null);
                p.playerNetServerHandler.setPlayerLocation(an.x, an.y, an.z, an.yaw, p.rotationPitch);
                return Json.map("ok", Boolean.TRUE, "msg", "teleported to " + an.name);
            }
            case "display.set": {
                String k = Json.str(m, "key", ""), v = Json.str(m, "value", null);
                r = engine.edit(w, "display " + k + " = " + v, one(Op.Change.display(k, v == null || v.isEmpty() ? null : v)));
                break;
            }
            case "undo":
                r = engine.undo(w);
                break;
            case "redo":
                r = engine.redo(w);
                break;
            case "snap.save":
                r = engine.saveSnapshot(w, Json.str(m, "name", ""));
                break;
            case "snap.diff":
                r = engine.diffSnapshot(w, Json.str(m, "name", ""));
                break;
            case "snap.restore":
                r = engine.restoreSnapshot(w, Json.str(m, "name", ""));
                break;
            case "preset.dry":
            case "import.dry": {
                boolean preset = "preset.dry".equals(a);
                String n = Json.str(m, "name", "");
                RelLayout rel;
                try {
                    rel = preset ? preset(n) : importFile(n);
                } catch (IOException | Json.ParseException e) {
                    return EditEngineResult.no(e.getMessage());
                }
                if (p == null) return EditEngineResult.no("players only (the layout goes at your feet, facing your way)");
                Pos o = new Pos(MathHelper.floor_double(p.posX), MathHelper.floor_double(p.posY + 0.01), MathHelper.floor_double(p.posZ));
                r = engine.planLayout(w, preset ? "preset" : "import", rel, o, facingOf(p), Json.str(m, "mode", "add"));
                break;
            }
            case "plan.apply":
                r = engine.applyPlan(w, Json.str(m, "token", ""));
                break;
            case "export": {
                String n = Json.str(m, "name", "");
                if (!FileStore.validName(n)) return EditEngineResult.no("export names: a-z 0-9 _ - (max 32)");
                if (p == null) return EditEngineResult.no("players only (the origin is where you stand)");
                Pos o = new Pos(MathHelper.floor_double(p.posX), MathHelper.floor_double(p.posY + 0.01), MathHelper.floor_double(p.posZ));
                int radius = Math.max(2, Math.min(Json.integer(m, "radius", 16), rules.maxImportSpan / 2));
                RelLayout rel = engine.export(n, o, facingOf(p), radius, Json.bool(m, "keepNames", false));
                try {
                    store.write(store.sub("exports", n), Json.pretty(rel.toJson()));
                } catch (IOException e) {
                    return EditEngineResult.no("export failed: " + e.getMessage());
                }
                audit.record(w, "export", o.key(), "-", "-", n + ": " + rel.panels.size() + " panels, " + rel.anchors.size() + " anchors, radius " + radius);
                return Json.map(
                    "ok",
                    Boolean.TRUE,
                    "msg",
                    "exported " + rel.panels.size() + " panels and " + rel.anchors.size() + " anchors within " + radius + " blocks to exports/" + n + ".json" + (Json.bool(m, "keepNames", false) ? "" : " (ids, boards and labels replaced by placeholders)"));
            }
            case "lock":
                r = engine.setLock(w, true, Json.str(m, "reason", "from the editor"));
                break;
            case "scan": {
                int n = scan(p, Math.max(1, Math.min(Json.integer(m, "radius", 16), 48)));
                return Json.map("ok", Boolean.TRUE, "msg", "scan: " + n + " panel(s) joined the layout", "changed", Boolean.TRUE);
            }
            default:
                return EditEngineResult.no("unknown action '" + a + "'");
        }
        return resultMap(r);
    }

    static Map<String, Object> resultMap(EditEngine.Result r) {
        Map<String, Object> out = Json.map("ok", r.ok, "msg", r.message);
        List<Object> lines = new ArrayList<>();
        for (String s : r.lines) if (lines.size() < 120) lines.add(s.length() > 160 ? s.substring(0, 160) + "..." : s);
        out.put("lines", lines);
        if (r.plan != null) {
            out.put(
                "plan",
                Json.map(
                    "token",
                    r.plan.token,
                    "kind",
                    r.plan.kind,
                    "title",
                    r.plan.title,
                    "changes",
                    (double) r.plan.changes.size(),
                    "conflicts",
                    (double) r.plan.conflicts.size(),
                    "bbox",
                    r.plan.bboxText()));
        }
        out.put("changed", r.ok && r.op != null && !r.op.changes.isEmpty());
        return out;
    }

    private static List<Op.Change> one(Op.Change c) {
        List<Op.Change> l = new ArrayList<>();
        l.add(c);
        return l;
    }

    private static String name(PanelSpec s) {
        PanelType t = PanelTypes.get(s.type);
        return t == null ? s.type : t.name;
    }

    static String stationOf(String anchor) {
        for (String st : StationAssigner.STATIONS) if (anchor.equals(st) || anchor.startsWith(st + "_")) return st;
        return null;
    }

    String nextSlot(String st) {
        Map<String, Anchor> all = CommonProxy.sync.anchors()
            .all();
        if (!all.containsKey(st)) return st;
        for (int i = 2; i <= 32; i++) if (!all.containsKey(st + "_" + i)) return st + "_" + i;
        return null;
    }

    static String facingOf(EntityPlayerMP p) {
        int q = MathHelper.floor_double(p.rotationYaw * 4.0F / 360.0F + 0.5D) & 3; // 0 south, 1 west, 2 north, 3 east
        return q == 0 ? "south" : q == 1 ? "west" : q == 2 ? "north" : "east";
    }

    static double[] feet(EntityPlayerMP p) {
        return new double[] { MathHelper.floor_double(p.posX) + 0.5, MathHelper.floor_double(p.posY + 0.01), MathHelper.floor_double(p.posZ) + 0.5 };
    }

    /** The cell on top of the block under the crosshair (within 16 blocks). */
    static double[] crosshairSpot(EntityPlayerMP p) {
        MovingObjectPosition mop = lookedAt(p, 16);
        if (mop == null || mop.typeOfHit != MovingObjectPosition.MovingObjectType.BLOCK) return null;
        int x = mop.blockX, y = mop.blockY, z = mop.blockZ;
        switch (mop.sideHit) {
            case 0:
                y--;
                break;
            case 1:
                y++;
                break;
            case 2:
                z--;
                break;
            case 3:
                z++;
                break;
            case 4:
                x--;
                break;
            case 5:
                x++;
                break;
            default:
        }
        return new double[] { x + 0.5, y, z + 0.5 };
    }

    public static MovingObjectPosition lookedAt(EntityPlayerMP p, double reach) {
        Vec3 eye = Vec3.createVectorHelper(p.posX, p.posY + p.getEyeHeight(), p.posZ);
        float yaw = p.rotationYaw, pitch = p.rotationPitch;
        float f1 = MathHelper.cos(-yaw * 0.017453292F - (float) Math.PI);
        float f2 = MathHelper.sin(-yaw * 0.017453292F - (float) Math.PI);
        float f3 = -MathHelper.cos(-pitch * 0.017453292F);
        float f4 = MathHelper.sin(-pitch * 0.017453292F);
        Vec3 end = eye.addVector(f2 * f3 * reach, f4 * reach, f1 * f3 * reach);
        return p.worldObj.rayTraceBlocks(eye, end);
    }

    /** First air cell to the right of the panel (then to the left) past its screen width. */
    Pos besideFree(Pos pos, PanelSpec s) {
        int q = PanelSpec.quarterOf(s.facing); // north 0, east 1, south 2, west 3
        // "right" as seen by someone facing the panel's front
        int[] right = q == 0 ? new int[] { -1, 0 } : q == 1 ? new int[] { 0, -1 } : q == 2 ? new int[] { 1, 0 } : new int[] { 0, 1 };
        int step = Math.max(1, s.w);
        Ports.World wp = engineWorld();
        for (int sign : new int[] { 1, -1 }) {
            for (int d = step; d <= 9; d++) {
                Pos t = pos.add(right[0] * d * sign, 0, right[1] * d * sign);
                if (wp.kind(t) == Ports.World.AIR && rules.protectedAt(t) == null) return t;
            }
        }
        return null;
    }

    /** Adopt this mod's panels within radius of the player (or the HQ anchors' centre for the console). */
    public int scan(EntityPlayerMP p, int radius) {
        WorldServer w = DimensionManager.getWorld(hq());
        if (w == null) return 0;
        int cx, cy, cz;
        if (p != null) {
            cx = MathHelper.floor_double(p.posX);
            cy = MathHelper.floor_double(p.posY);
            cz = MathHelper.floor_double(p.posZ);
        } else {
            Anchor a = CommonProxy.sync.anchors()
                .all()
                .values()
                .stream()
                .findFirst()
                .orElse(null);
            if (a == null) return 0;
            cx = MathHelper.floor_double(a.x);
            cy = MathHelper.floor_double(a.y);
            cz = MathHelper.floor_double(a.z);
        }
        Map<Pos, PanelSpec> found = new TreeMap<>();
        Ports.World wp = engineWorld();
        for (Object o : new ArrayList<Object>(w.loadedTileEntityList)) {
            if (!(o instanceof TileAgentCraft)) continue;
            TileEntity te = (TileEntity) o;
            if (Math.abs(te.xCoord - cx) > radius || Math.abs(te.yCoord - cy) > radius || Math.abs(te.zCoord - cz) > radius) continue;
            Pos pos = new Pos(te.xCoord, te.yCoord, te.zCoord);
            PanelSpec s = wp.read(pos);
            if (s != null) found.put(pos, s);
        }
        int n = engine.adoptAll(found);
        audit.record(who(p), "scan", cx + "," + cy + "," + cz, "-", "-", n + " panels adopted within " + radius);
        return n;
    }

    // ---- presets / imports -------------------------------------------------------------------

    private static final java.util.Set<String> NEEDS_PLAYER = new java.util.HashSet<>(
        java.util.Arrays.asList("panel.move", "anchor.move", "anchor.create", "anchor.addslot", "anchor.tp", "palette.give", "preset.dry", "import.dry", "export"));

    public static final String[] BUNDLED = { "open-office", "noc-wall", "focus-pods" };

    public RelLayout preset(String id) throws IOException, Json.ParseException {
        if (!FileStore.validName(id)) throw new IOException("preset names: a-z 0-9 _ -");
        String text = null;
        File own = store.sub("presets", id);
        if (own.isFile()) text = store.read(own);
        else {
            try (InputStream in = EditService.class.getResourceAsStream("/assets/agentcraftgtnh/presets/" + id + ".json")) {
                if (in != null) {
                    byte[] b = new byte[rules.maxLayoutBytes + 1];
                    int n = 0, k;
                    while (n < b.length && (k = in.read(b, n, b.length - n)) > 0) n += k;
                    if (n > rules.maxLayoutBytes) throw new IOException("preset too large");
                    text = new String(b, 0, n, StandardCharsets.UTF_8);
                }
            }
        }
        if (text == null) throw new IOException("no preset '" + id + "'");
        return RelLayout.fromJson(Json.parseObject(text));
    }

    public List<String> presetIds() {
        List<String> out = new ArrayList<>();
        for (String b : BUNDLED) out.add(b);
        for (String s : store.list("presets")) if (!out.contains(s)) out.add(s);
        return out;
    }

    public RelLayout importFile(String name) throws IOException, Json.ParseException {
        File f = store.sub("imports", name);
        if (!f.isFile()) f = store.sub("exports", name);
        String text = store.read(f);
        if (text == null) throw new IOException("no file imports/" + name + ".json or exports/" + name + ".json");
        return RelLayout.fromJson(Json.parseObject(text));
    }

    public List<String> importNames() {
        List<String> out = new ArrayList<>(store.list("imports"));
        for (String s : store.list("exports")) if (!out.contains(s)) out.add(s);
        return out;
    }

    // ---- view --------------------------------------------------------------------------------

    public void sendView(EntityPlayerMP p, Map<String, Object> result) {
        if (p == null) return;
        Net.sendTo(new Net.EditView(viewJson(p, result)), p);
    }

    private void refreshOthers(EntityPlayerMP except) {
        for (Object o : MinecraftServer.getServer()
            .getConfigurationManager().playerEntityList) {
            EntityPlayerMP q = (EntityPlayerMP) o;
            if (q != except && editors.contains(q.getCommandSenderName()) && denied(q) == null) sendView(q, null);
        }
    }

    String viewJson(EntityPlayerMP p, Map<String, Object> result) {
        Map<String, Object> v = new LinkedHashMap<>();
        v.put("allowed", denied(p) == null);
        v.put("locked", engine.locked());
        v.put("lockInfo", engine.lockInfo());
        v.put("readOnly", engine.readOnly() == null ? "" : engine.readOnly());
        v.put("undo", labels(engine.undoStack()));
        v.put("redo", labels(engine.redoStack()));
        v.put("undoN", (double) engine.undoStack()
            .size());
        v.put("redoN", (double) engine.redoStack()
            .size());
        v.put("panels", (double) engine.panels()
            .size());
        v.put("maxPanels", (double) rules.maxPanels);
        v.put("hqDim", (double) hq());
        List<Object> an = new ArrayList<>();
        for (Anchor a : CommonProxy.sync.anchors()
            .all()
            .values()) {
            if (an.size() >= 300) break;
            List<Object> e = new ArrayList<>();
            e.add(a.name);
            e.add(r2(a.x));
            e.add(r2(a.y));
            e.add(r2(a.z));
            e.add((double) a.yaw);
            an.add(e);
        }
        v.put("anchors", an);
        v.put("missing", new ArrayList<Object>(dev.agentcraft.gtnh.command.CommandAgentCraft.missingStations(CommonProxy.sync.anchors())));
        v.put("snapshots", new ArrayList<Object>(store.snapshots()));
        List<Object> ps = new ArrayList<>();
        for (String id : presetIds()) {
            try {
                RelLayout r = preset(id);
                ps.add(java.util.Arrays.asList(id, r.name, r.description, (double) r.panels.size(), (double) r.anchors.size()));
            } catch (IOException | Json.ParseException e) {
                ps.add(java.util.Arrays.asList(id, id, "unreadable: " + e.getMessage(), 0.0, 0.0));
            }
        }
        v.put("presets", ps);
        v.put("imports", new ArrayList<Object>(importNames()));
        v.put("display", new LinkedHashMap<String, Object>(engine.display()));
        List<Object> rec = new ArrayList<>();
        for (String s : audit.recent(8)) rec.add(s.length() > 200 ? s.substring(0, 200) : s);
        v.put("recent", rec);
        if (result != null) v.put("result", result);
        String s = Json.write(v);
        while (s.getBytes(StandardCharsets.UTF_8).length > Net.MAX_VIEW - 100 && !an.isEmpty()) {
            an.subList(an.size() / 2, an.size())
                .clear();
            s = Json.write(v);
        }
        return s;
    }

    private static double r2(double d) {
        return Math.round(d * 100) / 100.0;
    }

    private static List<Object> labels(List<dev.agentcraft.gtnh.edit.Op> ops) {
        List<Object> out = new ArrayList<>();
        for (dev.agentcraft.gtnh.edit.Op o : ops) {
            if (out.size() >= 6) break;
            out.add(o.label);
        }
        return out;
    }

    // ---- ports -------------------------------------------------------------------------------

    private final ForgeWorld worldPort = new ForgeWorld();

    Ports.World engineWorld() {
        return worldPort;
    }

    /**
     * The HQ world, seen through the safety gate: AIR is exactly vanilla air with no tile entity;
     * PANEL is a block registered as a panel kind; everything else is OTHER and is never changed.
     * place/update/remove re-check the cell themselves (second gate after the engine's).
     */
    final class ForgeWorld implements Ports.World {

        WorldServer w() {
            return DimensionManager.getWorld(hq());
        }

        @Override
        public int kind(Pos p) {
            WorldServer w = w();
            if (w == null) return UNLOADED;
            if (p.y < 0 || p.y > 255) return OTHER;
            if (!w.blockExists(p.x, p.y, p.z)) return UNLOADED;
            Block b = w.getBlock(p.x, p.y, p.z);
            if (typeOf(b) != null) return w.getTileEntity(p.x, p.y, p.z) instanceof TileAgentCraft ? PANEL : OTHER;
            if (b == Blocks.air && w.getTileEntity(p.x, p.y, p.z) == null) return AIR;
            return OTHER;
        }

        @Override
        public PanelSpec read(Pos p) {
            if (kind(p) != PANEL) return null;
            WorldServer w = w();
            String t = typeOf(w.getBlock(p.x, p.y, p.z));
            TileAgentCraft te = (TileAgentCraft) w.getTileEntity(p.x, p.y, p.z);
            PanelType pt = PanelTypes.get(t);
            String facing = pt != null && pt.faced ? PanelSpec.facingOfMeta(w.getBlockMetadata(p.x, p.y, p.z)) : "north";
            PanelSpec s = new PanelSpec(t, facing, te.binding, te.screenW, te.screenH, te.label, te.theme);
            return pt == null ? s : pt.normalize(s);
        }

        @Override
        public String describe(Pos p) {
            WorldServer w = w();
            if (w == null || p.y < 0 || p.y > 255 || !w.blockExists(p.x, p.y, p.z)) return "unloaded";
            Block b = w.getBlock(p.x, p.y, p.z);
            return Block.blockRegistry.getNameForObject(b) + ":" + w.getBlockMetadata(p.x, p.y, p.z);
        }

        @Override
        public boolean place(Pos p, PanelSpec s) {
            int k = kind(p);
            if (k != AIR && k != PANEL) return false;
            PanelType pt = PanelTypes.get(s.type);
            Block b = blockOf(s.type);
            if (pt == null || !pt.placeable || b == null) return false;
            WorldServer w = w();
            int meta = pt.faced ? PanelSpec.meta(s.facing) : 0;
            if (!w.setBlock(p.x, p.y, p.z, b, meta, 3)) return false;
            TileEntity te = w.getTileEntity(p.x, p.y, p.z);
            if (te instanceof TileAgentCraft) {
                ((TileAgentCraft) te).setBinding(s.binding, s.w, s.h);
                ((TileAgentCraft) te).setLook(s.label, s.theme);
            }
            return true;
        }

        @Override
        public boolean update(Pos p, PanelSpec s) {
            if (kind(p) != PANEL) return false;
            WorldServer w = w();
            String t = typeOf(w.getBlock(p.x, p.y, p.z));
            if (!s.type.equals(t)) return false;
            PanelType pt = PanelTypes.get(t);
            if (pt != null && pt.faced) {
                int meta = PanelSpec.meta(s.facing);
                if (w.getBlockMetadata(p.x, p.y, p.z) != meta) w.setBlockMetadataWithNotify(p.x, p.y, p.z, meta, 3);
            }
            TileAgentCraft te = (TileAgentCraft) w.getTileEntity(p.x, p.y, p.z);
            te.setBinding(s.binding, s.w, s.h);
            te.setLook(s.label, s.theme);
            return true;
        }

        @Override
        public boolean remove(Pos p) {
            if (kind(p) != PANEL) return false;
            return w().setBlockToAir(p.x, p.y, p.z);
        }
    }

    /** hq-anchors.json through HqAnchors (the same file the anchor commands write). */
    final class AnchorPort implements Ports.Anchors {

        @Override
        public Map<String, Anchor> all() {
            return CommonProxy.sync.anchors()
                .all();
        }

        @Override
        public void put(Anchor a) throws IOException {
            HqAnchors an = CommonProxy.sync.anchors();
            an.put(a, an.all()
                .isEmpty() ? actorDim : an.dimension());
            CommonProxy.sync.anchorsChanged();
        }

        @Override
        public void remove(String name) throws IOException {
            CommonProxy.sync.anchors()
                .remove(name);
            CommonProxy.sync.anchorsChanged();
        }
    }

    // ---- commands (CommandAgentCraft) -----------------------------------------------------------

    /** Run an action for a command sender (console = null player); returns chat lines. */
    public List<String> command(EntityPlayerMP p, String a, Map<String, Object> m) {
        List<String> out = new ArrayList<>();
        String deny = denied(p);
        if (deny != null) {
            out.add("\u00a7c[AgentCraft] " + deny);
            return out;
        }
        String dim = wrongDim(p);
        if (dim != null) {
            out.add("\u00a7c[AgentCraft] " + dim);
            return out;
        }
        Map<String, Object> r = act(p, a, m);
        boolean ok = Boolean.TRUE.equals(r.get("ok"));
        out.add((ok ? "[AgentCraft] " : "\u00a7c[AgentCraft] ") + Json.str(r, "msg", ""));
        List<Object> lines = Json.arr(r, "lines");
        if (lines != null) for (int i = 0; i < lines.size() && i < 40; i++) out.add("  " + lines.get(i));
        if (lines != null && lines.size() > 40) out.add("  ... " + (lines.size() - 40) + " more");
        Map<String, Object> plan = Json.obj(r, "plan");
        if (plan != null) out.add("\u00a7e[AgentCraft] dry run only. Apply: /agentcraft edit apply " + Json.str(plan, "token", ""));
        if (Boolean.TRUE.equals(r.get("changed"))) refreshOthers(p);
        return out;
    }

    /** True when the classic anchor/bind commands go through the engine (recorded, undoable, lockable). */
    public boolean routes() {
        return Config.editEnabled && engine != null;
    }

    /** /agentcraft anchor set|remove and bind overflow: the same edit as the anchor editor. */
    public EditEngine.Result recordAnchor(EntityPlayerMP p, int dim, String name, Anchor a) {
        actorDim = dim;
        return engine.edit(who(p), (a == null ? "anchor remove " : "anchor set ") + name, one(Op.Change.anchor(name, a)));
    }

    /**
     * /agentcraft bind on a panel in the HQ dimension: recorded like an inspector rebind. Returns
     * null when it does not apply (another dimension, not a panel kind): the command then binds
     * directly, as before card 6.
     */
    public EditEngine.Result recordBind(EntityPlayerMP p, World world, int x, int y, int z, String binding, int w, int h) {
        if (world.provider.dimensionId != hq()) return null;
        Pos pos = new Pos(x, y, z);
        PanelSpec cur = engineWorld().read(pos);
        if (cur == null) return null;
        PanelSpec s = cur.withBinding(binding);
        if (w > 0 || h > 0) s = s.withSize(w > 0 ? w : cur.w, h > 0 ? h : cur.h);
        actorDim = world.provider.dimensionId;
        return engine.edit(who(p), "bind " + name(cur) + " at " + pos + " to " + (binding.isEmpty() ? "(none)" : binding), one(Op.Change.panel(pos, s)));
    }

    public static ItemStack toolStack() {
        return new ItemStack(CommonProxy.editTool);
    }

    /** Tool use on the server: sneak = toggle edit mode (op check), else nothing (screens are client side). */
    public String toggle(EntityPlayerMP p, ItemStack stack) {
        String deny = denied(p);
        if (deny != null) {
            ItemEditTool.setOn(stack, false);
            return "\u00a7c[AgentCraft] " + deny;
        }
        boolean on = !ItemEditTool.isOn(stack);
        ItemEditTool.setOn(stack, on);
        editors.add(p.getCommandSenderName());
        sendView(p, null);
        audit.record(who(p), on ? "edit-mode-on" : "edit-mode-off", "-", "-", "-", "");
        return on ? "[AgentCraft] edit mode ON: look at a panel or anchor; right-click to edit, right-click air for the editor. Sneak-right-click again to leave."
            : "[AgentCraft] edit mode off";
    }

    public static void chat(EntityPlayerMP p, String s) {
        p.addChatMessage(new ChatComponentText(s));
    }
}
