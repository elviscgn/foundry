package dev.foundry.settlement;

import java.util.Locale;

/** Stable civic identity shared by every Foundry system that refers to a settlement. */
public final class SettlementIdentity {
    private SettlementIdentity() {
    }

    public static String label(Settlement settlement, SettlementTier tier) {
        if (settlement == null) {
            return "Unlinked";
        }
        if (settlement.hasCustomName()) {
            return settlement.getCustomName();
        }
        SettlementTier effectiveTier = tier == null ? SettlementTier.HAMLET : tier;
        return effectiveTier.displayName() + " " + shortCode(settlement);
    }

    public static String registryLabel(Settlement settlement, SettlementTier tier) {
        if (settlement == null) {
            return "Unlinked";
        }
        if (!settlement.hasCustomName()) {
            return label(settlement, tier);
        }
        return settlement.getCustomName() + " [" + shortCode(settlement) + "]";
    }

    public static String shortCode(Settlement settlement) {
        String compactId = settlement.getId().toString().replace("-", "");
        return compactId.substring(0, Math.min(4, compactId.length())).toUpperCase(Locale.ROOT);
    }
}
