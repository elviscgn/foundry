package dev.foundry.worldgen;

import com.mojang.serialization.MapCodec;
import net.minecraft.util.KeyDispatchDataCodec;
import net.minecraft.world.level.levelgen.DensityFunction;

/**
 * Seeded strategic-scale geography envelope for Tiger Ascent.
 *
 * <p>Foundry owns strategic landmass scale/separation while Tectonic owns physical terrain
 * character. Strategic regions are not generated one-per-cell: a correlated activity field creates
 * clusters of large mainlands, medium/small fringe islands, and genuine open-ocean basins. Each
 * active region is then assembled from overlapping rotated lobes, peninsulas, optional satellites
 * and coordinate warping.</p>
 *
 * <p>The hidden lattice is only a bounded candidate-search structure. Correlated activation,
 * heavy center jitter and a non-radial hard envelope prevent that scaffold from becoming visible in
 * the coastline. Substantial islands remain roughly 500-3000 blocks across.</p>
 */
public record StrategicMacroMask(long seed) implements DensityFunction.SimpleFunction {
    public static final KeyDispatchDataCodec<StrategicMacroMask> CODEC =
            KeyDispatchDataCodec.of(MapCodec.unit(new StrategicMacroMask(0L)));

    private static final double CELL_SPACING_X = 3_250.0;
    private static final double CELL_SPACING_Z = 2_815.0;
    private static final double CENTER_JITTER = 1_200.0;

    private static final double MIN_DIAMETER = 500.0;
    private static final double MAX_DIAMETER = 3_000.0;
    private static final double OCEAN_HALF_GAP = 155.0;

    // Only the low tail becomes empty strategic ocean. Correlation creates broad gaps without
    // sacrificing the ~30-35% land-share neighborhood that already tested well in game.
    private static final double ACTIVITY_THRESHOLD = -0.45;
    private static final double ACTIVITY_MACRO_WEIGHT = 0.50;
    private static final double ACTIVITY_LOCAL_WEIGHT = 0.50;
    private static final double ACTIVITY_SCALE = 2.10;

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

        double nearestDistance = Double.POSITIVE_INFINITY;
        double secondDistance = Double.POSITIVE_INFINITY;
        long nearestColumn = 0L;
        long nearestRow = 0L;
        double nearestCenterX = 0.0;
        double nearestCenterZ = 0.0;

        for (long row = roughRow - 4; row <= roughRow + 4; row++) {
            double rowOffset = ((row & 1L) == 0L) ? 0.0 : CELL_SPACING_X * 0.5;
            long roughColumn = fastFloor((x - rowOffset) / CELL_SPACING_X);

            for (long column = roughColumn - 4; column <= roughColumn + 4; column++) {
                if (!isRegionActive(seed, column, row)) {
                    continue;
                }

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

        if (!Double.isFinite(nearestDistance)) {
            return MIN_OUTPUT;
        }

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

        double warpScale = Math.max(120.0, hardRadius * 0.28);
        double warpAmplitude = Math.max(16.0, Math.min(105.0, hardRadius * 0.085));
        double warpedX = localX + valueNoise(
                seed,
                x / warpScale,
                z / warpScale,
                0x243F6A8885A308D3L
        ) * warpAmplitude;
        double warpedZ = localZ + valueNoise(
                seed,
                x / warpScale,
                z / warpScale,
                0x13198A2E03707344L
        ) * warpAmplitude;

        double activity = regionActivity(seed, nearestColumn, nearestRow);
        double mainAspect = lerp(
                0.48,
                0.66,
                unitHash(seed, nearestColumn, nearestRow, 0xC0AC29B7C97C50DDL)
        );
        if (activity > 0.30) {
            mainAspect *= 0.95;
        }

        double shape = ellipseSignedDistance(
                warpedX,
                warpedZ,
                hardRadius * 0.68,
                hardRadius * mainAspect,
                signedHash(seed, nearestColumn, nearestRow, 0xC0AC29B7C97C50DDL) * 0.70
        );

        shape = Math.max(shape, regionalLobe(
                seed, nearestColumn, nearestRow, warpedX, warpedZ, hardRadius,
                0x3F84D5B5B5470917L,
                -0.40, 0.10, 0.48, 0.29
        ));
        shape = Math.max(shape, regionalLobe(
                seed, nearestColumn, nearestRow, warpedX, warpedZ, hardRadius,
                0x9216D5D98979FB1BL,
                0.39, -0.12, 0.47, 0.28
        ));
        shape = Math.max(shape, regionalLobe(
                seed, nearestColumn, nearestRow, warpedX, warpedZ, hardRadius,
                0xD1310BA698DFB5ACL,
                0.02, 0.39, 0.33, 0.20
        ));
        shape = Math.max(shape, regionalLobe(
                seed, nearestColumn, nearestRow, warpedX, warpedZ, hardRadius,
                0xB8E1AFED6A267E96L,
                0.12, -0.39, 0.32, 0.19
        ));
        shape = Math.max(shape, regionalLobe(
                seed, nearestColumn, nearestRow, warpedX, warpedZ, hardRadius,
                0xBA7C9045F12C7F99L,
                -0.17, 0.25, 0.26, 0.16
        ));

        if (diameter >= 1_500.0) {
            long salt = 0x24A19947B3916CF7L;
            double side = unitHash(seed, nearestColumn, nearestRow, salt) < 0.5 ? -1.0 : 1.0;
            double peninsulaAngle = (side < 0.0 ? Math.PI : 0.0)
                    + signedHash(seed, nearestColumn, nearestRow, salt ^ 0x55L) * 0.55;
            double offset = hardRadius * 0.61;
            double peninsulaX = Math.cos(peninsulaAngle) * offset;
            double peninsulaZ = Math.sin(peninsulaAngle) * offset;
            shape = Math.max(shape, ellipseSignedDistance(
                    warpedX - peninsulaX,
                    warpedZ - peninsulaZ,
                    hardRadius * 0.32,
                    hardRadius * 0.13,
                    signedHash(seed, nearestColumn, nearestRow, salt ^ 0x66L) * 0.95
            ));
        }

        if (diameter >= 1_800.0) {
            double satelliteBoost = activity > 0.20 ? 0.14 : 0.0;
            shape = Math.max(shape, satelliteIsland(
                    seed, nearestColumn, nearestRow, warpedX, warpedZ, hardRadius,
                    0x0801F2E2858EFC16L,
                    0.58 + satelliteBoost
            ));
            shape = Math.max(shape, satelliteIsland(
                    seed, nearestColumn, nearestRow, warpedX, warpedZ, hardRadius,
                    0xA5A3564E27F8862BL,
                    0.25 + satelliteBoost
            ));
        }

        double coastScale = Math.max(70.0, hardRadius * 0.14);
        double coastAmplitude = Math.max(8.0, Math.min(55.0, hardRadius * 0.060));
        shape += valueNoise(
                seed,
                x / coastScale,
                z / coastScale,
                0x636920D871574E69L
        ) * coastAmplitude;

        shape += hardRadius * 0.14;

        // Hard maximum is now an elongated, rotated, softly warped envelope rather than a circle.
        // Its long axis stays below the selected 500-3000 block contract.
        double capAspect = lerp(
                0.70,
                0.95,
                unitHash(seed, nearestColumn, nearestRow, 0xA458FEA3F4933D7EL)
        );
        double capRotation = signedHash(seed, nearestColumn, nearestRow, 0x8F1BBCDCB7A56463L) * 0.55;
        double cap = ellipseSignedDistance(
                localX,
                localZ,
                hardRadius * 0.97,
                hardRadius * capAspect,
                capRotation
        );
        double capNoiseScale = Math.max(180.0, hardRadius * 0.50);
        double capNoiseAmplitude = Math.min(36.0, hardRadius * 0.030);
        cap += valueNoise(
                seed,
                x / capNoiseScale,
                z / capNoiseScale,
                0xE49B69C19EF14AD2L
        ) * capNoiseAmplitude;
        shape = Math.min(shape, cap);

        double corridorSignedDistance = Double.isFinite(secondDistance)
                ? (secondDistance - nearestDistance) * 0.5 - OCEAN_HALF_GAP
                : Double.POSITIVE_INFINITY;
        double signedDistance = Math.min(shape, corridorSignedDistance);

        double coastFeather = Math.max(50.0, Math.min(145.0, hardRadius * 0.11));
        double value = LAND_THRESHOLD + (signedDistance / coastFeather) * 0.55;
        return Math.max(MIN_OUTPUT, Math.min(MAX_OUTPUT, value));
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
                + signedHash(seed, column, row, salt) * 0.12);
        double offsetZ = hardRadius * (baseOffsetZ
                + signedHash(seed, column, row, salt ^ 0x111L) * 0.13);
        double radiusX = hardRadius * baseRadiusX * lerp(
                0.78,
                1.20,
                unitHash(seed, column, row, salt ^ 0x222L)
        );
        double radiusZ = hardRadius * baseRadiusZ * lerp(
                0.76,
                1.18,
                unitHash(seed, column, row, salt ^ 0x333L)
        );
        double rotation = signedHash(seed, column, row, salt ^ 0x444L) * 1.10;
        return ellipseSignedDistance(
                x - offsetX,
                z - offsetZ,
                radiusX,
                radiusZ,
                rotation
        );
    }

    private static double satelliteIsland(
            long seed,
            long column,
            long row,
            double x,
            double z,
            double hardRadius,
            long salt,
            double chance
    ) {
        if (unitHash(seed, column, row, salt ^ 0x999L) >= chance) {
            return Double.NEGATIVE_INFINITY;
        }

        double angle = unitHash(seed, column, row, salt) * Math.PI * 2.0;
        double offset = hardRadius * lerp(
                0.68,
                0.80,
                unitHash(seed, column, row, salt ^ 0x123L)
        );
        double centerX = Math.cos(angle) * offset;
        double centerZ = Math.sin(angle) * offset;

        double satelliteLongRadius = Math.max(
                250.0,
                Math.min(350.0, hardRadius * lerp(
                        0.18,
                        0.23,
                        unitHash(seed, column, row, salt ^ 0x456L)
                ))
        );
        double satelliteShortRadius = satelliteLongRadius * lerp(
                0.58,
                0.86,
                unitHash(seed, column, row, salt ^ 0x789L)
        );
        double rotation = signedHash(seed, column, row, salt ^ 0xABCL) * 1.20;

        return ellipseSignedDistance(
                x - centerX,
                z - centerZ,
                satelliteLongRadius,
                satelliteShortRadius,
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
     * Preserve the successful broad size distribution, but let high-activity cluster cores bias
     * toward major 2-3k mainlands. Fringe cells still supply the full 500-1600 island range.
     */
    private static double chooseDiameter(long seed, long column, long row) {
        double activity = regionActivity(seed, column, row);
        double bucket = unitHash(seed, column, row, 0xCBBB9D5DC1059ED8L);
        double within = unitHash(seed, column, row, 0x629A292A367CD507L);

        if (activity > 0.30) {
            return lerp(2_600.0, MAX_DIAMETER, within);
        }
        if (activity > 0.10 && bucket < 0.55) {
            return lerp(2_200.0, 2_850.0, within);
        }

        if (bucket < 0.07) {
            return lerp(MIN_DIAMETER, 900.0, within);
        }
        if (bucket < 0.19) {
            return lerp(900.0, 1_600.0, within);
        }
        if (bucket < 0.46) {
            return lerp(1_600.0, 2_200.0, within);
        }
        if (bucket < 0.78) {
            return lerp(2_200.0, 2_700.0, within);
        }
        return lerp(2_700.0, MAX_DIAMETER, within);
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
