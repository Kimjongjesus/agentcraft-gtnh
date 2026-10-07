package dev.agentcraft.gtnh.ops;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Card 5b (review r1): the server's set of OPEN decisions, bounded, without ever turning a cap into
 * a fake close. Details are held for the most recently upserted {@link #hold} decisions; an open
 * decision pushed out of that window keeps its id in an id-only set, so it is still reported as
 * open. Only an explicit close (status not "open") or a new adapter snapshot ({@link #reset()})
 * removes an id. If even the id-only set overflows, {@link #complete()} turns false until the next
 * snapshot, and clients then treat a missing id as "unknown", never as "closed". Pure Java.
 */
public final class OpenDecisions {

    public final int hold, maxIds;
    private final Map<String, DecisionData.Decision> details = new LinkedHashMap<>();
    private final Set<String> idOnly = new LinkedHashSet<>();
    private boolean lost;

    public OpenDecisions() {
        this(4 * DecisionData.MAX, 4096);
    }

    public OpenDecisions(int hold, int maxIds) {
        this.hold = Math.max(DecisionData.MAX, hold);
        this.maxIds = Math.max(0, maxIds);
    }

    /** A new adapter snapshot: start over (the snapshot lists every open decision). */
    public void reset() {
        details.clear();
        idOnly.clear();
        lost = false;
    }

    /** An open decision (new or updated). */
    public void put(DecisionData.Decision d) {
        if (d.id.isEmpty()) return;
        details.remove(d.id);
        details.put(d.id, d);
        idOnly.remove(d.id);
        while (details.size() > hold) {
            Iterator<String> it = details.keySet()
                .iterator();
            String old = it.next();
            it.remove();
            if (idOnly.size() < maxIds) idOnly.add(old);
            else lost = true; // an open id is no longer known: the id list is incomplete
        }
    }

    /** An explicit close (status no longer open). True if it was open here. */
    public boolean close(String id) {
        boolean a = details.remove(id) != null, b = idOnly.remove(id);
        return a || b;
    }

    public boolean isEmpty() {
        return details.isEmpty() && idOnly.isEmpty();
    }

    /** Open decisions known by id (with or without details). */
    public int size() {
        return details.size() + idOnly.size();
    }

    /** The id list holds every open decision (false after an id-only overflow, until a reset). */
    public boolean complete() {
        return !lost;
    }

    /** Details of the newest {@code max} decisions by createdAt, oldest first. */
    public List<DecisionData.Decision> newest(int max) {
        List<DecisionData.Decision> open = new ArrayList<>(details.values());
        Collections.sort(open, (a, b) -> Long.compare(a.createdAt, b.createdAt));
        return open.size() > max ? new ArrayList<>(open.subList(open.size() - max, open.size())) : open;
    }

    /** Every open id, newest first: held ones by createdAt, then the id-only ones newest first. */
    public List<String> ids() {
        List<DecisionData.Decision> held = newest(Integer.MAX_VALUE);
        List<String> out = new ArrayList<>(size());
        for (int i = held.size() - 1; i >= 0; i--) out.add(held.get(i).id);
        List<String> older = new ArrayList<>(idOnly);
        for (int i = older.size() - 1; i >= 0; i--) out.add(older.get(i));
        return out;
    }
}
