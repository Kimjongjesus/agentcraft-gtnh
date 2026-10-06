package dev.agentcraft.gtnh.edit;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Hard limits and protected areas for the edit tool (from the config's {@code edit} category).
 * Pure; the engine consults it before every change.
 */
public final class EditRules {

    /** A protected sphere: no panel may be placed or removed within {@code r} blocks of (x, y, z). */
    public static final class Exclusion {

        public final int x, y, z, r;
        public final String text;

        public Exclusion(int x, int y, int z, int r, String text) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.r = r;
            this.text = text;
        }

        public boolean covers(Pos p) {
            long dx = p.x - x, dy = p.y - y, dz = p.z - z;
            return dx * dx + dy * dy + dz * dz <= (long) r * r;
        }
    }

    public int maxPanels = 256;
    public int maxLayoutBytes = 256 * 1024;
    public int undoSteps = 64;
    public int persistSteps = 20;
    public int maxChangesPerOp = 512;
    public int maxImportSpan = 48;
    public int maxSnapshots = 64;
    public double editsPerSecond = 4;
    public double burst = 8;
    public final List<Exclusion> exclusions = new ArrayList<>();

    /**
     * Parse "x,y,z,r" or "x y z r" entries (optional "# note" after); bad entries are returned as
     * errors and ignored. The dimension is the HQ's (the tool only edits there).
     */
    public List<String> setExclusions(String[] entries) {
        exclusions.clear();
        List<String> errors = new ArrayList<>();
        if (entries == null) return errors;
        for (String raw : entries) {
            if (raw == null || raw.trim()
                .isEmpty()) continue;
            String e = raw;
            String note = "";
            int hash = e.indexOf('#');
            if (hash >= 0) {
                note = e.substring(hash + 1)
                    .trim();
                e = e.substring(0, hash);
            }
            String[] p = e.trim()
                .split("[,\\s]+");
            try {
                if (p.length != 4) throw new NumberFormatException();
                int r = Integer.parseInt(p[3]);
                if (r < 0 || r > 512) throw new NumberFormatException();
                exclusions.add(new Exclusion(Integer.parseInt(p[0]), Integer.parseInt(p[1]), Integer.parseInt(p[2]), r, note.isEmpty() ? e.trim() : note));
            } catch (NumberFormatException ex) {
                errors.add(raw);
            }
        }
        return errors;
    }

    /** The exclusion covering p, or null. */
    public Exclusion protectedAt(Pos p) {
        for (Exclusion x : exclusions) if (x.covers(p)) return x;
        return null;
    }

    /** Token bucket per actor: {@code editsPerSecond} refill, {@code burst} capacity. */
    public static final class RateLimiter {

        private final Map<String, double[]> buckets = new HashMap<>();

        /** @return true when the actor may make one more edit at time {@code nowMs} */
        public synchronized boolean take(String who, long nowMs, double perSecond, double burst) {
            double[] b = buckets.get(who);
            if (b == null) {
                b = new double[] { burst, nowMs };
                buckets.put(who, b);
            }
            b[0] = Math.min(burst, b[0] + (nowMs - b[1]) / 1000.0 * perSecond);
            b[1] = nowMs;
            if (b[0] < 1.0) return false;
            b[0] -= 1.0;
            return true;
        }
    }
}
