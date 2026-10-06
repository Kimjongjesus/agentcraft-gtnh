package dev.agentcraft.gtnh.client;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import net.minecraft.client.gui.GuiScreen;

import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;

import dev.agentcraft.gtnh.state.ClientAgentCache;
import dev.agentcraft.gtnh.state.ClientHq;
import dev.agentcraft.gtnh.state.HqData;
import dev.agentcraft.gtnh.ui.TextLayout;
import dev.agentcraft.gtnh.ui.Theme;
import dev.agentcraft.gtnh.ui.Ui;
import dev.agentcraft.gtnh.ui.UiFont;
import dev.agentcraft.gtnh.ui.Widgets;

/**
 * Read-only library screen (right-click an Agent Library) on the card-4 toolkit: the notes the
 * adapter exposes (agent plans, handoffs and review verdicts, done-card summaries, board overviews,
 * open decision questions), already filtered by the adapter. Search field + kind tabs + a list with
 * two-line titles + a reader that wraps the whole note by pixel width. Search text, filter, scroll
 * and selection are local GUI state; nothing is sent to the server.
 */
public class GuiLibrary extends GuiScreen {

    private static final String[] KINDS = { "", "plan", "handoff", "review", "summary", "overview", "decision" };
    private static final String[] KIND_LABELS = { "All", "Plans", "Handoffs", "Reviews", "Done", "Boards", "Decisions" };

    private final String binding;
    private final Widgets.SearchField search = new Widgets.SearchField("Search titles and text\u2026");
    private final Widgets.Tabs tabs = new Widgets.Tabs(KIND_LABELS);
    private final Widgets.ScrollList list = new Widgets.ScrollList(), reader = new Widgets.ScrollList();
    private final Theme th = Theme.DARK;
    private String selectedId;
    private final List<Object[]> rows = new ArrayList<>();

    public GuiLibrary(String binding, String query) {
        this.binding = binding == null ? "" : binding;
        search.text = query == null ? "" : query;
    }

    @Override
    public boolean doesGuiPauseGame() {
        return false;
    }

    @Override
    public void initGui() {
        Keyboard.enableRepeatEvents(true);
        layout();
    }

    private void layout() {
        float pad = 8;
        float y = pad + UiFont.heavy()
            .lineHeight(Widgets.H1) + 4;
        float split = Math.max(170, Math.min(width * 0.42F, 280));
        search.at(pad, y, split - pad, 14);
        float tabsBottom = tabs.layout(split + 8, y + 0.5F, width - split - 8 - pad, 13);
        float top = Math.max(y + 14, tabsBottom) + 6;
        list.bounds(pad, top + 10, split, height - pad);
        reader.bounds(split + 8, top, width - pad, height - pad);
    }

    @Override
    public void onGuiClosed() {
        Keyboard.enableRepeatEvents(false);
    }

    /** Notes matching board binding, kind filter and search text (title, body, author, kind, task). */
    private List<HqData.Note> visible() {
        String q = search.text.trim()
            .toLowerCase(Locale.ROOT);
        List<HqData.Note> out = new ArrayList<>();
        for (HqData.Note n : ClientHq.notes) {
            if (!n.board.isEmpty() && !ClientHq.onBoard(binding, n.board)) continue;
            if (!KINDS[tabs.selected].isEmpty() && !KINDS[tabs.selected].equals(n.kind)) continue;
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

    static int kindColor(String k) {
        switch (k) {
            case "plan":
                return 0x2FA3A0;
            case "handoff":
                return 0xC9A227;
            case "review":
                return 0x7DA2F0;
            case "summary":
                return 0x8FA98B;
            case "decision":
                return 0xD97757;
            default:
                return 0x9C9488;
        }
    }

    private static String kindName(String k) {
        switch (k) {
            case "summary":
                return "done";
            case "overview":
                return "board";
            default:
                return k.isEmpty() ? "note" : k;
        }
    }

    @Override
    public void drawScreen(int mx, int my, float partial) {
        drawRect(0, 0, width, height, 0xE8000000 | (th.bg & 0xFFFFFF));
        layout();
        UiFont reg = UiFont.regular(), bold = UiFont.bold(), heavy = UiFont.heavy();
        float pad = 8;
        float tw = heavy.draw("Agent library", pad, pad, Widgets.H1, 0xFF000000 | th.text);
        reg.draw(BoardView.scopeLabel(binding) + "  \u00b7  read-only, filtered by the adapter", pad + tw + 8, pad + 3, Widgets.BODY, 0xFF000000 | th.muted);
        if (!ClientAgentCache.linkUp) Ui.pillRight(reg, "adapter offline", width - pad, pad + 1, Widgets.SMALL, th.danger);
        search.draw(th);
        tabs.draw(th, mx, my);

        List<HqData.Note> notes = visible();
        reg.draw(notes.size() + " of " + ClientHq.notes.size() + " notes", list.x0 + 2, list.y0 - 10, Widgets.SMALL, 0xFF000000 | th.muted);
        HqData.Note sel = null;
        for (HqData.Note n : notes) if (n.id.equals(selectedId)) sel = n;
        if (sel == null && !notes.isEmpty()) {
            sel = notes.get(0);
            selectedId = sel.id;
            reader.offset = 0;
        }

        rows.clear();
        float cw = list.x1 - list.x0 - 6, y = 0;
        for (HqData.Note n : notes) {
            int lines = Math.max(1, TextLayout.wrap(n.title, cw - 12, 2, bold.measure(Widgets.BODY)).size());
            float h = 5 + lines * bold.lineHeight(Widgets.BODY) + reg.lineHeight(Widgets.SMALL) + 4;
            rows.add(new Object[] { n, y, y + h });
            y += h + 3;
        }
        list.setContent(y);
        Widgets.clip(this, list.x0, list.y0, list.x1, list.y1);
        HqData.Note hover = null;
        for (Object[] r : rows) {
            HqData.Note n = (HqData.Note) r[0];
            float y0 = list.y0 + (Float) r[1] - list.offset, y1 = list.y0 + (Float) r[2] - list.offset;
            if (y1 < list.y0 || y0 > list.y1) continue;
            boolean h = Widgets.inside(mx, my, list.x0, Math.max(y0, list.y0), list.x0 + cw, Math.min(y1, list.y1));
            if (h) hover = n;
            boolean isSel = n == sel;
            int bg = isSel ? th.cardSelected : h ? Theme.mix(th.card, 0xFFFFFF, 0.5F) : th.card;
            float x0 = list.x0, x1 = list.x0 + cw;
            Ui.round(x0, y0, x1, y1, 3, 0xFF000000 | bg);
            if (isSel) Ui.roundOutline(x0, y0, x1, y1, 3, 1.2, 0xFF000000 | th.accent);
            int kc = kindColor(n.kind);
            Ui.round(x0, y0, x0 + 6, y1, 3, 0xFF000000 | kc);
            Ui.rect(x0 + 3, y0, x0 + 6, y1, 0xFF000000 | bg);
            float tx = x0 + 8, ty = y0 + 4;
            for (String l : TextLayout.wrap(n.title, x1 - tx - 4, 2, bold.measure(Widgets.BODY))) {
                bold.draw(l, tx, ty, Widgets.BODY, 0xFF000000 | th.cardText);
                ty += bold.lineHeight(Widgets.BODY);
            }
            float my2 = y1 - reg.lineHeight(Widgets.SMALL) - 3;
            String meta = kindName(n.kind) + "  \u00b7  " + age(n.updated);
            float mw = reg.drawRight(meta, x1 - 4, my2, Widgets.SMALL, 0xFF000000 | th.cardMuted);
            String who = n.author.isEmpty() ? n.scope : BoardView.agentName(n.author);
            int wc = n.author.isEmpty() ? th.cardMuted : Theme.readable(BoardView.agentColor(n.author), bg);
            (n.author.isEmpty() ? reg : bold).drawFit(who, tx, my2, Widgets.SMALL, x1 - 8 - mw - tx, 0xFF000000 | wc);
        }
        Widgets.unclip();
        list.drawBar(th);
        if (notes.isEmpty()) reg.draw(ClientHq.haveLibrary ? "No notes match." : "Waiting for the library\u2026", list.x0 + 4, list.y0 + 4, Widgets.BODY, 0xFF000000 | th.muted);

        Ui.round(reader.x0, reader.y0, reader.x1, reader.y1, 4, 0xFF000000 | th.surface);
        if (sel != null) drawReader(sel);
        if (hover != null) {
            List<String> tip = new ArrayList<>();
            tip.add(hover.title);
            Widgets.tooltip(th, tip, mx, my, width, height);
        }
    }

    private void drawReader(HqData.Note n) {
        UiFont reg = UiFont.regular(), bold = UiFont.bold();
        float pad = 8, x = reader.x0 + pad, w = reader.x1 - reader.x0 - 2 * pad - 4;
        List<Object[]> lines = new ArrayList<>(); // [font, size, colour, text]
        for (String l : TextLayout.wrap(n.title, w, 0, bold.measure(Widgets.TITLE))) lines.add(new Object[] { bold, Widgets.TITLE, th.text, l });
        String who = n.author.isEmpty() ? n.scope : BoardView.agentName(n.author) + " (" + n.author + ")";
        String meta = kindName(n.kind) + "  \u00b7  " + who + (n.board.isEmpty() ? "" : "  \u00b7  board " + n.board)
            + (n.taskId.isEmpty() ? "" : "  \u00b7  " + n.taskId)
            + "  \u00b7  " + age(n.updated) + " ago";
        for (String l : TextLayout.wrap(meta, w, 0, reg.measure(Widgets.SMALL + 0.4F))) lines.add(new Object[] { reg, Widgets.SMALL + 0.4F, th.muted, l });
        lines.add(new Object[] { reg, 5F, 0, "" });
        for (String para : n.body.split("\n")) {
            if (para.trim()
                .isEmpty()) {
                lines.add(new Object[] { reg, 4F, 0, "" });
                continue;
            }
            boolean heading = para.endsWith(":") || para.matches("^(PLAN|HANDOFF|PASS|REVISE|VERIFICATION|PROGRESS)\\b.*");
            UiFont f = heading ? bold : reg;
            int c = heading ? Theme.readable(0xD9B23A, th.surface) : th.text;
            for (String l : TextLayout.wrap(para, w, 0, f.measure(Widgets.BODY))) lines.add(new Object[] { f, Widgets.BODY, c, l });
        }
        float total = 0;
        for (Object[] l : lines) total += ((UiFont) l[0]).lineHeight((Float) l[1]);
        reader.setContent(total + 2 * pad);
        Widgets.clip(this, reader.x0, reader.y0, reader.x1, reader.y1);
        float y = reader.y0 + pad - reader.offset;
        for (Object[] l : lines) {
            float h = ((UiFont) l[0]).lineHeight((Float) l[1]);
            if (y + h >= reader.y0 && y <= reader.y1) ((UiFont) l[0]).draw((String) l[3], x, y, (Float) l[1], 0xFF000000 | (Integer) l[2]);
            y += h;
        }
        Widgets.unclip();
        reader.drawBar(th);
    }

    @Override
    public void updateScreen() {
        search.tick();
    }

    @Override
    public void handleMouseInput() {
        super.handleMouseInput();
        int d = Mouse.getEventDWheel();
        if (d == 0) return;
        int mx = Mouse.getEventX() * width / mc.displayWidth;
        if (mx >= reader.x0) reader.wheel(d, 18);
        else list.wheel(d, 18);
    }

    @Override
    protected void mouseClicked(int mx, int my, int button) {
        super.mouseClicked(mx, my, button);
        search.click(mx, my);
        if (tabs.click(mx, my)) {
            list.offset = 0;
            return;
        }
        if (!list.contains(mx, my)) return;
        for (Object[] r : rows) {
            float y0 = list.y0 + (Float) r[1] - list.offset, y1 = list.y0 + (Float) r[2] - list.offset;
            if (my >= y0 && my < y1) {
                selectedId = ((HqData.Note) r[0]).id;
                reader.offset = 0;
                return;
            }
        }
    }

    @Override
    protected void keyTyped(char ch, int key) {
        if (key == Keyboard.KEY_ESCAPE) {
            mc.displayGuiScreen(null);
            return;
        }
        if (search.key(ch, key)) {
            list.offset = 0;
            return;
        }
        if (key == Keyboard.KEY_DOWN) reader.wheel(-1, 10);
        if (key == Keyboard.KEY_UP) reader.wheel(1, 10);
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
