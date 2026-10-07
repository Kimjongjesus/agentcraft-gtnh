package com.robertsnest.aifactory.telemetry;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Supplies raw ME-network contents, abstracted away from AE2's own types.
 *
 * <p>
 * <b>Why this interface exists.</b> AE2 is a {@code compileOnly} dependency
 * and its classes are absent both in unit tests and on a server without AE2
 * installed. Keeping the collection logic behind this seam means it can be
 * tested for real, while the only untestable part is the thin adapter that
 * talks to AE2.
 *
 * <p>
 * <b>Availability and failure are one result, not a preflight boolean.</b> The
 * old shape asked {@code isAvailable()} first and then {@code readStock()},
 * and a network that vanished between the two calls came back as a complete
 * empty list. Every read now returns its own {@link Coverage}.
 *
 * <p>
 * Implementations are called <em>on the server thread</em> and must not
 * block, sleep or perform I/O.
 */
public interface MeNetworkReader {

    /** What one read produced and how much of the network it represents. */
    final class StockRead {

        private final List<FactorySnapshot.ItemStock> stock;
        private final Coverage coverage;
        private final Double storedPower;
        private final Double maxStoredPower;
        private final Double avgPowerUsage;
        private final Double avgPowerInjection;
        private final Boolean networkPowered;
        private final Integer craftingCpus;
        private final Integer busyCraftingCpus;

        public StockRead(List<FactorySnapshot.ItemStock> stock, Coverage coverage) {
            this(stock, coverage, null, null, null, null, null, null, null);
        }

        public StockRead(List<FactorySnapshot.ItemStock> stock, Coverage coverage, Double storedPower,
            Double maxStoredPower, Double avgPowerUsage, Double avgPowerInjection, Boolean networkPowered,
            Integer craftingCpus, Integer busyCraftingCpus) {
            this.stock = Collections.unmodifiableList(
                new ArrayList<FactorySnapshot.ItemStock>(
                    stock == null ? Collections.<FactorySnapshot.ItemStock>emptyList() : stock));
            this.coverage = coverage == null ? Coverage.error("no coverage recorded") : coverage;
            this.storedPower = storedPower;
            this.maxStoredPower = maxStoredPower;
            this.avgPowerUsage = avgPowerUsage;
            this.avgPowerInjection = avgPowerInjection;
            this.networkPowered = networkPowered;
            this.craftingCpus = craftingCpus;
            this.busyCraftingCpus = busyCraftingCpus;
        }

        public static StockRead unavailable(String reason) {
            return new StockRead(null, Coverage.unavailable(reason));
        }

        public static StockRead error(String reason) {
            return new StockRead(null, Coverage.error(reason));
        }

        public List<FactorySnapshot.ItemStock> stock() {
            return stock;
        }

        public Coverage coverage() {
            return coverage;
        }

        /** AE energy in the network's buffer, or null when not readable. */
        public Double storedPower() {
            return storedPower;
        }

        public Double maxStoredPower() {
            return maxStoredPower;
        }

        public Double avgPowerUsage() {
            return avgPowerUsage;
        }

        public Double avgPowerInjection() {
            return avgPowerInjection;
        }

        public Boolean networkPowered() {
            return networkPowered;
        }

        public Integer craftingCpus() {
            return craftingCpus;
        }

        public Integer busyCraftingCpus() {
            return busyCraftingCpus;
        }
    }

    /**
     * Read the network's contents.
     *
     * @param cap    the most entries the caller will accept; implementations
     *               keep the {@code cap} largest quantities seen within their
     *               traversal budget rather than an arbitrary prefix
     * @param budget bounds the traversal; exhausting it marks the read partial
     */
    StockRead read(int cap, WorkBudget budget);
}
