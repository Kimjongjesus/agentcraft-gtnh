package com.robertsnest.aifactory.telemetry;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class BaseScopeAndCoverageTest {

    @Test
    public void scopeContainsChecksDimensionFirst() {
        BaseScope s = new BaseScope(BaseScope.Kind.CONFIGURED, "b", 0, "Overworld", 0, 64, 0, 10, 5, null);
        assertTrue(s.contains(0, 5, 66, -10));
        assertFalse("same coordinates in the Nether are out of scope", s.contains(-1, 5, 66, -10));
        assertFalse(s.contains(0, 11, 64, 0));
        assertFalse(s.contains(0, 0, 70, 0));
        assertTrue(s.contains(0, -10, 59, 10));
    }

    @Test
    public void noneScopeContainsNothingAndHasStableIdentity() {
        BaseScope none = BaseScope.none("x");
        assertFalse(none.defined());
        assertFalse(none.contains(0, 0, 0, 0));
        assertEquals("none", none.identity());
    }

    @Test
    public void identityChangesWithDimensionKindAndBox() {
        BaseScope a = new BaseScope(BaseScope.Kind.CONFIGURED, "b", 0, "Overworld", 0, 64, 0, 10, 5, null);
        BaseScope b = new BaseScope(BaseScope.Kind.CONFIGURED, "b", 1, "End", 0, 64, 0, 10, 5, null);
        BaseScope c = new BaseScope(BaseScope.Kind.PLAYER_RELATIVE, "b", 0, "Overworld", 0, 64, 0, 10, 5, "Player1");
        BaseScope d = new BaseScope(BaseScope.Kind.CONFIGURED, "b", 0, "Overworld", 0, 64, 0, 11, 5, null);
        assertNotEquals(a.identity(), b.identity());
        assertNotEquals(a.identity(), c.identity());
        assertNotEquals(a.identity(), d.identity());
        assertEquals("configured:dim0:0,64,0:r10h5", a.identity());
        assertEquals("Player1", c.anchor());
    }

    @Test
    public void yIsClampedToTheWorld() {
        BaseScope s = new BaseScope(BaseScope.Kind.CONFIGURED, "b", 0, "Overworld", 0, 5, 0, 10, 100, null);
        assertEquals(0, s.minY());
        assertEquals(105, s.maxY());
        BaseScope top = new BaseScope(BaseScope.Kind.CONFIGURED, "b", 0, "Overworld", 0, 250, 0, 10, 100, null);
        assertEquals(255, top.maxY());
    }

    @Test
    public void coverageSettlesPartialFromCountsNotFromACallerFlag() {
        Coverage clean = Coverage.builder()
            .status(Coverage.Status.OK)
            .attempted(10)
            .succeeded(10)
            .settle()
            .build();
        assertEquals(Coverage.Status.OK, clean.status());
        assertTrue(clean.complete());

        Coverage errored = Coverage.builder()
            .status(Coverage.Status.OK)
            .attempted(10)
            .succeeded(9)
            .errors(1)
            .settle()
            .build();
        assertEquals(Coverage.Status.PARTIAL, errored.status());
        assertFalse(errored.complete());

        Coverage capped = Coverage.builder()
            .status(Coverage.Status.OK)
            .returned(40)
            .distinctSeen(41)
            .settle()
            .build();
        assertEquals(Coverage.Status.PARTIAL, capped.status());

        Coverage unloaded = Coverage.builder()
            .status(Coverage.Status.OK)
            .skippedUnloaded(1)
            .settle()
            .build();
        assertEquals(Coverage.Status.PARTIAL, unloaded.status());
    }

    @Test
    public void unavailableAndErrorAreNeverUsableAsData() {
        assertFalse(
            Coverage.unavailable("x")
                .usable());
        assertFalse(
            Coverage.error("x")
                .usable());
        assertFalse(
            Coverage.unavailable("x")
                .complete());
        assertEquals(
            Coverage.Status.UNAVAILABLE,
            Coverage.unavailable("x")
                .toBuilder()
                .settle()
                .build()
                .status());
    }

    @Test
    public void sourceSessionRequiresAnId() {
        try {
            new SourceSession(" ", 0L, "v");
            assertTrue(false);
        } catch (IllegalArgumentException expected) {
            assertTrue(true);
        }
        SourceSession a = SourceSession.start("v", 1L);
        SourceSession b = SourceSession.start("v", 1L);
        assertNotEquals("two server starts are two sessions", a.sessionId(), b.sessionId());
    }
}
