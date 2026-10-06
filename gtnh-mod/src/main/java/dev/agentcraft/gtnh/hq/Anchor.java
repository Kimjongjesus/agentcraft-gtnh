package dev.agentcraft.gtnh.hq;

import java.util.Locale;

/**
 * A named spot in Eli's HQ. Stations and agent spots: FEET position + facing yaw. Block anchors
 * (overflow_sign): the block position. cam_*: eye position + yaw/pitch (QA cameras, teleport targets).
 * Yaw: 0 = south (+Z), 90 = west (-X), 180 = north (-Z), -90 = east (+X), as in Minecraft.
 */
public final class Anchor {

    public final String name;
    public final double x, y, z;
    public final float yaw, pitch;

    public Anchor(String name, double x, double y, double z, float yaw, float pitch) {
        this.name = name;
        this.x = x;
        this.y = y;
        this.z = z;
        this.yaw = normYaw(yaw);
        this.pitch = pitch;
    }

    public static float normYaw(float yaw) {
        float y = yaw % 360.0F;
        if (y >= 180.0F) y -= 360.0F;
        if (y < -180.0F) y += 360.0F;
        return y;
    }

    /** north/south/east/west for a yaw (nearest quarter). */
    public static String facingName(float yaw) {
        int q = Math.round(normYaw(yaw) / 90.0F);
        switch (q) {
            case 0:
                return "south";
            case 1:
                return "west";
            case -1:
                return "east";
            default:
                return "north";
        }
    }

    /** Parses north/south/east/west (or n/s/e/w) or a number of degrees; null if neither. */
    public static Float parseFacing(String s) {
        if (s == null) return null;
        switch (s.toLowerCase(Locale.ROOT)) {
            case "south":
            case "s":
                return 0.0F;
            case "west":
            case "w":
                return 90.0F;
            case "north":
            case "n":
                return 180.0F;
            case "east":
            case "e":
                return -90.0F;
            default:
                try {
                    return normYaw(Float.parseFloat(s));
                } catch (NumberFormatException e) {
                    return null;
                }
        }
    }

    public double distSq(double px, double py, double pz) {
        double dx = x - px, dy = y - py, dz = z - pz;
        return dx * dx + dy * dy + dz * dz;
    }

    @Override
    public String toString() {
        return String.format(Locale.ROOT, "%s %.1f %.1f %.1f facing %s", name, x, y, z, facingName(yaw));
    }
}
