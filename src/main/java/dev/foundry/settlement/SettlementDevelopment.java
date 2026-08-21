package dev.foundry.settlement;

import java.util.List;

/**
 * Structural development model behind settlement Prosperity.
 *
 * Prosperity is a 0-100 development score. It measures whether a settlement has built a
 * durable productive economy: employment, job depth, physical output, reliable supplies,
 * freight integration, and sector diversity. It is intentionally not a happiness meter and
 * it does not rise just because a player refills Bread once.
 */
public final class SettlementDevelopment {
    public static final int MAX_SCORE = 100;

    private static final int EMPLOYMENT_WEIGHT = 25;
    private static final int INDUSTRIAL_DEPTH_WEIGHT = 20;
    private static final int PRODUCTION_WEIGHT = 20;
    private static final int SUPPLY_RELIABILITY_WEIGHT = 15;
    private static final int TRADE_INTEGRATION_WEIGHT = 10;
    private static final int DIVERSITY_WEIGHT = 10;
    private static final int TREND_DAYS = 7;

    private SettlementDevelopment() {
    }

    public static int score(Settlement settlement) {
        if (settlement == null) {
            return 0;
        }

        int workforce = Math.max(1, settlement.getWorkforce());
        int employment = scaledScore(
                settlement.getEmployed(),
                workforce,
                EMPLOYMENT_WEIGHT
        );

        int productiveJobs = Math.min(settlement.getTotalJobCapacity(), workforce);
        int industrialDepth = scaledScore(
                productiveJobs,
                workforce,
                INDUSTRIAL_DEPTH_WEIGHT
        );

        int productionReference = Math.max(
                1,
                settlement.getDailyBreadConsumption()
                        + Math.max(1, settlement.getBuildingMaterialsTarget() / 4)
        );
        int measuredOutput = settlement.getFoodOutputAverage(TREND_DAYS)
                + settlement.getConstructionOutputAverage(TREND_DAYS);
        int production = scaledScore(
                measuredOutput,
                productionReference,
                PRODUCTION_WEIGHT
        );

        int supplyReliability = supplyReliabilityScore(settlement);

        int freightReference = Math.max(
                1,
                Math.max(1, settlement.getDailyBreadConsumption() / 2)
                        + Math.max(1, settlement.getBuildingMaterialsTarget() / 8)
        );
        int freightThroughput = settlement.getBreadImportsAverage(TREND_DAYS)
                + settlement.getBreadExportsAverage(TREND_DAYS)
                + settlement.getBrickImportsAverage(TREND_DAYS)
                + settlement.getBrickExportsAverage(TREND_DAYS);
        int tradeIntegration = scaledScore(
                freightThroughput,
                freightReference,
                TRADE_INTEGRATION_WEIGHT
        );

        int diversity = 0;
        if (settlement.getFoodJobCapacity() > 0) {
            diversity += DIVERSITY_WEIGHT / 2;
        }
        if (settlement.getConstructionJobCapacity() > 0) {
            diversity += DIVERSITY_WEIGHT / 2;
        }

        return clamp(
                employment
                        + industrialDepth
                        + production
                        + supplyReliability
                        + tradeIntegration
                        + diversity,
                0,
                MAX_SCORE
        );
    }

    private static int supplyReliabilityScore(Settlement settlement) {
        List<Settlement.HistoryPoint> history = settlement.getHistory();
        if (history.isEmpty()) {
            return supplyPointScore(
                    settlement.getBreadSupplied(),
                    settlement.getBreadTarget(),
                    settlement.getBuildingMaterialsSupplied(),
                    settlement.getBuildingMaterialsTarget()
            );
        }

        int count = Math.min(TREND_DAYS, history.size());
        int total = 0;
        for (int i = history.size() - count; i < history.size(); i++) {
            Settlement.HistoryPoint point = history.get(i);
            total += supplyPointScore(
                    point.breadSupplied(),
                    point.breadTarget(),
                    point.buildingMaterialsSupplied(),
                    point.buildingMaterialsTarget()
            );
        }
        return (total + count / 2) / count;
    }

    private static int supplyPointScore(int breadSupplied, int breadTarget,
                                        int materialsSupplied, int materialsTarget) {
        int breadReliability = scaledScore(
                breadSupplied,
                Math.max(1, breadTarget),
                10
        );
        int materialsReliability = scaledScore(
                materialsSupplied,
                Math.max(1, materialsTarget),
                5
        );
        return clamp(breadReliability + materialsReliability, 0, SUPPLY_RELIABILITY_WEIGHT);
    }

    private static int scaledScore(long value, long reference, int weight) {
        if (value <= 0L || reference <= 0L || weight <= 0) {
            return 0;
        }
        long capped = Math.min(value, reference);
        return (int) Math.min(
                weight,
                (capped * weight + reference / 2L) / reference
        );
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
