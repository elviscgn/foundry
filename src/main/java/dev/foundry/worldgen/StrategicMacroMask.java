package dev.foundry.worldgen;

import com.mojang.serialization.MapCodec;
import net.minecraft.util.KeyDispatchDataCodec;
import net.minecraft.world.level.levelgen.DensityFunction;

/**
 * Seeded strategic-scale geography envelope for Tiger Ascent.
 *
 * <p>Tectonic still owns terrain character, but this field owns macro landmass scale. Each
 * strategic region receives a seeded size from roughly 500 to 3000 blocks across, a rotated
 * non-circular outline, and a strongly jittered center. A shrunken Voronoi boundary guarantees
 * an ocean corridor between neighboring regions even when their random centers approach.</p>
 *
 * <p>The underlying hex scaffold is deliberately invisible: it only gives us a bounded local
 * search. Large center jitter, variable diameters, rotation, aspect ratio and harmonic coastline
 * deformation prevent the repeated same-size-dot pattern of the first prototype.</p>
 */
public record StrategicMacroMask(long seed) implements DensityFunction.SimpleFunction {
    public static final KeyDispatchDataCodec<StrategicMacroMask> CODEC =
            KeyDispatchDataCodec.of(MapCodec.unit(new StrategicMacroMask(0L)));

    // Strategic centers. The visible map is NOT allowed to inherit this spacing as a coastline.
    private static final double CELL_SPACING_X = 3_250.0;
    private static final double CELL_SPACING_Z = 2_815.0; // sqrt(3) / 2 * 3250, rounded
    private static final double CENTER_JITTER = 575.0;

    // User-facing macro size contract: substantial islands/mainlands range from ~500 to ~3000
    // blocks across. The selected diameter is a hard maximum before Voronoi corridor clipping.
    private static final double MIN_DIAMETER = 500.0;
    private static final double MAX_DIAMETER = 3_000.0;

    // Retreat each neighboring landmass this far from the Voronoi bisector. The resulting full
    // guaranteed corridor is roughly twice this value; naturally smaller neighbors create much
    // wider seas without any extra rule.
    private static final double OCEAN_HALF_GAP = 145.0;

    private static final double LAND_THRESHOLD = -0.19;
    private static final double MIN_OUTPUT = -1.20;
    private static final double MAX_OUTPUT = 1.00;

    public StrategicMacroMask() {
        this(0L);
    }

    @Override
    public double compute(FunctionContext context) {
        return sample(seed, context.blockX(), context.blockZ());
    }

    /** Shared horizontal sampler used by the physical ocean-floor clamp. */
    public static double sample(long seed, double x, double z) {
        long roughRow = fastFloor(z / CELL_SPACING_Z);

        double nearestDistance = Double.POSITIVE_INFINITY;
        double secondDistance = Double.POSITIVE_INFINITY;
        long nearestColumn = 0L;
        long nearestRow = 0L;
        double nearestCenterX = 0.0;
        double nearestCenterZ = 0.0;

        // Large center jitter destroys the visible lattice. A 5x5 neighborhood still comfortably
        // covers every center that can become nearest at this spacing/jitter.
        for (long row = roughRow - 2; row <= roughRow + 2; row++) {
            double rowOffset = ((row & 1L) == 0L) ? 0.0 : CELL_SPACING_X * 0.5;
            long roughColumn = fastFloor((x - rowOffset) / CELL_SPACING_X);

            for (long column = roughColumn - 2; column <= roughColumn + 2; column++) {
                double centerX = column * CELL_SPACING_X + rowOffset
                        + signedHash(seed, column, row, 0x6A09E667F3BCC909L) * CENTER_JITTER;
                double centerZ = row * CELL_SPACING_Z
                        + signedHash(seed, column, row, 0xBB67AE8584CAA73BL) * CENTER_JITTER;

                double dx = x - centerX;
                double dz = z - centerZ;
                double distance = Math.sqrt(dx * dx + dz * dz);

                if (distance < nearestDistance) {
                    secondDistance = nearestDistance;
                    nearestDistance = distance;
                    nearestColumn = column;
                    nearestRow = row;
                    nearestCenterX = centerX;
                    nearestCenterZ = centerZ;
                } else if (distance < secondDistance) {
                    secondDistance = distance;
                }
            }
        }

        double diameter = chooseDiameter(seed, nearestColumn, nearestRow);
        double longRadius = diameter * 0.5;

        double dx = x - nearestCenterX;
        double dz = z - nearestCenterZ;

        double rotation = unitHash(seed, nearestColumn, nearestRow, 0x3C6EF372FE94F82BL) * Math.PI;
        double cosR = Math.cos(rotation);
        double sinR = Math.sin(rotation);
        double localX = dx * cosR + dz * sinR;
        double localZ = -dx * sinR + dz * cosR;

        double aspect = lerp(
                0.78,
                0.98,
                unitHash(seed, nearestColumn, nearestRow, 0xA54FF53A5F1D36F1L)
        );
        double shortRadius = longRadius * aspect;

        double angle = Math.atan2(localZ, localX);
        double cosA = Math.cos(angle);
        double sinA = Math.sin(angle);
        double ellipseRadius = (longRadius * shortRadius) / Math.sqrt(
                shortRadius * shortRadius * cosA * cosA
                        + longRadius * longRadius * sinA * sinA
        );

        // Seeded harmonics create asymmetric bays/capes/peninsulas. Their baseline is below 1.0
        // and the final radius is clamped to longRadius, so no deformation can violate the selected
        // 500-3000 block size class.
        double phase3 = unitHash(seed, nearestColumn, nearestRow, 0x510E527FADE682D1L) * Math.PI * 2.0;
        double phase5 = unitHash(seed, nearestColumn, nearestRow, 0x9B05688C2B3E6C1FL) * Math.PI * 2.0;
        double phase7 = unitHash(seed, nearestColumn, nearestRow, 0x1F83D9ABFB41BD6BL) * Math.PI * 2.0;
        double harmonic = 0.90
                + 0.075 * Math.sin(angle * 3.0 + phase3)
                + 0.045 * Math.sin(angle * 5.0 + phase5)
                + 0.025 * Math.sin(angle * 7.0 + phase7);

        double noiseScale = Math.max(150.0, longRadius * 0.38);
        double noiseAmplitude = Math.max(18.0, Math.min(72.0, longRadius * 0.055));
        double coastNoise = valueNoise(
                seed,
                x / noiseScale,
                z / noiseScale,
                0x5BE0CD19137E2179L
        ) * noiseAmplitude;

        double organicRadius = ellipseRadius * harmonic + coastNoise;
        organicRadius = Math.max(longRadius * 0.58, Math.min(longRadius, organicRadius));
        double shapeSignedDistance = organicRadius - nearestDistance;

        // At the bisector d2-d1 = 0. Moving inward raises this approximately one block per block;
        // retreating by OCEAN_HALF_GAP therefore forces a real water corridor while still allowing
        // a 500-block island to sit much closer to a large neighbor than two 3000-block mainlands.
        double corridorSignedDistance = (secondDistance - nearestDistance) * 0.5 - OCEAN_HALF_GAP;
        double signedDistance = Math.min(shapeSignedDistance, corridorSignedDistance);

        double coastFeather = Math.max(70.0, Math.min(180.0, longRadius * 0.14));
        double value = LAND_THRESHOLD + (signedDistance / coastFeather) * 0.55;
        return Math.max(MIN_OUTPUT, Math.min(MAX_OUTPUT, value));
    }

    /**
     * Weighted size distribution. Small islands exist, but the world remains useful for a
     * development-state game: most strategic regions are medium/large rather than tiny specks.
     */
    private static double chooseDiameter(long seed, long column, long row) {
        double bucket = unitHash(seed, column, row, 0xCBBB9D5DC1059ED8L);
        double within = unitHash(seed, column, row, 0x629A292A367CD507L);

        if (bucket < 0.05) {
            return lerp(MIN_DIAMETER, 900.0, within);       // 5% small: 500-900
        }
        if (bucket < 0.15) {
            return lerp(900.0, 1_600.0, within);           // 10% compact: 900-1600
        }
        if (bucket < 0.40) {
            return lerp(1_600.0, 2_200.0, within);         // 25% medium: 1600-2200
        }
        if (bucket < 0.75) {
            return lerp(2_200.0, 2_700.0, within);         // 35% major: 2200-2700
        }
        return lerp(2_700.0, MAX_DIAMETER, within);        // 25% large: 2700-3000
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

    private static double valueNoise(long seed, double x, double z, long salt) {
        long x0 = fastFloor(x);
        long z0 = fastFloor(z);
        long x1 = x0 + 1L;
        long z1 = z0 + 1L;

        double tx = smooth(x - x0);
        double tz = smooth(z - z0);

        double a = signedHash(seed, x0, z0, salt);
        double b = signedHash(seed, x1, z0, salt);
        double c = signedHash(seed, x0, z1, salt);
        double d = signedHash(seed, x1, z1, salt);

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

    private static double unitHash(long seed, long x, long z, long salt) {
        return (signedHash(seed, x, z, salt) + 1.0) * 0.5;
    }

    private static double signedHash(long seed, long x, long z, long salt) {
        long value = seed ^ salt;
        value += x * 0x9E3779B97F4A7C15L + z * 0xC2B2AE3D27D4EB4FL;
        value ^= value >>> 30;
        value *= 0xBF58476D1CE4E5B9L;
        value ^= value >>> 27;
        value *= 0x94D049BB133111EBL;
        value ^= value >>> 31;

        double unit = (value >>> 11) * 0x1.0p-53;
        return unit * 2.0 - 1.0;
    }
}
