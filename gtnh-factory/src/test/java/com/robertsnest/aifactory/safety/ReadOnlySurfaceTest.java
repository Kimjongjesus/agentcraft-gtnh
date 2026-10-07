package com.robertsnest.aifactory.safety;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.DataInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeMap;

import org.junit.Test;

import com.robertsnest.aifactory.AiFactoryMod;

/**
 * The read-only claim, checked on the compiled classes rather than on the source.
 *
 * <p>
 * Every class of the mod is parsed and its constant pool searched for the names a world write,
 * a chunk load, a server command or an AE2 / GregTech mutation would have to reference. Main
 * classes are compiled against MCP names, so these are the names that would appear. The only
 * command type allowed is the client-side {@code /oracle} chat command (Forge's client command
 * handler; it talks to an Oracle service, never to the server). Added in AgentCraft (card G1);
 * the same check runs on a built jar with {@code javap} (see README "Verifying a build").
 */
public class ReadOnlySurfaceTest {

    /** Member names that change the world, load chunks, run commands or move items. */
    private static final Set<String> FORBIDDEN_MEMBERS = new HashSet<String>(
        Arrays.asList(
            "setBlock",
            "setBlockToAir",
            "setBlockMetadataWithNotify",
            "setTileEntity",
            "removeTileEntity",
            "spawnEntityInWorld",
            "createExplosion",
            "newExplosion",
            "loadChunk",
            "provideChunk",
            "executeCommand",
            "getCommandManager",
            "submitJob",
            "beginCraftingJob",
            "injectItems",
            "extractItems",
            "setInventorySlotContents",
            "decrStackSize",
            "setWorkDataValue",
            "dropBlockAsItem",
            "func_147449_b",
            "func_147468_f"));

    /** Types whose mere reference means a write path or a chunk ticket. */
    private static final Set<String> FORBIDDEN_TYPES = new HashSet<String>(
        Arrays.asList(
            "net/minecraftforge/common/ForgeChunkManager",
            "net/minecraft/command/ServerCommandManager",
            "net/minecraft/command/ICommandManager",
            "appeng/api/networking/security/MachineSource",
            "appeng/api/networking/security/PlayerSource",
            "appeng/api/networking/security/BaseActionSource"));

    private static final String COMMAND_BASE = "net/minecraft/command/CommandBase";

    @Test
    public void noCompiledClassReferencesAWritePath() throws Exception {
        File root = classesRoot();
        List<File> classes = new ArrayList<File>();
        collect(new File(root, "com/robertsnest/aifactory"), classes);
        assertTrue("found only " + classes.size() + " classes under " + root, classes.size() > 50);

        TreeMap<String, String> violations = new TreeMap<String, String>();
        int commandClasses = 0;
        for (File file : classes) {
            String name = root.toURI()
                .relativize(file.toURI())
                .getPath();
            Set<String> strings = utf8Constants(file);
            for (String s : strings) {
                if (FORBIDDEN_MEMBERS.contains(s) || FORBIDDEN_TYPES.contains(s)) {
                    violations.put(name + " -> " + s, s);
                }
            }
            if (strings.contains(COMMAND_BASE)) {
                commandClasses++;
                if (!name.startsWith("com/robertsnest/aifactory/client/")) {
                    violations.put(name + " -> " + COMMAND_BASE + " outside the client package", COMMAND_BASE);
                }
            }
        }
        assertEquals("forbidden references: " + violations.keySet(), 0, violations.size());
        assertTrue("the client /oracle command is the only command type", commandClasses <= 2);
    }

    @Test
    public void noClassIsNamedLikeAnActionPath() throws Exception {
        File root = classesRoot();
        List<File> classes = new ArrayList<File>();
        collect(new File(root, "com/robertsnest/aifactory"), classes);
        List<String> suspicious = new ArrayList<String>();
        for (File file : classes) {
            String simple = file.getName();
            if (simple
                .matches(".*(Action|Gateway|Actor|Dispatch|Ledger|Executor|Mutat|Writer|Undo|Placer|CraftRequest).*")) {
                suspicious.add(simple);
            }
        }
        assertEquals("action-shaped classes: " + suspicious, 0, suspicious.size());
    }

    @Test
    public void theParserSeesRealReferences() throws Exception {
        // guards against a vacuous pass: the reads the mod does must be visible to the scan
        File root = classesRoot();
        Set<String> all = new HashSet<String>();
        List<File> classes = new ArrayList<File>();
        collect(new File(root, "com/robertsnest/aifactory"), classes);
        for (File file : classes) {
            all.addAll(utf8Constants(file));
        }
        assertTrue(all.contains("blockExists"));
        assertTrue(all.contains("getTileEntity"));
        assertTrue(all.contains("com/robertsnest/aifactory/http/TokenAuthenticator"));
    }

    private static File classesRoot() throws URISyntaxException {
        return new File(
            AiFactoryMod.class.getProtectionDomain()
                .getCodeSource()
                .getLocation()
                .toURI());
    }

    private static void collect(File dir, List<File> out) {
        File[] files = dir.listFiles();
        if (files == null) {
            return;
        }
        for (File f : files) {
            if (f.isDirectory()) {
                collect(f, out);
            } else if (f.getName()
                .endsWith(".class")) {
                    out.add(f);
                }
        }
    }

    /** All CONSTANT_Utf8 entries of a class file (JVMS 4.4). */
    static Set<String> utf8Constants(File file) throws IOException {
        Set<String> out = new HashSet<String>();
        DataInputStream in = new DataInputStream(new FileInputStream(file));
        try {
            if (in.readInt() != 0xCAFEBABE) {
                throw new IOException("not a class file: " + file);
            }
            in.readUnsignedShort();
            in.readUnsignedShort();
            int count = in.readUnsignedShort();
            for (int i = 1; i < count; i++) {
                int tag = in.readUnsignedByte();
                switch (tag) {
                    case 1:
                        out.add(in.readUTF());
                        break;
                    case 3:
                    case 4:
                        in.readInt();
                        break;
                    case 5:
                    case 6:
                        in.readLong();
                        i++;
                        break;
                    case 7:
                    case 8:
                    case 16:
                    case 19:
                    case 20:
                        in.readUnsignedShort();
                        break;
                    case 9:
                    case 10:
                    case 11:
                    case 12:
                    case 17:
                    case 18:
                        in.readInt();
                        break;
                    case 15:
                        in.readUnsignedByte();
                        in.readUnsignedShort();
                        break;
                    default:
                        throw new IOException("unknown constant tag " + tag + " in " + file);
                }
            }
        } finally {
            in.close();
        }
        return out;
    }
}
