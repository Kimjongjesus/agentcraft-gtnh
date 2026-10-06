package dev.agentcraft.gtnh.ui;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Screen-space layout of billboarded nameplates (pure Java, no Minecraft classes, so it is unit
 * tested in {@code dev/tests/PlateLayoutCheck}). Every plate is projected with the camera's yaw and
 * pitch; the nearest plate keeps its natural spot and each farther one takes the first candidate
 * that does not overlap an already placed plate: full plate on tier 0, compact plate (name + state),
 * then a one-line mini plate (name only) on tier 0, then the same three one tier higher, and so on;
 * only when no text plate fits on any tier does it shrink to its bare marker ("!" or status dot).
 * If nothing fits, the candidate with the least overlap wins. A plate keeps last frame's choice
 * while that still fits (no flicker; the caller drops that memory every few seconds so plates grow
 * back when room frees up).
 *
 * <p>
 * Coordinates are in blocks relative to the camera (Minecraft render coordinates): {@code x, y, z}
 * is the bottom centre of the plate on tier 0. Widths are split at the centre ({@code left} and
 * {@code right}) because the "!" badge sticks out on the left.
 */
public final class PlateDeclutter {

    /**
     * Plate modes: name + "state · activity", name + state, name only (one line), and, when nothing
     * else fits, just the marker (the "!" badge, or a status dot). Look at an agent (crosshair) to
     * give its plate priority: it is placed first, so it shows in full.
     */
    public static final int FULL = 0, COMPACT = 1, MINI = 2, DOT = 3, MODES = 4;

    public static final class Plate {

        public final int id;
        public final double x, y, z;
        /** Per mode (FULL, COMPACT, MINI, DOT): extents from the centre and height. */
        public final float[] left, right, height;
        /** Placed before every other plate (the agent under the crosshair). */
        public boolean priority;
        /** Result: tier (0 = natural height) and mode. */
        public int tier, mode;
        /** Previous frame's choice, tried first; prevTier -1 = none. */
        public int prevTier = -1, prevMode;

        /** left, right, height: one value per mode (full, compact, mini, dot), in blocks. */
        public Plate(int id, double x, double y, double z, float[] left, float[] right, float[] height) {
            this.id = id;
            this.x = x;
            this.y = y;
            this.z = z;
            this.left = left;
            this.right = right;
            this.height = height;
        }
    }

    private PlateDeclutter() {}

    /** Screen rect in camera units (x right, y up, both divided by depth). */
    static final class Rect {

        final double x0, y0, x1, y1;

        Rect(double x0, double y0, double x1, double y1) {
            this.x0 = x0;
            this.y0 = y0;
            this.x1 = x1;
            this.y1 = y1;
        }

        double overlap(Rect o) {
            double w = Math.min(x1, o.x1) - Math.max(x0, o.x0), h = Math.min(y1, o.y1) - Math.max(y0, o.y0);
            return w <= 0 || h <= 0 ? 0 : w * h;
        }
    }

    /** Camera basis: forward, right, up (Minecraft yaw 0 = looking south/+z, 90 = west; pitch + = down). */
    static double[][] basis(double yawDeg, double pitchDeg) {
        double yw = Math.toRadians(yawDeg), pt = Math.toRadians(pitchDeg);
        double fx = -Math.sin(yw) * Math.cos(pt), fy = -Math.sin(pt), fz = Math.cos(yw) * Math.cos(pt);
        double rx = -Math.cos(yw), ry = 0, rz = -Math.sin(yw);
        // up = right x forward
        double ux = ry * fz - rz * fy, uy = rz * fx - rx * fz, uz = rx * fy - ry * fx;
        return new double[][] { { fx, fy, fz }, { rx, ry, rz }, { ux, uy, uz } };
    }

    static double dot(double[] v, double x, double y, double z) {
        return v[0] * x + v[1] * y + v[2] * z;
    }

    /**
     * @param tierStep world height between tiers (blocks)
     * @param maxTiers number of tiers to try (1 = never raise)
     */
    public static void layout(List<Plate> plates, double yawDeg, double pitchDeg, double tierStep, int maxTiers) {
        final double[][] b = basis(yawDeg, pitchDeg);
        List<Plate> order = new ArrayList<>(plates);
        Collections.sort(order, (p, q) -> {
            if (p.priority != q.priority) return p.priority ? -1 : 1;
            int c = Double.compare(dot(b[0], p.x, p.y, p.z), dot(b[0], q.x, q.y, q.z));
            return c != 0 ? c : Integer.compare(p.id, q.id);
        });
        List<Rect> placed = new ArrayList<>();
        for (Plate p : order) {
            double depth = dot(b[0], p.x, p.y, p.z);
            if (depth < 0.3) { // behind or at the camera: not on screen, nothing to avoid
                p.tier = 0;
                p.mode = FULL;
                continue;
            }
            int bestTier = 0, bestMode = FULL;
            double bestOverlap = Double.MAX_VALUE;
            Rect bestRect = null;
            // the previous choice first, then text plates tier by tier (full, compact, mini), then
            // the bare marker tier by tier
            int text = maxTiers * (MODES - 1), n = text + maxTiers;
            for (int k = -1; k < n; k++) {
                int t, mode;
                if (k < 0) {
                    if (p.prevTier < 0 || p.prevTier >= maxTiers) continue;
                    t = p.prevTier;
                    mode = p.prevMode;
                } else if (k < text) {
                    t = k / (MODES - 1);
                    mode = k % (MODES - 1);
                } else {
                    t = k - text;
                    mode = DOT;
                }
                Rect r = rect(p, t, mode, tierStep, depth, b);
                double ov = 0;
                for (Rect o : placed) ov += o.overlap(r);
                if (ov < bestOverlap - 1e-12) {
                    bestOverlap = ov;
                    bestTier = t;
                    bestMode = mode;
                    bestRect = r;
                }
                if (ov <= 0) break;
            }
            p.tier = bestTier;
            p.mode = bestMode;
            if (bestRect != null) placed.add(bestRect);
        }
    }

    static Rect rect(Plate p, int tier, int mode, double step, double depth, double[][] b) {
        return rect(p, tier, mode, step, depth, b, 0.04);
    }

    /** @param air gap kept around the plate (blocks at the plate's depth) */
    static Rect rect(Plate p, int tier, int mode, double step, double depth, double[][] b, double air) {
        double y = p.y + tier * step;
        double sx = dot(b[1], p.x, y, p.z) / depth;
        double sy = dot(b[2], p.x, y, p.z) / depth;
        double l = p.left[mode] / depth, r = p.right[mode] / depth;
        double m = air / depth;
        return new Rect(sx - l - m, sy - m, sx + r + m, sy + p.height[mode] / depth + m);
    }

    /** Total pairwise screen overlap of the plates as they are now, without the air gap (for tests). */
    public static double totalOverlap(List<Plate> plates, double yawDeg, double pitchDeg, double tierStep) {
        double[][] b = basis(yawDeg, pitchDeg);
        List<Rect> rs = new ArrayList<>();
        for (Plate p : plates) {
            double depth = dot(b[0], p.x, p.y, p.z);
            if (depth < 0.3) continue;
            rs.add(rect(p, p.tier, p.mode, tierStep, depth, b, 0));
        }
        double s = 0;
        for (int i = 0; i < rs.size(); i++) for (int j = i + 1; j < rs.size(); j++) s += rs.get(i).overlap(rs.get(j));
        return s;
    }
}
