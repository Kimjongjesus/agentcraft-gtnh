package dev.agentcraft.gtnh.ui.panel;

import dev.agentcraft.gtnh.ui.Theme;

/**
 * Everything a {@link PanelRenderer} needs for one frame: the canvas size (canvas units and
 * blocks), the binding and its resolved data, the theme, the viewer distance and the level of
 * detail chosen from it.
 */
public final class PanelContext {

    /** Level of detail: FAR = headline numbers only, MID = headers + one-line titles, NEAR = all. */
    public static final int FAR = 0, MID = 1, NEAR = 2;

    public final String panelId;
    public final String binding;
    public final Object data;
    public final Theme theme;
    /** canvas units per block */
    public final float px;
    /** canvas size (canvas units) and size in blocks */
    public final float w, h;
    public final int widthBlocks, heightBlocks;
    /** viewer distance (blocks) and on-screen pixels per block at that distance */
    public final double distance, screenPxPerBlock;
    public final int lod;
    public final long now;

    public PanelContext(String panelId, String binding, Object data, Theme theme, float px, int widthBlocks, int heightBlocks,
        double distance, double screenPxPerBlock, int lod, long now) {
        this.panelId = panelId;
        this.binding = binding;
        this.data = data;
        this.theme = theme;
        this.px = px;
        this.widthBlocks = widthBlocks;
        this.heightBlocks = heightBlocks;
        this.w = widthBlocks * px;
        this.h = heightBlocks * px;
        this.distance = distance;
        this.screenPxPerBlock = screenPxPerBlock;
        this.lod = lod;
        this.now = now;
    }

    /** Canvas units for a physical size in blocks (e.g. em(0.085) = an 0.085-block-tall em). */
    public float em(double blocks) {
        return (float) (blocks * px);
    }

    /** On-screen pixel height of a size given in canvas units, at the current distance. */
    public double screenPx(float canvasUnits) {
        return canvasUnits / px * screenPxPerBlock;
    }
}
