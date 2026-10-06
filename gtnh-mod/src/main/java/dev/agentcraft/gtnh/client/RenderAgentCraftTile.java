package dev.agentcraft.gtnh.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.OpenGlHelper;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.entity.RenderManager;
import net.minecraft.client.renderer.tileentity.TileEntitySpecialRenderer;
import net.minecraft.entity.Entity;
import net.minecraft.tileentity.TileEntity;

import org.lwjgl.opengl.GL11;

import dev.agentcraft.gtnh.Config;
import dev.agentcraft.gtnh.block.BlockAgentCraft;
import dev.agentcraft.gtnh.block.TileAgentCraft;
import dev.agentcraft.gtnh.state.AgentInfo;
import dev.agentcraft.gtnh.state.ClientAgentCache;
import dev.agentcraft.gtnh.state.ClientHq;
import dev.agentcraft.gtnh.ui.Ui;
import dev.agentcraft.gtnh.ui.UiFont;
import dev.agentcraft.gtnh.ui.panel.InWorldPanels;

/**
 * Client renderers for the HQ blocks, all fed by ClientAgentCache (which only changes when the server
 * sends an update; nothing here talks to the network):
 * <ul>
 * <li>monitor: a W x H screen (default 3 x 2, the block is its bottom-centre) on the block's front:
 * name, state, activity, then the agent's last log lines. Drawn only within monitorRenderDistance.</li>
 * <li>status lamp: a glowing shell in the status colour of its agent (or the fleet); breathes while
 * waiting on Eli; dark while the agent is off shift or the adapter is offline.</li>
 * <li>beacon: the fleet colour as a beam into the sky plus a glowing shell.</li>
 * </ul>
 */
public class RenderAgentCraftTile extends TileEntitySpecialRenderer {


    @Override
    public void renderTileEntityAt(TileEntity te, double x, double y, double z, float partial) {
        if (!(te instanceof TileAgentCraft) || te.getWorldObj() == null) return;
        Entity viewer = Minecraft.getMinecraft().renderViewEntity;
        if (viewer == null) return;
        double dx = te.xCoord + 0.5 - viewer.posX, dy = te.yCoord + 0.5 - viewer.posY, dz = te.zCoord + 0.5 - viewer.posZ;
        double distSq = dx * dx + dy * dy + dz * dz;
        TileAgentCraft t = (TileAgentCraft) te;
        double dist = Math.sqrt(distSq);
        if (te.getBlockType() instanceof BlockAgentCraft) {
            switch (((BlockAgentCraft) te.getBlockType()).kind) {
                case MONITOR:
                    if (distSq <= sq(Config.monitorRenderDistance)) InWorldPanels.render(t, "monitor", x, y, z, dist);
                    return;
                case LAMP:
                    if (distSq <= sq(Config.monitorRenderDistance * 4)) renderShell(ClientAgentCache.familyFor(t.binding), x, y, z, 1.0F);
                    return;
                case TASKWALL:
                    if (distSq <= sq(Config.wallRenderDistance)) InWorldPanels.render(t, "task_wall", x, y, z, dist);
                    return;
                case ATRIUM:
                    if (distSq <= sq(Config.wallRenderDistance)) InWorldPanels.render(t, "goal_atrium", x, y, z, dist);
                    return;
                case LIBRARY:
                    if (distSq <= 64.0D) renderLabel("Agent Library", "right-click to read", 0xF4EFE6, x + 0.5, y + 1.4, z + 0.5);
                    return;
                default:
                    renderBeacon(x, y, z);
                    if (distSq <= sq(Config.wallRenderDistance) && ClientAgentCache.linkUp && ClientHq.haveBoard) {
                        BoardView v = BoardView.of("");
                        renderLabel(
                            v.count(1) + " doing \u00b7 " + v.count(2) + " review \u00b7 " + v.count(4) + " blocked",
                            v.goal.openDecisions + (v.goal.openDecisions == 1 ? " decision needs" : " decisions need") + " you",
                            v.goal.openDecisions > 0 ? 0xEE9474 : 0xC4BAAC,
                            x + 0.5,
                            y + 1.7,
                            z + 0.5);
                    }
            }
        }
    }

    private static double sq(double d) {
        return d * d;
    }

    // ---- labels ---------------------------------------------------------------------------

    /** Two-line billboard label (faces the camera like a nameplate) in the UI font. */
    private void renderLabel(String line1, String line2, int color2, double x, double y, double z) {
        UiFont bold = UiFont.bold(), reg = UiFont.regular();
        float s = 0.011F, size = 18.0F; // 18 units * 0.011 = an em of 0.2 block
        GL11.glPushMatrix();
        GL11.glTranslated(x, y, z);
        GL11.glNormal3f(0.0F, 1.0F, 0.0F);
        GL11.glRotatef(-RenderManager.instance.playerViewY, 0.0F, 1.0F, 0.0F);
        GL11.glRotatef(RenderManager.instance.playerViewX, 1.0F, 0.0F, 0.0F);
        GL11.glScalef(-s, -s, s);
        GL11.glDisable(GL11.GL_LIGHTING);
        GL11.glDisable(GL11.GL_CULL_FACE);
        OpenGlHelper.setLightmapTextureCoords(OpenGlHelper.lightmapTexUnit, 240.0F, 240.0F);
        float w = Math.max(bold.width(line1, size), reg.width(line2, size * 0.9F)) / 2;
        float lh = bold.lineHeight(size);
        GL11.glDepthMask(false);
        Ui.round(-w - 8, -5, w + 8, lh * 1.95F + 5, 9, 0xC81C1815);
        bold.drawCentered(line1, 0, 0, size, 0xFFF4EFE6);
        reg.drawCentered(line2, 0, lh, size * 0.9F, 0xFF000000 | color2);
        GL11.glDepthMask(true);
        GL11.glEnable(GL11.GL_CULL_FACE);
        GL11.glEnable(GL11.GL_LIGHTING);
        GL11.glColor4f(1.0F, 1.0F, 1.0F, 1.0F);
        GL11.glPopMatrix();
    }

    private static float breathe(String family) {
        if (!"waiting".equals(family)) return 1.0F;
        double t = (System.currentTimeMillis() % 2000L) / 2000.0D;
        return 0.55F + 0.45F * (float) (0.5 + 0.5 * Math.sin(t * Math.PI * 2));
    }

    /** Glowing shell around the block in the family's status colour (nothing while offline). */
    private void renderShell(String family, double x, double y, double z, float alphaScale) {
        if ("offline".equals(family)) return;
        int c = AgentInfo.familyColor(family);
        float a = ("idle".equals(family) ? 0.35F : 0.7F) * breathe(family) * alphaScale;
        GL11.glPushMatrix();
        GL11.glTranslated(x, y, z);
        GL11.glDisable(GL11.GL_LIGHTING);
        GL11.glDisable(GL11.GL_TEXTURE_2D);
        GL11.glEnable(GL11.GL_BLEND);
        OpenGlHelper.glBlendFunc(770, 771, 1, 0);
        GL11.glDepthMask(false);
        OpenGlHelper.setLightmapTextureCoords(OpenGlHelper.lightmapTexUnit, 240.0F, 240.0F);
        Tessellator t = Tessellator.instance;
        t.startDrawingQuads();
        t.setColorRGBA_F(((c >> 16) & 0xFF) / 255.0F, ((c >> 8) & 0xFF) / 255.0F, (c & 0xFF) / 255.0F, a);
        box(t, -0.01, -0.01, -0.01, 1.01, 1.01, 1.01);
        GL11.glDisable(GL11.GL_CULL_FACE);
        t.draw();
        GL11.glEnable(GL11.GL_CULL_FACE);
        GL11.glDepthMask(true);
        GL11.glDisable(GL11.GL_BLEND);
        GL11.glEnable(GL11.GL_TEXTURE_2D);
        GL11.glEnable(GL11.GL_LIGHTING);
        GL11.glColor4f(1.0F, 1.0F, 1.0F, 1.0F);
        GL11.glPopMatrix();
    }

    private void renderBeacon(double x, double y, double z) {
        String family = ClientAgentCache.linkUp ? ClientAgentCache.fleet : "offline";
        renderShell(family, x, y, z, 1.0F);
        if ("offline".equals(family)) return;
        int c = AgentInfo.familyColor(family);
        float a = 0.55F * breathe(family);
        double h = 96.0D, w = 0.3D;
        GL11.glPushMatrix();
        GL11.glTranslated(x + 0.5, y + 1.0, z + 0.5);
        GL11.glDisable(GL11.GL_LIGHTING);
        GL11.glDisable(GL11.GL_TEXTURE_2D);
        GL11.glDisable(GL11.GL_CULL_FACE);
        GL11.glShadeModel(GL11.GL_SMOOTH); // per-vertex alpha: the beam fades out upwards
        GL11.glEnable(GL11.GL_BLEND);
        OpenGlHelper.glBlendFunc(770, 771, 1, 0); // plain alpha: the status colour stays visible against a bright sky
        GL11.glDepthMask(false);
        OpenGlHelper.setLightmapTextureCoords(OpenGlHelper.lightmapTexUnit, 240.0F, 240.0F);
        Tessellator t = Tessellator.instance;
        t.startDrawingQuads();
        float r = ((c >> 16) & 0xFF) / 255.0F, g = ((c >> 8) & 0xFF) / 255.0F, b = (c & 0xFF) / 255.0F;
        for (int i = 0; i < 2; i++) {
            double ww = i == 0 ? w : w * 2.2;
            float aa = i == 0 ? a : a * 0.35F;
            t.setColorRGBA_F(r, g, b, aa);
            t.addVertex(-ww, 0, 0);
            t.addVertex(ww, 0, 0);
            t.setColorRGBA_F(r, g, b, 0.0F);
            t.addVertex(ww, h, 0);
            t.addVertex(-ww, h, 0);
            t.setColorRGBA_F(r, g, b, aa);
            t.addVertex(0, 0, -ww);
            t.addVertex(0, 0, ww);
            t.setColorRGBA_F(r, g, b, 0.0F);
            t.addVertex(0, h, ww);
            t.addVertex(0, h, -ww);
        }
        t.draw();
        GL11.glShadeModel(GL11.GL_FLAT);
        GL11.glDepthMask(true);
        OpenGlHelper.glBlendFunc(770, 771, 1, 0);
        GL11.glDisable(GL11.GL_BLEND);
        GL11.glEnable(GL11.GL_CULL_FACE);
        GL11.glEnable(GL11.GL_TEXTURE_2D);
        GL11.glEnable(GL11.GL_LIGHTING);
        GL11.glColor4f(1.0F, 1.0F, 1.0F, 1.0F);
        GL11.glPopMatrix();
    }

    private static void box(Tessellator t, double x0, double y0, double z0, double x1, double y1, double z1) {
        // down, up, north, south, west, east (outward winding)
        t.addVertex(x0, y0, z0);
        t.addVertex(x1, y0, z0);
        t.addVertex(x1, y0, z1);
        t.addVertex(x0, y0, z1);
        t.addVertex(x0, y1, z1);
        t.addVertex(x1, y1, z1);
        t.addVertex(x1, y1, z0);
        t.addVertex(x0, y1, z0);
        t.addVertex(x0, y1, z0);
        t.addVertex(x1, y1, z0);
        t.addVertex(x1, y0, z0);
        t.addVertex(x0, y0, z0);
        t.addVertex(x0, y0, z1);
        t.addVertex(x1, y0, z1);
        t.addVertex(x1, y1, z1);
        t.addVertex(x0, y1, z1);
        t.addVertex(x0, y0, z0);
        t.addVertex(x0, y0, z1);
        t.addVertex(x0, y1, z1);
        t.addVertex(x0, y1, z0);
        t.addVertex(x1, y1, z0);
        t.addVertex(x1, y1, z1);
        t.addVertex(x1, y0, z1);
        t.addVertex(x1, y0, z0);
    }

    static int lighten(int rgb) {
        int r = (rgb >> 16) & 0xFF, g = (rgb >> 8) & 0xFF, b = rgb & 0xFF;
        int lum = (r * 299 + g * 587 + b * 114) / 1000;
        if (lum >= 110) return rgb;
        return ((r + (255 - r) / 2) << 16) | ((g + (255 - g) / 2) << 8) | (b + (255 - b) / 2);
    }

    private static int darken(int rgb) {
        return ((((rgb >> 16) & 0xFF) * 3 / 4) << 16) | ((((rgb >> 8) & 0xFF) * 3 / 4) << 8) | ((rgb & 0xFF) * 3 / 4);
    }
}
