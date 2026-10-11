import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Enumeration;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Core-jar scan (card 7, docs/write-path.md 2.1): the core mod must stay a read-only client of Hermes, so
 * no class in its jar may contain the wire names of the write path. Reads every .class entry of the given
 * jar(s) and looks for the byte strings below anywhere in the class file (constant pool included, which is
 * where string literals, class and method names live). Exit status 1 on any hit.
 *
 * <p>
 * usage: java CoreJarScan &lt;jar&gt;...
 */
public class CoreJarScan {

    static final String[] FORBIDDEN = { "action.", "acwrite", "hermes_control" };

    static byte[] read(InputStream in) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        return out.toByteArray();
    }

    static int indexOf(byte[] hay, byte[] needle) {
        outer:
        for (int i = 0; i + needle.length <= hay.length; i++) {
            for (int j = 0; j < needle.length; j++) if (hay[i + j] != needle[j]) continue outer;
            return i;
        }
        return -1;
    }

    public static void main(String[] args) throws Exception {
        if (args.length == 0) {
            System.out.println("usage: CoreJarScan <jar>...");
            System.exit(2);
        }
        int hits = 0, classes = 0, scanned = 0;
        for (String path : args) {
            int jarClasses = 0;
            boolean hasHook = false;
            try (ZipFile zf = new ZipFile(new File(path))) {
                Enumeration<? extends ZipEntry> en = zf.entries();
                while (en.hasMoreElements()) {
                    ZipEntry e = en.nextElement();
                    if (e.isDirectory() || !e.getName().endsWith(".class")) continue;
                    jarClasses++;
                    if (e.getName().equals("dev/agentcraft/gtnh/api/Extensions.class")) hasHook = true;
                    byte[] data = read(zf.getInputStream(e));
                    for (String needle : FORBIDDEN) {
                        int at = indexOf(data, needle.getBytes(StandardCharsets.UTF_8));
                        if (at >= 0) {
                            int from = Math.max(0, at - 20), to = Math.min(data.length, at + 40);
                            String ctx = new String(data, from, to - from, StandardCharsets.ISO_8859_1).replaceAll("[^\\x20-\\x7e]", ".");
                            System.out.println("HIT '" + needle + "' in " + path + "!" + e.getName() + " near \"" + ctx + "\"");
                            hits++;
                        }
                    }
                }
            }
            scanned++;
            classes += jarClasses;
            System.out.println("scanned " + path + ": " + jarClasses + " classes" + (hasHook ? " (has the Extensions hook class)" : ""));
            if (jarClasses < 100 || !hasHook) {
                System.out.println("FAIL: " + path + " does not look like the core jar (needs 100+ classes and dev/agentcraft/gtnh/api/Extensions)");
                hits++;
            }
        }
        System.out.println("CoreJarScan: " + scanned + " jar(s), " + classes + " classes, forbidden " + String.join(", ", FORBIDDEN) + " -> " + (hits == 0 ? "CLEAN" : hits + " problem(s)"));
        System.exit(hits == 0 ? 0 : 1);
    }
}
