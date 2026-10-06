package dev.agentcraft.gtnh.item;

import java.util.List;

import net.minecraft.creativetab.CreativeTabs;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.world.World;

import dev.agentcraft.gtnh.AgentCraftGTNH;
import dev.agentcraft.gtnh.server.EditService;
import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;

/**
 * Card 6: the Office Edit Tool (only from {@code /agentcraft give edittool}, op only; not in any
 * creative tab). Sneak + right-click toggles edit mode (the server checks the op level and stores
 * the flag on the stack; it glows while on). In edit mode, right-click a panel for the Panel
 * Inspector, an anchor marker for the Anchor Editor, anywhere else for the editor (palette,
 * anchors, layouts). The item itself changes nothing: every edit is a request the server
 * validates.
 */
public class ItemEditTool extends Item {

    public static final String TAG = "agentcraftEdit";

    public ItemEditTool() {
        setUnlocalizedName(AgentCraftGTNH.MODID + ".edit_tool");
        setTextureName(AgentCraftGTNH.MODID + ":edit_tool");
        setMaxStackSize(1);
        setFull3D();
    }

    public static boolean isTool(ItemStack s) {
        return s != null && s.getItem() instanceof ItemEditTool;
    }

    public static boolean isOn(ItemStack s) {
        return isTool(s) && s.hasTagCompound()
            && s.getTagCompound()
                .getBoolean(TAG);
    }

    public static void setOn(ItemStack s, boolean on) {
        if (!isTool(s)) return;
        if (!s.hasTagCompound()) s.setTagCompound(new NBTTagCompound());
        s.getTagCompound()
            .setBoolean(TAG, on);
    }

    @Override
    public ItemStack onItemRightClick(ItemStack stack, World world, EntityPlayer player) {
        if (player.isSneaking()) {
            if (!world.isRemote && player instanceof EntityPlayerMP && EditService.instance != null) {
                EditService.chat((EntityPlayerMP) player, EditService.instance.toggle((EntityPlayerMP) player, stack));
                ((EntityPlayerMP) player).inventoryContainer.detectAndSendChanges();
            }
            return stack;
        }
        if (world.isRemote) AgentCraftGTNH.proxy.editToolUse(null, player, isOn(stack));
        return stack;
    }

    @Override
    public boolean onItemUse(ItemStack stack, EntityPlayer player, World world, int x, int y, int z, int side, float hx, float hy, float hz) {
        if (player.isSneaking()) {
            if (!world.isRemote && player instanceof EntityPlayerMP && EditService.instance != null) {
                EditService.chat((EntityPlayerMP) player, EditService.instance.toggle((EntityPlayerMP) player, stack));
                ((EntityPlayerMP) player).inventoryContainer.detectAndSendChanges();
            }
            return true;
        }
        if (world.isRemote) AgentCraftGTNH.proxy.editToolUse(new int[] { x, y, z, side }, player, isOn(stack));
        return true;
    }

    @Override
    @SideOnly(Side.CLIENT)
    public boolean hasEffect(ItemStack stack, int pass) {
        return isOn(stack);
    }

    @Override
    @SideOnly(Side.CLIENT)
    @SuppressWarnings({ "rawtypes", "unchecked" })
    public void addInformation(ItemStack stack, EntityPlayer player, List lines, boolean advanced) {
        lines.add(isOn(stack) ? "\u00a7aEdit mode ON" : "\u00a77Edit mode off");
        lines.add("\u00a77Sneak + right-click: toggle edit mode");
        lines.add("\u00a77Right-click a panel: inspector");
        lines.add("\u00a77Right-click elsewhere: palette, anchors, layouts");
    }

    @Override
    public Item setCreativeTab(CreativeTabs tab) {
        return this; // never in a creative tab: ops get it with /agentcraft give edittool
    }
}
