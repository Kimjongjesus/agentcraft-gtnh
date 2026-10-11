package dev.agentcraft.gtnh.write.proto;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Set;

/**
 * The shared HMAC key file: one line of 64 or more hex characters (at least 32 key bytes); blank
 * lines and lines starting with # are ignored; a second data line is refused. On POSIX a file
 * readable or writable by group or others is refused. There is no other way to give the key.
 */
public final class KeyFile {

    private KeyFile() {}

    public static byte[] load(Path path) throws IOException {
        if (path == null) throw new IOException("no key file configured (write.keyFile)");
        if (!Files.isRegularFile(path)) throw new IOException("key file not found");
        try {
            Set<PosixFilePermission> perms = Files.getPosixFilePermissions(path);
            for (PosixFilePermission p : perms) {
                switch (p) {
                    case GROUP_READ:
                    case GROUP_WRITE:
                    case GROUP_EXECUTE:
                    case OTHERS_READ:
                    case OTHERS_WRITE:
                    case OTHERS_EXECUTE:
                        throw new IOException("key file is accessible by group or others; chmod 600 it");
                    default:
                }
            }
        } catch (UnsupportedOperationException e) {
            // not a POSIX file system: nothing to check here
        }
        if (Files.size(path) > 4096) throw new IOException("key file too large");
        String hex = null;
        for (String line : new String(Files.readAllBytes(path), StandardCharsets.UTF_8).split("\n")) {
            String t = line.trim();
            if (t.isEmpty() || t.startsWith("#")) continue;
            if (hex != null) throw new IOException("key file must hold a single key line");
            hex = t;
        }
        if (hex == null) throw new IOException("key file is empty");
        if (hex.length() < 64 || hex.length() % 2 != 0) throw new IOException("key must be 64 or more hex characters (even count)");
        try {
            return Hex.decode(hex);
        } catch (IllegalArgumentException e) {
            throw new IOException("key is not hex");
        }
    }
}
