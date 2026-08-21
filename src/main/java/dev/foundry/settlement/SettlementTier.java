package dev.foundry.settlement;

public enum SettlementTier {
    HAMLET("hamlet", "Hamlet", 48),
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

    public SettlementTier next() {
        return switch (this) {
            case HAMLET -> TOWN;
            case TOWN -> CITY;
            case CITY -> METRO;
            case METRO -> METRO;
        };
    }

    public static SettlementTier fromSerializedName(String value) {
        for (SettlementTier tier : values()) {
            if (tier.serializedName.equals(value)) {
                return tier;
            }
        }
        return HAMLET;
    }
}
