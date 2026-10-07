package com.robertsnest.aifactory.client;

import static org.junit.Assert.*;

import org.junit.Test;

public class OracleViewsTest {

    @Test
    public void tenFeatureTabsHaveOnlyReadRoutesAndEncodeInputs() {
        assertEquals(10, OracleViews.TABS.length);
        for (int tab = 0; tab < 10; tab++) {
            for (int mode = 0; mode < OracleViews.modes(tab).length; mode++) {
                String route = OracleViews.route(tab, mode, "EBF & wire", "2");
                assertTrue(route, OracleRequest.allowed(route));
                assertFalse(route, route.contains(" & "));
            }
        }
        assertEquals("/oracle/ask?persona=power&question=why%3F", OracleViews.route(2, 1, "why?", "power"));
        assertEquals("/oracle/blueprint/ghost?id=fixture-id", OracleViews.route(9, 0, "fixture-id", ""));
        assertEquals("/oracle/why?at=123&symptom=EBF", OracleViews.route(5, 1, "123", "EBF"));
    }
}
