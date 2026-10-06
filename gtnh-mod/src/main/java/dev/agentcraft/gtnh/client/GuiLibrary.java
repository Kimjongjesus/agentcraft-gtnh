package dev.agentcraft.gtnh.client;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.GuiTextField;

import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;

import dev.agentcraft.gtnh.state.ClientAgentCache;
import dev.agentcraft.gtnh.state.ClientHq;
import dev.agentcraft.gtnh.state.HqData;

/**
 * Read-only library screen (right-click an Agent Library): the notes the adapter exposes (agent
 * plans, handoffs and review verdicts, done-card summaries, board overviews, open decision
 * questions), already filtered by the adapter. Search box + kind filter + scrolling reader. The
 * search text, filter, scroll and selection are local GUI state; nothing is sent to the server.
 */
public class GuiLibrary extends GuiScreen {

    private static final String[] KINDS = { "", "plan", "handoff", "review", "summary", "overview", "decision" };
    private static final String[] KIND_LABELS = { "All", "Plans", "Handoffs", "Reviews", "Done", "Boards", "Decisions" };
    private static final int ROW_H = 20;

    private final String binding;
    private final String initialQuery;
    private GuiTextField search;
    private int kind;
    private String selectedId;
    private int listScroll, bodyScroll;
    private int top, listLeft, listRight, readerLeft, readerRight, bottom;

    public GuiLibrary(String binding, String query) {
        this.binding = binding == null ? "" : binding;
        this.initialQuery = query == null ? "" : query;
    }

    @Override
    public boolean doesGuiPauseGame() {
        return false;
    }

    @Override
    @SuppressWarnings("unchecked")
    public void initGui() {
        Keyboard.enableRepeatEvents(true);
        String q = search == null ? initialQuery : search.getText();
        listLeft = 8;
        listRight = Math.max(160, Math.min(240, width * 2 / 5));
        readerLeft = listRight + 8;
        readerRight = width - 8;
        top = 52;
        bottom = height - 10;
        search = new GuiTextField(fontRendererObj, listLeft, 20, listRight - listLeft, 14);
        search.setMaxStringLength(60);
        search.setText(q);
        search.setFocused(true);
        buttonList.clear();
        int bx = readerLeft, by = 18;
        for (int i = 0; i < KINDS.length; i++) {
            int w = fontRendererObj.getStringWidth(KIND_LABELS[i]) + 10;
            if (bx + w > readerRight && bx > readerLeft) {
                // narrow screen (big GUI scale): the remaining filters go on a second row
                bx = readerLeft;
                by += 18;
            }
            buttonList.add(new GuiButton(i, bx, by, w, 16, KIND_LABELS[i]));
            bx += w + 2;
        }
        top = Math.max(top, by + 30); // the reader panel starts 12 px above top
        updateButtons();
    }

    @Override
    public void onGuiClosed() {
        Keyboard.enableRepeatEvents(false);
    }

    private void updateButtons() {
        for (Object o : buttonList) {
            GuiButton b = (GuiButton) o;
            b.enabled = b.id != kind;
        }
    }

    @Override
    protected void actionPerformed(GuiButton b) {
        kind = b.id;
        listScroll = 0;
        updateButtons();
    }

    /** Notes matching board binding, kind filter and search text (title, body, author, kind, task). */
    private List<HqData.Note> visible() {
        String q = search == null ? "" : search.getText()
            .trim()
            .toLowerCase(Locale.ROOT);
        List<HqData.Note> out = new ArrayList<>();
        for (HqData.Note n : ClientHq.notes) {
            if (!n.board.isEmpty() && !ClientHq.onBoard(binding, n.board)) continue;
            if (!KINDS[kind].isEmpty() && !KINDS[kind].equals(n.kind)) continue;
            if (!q.isEmpty()) {
                String hay = (n.title + "\n" + n.body + "\n" + n.author + "\n" + n.kind + "\n" + n.taskId).toLowerCase(Locale.ROOT);
                boolean all = true;
                for (String word : q.split("\\s+")) if (!hay.contains(word)) all = false;
                if (!all) continue;
            }
            out.add(n);
        }
        return out;
    }

    private static String age(long ts) {
        if (ts <= 0) return "";
        long s = Math.max(0, (System.currentTimeMillis() - ts) / 1000);
        return s < 3600 ? (s / 60) + "m" : s < 86400 ? (s / 3600) + "h" : (s / 86400) + "d";
    }

    private static int kindColor(String k) {
        switch (k) {
            case "plan":
                return 0x2FA3A0;
            case "handoff":
                return 0xC9A227;
            case "review":
                return 0x5B8DEF;
            case "summary":
                return 0x8FA98B;
            case "decision":
                return 0xD97757;
            default:
                return 0x9C9488;
        }
    }

    private int rowsVisible() {
        return Math.max(1, (bottom - top) / ROW_H);
    }

    @Override
    public void drawScreen(int mx, int my, float partial) {
        drawDefaultBackground();
        drawString(fontRendererObj, "\u00a7lAgent Library\u00a7r  " + RenderHqTile.bindingLabel(binding) + "  (read-only, filtered by the adapter)", 8, 6, 0xF4EFE6);
        search.drawTextBox();
        if (search.getText()
            .isEmpty() && !search.isFocused()) drawString(fontRendererObj, "search...", listLeft + 4, 23, 0x7C756B);
        List<HqData.Note> notes = visible();
        String count = notes.size() + " of " + ClientHq.notes.size() + " notes" + (ClientAgentCache.linkUp ? "" : "  \u00a7c(adapter offline)");
        drawString(fontRendererObj, count, listLeft, top - 12, 0x9C9488);
        if (selectedId == null && !notes.isEmpty()) selectedId = notes.get(0).id;
        HqData.Note sel = null;
        for (HqData.Note n : notes) if (n.id.equals(selectedId)) sel = n;
        if (sel == null && !notes.isEmpty()) {
            sel = notes.get(0);
            selectedId = sel.id;
            bodyScroll = 0;
        }

        int rows = rowsVisible();
        listScroll = Math.max(0, Math.min(listScroll, Math.max(0, notes.size() - rows)));
        for (int r = 0; r < rows && listScroll + r < notes.size(); r++) {
            HqData.Note n = notes.get(listScroll + r);
            int y = top + r * ROW_H;
            boolean isSel = n == sel;
            boolean hover = mx >= listLeft && mx < listRight && my >= y && my < y + ROW_H - 2;
            drawRect(listLeft, y, listRight, y + ROW_H - 2, isSel ? 0xFFF4EFE6 : hover ? 0xFFFFFBF4 : 0xFFE9E1D3);
            drawRect(listLeft, y, listLeft + 3, y + ROW_H - 2, 0xFF000000 | kindColor(n.kind));
            fontRendererObj.drawString(RenderHqTile.clip(fontRendererObj, n.title, listRight - listLeft - 10), listLeft + 6, y + 1, RenderHqTile.INK);
            String who = n.author.isEmpty() ? n.scope : RenderHqTile.agentName(n.author);
            String meta = n.kind + " \u00b7 " + age(n.updated);
            int mw = fontRendererObj.getStringWidth(meta);
            fontRendererObj.drawString(
                RenderHqTile.clip(fontRendererObj, who, listRight - listLeft - 14 - mw),
                listLeft + 6,
                y + 10,
                n.author.isEmpty() ? 0x6E675E : RenderHqTile.onPaper(RenderHqTile.agentColor(n.author)));
            fontRendererObj.drawString(meta, listRight - 3 - mw, y + 10, 0x6E675E);
        }
        if (notes.isEmpty()) drawString(fontRendererObj, ClientHq.haveLibrary ? "No notes match." : "Waiting for the library...", listLeft, top + 4, 0xB0A898);

        drawRect(readerLeft, top - 12, readerRight, bottom, 0xE0141210);
        if (sel != null) drawReader(sel);
        super.drawScreen(mx, my, partial);
    }

    @SuppressWarnings("unchecked")
    private void drawReader(HqData.Note n) {
        int x = readerLeft + 6, w = readerRight - readerLeft - 12;
        List<String[]> lines = new ArrayList<>();
        for (String s : (List<String>) fontRendererObj.listFormattedStringToWidth("\u00a7l" + n.title, w)) lines.add(new String[] { "15986662", s });
        String who = n.author.isEmpty() ? n.scope : RenderHqTile.agentName(n.author) + " (" + n.author + ")";
        String meta = n.kind + " \u00b7 " + who + (n.board.isEmpty() ? "" : " \u00b7 board " + n.board)
            + (n.taskId.isEmpty() ? "" : " \u00b7 " + n.taskId)
            + " \u00b7 " + age(n.updated) + " ago";
        for (String s : (List<String>) fontRendererObj.listFormattedStringToWidth(meta, w)) lines.add(new String[] { "10265736", s });
        lines.add(new String[] { "0", "" });
        for (String para : n.body.split("\n")) {
            if (para.trim()
                .isEmpty()) {
                lines.add(new String[] { "0", "" });
                continue;
            }
            int c = para.startsWith("- ") ? 0xD8D2C8 : para.endsWith(":") ? 0xC9A227 : 0xE3DACB;
            for (String s : (List<String>) fontRendererObj.listFormattedStringToWidth(para, w)) lines.add(new String[] { Integer.toString(c), s });
        }
        int rows = (bottom - top) / 10;
        bodyScroll = Math.max(0, Math.min(bodyScroll, Math.max(0, lines.size() - rows)));
        for (int i = 0; i < rows && bodyScroll + i < lines.size(); i++) {
            String[] l = lines.get(bodyScroll + i);
            fontRendererObj.drawString(l[1], x, top - 6 + i * 10, Integer.parseInt(l[0]));
        }
        if (lines.size() > rows) {
            String more = "wheel: " + Math.min(lines.size(), bodyScroll + rows) + "/" + lines.size() + " lines";
            fontRendererObj.drawString(more, readerRight - 4 - fontRendererObj.getStringWidth(more), bottom + 1, 0x7C756B);
        }
    }

    @Override
    public void updateScreen() {
        search.updateCursorCounter();
    }

    @Override
    public void handleMouseInput() {
        super.handleMouseInput();
        int d = Mouse.getEventDWheel();
        if (d == 0) return;
        int mx = Mouse.getEventX() * width / mc.displayWidth;
        int step = d > 0 ? -1 : 1;
        if (mx >= readerLeft) bodyScroll = Math.max(0, bodyScroll + step * 3);
        else listScroll = Math.max(0, listScroll + step);
    }

    @Override
    protected void mouseClicked(int mx, int my, int button) {
        super.mouseClicked(mx, my, button);
        search.mouseClicked(mx, my, button);
        if (mx < listLeft || mx >= listRight || my < top || my >= bottom) return;
        int r = (my - top) / ROW_H;
        List<HqData.Note> notes = visible();
        int i = listScroll + r;
        if (r < rowsVisible() && i < notes.size()) {
            selectedId = notes.get(i).id;
            bodyScroll = 0;
        }
    }

    @Override
    protected void keyTyped(char ch, int key) {
        if (key == Keyboard.KEY_ESCAPE) {
            mc.displayGuiScreen(null);
            return;
        }
        if (search.textboxKeyTyped(ch, key)) {
            listScroll = 0;
            return;
        }
        if (key == Keyboard.KEY_DOWN) bodyScroll++;
        if (key == Keyboard.KEY_UP) bodyScroll = Math.max(0, bodyScroll - 1);
    }

    /** Dev/QA: select the first note whose id contains the text. */
    public void selectFirstMatching(String idPart) {
        for (HqData.Note n : visible()) {
            if (n.id.contains(idPart)) {
                selectedId = n.id;
                return;
            }
        }
    }
}
