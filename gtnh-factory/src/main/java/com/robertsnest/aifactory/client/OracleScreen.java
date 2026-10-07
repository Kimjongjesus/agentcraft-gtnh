package com.robertsnest.aifactory.client;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.GuiTextField;

import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;

/** In-game read-only console. No inventory container or server packet exists. */
public final class OracleScreen extends GuiScreen {

    private final OracleSession session;
    private int tab;
    private int mode;
    private int page;
    private int frame;
    private long generation;
    private long fetchedAt;
    private boolean open;
    private GuiTextField first;
    private GuiTextField second;
    private com.google.gson.JsonObject payload;
    private List<String> lines = Collections.singletonList("Choose a view, then Read. No world or record writes.");

    public OracleScreen(OracleSession session) {
        this.session = session;
    }

    @Override
    public void initGui() {
        open = true;
        String a = first == null ? "" : first.getText();
        String b = second == null ? "" : second.getText();
        buttonList.clear();
        int w = (width - 16) / 5;
        for (int i = 0; i < OracleViews.TABS.length; i++) {
            GuiButton button = new GuiButton(i, 8 + (i % 5) * w, 25 + (i / 5) * 22, w - 2, 20, OracleViews.TABS[i]);
            button.enabled = i != tab;
            buttonList.add(button);
        }
        buttonList.add(new GuiButton(20, 8, 71, 108, 20, OracleViews.modes(tab)[mode]));
        buttonList.add(new GuiButton(21, 120, 71, 60, 20, "Read"));
        buttonList.add(new GuiButton(22, width - 68, 71, 60, 20, "Close"));
        buttonList.add(new GuiButton(23, 8, height - 24, 50, 20, "Prev"));
        buttonList.add(new GuiButton(24, 62, height - 24, 50, 20, "Next"));
        first = new GuiTextField(fontRendererObj, 8, 104, (width - 24) / 2, 18);
        second = new GuiTextField(fontRendererObj, width / 2 + 4, 104, (width - 24) / 2, 18);
        first.setMaxStringLength(2000);
        second.setMaxStringLength(2000);
        first.setText(a);
        second.setText(b);
        first.setFocused(true);
        Keyboard.enableRepeatEvents(true);
    }

    @Override
    protected void actionPerformed(GuiButton button) {
        if (button.id < 10) {
            tab = button.id;
            mode = 0;
            clearView();
            first.setText("");
            second.setText("");
            initGui();
        } else if (button.id == 20) {
            mode = (mode + 1) % OracleViews.modes(tab).length;
            clearView();
            initGui();
        } else if (button.id == 21) read();
        else if (button.id == 22) mc.displayGuiScreen(null);
        else if (button.id == 23) page = Math.max(0, page - 1);
        else if (button.id == 24) page++;
    }

    private void clearView() {
        generation++;
        page = 0;
        frame = 0;
        fetchedAt = 0;
        payload = null;
        lines = Collections.singletonList("Choose inputs if needed, then Read (GET only).");
    }

    void worldChanged() {
        clearView();
        first.setText("");
        second.setText("");
        lines = Collections.singletonList(
            "World changed / cached view cleared. Verify the configured Oracle endpoint before reading.");
    }

    private void read() {
        final long ticket = ++generation;
        String route = OracleViews.route(tab, mode, first.getText(), second.getText());
        if (!session.request(route, (data, error) -> {
            if (!open || ticket != generation) return;
            payload = error == null ? data : null;
            frame = 0;
            lines = error == null ? OracleText.lines(tab == 5 && mode == 0 ? OracleRecorder.details(data, frame) : data)
                : Collections.singletonList(error);
            fetchedAt = System.nanoTime();
            page = 0;
        })) lines = Collections.singletonList("Oracle busy. Press Read after the current request completes.");
        else lines = Collections.singletonList("Reading Oracle... (GET only)");
        fetchedAt = 0;
        payload = null;
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        drawRect(0, 0, width, height, 0xF018232B);
        drawString(fontRendererObj, "FACTORY ORACLE / READ ONLY", 8, 8, 0xCDE3E8);
        String[] labels = OracleViews.labels(tab, mode);
        drawString(fontRendererObj, fontRendererObj.trimStringToWidth(labels[0], width / 2 - 12), 8, 94, 0x9CB4BE);
        drawString(
            fontRendererObj,
            fontRendererObj.trimStringToWidth(labels[1], width / 2 - 12),
            width / 2 + 4,
            94,
            0x9CB4BE);
        first.drawTextBox();
        second.drawTextBox();
        boolean plotted = tab == 4 && mode == 0
            && width >= 600
            && height >= 300
            && OracleMap.draw(payload, fontRendererObj, 8, 134, width / 2 - 16, height - 174, mouseX, mouseY);
        if (tab == 5 && mode == 0 && width >= 600 && height >= 400)
            plotted = OracleRecorder.draw(payload, fontRendererObj, 8, 134, width / 2 - 16, height - 174, frame);
        if (tab == 9 && mode == 0 && width >= 600 && height >= 300)
            plotted = OracleGhost.draw(payload, fontRendererObj, 8, 134, width / 2 - 16, height - 174);
        int textX = plotted ? width / 2 + 4 : 12;
        List<String> wrapped = new ArrayList<>();
        for (String line : lines) wrapped.addAll(fontRendererObj.listFormattedStringToWidth(line, width - textX - 12));
        int perPage = Math.max(1, (height - 164) / 10);
        int lastPage = Math.max(0, (wrapped.size() - 1) / perPage);
        page = Math.min(page, lastPage);
        int y = 138;
        for (int i = page * perPage; i < Math.min(wrapped.size(), (page + 1) * perPage); i++) {
            drawString(fontRendererObj, wrapped.get(i), textX, y, 0xCDE3E8);
            y += 10;
        }
        String age = fetchedAt == 0 ? "" : " / fetched " + ((System.nanoTime() - fetchedAt) / 1000000000L) + "s ago";
        drawString(
            fontRendererObj,
            fontRendererObj.trimStringToWidth("Page " + (page + 1) + "/" + (lastPage + 1) + age, width - 128),
            120,
            height - 18,
            0xFFB06B);
        super.drawScreen(mouseX, mouseY, partialTicks);
    }

    @Override
    protected void keyTyped(char c, int key) {
        if (key == Keyboard.KEY_TAB) {
            boolean focus = first.isFocused();
            first.setFocused(!focus);
            second.setFocused(focus);
        } else if (key == Keyboard.KEY_RETURN) read();
        else if (!first.textboxKeyTyped(c, key) && !second.textboxKeyTyped(c, key)) super.keyTyped(c, key);
    }

    @Override
    protected void mouseClicked(int x, int y, int button) {
        if (button == 0 && tab == 5
            && mode == 0
            && payload != null
            && width >= 600
            && height >= 400
            && x >= 24
            && x <= width / 2 - 24
            && y >= 204
            && y <= 264) {
            frame = OracleRecorder.pick(payload, x - 24, width / 2 - 48);
            lines = OracleText.lines(OracleRecorder.details(payload, frame));
            page = 0;
            return;
        }
        super.mouseClicked(x, y, button);
        first.mouseClicked(x, y, button);
        second.mouseClicked(x, y, button);
    }

    @Override
    public void handleMouseInput() {
        super.handleMouseInput();
        int wheel = Mouse.getEventDWheel();
        if (wheel != 0) page = Math.max(0, page + (wheel < 0 ? 1 : -1));
    }

    @Override
    public void updateScreen() {
        first.updateCursorCounter();
        second.updateCursorCounter();
    }

    @Override
    public void onGuiClosed() {
        open = false;
        generation++;
        Keyboard.enableRepeatEvents(false);
    }

    @Override
    public boolean doesGuiPauseGame() {
        return false;
    }
}
