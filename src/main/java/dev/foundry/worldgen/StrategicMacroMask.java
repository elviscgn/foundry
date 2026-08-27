package dev.foundry.worldgen;

import com.mojang.serialization.MapCodec;
import net.minecraft.util.KeyDispatchDataCodec;
import net.minecraft.world.level.levelgen.DensityFunction;

/**
 * Seeded strategic-scale geography envelope for Tiger Ascent.
 *
 * <p>Foundry owns strategic landmass scale and separation while Tectonic owns terrain character.
 * Each strategic region receives a seeded 500-3000 block size class, but its visible coastline is
 * no longer a radial blob. The landform is built from several overlapping rotated lobes, optional
 * detached satellite lobes, carved bays, and low-frequency coordinate warping. A separate hard
 * envelope and Voronoi sea corridor preserve the gameplay-size guarantees regardless of shape.</p>
 */
public record StrategicMacroMask(long seed) implements DensityFunction.SimpleFunction {
    public static final KeyDispatchDataCodec<StrategicMacroMask> CODEC =
            KeyDispatchDataCodec.of(MapCodec.unit(new StrategicMacroMask(0L)));

    // Only a search scaffold. Heavy seeded jitter prevents the scaffold from becoming visible.
    private static final double CELL_SPACING_X = 3_250.0;
    private static final double CELL_SPACING_Z = 2_815.0;
    private static final double CENTER_JITTER = 950.0;

    // Hard gameplay contract for substantial strategic landforms.
    private static final double MIN_DIAMETER = 500.0;
    private static final double MAX_DIAMETER = 3_000.0;

    // Guaranteed physical sea corridor between neighboring strategic regions.
    private static final double OCEAN_HALF_GAP = 155.0;

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

        // ±3 remains cheap while safely covering the stronger center jitter.
        for (long row = roughRow - 3; row <= roughRow + 3; row++) {
            double rowOffset = ((row & 1L) == 0L) ? 0.0 : CELL_SPACING_X * 0.5;
            long roughColumn = fastFloor((x - rowOffset) / CELL_SPACING_X);

            for (long column = roughColumn - 3; column <= roughColumn + 3; column++) {
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
        double hardRadius = diameter * 0.5;

        double dx = x - nearestCenterX;
        double dz = z - nearestCenterZ;

        // Rotate the entire regional coordinate frame first.
        double regionRotation = unitHash(seed, nearestColumn, nearestRow, 0x3C6EF372FE94F82BL) * Math.PI * 2.0;
        double cosR = Math.cos(regionRotation);
        double sinR = Math.sin(regionRotation);
        double localX = dx * cosR + dz * sinR;
        double localZ = -dx * sinR + dz * cosR;

        // Domain warp bends the whole coastline instead of merely adding fuzzy edge noise.
        double warpScale = Math.max(220.0, hardRadius * 0.62);
        double warpAmplitude = Math.max(20.0, Math.min(105.0, hardRadius * 0.085));
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

        // Main body: deliberately elongated and off-center.
        double mainOffsetX = signedHash(seed, nearestColumn, nearestRow, 0xA4093822299F31D0L) * hardRadius * 0.08;
        double mainOffsetZ = signedHash(seed, nearestColumn, nearestRow, 0x082EFA98EC4E6C89L) * hardRadius * 0.08;
        double mainRx = hardRadius * lerp(0.70, 0.88,
                unitHash(seed, nearestColumn, nearestRow, 0x452821E638D01377L));
        double mainRz = hardRadius * lerp(0.50, 0.72,
                unitHash(seed, nearestColumn, nearestRow, 0xBE5466CF34E90C6CL));
        double shape = ellipseSignedDistance(
                warpedX - mainOffsetX,
                warpedZ - mainOffsetZ,
                mainRx,
                mainRz,
                signedHash(seed, nearestColumn, nearestRow, 0xC0AC29B7C97C50DDL) * 0.45
        );

        // Two major overlapping lobes create peninsulas, shoulders, necks and asymmetry.
        shape = Math.max(shape, seededLobe(
                seed, nearestColumn, nearestRow, warpedX, warpedZ, hardRadius,
                0x3F84D5B5B5470917L,
                0.20, 0.42,
                0.36, 0.58,
                0.28, 0.50
        ));
        shape = Math.max(shape, seededLobe(
                seed, nearestColumn, nearestRow, warpedX, warpedZ, hardRadius,
                0x9216D5D98979FB1BL,
                0.28, 0.52,
                0.28, 0.50,
                0.22, 0.42
        ));

        // Larger strategic regions get a third shoulder. Small islands remain simpler.
        if (diameter >= 1_200.0) {
            shape = Math.max(shape, seededLobe(
                    seed, nearestColumn, nearestRow, warpedX, warpedZ, hardRadius,
                    0xD1310BA698DFB5ACL,
                    0.36, 0.60,
                    0.20, 0.36,
                    0.16, 0.30
            ));
        }

        // Occasional detached satellite island within the same strategic region envelope.
        if (diameter >= 850.0
                && unitHash(seed, nearestColumn, nearestRow, 0x2FFD72DBD01ADFB7L) < 0.42) {
            shape = Math.max(shape, seededLobe(
                    seed, nearestColumn, nearestRow, warpedX, warpedZ, hardRadius,
                    0xB8E1AFED6A267E96L,
                    0.62, 0.79,
                    0.10, 0.18,
                    0.09, 0.16
            ));
        }

        // Carve one or two genuine concave bays into medium/large coasts. These are subtractive
        // signed-distance fields, so they create inlets rather than another sinusoidal wobble.
        if (diameter >= 1_000.0) {
            shape = Math.min(shape, bayOutsideDistance(
                    seed, nearestColumn, nearestRow, warpedX, warpedZ, hardRadius,
                    0xBA7C9045F12C7F99L,
                    0.48, 0.70,
                    0.18, 0.30
            ));
        }
        if (diameter >= 1_850.0
                && unitHash(seed, nearestColumn, nearestRow, 0x24A19947B3916CF7L) < 0.58) {
            shape = Math.min(shape, bayOutsideDistance(
                    seed, nearestColumn, nearestRow, warpedX, warpedZ, hardRadius,
                    0x0801F2E2858EFC16L,
                    0.58, 0.78,
                    0.13, 0.24
            ));
        }

        // Fine coastline roughness. This can cut or add local detail, but the hard envelope below
        // remains the final authority over maximum strategic size.
        double coastScale = Math.max(105.0, hardRadius * 0.26);
        double coastAmplitude = Math.max(10.0, Math.min(54.0, hardRadius * 0.050));
        shape += valueNoise(
                seed,
                x / coastScale,
                z / coastScale,
                0x636920D871574E69L
        ) * coastAmplitude;

        // Irregular hard cap: never exceeds the user's selected size class, but avoids clipping to
        // a visible perfect circle if an outer lobe reaches the envelope.
        double worldAngle = Math.atan2(localZ, localX);
        double capPhase = unitHash(seed, nearestColumn, nearestRow, 0xA458FEA3F4933D7EL) * Math.PI * 2.0;
        double capFactor = 0.94
                + 0.035 * Math.sin(worldAngle * 3.0 + capPhase)
                + 0.020 * Math.sin(worldAngle * 5.0 - capPhase * 0.7);
        capFactor = Math.min(1.0, capFactor);
        double hardEnvelopeDistance = hardRadius * capFactor - nearestDistance;
        shape = Math.min(shape, hardEnvelopeDistance);

        // Voronoi retreat prevents neighboring regions from ever reconnecting, regardless of
        // center jitter or lobe orientation.
        double corridorSignedDistance = (secondDistance - nearestDistance) * 0.5 - OCEAN_HALF_GAP;
        double signedDistance = Math.min(shape, corridorSignedDistance);

        double coastFeather = Math.max(50.0, Math.min(145.0, hardRadius * 0.11));
        double value = LAND_THRESHOLD + (signedDistance / coastFeather) * 0.55;
        return Math.max(MIN_OUTPUT, Math.min(MAX_OUTPUT, value));
    }

    private static double seededLobe(
            long seed,
            long column,
            long row,
            double x,
            double z,
            double hardRadius,
            long salt,
            double minOffset,
            double maxOffset,
            double minRx,
            double maxRx,
            double minRz,
            double maxRz
    ) {
        double angle = unitHash(seed, column, row, salt) * Math.PI * 2.0;
        double offset = hardRadius * lerp(
                minOffset,
                maxOffset,
                unitHash(seed, column, row, salt ^ 0x9E3779B97F4A7C15L)
        );
        double cx = Math.cos(angle) * offset;
        double cz = Math.sin(angle) * offset;
        double rx = hardRadius * lerp(
                minRx,
                maxRx,
                unitHash(seed, column, row, salt ^ 0xC2B2AE3D27D4EB4FL)
        );
        double rz = hardRadius * lerp(
                minRz,
                maxRz,
                unitHash(seed, column, row, salt ^ 0x165667B19E3779F9L)
        );
        double rotation = signedHash(seed, column, row, salt ^ 0x85EBCA77C2B2AE63L) * 0.9;
        return ellipseSignedDistance(x - cx, z - cz, rx, rz, rotation);
    }

    private static double bayOutsideDistance(
            long seed,
            long column,
            long row,
            double x,
            double z,
            double hardRadius,
            long salt,
            double minOffset,
            double maxOffset,
            double minRadius,
            double maxRadius
    ) {
        double angle = unitHash(seed, column, row, salt) * Math.PI * 2.0;
        double offset = hardRadius * lerp(
                minOffset,
                maxOffset,
                unitHash(seed, column, row, salt ^ 0xD6E8FEB86659FD93L)
        );
        double cx = Math.cos(angle) * offset;
        double cz = Math.sin(angle) * offset;
        double radius = hardRadius * lerp(
                minRadius,
                maxRadius,
                unitHash(seed, column, row, salt ^ 0xA5A3564E27F8862BL)
        );
        return Math.sqrt((x - cx) * (x - cx) + (z - cz) * (z - cz)) - radius;
    }

    /** Approximate Euclidean signed distance to a rotated ellipse: positive inside, negative out. */
    private static double ellipseSignedDistance(
            double x,
            double z,
            double radiusX,
            double radiusZ,
            double rotation
    ) {
        double cos = Math.cos(rotation);
        double sin = Math.sin(rotation);
        double rx = x * cos + z * sin;
        double rz = -x * sin + z * cos;
        double normalized = Math.sqrt(
                (rx * rx) / (radiusX * radiusX)
                        + (rz * rz) / (radiusZ * radiusZ)
        );
        return (1.0 - normalized) * Math.min(radiusX, radiusZ);
    }

    /** Weighted size distribution: small islands exist, but most regions remain state-scale. */
    private static double chooseDiameter(long seed, long column, long row) {
        double bucket = unitHash(seed, column, row, 0xCBBB9D5DC1059ED8L);
        double within = unitHash(seed, column, row, 0x629A292A367CD507L);

        if (bucket < 0.07) {
            return lerp(MIN_DIAMETER, 900.0, within);       // 7% small: 500-900
        }
        if (bucket < 0.19) {
            return lerp(900.0, 1_600.0, within);           // 12% compact: 900-1600
        }
        if (bucket < 0.46) {
            return lerp(1_600.0, 2_200.0, within);         // 27% medium: 1600-2200
        }
        if (bucket < 0.78) {
            return lerp(2_200.0, 2_700.0, within);         // 32% major: 2200-2700
        }
        return lerp(2_700.0, MAX_DIAMETER, within);        // 22% large: 2700-3000
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
