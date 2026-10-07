package com.robertsnest.aifactory.telemetry;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Cheap environmental facts about the scoped region: time, weather, biome at
 * the centre, players present, and bounded entity counts.
 *
 * <p>
 * Entity counts are a bounded inspection of the loaded entity list filtered
 * to the scope box, not a census of the world, and the coverage says how many
 * entities were examined. Player names are untrusted data carried verbatim.
 */
public final class Surroundings {

    private final Long worldTime;
    private final Long totalWorldTime;
    private final Boolean raining;
    private final Boolean thundering;
    private final String biomeAtCenter;
    private final List<String> playersInScope;
    private final int playersOnline;
    private final Map<String, Integer> entityCounts;
    private final Integer hostileCount;
    private final Coverage coverage;

    public Surroundings(Long worldTime, Long totalWorldTime, Boolean raining, Boolean thundering, String biomeAtCenter,
        List<String> playersInScope, int playersOnline, Map<String, Integer> entityCounts, Integer hostileCount,
        Coverage coverage) {
        this.worldTime = worldTime;
        this.totalWorldTime = totalWorldTime;
        this.raining = raining;
        this.thundering = thundering;
        this.biomeAtCenter = biomeAtCenter;
        this.playersInScope = Collections.unmodifiableList(
            new ArrayList<String>(playersInScope == null ? Collections.<String>emptyList() : playersInScope));
        this.playersOnline = playersOnline;
        this.entityCounts = Collections.unmodifiableMap(
            new LinkedHashMap<String, Integer>(
                entityCounts == null ? Collections.<String, Integer>emptyMap() : entityCounts));
        this.hostileCount = hostileCount;
        this.coverage = coverage == null ? Coverage.error("no coverage recorded") : coverage;
    }

    public static Surroundings unavailable(String reason) {
        return new Surroundings(null, null, null, null, null, null, 0, null, null, Coverage.unavailable(reason));
    }

    /** Time of day in ticks (0..24000), or null. */
    public Long worldTime() {
        return worldTime;
    }

    public Long totalWorldTime() {
        return totalWorldTime;
    }

    public Boolean raining() {
        return raining;
    }

    public Boolean thundering() {
        return thundering;
    }

    public String biomeAtCenter() {
        return biomeAtCenter;
    }

    public List<String> playersInScope() {
        return playersInScope;
    }

    public int playersOnline() {
        return playersOnline;
    }

    /** Entity class simple name to count, within scope, bounded. */
    public Map<String, Integer> entityCounts() {
        return entityCounts;
    }

    /** Entities implementing the hostile marker interface, or null when unknown. */
    public Integer hostileCount() {
        return hostileCount;
    }

    public Coverage coverage() {
        return coverage;
    }
}
