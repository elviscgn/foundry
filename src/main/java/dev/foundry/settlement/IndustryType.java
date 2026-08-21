package dev.foundry.settlement;

import java.util.Locale;

public enum IndustryType {
    BAKERY("Bakery", "Food", 12),
    BRICKWORKS("Brickworks", "Construction", 16);

    private final String displayName;
    private final String sectorName;
    private final int jobs;

    IndustryType(String displayName, String sectorName, int jobs) {
        this.displayName = displayName;
        this.sectorName = sectorName;
        this.jobs = jobs;
    }

    public String displayName() {
        return displayName;
    }

    public String sectorName() {
        return sectorName;
    }

    public int jobs() {
        return jobs;
    }

    public String serializedName() {
        return name().toLowerCase(Locale.ROOT);
    }

    public static IndustryType fromSerializedName(String value) {
        for (IndustryType type : values()) {
            if (type.serializedName().equals(value)) {
                return type;
            }
        }
        return null;
    }
}
