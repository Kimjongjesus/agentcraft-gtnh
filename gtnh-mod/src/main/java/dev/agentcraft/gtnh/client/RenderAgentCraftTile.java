package dev.agentcraft.gtnh.client;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.renderer.OpenGlHelper;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.tileentity.TileEntitySpecialRenderer;
import net.minecraft.entity.Entity;
import net.minecraft.tileentity.TileEntity;

import org.lwjgl.opengl.GL11;

import dev.agentcraft.gtnh.Config;
import dev.agentcraft.gtnh.block.BlockAgentCraft;
import dev.agentcraft.gtnh.block.TileAgentCraft;
import dev.agentcraft.gtnh.state.AgentInfo;
import dev.agentcraft.gtnh.state.ClientAgentCache;
import dev.agentcraft.gtnh.state.LogLine;

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

    private static final int PX_PER_BLOCK = 80;
    private final SimpleDateFormat hhmm = new SimpleDateFormat("HH:mm");

    @Override
    public void renderTileEntityAt(TileEntity te, double x, double y, double z, float partial) {
        if (!(te instanceof TileAgentCraft) || te.getWorldObj() == null) return;
        Entity viewer = Minecraft.getMinecraft().renderViewEntity;
        if (viewer == null) return;
        double dx = te.xCoord + 0.5 - viewer.posX, dy = te.yCoord + 0.5 - viewer.posY, dz = te.zCoord + 0.5 - viewer.posZ;
        double distSq = dx * dx + dy * dy + dz * dz;
        TileAgentCraft t = (TileAgentCraft) te;
        if (te.getBlockType() instanceof BlockAgentCraft) {
            switch (((BlockAgentCraft) te.getBlockType()).kind) {
                case MONITOR:
                    if (distSq <= sq(Config.monitorRenderDistance)) renderMonitor(t, x, y, z);
                    return;
                case LAMP:
                    if (distSq <= sq(Config.monitorRenderDistance * 4)) renderShell(ClientAgentCache.familyFor(t.binding), x, y, z, 1.0F);
                    return;
                default:
                    renderBeacon(x, y, z);
            }
        }
    }

    private static double sq(double d) {
        return d * d;
    }

    // ---- monitor --------------------------------------------------------------------------

    private void renderMonitor(TileAgentCraft t, double x, double y, double z) {
        int meta = t.getBlockMetadata();
        float rot = meta == 2 ? 180.0F : meta == 4 ? -90.0F : meta == 5 ? 90.0F : 0.0F;
        int wPx = t.screenW * PX_PER_BLOCK, hPx = t.screenH * PX_PER_BLOCK;
        float s = 1.0F / PX_PER_BLOCK;

        GL11.glPushMatrix();
        GL11.glTranslated(x + 0.5, y + 0.5, z + 0.5);
        GL11.glRotatef(rot, 0.0F, 1.0F, 0.0F);
        // canvas: top-left corner of the screen, 0.01 in front of the block face, +x right, +y down
        GL11.glTranslated(-t.screenW / 2.0, -0.5 + t.screenH, 0.512);
        GL11.glScalef(s, -s, s);
        GL11.glNormal3f(0.0F, 0.0F, 1.0F);
        GL11.glDisable(GL11.GL_LIGHTING);
        GL11.glDisable(GL11.GL_CULL_FACE);
        OpenGlHelper.setLightmapTextureCoords(OpenGlHelper.lightmapTexUnit, 240.0F, 240.0F);

        String id = t.binding;
        AgentInfo a = id.isEmpty() || "fleet".equals(id) ? null : ClientAgentCache.get(id);
        int accent = a == null ? 0x4A4744 : a.color;
        quad(-2, -2, wPx + 2, hPx + 2, 0xFF000000 | darken(accent), -0.2F);
        quad(0, 0, wPx, hPx, 0xF0101418, -0.1F);

        FontRenderer fr = func_147498_b();
        GL11.glDepthMask(false);
        int pad = 6, lineH = 10;
        if (a == null) {
            fr.drawString(id.isEmpty() ? "Agent monitor (unbound)" : "No agent '" + clip(fr, id, wPx - 2 * pad) + "'", pad, pad, 0xFFB0A898);
            fr.drawString("Look at it: /agentcraft bind <agent>", pad, pad + lineH, 0xFF7C756B);
        } else {
            boolean link = ClientAgentCache.linkUp;
            String fam = link ? a.family() : "offline";
            int status = AgentInfo.familyColor(fam);
            fr.drawString("\u25cf", pad, pad, 0xFF000000 | status);
            fr.drawString("\u00a7l" + clip(fr, a.name, wPx / 2), pad + 9, pad, 0xFF000000 | lighten(a.color));
            String st = link ? a.state.replace('_', ' ') + (a.waiting ? "  !" : "") : "adapter offline";
            fr.drawString(st, wPx - pad - fr.getStringWidth(st), pad, 0xFF000000 | status);
            fr.drawString(clip(fr, link ? a.activity : "Hermes adapter offline", wPx - 2 * pad), pad, pad + lineH + 1, 0xFFC8C0B4);
            quad(pad, pad + 2 * lineH + 3, wPx - pad, pad + 2 * lineH + 4, 0x60FFFFFF, 0.0F);
            int top = pad + 2 * lineH + 8;
            int rows = Math.max(1, (hPx - top - pad) / lineH);
            List<String[]> lines = wrapLogs(fr, ClientAgentCache.logs(a.id), wPx - 2 * pad);
            int from = Math.max(0, lines.size() - rows);
            for (int i = from; i < lines.size(); i++) {
                fr.drawString(lines.get(i)[1], pad, top + (i - from) * lineH, 0xFF000000 | Integer.parseInt(lines.get(i)[0]));
            }
            if (lines.isEmpty()) fr.drawString("(no recent log lines)", pad, top, 0xFF7C756B);
        }
        GL11.glDepthMask(true);
        GL11.glEnable(GL11.GL_CULL_FACE);
        GL11.glEnable(GL11.GL_LIGHTING);
        GL11.glColor4f(1.0F, 1.0F, 1.0F, 1.0F);
        GL11.glPopMatrix();
    }

    /** [colour, text] display rows: "HH:mm " prefix on the first row of each entry, wrapped to width. */
    private List<String[]> wrapLogs(FontRenderer fr, List<LogLine> logs, int width) {
        List<String[]> out = new ArrayList<>();
        for (LogLine l : logs) {
            int c = kindColor(l.kind);
            String prefix = l.ts > 0 ? hhmm.format(new Date(l.ts)) + " " : "";
            boolean first = true;
            for (String para : l.text.split("\n")) {
                if (para.trim()
                    .isEmpty()) continue;
                @SuppressWarnings("unchecked")
                List<String> wrapped = fr.listFormattedStringToWidth((first ? prefix : "      ") + para, width);
                for (String w : wrapped) out.add(new String[] { Integer.toString(c), w });
                first = false;
            }
        }
        return out;
    }

    private static int kindColor(String kind) {
        switch (kind) {
            case "tool":
                return 0x5FC4C0;
            case "result":
                return 0x9CC59A;
            case "error":
                return 0xE0605A;
            case "diff":
                return 0xC9A227;
            default:
                return 0xD8D2C8;
        }
    }

    private static String clip(FontRenderer fr, String s, int width) {
        if (fr.getStringWidth(s) <= width) return s;
        return fr.trimStringToWidth(s, width - fr.getStringWidth("...")) + "...";
    }

    private static void quad(double x0, double y0, double x1, double y1, int argb, float z) {
        Tessellator t = Tessellator.instance;
        GL11.glDisable(GL11.GL_TEXTURE_2D);
        GL11.glEnable(GL11.GL_BLEND);
        OpenGlHelper.glBlendFunc(770, 771, 1, 0);
        t.startDrawingQuads();
        t.setColorRGBA((argb >> 16) & 0xFF, (argb >> 8) & 0xFF, argb & 0xFF, (argb >>> 24) & 0xFF);
        t.addVertex(x0, y1, z);
        t.addVertex(x1, y1, z);
        t.addVertex(x1, y0, z);
        t.addVertex(x0, y0, z);
        t.draw();
        GL11.glEnable(GL11.GL_TEXTURE_2D);
    }

    // ---- lamp / beacon --------------------------------------------------------------------

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
