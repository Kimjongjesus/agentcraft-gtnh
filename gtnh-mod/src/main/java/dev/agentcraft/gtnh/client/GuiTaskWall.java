package dev.agentcraft.gtnh.client;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import net.minecraft.client.gui.GuiScreen;

import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;

import dev.agentcraft.gtnh.state.ClientAgentCache;
import dev.agentcraft.gtnh.state.ClientHq;
import dev.agentcraft.gtnh.state.HqData;

/**
 * Read-only task wall screen (right-click a Task Wall or Goal Atrium): the five columns with every
 * card of the bound board (scroll each column with the mouse wheel), and the selected card's detail
 * (status, assignee, priority, dependencies with their status, branch, blocked reason, description,
 * summary). Nothing here sends anything to the server; scroll and selection are local state.
 */
public class GuiTaskWall extends GuiScreen {

    private final String binding;
    private String selectedId;
    private final int[] scroll = new int[HqData.COLUMNS.length];
    private int detailScroll;
    private final SimpleDateFormat when = new SimpleDateFormat("MMM d HH:mm");
    /** Card height: two lines (title, assignee), three when columns are narrow (title wraps). */
    private int rowH = 22;

    // layout (recomputed in initGui)
    private int top, colsLeft, colsRight, colW, listTop, bottom, detailLeft, detailRight;

    public GuiTaskWall(String binding, String selectId) {
        this.binding = binding == null ? "" : binding;
        this.selectedId = selectId;
    }

    @Override
    public boolean doesGuiPauseGame() {
        return false;
    }

    @Override
    public void initGui() {
        top = 30;
        bottom = height - 12;
        colsLeft = 8;
        int detailW = Math.max(150, Math.min(260, width * 2 / 5));
        detailRight = width - 8;
        detailLeft = detailRight - detailW;
        colsRight = detailLeft - 8;
        colW = (colsRight - colsLeft - 4 * 3) / HqData.COLUMNS.length;
        rowH = colW < 90 ? 31 : 22;
        listTop = top + 14;
        if (selectedId == null) {
            List<List<HqData.Task>> cols = RenderHqTile.columns(binding);
            for (int c : new int[] { 1, 4, 2, 0, 3 }) {
                if (!cols.get(c)
                    .isEmpty()) {
                    selectedId = cols.get(c)
                        .get(0).id;
                    break;
                }
            }
        }
    }

    private int rowsVisible() {
        return Math.max(1, (bottom - listTop) / rowH);
    }

    @Override
    public void drawScreen(int mx, int my, float partial) {
        drawDefaultBackground();
        HqData.Goal g = ClientHq.summary(binding);
        drawString(fontRendererObj, "\u00a7lTask Wall\u00a7r  " + RenderHqTile.bindingLabel(binding) + "  (read-only)", 8, 8, 0xF4EFE6);
        String sum = !ClientAgentCache.linkUp ? "\u00a7cHermes adapter offline\u00a7r (showing the last data)"
            : RenderHqTile.headerRight(g) + " \u00b7 " + g.counts[3] + "/" + g.total + " done (" + Math.round(g.progress * 100) + "%)";
        drawString(fontRendererObj, sum, 8, 18, 0xB0A898);
        String hint = "wheel: scroll  click: details  Esc: close";
        drawString(fontRendererObj, hint, width - 8 - fontRendererObj.getStringWidth(hint), 8, 0x7C756B);

        List<List<HqData.Task>> cols = RenderHqTile.columns(binding);
        int rows = rowsVisible();
        for (int c = 0; c < cols.size(); c++) {
            int x = colsLeft + c * (colW + 3);
            List<HqData.Task> col = cols.get(c);
            scroll[c] = Math.max(0, Math.min(scroll[c], Math.max(0, col.size() - rows)));
            drawRect(x, top, x + colW, top + 11, 0xFF000000 | RenderHqTile.darken(RenderHqTile.COLUMN_COLORS[c]));
            drawString(fontRendererObj, RenderHqTile.COLUMN_NAMES[c] + " " + col.size(), x + 3, top + 2, 0xF4EFE6);
            for (int r = 0; r < rows && scroll[c] + r < col.size(); r++) {
                HqData.Task t = col.get(scroll[c] + r);
                int y = listTop + r * rowH;
                boolean sel = t.id.equals(selectedId);
                boolean hover = mx >= x && mx < x + colW && my >= y && my < y + rowH - 2;
                int bg = sel ? 0xFFF4EFE6 : c == 4 ? 0xFFF1D2C4 : c == 3 ? 0xFFD9DED2 : 0xFFE9E1D3;
                drawRect(x, y, x + colW, y + rowH - 2, hover && !sel ? 0xFFFFFBF4 : bg);
                int stripe = t.assignee.isEmpty() ? 0x9C9488 : RenderHqTile.agentColor(t.assignee);
                drawRect(x, y, x + 2, y + rowH - 2, 0xFF000000 | stripe);
                if (rowH > 22) {
                    // narrow columns: the title gets two lines, the second one clipped
                    List<String> wrapped = wrap(t.title, colW - 6);
                    fontRendererObj.drawString(wrapped.isEmpty() ? "" : wrapped.get(0), x + 4, y + 2, RenderHqTile.INK);
                    if (wrapped.size() > 1) {
                        String rest = t.title.substring(Math.min(t.title.length(), wrapped.get(0).length()))
                            .trim();
                        fontRendererObj.drawString(RenderHqTile.clip(fontRendererObj, rest, colW - 6), x + 4, y + 11, RenderHqTile.INK);
                    }
                } else {
                    fontRendererObj.drawString(RenderHqTile.clip(fontRendererObj, t.title, colW - 6), x + 4, y + 2, RenderHqTile.INK);
                }
                int whoY = y + rowH - 11;
                String who = t.assignee.isEmpty() ? "unassigned" : RenderHqTile.agentName(t.assignee);
                String hintR = (t.priority > 0 ? "P" + t.priority : "") + (t.deps.isEmpty() ? "" : " \u2192" + t.deps.size());
                int hw = fontRendererObj.getStringWidth(hintR);
                fontRendererObj.drawString(
                    RenderHqTile.clip(fontRendererObj, who, colW - 8 - hw),
                    x + 4,
                    whoY,
                    RenderHqTile.onPaper(stripe));
                if (hw > 0) fontRendererObj.drawString(hintR, x + colW - 2 - hw, whoY, 0x6E5615);
            }
            if (col.size() > rows) {
                String more = (scroll[c] + 1) + "-" + Math.min(col.size(), scroll[c] + rows) + " of " + col.size();
                fontRendererObj.drawString(more, x + colW - fontRendererObj.getStringWidth(more), bottom + 2, 0x7C756B);
            }
        }
        drawDetail();
        super.drawScreen(mx, my, partial);
    }

    private void drawDetail() {
        drawRect(detailLeft, top, detailRight, bottom, 0xE0141210);
        HqData.Task t = selectedId == null ? null : ClientHq.task(selectedId);
        int x = detailLeft + 6, w = detailRight - detailLeft - 12;
        if (t == null) {
            drawString(fontRendererObj, ClientHq.haveBoard ? "Click a card for its details." : "Waiting for the board...", x, top + 6, 0xB0A898);
            return;
        }
        List<String[]> lines = new ArrayList<>(); // [colour, text]
        add(lines, w, 0xF4EFE6, "\u00a7l" + t.title);
        add(lines, w, 0x9C9488, t.id + (t.board.isEmpty() ? "" : " \u00b7 board " + t.board));
        lines.add(new String[] { "0", "" });
        int col = HqData.column(t.status);
        add(lines, w, col < 0 ? 0x9C9488 : RenderHqTile.COLUMN_COLORS[col], "Status: " + (col < 0 ? t.status : RenderHqTile.COLUMN_NAMES[col]));
        add(
            lines,
            w,
            t.assignee.isEmpty() ? 0x9C9488 : RenderHqTile.agentColor(t.assignee),
            "Assignee: " + (t.assignee.isEmpty() ? "unassigned" : RenderHqTile.agentName(t.assignee) + " (" + t.assignee + ")"));
        add(lines, w, 0xD8D2C8, "Priority: " + t.priority + (t.updatedAt > 0 ? "   updated " + when.format(new Date(t.updatedAt)) : ""));
        if (!t.branch.isEmpty()) add(lines, w, 0xD8D2C8, "Branch: " + t.branch);
        if (!t.deps.isEmpty()) {
            add(lines, w, 0xD8D2C8, "Depends on:");
            for (String d : t.deps) {
                HqData.Task dt = ClientHq.task(d);
                add(lines, w, 0xB0A898, "  " + d + (dt == null ? "" : " - " + dt.status + ": " + dt.title));
            }
        }
        if (!t.blockedReason.isEmpty()) {
            lines.add(new String[] { "0", "" });
            add(lines, w, 0xD97757, "Blocked: " + t.blockedReason);
        }
        if (!t.description.isEmpty()) {
            lines.add(new String[] { "0", "" });
            add(lines, w, 0x9C9488, "Description");
            add(lines, w, 0xD8D2C8, t.description);
        }
        if (!t.summary.isEmpty()) {
            lines.add(new String[] { "0", "" });
            add(lines, w, 0x9C9488, "Summary");
            add(lines, w, 0x9CC59A, t.summary);
        }
        int rows = (bottom - top - 10) / 10;
        detailScroll = Math.max(0, Math.min(detailScroll, Math.max(0, lines.size() - rows)));
        for (int i = 0; i < rows && detailScroll + i < lines.size(); i++) {
            String[] l = lines.get(detailScroll + i);
            fontRendererObj.drawString(l[1], x, top + 6 + i * 10, Integer.parseInt(l[0]));
        }
        if (lines.size() > rows) {
            String more = "wheel: more (" + (detailScroll + rows) + "/" + lines.size() + ")";
            fontRendererObj.drawString(more, detailRight - 4 - fontRendererObj.getStringWidth(more), bottom + 2, 0x7C756B);
        }
    }

    @SuppressWarnings("unchecked")
    private List<String> wrap(String text, int w) {
        return (List<String>) fontRendererObj.listFormattedStringToWidth(text, w);
    }

    @SuppressWarnings("unchecked")
    private void add(List<String[]> out, int w, int color, String text) {
        for (String para : text.split("\n")) {
            if (para.isEmpty()) {
                out.add(new String[] { "0", "" });
                continue;
            }
            for (String s : (List<String>) fontRendererObj.listFormattedStringToWidth(para, w)) out.add(new String[] { Integer.toString(color), s });
        }
    }

    @Override
    public void handleMouseInput() {
        super.handleMouseInput();
        int d = Mouse.getEventDWheel();
        if (d == 0) return;
        int mx = Mouse.getEventX() * width / mc.displayWidth;
        int step = d > 0 ? -1 : 1;
        if (mx >= detailLeft) {
            detailScroll = Math.max(0, detailScroll + step * 3);
            return;
        }
        int c = (mx - colsLeft) / (colW + 3);
        if (c >= 0 && c < scroll.length) scroll[c] = Math.max(0, scroll[c] + step);
    }

    @Override
    protected void mouseClicked(int mx, int my, int button) {
        super.mouseClicked(mx, my, button);
        if (mx >= colsRight || my < listTop || my >= bottom) return;
        int c = (mx - colsLeft) / (colW + 3);
        if (c < 0 || c >= scroll.length) return;
        int r = (my - listTop) / rowH;
        List<HqData.Task> col = RenderHqTile.columns(binding)
            .get(c);
        int i = scroll[c] + r;
        if (r < rowsVisible() && i < col.size()) {
            selectedId = col.get(i).id;
            detailScroll = 0;
        }
    }

    @Override
    protected void keyTyped(char ch, int key) {
        super.keyTyped(ch, key);
        if (key == Keyboard.KEY_DOWN) detailScroll++;
        if (key == Keyboard.KEY_UP) detailScroll = Math.max(0, detailScroll - 1);
    }
}
