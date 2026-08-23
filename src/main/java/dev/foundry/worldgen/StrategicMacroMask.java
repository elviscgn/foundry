package dev.foundry.worldgen;

import com.mojang.serialization.MapCodec;
import net.minecraft.util.KeyDispatchDataCodec;
import net.minecraft.world.level.levelgen.DensityFunction;

/**
 * Hard strategic-scale envelope for Tiger Ascent geography.
 *
 * <p>Continents/Tectonic remain responsible for natural coastlines and terrain character, but
 * this mask guarantees that connected strategic land cannot silently percolate across the whole
 * world. Candidate land regions sit on a jittered hex lattice. Each region is capped at roughly
 * 2.2-2.7k blocks across, with guaranteed ocean separation between neighboring envelopes.</p>
 *
 * <p>The mask is intentionally seed-independent for the current prototype: world seeds still
 * change the Continents/Tectonic land inside each envelope. Once the strategic scale is approved,
 * the envelope jitter can be made seed-dependent without changing its hard size guarantees.</p>
 */
public record StrategicMacroMask() implements DensityFunction.SimpleFunction {
    public static final KeyDispatchDataCodec<StrategicMacroMask> CODEC =
            KeyDispatchDataCodec.of(MapCodec.unit(new StrategicMacroMask()));

    private static final double CELL_SPACING_X = 3_600.0;
    private static final double CELL_SPACING_Z = 3_118.0; // sqrt(3) / 2 * 3600
    private static final double CENTER_JITTER = 180.0;

    private static final double BASE_RADIUS = 1_225.0;
    private static final double RADIUS_DETAIL = 125.0;
    private static final double DETAIL_SCALE = 360.0;
    private static final double COAST_FEATHER = 220.0;

    private static final double LAND_THRESHOLD = -0.19;
    private static final double MIN_OUTPUT = -1.20;
    private static final double MAX_OUTPUT = 1.00;

    @Override
    public double compute(FunctionContext context) {
        double x = context.blockX();
        double z = context.blockZ();

        long roughRow = fastFloor(z / CELL_SPACING_Z);
        double bestDistanceSquared = Double.POSITIVE_INFINITY;

        // Five rows/columns are cheap and comfortably cover all possible jittered nearest cells.
        for (long row = roughRow - 2; row <= roughRow + 2; row++) {
            double rowOffset = ((row & 1L) == 0L) ? 0.0 : CELL_SPACING_X * 0.5;
            long roughColumn = fastFloor((x - rowOffset) / CELL_SPACING_X);

            for (long column = roughColumn - 2; column <= roughColumn + 2; column++) {
                double centerX = column * CELL_SPACING_X + rowOffset
                        + signedHash(column, row, 0x6A09E667F3BCC909L) * CENTER_JITTER;
                double centerZ = row * CELL_SPACING_Z
                        + signedHash(column, row, 0xBB67AE8584CAA73BL) * CENTER_JITTER;

                double dx = x - centerX;
                double dz = z - centerZ;
                double distanceSquared = dx * dx + dz * dz;
                if (distanceSquared < bestDistanceSquared) {
                    bestDistanceSquared = distanceSquared;
                }
            }
        }

        double coastlineDetail = valueNoise(x / DETAIL_SCALE, z / DETAIL_SCALE, 0x3C6EF372FE94F82BL);
        double radius = BASE_RADIUS + coastlineDetail * RADIUS_DETAIL;
        double signedDistance = radius - Math.sqrt(bestDistanceSquared);

        // LAND_THRESHOLD sits exactly on the strategic envelope boundary. Inside the envelope the
        // mask rises above the threshold and leaves Continents untouched via min(); outside it falls
        // decisively oceanward. The feather gives Tectonic a broad coastal transition rather than
        // a vertical density cliff.
        double value = LAND_THRESHOLD + (signedDistance / COAST_FEATHER) * 0.55;
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

    private static long fastFloor(double value) {
        long floor = (long) value;
        return value < floor ? floor - 1L : floor;
    }

    private static double valueNoise(double x, double z, long salt) {
        long x0 = fastFloor(x);
        long z0 = fastFloor(z);
        long x1 = x0 + 1L;
        long z1 = z0 + 1L;

        double tx = smooth(x - x0);
        double tz = smooth(z - z0);

        double a = signedHash(x0, z0, salt);
        double b = signedHash(x1, z0, salt);
        double c = signedHash(x0, z1, salt);
        double d = signedHash(x1, z1, salt);

        double ab = lerp(a, b, tx);
        double cd = lerp(c, d, tx);
        return lerp(ab, cd, tz);
    }

    private static double smooth(double t) {
        return t * t * (3.0 - 2.0 * t);
    }

    private static double lerp(double a, double b, double t) {
        return a + (b - a) * t;
    }

    private static double signedHash(long x, long z, long salt) {
        long value = x * 0x9E3779B97F4A7C15L + z * 0xC2B2AE3D27D4EB4FL + salt;
        value ^= value >>> 30;
        value *= 0xBF58476D1CE4E5B9L;
        value ^= value >>> 27;
        value *= 0x94D049BB133111EBL;
        value ^= value >>> 31;

        // Convert the upper 53 bits to [0, 1), then remap to [-1, 1).
        double unit = (value >>> 11) * 0x1.0p-53;
        return unit * 2.0 - 1.0;
    }
}
