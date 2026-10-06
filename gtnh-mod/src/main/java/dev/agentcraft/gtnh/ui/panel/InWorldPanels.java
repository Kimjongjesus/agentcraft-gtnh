package dev.agentcraft.gtnh.ui.panel;

import java.util.WeakHashMap;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.OpenGlHelper;

import org.lwjgl.opengl.GL11;

import dev.agentcraft.gtnh.block.TileAgentCraft;
import dev.agentcraft.gtnh.ui.Theme;
import dev.agentcraft.gtnh.ui.Ui;

/**
 * Draws a block's panel: looks up the layout entry, resolves the data source, chooses the level of
 * detail from the viewer distance (with hysteresis, so it does not flicker at a threshold), sets up
 * the canvas on the block's front face (the block is the bottom centre of a W x H screen) and calls
 * the registered renderer.
 */
public final class InWorldPanels {

    private static final WeakHashMap<TileAgentCraft, Integer> LAST_LOD = new WeakHashMap<>();

    private InWorldPanels() {}

    /** On-screen pixels per block at distance d for the current window and FOV. */
    public static double screenPxPerBlock(double d) {
        Minecraft mc = Minecraft.getMinecraft();
        double fov = mc.gameSettings.fovSetting;
        if (fov < 10 || fov > 170) fov = 70;
        return mc.displayHeight / (2.0 * Math.tan(Math.toRadians(fov) / 2.0) * Math.max(0.5, d));
    }

    static int lod(TileAgentCraft t, double ppb) {
        Integer prev = LAST_LOD.get(t);
        double near = PanelLayout.nearPx, mid = PanelLayout.midPx;
        int l;
        if (prev == null) {
            l = ppb >= near ? PanelContext.NEAR : ppb >= mid ? PanelContext.MID : PanelContext.FAR;
        } else {
            // 10 % band: switch up only past the threshold * 1.1, down only below threshold * 0.9
            l = prev;
            if (prev == PanelContext.NEAR && ppb < near * 0.9) l = ppb >= mid ? PanelContext.MID : PanelContext.FAR;
            else if (prev == PanelContext.MID && ppb >= near * 1.1) l = PanelContext.NEAR;
            else if (prev == PanelContext.MID && ppb < mid * 0.9) l = PanelContext.FAR;
            else if (prev == PanelContext.FAR && ppb >= mid * 1.1) l = ppb >= near * 1.1 ? PanelContext.NEAR : PanelContext.MID;
        }
        LAST_LOD.put(t, l);
        return l;
    }

    /**
     * @param blockKind layout key ("task_wall", "goal_atrium", "monitor")
     * @param distance  viewer distance to the block centre (blocks)
     */
    public static void render(TileAgentCraft t, String blockKind, double x, double y, double z, double distance) {
        PanelLayout.poll();
        int dim = t.getWorldObj() == null ? 0 : t.getWorldObj().provider.dimensionId;
        PanelLayout.Entry e = PanelLayout.entry(blockKind, dim, t.xCoord, t.yCoord, t.zCoord);
        if (e == null) return;
        PanelRenderer r = PanelRegistry.get(e.panel);
        if (r == null) return;
        double ppb = screenPxPerBlock(distance);
        Theme th = Theme.byId(e.theme);
        PanelContext c = new PanelContext(
            e.panel,
            t.binding,
            PanelRegistry.resolve(r.source(), t.binding),
            th,
            e.pxPerBlock,
            t.screenW,
            t.screenH,
            distance,
            ppb,
            lod(t, ppb),
            System.currentTimeMillis());
        beginCanvas(t, x, y, z, e.pxPerBlock);
        // frame and background write depth (the screen occludes what is behind it), then painter's order
        float f = c.em(0.035);
        Ui.rectZ(-f, -f, c.w + f, c.h + f, 0xFF000000 | th.frame, -0.2);
        Ui.rectZ(0, 0, c.w, c.h, 0xFF000000 | th.bg, -0.1);
        GL11.glDepthMask(false);
        try {
            r.render(c);
        } finally {
            endCanvas();
        }
    }

    /** Canvas on the block's front face: origin = top-left of a W x H screen, +x right, +y down. */
    static void beginCanvas(TileAgentCraft t, double x, double y, double z, int px) {
        int meta = t.getBlockMetadata();
        float rot = meta == 2 ? 180.0F : meta == 4 ? -90.0F : meta == 5 ? 90.0F : 0.0F;
        float s = 1.0F / px;
        GL11.glPushMatrix();
        GL11.glTranslated(x + 0.5, y + 0.5, z + 0.5);
        GL11.glRotatef(rot, 0.0F, 1.0F, 0.0F);
        GL11.glTranslated(-t.screenW / 2.0, -0.5 + t.screenH, 0.512);
        GL11.glScalef(s, -s, s);
        GL11.glNormal3f(0.0F, 0.0F, 1.0F);
        GL11.glDisable(GL11.GL_LIGHTING);
        GL11.glDisable(GL11.GL_CULL_FACE);
        OpenGlHelper.setLightmapTextureCoords(OpenGlHelper.lightmapTexUnit, 240.0F, 240.0F);
    }

    static void endCanvas() {
        GL11.glDepthMask(true);
        GL11.glEnable(GL11.GL_CULL_FACE);
        GL11.glEnable(GL11.GL_LIGHTING);
        GL11.glEnable(GL11.GL_TEXTURE_2D);
        GL11.glColor4f(1.0F, 1.0F, 1.0F, 1.0F);
        GL11.glPopMatrix();
    }
}
