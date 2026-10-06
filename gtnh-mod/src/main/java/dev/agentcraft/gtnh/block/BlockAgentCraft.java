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
import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;

/**
 * Placeable HQ blocks: an agent monitor (faces the player who places it), a status lamp and the
 * fleet beacon. They are plain decorative blocks with a binding; Eli places and breaks them like any
 * block (they drop themselves). Bind with /agentcraft bind &lt;agent|fleet&gt; while looking at one.
 */
public class BlockAgentCraft extends BlockContainer {

    public enum Kind {
        MONITOR,
        LAMP,
        BEACON
    }

    public final Kind kind;
    @SideOnly(Side.CLIENT)
    private IIcon front, side, top;

    public BlockAgentCraft(Kind kind) {
        super(kind == Kind.MONITOR ? Material.iron : Material.glass);
        this.kind = kind;
        String n = kind.name()
            .toLowerCase(java.util.Locale.ROOT);
        setBlockName(AgentCraftGTNH.MODID + "." + n);
        setHardness(1.5F);
        setResistance(10.0F);
        setCreativeTab(CreativeTabs.tabDecorations);
        setStepSound(kind == Kind.MONITOR ? soundTypeMetal : soundTypeGlass);
        setLightLevel(kind == Kind.MONITOR ? 0.35F : kind == Kind.LAMP ? 0.75F : 1.0F);
    }

    @Override
    public TileEntity createNewTileEntity(World world, int meta) {
        switch (kind) {
            case MONITOR:
                return new TileAgentCraft.Monitor();
            case LAMP:
                return new TileAgentCraft.Lamp();
            default:
                return new TileAgentCraft.Beacon();
        }
    }

    /** Monitors face the player who places them (metadata 2..5 = north, south, west, east). */
    @Override
    public void onBlockPlacedBy(World w, int x, int y, int z, EntityLivingBase placer, ItemStack stack) {
        if (kind != Kind.MONITOR) return;
        int l = MathHelper.floor_double(placer.rotationYaw * 4.0F / 360.0F + 0.5D) & 3;
        int meta = l == 0 ? 2 : l == 1 ? 5 : l == 2 ? 3 : 4;
        w.setBlockMetadataWithNotify(x, y, z, meta, 2);
    }

    /** Right-click: says what the block shows (binding changes are op commands, not clicks). */
    @Override
    public boolean onBlockActivated(World w, int x, int y, int z, EntityPlayer p, int side, float hx, float hy, float hz) {
        if (w.isRemote) return true;
        TileEntity te = w.getTileEntity(x, y, z);
        String b = te instanceof TileAgentCraft ? ((TileAgentCraft) te).binding : "";
        String what = kind == Kind.BEACON ? "the whole fleet"
            : b.isEmpty() ? (kind == Kind.LAMP ? "the whole fleet (unbound)" : "nothing yet (unbound)")
                : "fleet".equals(b) ? "the whole fleet" : "agent " + b;
        p.addChatMessage(
            new ChatComponentText(
                "[AgentCraft] this " + kind.name()
                    .toLowerCase(java.util.Locale.ROOT)
                    + " shows "
                    + what
                    + ". Ops: look at it and /agentcraft bind <agent|fleet>"));
        return true;
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
        if (kind == Kind.MONITOR) {
            int f = meta < 2 ? 3 : meta; // item form: front to the south
            return s == f ? front : side;
        }
        return s <= 1 ? top : side;
    }
}
