package dev.foundry.compat;

import com.tom.createores.Config;
import net.minecraftforge.event.server.ServerAboutToStartEvent;

/**
 * Foundry policy for Create Ore Excavation.
 *
 * Create Ore Excavation already models a reserve amount for finite veins. Foundry makes that
 * mode authoritative instead of allowing the addon's default infinite deposits. The built-in
 * vein recipes then use their own amount multipliers against FINITE_AMOUNT_BASE, e.g. the
 * stock 1.20.1 coal vein is 15x-40x and iron is 10x-30x.
 *
 * This is intentionally a compatibility seam rather than a second reserve counter. A later
 * Foundry geology registry can read the COE vein id/remaining amount and expose it through the
 * Surveyor, Statistics Bureau, mine employment and regional development systems.
 */
public final class CreateOreExcavationCompat {
    public static final int FINITE_AMOUNT_BASE = 1_000;
    public static final int MAX_EXTRACTORS_PER_VEIN = 2;

    private CreateOreExcavationCompat() { }

    public static void applyFiniteReservePolicy(ServerAboutToStartEvent event) {
        // Server-side recipe serialization and extraction both consult these runtime values.
        // We enforce them every world start so a Foundry campaign cannot silently become an
        // infinite-resource economy because of COE's upstream defaults.
        Config.defaultInfinite = false;
        Config.finiteAmountBase = FINITE_AMOUNT_BASE;
        Config.maxExtractorsPerVein = MAX_EXTRACTORS_PER_VEIN;

        // Keep the loaded Forge config view consistent with the runtime policy as well.
        Config.SERVER.defaultInfinite.set(false);
        Config.SERVER.finiteAmountBase.set(FINITE_AMOUNT_BASE);
        Config.SERVER.maxExtractorsPerVein.set(MAX_EXTRACTORS_PER_VEIN);
    }
}
