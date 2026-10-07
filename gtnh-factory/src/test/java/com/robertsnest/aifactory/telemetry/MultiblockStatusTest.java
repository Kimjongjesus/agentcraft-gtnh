package com.robertsnest.aifactory.telemetry;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import org.junit.Test;

public class MultiblockStatusTest {

    private static MultiblockPart part(MultiblockPart.PartType type, int x) {
        return new MultiblockPart(type, type.name(), x, 64, 0, 3, null);
    }

    private static MultiblockPart mePart(MultiblockPart.PartType type, Boolean channelActive) {
        return new MultiblockPart(type, "ME " + type.name(), 10, 64, 10, 5, channelActive);
    }

    private static MultiblockStatus status(boolean formed, Boolean maintenance, List<MultiblockPart> parts) {
        return MultiblockStatus.builder("multimachine.blastfurnace", 0, 1, 2, 3)
            .name("Electric Blast Furnace")
            .formed(formed)
            .active(false)
            .needsMaintenance(maintenance)
            .euPerTick(-480L)
            .parts(parts)
            .build();
    }

    @Test
    public void identityIsDimensionQualifiedAndUntranslated() {
        MultiblockStatus overworld = status(true, Boolean.FALSE, null);
        MultiblockStatus nether = MultiblockStatus.builder("multimachine.blastfurnace", -1, 1, 2, 3)
            .name("Electric Blast Furnace")
            .build();

        assertEquals("dim0:multimachine.blastfurnace@1,2,3", overworld.machineId());
        assertEquals("dim-1:multimachine.blastfurnace@1,2,3", nether.machineId());
        assertFalse(
            "same name and coordinates in two dimensions are two machines",
            overworld.machineId()
                .equals(nether.machineId()));
        assertFalse(
            "localised display text is not part of the identity",
            overworld.machineId()
                .contains("Electric"));
    }

    @Test
    public void partsAreGroupedAndCountedByType() {
        MultiblockStatus s = status(
            true,
            Boolean.FALSE,
            Arrays.asList(
                part(MultiblockPart.PartType.ENERGY_HATCH, 1),
                part(MultiblockPart.PartType.ENERGY_HATCH, 2),
                part(MultiblockPart.PartType.INPUT_BUS, 3)));

        assertEquals(
            2,
            s.partsOfType(MultiblockPart.PartType.ENERGY_HATCH)
                .size());
        assertEquals(
            1,
            s.partsOfType(MultiblockPart.PartType.INPUT_BUS)
                .size());
        assertTrue(
            s.partsOfType(MultiblockPart.PartType.MUFFLER_HATCH)
                .isEmpty());

        Map<MultiblockPart.PartType, Integer> counts = s.partCounts();
        assertEquals(Integer.valueOf(2), counts.get(MultiblockPart.PartType.ENERGY_HATCH));
        assertEquals(Integer.valueOf(1), counts.get(MultiblockPart.PartType.INPUT_BUS));
    }

    @Test
    public void hasPartAnswersPresenceWithoutBuildingAList() {
        MultiblockStatus s = status(true, Boolean.FALSE, Arrays.asList(part(MultiblockPart.PartType.ENERGY_HATCH, 1)));

        assertTrue(s.hasPart(MultiblockPart.PartType.ENERGY_HATCH));
        assertFalse(s.hasPart(MultiblockPart.PartType.DYNAMO_HATCH));
        assertFalse(s.hasPart(MultiblockPart.PartType.ME_INPUT_BUS));
    }

    @Test
    public void partsCarryTheirWorldPosition() {
        MultiblockPart hatch = new MultiblockPart(
            MultiblockPart.PartType.ENERGY_HATCH,
            "Energy Hatch (IV)",
            -120,
            64,
            301,
            5,
            null);

        assertEquals(-120, hatch.x());
        assertEquals(64, hatch.y());
        assertEquals(301, hatch.z());
        assertEquals("tier is reported for advice about voltage", 5, hatch.tier());
        assertTrue(
            hatch.toString()
                .contains("-120,64,301"));
    }

    @Test
    public void anUnformedStructureIsTheFirstProblemReported() {
        MultiblockStatus s = status(false, Boolean.TRUE, Collections.<MultiblockPart>emptyList());

        assertFalse(s.formed());
        assertEquals("structure not formed", s.describeProblem());
    }

    @Test
    public void maintenanceIsReportedOnAFormedMachine() {
        MultiblockStatus s = status(true, Boolean.TRUE, Arrays.asList(part(MultiblockPart.PartType.ENERGY_HATCH, 1)));

        assertEquals("needs maintenance", s.describeProblem());
    }

    @Test
    public void namedMaintenanceIssuesAreListed() {
        MultiblockStatus s = MultiblockStatus.builder("m", 0, 0, 0, 0)
            .formed(true)
            .needsMaintenance(Boolean.TRUE)
            .maintenanceIssues(Arrays.asList("wrench", "soft_mallet"))
            .parts(Arrays.asList(part(MultiblockPart.PartType.ENERGY_HATCH, 1)))
            .build();

        assertEquals("needs maintenance: wrench, soft_mallet", s.describeProblem());
    }

    @Test
    public void unknownMaintenanceIsNotReportedAsHealthy() {
        // Regression for M6: a failed maintenance query used to become
        // needsMaintenance:false. Unknown is unknown.
        MultiblockStatus s = status(true, null, Arrays.asList(part(MultiblockPart.PartType.ENERGY_HATCH, 1)));

        assertNull(s.needsMaintenance());
        assertEquals("maintenance state unknown", s.describeProblem());
    }

    @Test
    public void aFormedMachineWithNoPowerInputIsDiagnosed() {
        MultiblockStatus s = status(true, Boolean.FALSE, Arrays.asList(part(MultiblockPart.PartType.INPUT_BUS, 1)));

        assertEquals("no energy or dynamo hatch", s.describeProblem());
    }

    @Test
    public void aGeneratorWithOnlyADynamoHatchIsNotReportedAsUnpowered() {
        MultiblockStatus s = status(true, Boolean.FALSE, Arrays.asList(part(MultiblockPart.PartType.DYNAMO_HATCH, 1)));

        assertNull("a dynamo hatch is a valid power connection", s.describeProblem());
    }

    @Test
    public void aDeadMeChannelIsSurfacedWithItsLocation() {
        MultiblockStatus s = status(
            true,
            Boolean.FALSE,
            Arrays.asList(
                part(MultiblockPart.PartType.ENERGY_HATCH, 1),
                mePart(MultiblockPart.PartType.ME_INPUT_HATCH, Boolean.FALSE)));

        MultiblockPart fault = s.firstMeChannelFault();
        assertNotNull(fault);
        assertEquals("ME channel inactive at 10,64,10", s.describeProblem());
    }

    @Test
    public void aHealthyMeHatchIsNotAFault() {
        MultiblockStatus s = status(
            true,
            Boolean.FALSE,
            Arrays.asList(
                part(MultiblockPart.PartType.ENERGY_HATCH, 1),
                mePart(MultiblockPart.PartType.ME_INPUT_HATCH, Boolean.TRUE)));

        assertNull(s.firstMeChannelFault());
        assertNull(s.describeProblem());
    }

    @Test
    public void aPlainHatchWithoutAChannelIsNotTreatedAsBroken() {
        MultiblockPart plain = part(MultiblockPart.PartType.INPUT_HATCH, 1);
        MultiblockStatus s = status(
            true,
            Boolean.FALSE,
            Arrays.asList(part(MultiblockPart.PartType.ENERGY_HATCH, 2), plain));

        assertNull(plain.meChannelActive());
        assertFalse(plain.isMeBacked());
        assertNull(s.firstMeChannelFault());
        assertNull(s.describeProblem());
    }

    @Test
    public void meBackedPartsAreDistinguishedFromPlainOnes() {
        assertTrue(mePart(MultiblockPart.PartType.ME_INPUT_HATCH, Boolean.TRUE).isMeBacked());
        assertTrue(mePart(MultiblockPart.PartType.ME_INPUT_BUS, Boolean.TRUE).isMeBacked());
        assertFalse(part(MultiblockPart.PartType.INPUT_HATCH, 1).isMeBacked());
        assertFalse(part(MultiblockPart.PartType.INPUT_BUS, 1).isMeBacked());
    }

    @Test
    public void progressIsReportedAsAFraction() {
        MultiblockStatus running = MultiblockStatus.builder("m", 0, 0, 0, 0)
            .formed(true)
            .active(true)
            .progress(50, 200)
            .build();
        MultiblockStatus idle = MultiblockStatus.builder("m", 0, 0, 0, 0)
            .formed(true)
            .build();

        assertEquals(0.25d, running.progressFraction(), 0.0001d);
        assertEquals("an idle machine must not divide by zero", 0.0d, idle.progressFraction(), 0.0001d);
    }

    @Test
    public void progressIsClampedWhenTheGameReportsOvershoot() {
        MultiblockStatus overshoot = MultiblockStatus.builder("m", 0, 0, 0, 0)
            .formed(true)
            .progress(500, 200)
            .build();

        assertEquals(1.0d, overshoot.progressFraction(), 0.0001d);
    }

    @Test
    public void energyFieldsAreNullWhenNotMeasured() {
        MultiblockStatus s = MultiblockStatus.builder("m", 0, 0, 0, 0)
            .build();
        assertNull(s.euStored());
        assertNull(s.euCapacity());
        assertNull(s.efficiencyPercent());
    }

    @Test
    public void statusConvertsToTheFlatSnapshotForm() {
        MultiblockStatus s = status(false, Boolean.FALSE, Collections.<MultiblockPart>emptyList());

        FactorySnapshot.MachineStatus flat = s.toMachineStatus();

        assertEquals("dim0:multimachine.blastfurnace@1,2,3", flat.machineId());
        assertEquals("Electric Blast Furnace", flat.name());
        assertEquals("structure not formed", flat.problem());
    }

    @Test
    public void partListIsImmutableAndDefensivelyCopied() {
        List<MultiblockPart> source = new ArrayList<MultiblockPart>();
        source.add(part(MultiblockPart.PartType.ENERGY_HATCH, 1));
        MultiblockStatus s = status(true, Boolean.FALSE, source);

        source.add(part(MultiblockPart.PartType.INPUT_BUS, 2));
        assertEquals(
            "defensive copy taken",
            1,
            s.parts()
                .size());
        try {
            s.parts()
                .add(part(MultiblockPart.PartType.MUFFLER_HATCH, 3));
            fail("part list must not be modifiable");
        } catch (UnsupportedOperationException expected) {
            assertTrue(true);
        }
    }

    @Test
    public void rejectsAMalformedStatus() {
        try {
            MultiblockStatus.builder("  ", 0, 0, 0, 0)
                .build();
            fail("expected a rejection for a blank type key");
        } catch (IllegalArgumentException expected) {
            assertTrue(
                expected.getMessage()
                    .contains("type key"));
        }
        try {
            new MultiblockPart(null, "n", 0, 0, 0, 0, null);
            fail("expected a rejection for a null part type");
        } catch (IllegalArgumentException expected) {
            assertTrue(
                expected.getMessage()
                    .contains("type"));
        }
    }
}
