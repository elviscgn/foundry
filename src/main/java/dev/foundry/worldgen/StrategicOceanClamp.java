package dev.foundry.worldgen;

import com.mojang.serialization.MapCodec;
import net.minecraft.util.KeyDispatchDataCodec;
import net.minecraft.world.level.levelgen.DensityFunction;

/**
 * Physical terrain cap paired with {@link StrategicMacroMask}.
 *
 * <p>The macro mask owns the horizontal land boundary. This function evaluates that exact same
 * seeded field and caps final terrain density outside it so aquifers form real ocean corridors.
 * Near the boundary the cap rises smoothly into a coastal shelf instead of producing a wall.</p>
 */
public record StrategicOceanClamp(long seed) implements DensityFunction.SimpleFunction {
    public static final KeyDispatchDataCodec<StrategicOceanClamp> CODEC =
            KeyDispatchDataCodec.of(MapCodec.unit(new StrategicOceanClamp(0L)));

    private static final double SAFE_INTERIOR_MASK = 0.20;
    private static final double DEEP_OCEAN_MASK = -0.65;

    private static final double DEEP_OCEAN_FLOOR_Y = 30.0;
    private static final double INNER_COAST_CAP_Y = 88.0;
    private static final double VERTICAL_FEATHER = 10.0;

    private static final double MIN_OUTPUT = -4.0;
    private static final double MAX_OUTPUT = 8.0;

    public StrategicOceanClamp() {
        this(0L);
    }

    @Override
    public double compute(FunctionContext context) {
        double macro = StrategicMacroMask.sample(seed, context.blockX(), context.blockZ());
        if (macro >= SAFE_INTERIOR_MASK) {
            return MAX_OUTPUT;
        }

        double t = (macro - DEEP_OCEAN_MASK) / (SAFE_INTERIOR_MASK - DEEP_OCEAN_MASK);
        t = Math.max(0.0, Math.min(1.0, t));
        t = t * t * (3.0 - 2.0 * t);

        double floorY = DEEP_OCEAN_FLOOR_Y
                + (INNER_COAST_CAP_Y - DEEP_OCEAN_FLOOR_Y) * t;
        double value = (floorY - context.blockY()) / VERTICAL_FEATHER;
        return Math.max(MIN_OUTPUT, Math.min(MAX_OUTPUT, value));
    }

    @Override
    public double minValue() {
        return MIN_OUTPUT;
    }

    @Override
    public double maxValue() {
        return MAX_OUTPUT;
    }

    @Override
    public KeyDispatchDataCodec<? extends DensityFunction> codec() {
        return CODEC;
    }
}
