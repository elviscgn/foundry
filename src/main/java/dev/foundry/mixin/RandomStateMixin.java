package dev.foundry.mixin;

import dev.foundry.worldgen.StrategicMacroMask;
import dev.foundry.worldgen.StrategicOceanClamp;
import net.minecraft.core.HolderGetter;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.DensityFunctions;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.NoiseRouter;
import net.minecraft.world.level.levelgen.RandomState;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Final authority over Tiger Ascent macro geography.
 *
 * <p>Tectonic and Continents finish all normal datapack/Lithostitched wiring first. Foundry then
 * modifies the finished live Overworld NoiseRouter exactly once. The strategic mask owns macro
 * landmass size/separation; Continents is retained only as oceanward coastline detail. Final
 * terrain density is additionally capped outside the same envelopes so physical blocks cannot
 * reconnect across the guaranteed sea corridors.</p>
 */
@Mixin(RandomState.class)
public abstract class RandomStateMixin {
    @Mutable
    @Shadow
    @Final
    private NoiseRouter router;

    private static final double CONTINENTS_COAST_DETAIL = 0.20;

    @Inject(method = "<init>", at = @At("RETURN"))
    private void foundry$clampLiveOverworldRouter(
            NoiseGeneratorSettings settings,
            HolderGetter<?> noiseParameters,
            long seed,
            CallbackInfo callbackInfo
    ) {
        // Only touch the normal stone-and-water Overworld family. Nether/End/custom dimensions
        // keep their own routers completely untouched.
        if (settings.seaLevel() != 63
                || !settings.defaultBlock().is(Blocks.STONE)
                || !settings.defaultFluid().is(Blocks.WATER)) {
            return;
        }

        DensityFunction originalContinents = this.router.continents();
        DensityFunction strategicMask = new StrategicMacroMask();

        // The strategic mask is the landmass baseline. Continents can only cut oceanward detail
        // into that baseline; it is never allowed to push land past the hard envelope.
        DensityFunction coastDetail = DensityFunctions.mul(
                DensityFunctions.constant(CONTINENTS_COAST_DETAIL),
                originalContinents
        );
        DensityFunction detailedMask = DensityFunctions.add(strategicMask, coastDetail);
        DensityFunction strategicContinents = DensityFunctions.min(strategicMask, detailedMask);

        // Tectonic terrain functions can keep independent references to the same continentalness
        // node. Replace those references inside the live router graph so terrain shaping sees the
        // strategic field too, rather than only the climate/biome sampler seeing it.
        DensityFunction.Visitor clampVisitor = new DensityFunction.Visitor() {
            @Override
            public DensityFunction.NoiseHolder visitNoise(DensityFunction.NoiseHolder noiseHolder) {
                return noiseHolder;
            }

            @Override
            public DensityFunction apply(DensityFunction function) {
                if (function == originalContinents || function.equals(originalContinents)) {
                    return strategicContinents;
                }
                return function;
            }
        };

        NoiseRouter mapped = this.router.mapAll(clampVisitor);

        // Top-level continentalness is forced to the strategic field directly. No later datapack or
        // Lithostitched resource ordering participates after this point.
        DensityFunction finalContinents = strategicContinents;

        // Non-bypassable physical guarantee: even if a terrain branch did not share the exact
        // original continentalness node, final density cannot rise above the strategic coastal
        // shelf outside an envelope. Aquifers fill the corridor to sea level as real ocean.
        DensityFunction finalTerrain = DensityFunctions.min(
                mapped.finalDensity(),
                new StrategicOceanClamp()
        );

        this.router = new NoiseRouter(
                mapped.barrierNoise(),
                mapped.fluidLevelFloodednessNoise(),
                mapped.fluidLevelSpreadNoise(),
                mapped.lavaNoise(),
                mapped.temperature(),
                mapped.vegetation(),
                finalContinents,
                mapped.erosion(),
                mapped.depth(),
                mapped.ridges(),
                mapped.initialDensityWithoutJaggedness(),
                finalTerrain,
                mapped.veinToggle(),
                mapped.veinRidged(),
                mapped.veinGap()
        );

        System.out.println("[Foundry] LIVE OVERWORLD ROUTER CLAMP ACTIVE — strategic macro land + physical ocean corridors (seed "
                + seed + ")");
    }
}
