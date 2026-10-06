package dev.agentcraft.gtnh.client;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.renderer.OpenGlHelper;
import net.minecraft.client.renderer.Tessellator;

import org.lwjgl.opengl.GL11;

import dev.agentcraft.gtnh.Config;
import dev.agentcraft.gtnh.block.TileAgentCraft;
import dev.agentcraft.gtnh.state.AgentInfo;
import dev.agentcraft.gtnh.state.ClientAgentCache;
import dev.agentcraft.gtnh.state.ClientHq;
import dev.agentcraft.gtnh.state.HqData;

/**
 * Card 3 in-world displays, drawn from ClientHq (filled only by server blobs; nothing here talks to
 * the network):
 * <ul>
 * <li>Task Wall: a W x H screen (default 5 x 3; the block is its bottom-centre) with the five
 * columns todo / doing / review / done / blocked; cards show the title (two lines, truncated),
 * the assignee in its agent colour, a priority hint and a dependency marker. A column with more
 * cards than fit flips pages every wallPageSeconds ("1/3" in its header).</li>
 * <li>Goal Atrium: a W x H panel (default 3 x 3) with a progress ring (done / total cards of the
 * bound board, or all boards), the todo / doing / review / blocked counts and "N decisions need
 * you" (a count; nothing in the game answers a decision).</li>
 * </ul>
 * Both draw only within wallRenderDistance blocks; layouts are cached per data version.
 */
public final class RenderHqTile {

    static final int WALL_PX = 96, ATRIUM_PX = 80;
    static final int INK = 0x1F1E1D, PAPER = 0xE9E1D3, CREAM = 0xF4EFE6, CLAY = 0xD97757, SAGE = 0x8FA98B;
    static final int[] COLUMN_COLORS = { 0x9C9488, 0x2FA3A0, 0xC9A227, 0x8FA98B, 0xD97757 };
    static final String[] COLUMN_NAMES = { "To do", "Doing", "Review", "Done", "Blocked" };

    private static long cacheVersion = -1;
    private static final Map<String, List<List<HqData.Task>>> COLUMNS = new HashMap<>();
    private static final Map<String, List<String>> WRAPS = new HashMap<>();

    private RenderHqTile() {}

    // ---- shared ---------------------------------------------------------------------------

    /** Columns of one binding, sorted for display; cached until the next blob. */
    static List<List<HqData.Task>> columns(String binding) {
        if (cacheVersion != ClientHq.version) {
            COLUMNS.clear();
            WRAPS.clear();
            cacheVersion = ClientHq.version;
        }
        List<List<HqData.Task>> cols = COLUMNS.get(binding);
        if (cols != null) return cols;
        cols = new ArrayList<>();
        for (int i = 0; i < HqData.COLUMNS.length; i++) cols.add(new ArrayList<>());
        for (HqData.Task t : ClientHq.tasksFor(binding)) cols.get(HqData.column(t.status))
            .add(t);
        for (int i = 0; i < cols.size(); i++) {
            final boolean done = i == 3;
            Collections.sort(
                cols.get(i),
                (a, b) -> done || a.priority == b.priority ? Long.compare(b.updatedAt, a.updatedAt)
                    : Integer.compare(b.priority, a.priority));
        }
        COLUMNS.put(binding, cols);
        return cols;
    }

    /** Up to maxLines wrapped lines, the last one ellipsized if text was cut. */
    @SuppressWarnings("unchecked")
    static List<String> wrap(FontRenderer fr, String text, int width, int maxLines) {
        String key = width + "|" + maxLines + "|" + text;
        List<String> got = WRAPS.get(key);
        if (got != null) return got;
        List<String> lines = new ArrayList<>(fr.listFormattedStringToWidth(text, width));
        if (lines.size() > maxLines) {
            List<String> cut = new ArrayList<>(lines.subList(0, maxLines));
            String last = cut.get(maxLines - 1);
            cut.set(maxLines - 1, fr.trimStringToWidth(last, width - fr.getStringWidth("...")) + "...");
            lines = cut;
        }
        if (WRAPS.size() > 2000) WRAPS.clear();
        WRAPS.put(key, lines);
        return lines;
    }

    static String clip(FontRenderer fr, String s, int width) {
        if (fr.getStringWidth(s) <= width) return s;
        return fr.trimStringToWidth(s, Math.max(0, width - fr.getStringWidth("..."))) + "...";
    }

    static String agentName(String id) {
        AgentInfo a = ClientAgentCache.get(id);
        return a == null ? id : a.name;
    }

    static int agentColor(String id) {
        AgentInfo a = ClientAgentCache.get(id);
        return a == null ? 0x9C9488 : a.color;
    }

    /** Darker variant of a colour for text on paper. */
    static int onPaper(int rgb) {
        int r = (rgb >> 16) & 0xFF, g = (rgb >> 8) & 0xFF, b = rgb & 0xFF;
        int lum = (r * 299 + g * 587 + b * 114) / 1000;
        if (lum < 120) return rgb;
        return ((r * 3 / 5) << 16) | ((g * 3 / 5) << 8) | (b * 3 / 5);
    }

    static void quad(double x0, double y0, double x1, double y1, int argb, float z) {
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

    /** Push a canvas on the block's front face: origin = top-left of a W x H screen, +x right, +y down. */
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
        GL11.glColor4f(1.0F, 1.0F, 1.0F, 1.0F);
        GL11.glPopMatrix();
    }

    static String headerRight(HqData.Goal g) {
        int open = g.counts[0] + g.counts[1] + g.counts[2] + g.counts[4];
        return open + " open \u00b7 " + g.openDecisions + " need you";
    }

    static String bindingLabel(String binding) {
        return binding == null || binding.isEmpty() || "all".equals(binding) ? "all boards" : "board " + binding;
    }

    // ---- task wall ------------------------------------------------------------------------

    static void taskWall(FontRenderer fr, TileAgentCraft t, double x, double y, double z) {
        int wPx = t.screenW * WALL_PX, hPx = t.screenH * WALL_PX;
        beginCanvas(t, x, y, z, WALL_PX);
        quad(-3, -3, wPx + 3, hPx + 3, 0xFF3B2A20, -0.2F); // walnut frame
        quad(0, 0, wPx, hPx, 0xF0222019, -0.1F);
        GL11.glDepthMask(false);
        int pad = 5;
        String binding = t.binding;
        boolean link = ClientAgentCache.linkUp;
        HqData.Goal sum = ClientHq.summary(binding);
        fr.drawString("\u00a7lTASK WALL\u00a7r  " + bindingLabel(binding), pad, pad, 0xFF000000 | CREAM);
        String right = !link ? "Hermes adapter offline" : !ClientHq.haveBoard ? "waiting for the board..." : headerRight(sum);
        fr.drawString(right, wPx - pad - fr.getStringWidth(right), pad, 0xFF000000 | (!link ? 0xC2413B : sum.openDecisions > 0 ? CLAY : 0xB0A898));
        int top = pad + 12;
        int nCols = HqData.COLUMNS.length;
        int gap = 4;
        int colW = (wPx - 2 * pad - gap * (nCols - 1)) / nCols;
        List<List<HqData.Task>> cols = columns(binding);
        int cardH = 33, cardGap = 3;
        int listTop = top + 14;
        int rows = Math.max(1, (hPx - pad - listTop + cardGap) / (cardH + cardGap));
        long pageTick = System.currentTimeMillis() / (Config.wallPageSeconds * 1000L);
        for (int c = 0; c < nCols; c++) {
            int cx = pad + c * (colW + gap);
            List<HqData.Task> col = cols.get(c);
            int pages = Math.max(1, (col.size() + rows - 1) / rows);
            int page = (int) (pageTick % pages);
            quad(cx, top, cx + colW, top + 11, 0xFF000000 | darken(COLUMN_COLORS[c]), 0.0F);
            String head = COLUMN_NAMES[c] + " " + col.size();
            fr.drawString(head, cx + 3, top + 2, 0xFF000000 | CREAM);
            if (pages > 1) {
                String pg = (page + 1) + "/" + pages;
                fr.drawString(pg, cx + colW - 3 - fr.getStringWidth(pg), top + 2, 0xFFE3DACB);
            }
            for (int r = 0; r < rows; r++) {
                int i = page * rows + r;
                if (i >= col.size()) break;
                card(fr, col.get(i), c, cx, listTop + r * (cardH + cardGap), colW, cardH);
            }
            if (col.isEmpty()) fr.drawString("-", cx + colW / 2 - 2, listTop + 4, 0xFF6E675E);
        }
        endCanvas();
    }

    private static void card(FontRenderer fr, HqData.Task t, int col, int x, int y, int w, int h) {
        boolean blocked = col == 4;
        int bg = blocked ? 0xFFF1D2C4 : col == 3 ? 0xFFD9DED2 : 0xFF000000 | PAPER;
        quad(x, y, x + w, y + h, bg, 0.05F);
        int stripe = t.assignee.isEmpty() ? 0x9C9488 : agentColor(t.assignee);
        quad(x, y, x + 2, y + h, 0xFF000000 | stripe, 0.08F);
        int tx = x + 4, tw = w - 6;
        List<String> lines = wrap(fr, t.title, tw, 2);
        for (int i = 0; i < lines.size(); i++) fr.drawString(lines.get(i), tx, y + 2 + i * 9, 0xFF000000 | INK);
        // meta line: assignee (agent colour), then priority / deps hints on the right
        StringBuilder hint = new StringBuilder();
        if (t.priority > 0) hint.append("P")
            .append(t.priority);
        if (!t.deps.isEmpty()) {
            if (hint.length() > 0) hint.append(' ');
            hint.append("\u2192")
                .append(t.deps.size());
        }
        String h2 = hint.toString();
        int hw = fr.getStringWidth(h2);
        String who = t.assignee.isEmpty() ? "unassigned" : agentName(t.assignee);
        fr.drawString(clip(fr, who, tw - hw - 3), tx, y + h - 10, 0xFF000000 | (t.assignee.isEmpty() ? 0x7C756B : onPaper(stripe)));
        if (hw > 0) fr.drawString(h2, x + w - 2 - hw, y + h - 10, 0xFF000000 | (t.priority >= 80 ? 0xB4553A : 0x6E5615));
    }

    static int darken(int rgb) {
        return ((((rgb >> 16) & 0xFF) * 3 / 5) << 16) | ((((rgb >> 8) & 0xFF) * 3 / 5) << 8) | ((rgb & 0xFF) * 3 / 5);
    }

    // ---- atrium ---------------------------------------------------------------------------

    static void atrium(FontRenderer fr, TileAgentCraft t, double x, double y, double z) {
        int wPx = t.screenW * ATRIUM_PX, hPx = t.screenH * ATRIUM_PX;
        beginCanvas(t, x, y, z, ATRIUM_PX);
        quad(-3, -3, wPx + 3, hPx + 3, 0xFF3B2A20, -0.2F);
        quad(0, 0, wPx, hPx, 0xF01A1917, -0.1F);
        GL11.glDepthMask(false);
        boolean link = ClientAgentCache.linkUp;
        HqData.Goal g = ClientHq.summary(t.binding);
        int pad = 6;
        String title = "\u00a7lGOAL ATRIUM";
        fr.drawString(title, (wPx - fr.getStringWidth(title)) / 2, pad, 0xFF000000 | CREAM);
        String sub = clip(fr, g.text, wPx - 2 * pad);
        fr.drawString(sub, (wPx - fr.getStringWidth(sub)) / 2, pad + 10, 0xFFB0A898);

        int footer = 30;
        int ringTop = pad + 22;
        int ringSpace = Math.min(wPx - 2 * pad, hPx - ringTop - footer);
        double cx = wPx / 2.0, cy = ringTop + ringSpace / 2.0;
        double rOut = ringSpace / 2.0 - 2, rIn = rOut * 0.74;
        ring(cx, cy, rIn, rOut, 0.0, 1.0, 0xFF3A3632, 0.0F);
        float p = link ? g.progress : 0.0F;
        if (p > 0) ring(cx, cy, rIn, rOut, 0.0, p, 0xFF000000 | SAGE, 0.02F);
        // centre: percentage (2x) and done / total
        String pct = Math.round(p * 100) + "%";
        GL11.glPushMatrix();
        GL11.glTranslated(cx, cy - 10, 0.04);
        GL11.glScalef(2.0F, 2.0F, 1.0F);
        fr.drawString(pct, -fr.getStringWidth(pct) / 2, 0, 0xFF000000 | CREAM);
        GL11.glPopMatrix();
        String done = g.counts[3] + " / " + g.total + " done";
        fr.drawString(done, (int) (cx - fr.getStringWidth(done) / 2.0), (int) (cy + 9), 0xFF000000 | SAGE);

        // counts row: todo doing review blocked
        int[] order = { 0, 1, 2, 4 };
        int cellW = (wPx - 2 * pad) / order.length;
        int rowY = hPx - footer + 2;
        for (int i = 0; i < order.length; i++) {
            int c = order[i];
            int x0 = pad + i * cellW;
            quad(x0 + 1, rowY, x0 + 5, rowY + 7, 0xFF000000 | COLUMN_COLORS[c], 0.03F);
            String s = (link ? g.counts[c] : 0) + " " + COLUMN_NAMES[c].toLowerCase();
            fr.drawString(clip(fr, s, cellW - 8), x0 + 7, rowY, 0xFFD8D2C8);
        }
        String dec;
        int decColor;
        if (!link) {
            dec = "Hermes adapter offline";
            decColor = 0xC2413B;
        } else if (g.openDecisions > 0) {
            dec = g.openDecisions + (g.openDecisions == 1 ? " decision needs you" : " decisions need you");
            double tt = (System.currentTimeMillis() % 2000L) / 2000.0D;
            float k = 0.65F + 0.35F * (float) (0.5 + 0.5 * Math.sin(tt * Math.PI * 2));
            decColor = (Math.round(((CLAY >> 16) & 0xFF) * k) << 16) | (Math.round(((CLAY >> 8) & 0xFF) * k) << 8)
                | Math.round((CLAY & 0xFF) * k);
        } else {
            dec = "no decisions waiting";
            decColor = 0x9C9488;
        }
        fr.drawString(dec, (wPx - fr.getStringWidth(dec)) / 2, hPx - 13, 0xFF000000 | decColor);
        endCanvas();
    }

    /** Annulus segment from fraction a0 to a1 (0 = top, clockwise), canvas coordinates (+y down). */
    static void ring(double cx, double cy, double rIn, double rOut, double a0, double a1, int argb, float z) {
        Tessellator t = Tessellator.instance;
        GL11.glDisable(GL11.GL_TEXTURE_2D);
        GL11.glEnable(GL11.GL_BLEND);
        OpenGlHelper.glBlendFunc(770, 771, 1, 0);
        t.startDrawingQuads();
        t.setColorRGBA((argb >> 16) & 0xFF, (argb >> 8) & 0xFF, argb & 0xFF, (argb >>> 24) & 0xFF);
        int seg = Math.max(2, (int) Math.ceil((a1 - a0) * 72));
        for (int i = 0; i < seg; i++) {
            double f0 = a0 + (a1 - a0) * i / seg, f1 = a0 + (a1 - a0) * (i + 1) / seg;
            double t0 = f0 * Math.PI * 2, t1 = f1 * Math.PI * 2;
            double s0 = Math.sin(t0), c0 = -Math.cos(t0), s1 = Math.sin(t1), c1 = -Math.cos(t1);
            t.addVertex(cx + s0 * rIn, cy + c0 * rIn, z);
            t.addVertex(cx + s1 * rIn, cy + c1 * rIn, z);
            t.addVertex(cx + s1 * rOut, cy + c1 * rOut, z);
            t.addVertex(cx + s0 * rOut, cy + c0 * rOut, z);
        }
        t.draw();
        GL11.glEnable(GL11.GL_TEXTURE_2D);
    }
}
