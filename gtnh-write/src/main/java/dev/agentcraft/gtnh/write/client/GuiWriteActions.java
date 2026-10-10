package dev.agentcraft.gtnh.write.client;

import java.util.List;

import net.minecraft.client.gui.GuiScreen;

import dev.agentcraft.gtnh.ui.Theme;
import dev.agentcraft.gtnh.ui.Ui;
import dev.agentcraft.gtnh.ui.UiFont;
import dev.agentcraft.gtnh.ui.Widgets;

/**
 * "Write actions": what the policy offers and the buttons for the one-click operations (restart a service,
 * run a job), plus the state of the write path and the last results. Dispatch, card forms and decision
 * answers live on the task wall and the decision screen. Restart and run are single, audited requests;
 * the server and the control service decide again and may refuse (presence, limits, lock).
 */
public class GuiWriteActions extends WriteScreen {

    public GuiWriteActions(GuiScreen parent) {
        super(parent);
    }

    @Override
    String title() {
        return "Write actions";
    }

    @Override
    int panelWidth() {
        return 340;
    }

    @Override
    int panelHeight() {
        return 260;
    }

    private String why(String cap) {
        if (!ClientWriteState.canWrite()) return WUi.stateText();
        if (!ClientWriteState.policy.capabilities.containsKey(cap)) return "The control service does not offer " + WUi.label(cap) + ".";
        if (!ClientWriteState.policy.usable(cap)) return WUi.label(cap) + " is switched off in the policy.";
        return null;
    }

    @Override
    void body(int mx, int my) {
        UiFont reg = UiFont.regular(), bold = UiFont.bold();
        float x = bx0, w = bx1 - bx0, y = by0;
        y = para("One click sends one audited request. Dispatch needs a Confirm; start it from a card on the task wall.", x, y, w, Widgets.SMALL + 0.6F, reg, WUi.TH.muted, 2) + 3;
        y = section("Restart a service", "service.restart", ClientWriteState.policy.services, "Restart ", y, w, true);
        y = section("Run a job", "cron.run", ClientWriteState.policy.jobs, "Run ", y, w, false);
        // the dry-run fact and the recent results
        if (ClientWriteState.dryRun) {
            float pw = Ui.pill(bold, "DRY RUN", x, y + 1, Widgets.SMALL + 0.6F, WUi.AMBER);
            reg.draw("the control service records requests and executes nothing", x + pw + 4, y + 2, Widgets.SMALL + 0.6F, 0xFF000000 | WUi.TH.muted);
            y += Ui.pillHeight(bold, Widgets.SMALL + 0.6F) + 5;
        }
        bold.draw("Recent", x, y, Widgets.SMALL + 0.6F, 0xFF000000 | WUi.TH.muted);
        y += reg.lineHeight(Widgets.SMALL + 0.6F);
        List<ClientWriteState.ResultInfo> rs = ClientWriteState.recent(6);
        if (rs.isEmpty()) reg.draw("Nothing yet.", x, y, Widgets.SMALL + 0.6F, 0xFF000000 | WUi.TH.muted);
        for (ClientWriteState.ResultInfo r : rs) {
            if (y + reg.lineHeight(Widgets.SMALL + 0.6F) > by1 - 20) break;
            String line = WUi.label(r.capability) + ": " + FormLogic.resultLine(r.status, r.error, r.dryRun);
            reg.drawFit(line, x, y, Widgets.SMALL + 0.6F, w, 0xFF000000 | WUi.resultColor(r));
            y += reg.lineHeight(Widgets.SMALL + 0.6F);
        }
        WUi.Btn close = new WUi.Btn("close", "Close").style(WUi.PRIMARY).on(new Runnable() {

            @Override
            public void run() {
                close();
            }
        });
        float cw = WUi.Btn.widthFor("Close", 16) + 14;
        close.at(bx1 - cw, by1 - 16, cw, 16);
        btns.add(close);
    }

    private float section(String head, final String cap, List<String> names, String verb, float y, float w, boolean restart) {
        UiFont reg = UiFont.regular(), bold = UiFont.bold();
        float x = bx0;
        bold.draw(head, x, y, Widgets.BODY, 0xFF000000 | WUi.TH.text);
        String why = why(cap);
        if (why != null) {
            reg.drawFit(why, x + 100, y + 1, Widgets.SMALL + 0.6F, w - 100, 0xFF000000 | Theme.readable(WUi.ORANGE, WUi.TH.bg));
        }
        y += bold.lineHeight(Widgets.BODY) + 2;
        if (names.isEmpty()) {
            reg.draw("The policy lists none.", x, y, Widgets.SMALL + 0.6F, 0xFF000000 | WUi.TH.muted);
            return y + reg.lineHeight(Widgets.SMALL + 0.6F) + 6;
        }
        float bx = x, h = 15;
        for (final String n : names) {
            String label = verb + n;
            float bw = WUi.Btn.widthFor(label, h);
            if (bx + bw > bx1 && bx > x) {
                bx = x;
                y += h + 3;
            }
            final boolean isRestart = restart;
            WUi.Btn b = new WUi.Btn((isRestart ? "restart." : "run.") + n, label).on(new Runnable() {

                @Override
                public void run() {
                    if (isRestart) WriteClient.restartService(n);
                    else WriteClient.runJob(n);
                }
            });
            b.enable(why == null, why);
            b.at(bx, y, bw, h);
            btns.add(b);
            bx += bw + 4;
        }
        return y + h + 7;
    }
}
