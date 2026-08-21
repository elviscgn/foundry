package dev.foundry.settlement;

import java.util.Locale;

/**
 * Stable human-readable settlement labels until player-defined civic names arrive.
 * The short code comes from the persisted settlement UUID, so every linked worksite and
 * depot can identify exactly which settlement it belongs to without exposing a full UUID.
 */
public final class SettlementIdentity {
    private SettlementIdentity() {
    }

    public static String label(Settlement settlement, SettlementTier tier) {
        if (settlement == null) {
            return "Unlinked";
        }
        SettlementTier effectiveTier = tier == null ? SettlementTier.HAMLET : tier;
        String compactId = settlement.getId().toString().replace("-", "");
        String shortCode = compactId.substring(0, Math.min(4, compactId.length())).toUpperCase(Locale.ROOT);
        return effectiveTier.displayName() + " " + shortCode;
    }
}
