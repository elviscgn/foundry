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
 * <p>Tectonic and Continents are allowed to finish all of their normal datapack and Lithostitched
 * wiring first. At the end of RandomState construction, Foundry clamps the live Overworld
 * continentalness graph itself. This deliberately avoids depending on datapack priority, resource
 * replacement order, or compatibility modifiers.</p>
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
        // must keep their own routers completely untouched.
        if (settings.seaLevel() != 63
                || !settings.defaultBlock().is(Blocks.STONE)
                || !settings.defaultFluid().is(Blocks.WATER)) {
            return;
        }

        DensityFunction originalContinents = this.router.continents();
        DensityFunction strategicMask = new StrategicMacroMask();
        DensityFunction boundedContinents = DensityFunctions.min(originalContinents, strategicMask);

        // Terrain functions such as depth/finalDensity can hold references to the same
        // continentalness function independently of NoiseRouter.continents(). Replace those
        // references too, otherwise the climate map would be bounded while actual terrain could
        // still form a giant mainland.
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

        // Force the public continentalness channel as the final operation as well. Even if another
        // branch of the graph was structurally distinct, nothing can bypass this top-level clamp.
        DensityFunction finalContinents = DensityFunctions.min(mapped.continents(), strategicMask);
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
                mapped.finalDensity(),
                mapped.veinToggle(),
                mapped.veinRidged(),
                mapped.veinGap()
        );

        System.out.println("[Foundry] Applied hard strategic macro clamp to the live Overworld NoiseRouter (seed "
                + seed + ")");
    }
}
