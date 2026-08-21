package dev.foundry.settlement;

public enum SettlementTier {
    HAMLET("hamlet", "Hamlet", 24),
    TOWN("town", "Town", 64),
    CITY("city", "City", 96),
    METRO("metro", "Metro", 128);

    private final String serializedName;
    private final String displayName;
    private final int claimRadius;

    SettlementTier(String serializedName, String displayName, int claimRadius) {
        this.serializedName = serializedName;
        this.displayName = displayName;
        this.claimRadius = claimRadius;
    }

    public String serializedName() {
        return serializedName;
    }

    public String displayName() {
        return displayName;
    }

    public int claimRadius() {
        return claimRadius;
    }

    /**
     * Development classification derived from the settlement's real economy.
     *
     * Population and jobs still matter, but a settlement must now also cross structural
     * Prosperity thresholds. That score reflects employment, industrial depth, measured
     * physical output, supply reliability, freight integration, and sector diversity.
     * Large specialist settlements can therefore remain Towns instead of becoming Metros
     * purely because population grew.
     */
    public static SettlementTier forSettlement(Settlement settlement) {
        if (settlement == null) {
            return HAMLET;
        }

        int population = settlement.getPopulation();
        int jobs = settlement.getTotalJobCapacity();
        int employed = settlement.getEmployed();
        int prosperity = settlement.getProsperity();
        boolean diversified = settlement.getFoodJobCapacity() > 0 && settlement.getConstructionJobCapacity() > 0;

        if (population >= 1_500
                && jobs >= 96
                && employed >= 72
                && prosperity >= 70
                && diversified) {
            return METRO;
        }

        if (population >= 600
                && jobs >= 36
                && employed >= 30
                && prosperity >= 50
                && diversified) {
            return CITY;
        }

        if (population >= 180
                && jobs >= 12
                && employed >= 10
                && prosperity >= 25) {
            return TOWN;
        }

        return HAMLET;
    }
}
