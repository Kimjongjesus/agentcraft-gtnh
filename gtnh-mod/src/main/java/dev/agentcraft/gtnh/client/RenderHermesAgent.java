package dev.agentcraft.gtnh.client;

import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.model.ModelBiped;
import net.minecraft.client.renderer.OpenGlHelper;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.entity.RenderBiped;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLiving;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.util.ResourceLocation;

import org.lwjgl.opengl.GL11;

import dev.agentcraft.gtnh.entity.EntityHermesAgent;
import dev.agentcraft.gtnh.state.AgentInfo;
import dev.agentcraft.gtnh.state.ClientAgentCache;

/**
 * Biped NPC (Steve skin; the outfit is dyed armour in the agent colour, a gold helmet for the lead,
 * who is also drawn 10% taller) with a two-line nameplate: line 1 = status dot + name in the agent
 * colour, line 2 = "state · activity" in the status colour, and a bobbing "!" above agents waiting
 * on Eli. Data comes from the SimpleNetworkWrapper cache, falling back to the entity's DataWatcher.
 */
public class RenderHermesAgent extends RenderBiped {

    private static final ResourceLocation STEVE = new ResourceLocation("textures/entity/steve.png");
    private static final double PLATE_RANGE_SQ = 48.0D * 48.0D;

    public RenderHermesAgent() {
        super(new ModelBiped(), 0.5F);
    }

    @Override
    protected ResourceLocation getEntityTexture(EntityLiving entity) {
        return STEVE;
    }

    @Override
    protected ResourceLocation getEntityTexture(Entity entity) {
        return STEVE;
    }

    @Override
    protected void passSpecialRender(EntityLivingBase living, double x, double y, double z) {
        if (!(living instanceof EntityHermesAgent)) return;
        EntityHermesAgent e = (EntityHermesAgent) living;
        if (e.getDistanceSqToEntity(renderManager.livingPlayer) > PLATE_RANGE_SQ) return;

        AgentInfo a = ClientAgentCache.forEntity(e.getEntityId());
        String name, state, activity;
        int color;
        boolean active;
        if (a != null) {
            name = a.name;
            state = a.state;
            activity = a.activity;
            color = a.color;
            active = a.active;
        } else {
            String custom = e.getCustomNameTag();
            int cut = custom.indexOf(" \u00a7");
            name = cut > 0 ? custom.substring(0, cut) : (custom.isEmpty() ? e.getAgentId() : custom);
            state = e.getAgentState();
            activity = e.getAgentActivity();
            color = e.getAgentColor();
            active = true;
        }
        String family = a != null ? a.family() : AgentInfo.family(state, active);
        int statusColor = AgentInfo.familyColor(family);
        String line2 = state.replace('_', ' ') + (activity.isEmpty() ? "" : " \u00b7 " + activity);

        float top = living.height + ("lead".equals(a != null ? a.role : "") ? 0.95F : 0.75F);
        drawPlate(name, "\u25cf ", statusColor, color, x, y + top, z, true);
        drawPlate(line2, "", statusColor, statusColor, x, y + top - 0.27F, z, false);
        if (a != null && a.waiting && ClientAgentCache.linkUp) {
            // decision / permission marker: a bobbing "!" above the plate (display only, answered in Hermes)
            double bob = Math.sin((System.currentTimeMillis() % 1600L) / 1600.0D * Math.PI * 2) * 0.06D;
            drawMarker(x, y + top + 0.42F + bob, z);
        }
    }

    @Override
    protected void preRenderCallback(EntityLivingBase living, float partial) {
        AgentInfo a = ClientAgentCache.forEntity(living.getEntityId());
        if (a != null && "lead".equals(a.role)) GL11.glScalef(1.1F, 1.1F, 1.1F); // the lead stands a little taller
    }

    /** Big clay-coloured "!" in a dark circle, billboarded, visible through walls. */
    private void drawMarker(double x, double y, double z) {
        FontRenderer fr = getFontRendererFromRenderManager();
        float scale = 0.016666668F * 4.0F;
        GL11.glPushMatrix();
        GL11.glTranslatef((float) x, (float) y, (float) z);
        GL11.glNormal3f(0.0F, 1.0F, 0.0F);
        GL11.glRotatef(-renderManager.playerViewY, 0.0F, 1.0F, 0.0F);
        GL11.glRotatef(renderManager.playerViewX, 1.0F, 0.0F, 0.0F);
        GL11.glScalef(-scale, -scale, scale);
        GL11.glDisable(GL11.GL_LIGHTING);
        GL11.glDepthMask(false);
        GL11.glDisable(GL11.GL_DEPTH_TEST);
        GL11.glEnable(GL11.GL_BLEND);
        OpenGlHelper.glBlendFunc(770, 771, 1, 0);
        Tessellator t = Tessellator.instance;
        GL11.glDisable(GL11.GL_TEXTURE_2D);
        GL11.glDisable(GL11.GL_CULL_FACE);
        t.startDrawing(GL11.GL_TRIANGLE_FAN);
        t.setColorRGBA_F(0.85F, 0.47F, 0.34F, 0.95F);
        t.addVertex(0.0D, 3.5D, 0.0D);
        for (int i = 0; i <= 20; i++) {
            double ang = i / 20.0D * Math.PI * 2;
            t.addVertex(Math.cos(ang) * 6.0D, 3.5D + Math.sin(ang) * 6.0D, 0.0D);
        }
        t.draw();
        GL11.glEnable(GL11.GL_CULL_FACE);
        GL11.glEnable(GL11.GL_TEXTURE_2D);
        int w = fr.getStringWidth("!");
        fr.drawString("\u00a7l!", -w / 2 - 1, 0, 0xFFFFFFFF);
        GL11.glEnable(GL11.GL_DEPTH_TEST);
        GL11.glDepthMask(true);
        fr.drawString("\u00a7l!", -w / 2 - 1, 0, 0xFFFFFFFF);
        GL11.glEnable(GL11.GL_LIGHTING);
        GL11.glDisable(GL11.GL_BLEND);
        GL11.glColor4f(1.0F, 1.0F, 1.0F, 1.0F);
        GL11.glPopMatrix();
    }

    /** Billboarded text pill, like vanilla's label but with a coloured prefix and opaque-ish background. */
    private void drawPlate(String text, String prefix, int prefixColor, int textColor, double x, double y, double z,
        boolean bold) {
        FontRenderer fr = getFontRendererFromRenderManager();
        float scale = 0.016666668F * 1.6F;
        String main = bold ? "\u00a7l" + text : text;
        int wPrefix = fr.getStringWidth(prefix);
        int width = wPrefix + fr.getStringWidth(main);
        int half = width / 2;

        GL11.glPushMatrix();
        GL11.glTranslatef((float) x, (float) y, (float) z);
        GL11.glNormal3f(0.0F, 1.0F, 0.0F);
        GL11.glRotatef(-renderManager.playerViewY, 0.0F, 1.0F, 0.0F);
        GL11.glRotatef(renderManager.playerViewX, 1.0F, 0.0F, 0.0F);
        GL11.glScalef(-scale, -scale, scale);
        GL11.glDisable(GL11.GL_LIGHTING);
        GL11.glDepthMask(false);
        GL11.glDisable(GL11.GL_DEPTH_TEST);
        GL11.glEnable(GL11.GL_BLEND);
        OpenGlHelper.glBlendFunc(770, 771, 1, 0);

        Tessellator t = Tessellator.instance;
        GL11.glDisable(GL11.GL_TEXTURE_2D);
        t.startDrawingQuads();
        t.setColorRGBA_F(0.08F, 0.07F, 0.06F, 0.55F);
        t.addVertex(-half - 2, -1.5, 0.0D);
        t.addVertex(-half - 2, 8.5, 0.0D);
        t.addVertex(half + 2, 8.5, 0.0D);
        t.addVertex(half + 2, -1.5, 0.0D);
        t.draw();
        GL11.glEnable(GL11.GL_TEXTURE_2D);

        // faint pass through walls, then the full-brightness pass with depth
        fr.drawString(prefix, -half, 0, 0x40000000 | prefixColor);
        fr.drawString(main, -half + wPrefix, 0, 0x40000000 | textColor);
        GL11.glEnable(GL11.GL_DEPTH_TEST);
        GL11.glDepthMask(true);
        fr.drawString(prefix, -half, 0, 0xFF000000 | prefixColor);
        fr.drawString(main, -half + wPrefix, 0, 0xFF000000 | lighten(textColor));

        GL11.glEnable(GL11.GL_LIGHTING);
        GL11.glDisable(GL11.GL_BLEND);
        GL11.glColor4f(1.0F, 1.0F, 1.0F, 1.0F);
        GL11.glPopMatrix();
    }

    /** Keep dark agent colours readable on the dark pill. */
    private static int lighten(int rgb) {
        int r = (rgb >> 16) & 0xFF, g = (rgb >> 8) & 0xFF, b = rgb & 0xFF;
        int lum = (r * 299 + g * 587 + b * 114) / 1000;
        if (lum >= 110) return rgb;
        r = r + (255 - r) / 2;
        g = g + (255 - g) / 2;
        b = b + (255 - b) / 2;
        return (r << 16) | (g << 8) | b;
    }
}
