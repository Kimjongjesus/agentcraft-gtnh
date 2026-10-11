package dev.agentcraft.gtnh.write.client;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.client.gui.GuiScreen;

import org.lwjgl.input.Keyboard;

import dev.agentcraft.gtnh.state.HqData;
import dev.agentcraft.gtnh.ui.UiFont;
import dev.agentcraft.gtnh.ui.Widgets;

/**
 * The simple card forms: create a card, edit a card (title / details / priority) or comment on one. Each
 * one only builds a {@link WriteClient} request; the server and the control service check it again.
 */
public class GuiCardForm extends WriteScreen {

    public enum Mode {
        CREATE, EDIT, COMMENT
    }

    private final Mode mode;
    private final HqData.Task task;
    private final WUi.Field title = new WUi.Field("field.title", "Title", 120);
    private final WUi.Field details = new WUi.Field("field.details", "Details (optional)", 4000);
    private final WUi.Field priority = new WUi.Field("field.priority", "0-100", 3);
    private final WUi.Field comment = new WUi.Field("field.comment", "Write a comment", 2000);
    private final List<WUi.Field> order = new ArrayList<WUi.Field>();
    private String board;
    private String error = "";
    private boolean waiting;
    private long submittedAt;

    public GuiCardForm(GuiScreen parent, Mode mode, HqData.Task task) {
        super(parent);
        this.mode = mode;
        this.task = task;
        details.multi = true;
        comment.multi = true;
        priority.digits = true;
        board = task == null ? null : FormLogic.pickBoard(task.board, ClientWriteState.policy.boards);
        if (board == null && !ClientWriteState.policy.boards.isEmpty()) board = ClientWriteState.policy.boards.get(0);
        if (mode == Mode.EDIT && task != null) {
            title.text = task.title;
            details.text = task.description;
            priority.text = task.priority > 0 ? String.valueOf(task.priority) : "";
        }
        if (mode == Mode.COMMENT) {
            comment.focused = true;
            order.add(comment);
        } else {
            title.focused = true;
            order.add(title);
            order.add(details);
            order.add(priority);
        }
    }

    @Override
    String title() {
        return mode == Mode.CREATE ? "New card" : mode == Mode.EDIT ? "Edit card" : "Comment on a card";
    }

    @Override
    int panelWidth() {
        return 320;
    }

    @Override
    int panelHeight() {
        return mode == Mode.COMMENT ? 210 : 250;
    }

    private String cap() {
        return mode == Mode.CREATE ? "card.create" : "card.edit";
    }

    private String blocked() {
        if (!ClientWriteState.canWrite()) return WUi.stateText();
        if (!ClientWriteState.policy.usable(cap())) return "The policy does not offer or has switched off " + WUi.label(cap()) + ".";
        if (mode == Mode.CREATE && board == null) return "The policy offers no board.";
        return null;
    }

    @Override
    void tick() {
        for (WUi.Field f : order) f.tick();
        if (waiting) {
            for (ClientWriteState.ResultInfo r : ClientWriteState.recent(4)) {
                if (r.atMs < submittedAt || !cap().equals(r.capability)) continue;
                waiting = false;
                if ("applied".equals(r.status) || "queued".equals(r.status)) close();
                else error = FormLogic.resultLine(r.status, r.error, r.dryRun);
                return;
            }
            if (System.currentTimeMillis() - submittedAt > 100000L) {
                waiting = false;
                error = "No answer yet: check outside the game before trying again.";
            }
        }
    }

    private void submit() {
        error = "";
        String b = blocked();
        if (b != null) {
            error = b;
            return;
        }
        boolean sent;
        if (mode == Mode.CREATE) {
            String e = FormLogic.createError(board, title.text, details.text, priority.text);
            if (e != null) {
                error = e;
                return;
            }
            int p = FormLogic.parsePriority(priority.text);
            String body = details.text.trim();
            sent = WriteClient.createCard(board, title.text.trim(), body.isEmpty() ? null : body, p == Integer.MIN_VALUE ? null : Integer.valueOf(p));
        } else if (mode == Mode.EDIT) {
            FormLogic.Edit e = FormLogic.edit(task.title, task.description, task.priority, title.text, details.text, priority.text);
            if (e.error != null) {
                error = e.error;
                return;
            }
            sent = WriteClient.editCard(FormLogic.cardId(task.id, task.board), e.title, e.body, e.priority);
        } else {
            String e = FormLogic.commentError(comment.text);
            if (e != null) {
                error = e;
                return;
            }
            sent = WriteClient.comment(FormLogic.cardId(task.id, task.board), comment.text.trim());
        }
        if (sent) {
            waiting = true;
            submittedAt = System.currentTimeMillis();
        }
    }

    private void layoutField(WUi.Field f, float x, float y, float w, float h) {
        f.at(x, y, w, h);
        f.draw();
    }

    @Override
    void body(int mx, int my) {
        UiFont reg = UiFont.regular(), bold = UiFont.bold();
        float x = bx0, w = bx1 - bx0, y = by0;
        if (mode != Mode.CREATE && task != null) {
            reg.draw(FormLogic.cardId(task.id, task.board) + (task.board.isEmpty() ? "" : "  \u00b7  board " + task.board), x, y, Widgets.SMALL + 0.4F, 0xFF000000 | WUi.TH.muted);
            y += reg.lineHeight(Widgets.SMALL + 0.4F) + 1;
            if (mode == Mode.COMMENT) y = para(task.title, x, y, w, Widgets.TITLE, bold, WUi.TH.text, 2) + 4;
        }
        if (mode == Mode.CREATE) {
            bold.draw("Board", x, y + 2, Widgets.SMALL + 0.6F, 0xFF000000 | WUi.TH.muted);
            float bx = x + 34;
            for (final String bd : ClientWriteState.policy.boards) {
                float bw = WUi.Btn.widthFor(bd, 13) + 4;
                WUi.Btn b = new WUi.Btn("board." + bd, bd).style(bd.equals(board) ? WUi.PRIMARY : WUi.NORMAL).on(new Runnable() {

                    @Override
                    public void run() {
                        board = bd;
                    }
                });
                b.at(bx, y, bw, 13);
                btns.add(b);
                bx += bw + 3;
            }
            y += 17;
        }
        if (mode == Mode.COMMENT) {
            layoutField(comment, x, y, w, by1 - y - 44);
        } else {
            bold.draw("Title", x, y, Widgets.SMALL + 0.6F, 0xFF000000 | WUi.TH.muted);
            y += reg.lineHeight(Widgets.SMALL + 0.6F);
            layoutField(title, x, y, w, 15);
            y += 19;
            bold.draw("Details", x, y, Widgets.SMALL + 0.6F, 0xFF000000 | WUi.TH.muted);
            y += reg.lineHeight(Widgets.SMALL + 0.6F);
            layoutField(details, x, y, w, 52);
            y += 56;
            bold.draw("Priority", x, y + 3, Widgets.SMALL + 0.6F, 0xFF000000 | WUi.TH.muted);
            layoutField(priority, x + 40, y, 40, 15);
        }
        // message, result and buttons
        float my2 = by1 - 36;
        String b = blocked();
        if (!error.isEmpty()) reg.drawFit(error, x, my2, Widgets.SMALL + 0.6F, w, 0xFF000000 | WUi.RED);
        else if (b != null) reg.drawFit(b, x, my2, Widgets.SMALL + 0.6F, w, 0xFF000000 | WUi.RED);
        else if (waiting) reg.draw("Sent. Waiting for the answer\u2026", x, my2, Widgets.SMALL + 0.6F, 0xFF000000 | WUi.TH.muted);
        else resultLine(x, my2, w);
        String label = mode == Mode.CREATE ? "Create card" : mode == Mode.EDIT ? "Save changes" : "Post comment";
        WUi.Btn go = new WUi.Btn("submit", label).style(WUi.PRIMARY).on(new Runnable() {

            @Override
            public void run() {
                submit();
            }
        });
        go.enable(!waiting && b == null, b);
        WUi.Btn cancel = new WUi.Btn("cancel", "Cancel").on(new Runnable() {

            @Override
            public void run() {
                close();
            }
        });
        float gw = WUi.Btn.widthFor(label, 16) + 14, cw = WUi.Btn.widthFor("Cancel", 16) + 14;
        go.at(bx1 - gw, by1 - 18, gw, 16);
        cancel.at(bx1 - gw - 4 - cw, by1 - 18, cw, 16);
        btns.add(go);
        btns.add(cancel);
    }

    @Override
    void clicked(int mx, int my) {
        for (WUi.Field f : order) f.click(mx, my);
    }

    @Override
    void typed(char ch, int key) {
        if (key == Keyboard.KEY_TAB) {
            int at = -1;
            for (int i = 0; i < order.size(); i++) if (order.get(i).focused) at = i;
            for (WUi.Field f : order) f.focused = false;
            order.get((at + 1) % order.size()).focused = true;
            return;
        }
        WUi.Field focus = null;
        for (WUi.Field f : order) if (f.focused) focus = f;
        if (focus == null) return;
        if ((key == Keyboard.KEY_RETURN || key == Keyboard.KEY_NUMPADENTER) && !focus.multi) {
            submit();
            return;
        }
        focus.key(ch, key);
        error = "";
    }
}
