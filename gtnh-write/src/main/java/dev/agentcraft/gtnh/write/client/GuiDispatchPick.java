package dev.agentcraft.gtnh.write.client;

import java.util.List;

import net.minecraft.client.gui.GuiScreen;

import dev.agentcraft.gtnh.state.HqData;
import dev.agentcraft.gtnh.ui.UiFont;
import dev.agentcraft.gtnh.ui.Widgets;

/**
 * Dispatch entry: the player picked a card on the task wall and pressed Dispatch; this screen lists the
 * builder profiles the control service offers. Choosing one only sends {@code card.dispatch}; the Confirm
 * screen follows when the control service's prompt arrives. Nothing starts here.
 */
public class GuiDispatchPick extends WriteScreen {

    private final HqData.Task task;
    private final String board;

    public GuiDispatchPick(GuiScreen parent, HqData.Task task) {
        super(parent);
        this.task = task;
        this.board = FormLogic.pickBoard(task.board, ClientWriteState.policy.boards);
    }

    @Override
    String title() {
        return "Dispatch a card";
    }

    @Override
    int panelWidth() {
        return 280;
    }

    @Override
    int panelHeight() {
        return 190;
    }

    @Override
    void body(int mx, int my) {
        UiFont reg = UiFont.regular(), bold = UiFont.bold();
        float x = bx0, w = bx1 - bx0, y = by0;
        String id = FormLogic.cardId(task.id, task.board);
        reg.draw(id + (board == null ? "" : "  \u00b7  board " + board), x, y, Widgets.SMALL + 0.4F, 0xFF000000 | WUi.TH.muted);
        y += reg.lineHeight(Widgets.SMALL + 0.4F) + 1;
        y = para(task.title, x, y, w, Widgets.TITLE, bold, WUi.TH.text, 2) + 4;
        y = para("Pick the builder profile that should take this card. You will see a Confirm screen next; nothing starts before you press Confirm there.", x, y, w, Widgets.SMALL + 0.6F, reg, WUi.TH.muted, 3) + 4;
        List<String> profiles = ClientWriteState.policy.profiles;
        String why = null;
        if (!ClientWriteState.canWrite()) why = WUi.stateText();
        else if (!ClientWriteState.policy.usable("card.dispatch")) why = "Dispatch is not offered or is switched off in the policy.";
        else if (board == null) why = "This card's board is not one the policy offers.";
        else if (profiles.isEmpty()) why = "The policy offers no builder profile.";
        if (why != null) {
            y = para(why, x, y, w, Widgets.BODY, bold, WUi.RED, 3) + 3;
        }
        float bx = x, by = y;
        float h = 16;
        for (final String p : profiles) {
            float bw = WUi.Btn.widthFor(p, h) + 10;
            if (bx + bw > bx1 && bx > x) {
                bx = x;
                by += h + 4;
            }
            WUi.Btn b = new WUi.Btn("profile." + p, p).style(WUi.PRIMARY).on(new Runnable() {

                @Override
                public void run() {
                    pick(p);
                }
            });
            b.enable(why == null, why);
            b.at(bx, by, bw, h);
            btns.add(b);
            bx += bw + 4;
        }
        float ry = by1 - 30;
        resultLine(x, ry, w);
        WUi.Btn cancel = new WUi.Btn("cancel", "Cancel").on(new Runnable() {

            @Override
            public void run() {
                close();
            }
        });
        float cw = WUi.Btn.widthFor("Cancel", 16) + 14;
        cancel.at(bx1 - cw, by1 - 16, cw, 16);
        btns.add(cancel);
    }

    private void pick(String profile) {
        if (board == null) return;
        if (WriteClient.requestDispatch(FormLogic.cardId(task.id, task.board), board, profile)) close();
    }
}
