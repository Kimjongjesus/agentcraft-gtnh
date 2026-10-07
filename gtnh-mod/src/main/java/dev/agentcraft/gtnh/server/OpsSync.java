package dev.agentcraft.gtnh.server;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import net.minecraft.entity.player.EntityPlayerMP;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import dev.agentcraft.gtnh.AgentCraftGTNH;
import dev.agentcraft.gtnh.Config;
import dev.agentcraft.gtnh.edit.Json;
import dev.agentcraft.gtnh.net.Net;
import dev.agentcraft.gtnh.ops.DecisionData;
import dev.agentcraft.gtnh.ops.OpenDecisions;
import dev.agentcraft.gtnh.ops.OpsData;
import dev.agentcraft.gtnh.ops.OpsModel;
import dev.agentcraft.gtnh.state.AgentInfo;

/**
 * Card 5b server state: the {@code ops.*} feeds and the open decisions, replicated to clients as two
 * binary blobs (like {@link BoardSync}). Read-only: nothing flows back to the adapter.
 *
 * <p>
 * Ops: {@link OpsModel} (the adapter's reference-receiver rules, bounded). Re-encoded at most once
 * per {@code opsSyncSeconds} and only while something changed; sent only if the bytes differ from
 * the last blob (change-only). Decisions: the open ones with the fields the toast and the decision
 * screen show, at most {@link DecisionData#MAX}; checked every second, sent only on change. Players
 * who just logged in get both current blobs.
 */
public final class OpsSync {

    private final OpsModel model = new OpsModel();
    /** Open decisions: details for the newest, ids for all; caps never fake a close (review r1). */
    private final OpenDecisions decisions = new OpenDecisions();
    private final Set<EntityPlayerMP> needsFull = new HashSet<>();
    private boolean decisionsDirty;
    private long lastOpsSend, lastDecisionSend;
    private byte[] lastOps = new byte[0], lastDecisions = new byte[0];
    private int gen;
    private String opsWorst = "none";
    public long opsBlobs, decisionBlobs, parseErrors;
    public int lastOpsBytes, lastDecisionBytes;

    public void reset() {
        decisions.reset();
        needsFull.clear();
        lastOps = new byte[0];
        lastDecisions = new byte[0];
        model.disconnected();
        opsWorst = "none";
        opsBlobs = decisionBlobs = parseErrors = 0;
    }

    public OpsModel model() {
        return model;
    }

    /** "none" | "ok" | "warn" | "error" (see {@link OpsData#worst}). */
    public String opsWorst() {
        return opsWorst;
    }

    public int openDecisionCount() {
        return decisions.size();
    }

    /** Group / source / provider names the ops data knows (bind hints). */
    public List<String> filters() {
        Set<String> out = new java.util.TreeSet<>();
        OpsData.View v = model.view();
        for (OpsData.Service s : v.services) {
            out.add(s.group);
            out.add(s.sourceId);
        }
        for (OpsData.Job j : v.jobs) out.add(j.sourceId);
        for (OpsData.Usage u : v.usage) out.add(u.provider);
        for (OpsData.Alert a : v.alerts) out.add(a.source);
        out.remove("");
        return new ArrayList<>(out);
    }

    // ---- protocol -------------------------------------------------------------------------

    /** ops.* messages; false if the type is not ours. */
    boolean apply(String type, JsonObject m) {
        if (!type.startsWith("ops.")) return false;
        try {
            model.apply(type, Json.parseObject(m.toString()));
        } catch (Json.ParseException e) {
            parseErrors++;
        }
        return true;
    }

    void linkDown() {
        model.disconnected();
        decisionsDirty = true;
    }

    void decisionsReset() {
        decisions.reset();
        decisionsDirty = true;
    }

    /** One adapter Decision (snapshot entry or decision.upsert): kept only while open. */
    void applyDecision(JsonObject d) {
        String id = str(d, "id", 96);
        if (id.isEmpty()) return;
        String status = str(d, "status", 16);
        String agent = str(d, "agentId", 64);
        if (!"open".equals(status) || agent.isEmpty()) {
            if (decisions.close(id)) decisionsDirty = true;
            return;
        }
        DecisionData.Decision x = new DecisionData.Decision();
        x.id = id;
        x.agentId = agent;
        x.kind = str(d, "kind", 16);
        x.question = str(d, "question", 240);
        x.context = str(d, "context", 160);
        x.taskId = str(d, "taskId", 96);
        x.createdAt = d.has("createdAt") && d.get("createdAt")
            .isJsonPrimitive() ? d.get("createdAt")
                .getAsLong() : 0L;
        if (d.has("options") && d.get("options")
            .isJsonArray()) {
            JsonArray a = d.getAsJsonArray("options");
            for (int i = 0; i < a.size() && x.options.size() < DecisionData.MAX_OPTIONS; i++) {
                JsonElement el = a.get(i);
                if (el.isJsonPrimitive()) x.options.add(OpsData.clean(el.getAsString(), 40));
            }
        }
        decisions.put(x);
        decisionsDirty = true;
    }

    private static String str(JsonObject o, String k, int max) {
        return o.has(k) && o.get(k)
            .isJsonPrimitive() ? OpsData.clean(
                o.get(k)
                    .getAsString(),
                max) : "";
    }

    /** Agent names/colours changed (agent.upsert): the decision blob carries them. */
    void agentsChanged() {
        if (!decisions.isEmpty()) decisionsDirty = true;
    }

    // ---- replication ----------------------------------------------------------------------

    void playerJoined(EntityPlayerMP p) {
        needsFull.add(p);
    }

    void tick(int tick, Map<String, AgentInfo> agents, boolean linkUp) {
        long now = System.currentTimeMillis();
        boolean opsDirty = model.takeDirty();
        if (opsDirty) opsWorst = OpsData.worst(model.view());
        if ((opsDirty || pendingOps) && now - lastOpsSend >= Config.opsSyncSeconds * 1000L) {
            pendingOps = false;
            lastOpsSend = now;
            byte[] b = encodeOps();
            if (!Arrays.equals(b, lastOps)) {
                lastOps = b;
                lastOpsBytes = b.length;
                opsBlobs++;
                send(Net.Blob.OPS, b, null);
            }
        } else if (opsDirty) {
            pendingOps = true; // throttled: send on a later tick
        }
        if (decisionsDirty && now - lastDecisionSend >= 1000L) {
            decisionsDirty = false;
            lastDecisionSend = now;
            byte[] b = encodeDecisions(agents, linkUp);
            if (!Arrays.equals(b, lastDecisions)) {
                lastDecisions = b;
                lastDecisionBytes = b.length;
                decisionBlobs++;
                send(Net.Blob.DECISIONS, b, null);
            }
        }
        if (!needsFull.isEmpty() && tick % 20 == 10) {
            for (EntityPlayerMP p : needsFull) {
                if (lastOps.length > 0) send(Net.Blob.OPS, lastOps, p);
                if (lastDecisions.length > 0) send(Net.Blob.DECISIONS, lastDecisions, p);
            }
            needsFull.clear();
        }
    }

    private boolean pendingOps;

    byte[] encodeOps() {
        OpsData.View v = model.view();
        return encode(out -> OpsData.write(out, v.live, v.services, v.jobs, v.usage, v.alerts, v.sources));
    }

    byte[] encodeDecisions(Map<String, AgentInfo> agents, boolean linkUp) {
        // every open id (newest first) + whether that list is complete, so the client can tell
        // "cut from the details" apart from "closed" (review r1); details: the newest MAX (the toast
        // is about new ones; the screen says when the list is cut)
        final List<String> ids = decisions.ids();
        final boolean complete = decisions.complete();
        final List<DecisionData.Decision> out = decisions.newest(DecisionData.MAX);
        for (DecisionData.Decision d : out) {
            AgentInfo a = agents.get(d.agentId);
            d.agentName = a == null || a.name.isEmpty() ? d.agentId : a.name;
            d.agentColor = a == null ? 0x9C9488 : a.color & 0xFFFFFF;
        }
        return encode(o -> DecisionData.write(o, linkUp, out, ids, complete));
    }

    private void send(byte kind, byte[] data, EntityPlayerMP to) {
        gen++;
        for (Net.Blob part : Net.Blob.split(kind, gen, data)) {
            if (to == null) Net.sendToAll(part);
            else Net.sendTo(part, to);
        }
        if (Config.verboseLog && to == null) {
            AgentCraftGTNH.LOG.info(
                "{} sync: {} bytes ({} services, {} jobs, {} usage, {} alerts, {} open decisions)",
                kind == Net.Blob.OPS ? "ops" : "decision",
                data.length,
                model.count("service"),
                model.count("job"),
                model.count("usage"),
                model.count("alert"),
                decisions.size());
        }
    }

    private interface Writer {

        void write(DataOutputStream out) throws IOException;
    }

    private static byte[] encode(Writer w) {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(bos)) {
            w.write(out);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        return bos.toByteArray();
    }
}
