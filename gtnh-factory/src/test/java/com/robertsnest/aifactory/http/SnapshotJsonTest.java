package com.robertsnest.aifactory.http;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.Test;

import com.robertsnest.aifactory.telemetry.BaseDesignSurvey;
import com.robertsnest.aifactory.telemetry.BaseScope;
import com.robertsnest.aifactory.telemetry.Coverage;
import com.robertsnest.aifactory.telemetry.FactorySnapshot;
import com.robertsnest.aifactory.telemetry.MeNetworkReader;
import com.robertsnest.aifactory.telemetry.MultiblockPart;
import com.robertsnest.aifactory.telemetry.MultiblockStatus;
import com.robertsnest.aifactory.telemetry.SourceSession;
import com.robertsnest.aifactory.telemetry.Surroundings;
import com.robertsnest.aifactory.telemetry.TelemetryCapture;

/**
 * Every rendered payload is parsed back by an independent strict parser
 * ({@link TestJsonParser}) and inspected structurally. Substring checks
 * alone were the previous test's weakness: correct fragments in malformed
 * nesting would have passed.
 */
public class SnapshotJsonTest {

    static final BaseScope SCOPE = new BaseScope(
        BaseScope.Kind.CONFIGURED,
        "main base",
        0,
        "Overworld",
        100,
        64,
        -200,
        48,
        24,
        null);

    static final SourceSession SESSION = new SourceSession("sess-1", 1000L, "test");

    static Coverage ok(long attempted) {
        return Coverage.builder()
            .status(Coverage.Status.OK)
            .attempted(attempted)
            .succeeded(attempted)
            .settle()
            .build();
    }

    static MultiblockStatus ebf() {
        return MultiblockStatus.builder("multimachine.blastfurnace", 0, 1, 2, 3)
            .name("Electric \"Blast\" Furnace \u00a7a")
            .formed(true)
            .active(true)
            .needsMaintenance(Boolean.FALSE)
            .euPerTick(-480L)
            .progress(50, 200)
            .energy(Long.valueOf(12345L), Long.valueOf(99999L))
            .efficiencyPercent(Integer.valueOf(100))
            .parts(
                Arrays.asList(
                    new MultiblockPart(MultiblockPart.PartType.ENERGY_HATCH, "Energy Hatch (IV)", 10, 64, 11, 5, null),
                    new MultiblockPart(
                        MultiblockPart.PartType.ME_INPUT_BUS,
                        "ME Input Bus",
                        12,
                        64,
                        11,
                        3,
                        Boolean.FALSE)))
            .build();
    }

    static TelemetryCapture capture() {
        List<FactorySnapshot.ItemStock> stock = Arrays.asList(
            new FactorySnapshot.ItemStock("gregtech:gt.metaitem.01@11001", "Copper Ingot", 64L, true),
            new FactorySnapshot.ItemStock("minecraft:wool@11#abcdef012345", "Blue Wool", 3L, false));
        MeNetworkReader.StockRead read = new MeNetworkReader.StockRead(
            stock,
            ok(2),
            Double.valueOf(1000.5d),
            Double.valueOf(2000d),
            Double.valueOf(12.25d),
            Double.valueOf(40d),
            Boolean.TRUE,
            Integer.valueOf(2),
            Integer.valueOf(1));
        BaseDesignSurvey survey = new BaseDesignSurvey(
            SCOPE,
            2,
            1,
            1,
            1,
            6000L,
            5000L,
            1200L,
            Arrays.asList(
                new BaseDesignSurvey.BlockUsage("gregtech:gt.blockcasings#16", "Solid Steel Casing", 900L),
                new BaseDesignSurvey.BlockUsage("minecraft:glass#0", "Glass", 300L)),
            new BaseDesignSurvey.ArtificialLightProfile(1000L, 700L, 300L, 0, 15),
            Coverage.builder()
                .status(Coverage.Status.OK)
                .attempted(6000)
                .succeeded(5000)
                .skippedUnloaded(1000)
                .returned(2)
                .distinctSeen(2)
                .settle()
                .build());
        Map<String, Integer> entities = new LinkedHashMap<String, Integer>();
        entities.put("EntityZombie", Integer.valueOf(2));
        Surroundings surroundings = new Surroundings(
            Long.valueOf(13000L),
            Long.valueOf(500000L),
            Boolean.FALSE,
            Boolean.FALSE,
            "Plains",
            Arrays.asList("Eli"),
            1,
            entities,
            Integer.valueOf(2),
            ok(10));
        return TelemetryCapture.builder()
            .session(SESSION)
            .worldName("world")
            .scope(SCOPE)
            .worldRevision(7L)
            .captureStartedAtMillis(1000L)
            .capturedAtMillis(1500L)
            .captureTicks(9)
            .captureSequence(3L)
            .machines(Arrays.asList(ebf()), ok(1))
            .stock(read)
            .design(survey, survey.coverage())
            .surroundings(surroundings)
            .warnings(Arrays.asList("fixture"))
            .build();
    }

    @Test
    public void escapesCharactersModdedNamesActuallyContainAndRoundTrips() {
        String original = "\u00a7aQuartz \"Glass\" \\ pane\nnew\ttab";
        String json = new Json().object()
            .put("name", original)
            .endObject()
            .toString();

        assertEquals(
            original,
            TestJsonParser.object(json)
                .get("name"));
    }

    @Test
    public void escapesControlCharactersAndRoundTrips() {
        StringBuilder all = new StringBuilder();
        for (char c = 0; c < 0x20; c++) {
            all.append(c);
        }
        String json = new Json().object()
            .put("name", all.toString())
            .endObject()
            .toString();

        assertEquals(
            all.toString(),
            TestJsonParser.object(json)
                .get("name"));
    }

    @Test
    public void nonBmpNamesSurviveAndLoneSurrogatesAreEscaped() {
        String emoji = "\uD83D\uDE00 ok";
        String lone = "bad \uD83D end";
        String json = new Json().object()
            .put("emoji", emoji)
            .put("lone", lone)
            .endObject()
            .toString();

        Map<String, Object> parsed = TestJsonParser.object(json);
        assertEquals(emoji, parsed.get("emoji"));
        assertEquals("the lone code unit is preserved via \\u escape, not replaced", lone, parsed.get("lone"));
        assertTrue(json.contains("\\ud83d end"));
        // The encoded bytes must be valid UTF-8 with no replacement character.
        byte[] bytes = json.getBytes(java.nio.charset.Charset.forName("UTF-8"));
        assertFalse(new String(bytes, java.nio.charset.Charset.forName("UTF-8")).contains("\uFFFD"));
    }

    @Test
    public void nullStringsBecomeJsonNullNotTheWordNull() {
        String json = new Json().object()
            .put("problem", (String) null)
            .endObject()
            .toString();

        Map<String, Object> parsed = TestJsonParser.object(json);
        assertTrue(parsed.containsKey("problem"));
        assertNull(parsed.get("problem"));
    }

    @Test
    public void tristateBooleansAndNullableNumbersSurviveSerialisation() {
        String json = new Json().object()
            .put("a", (Boolean) null)
            .put("b", Boolean.FALSE)
            .put("c", Boolean.TRUE)
            .put("d", (Long) null)
            .put("e", Long.valueOf(5L))
            .put("f", (Double) null)
            .put("g", Double.valueOf(1.5d))
            .endObject()
            .toString();

        Map<String, Object> parsed = TestJsonParser.object(json);
        assertNull(parsed.get("a"));
        assertEquals(Boolean.FALSE, parsed.get("b"));
        assertEquals(Boolean.TRUE, parsed.get("c"));
        assertNull(parsed.get("d"));
        assertEquals(Long.valueOf(5L), parsed.get("e"));
        assertNull(parsed.get("f"));
        assertEquals(Double.valueOf(1.5d), parsed.get("g"));
    }

    @Test
    public void nonFiniteNumbersBecomeNullRatherThanInvalidJson() {
        String json = new Json().object()
            .put("x", Double.NaN)
            .put("y", Double.POSITIVE_INFINITY)
            .endObject()
            .toString();

        Map<String, Object> parsed = TestJsonParser.object(json);
        assertNull(parsed.get("x"));
        assertNull(parsed.get("y"));
    }

    @Test
    public void emptyNestedShapesParse() {
        String json = new Json().object()
            .objectField("o")
            .endObject()
            .array("a")
            .endArray()
            .array("b")
            .object()
            .endObject()
            .value("s")
            .value(null)
            .endArray()
            .endObject()
            .toString();

        Map<String, Object> parsed = TestJsonParser.object(json);
        assertTrue(
            TestJsonParser.obj(parsed.get("o"))
                .isEmpty());
        assertTrue(
            TestJsonParser.arr(parsed.get("a"))
                .isEmpty());
        List<Object> b = TestJsonParser.arr(parsed.get("b"));
        assertEquals(3, b.size());
        assertEquals("s", b.get(1));
        assertNull(b.get(2));
    }

    @Test
    public void captureCarriesEnvelopeSessionScopeAndEverySectionCoverage() {
        Map<String, Object> root = TestJsonParser.object(SnapshotJson.capture(capture()));

        assertEquals("ai-factory/v2", root.get("protocol"));
        assertEquals(Long.valueOf(7L), root.get("worldRevision"));
        assertEquals(Long.valueOf(1500L), root.get("capturedAtMillis"));
        assertEquals(Long.valueOf(9L), root.get("captureTicks"));
        assertEquals(Long.valueOf(3L), root.get("captureSequence"));
        assertEquals("world/configured:dim0:100,64,-200:r48h24", root.get("baseId"));
        assertEquals(
            "sess-1",
            TestJsonParser.obj(root.get("session"))
                .get("id"));
        Map<String, Object> scope = TestJsonParser.obj(root.get("scope"));
        assertEquals("CONFIGURED", scope.get("kind"));
        assertEquals(Long.valueOf(0L), scope.get("dimensionId"));
        assertEquals(Long.valueOf(40L), scope.get("minY"));

        List<Object> machines = TestJsonParser.arr(root.get("multiblocks"));
        Map<String, Object> ebf = TestJsonParser.obj(machines.get(0));
        assertEquals("dim0:multimachine.blastfurnace@1,2,3", ebf.get("id"));
        assertEquals("Electric \"Blast\" Furnace \u00a7a", ebf.get("name"));
        assertEquals(Long.valueOf(-480L), ebf.get("euPerTick"));
        assertEquals(Long.valueOf(12345L), ebf.get("euStored"));
        assertEquals(Boolean.FALSE, ebf.get("needsMaintenance"));
        List<Object> parts = TestJsonParser.arr(ebf.get("parts"));
        assertEquals(2, parts.size());
        assertNull(
            TestJsonParser.obj(parts.get(0))
                .get("meChannelActive"));
        assertEquals(
            Boolean.FALSE,
            TestJsonParser.obj(parts.get(1))
                .get("meChannelActive"));
        assertEquals(
            "OK",
            TestJsonParser.obj(root.get("machineCoverage"))
                .get("status"));

        Map<String, Object> stock = TestJsonParser.obj(root.get("stock"));
        List<Object> items = TestJsonParser.arr(stock.get("items"));
        assertEquals(
            "minecraft:wool@11#abcdef012345",
            TestJsonParser.obj(items.get(1))
                .get("id"));
        assertEquals(
            Double.valueOf(1000.5d),
            TestJsonParser.obj(stock.get("power"))
                .get("stored"));
        assertEquals(
            Long.valueOf(1L),
            TestJsonParser.obj(stock.get("crafting"))
                .get("busyCpus"));
        assertEquals(
            Boolean.TRUE,
            TestJsonParser.obj(stock.get("coverage"))
                .get("complete"));

        Map<String, Object> design = TestJsonParser.obj(root.get("design"));
        assertEquals(Long.valueOf(2L), design.get("stride"));
        assertEquals(Long.valueOf(6000L), design.get("positionsAttempted"));
        Map<String, Object> light = TestJsonParser.obj(design.get("artificialLight"));
        assertEquals(Long.valueOf(300L), light.get("unlit"));
        assertFalse("no spawn-probability field may exist", light.containsKey("darkFraction"));
        assertFalse(light.containsKey("mobSpawnThreshold"));
        Map<String, Object> designCoverage = TestJsonParser.obj(design.get("coverage"));
        assertEquals("PARTIAL", designCoverage.get("status"));
        assertEquals(Long.valueOf(1000L), designCoverage.get("skippedUnloaded"));

        Map<String, Object> surroundings = TestJsonParser.obj(root.get("surroundings"));
        assertEquals("Plains", surroundings.get("biomeAtCenter"));
        assertEquals(
            Long.valueOf(2L),
            TestJsonParser.obj(surroundings.get("entityCounts"))
                .get("EntityZombie"));
        assertEquals(
            "Eli",
            TestJsonParser.arr(surroundings.get("playersInScope"))
                .get(0));
        assertEquals(
            "fixture",
            TestJsonParser.arr(root.get("warnings"))
                .get(0));
    }

    @Test
    public void legacySnapshotKeepsFlatShapeAndDerivesTruncated() {
        Map<String, Object> root = TestJsonParser.object(SnapshotJson.snapshot(capture()));

        assertEquals(Boolean.FALSE, root.get("truncated"));
        List<Object> stock = TestJsonParser.arr(root.get("stock"));
        assertEquals(
            Long.valueOf(64L),
            TestJsonParser.obj(stock.get(0))
                .get("quantity"));
        List<Object> machines = TestJsonParser.arr(root.get("machines"));
        assertEquals(
            Boolean.TRUE,
            TestJsonParser.obj(machines.get(0))
                .get("active"));
        assertEquals(
            "the fixture EBF has a dead ME channel, which is its problem",
            "ME channel inactive at 12,64,11",
            TestJsonParser.obj(machines.get(0))
                .get("problem"));
    }

    @Test
    public void unavailableSectionsAreNotEmptyHealthyOnes() {
        TelemetryCapture c = TelemetryCapture.builder()
            .session(SESSION)
            .scope(BaseScope.none("nothing"))
            .machines(Collections.<MultiblockStatus>emptyList(), Coverage.unavailable("gregtech_not_present"))
            .stock(MeNetworkReader.StockRead.unavailable("no_access_point_configured"))
            .build();
        Map<String, Object> root = TestJsonParser.object(SnapshotJson.capture(c));

        assertEquals(
            "NONE",
            TestJsonParser.obj(root.get("scope"))
                .get("kind"));
        Map<String, Object> mc = TestJsonParser.obj(root.get("machineCoverage"));
        assertEquals("UNAVAILABLE", mc.get("status"));
        assertEquals("gregtech_not_present", mc.get("reason"));
        assertEquals(Boolean.FALSE, mc.get("complete"));
        Map<String, Object> stock = TestJsonParser.obj(root.get("stock"));
        assertEquals(
            "UNAVAILABLE",
            TestJsonParser.obj(stock.get("coverage"))
                .get("status"));
        assertNull(
            TestJsonParser.obj(stock.get("power"))
                .get("stored"));
        assertEquals(
            "UNAVAILABLE",
            TestJsonParser.obj(
                TestJsonParser.obj(root.get("design"))
                    .get("coverage"))
                .get("status"));
        assertEquals(
            "UNAVAILABLE",
            TestJsonParser.obj(
                TestJsonParser.obj(root.get("surroundings"))
                    .get("coverage"))
                .get("status"));
        Map<String, Object> legacy = TestJsonParser.object(SnapshotJson.snapshot(c));
        assertEquals(
            "legacy flag must say partial when any section is unavailable",
            Boolean.TRUE,
            legacy.get("truncated"));
    }

    @Test
    public void healthCarriesFreshnessNotGameState() {
        Map<String, Object> h = TestJsonParser.object(
            SnapshotJson
                .health(5000L, 4900L, Long.valueOf(4000L), 12L, 3, true, false, Arrays.asList("TELEMETRY_SNAPSHOT")));

        assertEquals("ok", h.get("status"));
        assertEquals(Long.valueOf(100L), h.get("lastTickAgeMillis"));
        assertEquals(Long.valueOf(3L), h.get("httpRejected"));
        assertEquals(Boolean.FALSE, h.get("ae2"));
        assertEquals(
            "TELEMETRY_SNAPSHOT",
            TestJsonParser.arr(h.get("enabledOperations"))
                .get(0));
        Map<String, Object> never = TestJsonParser
            .object(SnapshotJson.health(5000L, 0L, null, 0L, 0, false, false, Collections.<String>emptyList()));
        assertEquals(Long.valueOf(-1L), never.get("lastTickAgeMillis"));
        assertNull(never.get("lastCaptureMillis"));
    }

    @Test
    public void errorsAreRenderedAsJson() {
        assertEquals("{\"protocol\":\"ai-factory/v2\",\"error\":\"unauthorized\"}", SnapshotJson.error("unauthorized"));
    }
}
