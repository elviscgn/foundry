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
 * <p>Tectonic and Continents finish all normal wiring first. Foundry then modifies the finished
 * live Overworld NoiseRouter exactly once. Foundry owns macro scale/separation; Continents is
 * retained as an oceanward carving signal so its natural coastline structure survives inside the
 * hard strategic envelope. Final terrain density is also capped by the same seeded envelope.</p>
 */
@Mixin(RandomState.class)
public abstract class RandomStateMixin {
    @Mutable
    @Shadow
    @Final
    private NoiseRouter router;

    // Bias means Continents only cuts when its live field is meaningfully oceanward. This prevents
    // the two independent masks from multiplying into an excessively watery world while still
    // allowing Tectonic/Continents to carve natural bays, straits and coastal breakup.
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
        DensityFunction strategicMask = new StrategicMacroMask(seed);

        // Foundry is the hard outer boundary. Continents is shifted slightly landward, then used
        // only as a subtractive/oceanward signal. It can carve the interior coastline but can never
        // expand land beyond the 500-3000 block strategic envelope.
        DensityFunction coastSignal = DensityFunctions.add(
                originalContinents,
                DensityFunctions.constant(CONTINENTS_COAST_BIAS)
        );
        DensityFunction coastDetail = DensityFunctions.mul(
                DensityFunctions.constant(CONTINENTS_COAST_DETAIL),
                coastSignal
        );
        DensityFunction detailedMask = DensityFunctions.add(strategicMask, coastDetail);
        DensityFunction strategicContinents = DensityFunctions.min(strategicMask, detailedMask);

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

        DensityFunction finalTerrain = DensityFunctions.min(
                mapped.finalDensity(),
                new StrategicOceanClamp(seed)
        );

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
                finalTerrain,
                mapped.veinToggle(),
                mapped.veinRidged(),
                mapped.veinGap()
        );

        System.out.println("[Foundry] LIVE OVERWORLD ROUTER CLAMP ACTIVE — organic seeded strategic geography + physical ocean corridors (seed "
                + seed + ")");
    }
}
