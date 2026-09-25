package dev.everview.core;

import org.junit.jupiter.api.Test;

final class ArchitectureTest {
    @Test void flatSurfaceSimplifiesWithoutChangingCoverage() { ArchitectureChecks.flatTerrain(); }
    @Test void isolatedExtremaSurviveSimplification() { ArchitectureChecks.peaksAndValleys(); }
    @Test void actualTriangleErrorIsBounded() { ArchitectureChecks.saddleAndRidge(); }
    @Test void islandAndInletKeepTheirSampledFootprints() { ArchitectureChecks.islandAndInlet(); }
    @Test void narrowSampledRiverRemainsConnected() { ArchitectureChecks.narrowChannel(); }
    @Test void shallowWaterAndDryLandAtOneHeightRemainDistinct() { ArchitectureChecks.shallowShore(); }
    @Test void partialChildReadinessDoesNotHideOtherCells() { ArchitectureChecks.coverageTransitions(); }
    @Test void retirementRequiresParentAndUploadIncludesOldBuffers() { ArchitectureChecks.retirementAndBudget(); }
    @Test void gapTelemetryMeasuresDrawableCoverage() { ArchitectureChecks.coverageDiagnostic(); }
}
