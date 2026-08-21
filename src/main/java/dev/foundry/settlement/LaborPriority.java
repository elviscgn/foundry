package dev.foundry.settlement;

import java.util.Locale;

public enum LaborPriority {
    BALANCED("Balanced"),
    FOOD("Food"),
    CONSTRUCTION("Construction");

    private final String displayName;

    LaborPriority(String displayName) {
        this.displayName = displayName;
    }

    public String displayName() {
        return displayName;
    }

    public String serializedName() {
        return name().toLowerCase(Locale.ROOT);
    }

    public LaborPriority next() {
        LaborPriority[] priorities = values();
        return priorities[(ordinal() + 1) % priorities.length];
    }

    public static LaborPriority fromSerializedName(String value) {
        for (LaborPriority priority : values()) {
            if (priority.serializedName().equals(value)) {
                return priority;
            }
        }
        return BALANCED;
    }
}
