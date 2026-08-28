package dev.foundry.mixin;

import dev.foundry.worldgen.StrategicMacroMask;
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
 * <p>Tectonic and Continents finish all normal wiring first. Foundry then replaces the live
 * Overworld continentalness graph with the seeded strategic field. Tectonic remains fully
 * responsible for converting that field into physical terrain: mountains, shelves, ocean floors,
 * caves and coast transitions are never clipped directly by Foundry.</p>
 */
@Mixin(RandomState.class)
public abstract class RandomStateMixin {
    @Mutable
    @Shadow
    @Final
    private NoiseRouter router;

    // Continents is used only as an oceanward carving signal inside Foundry's bounded strategic
    // land envelopes. It cannot expand land beyond the 500-3000 block macro contract.
    private static final double CONTINENTS_COAST_BIAS = 0.12;
    private static final double CONTINENTS_COAST_DETAIL = 0.32;

    @Inject(method = "<init>", at = @At("RETURN"))
    private void foundry$clampLiveOverworldRouter(
            NoiseGeneratorSettings settings,
            HolderGetter<?> noiseParameters,
            long seed,
            CallbackInfo callbackInfo
    ) {
        if (settings.seaLevel() != 63
                || !settings.defaultBlock().is(Blocks.STONE)
                || !settings.defaultFluid().is(Blocks.WATER)) {
            return;
        }

        DensityFunction originalContinents = this.router.continents();

        // StrategicMacroMask is purely horizontal but comparatively expensive. Mirror vanilla /
        // Tectonic's own 2D continentalness pattern and let NoiseChunk cache it across the many Y
        // samples performed while generating a chunk. This preserves geography exactly while
        // avoiding repeated strategic-site searches for the same horizontal coordinates.
        DensityFunction strategicMask = DensityFunctions.flatCache(
                DensityFunctions.cache2d(new StrategicMacroMask(seed))
        );

        DensityFunction coastSignal = DensityFunctions.add(
                originalContinents,
                DensityFunctions.constant(CONTINENTS_COAST_BIAS)
        );
        DensityFunction coastDetail = DensityFunctions.mul(
                DensityFunctions.constant(CONTINENTS_COAST_DETAIL),
                coastSignal
        );
        DensityFunction detailedMask = DensityFunctions.add(strategicMask, coastDetail);
        DensityFunction strategicContinents = DensityFunctions.flatCache(
                DensityFunctions.cache2d(DensityFunctions.min(strategicMask, detailedMask))
        );

        // Replace every reference to the live continentalness node throughout Tectonic's finished
        // router graph. This lets Tectonic's own depth/factor/final-density machinery produce the
        // physical coast and ocean naturally instead of Foundry chopping finalDensity afterward.
        DensityFunction.Visitor strategicVisitor = new DensityFunction.Visitor() {
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

        NoiseRouter mapped = this.router.mapAll(strategicVisitor);

        this.router = new NoiseRouter(
                mapped.barrierNoise(),
                mapped.fluidLevelFloodednessNoise(),
                mapped.fluidLevelSpreadNoise(),
                mapped.lavaNoise(),
                mapped.temperature(),
                mapped.vegetation(),
                strategicContinents,
                mapped.erosion(),
                mapped.depth(),
                mapped.ridges(),
                mapped.initialDensityWithoutJaggedness(),
                mapped.finalDensity(),
                mapped.veinToggle(),
                mapped.veinRidged(),
                mapped.veinGap()
        );

        System.out.println("[Foundry] LIVE OVERWORLD STRATEGIC CONTINENTALNESS ACTIVE — cached 2D strategic field; Tectonic owns physical terrain (seed "
                + seed + ")");
    }
}
