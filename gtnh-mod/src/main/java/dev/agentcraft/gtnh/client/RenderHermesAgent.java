package dev.agentcraft.gtnh.client;

import net.minecraft.client.model.ModelBiped;
import net.minecraft.client.renderer.OpenGlHelper;
import net.minecraft.client.renderer.entity.RenderBiped;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLiving;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.util.ResourceLocation;

import org.lwjgl.opengl.GL11;

import dev.agentcraft.gtnh.entity.EntityHermesAgent;
import dev.agentcraft.gtnh.state.AgentInfo;
import dev.agentcraft.gtnh.state.ClientAgentCache;
import dev.agentcraft.gtnh.ui.PlateDeclutter;
import dev.agentcraft.gtnh.ui.TextLayout;
import dev.agentcraft.gtnh.ui.Theme;
import dev.agentcraft.gtnh.ui.Ui;
import dev.agentcraft.gtnh.ui.UiFont;

/**
 * Biped NPC (Steve skin; the outfit is dyed armour in the agent colour, a gold helmet for the lead,
 * who is also drawn 10% taller) with a two-line nameplate: line 1 = status dot + name in the agent
 * colour, line 2 = "state · activity" in the status colour, and a bobbing "!" above agents waiting
 * on the player. Data comes from the SimpleNetworkWrapper cache, falling back to the entity's DataWatcher.
 */
public class RenderHermesAgent extends RenderBiped {

    private static final ResourceLocation STEVE = new ResourceLocation("textures/entity/steve.png");
    private static final double PLATE_RANGE_SQ = 48.0D * 48.0D;
    /** Plate pixels -> blocks; name and state text sizes; max text row width (plate pixels). */
    static final float SCALE = 0.0125F, NS = 16, SS = 12.5F, MAX_W = 230;
    /** How far the "!" badge reaches left of the plate (plate pixels); marker-only radius. */
    static final float BADGE_REACH = 34, DOT_R = 20;

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

    /** What a plate says (from the network cache, else the entity's DataWatcher). */
    private static final class PlateText {

        String name, state, line2;
        int statusColor, color;
        boolean lead;
    }

    private static PlateText text(EntityHermesAgent e) {
        AgentInfo a = ClientAgentCache.forEntity(e.getEntityId());
        PlateText p = new PlateText();
        String activity;
        boolean active;
        if (a != null) {
            p.name = a.name;
            p.state = a.state;
            activity = a.activity;
            p.color = a.color;
            active = a.active;
        } else {
            String custom = e.getCustomNameTag();
            int cut = custom.indexOf(" \u00a7");
            p.name = cut > 0 ? custom.substring(0, cut) : (custom.isEmpty() ? e.getAgentId() : custom);
            p.state = e.getAgentState();
            activity = e.getAgentActivity();
            p.color = e.getAgentColor();
            active = true;
        }
        String family = a != null ? a.family() : AgentInfo.family(p.state, active);
        p.statusColor = AgentInfo.familyColor(family);
        p.state = p.state.replace('_', ' ');
        p.line2 = p.state + (activity.isEmpty() ? "" : " \u00b7 " + activity);
        p.lead = a != null && "lead".equals(a.role);
        return p;
    }

    /** Plate geometry in plate pixels for a mode: {w1 (name row), w2 (state row), half width, height}. */
    private static float[] metrics(PlateText t, int groupSize, int mode) {
        if (mode == PlateDeclutter.DOT) return new float[] { 0, 0, DOT_R, DOT_R * 2 };
        UiFont bold = UiFont.bold(), reg = UiFont.regular();
        String extra = groupSize > 1 ? "+" + (groupSize - 1) + " here" : "";
        float extraW = extra.isEmpty() ? 0 : reg.width(extra, SS) + SS * 1.1F + 5;
        float dot = NS * 0.24F;
        String n1 = TextLayout.ellipsize(t.name, MAX_W - dot * 3 - extraW, bold.measure(NS));
        float w1 = dot * 3 + bold.width(n1, NS) + extraW, w2 = 0;
        float h = bold.lineHeight(NS) + 6;
        if (mode != PlateDeclutter.MINI) {
            String n2 = TextLayout.ellipsize(row2(t, mode), MAX_W, reg.measure(SS));
            w2 = reg.width(n2, SS);
            h += reg.lineHeight(SS);
        }
        float half = Math.max(w1, w2) / 2 + 7;
        return new float[] { w1, w2, half, h };
    }

    private static String row2(PlateText t, int mode) {
        return mode == PlateDeclutter.FULL ? t.line2 : t.state;
    }

    private static float base(EntityHermesAgent e, boolean lead) {
        return e.height + (lead ? 0.95F : 0.78F);
    }

    private static final PlateLayout.Sizer SIZER = new PlateLayout.Sizer() {

        @Override
        public float[][] size(EntityHermesAgent e, PlateLayout.Info i) {
            PlateText t = text(e);
            float[][] out = new float[3][PlateDeclutter.MODES];
            for (int m = 0; m < PlateDeclutter.MODES; m++) {
                float[] g = metrics(t, i.groupSize, m);
                float badge = i.waiting > 0 && m != PlateDeclutter.DOT ? BADGE_REACH : 0;
                out[0][m] = (g[2] + badge) * SCALE;
                out[1][m] = g[2] * SCALE;
                out[2][m] = g[3] * SCALE;
            }
            return out;
        }

        @Override
        public float base(EntityHermesAgent e) {
            AgentInfo a = ClientAgentCache.forEntity(e.getEntityId());
            return RenderHermesAgent.base(e, a != null && "lead".equals(a.role));
        }
    };

    @Override
    protected void passSpecialRender(EntityLivingBase living, double x, double y, double z) {
        if (!(living instanceof EntityHermesAgent)) return;
        EntityHermesAgent e = (EntityHermesAgent) living;
        if (e.getDistanceSqToEntity(renderManager.livingPlayer) > PLATE_RANGE_SQ) return;

        PlateLayout.Info pl = PlateLayout.of(e, SIZER);
        if (pl.hidden) return; // shares a spot with another agent: that agent's plate shows "+N here"
        PlateText t = text(e);
        float top = base(e, t.lead) + pl.tier * PlateLayout.TIER_STEP;
        drawPlate(t, pl.mode, pl.groupSize, pl.waiting, x, y + top, z);
    }

    @Override
    protected void preRenderCallback(EntityLivingBase living, float partial) {
        AgentInfo a = ClientAgentCache.forEntity(living.getEntityId());
        if (a != null && "lead".equals(a.role)) GL11.glScalef(1.1F, 1.1F, 1.1F); // the lead stands a little taller
    }

    private void billboard(double x, double y, double z, float scale) {
        GL11.glPushMatrix();
        GL11.glTranslatef((float) x, (float) y, (float) z);
        GL11.glNormal3f(0.0F, 1.0F, 0.0F);
        GL11.glRotatef(-renderManager.playerViewY, 0.0F, 1.0F, 0.0F);
        GL11.glRotatef(renderManager.playerViewX, 1.0F, 0.0F, 0.0F);
        GL11.glScalef(-scale, -scale, scale);
        GL11.glDisable(GL11.GL_LIGHTING);
        GL11.glDisable(GL11.GL_CULL_FACE);
        OpenGlHelper.setLightmapTextureCoords(OpenGlHelper.lightmapTexUnit, 240.0F, 240.0F);
    }

    private void endBillboard() {
        GL11.glEnable(GL11.GL_DEPTH_TEST);
        GL11.glDepthMask(true);
        GL11.glEnable(GL11.GL_CULL_FACE);
        GL11.glEnable(GL11.GL_LIGHTING);
        GL11.glEnable(GL11.GL_TEXTURE_2D);
        GL11.glDisable(GL11.GL_BLEND);
        GL11.glColor4f(1.0F, 1.0F, 1.0F, 1.0F);
        GL11.glPopMatrix();
    }

    /**
     * The "waiting on the player" badge: a big white "!" in a clay disc at the plate's left edge (beside the
     * plate, not above it, so stacked plates of neighbours never cover it), pulsing, with the number
     * of waiting agents when several share the spot. Display only; decisions are answered in Hermes.
     */
    private void drawBadge(float cx, float cy, int count) {
        UiFont heavy = UiFont.heavy(), bold = UiFont.bold();
        double t = (System.currentTimeMillis() % 1600L) / 1600.0D;
        float k = (float) (0.5 + 0.5 * Math.sin(t * Math.PI * 2));
        float r = 15 + 1.5F * k;
        Ui.dot(cx, cy, r + 2.5, 0xF01C1815);
        Ui.dot(cx, cy, r, 0xFF000000 | Theme.mix(0xD97757, 0xEE9474, k));
        float s = 25;
        heavy.drawCentered("!", cx, cy - heavy.lineHeight(s) * 0.5F, s, 0xFFFFFFFF);
        if (count > 1) Ui.pill(bold, String.valueOf(count), cx + r * 0.35F, cy - r - 9, 13, 0xF4EFE6);
    }

    /**
     * Billboarded plate in the UI font: status dot + name (agent colour) and, below, "state ·
     * activity" (full) or the state (compact) in the status colour, ellipsized by width; name only
     * (mini); or just the marker (dot). "+N here" when agents share this spot. A faint pass shows
     * through walls, the full pass is depth-tested.
     */
    private void drawPlate(PlateText t, int mode, int groupSize, int waiting, double x, double y, double z) {
        UiFont bold = UiFont.bold(), reg = UiFont.regular();
        final int bg = 0x1C1815;
        float ns = NS, ss = SS;
        int statusColor = t.statusColor;
        billboard(x, y, z, SCALE);
        GL11.glDepthMask(false);
        GL11.glDisable(GL11.GL_DEPTH_TEST);
        if (mode == PlateDeclutter.DOT) {
            // marker only: the "!" badge, or the status dot in a dark ring
            if (waiting > 0) drawBadge(0, -DOT_R, waiting);
            else {
                Ui.dot(0, -DOT_R, 10, 0xF01C1815);
                Ui.dot(0, -DOT_R, 7, 0xFF000000 | statusColor);
            }
            endBillboard();
            return;
        }
        String extra = groupSize > 1 ? "+" + (groupSize - 1) + " here" : "";
        float extraW = extra.isEmpty() ? 0 : reg.width(extra, ss) + ss * 1.1F + 5;
        float dot = ns * 0.24F;
        String n1 = TextLayout.ellipsize(t.name, MAX_W - dot * 3 - extraW, bold.measure(ns));
        String n2 = mode == PlateDeclutter.MINI ? "" : TextLayout.ellipsize(row2(t, mode), MAX_W, reg.measure(ss));
        float[] m = metrics(t, groupSize, mode);
        float w1 = m[0], half = m[2], h = m[3];
        float lh1 = bold.lineHeight(ns);
        int nameC = Theme.readable(t.color, bg), stC = Theme.readable(statusColor, bg);
        Ui.round(-half, -h, half, 0, 7, 0xB4000000 | bg);
        if (waiting > 0) drawBadge(-half - 15, -h / 2, waiting);
        for (int pass = 0; pass < 2; pass++) {
            int alpha = pass == 0 ? 0x50000000 : 0xFF000000;
            if (pass == 1) {
                GL11.glEnable(GL11.GL_DEPTH_TEST);
                GL11.glDepthMask(true);
            }
            float x1 = -w1 / 2;
            Ui.dot(x1 + dot, -h + 3 + lh1 * 0.5F, dot, alpha | statusColor);
            bold.draw(n1, x1 + dot * 3, -h + 3, ns, alpha | nameC);
            if (!extra.isEmpty()) Ui.pill(reg, extra, w1 / 2 - extraW + 5, -h + 3 + (lh1 - Ui.pillHeight(reg, ss)) / 2, ss, 0xEE9474);
            if (!n2.isEmpty()) reg.drawCentered(n2, 0, -h + 3 + lh1, ss, alpha | stC);
        }
        endBillboard();
    }
}
