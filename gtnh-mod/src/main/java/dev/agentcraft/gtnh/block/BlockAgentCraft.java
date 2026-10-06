package dev.agentcraft.gtnh.block;

import net.minecraft.block.BlockContainer;
import net.minecraft.block.material.Material;
import net.minecraft.client.renderer.texture.IIconRegister;
import net.minecraft.creativetab.CreativeTabs;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.IIcon;
import net.minecraft.util.MathHelper;
import net.minecraft.world.World;

import dev.agentcraft.gtnh.AgentCraftGTNH;
import dev.agentcraft.gtnh.CommonProxy;
import dev.agentcraft.gtnh.state.HqData;
import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;

/**
 * Placeable HQ blocks. Card 2: an agent monitor, a status lamp and the fleet beacon. Card 3: the
 * task wall (a W x H screen of the Kanban), the library (opens a read-only reader) and the goal
 * atrium (progress ring panel). They are plain blocks with a binding; Eli places and breaks them
 * like any block (they drop themselves). Bind with /agentcraft bind ... while looking at one.
 * Right-clicking a task wall / atrium / library opens a client-only screen: nothing is sent to the
 * server (read-only, no decision answering).
 */
public class BlockAgentCraft extends BlockContainer {

    public enum Kind {
        MONITOR,
        LAMP,
        BEACON,
        TASKWALL,
        LIBRARY,
        ATRIUM;

        /** Blocks that face the player who places them (metadata 2..5) and draw on their front. */
        public boolean faced() {
            return this == MONITOR || this == TASKWALL || this == ATRIUM || this == LIBRARY;
        }

        public String id() {
            return name().toLowerCase(java.util.Locale.ROOT);
        }
    }

    public final Kind kind;
    @SideOnly(Side.CLIENT)
    private IIcon front, side, top;

    public BlockAgentCraft(Kind kind) {
        super(kind == Kind.LIBRARY ? Material.wood : kind == Kind.LAMP || kind == Kind.BEACON ? Material.glass : Material.iron);
        this.kind = kind;
        setBlockName(AgentCraftGTNH.MODID + "." + kind.id());
        setHardness(1.5F);
        setResistance(10.0F);
        setCreativeTab(CreativeTabs.tabDecorations);
        setStepSound(kind == Kind.LIBRARY ? soundTypeWood : kind == Kind.LAMP || kind == Kind.BEACON ? soundTypeGlass : soundTypeMetal);
        setLightLevel(kind == Kind.LAMP ? 0.75F : kind == Kind.BEACON ? 1.0F : kind == Kind.LIBRARY ? 0.0F : 0.35F);
    }

    @Override
    public TileEntity createNewTileEntity(World world, int meta) {
        switch (kind) {
            case MONITOR:
                return new TileAgentCraft.Monitor();
            case LAMP:
                return new TileAgentCraft.Lamp();
            case TASKWALL:
                return new TileAgentCraft.TaskWall();
            case LIBRARY:
                return new TileAgentCraft.Library();
            case ATRIUM:
                return new TileAgentCraft.Atrium();
            default:
                return new TileAgentCraft.Beacon();
        }
    }

    /** Faced blocks turn their front to the player who places them (metadata 2..5 = north, south, west, east). */
    @Override
    public void onBlockPlacedBy(World w, int x, int y, int z, EntityLivingBase placer, ItemStack stack) {
        if (!kind.faced()) return;
        int l = MathHelper.floor_double(placer.rotationYaw * 4.0F / 360.0F + 0.5D) & 3;
        int meta = l == 0 ? 2 : l == 1 ? 5 : l == 2 ? 3 : 4;
        w.setBlockMetadataWithNotify(x, y, z, meta, 2);
    }

    /**
     * Right-click. Task wall / atrium / library: open the read-only screen (client side only). Others:
     * say what the block shows; the beacon also gives the board summary. Binding changes are op
     * commands, never clicks.
     */
    @Override
    public boolean onBlockActivated(World w, int x, int y, int z, EntityPlayer p, int side, float hx, float hy, float hz) {
        TileEntity te = w.getTileEntity(x, y, z);
        String b = te instanceof TileAgentCraft ? ((TileAgentCraft) te).binding : "";
        if (kind == Kind.TASKWALL || kind == Kind.LIBRARY || kind == Kind.ATRIUM) {
            if (w.isRemote) AgentCraftGTNH.proxy.openHqScreen(kind, b);
            return true;
        }
        if (w.isRemote) return true;
        String what = kind == Kind.BEACON ? "the whole fleet"
            : b.isEmpty() ? (kind == Kind.LAMP ? "the whole fleet (unbound)" : "nothing yet (unbound)")
                : "fleet".equals(b) ? "the whole fleet" : "agent " + b;
        p.addChatMessage(
            new ChatComponentText(
                "[AgentCraft] this " + kind.id() + " shows " + what + ". Ops: look at it and /agentcraft bind <agent|fleet>"));
        if (kind == Kind.BEACON && CommonProxy.sync != null) {
            p.addChatMessage(new ChatComponentText("[AgentCraft] " + summaryLine(CommonProxy.sync.board.summary(""))));
        }
        return true;
    }

    /** "All boards: 12 todo, 3 doing, 1 review, 2 blocked, 40/58 done (69%), 4 decisions need you". */
    public static String summaryLine(HqData.Goal g) {
        return g.text + ": " + g.counts[0]
            + " todo, "
            + g.counts[1]
            + " doing, "
            + g.counts[2]
            + " review, "
            + g.counts[4]
            + " blocked, "
            + g.counts[3]
            + "/"
            + g.total
            + " done ("
            + Math.round(g.progress * 100)
            + "%), "
            + g.openDecisions
            + (g.openDecisions == 1 ? " decision needs" : " decisions need")
            + " you";
    }

    @Override
    public int getRenderType() {
        return 0;
    }

    @Override
    @SideOnly(Side.CLIENT)
    public void registerBlockIcons(IIconRegister reg) {
        String p = AgentCraftGTNH.MODID + ":";
        switch (kind) {
            case MONITOR:
                front = reg.registerIcon(p + "monitor_front");
                side = reg.registerIcon(p + "monitor_side");
                top = side;
                break;
            case TASKWALL:
                front = reg.registerIcon(p + "taskwall_front");
                side = reg.registerIcon(p + "monitor_side");
                top = side;
                break;
            case ATRIUM:
                front = reg.registerIcon(p + "atrium_front");
                side = reg.registerIcon(p + "monitor_side");
                top = side;
                break;
            case LIBRARY:
                front = reg.registerIcon(p + "library_front");
                side = reg.registerIcon(p + "library_side");
                top = reg.registerIcon(p + "library_top");
                break;
            case LAMP:
                front = side = top = reg.registerIcon(p + "lamp");
                break;
            default:
                front = side = reg.registerIcon(p + "beacon_side");
                top = reg.registerIcon(p + "beacon_top");
        }
        blockIcon = side;
    }

    @Override
    @SideOnly(Side.CLIENT)
    public IIcon getIcon(int s, int meta) {
        if (kind.faced()) {
            int f = meta < 2 ? 3 : meta; // item form: front to the south
            if (s == f) return front;
            return s <= 1 ? top : side;
        }
        return s <= 1 ? top : side;
    }
}
