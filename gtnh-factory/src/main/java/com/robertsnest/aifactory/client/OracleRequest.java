package com.robertsnest.aifactory.client;

import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/** Fixed read-only route vocabulary. No arbitrary URL or world command input. */
public final class OracleRequest {

    private static final Set<String> READS = new HashSet<>(
        Arrays.asList(
            "base",
            "ask",
            "shift",
            "diagnose",
            "personas",
            "simulate",
            "improve",
            "overlay",
            "locate",
            "timeline",
            "why",
            "quests",
            "area/history",
            "tour",
            "crew/board",
            "blueprint/validate",
            "blueprint/ghost"));
    public static final String USAGE = "/oracle status | ask <persona> <question> | diagnose <machine> | shift";

    private OracleRequest() {}

    public static String command(String[] args) {
        if (args.length == 1 && args[0].equals("status")) return "/oracle/base";
        if (args.length == 1 && args[0].equals("shift")) return "/oracle/shift";
        if (args.length >= 2 && args[0].equals("diagnose")) return "/oracle/diagnose?symptom=" + encode(join(args, 1));
        if (args.length >= 3 && args[0].equals("ask"))
            return "/oracle/ask?persona=" + encode(args[1]) + "&question=" + encode(join(args, 2));
        throw new IllegalArgumentException(USAGE);
    }

    private static String join(String[] args, int start) {
        String value = String.join(" ", Arrays.copyOfRange(args, start, args.length))
            .trim();
        if (value.isEmpty() || value.length() > 2000)
            throw new IllegalArgumentException("Input must contain 1..2000 characters");
        return value;
    }

    public static String encode(String value) {
        try {
            return URLEncoder.encode(value, "UTF-8");
        } catch (UnsupportedEncodingException impossible) {
            throw new AssertionError(impossible);
        }
    }

    public static boolean allowed(String route) {
        String path = route.split("\\?", 2)[0];
        return route.length() <= 16384 && !route.contains("#")
            && path.startsWith("/oracle/")
            && READS.contains(path.substring(8));
    }
}
