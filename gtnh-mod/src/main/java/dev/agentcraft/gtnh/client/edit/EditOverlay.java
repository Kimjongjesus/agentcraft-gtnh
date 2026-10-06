package dev.agentcraft.gtnh.client.edit;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import net.minecraft.block.Block;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.client.renderer.OpenGlHelper;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.entity.RenderManager;
import net.minecraft.entity.Entity;
import net.minecraft.item.ItemStack;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.tileentity.TileEntitySign;
import net.minecraft.util.MovingObjectPosition;
import net.minecraft.util.Vec3;
import net.minecraftforge.client.event.RenderGameOverlayEvent;
import net.minecraftforge.client.event.RenderWorldLastEvent;

import org.lwjgl.opengl.GL11;

import dev.agentcraft.gtnh.block.TileAgentCraft;
import dev.agentcraft.gtnh.client.BoardView;
import dev.agentcraft.gtnh.edit.PanelSpec;
import dev.agentcraft.gtnh.edit.PanelType;
import dev.agentcraft.gtnh.edit.PanelTypes;
import dev.agentcraft.gtnh.hq.Anchor;
import dev.agentcraft.gtnh.hq.StationAssigner;
import dev.agentcraft.gtnh.item.ItemEditTool;
import dev.agentcraft.gtnh.server.EditService;
import dev.agentcraft.gtnh.ui.Theme;
import dev.agentcraft.gtnh.ui.Ui;
import dev.agentcraft.gtnh.ui.UiFont;
import dev.agentcraft.gtnh.ui.Widgets;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;

/**
 * Card 6 edit-mode overlay (only while the held edit tool is switched on): every anchor within 32
 * blocks as a marker column with its facing arrow, name and slot number (the Anchor Editor's
 * in-world view; right-click one to select it); the panel under the crosshair outlined at its
 * screen size with corner handles; and a Nunito info card next to the crosshair saying what you
 * look at, what it is bound to and its size. Move mode shows where the panel would go.
 */
public final class EditOverlay {

    private static final Theme TH = Theme.DARK;
    private long lastHello;
    private boolean wasOn;

    public static boolean editMode() {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.thePlayer == null) return false;
        ItemStack held = mc.thePlayer.getHeldItem();
        return ItemEditTool.isOn(held);
    }

    @SubscribeEvent
    public void onTick(TickEvent.ClientTickEvent e) {
        if (e.phase != TickEvent.Phase.END) return;
        boolean on = editMode();
        long now = System.currentTimeMillis();
        if (on && (!wasOn || now - ClientEdit.viewAt > 15000) && now - lastHello > 3000) {
            lastHello = now;
            ClientEdit.send("a", "hello");
        }
        if (!on && wasOn) {
            ClientEdit.moving = null;
            ClientEdit.targetAnchor = null;
        }
        wasOn = on;
    }

    // ---- world -------------------------------------------------------------------------------

    @SubscribeEvent
    public void onRenderWorldLast(RenderWorldLastEvent e) {
        if (!editMode()) {
            ClientEdit.targetAnchor = null;
            return;
        }
        Minecraft mc = Minecraft.getMinecraft();
        Entity view = mc.renderViewEntity;
        if (view == null) return;
        float pt = e.partialTicks;
        double px = view.lastTickPosX + (view.posX - view.lastTickPosX) * pt;
        double py = view.lastTickPosY + (view.posY - view.lastTickPosY) * pt;
        double pz = view.lastTickPosZ + (view.posZ - view.lastTickPosZ) * pt;
        // the same eye point vanilla picks blocks from (EntityRenderer.getMouseOver)
        Vec3 eye = mc.renderViewEntity.getPosition(pt);
        Vec3 look = mc.renderViewEntity.getLook(pt);
        MovingObjectPosition mop = mc.objectMouseOver;
        double blockDist = mop != null && mop.typeOfHit == MovingObjectPosition.MovingObjectType.BLOCK ? mop.hitVec.distanceTo(eye) : 64;

        // anchors: pick the one under the crosshair (nearer than the block hit)
        List<Object[]> anchors = ClientEdit.anchors();
        String target = null;
        double best = blockDist;
        for (Object[] a : anchors) {
            double ax = (Double) a[1], ay = (Double) a[2], az = (Double) a[3];
            double t = rayBox(eye, look, ax - 0.4, ay, az - 0.4, ax + 0.4, ay + 1.9, az + 0.4);
            // t == 0 means the eye is inside the marker: that is the anchor you stand on, not the one
            // you look at (it would otherwise hide every panel in front of you)
            if (t > 0.3 && t < best && t < 24) {
                best = t;
                target = (String) a[0];
            }
        }
        ClientEdit.targetAnchor = target;

        GL11.glPushMatrix();
        GL11.glDisable(GL11.GL_LIGHTING);
        GL11.glDisable(GL11.GL_TEXTURE_2D);
        GL11.glDisable(GL11.GL_CULL_FACE);
        GL11.glEnable(GL11.GL_BLEND);
        OpenGlHelper.glBlendFunc(770, 771, 1, 0);
        OpenGlHelper.setLightmapTextureCoords(OpenGlHelper.lightmapTexUnit, 240.0F, 240.0F);
        for (Object[] a : anchors) {
            double ax = (Double) a[1], ay = (Double) a[2], az = (Double) a[3];
            double dx = ax - px, dy = ay - py, dz = az - pz;
            if (dx * dx + dy * dy + dz * dz > 32 * 32) continue;
            String n = (String) a[0];
            int col = colour(n);
            boolean hot = n.equals(target);
            column(dx, dy, dz, col, hot ? 0.45F : 0.18F, hot);
            arrow(dx, dy + 0.04, dz, ((Double) a[4]).floatValue(), col);
        }
        // the panel under the crosshair: its screen outline with corner handles
        TileEntity te = mop != null && mop.typeOfHit == MovingObjectPosition.MovingObjectType.BLOCK && target == null ? mc.theWorld.getTileEntity(mop.blockX, mop.blockY, mop.blockZ) : null;
        if (te instanceof TileAgentCraft && ClientEdit.moving == null) {
            TileAgentCraft t = (TileAgentCraft) te;
            PanelType type = PanelTypes.get(EditService.typeOf(mc.theWorld.getBlock(t.xCoord, t.yCoord, t.zCoord)));
            int[] ov = ClientEdit.sizeOverride(t.xCoord, t.yCoord, t.zCoord);
            int w = ov != null ? ov[0] : t.screenW, h = ov != null ? ov[1] : t.screenH;
            if (type != null && type.faced && type.resizable) screenOutline(t.xCoord - px, t.yCoord - py, t.zCoord - pz, mc.theWorld.getBlockMetadata(t.xCoord, t.yCoord, t.zCoord), w, h, TH.accent);
            else box(t.xCoord - px, t.yCoord - py, t.zCoord - pz, 1, 1, 1, TH.accent);
        }
        // move mode: where the panel would go
        int[] mv = ClientEdit.moving;
        if (mv != null && mop != null && mop.typeOfHit == MovingObjectPosition.MovingObjectType.BLOCK) {
            int[] c = adjacent(mop);
            box(c[0] - px, c[1] - py, c[2] - pz, 1, 1, 1, 0xE8C547);
            box(mv[0] - px, mv[1] - py, mv[2] - pz, 1, 1, 1, 0x9C9488);
        }
        GL11.glEnable(GL11.GL_TEXTURE_2D);
        // labels last, drawn through walls
        GL11.glDisable(GL11.GL_DEPTH_TEST);
        GL11.glDepthMask(false);
        for (Object[] a : anchors) {
            double ax = (Double) a[1], ay = (Double) a[2], az = (Double) a[3];
            double dx = ax - px, dy = ay - py, dz = az - pz;
            double d2 = dx * dx + dy * dy + dz * dz;
            if (d2 > 32 * 32) continue;
            String n = (String) a[0];
            String sub = slotText(n, anchors) + " \u00b7 " + Anchor.facingName(((Double) a[4]).floatValue());
            label(n, sub, dx, dy + 2.25, dz, colour(n), n.equals(target), Math.sqrt(d2));
        }
        GL11.glDepthMask(true);
        GL11.glEnable(GL11.GL_DEPTH_TEST);
        GL11.glEnable(GL11.GL_CULL_FACE);
        GL11.glEnable(GL11.GL_LIGHTING);
        GL11.glDisable(GL11.GL_BLEND);
        GL11.glColor4f(1, 1, 1, 1);
        GL11.glPopMatrix();
    }

    static int colour(String n) {
        return n.startsWith("cam_") ? 0x5FC4C0 : StationAssigner.isStandingAnchor(n) ? 0x7FD77F : 0xE8C547;
    }

    /** "slot 2 of 3" for station anchors, else what it is. */
    static String slotText(String n, List<Object[]> all) {
        String found = null;
        for (String s : StationAssigner.STATIONS) if (n.equals(s) || n.startsWith(s + "_")) found = s;
        final String st = found;
        if (n.startsWith("cam_")) return "camera";
        if ("overflow_sign".equals(n)) return "overflow sign";
        if (st == null) return "not a station";
        List<String> slots = new ArrayList<>();
        for (Object[] a : all) {
            String m = (String) a[0];
            if (m.equals(st) || m.startsWith(st + "_")) slots.add(m);
        }
        java.util.Collections.sort(slots, (x, y) -> slotIndex(x, st) - slotIndex(y, st));
        String rest = n.length() > st.length() ? n.substring(st.length() + 1) : "";
        if (!rest.isEmpty() && !rest.matches("\\d+")) return st + " \u00b7 personal (" + rest + ")";
        return st + " \u00b7 slot " + (slots.indexOf(n) + 1) + " of " + slots.size();
    }

    private static int slotIndex(String n, String st) {
        if (n.equals(st)) return 1;
        String r = n.substring(st.length() + 1);
        return r.matches("\\d+") ? Integer.parseInt(r) : 1000;
    }

    static int[] adjacent(MovingObjectPosition mop) {
        int x = mop.blockX, y = mop.blockY, z = mop.blockZ;
        switch (mop.sideHit) {
            case 0:
                y--;
                break;
            case 1:
                y++;
                break;
            case 2:
                z--;
                break;
            case 3:
                z++;
                break;
            case 4:
                x--;
                break;
            case 5:
                x++;
                break;
            default:
        }
        return new int[] { x, y, z };
    }

    /** Distance along the ray to an axis-aligned box, or -1. */
    static double rayBox(Vec3 o, Vec3 d, double x0, double y0, double z0, double x1, double y1, double z1) {
        double tmin = 0, tmax = 1e9;
        double[] oo = { o.xCoord, o.yCoord, o.zCoord }, dd = { d.xCoord, d.yCoord, d.zCoord }, lo = { x0, y0, z0 }, hi = { x1, y1, z1 };
        for (int i = 0; i < 3; i++) {
            if (Math.abs(dd[i]) < 1e-9) {
                if (oo[i] < lo[i] || oo[i] > hi[i]) return -1;
                continue;
            }
            double a = (lo[i] - oo[i]) / dd[i], b = (hi[i] - oo[i]) / dd[i];
            if (a > b) {
                double t = a;
                a = b;
                b = t;
            }
            tmin = Math.max(tmin, a);
            tmax = Math.min(tmax, b);
            if (tmin > tmax) return -1;
        }
        return tmin;
    }

    private static void color(Tessellator t, int rgb, float a) {
        t.setColorRGBA_F(((rgb >> 16) & 0xFF) / 255F, ((rgb >> 8) & 0xFF) / 255F, (rgb & 0xFF) / 255F, a);
    }

    private static void column(double x, double y, double z, int col, float alpha, boolean hot) {
        double r = hot ? 0.36 : 0.28;
        Tessellator t = Tessellator.instance;
        t.startDrawingQuads();
        color(t, col, alpha);
        double[][] f = { { -r, -r, r, -r }, { r, -r, r, r }, { r, r, -r, r }, { -r, r, -r, -r } };
        for (double[] s : f) {
            t.addVertex(x + s[0], y, z + s[1]);
            t.addVertex(x + s[2], y, z + s[3]);
            t.addVertex(x + s[2], y + 1.8, z + s[3]);
            t.addVertex(x + s[0], y + 1.8, z + s[1]);
        }
        t.draw();
        if (hot) {
            GL11.glLineWidth(3.0F);
            t.startDrawing(GL11.GL_LINES);
            color(t, col, 1.0F);
            for (double[] s : f) {
                t.addVertex(x + s[0], y, z + s[1]);
                t.addVertex(x + s[0], y + 1.8, z + s[1]);
                t.addVertex(x + s[0], y + 1.8, z + s[1]);
                t.addVertex(x + s[2], y + 1.8, z + s[3]);
                t.addVertex(x + s[0], y, z + s[1]);
                t.addVertex(x + s[2], y, z + s[3]);
            }
            t.draw();
            GL11.glLineWidth(1.0F);
        }
    }

    private static void arrow(double x, double y, double z, float yaw, int col) {
        GL11.glPushMatrix();
        GL11.glTranslated(x, y, z);
        GL11.glRotatef(-yaw, 0, 1, 0);
        Tessellator t = Tessellator.instance;
        t.startDrawing(GL11.GL_TRIANGLES);
        color(t, col, 0.9F);
        t.addVertex(0, 0, 0.75);
        t.addVertex(-0.32, 0, 0.1);
        t.addVertex(0.32, 0, 0.1);
        t.draw();
        GL11.glPopMatrix();
    }

    /** The W x H screen rectangle on a faced panel's front, plus corner handles and a size grip. */
    private static void screenOutline(double x, double y, double z, int meta, int w, int h, int col) {
        float rot = meta == 2 ? 180.0F : meta == 4 ? -90.0F : meta == 5 ? 90.0F : 0.0F;
        GL11.glPushMatrix();
        GL11.glTranslated(x + 0.5, y + 0.5, z + 0.5);
        GL11.glRotatef(rot, 0, 1, 0);
        double x0 = -w / 2.0, x1 = w / 2.0, y0 = -0.5, y1 = -0.5 + h, zz = 0.53;
        Tessellator t = Tessellator.instance;
        GL11.glDisable(GL11.GL_DEPTH_TEST);
        GL11.glLineWidth(3.0F);
        t.startDrawing(GL11.GL_LINE_LOOP);
        color(t, col, 1.0F);
        t.addVertex(x0, y0, zz);
        t.addVertex(x1, y0, zz);
        t.addVertex(x1, y1, zz);
        t.addVertex(x0, y1, zz);
        t.draw();
        GL11.glLineWidth(1.0F);
        double s = 0.09;
        t.startDrawingQuads();
        color(t, 0xFFFFFF, 1.0F);
        for (double[] c : new double[][] { { x0, y0 }, { x1, y0 }, { x1, y1 }, { x0, y1 } }) {
            t.addVertex(c[0] - s, c[1] - s, zz + 0.01);
            t.addVertex(c[0] + s, c[1] - s, zz + 0.01);
            t.addVertex(c[0] + s, c[1] + s, zz + 0.01);
            t.addVertex(c[0] - s, c[1] + s, zz + 0.01);
        }
        color(t, col, 1.0F);
        double g = 0.16; // size grip at the top-right corner
        t.addVertex(x1 - g, y1 + 0.02, zz + 0.02);
        t.addVertex(x1 + 0.02, y1 + 0.02, zz + 0.02);
        t.addVertex(x1 + 0.02, y1 - g, zz + 0.02);
        t.addVertex(x1 - g, y1 - g, zz + 0.02);
        t.draw();
        GL11.glEnable(GL11.GL_DEPTH_TEST);
        GL11.glPopMatrix();
    }

    private static void box(double x, double y, double z, double w, double h, double d, int col) {
        double e = 0.004;
        double x0 = x - e, y0 = y - e, z0 = z - e, x1 = x + w + e, y1 = y + h + e, z1 = z + d + e;
        Tessellator t = Tessellator.instance;
        GL11.glLineWidth(3.0F);
        t.startDrawing(GL11.GL_LINES);
        color(t, col, 1.0F);
        double[][] v = { { x0, y0, z0 }, { x1, y0, z0 }, { x1, y0, z1 }, { x0, y0, z1 }, { x0, y1, z0 }, { x1, y1, z0 }, { x1, y1, z1 }, { x0, y1, z1 } };
        int[][] edges = { { 0, 1 }, { 1, 2 }, { 2, 3 }, { 3, 0 }, { 4, 5 }, { 5, 6 }, { 6, 7 }, { 7, 4 }, { 0, 4 }, { 1, 5 }, { 2, 6 }, { 3, 7 } };
        for (int[] ed : edges) {
            t.addVertex(v[ed[0]][0], v[ed[0]][1], v[ed[0]][2]);
            t.addVertex(v[ed[1]][0], v[ed[1]][1], v[ed[1]][2]);
        }
        t.draw();
        GL11.glLineWidth(1.0F);
    }

    /** A billboard plate in the UI font: name (bold) and a muted second line. */
    private static void label(String name, String sub, double x, double y, double z, int col, boolean hot, double dist) {
        RenderManager rm = RenderManager.instance;
        float s = (float) (0.0045 * Math.max(1.0, dist / 6.0)) * (hot ? 1.25F : 1.0F);
        GL11.glPushMatrix();
        GL11.glTranslated(x, y, z);
        GL11.glRotatef(-rm.playerViewY, 0, 1, 0);
        GL11.glRotatef(rm.playerViewX, 1, 0, 0);
        GL11.glScalef(-s, -s, s); // the vanilla nameplate transform: text reads left to right from any side
        UiFont b = UiFont.bold(), r = UiFont.regular();
        float size = 20;
        float w = Math.max(b.width(name, size), r.width(sub, size * 0.7F)) + 16;
        float hh = b.lineHeight(size) + r.lineHeight(size * 0.7F) + 8;
        Ui.round(-w / 2, -hh, w / 2, 0, 6, 0xD0000000 | (TH.surface & 0xFFFFFF));
        Ui.rect(-w / 2, -hh, -w / 2 + 4, 0, 0xFF000000 | col);
        if (hot) Ui.roundOutline(-w / 2, -hh, w / 2, 0, 6, 2, 0xFF000000 | col);
        b.drawCentered(name, 2, -hh + 3, size, 0xFF000000 | TH.text);
        r.drawCentered(sub, 2, -hh + 4 + b.lineHeight(size), size * 0.7F, 0xFF000000 | TH.muted);
        GL11.glPopMatrix();
    }

    // ---- HUD ---------------------------------------------------------------------------------

    @SubscribeEvent
    public void onHud(RenderGameOverlayEvent.Post e) {
        if (e.type != RenderGameOverlayEvent.ElementType.ALL || !editMode()) return;
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.currentScreen != null) return;
        ScaledResolution sr = e.resolution;
        int W = sr.getScaledWidth(), H = sr.getScaledHeight();
        GL11.glPushMatrix();
        GL11.glDisable(GL11.GL_DEPTH_TEST);
        // status pill, top left
        UiFont bold = UiFont.bold(), reg = UiFont.regular();
        float x = 6, y = 6;
        if (ClientEdit.locked()) x += Ui.pill(bold, "EDITING LOCKED", x, y, Widgets.SMALL + 1, TH.danger) + 4;
        else x += Ui.pill(bold, "EDIT MODE", x, y, Widgets.SMALL + 1, TH.accent) + 4;
        String status = "undo " + ClientEdit.num("undoN") + " \u00b7 redo " + ClientEdit.num("redoN") + " \u00b7 " + ClientEdit.num("panels") + " panels \u00b7 sneak + right-click to leave";
        float sw = reg.width(status, Widgets.SMALL + 0.4F);
        // a backing plate so the line stays readable over a bright wall or the sky
        Ui.round(x - 2, y - 1, x + sw + 6, y + reg.lineHeight(Widgets.SMALL + 0.4F) + 2.5F, 4, 0xB0000000 | (TH.surface & 0xFFFFFF));
        reg.draw(status, x + 2, y + 1.5F, Widgets.SMALL + 0.4F, 0xFF000000 | 0xF4EFE6);

        List<String[]> card = info(mc);
        if (!card.isEmpty()) {
            float cx = W / 2F + 14, cy = H / 2F + 6;
            float w = 0, h = 6;
            for (String[] l : card) {
                float sz = "h".equals(l[0]) ? Widgets.TITLE : Widgets.SMALL + 0.8F;
                UiFont f = "h".equals(l[0]) ? UiFont.heavy() : "k".equals(l[0]) ? UiFont.mono() : reg;
                w = Math.max(w, f.width(l[1], sz));
                h += f.lineHeight(sz) + 1;
            }
            w += 16;
            if (cx + w > W - 4) cx = W / 2F - 14 - w;
            Ui.round(cx, cy, cx + w, cy + h, 4, 0xE0000000 | (TH.surface & 0xFFFFFF));
            Ui.roundOutline(cx, cy, cx + w, cy + h, 4, 0.8, 0xFF000000 | TH.accent);
            float ly = cy + 3;
            for (String[] l : card) {
                float sz = "h".equals(l[0]) ? Widgets.TITLE : Widgets.SMALL + 0.8F;
                UiFont f = "h".equals(l[0]) ? UiFont.heavy() : "k".equals(l[0]) ? UiFont.mono() : reg;
                int col = "m".equals(l[0]) ? TH.muted : "a".equals(l[0]) ? Theme.readable(0xC9A227, TH.surface) : TH.text;
                f.draw(l[1], cx + 8, ly, sz, 0xFF000000 | col);
                ly += f.lineHeight(sz) + 1;
            }
        }
        GL11.glEnable(GL11.GL_DEPTH_TEST);
        GL11.glPopMatrix();
        GL11.glColor4f(1, 1, 1, 1);
    }

    /** Lines for the crosshair card: {style, text}; style h = heading, t = text, m = muted, k = mono, a = action. */
    static List<String[]> info(Minecraft mc) {
        List<String[]> out = new ArrayList<>();
        int[] mv = ClientEdit.moving;
        if (mv != null) {
            out.add(new String[] { "h", "Moving " + ClientEdit.movingName });
            out.add(new String[] { "t", "from " + mv[0] + " " + mv[1] + " " + mv[2] });
            out.add(new String[] { "a", "Right-click a block face: put it there (facing you)" });
            out.add(new String[] { "m", "Right-click the air to cancel" });
            return out;
        }
        String an = ClientEdit.targetAnchor;
        if (an != null) {
            for (Object[] a : ClientEdit.anchors()) {
                if (!an.equals(a[0])) continue;
                out.add(new String[] { "h", an });
                out.add(new String[] { "t", slotText(an, ClientEdit.anchors()) });
                out.add(new String[] { "m", String.format(Locale.ROOT, "%.1f %.1f %.1f \u00b7 facing %s", (Double) a[1], (Double) a[2], (Double) a[3], Anchor.facingName(((Double) a[4]).floatValue())) });
                out.add(new String[] { "a", "Right-click: anchor editor" });
            }
            return out;
        }
        MovingObjectPosition mop = mc.objectMouseOver;
        if (mop == null || mop.typeOfHit != MovingObjectPosition.MovingObjectType.BLOCK) return out;
        int bx = mop.blockX, by = mop.blockY, bz = mop.blockZ;
        TileEntity te = mc.theWorld.getTileEntity(bx, by, bz);
        Block b = mc.theWorld.getBlock(bx, by, bz);
        if (te instanceof TileAgentCraft) {
            TileAgentCraft t = (TileAgentCraft) te;
            PanelType type = PanelTypes.get(EditService.typeOf(b));
            String name = type == null ? b.getLocalizedName() : type.name;
            out.add(new String[] { "h", name + (t.label.isEmpty() ? "" : "  \u201c" + t.label + "\u201d") });
            String bind;
            if (type != null && PanelType.BOARD.equals(type.source)) bind = "shows " + BoardView.scopeLabel(t.binding);
            else if (type != null && PanelType.NONE.equals(type.source)) bind = "shows the whole fleet";
            else bind = t.binding.isEmpty() ? "unbound" : "fleet".equals(t.binding) ? "shows the whole fleet" : "bound to " + BoardView.agentName(t.binding);
            out.add(new String[] { "t", bind });
            String facing = type != null && type.faced ? " \u00b7 facing " + PanelSpec.facingOfMeta(mc.theWorld.getBlockMetadata(bx, by, bz)) : "";
            out.add(new String[] { "m", (type != null && type.resizable ? t.screenW + " \u00d7 " + t.screenH + " blocks" : "one block") + facing + " \u00b7 " + bx + " " + by + " " + bz });
            if (!t.binding.isEmpty()) out.add(new String[] { "k", t.binding });
            out.add(new String[] { "a", "Right-click: Panel Inspector" });
        } else if (te instanceof TileEntitySign) {
            out.add(new String[] { "h", "Vanilla sign" });
            out.add(new String[] { "a", "Right-click: offer it as the overflow sign" });
        } else {
            out.add(new String[] { "h", b.getLocalizedName() });
            out.add(new String[] { "m", "not an AgentCraft panel: the tool never changes it" });
            out.add(new String[] { "a", "Right-click: palette, anchors, layouts" });
        }
        return out;
    }
}
