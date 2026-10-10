package dev.agentcraft.gtnh.write.client;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiScreen;

import org.lwjgl.input.Keyboard;

import dev.agentcraft.gtnh.api.Extensions;
import dev.agentcraft.gtnh.ops.DecisionData;
import dev.agentcraft.gtnh.state.HqData;
import dev.agentcraft.gtnh.ui.UiFont;
import dev.agentcraft.gtnh.ui.Widgets;

/**
 * The strip under the detail pane of the core's decision screen and task wall: the write status with the
 * one-click Lock button, a Write actions button, and the controls that fit the selected thing:
 * <ul>
 * <li>task wall: Dispatch (pick a builder, then a Confirm screen), Edit, Comment, New card;</li>
 * <li>decision screen: the offered choices as buttons for a plain question, a text box for an open
 * question, only Deny (+ an optional note) for a permission halt ("approve outside the game"), and nothing
 * but that sentence for hand-off and unrecognised decisions.</li>
 * </ul>
 * It only builds {@link WriteClient} requests; the server and the control service decide again.
 */
public final class WriteFooter implements Extensions.ClientHooks {

    static final float HEIGHT = 48;
    private static final float ROW = 14;

    private final WUi.Btn lock = new WUi.Btn("footer.lock", "Lock writes").on(new Runnable() {

        @Override
        public void run() {
            WriteClient.lock("locked from the game screen");
        }
    });
    private final WUi.Btn actions = new WUi.Btn("footer.actions", "Actions").tip("Write actions: restart a service, run a job, see the last results").on(new Runnable() {

        @Override
        public void run() {
            Minecraft.getMinecraft()
                .displayGuiScreen(new GuiWriteActions(WUi.current()));
        }
    });
    private final WUi.Field field = new WUi.Field("footer.field", "", 600);
    private final Map<String, String> typed = new HashMap<String, String>();
    private String fieldFor = "";

    private static final class Lay {

        final List<WUi.Btn> btns = new ArrayList<WUi.Btn>();
        WUi.Field field;
        /** the sentence for row C (left) */
        String hint = "";
        /** what pressing Enter in the text box does; null = nothing */
        Runnable enter;
    }

    // ---- hooks ---------------------------------------------------------------------------------------------------

    @Override
    public float footerHeight(String screen) {
        return HEIGHT;
    }

    @Override
    public String statusLine(String screen) {
        // the core screens print this next to their title: keep it short (the strip below has the full text)
        String s = ClientWriteState.statusLine();
        return s.length() > 48 ? s.substring(0, 47) + "\u2026" : s;
    }

    @Override
    public void drawFooter(String screen, Object subject, float x0, float y0, float x1, float y1, int mx, int my) {
        Lay lay = layout(screen, subject, x0, y0, x1);
        WUi.strip(x0, y0, x1, mx, my, lock, actions);
        for (WUi.Btn b : lay.btns) b.draw(mx, my);
        if (lay.field != null) {
            lay.field.tick();
            lay.field.draw();
        }
        float cy = y0 + 2 * (ROW + 3) + 1;
        UiFont reg = UiFont.regular();
        String res = WUi.recentResultLine();
        boolean both = res != null && !lay.hint.isEmpty();
        float half = both ? (x1 - x0) * 0.56F : (x1 - x0);
        if (!lay.hint.isEmpty()) reg.drawFit(lay.hint, x0 + 1, cy, Widgets.SMALL + 0.4F, half - 4, 0xFF000000 | WUi.TH.muted);
        if (res != null) {
            float rx = both ? x0 + half : x0 + 1;
            reg.drawFit(res, rx, cy, Widgets.SMALL + 0.4F, x1 - rx, 0xFF000000 | WUi.resultColor(ClientWriteState.lastResult()));
        }
        WUi.flushTip();
    }

    @Override
    public boolean footerClick(String screen, Object subject, float x0, float y0, float x1, float y1, int mx, int my, int button) {
        if (button != 0) return false;
        Lay lay = layout(screen, subject, x0, y0, x1);
        if (lock.enabled && lock.hit(mx, my)) {
            lock.action.run();
            return true;
        }
        if (actions.hit(mx, my)) {
            actions.action.run();
            return true;
        }
        for (WUi.Btn b : lay.btns) {
            if (!b.hit(mx, my)) continue;
            if (b.enabled && b.action != null) b.action.run();
            else if (b.tip != null) ClientWriteState.localRefusal(b.name, b.tip);
            return true;
        }
        if (lay.field != null) {
            boolean in = lay.field.click(mx, my);
            return in;
        }
        return true; // a click in the strip is never passed on to the list behind it
    }

    @Override
    public boolean footerKey(String screen, Object subject, char ch, int key) {
        if (!field.focused || !"decisions".equals(screen) || !(subject instanceof DecisionData.Decision)) return false;
        DecisionData.Decision d = (DecisionData.Decision) subject;
        if (!d.id.equals(fieldFor)) return false; // the box belongs to another decision
        if (key == Keyboard.KEY_ESCAPE) {
            field.focused = false;
            return true;
        }
        if ((key == Keyboard.KEY_RETURN || key == Keyboard.KEY_NUMPADENTER)) {
            Lay lay = layout(screen, subject, 0, 0, 400);
            if (lay.enter != null) lay.enter.run();
            return true;
        }
        if (field.key(ch, key)) typed.put(d.id, field.text);
        return true;
    }

    // ---- layout (one function for drawing and clicking) --------------------------------------------------------------

    private Lay layout(String screen, Object subject, float x0, float y0, float x1) {
        Lay lay = new Lay();
        float rowB = y0 + ROW + 3;
        if ("taskwall".equals(screen)) taskwall(lay, subject instanceof HqData.Task ? (HqData.Task) subject : null, x0, x1, rowB);
        else if ("decisions".equals(screen)) decisions(lay, subject instanceof DecisionData.Decision ? (DecisionData.Decision) subject : null, x0, x1, rowB);
        else lay.hint = "";
        return lay;
    }

    private static String capWhy(String cap) {
        if (!ClientWriteState.canWrite()) return WUi.stateText();
        if (!ClientWriteState.policy.capabilities.containsKey(cap)) return "The control service does not offer " + WUi.label(cap) + ".";
        if (!ClientWriteState.policy.usable(cap)) return WUi.label(cap) + " is switched off in the policy.";
        return null;
    }

    private void taskwall(Lay lay, final HqData.Task t, float x0, float x1, float y) {
        final GuiScreen cur = WUi.current();
        String dWhy = capWhy("card.dispatch");
        if (dWhy == null) {
            if (t == null) dWhy = "Select a card first.";
            else if (HqData.column(t.status) != 0 || !t.assignee.isEmpty()) dWhy = "Only an unassigned card in To do can be dispatched.";
            else if (FormLogic.pickBoard(t.board, ClientWriteState.policy.boards) == null) dWhy = "This card's board is not one the policy offers.";
            else if (ClientWriteState.policy.profiles.isEmpty()) dWhy = "The policy offers no builder profile.";
        }
        WUi.Btn dispatch = new WUi.Btn("footer.dispatch", "Dispatch\u2026").style(WUi.PRIMARY).on(new Runnable() {

            @Override
            public void run() {
                Minecraft.getMinecraft()
                    .displayGuiScreen(new GuiDispatchPick(cur, t));
            }
        });
        dispatch.enable(dWhy == null, dWhy);
        if (dWhy == null) dispatch.tip("Pick a builder, then confirm. Nothing starts before the Confirm screen.");
        String eWhy = capWhy("card.edit");
        if (eWhy == null && t == null) eWhy = "Select a card first.";
        WUi.Btn edit = new WUi.Btn("footer.edit", "Edit").on(new Runnable() {

            @Override
            public void run() {
                Minecraft.getMinecraft()
                    .displayGuiScreen(new GuiCardForm(cur, GuiCardForm.Mode.EDIT, t));
            }
        });
        edit.enable(eWhy == null, eWhy);
        WUi.Btn comment = new WUi.Btn("footer.comment", "Comment").on(new Runnable() {

            @Override
            public void run() {
                Minecraft.getMinecraft()
                    .displayGuiScreen(new GuiCardForm(cur, GuiCardForm.Mode.COMMENT, t));
            }
        });
        comment.enable(eWhy == null, eWhy);
        String nWhy = capWhy("card.create");
        if (nWhy == null && ClientWriteState.policy.boards.isEmpty()) nWhy = "The policy offers no board.";
        WUi.Btn create = new WUi.Btn("footer.new", "New card").on(new Runnable() {

            @Override
            public void run() {
                Minecraft.getMinecraft()
                    .displayGuiScreen(new GuiCardForm(cur, GuiCardForm.Mode.CREATE, t));
            }
        });
        create.enable(nWhy == null, nWhy);
        lay.btns.add(dispatch);
        lay.btns.add(edit);
        lay.btns.add(comment);
        lay.btns.add(create);
        WUi.row(lay.btns, x0, y, ROW, 4);
        List<String> profiles = ClientWriteState.policy.profiles;
        lay.hint = ClientWriteState.canWrite() ? "Builders: " + (profiles.isEmpty() ? "none offered" : join(profiles)) + ". Dispatch asks you to confirm." : "";
    }

    private void decisions(Lay lay, final DecisionData.Decision d, float x0, float x1, float y) {
        if (d == null) {
            lay.hint = "Select a decision.";
            return;
        }
        if (!d.id.equals(fieldFor)) {
            fieldFor = d.id;
            field.text = typed.containsKey(d.id) ? typed.get(d.id) : "";
            field.focused = false;
        }
        DecisionKind.Kind kind = DecisionKind.classify(d.kind, d.question, d.options);
        String why = capWhy("decision.answer");
        if (kind == DecisionKind.Kind.QUESTION && !d.options.isEmpty()) {
            float n = d.options.size();
            float gap = 4, avail = x1 - x0;
            float natural = 0;
            for (String o : d.options) natural += WUi.Btn.widthFor(o, ROW) + gap;
            float share = natural <= avail ? -1 : (avail - gap * (n - 1)) / n;
            float x = x0;
            for (final String o : d.options) {
                WUi.Btn b = new WUi.Btn("answer." + o, o).style(WUi.PRIMARY).on(new Runnable() {

                    @Override
                    public void run() {
                        if (WriteClient.answerDecision(d.taskId, d.id, o, null)) typed.remove(d.id);
                    }
                });
                b.enable(why == null && !d.taskId.isEmpty(), why == null ? "This decision names no card." : why);
                b.tip(o);
                float w = share < 0 ? WUi.Btn.widthFor(o, ROW) : share;
                b.at(x, y, w, ROW);
                x += w + gap;
                lay.btns.add(b);
            }
            lay.hint = "Pick one: it is sent at once and recorded as your answer.";
        } else if (kind == DecisionKind.Kind.QUESTION) {
            final WUi.Btn send = new WUi.Btn("answer.send", "Send answer").style(WUi.PRIMARY).on(new Runnable() {

                @Override
                public void run() {
                    sendText(d, null);
                }
            });
            send.enable(why == null && !d.taskId.isEmpty() && !field.text.trim().isEmpty(), why == null ? (d.taskId.isEmpty() ? "This decision names no card." : "Type an answer first.") : why);
            float sw = WUi.Btn.widthFor(send.label, ROW);
            field.placeholder = "Type your answer";
            field.at(x0, y, x1 - x0 - sw - 4, ROW);
            send.at(x1 - sw, y, sw, ROW);
            lay.btns.add(send);
            lay.field = field;
            lay.enter = new Runnable() {

                @Override
                public void run() {
                    if (send.enabled) send.action.run();
                }
            };
            lay.hint = "Open question: write the answer and press Send (or Enter).";
        } else if (kind == DecisionKind.Kind.PERMISSION && DecisionKind.offersDeny(d.options)) {
            final WUi.Btn deny = new WUi.Btn("answer.deny", "Deny").style(WUi.DANGER).on(new Runnable() {

                @Override
                public void run() {
                    sendText(d, DecisionKind.DENY);
                }
            });
            deny.enable(why == null && !d.taskId.isEmpty(), why == null ? "This decision names no card." : why);
            float dw = WUi.Btn.widthFor(deny.label, ROW) + 6;
            deny.at(x0, y, dw, ROW);
            field.placeholder = "Optional note with the denial";
            field.at(x0 + dw + 4, y, x1 - x0 - dw - 4, ROW);
            lay.btns.add(deny);
            lay.field = field;
            lay.hint = DecisionKind.readOnlyNote(kind);
        } else {
            lay.hint = DecisionKind.readOnlyNote(kind);
        }
    }

    private void sendText(DecisionData.Decision d, String choice) {
        String t = field.text.trim();
        if (choice == null && t.isEmpty()) return;
        if (WriteClient.answerDecision(d.taskId, d.id, choice, t.isEmpty() ? null : t)) {
            typed.remove(d.id);
            field.text = "";
            field.focused = false;
        }
    }

    private static String join(List<String> l) {
        StringBuilder b = new StringBuilder();
        for (String s : l) b.append(b.length() == 0 ? "" : ", ")
            .append(s);
        return b.toString();
    }
}
