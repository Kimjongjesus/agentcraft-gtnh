package dev.agentcraft.gtnh.hq;

import java.io.File;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.Collections;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Pattern;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import dev.agentcraft.gtnh.AgentCraftGTNH;

/**
 * hq-anchors.json: Station -> coordinates + facing, maintained by Eli (by hand, or in-game with
 * /agentcraft anchor set). The mod never places blocks; it only reads these spots.
 *
 * <pre>
 * {
 *   "version": 1,
 *   "dimension": 0,
 *   "anchors": {
 *     "lounge":              {"x": 100.5, "y": 64, "z": 200.5, "facing": "south"},
 *     "desk_claude-builder": {"x": 104.5, "y": 64, "z": 196.5, "facing": "north"},
 *     "overflow_sign":       {"x": 99, "y": 65, "z": 201}
 *   }
 * }
 * </pre>
 *
 * "facing" is north/south/east/west; "yaw" (degrees, Minecraft convention) wins when both are given;
 * "pitch" is only used by cam_* anchors. Unknown keys are ignored. Server thread only.
 */
public final class HqAnchors {

    public static final Pattern NAME = Pattern.compile("[a-z0-9][a-z0-9_.:-]{0,63}");
    public static final String OVERFLOW_SIGN = "overflow_sign";

    private final File file;
    private final Map<String, Anchor> anchors = new TreeMap<>();
    private int dimension;
    private int revision;
    private String lastError = "";

    public HqAnchors(File file, int defaultDimension) {
        this.file = file;
        this.dimension = defaultDimension;
    }

    public File file() {
        return file;
    }

    public int dimension() {
        return dimension;
    }

    /** Bumped on every change (load, set, remove): NPC targets are recomputed when it moves. */
    public int revision() {
        return revision;
    }

    public String lastError() {
        return lastError;
    }

    public Map<String, Anchor> all() {
        return Collections.unmodifiableMap(anchors);
    }

    public Anchor get(String name) {
        return anchors.get(name);
    }

    public boolean isEmpty() {
        for (String n : anchors.keySet()) if (StationAssigner.isStandingAnchor(n)) return false;
        return true;
    }

    public void load() {
        anchors.clear();
        revision++;
        lastError = "";
        if (!file.isFile()) {
            AgentCraftGTNH.LOG.info("no HQ anchors file yet ({}); use /agentcraft anchor set <station>", file.getPath());
            return;
        }
        try (Reader r = Files.newBufferedReader(file.toPath(), StandardCharsets.UTF_8)) {
            JsonObject root = new JsonParser().parse(r)
                .getAsJsonObject();
            if (root.has("dimension")) dimension = root.get("dimension")
                .getAsInt();
            JsonObject list = root.has("anchors") && root.get("anchors")
                .isJsonObject() ? root.getAsJsonObject("anchors") : new JsonObject();
            for (Map.Entry<String, JsonElement> e : list.entrySet()) {
                String name = e.getKey()
                    .toLowerCase(java.util.Locale.ROOT);
                if (!NAME.matcher(name)
                    .matches() || !e.getValue()
                        .isJsonObject()) {
                    AgentCraftGTNH.LOG.warn("hq-anchors.json: skipping bad anchor entry '{}'", e.getKey());
                    continue;
                }
                JsonObject o = e.getValue()
                    .getAsJsonObject();
                if (!o.has("x") || !o.has("y") || !o.has("z")) {
                    AgentCraftGTNH.LOG.warn("hq-anchors.json: anchor '{}' needs x, y and z", name);
                    continue;
                }
                float yaw = 0.0F;
                if (o.has("facing")) {
                    Float f = Anchor.parseFacing(
                        o.get("facing")
                            .getAsString());
                    if (f != null) yaw = f;
                }
                if (o.has("yaw")) yaw = o.get("yaw")
                    .getAsFloat();
                float pitch = o.has("pitch") ? o.get("pitch")
                    .getAsFloat() : 0.0F;
                anchors.put(
                    name,
                    new Anchor(
                        name,
                        o.get("x")
                            .getAsDouble(),
                        o.get("y")
                            .getAsDouble(),
                        o.get("z")
                            .getAsDouble(),
                        yaw,
                        pitch));
            }
            AgentCraftGTNH.LOG.info("loaded {} HQ anchors (dimension {}) from {}", anchors.size(), dimension, file.getPath());
        } catch (IOException | RuntimeException e) {
            lastError = e.getClass()
                .getSimpleName() + ": " + e.getMessage();
            AgentCraftGTNH.LOG.error("could not read {}: {} (NPCs use the fallback spots)", file.getPath(), lastError);
        }
    }

    public void put(Anchor a, int dim) throws IOException {
        if (!anchors.isEmpty() && dim != dimension) {
            throw new IOException("the HQ is in dimension " + dimension + "; anchors cannot span dimensions");
        }
        dimension = dim;
        anchors.put(a.name, a);
        revision++;
        save();
    }

    public boolean remove(String name) throws IOException {
        if (anchors.remove(name) == null) return false;
        revision++;
        save();
        return true;
    }

    private void save() throws IOException {
        JsonObject root = new JsonObject();
        root.addProperty("version", 1);
        root.addProperty("dimension", dimension);
        JsonObject list = new JsonObject();
        for (Anchor a : anchors.values()) {
            JsonObject o = new JsonObject();
            o.addProperty("x", a.x);
            o.addProperty("y", a.y);
            o.addProperty("z", a.z);
            o.addProperty("facing", Anchor.facingName(a.yaw));
            o.addProperty("yaw", a.yaw);
            if (a.pitch != 0.0F) o.addProperty("pitch", a.pitch);
            list.add(a.name, o);
        }
        root.add("anchors", list);
        Gson gson = new GsonBuilder().setPrettyPrinting()
            .create();
        File dir = file.getAbsoluteFile()
            .getParentFile();
        if (dir != null && !dir.isDirectory() && !dir.mkdirs()) throw new IOException("cannot create " + dir);
        File tmp = new File(file.getPath() + ".tmp");
        try (Writer w = Files.newBufferedWriter(tmp.toPath(), StandardCharsets.UTF_8)) {
            w.write(gson.toJson(root));
            w.write("\n");
        }
        Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING);
    }
}
