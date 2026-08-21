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
     * Prototype development classification. The tier is deliberately derived from the
     * settlement's real economy rather than a manual upgrade button.
     *
     * Hamlet is always the starting state. Town requires an established labor market.
     * City and Metro additionally require a diversified productive base, so a large
     * single-purpose extraction settlement can remain a Town instead of automatically
     * becoming a metropolis just because one industry is successful.
     */
    public static SettlementTier forSettlement(Settlement settlement) {
        if (settlement == null) {
            return HAMLET;
        }

        int population = settlement.getPopulation();
        int jobs = settlement.getTotalJobCapacity();
        int employed = settlement.getEmployed();
        boolean diversified = settlement.getFoodJobCapacity() > 0 && settlement.getConstructionJobCapacity() > 0;

        if (population >= 1_500
                && jobs >= 96
                && employed >= 72
                && diversified) {
            return METRO;
        }

        if (population >= 600
                && jobs >= 36
                && employed >= 30
                && diversified) {
            return CITY;
        }

        if (population >= 180
                && jobs >= 12
                && employed >= 10) {
            return TOWN;
        }

        return HAMLET;
    }
}
