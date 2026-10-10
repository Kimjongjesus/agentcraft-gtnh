package dev.agentcraft.gtnh.write.client;

import java.util.List;
import java.util.Map;

import net.minecraft.client.gui.GuiScreen;

import dev.agentcraft.gtnh.ui.Theme;
import dev.agentcraft.gtnh.ui.Ui;
import dev.agentcraft.gtnh.ui.UiFont;
import dev.agentcraft.gtnh.ui.Widgets;

/**
 * The Confirm screen of {@code card.dispatch}, the only two-step write. It is opened by
 * {@link WriteTicker} when the control service's prompt arrives (the server sends it to the requesting
 * player only). It shows what will run (card, title, board, builder profile, model, the first lines of the
 * body), a 60 s countdown, Confirm and Cancel, and a DRY RUN badge when the control service runs in dry-run
 * mode. It closes itself when the time is up, when writes are locked or disarmed, when the control link is
 * lost, or when the server drops the prompt. Closing it any other way cancels the token. After Confirm it
 * waits for the result and shows it (applied / refused / unknown).
 */
public class GuiDispatchConfirm extends WriteScreen {

    private static final long WINDOW_MS = 60_000L;
    private static final long RESULT_WAIT_MS = 100_000L;

    private enum Phase {
        CONFIRM, SENT, RESULT
    }

    private final ClientWriteState.PromptInfo prompt;
    private Phase phase = Phase.CONFIRM;
    private long sentAt;
    private ClientWriteState.ResultInfo result;
    private String closedWhy = "";
    private boolean cancelOnClose = true;

    public GuiDispatchConfirm(ClientWriteState.PromptInfo prompt, GuiScreen parent) {
        super(parent);
        this.prompt = prompt;
    }

    /** The token this screen was opened for (the ticker does not reopen a screen for a token it already showed). */
    String token() {
        return prompt.token;
    }

    @Override
    String title() {
        return phase == Phase.RESULT ? "Dispatch result" : "Confirm dispatch";
    }

    @Override
    String note() {
        return null;
    }

    @Override
    int panelWidth() {
        return 300;
    }

    @Override
    int panelHeight() {
        return 218;
    }

    private void confirm() {
        if (phase != Phase.CONFIRM) return;
        phase = Phase.SENT;
        sentAt = System.currentTimeMillis();
        cancelOnClose = false;
        WriteClient.confirm(prompt.token);
    }

    private void cancel() {
        if (phase == Phase.CONFIRM) {
            cancelOnClose = false;
            WriteClient.cancel(prompt.token);
        }
        close();
    }

    @Override
    void close() {
        mc.displayGuiScreen(parent);
    }

    @Override
    public void onGuiClosed() {
        super.onGuiClosed();
        // closing the screen any other way than the buttons must not leave a live token behind
        if (cancelOnClose && phase == Phase.CONFIRM && ClientWriteState.pendingPrompt() != null) WriteClient.cancel(prompt.token);
    }

    @Override
    void tick() {
        if (phase == Phase.CONFIRM) {
            String why = null;
            if (ClientWriteState.pendingPrompt() == null || !ClientWriteState.pendingPrompt().token.equals(prompt.token)) why = prompt.msLeft() <= 0 ? "expired" : "the server closed the request";
            else if (ClientWriteState.locked) why = "writes are locked";
            else if (!ClientWriteState.armed) why = "writes are disarmed";
            else if (!ClientWriteState.linkUp) why = "the control link is down";
            if (why != null) {
                closedWhy = why;
                cancelOnClose = false;
                ClientWriteState.localRefusal("card.dispatch", "the Confirm screen closed: " + why);
                close();
            }
        } else if (phase == Phase.SENT) {
            ClientWriteState.ResultInfo r = ClientWriteState.resultFor(prompt.requestId);
            if (r != null && r.atMs >= sentAt && !"prompted".equals(r.status)) {
                result = r;
                phase = Phase.RESULT;
            } else if (System.currentTimeMillis() - sentAt > RESULT_WAIT_MS) {
                phase = Phase.RESULT; // no answer: shown as unknown, never retried from here
            }
        }
    }

    @Override
    void body(int mx, int my) {
        UiFont reg = UiFont.regular(), bold = UiFont.bold(), heavy = UiFont.heavy();
        Map<String, String> s = prompt.summary;
        float x = bx0, w = bx1 - bx0, y = by0;
        if (ClientWriteState.dryRun) {
            Ui.pillRight(bold, "DRY RUN", px1 - 8 - 2, py0 + 2, Widgets.SMALL + 0.6F, WUi.AMBER);
        }
        if (phase == Phase.RESULT) {
            drawResult(x, y, w, mx, my);
            return;
        }
        reg.draw("This will start work on the card below. Nothing runs until you press Confirm.", x, y, Widgets.SMALL + 0.4F, 0xFF000000 | WUi.TH.muted);
        y += reg.lineHeight(Widgets.SMALL + 0.4F) + 3;
        // the card
        float cardTop = y;
        float cardH = 100;
        Ui.round(x, cardTop, bx1, cardTop + cardH, 4, 0xFF000000 | WUi.TH.surface);
        float cx = x + 6, cw = w - 12, cy = cardTop + 5;
        String id = s.get("card") == null ? "" : s.get("card");
        reg.draw(id, cx, cy, Widgets.SMALL + 0.4F, 0xFF000000 | WUi.TH.muted);
        cy += reg.lineHeight(Widgets.SMALL + 0.4F);
        cy = para(s.get("title") == null ? "" : s.get("title"), cx, cy, cw, Widgets.TITLE, bold, WUi.TH.text, 2) + 3;
        String[][] facts = { { "Board", s.get("board") }, { "Builder", s.get("profile") }, { "Model", s.get("model") == null || s.get("model").isEmpty() ? "default" : s.get("model") } };
        for (String[] f : facts) {
            bold.draw(f[0], cx, cy, Widgets.BODY, 0xFF000000 | WUi.TH.muted);
            reg.drawFit(f[1] == null ? "-" : f[1], cx + 42, cy, Widgets.BODY, cw - 42, 0xFF000000 | WUi.TH.text);
            cy += reg.lineHeight(Widgets.BODY);
        }
        cy += 3;
        List<String> body = FormLogic.firstLines(s.get("body"), 3);
        for (String l : body) {
            if (cy + reg.lineHeight(Widgets.SMALL + 0.4F) > cardTop + cardH - 3) break;
            cy = para(l, cx, cy, cw, Widgets.SMALL + 0.4F, reg, WUi.TH.muted, 1);
        }
        y = cardTop + cardH + 6;
        // countdown
        long left = prompt.msLeft();
        int secs = FormLogic.secondsLeft(left);
        float frac = FormLogic.fractionLeft(left, WINDOW_MS);
        int col = secs <= 10 ? WUi.RED : secs <= 20 ? WUi.AMBER : WUi.GREEN;
        float rcx = x + 14, rcy = y + 14;
        Ui.ring(rcx, rcy, 10, 13, 0, 1, 0xFF000000 | WUi.TH.line);
        if (phase == Phase.CONFIRM && frac > 0) Ui.ring(rcx, rcy, 10, 13, 0, frac, 0xFF000000 | col);
        heavy.drawCentered(String.valueOf(secs), rcx, rcy - heavy.lineHeight(Widgets.BODY) / 2 + 0.5F, Widgets.BODY, 0xFF000000 | WUi.TH.text);
        if (phase == Phase.CONFIRM) {
            bold.draw("Expires in " + secs + " s", x + 34, y + 4, Widgets.BODY, 0xFF000000 | Theme.readable(col, WUi.TH.bg));
            reg.draw("The request closes by itself, and on lock or disarm.", x + 34, y + 4 + bold.lineHeight(Widgets.BODY), Widgets.SMALL, 0xFF000000 | WUi.TH.muted);
        } else {
            bold.draw("Sent. Waiting for the result\u2026", x + 34, y + 4, Widgets.BODY, 0xFF000000 | WUi.TH.text);
            reg.draw("Your confirmation was sent once; it cannot be reused.", x + 34, y + 4 + bold.lineHeight(Widgets.BODY), Widgets.SMALL, 0xFF000000 | WUi.TH.muted);
        }
        // buttons
        float by = by1 - 16;
        WUi.Btn ok = new WUi.Btn("confirm", "Confirm").style(WUi.PRIMARY).on(new Runnable() {

            @Override
            public void run() {
                confirm();
            }
        });
        ok.enable(phase == Phase.CONFIRM && ClientWriteState.canWrite(), "Writes are not armed.");
        WUi.Btn no = new WUi.Btn("cancel", "Cancel").on(new Runnable() {

            @Override
            public void run() {
                cancel();
            }
        });
        no.enable(phase == Phase.CONFIRM, null);
        float bw = WUi.Btn.widthFor("Confirm", 16) + 14;
        ok.at(bx1 - bw, by, bw, 16);
        no.at(bx1 - bw - 4 - (WUi.Btn.widthFor("Cancel", 16) + 14), by, WUi.Btn.widthFor("Cancel", 16) + 14, 16);
        btns.add(ok);
        btns.add(no);
        float ry = by - reg.lineHeight(Widgets.SMALL + 0.6F) - 2;
        resultLine(x, ry, w);
    }

    private void drawResult(float x, float y, float w, int mx, int my) {
        UiFont reg = UiFont.regular(), bold = UiFont.bold(), heavy = UiFont.heavy();
        ClientWriteState.ResultInfo r = result;
        String status = r == null ? "unknown" : r.status;
        boolean ok = "applied".equals(status) || "queued".equals(status);
        int col = ok ? WUi.GREEN : "unknown".equals(status) ? WUi.AMBER : WUi.RED;
        Map<String, String> s = prompt.summary;
        Ui.round(x, y, bx1, y + 112, 4, 0xFF000000 | WUi.TH.surface);
        float cx = x + 8, cy = y + 8;
        Ui.dot(cx + 6, cy + 7, 6, 0xFF000000 | col);
        String head = ok ? "Applied" : "unknown".equals(status) ? "Unknown" : "cancelled".equals(status) ? "Cancelled" : "Refused";
        heavy.draw(head, cx + 18, cy, Widgets.H1, 0xFF000000 | Theme.readable(col, WUi.TH.surface));
        cy += heavy.lineHeight(Widgets.H1) + 4;
        boolean dry = r != null && r.dryRun;
        if (dry) {
            float pw = Ui.pill(bold, "DRY RUN", cx, cy, Widgets.SMALL + 0.6F, WUi.AMBER);
            reg.draw("nothing ran: the control service only recorded the call", cx + pw + 5, cy + 1, Widgets.SMALL + 0.6F, 0xFF000000 | WUi.TH.muted);
            cy += Ui.pillHeight(bold, Widgets.SMALL + 0.6F) + 5;
        }
        String line = r == null ? "No answer from the control service yet. Check outside the game; this screen will not retry." : FormLogic.resultLine(r.status, r.error, r.dryRun);
        cy = para(line, cx, cy, w - 16, Widgets.BODY, reg, WUi.TH.text, 3) + 3;
        cy = para("Card " + (s.get("card") == null ? "" : s.get("card")) + "  " + (s.get("title") == null ? "" : s.get("title")), cx, cy, w - 16, Widgets.BODY, bold, WUi.TH.text, 2) + 2;
        cy = para("Builder " + (s.get("profile") == null ? "-" : s.get("profile")) + "  \u00b7  board " + (s.get("board") == null ? "-" : s.get("board")), cx, cy, w - 16, Widgets.SMALL + 0.6F, reg, WUi.TH.muted, 1) + 2;
        if (r != null && !r.audit.isEmpty()) reg.draw("audit " + r.audit, cx, cy, Widgets.SMALL + 0.6F, 0xFF000000 | WUi.TH.muted);
        WUi.Btn done = new WUi.Btn("close", "Close").style(WUi.PRIMARY).on(new Runnable() {

            @Override
            public void run() {
                close();
            }
        });
        float bw = WUi.Btn.widthFor("Close", 16) + 14;
        done.at(bx1 - bw, by1 - 16, bw, 16);
        btns.add(done);
    }

    @Override
    protected void keyTyped(char ch, int key) {
        if (key == org.lwjgl.input.Keyboard.KEY_ESCAPE) {
            if (phase == Phase.CONFIRM) cancel();
            else close();
            return;
        }
        if (phase == Phase.CONFIRM && (key == org.lwjgl.input.Keyboard.KEY_RETURN) && ClientWriteState.canWrite()) confirm();
    }

    String closedWhy() {
        return closedWhy;
    }
}
