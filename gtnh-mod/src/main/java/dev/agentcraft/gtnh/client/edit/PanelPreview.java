package dev.agentcraft.gtnh.client.edit;

import net.minecraft.block.Block;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderHelper;
import net.minecraft.client.renderer.entity.RenderItem;
import net.minecraft.init.Items;
import net.minecraft.item.ItemStack;

import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;

import dev.agentcraft.gtnh.edit.PanelType;
import dev.agentcraft.gtnh.server.EditService;
import dev.agentcraft.gtnh.ui.Theme;
import dev.agentcraft.gtnh.ui.Ui;
import dev.agentcraft.gtnh.ui.UiFont;
import dev.agentcraft.gtnh.ui.panel.PanelContext;
import dev.agentcraft.gtnh.ui.panel.PanelRegistry;
import dev.agentcraft.gtnh.ui.panel.PanelRenderer;

/**
 * Draws a panel kind into a GUI box: the card-4 renderer itself (same code as the wall in the
 * world, at full detail, with the given binding and size), or for kinds without a screen the
 * block's item icon. Used by the palette and the inspector's live preview; a newly registered
 * panel kind previews here with no editor changes.
 */
public final class PanelPreview {

    private static final RenderItem ITEMS = new RenderItem();

    private PanelPreview() {}

    /** @return the box actually used (x0, y0, x1, y1), keeping the panel's aspect ratio */
    public static float[] draw(PanelType t, String binding, String theme, int w, int h, float x0, float y0, float x1, float y1) {
        PanelRenderer r = t == null || t.previewPanel.isEmpty() ? null : PanelRegistry.get(t.previewPanel);
        Theme th = Theme.byId(theme == null || theme.isEmpty() ? "dark" : theme);
        if (r == null) {
            float s = Math.min(x1 - x0, y1 - y0);
            float cx = (x0 + x1) / 2, cy = (y0 + y1) / 2;
            Ui.round(cx - s / 2, cy - s / 2, cx + s / 2, cy + s / 2, 4, 0xFF000000 | th.bg);
            icon(t, cx - s * 0.32F, cy - s * 0.32F, s * 0.64F / 16F);
            return new float[] { cx - s / 2, cy - s / 2, cx + s / 2, cy + s / 2 };
        }
        float px = 128;
        float cw = w * px, ch = h * px;
        float k = Math.min((x1 - x0) / cw, (y1 - y0) / ch);
        float bw = cw * k, bh = ch * k;
        float bx = x0 + ((x1 - x0) - bw) / 2, by = y0 + ((y1 - y0) - bh) / 2;
        PanelContext c = new PanelContext(
            r.id(),
            binding,
            PanelRegistry.resolve(r.source(), binding),
            th,
            px,
            w,
            h,
            3.0,
            400,
            PanelContext.NEAR,
            System.currentTimeMillis());
        Ui.round(bx - 2, by - 2, bx + bw + 2, by + bh + 2, 3, 0xFF000000 | th.frame);
        GL11.glPushMatrix();
        GL11.glTranslatef(bx, by, 0);
        GL11.glScalef(k, k, 1);
        boolean depth = GL11.glIsEnabled(GL11.GL_DEPTH_TEST);
        GL11.glDisable(GL11.GL_DEPTH_TEST);
        try {
            Ui.rect(0, 0, c.w, c.h, 0xFF000000 | th.bg);
            r.render(c);
        } finally {
            if (depth) GL11.glEnable(GL11.GL_DEPTH_TEST);
            GL11.glColor4f(1, 1, 1, 1);
            GL11.glPopMatrix();
        }
        return new float[] { bx, by, bx + bw, by + bh };
    }

    public static ItemStack stack(PanelType t) {
        if (t == null) return null;
        if (PanelType.SIGN.equals(t.source)) return new ItemStack(Items.sign);
        Block b = EditService.blockOf(t.id);
        return b == null ? null : new ItemStack(b);
    }

    /** Item icon at (x, y), scale 1 = 16 GUI units. */
    public static void icon(PanelType t, float x, float y, float scale) {
        ItemStack st = stack(t);
        if (st == null) return;
        Minecraft mc = Minecraft.getMinecraft();
        GL11.glPushMatrix();
        GL11.glTranslatef(x, y, 0);
        GL11.glScalef(scale, scale, 1);
        RenderHelper.enableGUIStandardItemLighting();
        GL11.glEnable(GL12.GL_RESCALE_NORMAL);
        GL11.glEnable(GL11.GL_DEPTH_TEST);
        ITEMS.renderItemAndEffectIntoGUI(mc.fontRenderer, mc.getTextureManager(), st, 0, 0);
        RenderHelper.disableStandardItemLighting();
        GL11.glDisable(GL12.GL_RESCALE_NORMAL);
        GL11.glDisable(GL11.GL_DEPTH_TEST);
        GL11.glDisable(GL11.GL_LIGHTING);
        GL11.glEnable(GL11.GL_BLEND);
        GL11.glPopMatrix();
        GL11.glColor4f(1, 1, 1, 1);
    }

    /** One-line binding summary for a kind ("binds to a board", ...). */
    public static String bindsTo(PanelType t) {
        switch (t.source) {
            case PanelType.AGENT:
                return "binds to an agent";
            case PanelType.BOARD:
                return "binds to a board (or all)";
            case PanelType.LAMP:
                return "binds to an agent or the fleet";
            case PanelType.SIGN:
                return "a vanilla sign you place";
            default:
                return "no binding";
        }
    }

    public static float textH(float size) {
        return UiFont.regular()
            .lineHeight(size);
    }
}
