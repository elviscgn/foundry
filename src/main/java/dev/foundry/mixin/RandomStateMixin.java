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
 * modifies the finished live Overworld NoiseRouter exactly once. The public continentalness
 * channel is hard-bounded by the strategic mask and final terrain density is hard-bounded by a
 * matching ocean-floor clamp, so neither biome geography nor physical blocks can reconnect across
 * the guaranteed sea corridors.</p>
 */
@Mixin(RandomState.class)
public abstract class RandomStateMixin {
    @Mutable
    @Shadow
    @Final
    private NoiseRouter router;

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
        DensityFunction boundedContinents = DensityFunctions.min(originalContinents, strategicMask);

        // Tectonic terrain functions can keep independent references to the same continentalness
        // node. Replace those references inside the live router graph as well as clamping the
        // top-level climate channel below.
        DensityFunction.Visitor clampVisitor = new DensityFunction.Visitor() {
            @Override
            public DensityFunction.NoiseHolder visitNoise(DensityFunction.NoiseHolder noiseHolder) {
                return noiseHolder;
            }

            @Override
            public DensityFunction apply(DensityFunction function) {
                if (function == originalContinents || function.equals(originalContinents)) {
                    return boundedContinents;
                }
                return function;
            }
        };

        NoiseRouter mapped = this.router.mapAll(clampVisitor);

        DensityFunction finalContinents = DensityFunctions.min(mapped.continents(), strategicMask);

        // This is the non-bypassable physical guarantee. Even if some terrain branch did not share
        // the same continentalness node, its final density cannot rise above the strategic coastal
        // shelf outside an envelope. Aquifers then fill the corridor to sea level as real ocean.
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

        System.out.println("[Foundry] LIVE OVERWORLD ROUTER CLAMP ACTIVE — strategic continents + physical ocean corridors (seed "
                + seed + ")");
    }
}
