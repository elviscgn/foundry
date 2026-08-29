package dev.foundry.worldgen;

import com.mojang.serialization.MapCodec;
import net.minecraft.util.KeyDispatchDataCodec;
import net.minecraft.world.level.levelgen.DensityFunction;

/**
 * Seeded strategic-scale geography envelope for Tiger Ascent.
 *
 * <p>Foundry owns macro landmass scale and separation while Tectonic owns physical terrain.
 * Most strategic islands are intentionally around one thousand blocks across. Five-hundred-block
 * islands are common enough to matter, while two-thousand-plus mainlands are deliberately rare.</p>
 *
 * <p>Visible landforms are assembled from a bent chain of narrow, overlapping lobes plus carved
 * inlets and peninsulas. There is no giant central ellipse. A strongly irregular outer envelope is
 * used only as a safety cap, so the 500-3000 block contract survives without forcing round coasts.</p>
 */
public record StrategicMacroMask(long seed) implements DensityFunction.SimpleFunction {
    public static final KeyDispatchDataCodec<StrategicMacroMask> CODEC =
            KeyDispatchDataCodec.of(MapCodec.unit(new StrategicMacroMask(0L)));

    // Keep the approved ~1k island scale, but bring neighboring strategic places close enough that
    // reaching one coast usually reveals another useful destination across a short sea crossing.
    // Inactive cells still create occasional wider strategic sea lanes; they just no longer cluster
    // into giant empty basins as often.
    private static final double CELL_SPACING_X = 1_050.0;
    private static final double CELL_SPACING_Z = 909.0; // ~sqrt(3)/2 * 1050
    private static final double CENTER_JITTER = 150.0;

    private static final double MIN_DIAMETER = 500.0;
    private static final double MAX_DIAMETER = 3_000.0;
    private static final double OCEAN_HALF_GAP = 35.0;

    private static final double ACTIVITY_THRESHOLD = -0.50;
    private static final double ACTIVITY_MACRO_WEIGHT = 0.40;
    private static final double ACTIVITY_LOCAL_WEIGHT = 0.60;
    private static final double ACTIVITY_SCALE = 1.80;

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

    public static double sample(long seed, double x, double z) {
        long roughRow = fastFloor(z / CELL_SPACING_Z);

        // Compare squared distances inside the candidate loop. The old implementation performed a
        // sqrt for every candidate; this performs only two after the nearest sites are known.
        double nearestDistanceSq = Double.POSITIVE_INFINITY;
        double secondDistanceSq = Double.POSITIVE_INFINITY;
        long nearestColumn = 0L;
        long nearestRow = 0L;
        double nearestCenterX = 0.0;
        double nearestCenterZ = 0.0;

        for (long row = roughRow - 3; row <= roughRow + 3; row++) {
            double rowOffset = ((row & 1L) == 0L) ? 0.0 : CELL_SPACING_X * 0.5;
            long roughColumn = fastFloor((x - rowOffset) / CELL_SPACING_X);

            for (long column = roughColumn - 3; column <= roughColumn + 3; column++) {
                if (!isRegionActive(seed, column, row)) {
                    continue;
                }

                double centerX = column * CELL_SPACING_X + rowOffset
                        + signedHash(seed, column, row, 0x6A09E667F3BCC909L) * CENTER_JITTER;
                double centerZ = row * CELL_SPACING_Z
                        + signedHash(seed, column, row, 0xBB67AE8584CAA73BL) * CENTER_JITTER;

                double dx = x - centerX;
                double dz = z - centerZ;
                double distanceSq = dx * dx + dz * dz;

                if (distanceSq < nearestDistanceSq) {
                    secondDistanceSq = nearestDistanceSq;
                    nearestDistanceSq = distanceSq;
                    nearestColumn = column;
                    nearestRow = row;
                    nearestCenterX = centerX;
                    nearestCenterZ = centerZ;
                } else if (distanceSq < secondDistanceSq) {
                    secondDistanceSq = distanceSq;
                }
            }
        }

        if (!Double.isFinite(nearestDistanceSq)) {
            return MIN_OUTPUT;
        }

        double nearestDistance = Math.sqrt(nearestDistanceSq);
        double secondDistance = Double.isFinite(secondDistanceSq)
                ? Math.sqrt(secondDistanceSq)
                : Double.POSITIVE_INFINITY;

        double diameter = chooseDiameter(seed, nearestColumn, nearestRow);
        double hardRadius = diameter * 0.5;

        double dx = x - nearestCenterX;
        double dz = z - nearestCenterZ;

        double regionRotation = unitHash(seed, nearestColumn, nearestRow, 0x3C6EF372FE94F82BL)
                * Math.PI * 2.0;
        double cosR = Math.cos(regionRotation);
        double sinR = Math.sin(regionRotation);
        double localX = dx * cosR + dz * sinR;
        double localZ = -dx * sinR + dz * cosR;

        // Coherent domain warp bends the whole landform instead of simply roughening a circle.
        double warpScale = Math.max(85.0, hardRadius * 0.31);
        double warpAmplitude = Math.max(14.0, Math.min(78.0, hardRadius * 0.10));
        double warpedX = localX + valueNoise(
                seed, x / warpScale, z / warpScale, 0x243F6A8885A308D3L
        ) * warpAmplitude;
        double warpedZ = localZ + valueNoise(
                seed, x / warpScale, z / warpScale, 0x13198A2E03707344L
        ) * warpAmplitude;

        // No central potato. Three overlapping, differently rotated narrow lobes create a bent
        // connected spine. Their offsets/radii vary independently per strategic region.
        double shape = regionalLobe(
                seed, nearestColumn, nearestRow, warpedX, warpedZ, hardRadius,
                0x3F84D5B5B5470917L,
                -0.30, -0.03, 0.43, 0.20
        );
        shape = Math.max(shape, regionalLobe(
                seed, nearestColumn, nearestRow, warpedX, warpedZ, hardRadius,
                0x9216D5D98979FB1BL,
                0.00, 0.08, 0.39, 0.24
        ));
        shape = Math.max(shape, regionalLobe(
                seed, nearestColumn, nearestRow, warpedX, warpedZ, hardRadius,
                0xD1310BA698DFB5ACL,
                0.31, -0.08, 0.38, 0.18
        ));

        // Most regions get one asymmetric side mass. This is what creates hooks, broad bays and
        // one-sided silhouettes rather than the old bilateral oval look.
        if (unitHash(seed, nearestColumn, nearestRow, 0xB8E1AFED6A267E96L) < 0.78) {
            double side = unitHash(seed, nearestColumn, nearestRow, 0xBA7C9045F12C7F99L) < 0.5
                    ? -1.0 : 1.0;
            shape = Math.max(shape, regionalLobe(
                    seed, nearestColumn, nearestRow, warpedX, warpedZ, hardRadius,
                    0xBA7C9045F12C7F99L,
                    signedHash(seed, nearestColumn, nearestRow, 0xC13FA9A902A6328FL) * 0.12,
                    side * 0.34,
                    0.29,
                    0.13
            ));
        }

        // Narrow terminal peninsula. Even ~1k islands can have one; large islands are not the only
        // interesting silhouettes anymore.
        if (diameter >= 720.0
                && unitHash(seed, nearestColumn, nearestRow, 0x24A19947B3916CF7L) < 0.72) {
            long salt = 0x24A19947B3916CF7L;
            double side = unitHash(seed, nearestColumn, nearestRow, salt ^ 0x44L) < 0.5 ? -1.0 : 1.0;
            double peninsulaX = side * hardRadius * lerp(
                    0.53, 0.66, unitHash(seed, nearestColumn, nearestRow, salt ^ 0x55L)
            );
            double peninsulaZ = hardRadius * signedHash(
                    seed, nearestColumn, nearestRow, salt ^ 0x66L
            ) * 0.24;
            shape = Math.max(shape, ellipseSignedDistance(
                    warpedX - peninsulaX,
                    warpedZ - peninsulaZ,
                    hardRadius * lerp(0.22, 0.31,
                            unitHash(seed, nearestColumn, nearestRow, salt ^ 0x77L)),
                    hardRadius * lerp(0.065, 0.12,
                            unitHash(seed, nearestColumn, nearestRow, salt ^ 0x88L)),
                    signedHash(seed, nearestColumn, nearestRow, salt ^ 0x99L) * 1.30
            ));
        }

        // Carve long, narrow inlets from the edge. These produce concavity/straits without the
        // circular Pac-Man bites from the earlier prototype.
        if (diameter >= 780.0) {
            shape = carveInlet(
                    shape, seed, nearestColumn, nearestRow, warpedX, warpedZ, hardRadius,
                    0xA5A3564E27F8862BL, 0.74
            );
        }
        if (diameter >= 1_150.0) {
            shape = carveInlet(
                    shape, seed, nearestColumn, nearestRow, warpedX, warpedZ, hardRadius,
                    0x0801F2E2858EFC16L, 0.34
            );
        }

        // Fine coastline roughness after macro morphology is established.
        double coastScale = Math.max(48.0, hardRadius * 0.15);
        double coastAmplitude = Math.max(7.0, Math.min(42.0, hardRadius * 0.055));
        shape += valueNoise(
                seed, x / coastScale, z / coastScale, 0x636920D871574E69L
        ) * coastAmplitude;

        // Irregular safety envelope only. The visible shape normally stays inside this boundary;
        // it exists to guarantee the requested maximum size, not to define the coastline.
        double cap = irregularHardCap(
                seed, nearestColumn, nearestRow, localX, localZ, hardRadius
        );
        shape = Math.min(shape, cap);

        // Voronoi separation guarantees water between neighboring strategic regions even when
        // jitter places their centers unusually close together.
        double corridorSignedDistance = Double.isFinite(secondDistance)
                ? (secondDistance - nearestDistance) * 0.5 - OCEAN_HALF_GAP
                : Double.POSITIVE_INFINITY;
        double signedDistance = Math.min(shape, corridorSignedDistance);

        double coastFeather = Math.max(42.0, Math.min(115.0, hardRadius * 0.13));
        double value = LAND_THRESHOLD + (signedDistance / coastFeather) * 0.55;
        return Math.max(MIN_OUTPUT, Math.min(MAX_OUTPUT, value));
    }

    private static double carveInlet(
            double shape,
            long seed,
            long column,
            long row,
            double x,
            double z,
            double hardRadius,
            long salt,
            double chance
    ) {
        if (unitHash(seed, column, row, salt ^ 0x111L) >= chance) {
            return shape;
        }

        double angle = unitHash(seed, column, row, salt) * Math.PI * 2.0;
        double offset = hardRadius * lerp(
                0.52, 0.68, unitHash(seed, column, row, salt ^ 0x222L)
        );
        double centerX = Math.cos(angle) * offset;
        double centerZ = Math.sin(angle) * offset;

        double cut = ellipseSignedDistance(
                x - centerX,
                z - centerZ,
                hardRadius * lerp(0.22, 0.34,
                        unitHash(seed, column, row, salt ^ 0x333L)),
                hardRadius * lerp(0.045, 0.095,
                        unitHash(seed, column, row, salt ^ 0x444L)),
                -angle + signedHash(seed, column, row, salt ^ 0x555L) * 0.35
        );

        // SDF subtraction: positive cut values become water, while the narrow cut's long axis makes
        // an inlet/channel rather than a circular bite.
        return Math.min(shape, -cut);
    }

    private static double irregularHardCap(
            long seed,
            long column,
            long row,
            double x,
            double z,
            double hardRadius
    ) {
        double angle = Math.atan2(z, x);
        double distance = Math.sqrt(x * x + z * z);

        double phase2 = unitHash(seed, column, row, 0xA458FEA3F4933D7EL) * Math.PI * 2.0;
        double phase3 = unitHash(seed, column, row, 0x8F1BBCDCB7A56463L) * Math.PI * 2.0;
        double phase5 = unitHash(seed, column, row, 0x9E3779B97F4A7C15L) * Math.PI * 2.0;

        double radiusFactor = 0.84
                + 0.095 * Math.sin(angle * 2.0 + phase2)
                + 0.070 * Math.sin(angle * 3.0 + phase3)
                + 0.040 * Math.sin(angle * 5.0 + phase5);
        radiusFactor = Math.max(0.62, Math.min(0.99, radiusFactor));
        return hardRadius * radiusFactor - distance;
    }

    private static boolean isRegionActive(long seed, long column, long row) {
        return regionActivity(seed, column, row) > ACTIVITY_THRESHOLD;
    }

    private static double regionActivity(long seed, long column, long row) {
        double macro = valueNoise(
                seed,
                column / ACTIVITY_SCALE,
                row / ACTIVITY_SCALE,
                0xF00DBABE1234ABCDL
        );
        double local = signedHash(seed, column, row, 0xD00DFEED99887766L);
        return macro * ACTIVITY_MACRO_WEIGHT + local * ACTIVITY_LOCAL_WEIGHT;
    }

    private static double regionalLobe(
            long seed,
            long column,
            long row,
            double x,
            double z,
            double hardRadius,
            long salt,
            double baseOffsetX,
            double baseOffsetZ,
            double baseRadiusX,
            double baseRadiusZ
    ) {
        double offsetX = hardRadius * (baseOffsetX
                + signedHash(seed, column, row, salt) * 0.11);
        double offsetZ = hardRadius * (baseOffsetZ
                + signedHash(seed, column, row, salt ^ 0x111L) * 0.12);
        double radiusX = hardRadius * baseRadiusX * lerp(
                0.78, 1.18, unitHash(seed, column, row, salt ^ 0x222L)
        );
        double radiusZ = hardRadius * baseRadiusZ * lerp(
                0.72, 1.22, unitHash(seed, column, row, salt ^ 0x333L)
        );
        double rotation = signedHash(seed, column, row, salt ^ 0x444L) * 1.35;
        return ellipseSignedDistance(
                x - offsetX,
                z - offsetZ,
                radiusX,
                radiusZ,
                rotation
        );
    }

    private static double ellipseSignedDistance(
            double x,
            double z,
            double radiusX,
            double radiusZ,
            double rotation
    ) {
        double cos = Math.cos(rotation);
        double sin = Math.sin(rotation);
        double rotatedX = x * cos + z * sin;
        double rotatedZ = -x * sin + z * cos;
        double normalized = Math.sqrt(
                (rotatedX * rotatedX) / (radiusX * radiusX)
                        + (rotatedZ * rotatedZ) / (radiusZ * radiusZ)
        );
        return (1.0 - normalized) * Math.min(radiusX, radiusZ);
    }

    /**
     * Canonical strategic size distribution:
     * 15% 500-750, 55% 750-1150, 20% 1150-1500, 7% 1500-1900,
     * 2.5% 1900-2400, and only 0.5% 2400-3000.
     */
    private static double chooseDiameter(long seed, long column, long row) {
        double bucket = unitHash(seed, column, row, 0xCBBB9D5DC1059ED8L);
        double within = unitHash(seed, column, row, 0x629A292A367CD507L);

        if (bucket < 0.15) {
            return lerp(MIN_DIAMETER, 750.0, within);
        }
        if (bucket < 0.70) {
            return lerp(750.0, 1_150.0, within);
        }
        if (bucket < 0.90) {
            return lerp(1_150.0, 1_500.0, within);
        }
        if (bucket < 0.97) {
            return lerp(1_500.0, 1_900.0, within);
        }
        if (bucket < 0.995) {
            return lerp(1_900.0, 2_400.0, within);
        }
        return lerp(2_400.0, MAX_DIAMETER, within);
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