package com.robertsnest.aifactory.client;

/** Ten read-only feature views. Labels describe inputs, never raw URLs. */
public final class OracleViews {

    public static final String[] TABS = { "Shift", "Detective", "Persona", "What-if", "X-ray", "Recorder", "Quests",
        "Historian", "Crew", "Blueprint" };
    private static final String[][] MODES = { { "Shift", "Status" }, { "Diagnose" }, { "Personas", "Ask" },
        { "Simulate", "Improve" }, { "Overlay", "Locate" }, { "Timeline", "Why then" }, { "Objectives" },
        { "Tour", "Area history" }, { "Board" }, { "Ghost", "Validate" } };

    private OracleViews() {}

    public static String[] modes(int tab) {
        return MODES[tab].clone();
    }

    public static String[] labels(int tab, int mode) {
        switch (tab) {
            case 1:
                return new String[] { "Machine or symptom", "Unused" };
            case 2:
                return mode == 1 ? new String[] { "Question", "Persona (plain/power/chem/storage/terminal)" }
                    : new String[] { "Unused", "Unused" };
            case 3:
                return mode == 0 ? new String[] { "Item name", "Multiplier (default 2)" }
                    : new String[] { "Unused", "Unused" };
            case 4:
                return new String[] { mode == 1 ? "Machine ID" : "Unused", "Unused" };
            case 5:
                return new String[] { "Timestamp in milliseconds (blank = now)", mode == 1 ? "Symptom" : "Unused" };
            case 6:
                return new String[] { "Player (blank = credential owner)", "Unused" };
            case 7:
                return new String[] { mode == 1 ? "Area ID" : "Unused", "Unused" };
            case 9:
                return new String[] { "Existing blueprint proposal ID", "Unused" };
            default:
                return new String[] { "Unused", "Unused" };
        }
    }

    public static String route(int tab, int mode, String first, String second) {
        if (tab < 0 || tab >= TABS.length || mode < 0 || mode >= MODES[tab].length)
            throw new IllegalArgumentException("Unknown view");
        if (first.length() > 2000 || second.length() > 2000) throw new IllegalArgumentException("Input too long");
        String a = OracleRequest.encode(first.trim());
        String b = OracleRequest.encode(second.trim());
        switch (tab) {
            case 0:
                return mode == 0 ? "/oracle/shift" : "/oracle/base";
            case 1:
                return "/oracle/diagnose?symptom=" + a;
            case 2:
                return mode == 0 ? "/oracle/personas"
                    : "/oracle/ask?persona=" + (b.isEmpty() ? "plain" : b) + "&question=" + a;
            case 3:
                return mode == 0 ? "/oracle/simulate?item=" + a + "&multiplier=" + (b.isEmpty() ? "2" : b)
                    : "/oracle/improve";
            case 4:
                return mode == 0 ? "/oracle/overlay" : "/oracle/locate?id=" + a;
            case 5:
                return mode == 0 ? "/oracle/timeline" + (a.isEmpty() ? "" : "?around=" + a)
                    : "/oracle/why?" + (a.isEmpty() ? "" : "at=" + a + "&") + "symptom=" + b;
            case 6:
                return "/oracle/quests" + (a.isEmpty() ? "" : "?player=" + a);
            case 7:
                return mode == 0 ? "/oracle/tour" : "/oracle/area/history?id=" + a;
            case 8:
                return "/oracle/crew/board";
            case 9:
                return "/oracle/blueprint/" + (mode == 0 ? "ghost" : "validate") + "?id=" + a;
            default:
                throw new IllegalArgumentException("Unknown view");
        }
    }
}
