package com.robertsnest.aifactory.client;

import static org.junit.Assert.*;

import org.junit.Test;

import com.google.gson.JsonParser;

public class OracleHudTest {

    @Test
    public void rendersFreshnessFaultsStockShiftAndInvalidatesOnDisconnect() {
        OracleHud hud = new OracleHud();
        assertTrue(
            hud.lines(0)
                .toString()
                .contains("unknown"));
        hud.acceptBase(
            new JsonParser().parse(
                "{\"scope\":{\"label\":\"[FIXTURE] base\"},\"freshness\":{\"status\":\"fresh\"},\"coverage\":{\"stock\":{\"status\":\"PARTIAL\"}},\"machines\":{\"faults\":[{\"name\":\"EBF\",\"problem\":\"ME channel missing\"}]},\"stock\":{\"top\":[{\"name\":\"wire\",\"quantity\":0}],\"power\":{\"powered\":false}}}")
                .getAsJsonObject(),
            null,
            1000);
        hud.acceptShift(
            new JsonParser().parse("{\"capturesInWindow\":24,\"observed\":{\"eventsTotal\":19}}")
                .getAsJsonObject(),
            null,
            1000);
        String text = hud.lines(2000)
            .toString();
        assertTrue(text, text.contains("[FIXTURE]"));
        assertTrue(text, text.contains("ME channel"));
        assertTrue(text, text.contains("wire"));
        assertTrue(text, text.contains("19"));
        assertTrue(text, text.contains("PARTIAL"));
        assertTrue(
            hud.lines(50000)
                .toString()
                .contains("stale"));
        hud.reset();
        assertFalse(
            hud.lines(51000)
                .toString()
                .contains("EBF"));
        assertTrue(
            hud.lines(51000)
                .toString()
                .contains("unknown"));
    }
}
