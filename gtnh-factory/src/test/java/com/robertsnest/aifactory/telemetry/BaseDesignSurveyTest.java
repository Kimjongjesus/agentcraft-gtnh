package com.robertsnest.aifactory.telemetry;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.Test;

public class BaseDesignSurveyTest {

    private static final BaseScope SCOPE = new BaseScope(
        BaseScope.Kind.CONFIGURED,
        "test",
        0,
        "Overworld",
        100,
        64,
        -200,
        48,
        24,
        null);

    private static BaseDesignSurvey survey(long sampled, long solid, List<BaseDesignSurvey.BlockUsage> palette,
        BaseDesignSurvey.ArtificialLightProfile lighting, Coverage coverage) {
        return new BaseDesignSurvey(SCOPE, 2, 0, 0, 0, sampled, sampled, solid, palette, lighting, coverage);
    }

    private static Coverage ok() {
        return Coverage.builder()
            .status(Coverage.Status.OK)
            .settle()
            .build();
    }

    private static BaseDesignSurvey.BlockUsage use(String id, long count) {
        return new BaseDesignSurvey.BlockUsage(id, id, count);
    }

    @Test
    public void paletteIsOrderedByUseSoTheDominantMaterialLeads() {
        BaseDesignSurvey s = survey(
            1000L,
            300L,
            java.util.Arrays
                .asList(use("minecraft:glass#0", 20L), use("gt:casing#3", 200L), use("minecraft:stone#0", 80L)),
            null,
            ok());

        assertEquals(
            "gt:casing#3",
            s.palette()
                .get(0)
                .blockId());
        assertEquals(
            "minecraft:stone#0",
            s.palette()
                .get(1)
                .blockId());
        assertEquals(
            "minecraft:glass#0",
            s.palette()
                .get(2)
                .blockId());
    }

    @Test
    public void equalCountsBreakTiesByIdSoRepeatSurveysMatch() {
        BaseDesignSurvey s = survey(
            100L,
            20L,
            java.util.Arrays.asList(use("zzz:block#0", 10L), use("aaa:block#0", 10L)),
            null,
            ok());

        assertEquals(
            "aaa:block#0",
            s.palette()
                .get(0)
                .blockId());
    }

    @Test
    public void dominantShareDescribesHowMonolithicTheBuildIs() {
        BaseDesignSurvey mono = survey(
            1000L,
            100L,
            java.util.Arrays.asList(use("minecraft:cobblestone#0", 95L), use("minecraft:torch#0", 5L)),
            null,
            ok());
        BaseDesignSurvey varied = survey(
            1000L,
            100L,
            java.util.Arrays.asList(use("a", 25L), use("b", 25L), use("c", 25L), use("d", 25L)),
            null,
            ok());

        assertTrue("a cobble box scores high", mono.dominantShare() > 0.9d);
        assertTrue("a varied palette scores low", varied.dominantShare() < 0.3d);
    }

    @Test
    public void densityIsTheSolidFractionOfWhatWasSampled() {
        assertEquals(0.25d, survey(400L, 100L, null, null, ok()).density(), 0.0001d);
    }

    @Test
    public void anEmptySurveyDoesNotDivideByZero() {
        BaseDesignSurvey empty = survey(0L, 0L, null, null, Coverage.unavailable("x"));

        assertEquals(0.0d, empty.density(), 0.0001d);
        assertEquals(0.0d, empty.dominantShare(), 0.0001d);
        assertEquals(
            0.0d,
            empty.lighting()
                .unlitFraction(),
            0.0001d);
        assertEquals(0, empty.paletteReturned());
    }

    @Test
    public void lightProfileIsArtificialLightNotSpawnability() {
        BaseDesignSurvey.ArtificialLightProfile lighting = new BaseDesignSurvey.ArtificialLightProfile(
            400L,
            300L,
            100L,
            0,
            15);

        assertEquals(0.25d, lighting.unlitFraction(), 0.0001d);
        assertEquals(
            "1.7.10 block-light spawn floor is context, not a verdict",
            8,
            BaseDesignSurvey.ArtificialLightProfile.BLOCK_LIGHT_SPAWN_FLOOR);
    }

    @Test
    public void paletteReturnedIsDistinctFromDistinctSeen() {
        Coverage capped = Coverage.builder()
            .status(Coverage.Status.OK)
            .returned(2)
            .distinctSeen(9)
            .settle()
            .build();
        BaseDesignSurvey s = survey(100L, 50L, java.util.Arrays.asList(use("a", 30L), use("b", 20L)), null, capped);

        assertEquals(2, s.paletteReturned());
        assertEquals("a capped palette must not claim it saw only two variants", 9L, s.paletteDistinctSeen());
        assertEquals(
            Coverage.Status.PARTIAL,
            s.coverage()
                .status());
        assertFalse(
            s.coverage()
                .complete());
    }

    @Test
    public void paletteFromCountsSortsAndCaps() {
        Map<String, Long> counts = new LinkedHashMap<String, Long>();
        counts.put("a", Long.valueOf(5L));
        counts.put("b", Long.valueOf(50L));
        counts.put("c", Long.valueOf(25L));
        Map<String, String> names = new HashMap<String, String>();
        names.put("b", "Beta Block");

        List<BaseDesignSurvey.BlockUsage> palette = BaseDesignSurvey.paletteFrom(counts, names, 2);

        assertEquals("capped", 2, palette.size());
        assertEquals(
            "b",
            palette.get(0)
                .blockId());
        assertEquals(
            "display name used when known",
            "Beta Block",
            palette.get(0)
                .displayName());
        assertEquals(
            "id used as the name when unknown",
            "c",
            palette.get(1)
                .displayName());
    }

    @Test
    public void paletteFromToleratesNoInput() {
        assertTrue(
            BaseDesignSurvey.paletteFrom(null, null, 10)
                .isEmpty());
    }

    @Test
    public void surveyDefendsAgainstMalformedUsage() {
        try {
            new BaseDesignSurvey.BlockUsage("", "x", 1L);
            fail("expected a rejection for a blank block ID");
        } catch (IllegalArgumentException expected) {
            assertTrue(
                expected.getMessage()
                    .contains("block ID"));
        }
        try {
            new BaseDesignSurvey.BlockUsage("a", "x", -1L);
            fail("expected a rejection for a negative count");
        } catch (IllegalArgumentException expected) {
            assertTrue(
                expected.getMessage()
                    .contains("count"));
        }
    }

    @Test
    public void paletteIsImmutable() {
        BaseDesignSurvey s = survey(10L, 5L, java.util.Arrays.asList(use("a", 1L)), null, ok());
        try {
            s.palette()
                .add(use("b", 2L));
            fail("palette must not be modifiable");
        } catch (UnsupportedOperationException expected) {
            assertTrue(true);
        }
    }

    @Test
    public void missingCoverageIsAnErrorNotOk() {
        BaseDesignSurvey s = survey(10L, 5L, null, null, null);
        assertEquals(
            Coverage.Status.ERROR,
            s.coverage()
                .status());
    }
}
