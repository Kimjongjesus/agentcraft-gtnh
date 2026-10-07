package com.robertsnest.aifactory.telemetry;

/**
 * Which world region a capture is actually about.
 *
 * <p>
 * <b>The old code guessed.</b> It anchored the survey on
 * {@code playerEntityList.get(0)} and swept tile entities from every loaded
 * dimension, then reported the result as "the base". On a server with two
 * players that is a different base each request, and a consumer comparing two
 * captures would compute trends across unrelated regions.
 *
 * <p>
 * A scope is therefore explicit and travels with every capture: either an
 * operator-configured dimension and bounded box, or a clearly labelled
 * player-relative box that the caller asked for by name. There is no third
 * option and no implicit fallback to "wherever someone happens to be".
 */
public final class BaseScope {

    /** How this scope was chosen. */
    public enum Kind {
        /** An operator wrote the dimension and box into the mod config. */
        CONFIGURED,
        /** The caller explicitly asked for a box around a named player. */
        PLAYER_RELATIVE,
        /** No base is configured and none was requested. Nothing is in scope. */
        NONE
    }

    private final Kind kind;
    private final String label;
    private final int dimensionId;
    private final String dimensionName;
    private final int centerX;
    private final int centerY;
    private final int centerZ;
    private final int radius;
    private final int height;
    private final String anchor;

    public static BaseScope none(String label) {
        return new BaseScope(Kind.NONE, label, 0, "none", 0, 0, 0, 0, 0, null);
    }

    public BaseScope(Kind kind, String label, int dimensionId, String dimensionName, int centerX, int centerY,
        int centerZ, int radius, int height, String anchor) {
        this.kind = kind == null ? Kind.NONE : kind;
        this.label = label == null ? "base" : label;
        this.dimensionId = dimensionId;
        this.dimensionName = dimensionName == null ? "dim" + dimensionId : dimensionName;
        this.centerX = centerX;
        this.centerY = centerY;
        this.centerZ = centerZ;
        this.radius = Math.max(0, radius);
        this.height = Math.max(0, height);
        this.anchor = anchor;
    }

    public Kind kind() {
        return kind;
    }

    /** Operator-chosen name, so a consumer can say which base it is describing. */
    public String label() {
        return label;
    }

    public int dimensionId() {
        return dimensionId;
    }

    public String dimensionName() {
        return dimensionName;
    }

    public int centerX() {
        return centerX;
    }

    public int centerY() {
        return centerY;
    }

    public int centerZ() {
        return centerZ;
    }

    public int radius() {
        return radius;
    }

    public int height() {
        return height;
    }

    /** For PLAYER_RELATIVE: the player name the box follows. Null otherwise. */
    public String anchor() {
        return anchor;
    }

    public boolean defined() {
        return kind != Kind.NONE;
    }

    public int minX() {
        return centerX - radius;
    }

    public int maxX() {
        return centerX + radius;
    }

    public int minZ() {
        return centerZ - radius;
    }

    public int maxZ() {
        return centerZ + radius;
    }

    /** Clamped to the 1.7.10 world height; reading outside it throws. */
    public int minY() {
        return Math.max(0, centerY - height);
    }

    /** Clamped to the 1.7.10 world height; reading outside it throws. */
    public int maxY() {
        return Math.min(255, centerY + height);
    }

    /**
     * True when a position in the given dimension is inside this scope.
     *
     * <p>
     * Dimension is checked first and never assumed: two identically named
     * machines at identical coordinates in the Overworld and the Nether are
     * different machines, and merging them is exactly the identity collision
     * the previous release shipped.
     */
    public boolean contains(int dimension, int x, int y, int z) {
        if (!defined() || dimension != dimensionId) {
            return false;
        }
        return x >= minX() && x <= maxX() && z >= minZ() && z <= maxZ() && y >= minY() && y <= maxY();
    }

    /**
     * A stable identity for this scope, so history from two different bases is
     * never averaged together.
     */
    public String identity() {
        if (!defined()) {
            return "none";
        }
        return kind.name()
            .toLowerCase(java.util.Locale.ROOT) + ":dim"
            + dimensionId
            + ":"
            + centerX
            + ","
            + centerY
            + ","
            + centerZ
            + ":r"
            + radius
            + "h"
            + height;
    }
}
