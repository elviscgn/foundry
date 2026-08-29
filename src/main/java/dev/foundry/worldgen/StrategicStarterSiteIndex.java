package dev.foundry.worldgen;

import java.util.ArrayList;
import java.util.List;

/**
 * Search-only index of StrategicMacroMask's deterministic strategic-site lattice.
 *
 * <p>This mirrors only the site activity, jitter and diameter math from StrategicMacroMask so the
 * starter-seed curator can enumerate candidate islands inside a regional radius without rastering
 * the entire 10k x 10k area. Physical acceptance is still decided by the live Tectonic generator,
 * never by this index.</p>
 */
final class StrategicStarterSiteIndex {
    private static final double CELL_SPACING_X = 1_050.0;
    private static final double CELL_SPACING_Z = 909.0;
    private static final double CENTER_JITTER = 150.0;

    private static final double MIN_DIAMETER = 500.0;
    private static final double MAX_DIAMETER = 3_000.0;

    private static final double ACTIVITY_THRESHOLD = -0.50;
    private static final double ACTIVITY_MACRO_WEIGHT = 0.40;
    private static final double ACTIVITY_LOCAL_WEIGHT = 0.60;
    private static final double ACTIVITY_SCALE = 1.80;

    private StrategicStarterSiteIndex() {
    }

    static List<Site> sitesWithin(long seed, double radius) {
        List<Site> sites = new ArrayList<>();
        double padded = radius + CENTER_JITTER + CELL_SPACING_X;
        long minRow = fastFloor(-padded / CELL_SPACING_Z) - 1L;
        long maxRow = fastFloor(padded / CELL_SPACING_Z) + 1L;

        for (long row = minRow; row <= maxRow; row++) {
            double rowOffset = ((row & 1L) == 0L) ? 0.0 : CELL_SPACING_X * 0.5;
            long minColumn = fastFloor((-padded - rowOffset) / CELL_SPACING_X) - 1L;
            long maxColumn = fastFloor((padded - rowOffset) / CELL_SPACING_X) + 1L;

            for (long column = minColumn; column <= maxColumn; column++) {
                if (!isRegionActive(seed, column, row)) {
                    continue;
                }

                double centerX = column * CELL_SPACING_X + rowOffset
                        + signedHash(seed, column, row, 0x6A09E667F3BCC909L) * CENTER_JITTER;
                double centerZ = row * CELL_SPACING_Z
                        + signedHash(seed, column, row, 0xBB67AE8584CAA73BL) * CENTER_JITTER;
                if (Math.hypot(centerX, centerZ) > radius) {
                    continue;
                }

                sites.add(new Site(
                        (int) Math.round(centerX),
                        (int) Math.round(centerZ),
                        chooseDiameter(seed, column, row)
                ));
            }
        }
        return sites;
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

    record Site(int centerX, int centerZ, double nominalDiameter) {
    }
}
