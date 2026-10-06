package dev.agentcraft.gtnh.client;

import java.util.List;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.renderer.OpenGlHelper;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.entity.RenderManager;
import net.minecraft.entity.Entity;
import net.minecraftforge.client.event.RenderWorldLastEvent;

import org.lwjgl.opengl.GL11;

import dev.agentcraft.gtnh.hq.StationAssigner;
import dev.agentcraft.gtnh.state.ClientAgentCache;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;

/**
 * Op debug overlay for /agentcraft anchor show: a floating label and a facing arrow at every anchor
 * within 48 blocks, green for stations/agent spots, cyan for cam_*, yellow for block anchors, plus
 * the stations that have no anchor in the top-left corner of the screen. Shown for a limited time.
 */
public class AnchorOverlayRenderer {

    @SubscribeEvent
    public void onRenderWorldLast(RenderWorldLastEvent e) {
        if (System.currentTimeMillis() > ClientAgentCache.overlayUntil) return;
        Minecraft mc = Minecraft.getMinecraft();
        Entity view = mc.renderViewEntity;
        if (view == null) return;
        double px = view.lastTickPosX + (view.posX - view.lastTickPosX) * e.partialTicks;
        double py = view.lastTickPosY + (view.posY - view.lastTickPosY) * e.partialTicks;
        double pz = view.lastTickPosZ + (view.posZ - view.lastTickPosZ) * e.partialTicks;
        List<String> names = ClientAgentCache.overlayNames;
        List<double[]> spots = ClientAgentCache.overlaySpots;
        for (int i = 0; i < names.size() && i < spots.size(); i++) {
            double[] s = spots.get(i);
            double dx = s[0] - px, dy = s[1] - py, dz = s[2] - pz;
            if (dx * dx + dy * dy + dz * dz > 48 * 48) continue;
            String n = names.get(i);
            int color = n.startsWith("cam_") ? 0x5FC4C0 : StationAssigner.isStandingAnchor(n) ? 0x7FD77F : 0xE8C547;
            drawArrow(dx, dy + 0.05, dz, (float) s[3], color);
            drawLabel(mc.fontRenderer, n, dx, dy + 2.3, dz, color);
        }
    }

    /** Missing stations on the HUD (left column) while the overlay is shown. */
    @SubscribeEvent
    public void onHudText(net.minecraftforge.client.event.RenderGameOverlayEvent.Text e) {
        if (System.currentTimeMillis() > ClientAgentCache.overlayUntil) return;
        List<String> missing = ClientAgentCache.overlayMissing;
        e.left.add("\u00a7a[AgentCraft] anchors: " + ClientAgentCache.overlayNames.size());
        e.left.add(missing.isEmpty() ? "\u00a7aevery station has an anchor" : "\u00a7cno anchor: " + String.join(", ", missing));
    }

    private static void drawArrow(double x, double y, double z, float yaw, int color) {
        GL11.glPushMatrix();
        GL11.glTranslated(x, y, z);
        GL11.glRotatef(-yaw, 0.0F, 1.0F, 0.0F);
        GL11.glDisable(GL11.GL_LIGHTING);
        GL11.glDisable(GL11.GL_TEXTURE_2D);
        GL11.glDisable(GL11.GL_CULL_FACE);
        GL11.glEnable(GL11.GL_BLEND);
        OpenGlHelper.glBlendFunc(770, 771, 1, 0);
        Tessellator t = Tessellator.instance;
        t.startDrawing(GL11.GL_TRIANGLES);
        t.setColorRGBA_F(((color >> 16) & 0xFF) / 255.0F, ((color >> 8) & 0xFF) / 255.0F, (color & 0xFF) / 255.0F, 0.8F);
        // points to +Z (yaw 0 = south), rotated to the anchor's facing
        t.addVertex(0.0, 0.0, 0.55);
        t.addVertex(-0.3, 0.0, -0.3);
        t.addVertex(0.3, 0.0, -0.3);
        t.draw();
        GL11.glEnable(GL11.GL_CULL_FACE);
        GL11.glEnable(GL11.GL_TEXTURE_2D);
        GL11.glEnable(GL11.GL_LIGHTING);
        GL11.glDisable(GL11.GL_BLEND);
        GL11.glColor4f(1.0F, 1.0F, 1.0F, 1.0F);
        GL11.glPopMatrix();
    }

    private static void drawLabel(FontRenderer fr, String text, double x, double y, double z, int color) {
        RenderManager rm = RenderManager.instance;
        float scale = 0.016666668F * 1.4F;
        GL11.glPushMatrix();
        GL11.glTranslated(x, y, z);
        GL11.glRotatef(-rm.playerViewY, 0.0F, 1.0F, 0.0F);
        GL11.glRotatef(rm.playerViewX, 1.0F, 0.0F, 0.0F);
        GL11.glScalef(-scale, -scale, scale);
        GL11.glDisable(GL11.GL_LIGHTING);
        GL11.glDisable(GL11.GL_DEPTH_TEST);
        GL11.glDepthMask(false);
        GL11.glEnable(GL11.GL_BLEND);
        OpenGlHelper.glBlendFunc(770, 771, 1, 0);
        int w = fr.getStringWidth(text) / 2;
        Tessellator t = Tessellator.instance;
        GL11.glDisable(GL11.GL_TEXTURE_2D);
        t.startDrawingQuads();
        t.setColorRGBA_F(0.0F, 0.0F, 0.0F, 0.5F);
        t.addVertex(-w - 2, -1.5, 0.0);
        t.addVertex(-w - 2, 8.5, 0.0);
        t.addVertex(w + 2, 8.5, 0.0);
        t.addVertex(w + 2, -1.5, 0.0);
        t.draw();
        GL11.glEnable(GL11.GL_TEXTURE_2D);
        fr.drawString(text, -w, 0, 0xFF000000 | color);
        GL11.glDepthMask(true);
        GL11.glEnable(GL11.GL_DEPTH_TEST);
        GL11.glEnable(GL11.GL_LIGHTING);
        GL11.glDisable(GL11.GL_BLEND);
        GL11.glColor4f(1.0F, 1.0F, 1.0F, 1.0F);
        GL11.glPopMatrix();
    }
}
