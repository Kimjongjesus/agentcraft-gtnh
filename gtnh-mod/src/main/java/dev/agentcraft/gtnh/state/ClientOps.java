package dev.agentcraft.gtnh.state;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.util.Collections;
import java.util.List;

import dev.agentcraft.gtnh.AgentCraftGTNH;
import dev.agentcraft.gtnh.net.Net;
import dev.agentcraft.gtnh.ops.DecisionData;
import dev.agentcraft.gtnh.ops.OpsData;

/**
 * Card 5b client mirror: the ops view (fleet / cron / usage / alert panels) and the open decisions
 * (decision toast + decision screen), rebuilt from the server's blobs. Immutable snapshots swapped
 * atomically; renderers and screens read them without locks.
 */
public final class ClientOps {

    public static volatile OpsData.View view = new OpsData.View();
    public static volatile boolean haveOps;
    public static volatile List<DecisionData.Decision> decisions = Collections.emptyList();
    public static volatile boolean decisionsLive;
    /** The whole last decision blob (details + every open id + complete flag) for the toast policy. */
    public static volatile DecisionData.Snapshot decisionSnapshot = new DecisionData.Snapshot();
    public static volatile long version, decisionVersion, opsUpdates, decisionUpdates, lastOpsAt;

    private ClientOps() {}

    static void accept(byte kind, byte[] whole) {
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(whole))) {
            if (kind == Net.Blob.OPS) {
                view = OpsData.read(in);
                haveOps = true;
                opsUpdates++;
                lastOpsAt = System.currentTimeMillis();
                version++;
            } else {
                DecisionData.Snapshot s = DecisionData.read(in);
                decisionSnapshot = s;
                decisions = Collections.unmodifiableList(s.open);
                decisionsLive = s.live;
                decisionUpdates++;
                decisionVersion++;
            }
        } catch (IOException | RuntimeException e) {
            AgentCraftGTNH.LOG.warn("ignoring malformed {} blob: {}", kind == Net.Blob.OPS ? "ops" : "decision", e.toString());
        }
    }

    /** Left the server: forget its ops data and decisions (the toast's seen-set is kept). */
    public static void reset() {
        view = new OpsData.View();
        haveOps = false;
        decisions = Collections.emptyList();
        decisionSnapshot = new DecisionData.Snapshot();
        decisionsLive = false;
        version++;
        decisionVersion++;
    }

    /** "none" | "ok" | "warn" | "error" for the atrium's ops line. */
    public static String worst() {
        return OpsData.worst(view);
    }

    public static DecisionData.Decision decision(String id) {
        for (DecisionData.Decision d : decisions) if (d.id.equals(id)) return d;
        return null;
    }
}
