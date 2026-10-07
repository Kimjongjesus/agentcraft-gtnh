package dev.agentcraft.gtnh.server;

import java.io.File;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import net.minecraft.block.Block;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.tileentity.TileEntitySign;
import net.minecraft.util.ChunkCoordinates;
import net.minecraft.util.MathHelper;
import net.minecraft.world.WorldServer;
import net.minecraftforge.common.DimensionManager;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import dev.agentcraft.gtnh.AgentCraftGTNH;
import dev.agentcraft.gtnh.CommonProxy;
import dev.agentcraft.gtnh.Config;
import dev.agentcraft.gtnh.bridge.ForemanBridge;
import dev.agentcraft.gtnh.entity.EntityHermesAgent;
import dev.agentcraft.gtnh.hq.Anchor;
import dev.agentcraft.gtnh.hq.HqAnchors;
import dev.agentcraft.gtnh.hq.StationAssigner;
import dev.agentcraft.gtnh.net.Net;
import dev.agentcraft.gtnh.state.AgentInfo;
import dev.agentcraft.gtnh.state.LogLine;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.PlayerEvent;
import cpw.mods.fml.common.gameevent.TickEvent;

/**
 * Server-thread side of the bridge: applies adapter messages (agents, logs, decisions) to tables,
 * keeps one NPC per displayed agent at the anchor of its station, and replicates agents, fleet
 * summary and monitor tails to clients (on change; a 5 s agent refresh is the only periodic packet).
 */
public final class AgentWorldSync {

    public static final int LOG_KEEP = 60;

    private final Map<String, AgentInfo> agents = new LinkedHashMap<>();
    private final Map<String, Deque<LogLine>> logs = new HashMap<>();
    private final Map<String, String> openDecisions = new HashMap<>(); // decision id -> agent id
    private final Map<String, EntityHermesAgent> entities = new HashMap<>();
    private final Map<String, AgentInfo> applied = new HashMap<>();
    private final Map<String, double[]> spots = new HashMap<>();
    private final Map<String, Integer> sentLogHash = new HashMap<>();
    private final Set<EntityPlayerMP> needsLogs = new HashSet<>();
    private final StationAssigner assigner = new StationAssigner();
    /** Card 3: tasks, goals and library notes for the task wall, atrium and library. */
    public final BoardSync board = new BoardSync();
    /** Card 5b: ops feeds and the open decisions (decision toast) for the clients. */
    public final OpsSync ops = new OpsSync();
    private Map<String, StationAssigner.Target> targets = Collections.emptyMap();
    private HqAnchors anchors;
    private boolean linkUp;
    private int tick;
    private boolean dirty, logsDirty, noAnchorsLogged;
    private String lastSign = "";
    private List<String> overflowIds = Collections.emptyList();
    public long snapshots, upserts, logMessages, decisionMessages, otherMessages, stateChanges, duplicatesRemoved;
    public String foremanMessage = "";
    public int lastTaskCount;

    public void reset() {
        agents.clear();
        logs.clear();
        openDecisions.clear();
        entities.clear();
        applied.clear();
        spots.clear();
        sentLogHash.clear();
        needsLogs.clear();
        targets = Collections.emptyMap();
        linkUp = false;
        lastSign = "";
        snapshots = upserts = logMessages = decisionMessages = otherMessages = stateChanges = duplicatesRemoved = 0;
        board.reset();
        ops.reset();
        anchors = new HqAnchors(new File(Config.anchorsFile), Config.spawnDimension);
        anchors.load();
        assigner.resetWarnings();
    }

    public HqAnchors anchors() {
        return anchors;
    }

    public void reloadAnchors() {
        anchors.load();
        assigner.resetWarnings();
        dirty = true;
    }

    public void anchorsChanged() {
        dirty = true; // targets are recomputed on the next tick; missing stations are reported once each
    }

    public boolean linkUp() {
        return linkUp;
    }

    public Map<String, AgentInfo> agents() {
        return Collections.unmodifiableMap(agents);
    }

    public Map<String, EntityHermesAgent> entities() {
        return Collections.unmodifiableMap(entities);
    }

    public Map<String, StationAssigner.Target> targets() {
        return Collections.unmodifiableMap(targets);
    }

    public List<String> overflowIds() {
        return overflowIds;
    }

    public int openDecisionCount() {
        return openDecisions.size();
    }

    public List<LogLine> logTail(String agentId, int n) {
        Deque<LogLine> d = logs.get(agentId);
        if (d == null) return Collections.emptyList();
        List<LogLine> all = new ArrayList<>(d);
        return all.subList(Math.max(0, all.size() - n), all.size());
    }

    /** The dimension the HQ (anchors) lives in; the config's spawnDimension while there are none. */
    public int hqDimension() {
        return anchors == null || anchors.all()
            .isEmpty() ? Config.spawnDimension : anchors.dimension();
    }

    @SubscribeEvent
    public void onServerTick(TickEvent.ServerTickEvent e) {
        if (e.phase != TickEvent.Phase.END || anchors == null) return;
        ForemanBridge b = CommonProxy.bridge;
        if (b != null) {
            JsonObject m;
            int n = 0;
            while (n < 1000 && (m = b.inbox.poll()) != null) {
                try {
                    apply(m);
                } catch (RuntimeException ex) {
                    otherMessages++;
                    AgentCraftGTNH.LOG.warn("ignoring malformed adapter message: {}", ex.toString());
                }
                n++;
            }
        }
        tick++;
        // card 5b: ops + decision blobs; a new worst ops state re-sends the fleet colour
        String opsBefore = ops.opsWorst();
        ops.tick(tick, agents, linkUp);
        if (!opsBefore.equals(ops.opsWorst())) dirty = true;
        if (dirty || tick % 20 == 0) {
            reconcile();
        }
        if (dirty || tick % 100 == 0) {
            broadcastAgents();
        }
        if (logsDirty || !needsLogs.isEmpty()) {
            broadcastLogs();
        }
        board.tick(tick);
        dirty = false;
        logsDirty = false;
    }

    @SubscribeEvent
    public void onLogin(PlayerEvent.PlayerLoggedInEvent e) {
        dirty = true; // next tick re-sends the agents to everyone, including the new player
        if (e.player instanceof EntityPlayerMP) {
            needsLogs.add((EntityPlayerMP) e.player);
            board.playerJoined((EntityPlayerMP) e.player);
            ops.playerJoined((EntityPlayerMP) e.player);
        }
    }

    @SubscribeEvent
    public void onDimChange(PlayerEvent.PlayerChangedDimensionEvent e) {
        dirty = true;
    }

    // ---- protocol -------------------------------------------------------------------------

    void apply(JsonObject m) {
        String type = m.has("type") ? m.get("type")
            .getAsString() : "";
        switch (type) {
            case "snapshot": {
                snapshots++;
                Map<String, AgentInfo> next = new LinkedHashMap<>();
                for (JsonElement el : array(m, "agents")) {
                    if (el.isJsonObject()) {
                        AgentInfo a = AgentInfo.fromJson(el.getAsJsonObject());
                        if (!a.id.isEmpty()) next.put(a.id, a);
                    }
                }
                agents.clear();
                agents.putAll(next);
                logs.clear();
                for (JsonElement el : array(m, "logs")) {
                    if (!el.isJsonObject()) continue;
                    JsonObject o = el.getAsJsonObject();
                    if (o.has("agentId")) appendLogs(
                        o.get("agentId")
                            .getAsString(),
                        array(o, "entries"));
                }
                openDecisions.clear();
                ops.decisionsReset();
                for (JsonElement el : array(m, "decisions")) {
                    if (el.isJsonObject()) applyDecision(el.getAsJsonObject());
                }
                lastTaskCount = array(m, "tasks").size();
                board.applySnapshot(m);
                readStatus(m.has("foreman") && m.get("foreman")
                    .isJsonObject() ? m.getAsJsonObject("foreman") : null);
                linkUp = true;
                AgentCraftGTNH.LOG.info(
                    "snapshot from Hermes adapter: {} agents, {} tasks, {} open decisions, {} goals, {} library notes ({}); displaying {}",
                    agents.size(),
                    lastTaskCount,
                    openDecisions.size(),
                    board.goals()
                        .size(),
                    board.notes()
                        .size(),
                    foremanMessage,
                    displayedIds());
                dirty = true;
                logsDirty = true;
                break;
            }
            case "agent.upsert": {
                if (!m.has("agent") || !m.get("agent")
                    .isJsonObject()) return;
                upserts++;
                AgentInfo a = AgentInfo.fromJson(m.getAsJsonObject("agent"));
                if (a.id.isEmpty()) return;
                AgentInfo prev = agents.put(a.id, a);
                if (prev == null || !prev.name.equals(a.name) || prev.color != a.color) ops.agentsChanged();
                if (Config.verboseLog && (prev == null || !prev.state.equals(a.state)
                    || !prev.station.equals(a.station)
                    || !prev.activity.equals(a.activity))) {
                    AgentCraftGTNH.LOG.info(
                        "agent.upsert {}: {}@{} -> {}@{} \"{}\"",
                        a.id,
                        prev == null ? "(new)" : prev.state,
                        prev == null ? "-" : prev.station,
                        a.state,
                        a.station,
                        a.activity);
                }
                dirty = true;
                break;
            }
            case "agent.log": {
                logMessages++;
                if (m.has("agentId")) appendLogs(
                    m.get("agentId")
                        .getAsString(),
                    array(m, "entries"));
                logsDirty = true;
                break;
            }
            case "decision.upsert": {
                decisionMessages++;
                if (m.has("decision") && m.get("decision")
                    .isJsonObject()) applyDecision(m.getAsJsonObject("decision"));
                dirty = true;
                break;
            }
            case "foreman.status":
                readStatus(m.has("status") && m.get("status")
                    .isJsonObject() ? m.getAsJsonObject("status") : null);
                break;
            case "_disconnected":
                linkUp = false;
                ops.linkDown();
                AgentCraftGTNH.LOG.warn("Hermes adapter disconnected; NPCs show 'adapter offline' until it is back");
                dirty = true;
                break;
            default:
                // feed and the rest: not shown in-world; card 5b: ops.* go to OpsSync (read-only)
                if (!board.apply(type, m) && !ops.apply(type, m)) otherMessages++;
        }
    }

    private static JsonArray array(JsonObject o, String k) {
        return o.has(k) && o.get(k)
            .isJsonArray() ? o.getAsJsonArray(k) : new JsonArray();
    }

    private void appendLogs(String agentId, JsonArray entries) {
        Deque<LogLine> d = logs.get(agentId);
        if (d == null) {
            d = new ArrayDeque<>();
            logs.put(agentId, d);
        }
        for (JsonElement el : entries) {
            if (!el.isJsonObject()) continue;
            d.addLast(LogLine.fromJson(el.getAsJsonObject()));
            while (d.size() > LOG_KEEP) d.removeFirst();
        }
    }

    private void applyDecision(JsonObject d) {
        String id = d.has("id") ? d.get("id")
            .getAsString() : "";
        if (id.isEmpty()) return;
        String status = d.has("status") ? d.get("status")
            .getAsString() : "";
        String agent = d.has("agentId") ? d.get("agentId")
            .getAsString() : "";
        if ("open".equals(status) && !agent.isEmpty()) openDecisions.put(id, agent);
        else openDecisions.remove(id);
        ops.applyDecision(d);
    }

    private void readStatus(JsonObject s) {
        if (s != null && s.has("message")) foremanMessage = s.get("message")
            .getAsString();
    }

    // ---- views ----------------------------------------------------------------------------

    /** The adapter's agent plus the server-computed "waiting on the player" flag. */
    private AgentInfo withWaiting(AgentInfo raw) {
        AgentInfo a = raw.copy();
        a.waiting = "waiting".equals(AgentInfo.family(a.state, a.active)) || openDecisions.containsValue(a.id);
        return a;
    }

    /** What the NPC shows: the adapter's view, or a dimmed 'offline' variant while the link is down. */
    private AgentInfo view(AgentInfo raw) {
        AgentInfo a = withWaiting(raw);
        if (linkUp) return a;
        a.state = "idle";
        a.activity = "Hermes adapter offline";
        a.active = false;
        a.waiting = false;
        return a;
    }

    public List<AgentInfo> allViews() {
        List<AgentInfo> out = new ArrayList<>();
        for (AgentInfo a : agents.values()) out.add(view(a));
        return out;
    }

    /**
     * Card 5b: worst of (agents, ops) for the fleet beacon, lamps bound to "fleet" and the atrium:
     * see {@link dev.agentcraft.gtnh.ops.OpsData#combineFleet}.
     */
    public String fleetFamily() {
        return dev.agentcraft.gtnh.ops.OpsData.combineFleet(AgentInfo.fleetFamily(allViews(), linkUp), ops.opsWorst());
    }

    // ---- world ----------------------------------------------------------------------------

    /** Agents that get an NPC: the configured list, or everyone most urgent first; capped at maxAgents. */
    public List<String> displayedIds() {
        List<String> out = new ArrayList<>();
        int max = Config.maxAgents;
        if (Config.displayAgents.length > 0) {
            for (String id : Config.displayAgents) {
                if (out.size() >= max) break;
                if (agents.containsKey(id.trim())) out.add(id.trim());
            }
            return out;
        }
        List<AgentInfo> all = new ArrayList<>();
        for (AgentInfo a : agents.values()) all.add(withWaiting(a));
        // stable sort: urgency first, then the adapter's order
        Collections.sort(all, (x, y) -> Integer.compare(rank(x), rank(y)));
        for (AgentInfo a : all) {
            if (out.size() >= max) break;
            out.add(a.id);
        }
        return out;
    }

    private static int rank(AgentInfo a) {
        switch (a.family()) {
            case "waiting":
                return 0;
            case "error":
                return 1;
            case "working":
                return 2;
            case "thinking":
                return 3;
            default:
                return a.active ? 4 : 5;
        }
    }

    private void reconcile() {
        WorldServer world = DimensionManager.getWorld(hqDimension());
        if (world == null) return;
        List<String> shown = displayedIds();
        List<String> over = new ArrayList<>();
        for (String id : agents.keySet()) if (!shown.contains(id)) over.add(id);
        overflowIds = over;
        // remove NPCs whose agent left the snapshot (or the display list)
        for (Iterator<Map.Entry<String, EntityHermesAgent>> it = entities.entrySet()
            .iterator(); it.hasNext();) {
            Map.Entry<String, EntityHermesAgent> en = it.next();
            if (!shown.contains(en.getKey()) || en.getValue().worldObj != world) {
                en.getValue()
                    .setDead();
                applied.remove(en.getKey());
                spots.remove(en.getKey());
                assigner.forget(en.getKey());
                it.remove();
                AgentCraftGTNH.LOG.info("removed NPC for {}", en.getKey());
            }
        }
        if (tick % 100 == 0) removeStrays(world);

        ChunkCoordinates spawn = world.getSpawnPoint();
        List<StationAssigner.Want> wants = new ArrayList<>();
        for (String id : shown) {
            AgentInfo raw = agents.get(id);
            EntityHermesAgent ent = entities.get(id);
            boolean alive = ent != null && !ent.isDead;
            wants.add(
                new StationAssigner.Want(
                    id,
                    raw.station,
                    raw.active || withWaiting(raw).waiting,
                    alive ? ent.posX : spawn.posX,
                    alive ? ent.posY : spawn.posY,
                    alive ? ent.posZ : spawn.posZ));
        }
        targets = assigner.assign(wants, anchors.all(), (bx, by, bz) -> standable(world, bx + 0.5, by, bz + 0.5));
        if (anchors.isEmpty()) {
            if (!noAnchorsLogged) {
                noAnchorsLogged = true;
                AgentCraftGTNH.LOG.warn(
                    "no HQ station anchors yet: NPCs stand in a row next to the world spawn; set them with /agentcraft anchor set <station>");
            }
        } else {
            noAnchorsLogged = false;
            for (String st : assigner.newlyMissing) {
                AgentCraftGTNH.LOG.warn(
                    "no anchor for station '{}': agents there use a free lounge slot, or stand next to the lounge / nearest anchor; set one with /agentcraft anchor set {}",
                    st,
                    st);
            }
        }

        for (int i = 0; i < shown.size(); i++) {
            String id = shown.get(i);
            AgentInfo a = view(agents.get(id));
            StationAssigner.Target t = targets.get(id);
            double[] spot = t != null ? safeSpot(world, t) : legacySpot(world, i);
            if (spot == null) continue; // chunk not loaded: retried every second
            EntityHermesAgent ent = entities.get(id);
            if (ent == null || ent.isDead || !world.loadedEntityList.contains(ent)) {
                ent = spawn(world, a, spot, t);
                continue;
            }
            double[] last = spots.get(id);
            if (last == null || last[0] != spot[0] || last[1] != spot[1] || last[2] != spot[2] || last[3] != spot[3]) {
                spots.put(id, spot);
                if (Config.verboseLog) {
                    AgentCraftGTNH.LOG.info(
                        "NPC {} -> {} ({} {} {}) wants station {}",
                        id,
                        t == null ? "row" : t.toString(),
                        fmt(spot[0]),
                        fmt(spot[1]),
                        fmt(spot[2]),
                        t == null ? agents.get(id).station : t.wantedStation);
                }
                ent.walkTo(spot[0], spot[1], spot[2], (float) spot[3]);
            }
            AgentInfo prev = applied.get(id);
            if (!a.sameView(prev)) {
                ent.applyInfo(a);
                applied.put(id, a);
                stateChanges++;
                if (Config.verboseLog) {
                    AgentCraftGTNH.LOG.info(
                        "NPC #{} {} nameplate: [{}] {} | {}{}",
                        ent.getEntityId(),
                        id,
                        a.family(),
                        a.name,
                        a.stateLine(),
                        a.waiting ? " | ! waiting on you" : "");
                }
            }
        }
        updateOverflowSign(world, over);
    }

    private static String fmt(double d) {
        return String.format("%.1f", d);
    }

    /** Spawn-time duplicate guard: an agent NPC this table does not own is removed (never saved, but be sure). */
    private void removeStrays(WorldServer world) {
        Set<EntityHermesAgent> owned = new HashSet<>(entities.values());
        for (Object o : new ArrayList<Object>(world.loadedEntityList)) {
            if (o instanceof EntityHermesAgent && !owned.contains(o)) {
                ((EntityHermesAgent) o).setDead();
                duplicatesRemoved++;
                AgentCraftGTNH.LOG.warn("removed stray agent NPC #{}", ((EntityHermesAgent) o).getEntityId());
            }
        }
    }

    /** x, y, z, yaw for a target; fan cells were checked by the assigner (stacked = the anchor itself). */
    private double[] safeSpot(WorldServer world, StationAssigner.Target t) {
        Anchor a = t.anchor;
        double x = t.x(), y = a.y, z = t.z();
        if (t.ring >= 0 && !t.stacked && !standable(world, x, y, z)) {
            x = a.x;
            z = a.z;
        }
        if (!world.getChunkProvider()
            .chunkExists(MathHelper.floor_double(x) >> 4, MathHelper.floor_double(z) >> 4)) return null;
        return new double[] { x, y, z, a.yaw };
    }

    private static boolean standable(WorldServer world, double x, double y, double z) {
        int bx = MathHelper.floor_double(x), by = MathHelper.floor_double(y), bz = MathHelper.floor_double(z);
        if (!world.getChunkProvider()
            .chunkExists(bx >> 4, bz >> 4)) return false;
        Block floor = world.getBlock(bx, by - 1, bz);
        Block feet = world.getBlock(bx, by, bz);
        Block head = world.getBlock(bx, by + 1, bz);
        return floor.getMaterial()
            .isSolid()
            && !feet.getMaterial()
                .blocksMovement()
            && !feet.getMaterial()
                .isLiquid()
            && !head.getMaterial()
                .blocksMovement();
    }

    /** No anchors at all: card 1's row next to the world spawn (or spawnX/Y/Z). */
    private double[] legacySpot(WorldServer world, int slot) {
        int x, y, z;
        if (Config.spawnAtWorldSpawn) {
            ChunkCoordinates s = world.getSpawnPoint();
            x = s.posX + 2 + slot * Config.spacing;
            z = s.posZ + 2;
            y = -1;
        } else {
            x = Config.spawnX + slot * Config.spacing;
            z = Config.spawnZ;
            y = Config.spawnY;
        }
        if (!world.getChunkProvider()
            .chunkExists(x >> 4, z >> 4)) return null;
        if (y < 0) y = world.getTopSolidOrLiquidBlock(x, z);
        return new double[] { x + 0.5, y, z + 0.5, 180.0 };
    }

    private EntityHermesAgent spawn(WorldServer world, AgentInfo a, double[] spot, StationAssigner.Target t) {
        EntityHermesAgent ent = new EntityHermesAgent(world);
        ent.setLocationAndAngles(spot[0], spot[1], spot[2], (float) spot[3], 0.0F);
        ent.applyInfo(a);
        world.spawnEntityInWorld(ent);
        ent.placeAt(spot[0], spot[1], spot[2], (float) spot[3]);
        entities.put(a.id, ent);
        applied.put(a.id, a);
        spots.put(a.id, spot);
        AgentCraftGTNH.LOG.info(
            "spawned NPC #{} for agent {} (\"{}\") at {} {} {} dim {} [{}] | [{}] {}",
            ent.getEntityId(),
            a.id,
            a.name,
            fmt(spot[0]),
            fmt(spot[1]),
            fmt(spot[2]),
            world.provider.dimensionId,
            t == null ? "row (no anchors)" : t.toString(),
            a.family(),
            a.stateLine());
        return ent;
    }

    /** The player's overflow sign (anchor overflow_sign = a vanilla sign they placed): "+N more agents". */
    private void updateOverflowSign(WorldServer world, List<String> over) {
        Anchor s = anchors.get(HqAnchors.OVERFLOW_SIGN);
        if (s == null) return;
        int x = MathHelper.floor_double(s.x), y = MathHelper.floor_double(s.y), z = MathHelper.floor_double(s.z);
        if (!world.getChunkProvider()
            .chunkExists(x >> 4, z >> 4)) return;
        TileEntity te = world.getTileEntity(x, y, z);
        if (!(te instanceof TileEntitySign)) return;
        String[] lines = new String[4];
        lines[0] = over.isEmpty() ? "All agents" : "+" + over.size() + " more agent" + (over.size() == 1 ? "" : "s");
        lines[1] = over.isEmpty() ? "are in the HQ" : "";
        lines[2] = "";
        lines[3] = "";
        for (int i = 0; i < Math.min(3, over.size()); i++) {
            AgentInfo a = agents.get(over.get(i));
            String n = a == null ? over.get(i) : a.name;
            lines[i + 1] = n.length() > 15 ? n.substring(0, 15) : n;
        }
        if (over.size() > 3) lines[3] = "+" + (over.size() - 2) + " others";
        String key = String.join("|", lines);
        if (key.equals(lastSign) && key.equals(String.join("|", ((TileEntitySign) te).signText))) return;
        lastSign = key;
        TileEntitySign sign = (TileEntitySign) te;
        System.arraycopy(lines, 0, sign.signText, 0, 4);
        sign.markDirty();
        world.markBlockForUpdate(x, y, z);
        AgentCraftGTNH.LOG.info("overflow sign at {} {} {}: {}", x, y, z, key);
    }

    private void broadcastAgents() {
        Net.AgentSync msg = new Net.AgentSync();
        msg.linkUp = linkUp;
        msg.fleet = fleetFamily();
        msg.overflow = overflowIds.size();
        for (AgentInfo a : agents.values()) {
            EntityHermesAgent ent = entities.get(a.id);
            msg.agents.add(view(a));
            msg.entityIds.add(ent == null || ent.isDead ? -1 : ent.getEntityId());
        }
        Net.sendToAll(msg);
    }

    /** Monitor tails: only agents whose last monitorLines changed; a full set to players who just joined. */
    private void broadcastLogs() {
        for (String id : agents.keySet()) {
            List<LogLine> tail = logTail(id, Config.monitorLines);
            Integer h = tail.hashCode();
            boolean changed = !h.equals(sentLogHash.get(id));
            if (!changed && needsLogs.isEmpty()) continue;
            Net.LogSync msg = new Net.LogSync();
            msg.agentId = id;
            msg.lines.addAll(tail);
            if (changed) {
                sentLogHash.put(id, h);
                Net.sendToAll(msg);
            } else {
                for (EntityPlayerMP p : needsLogs) Net.sendTo(msg, p);
            }
        }
        needsLogs.clear();
    }

    public void despawnAll() {
        for (EntityHermesAgent e : entities.values()) e.setDead();
        entities.clear();
        applied.clear();
        spots.clear();
    }
}
