package dev.foundry.worldgen;

import com.tom.createores.recipe.VeinRecipe;
import dev.foundry.Foundry;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.structure.placement.RandomSpreadStructurePlacement;
import net.minecraft.world.level.levelgen.synth.NormalNoise;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;

/**
 * Headless, continuous starter-seed curator used by the Gradle starterSeedSearch task.
 *
 * Search pipeline:
 *  1. StrategicMacroMask + COE placement cheaply screen 20k deterministic seeds at a time.
 *  2. The best strategic candidates get a 32-block real-Tectonic physical island raster.
 *  3. Only the best physical candidates pay for the authoritative 16-block raster.
 *
 * The CLI deliberately does not spend time sampling biomes: biome is unrestricted for the
 * canonical starter search. The accepted seed is therefore selected by physical island geometry,
 * separation, relief, spawn proximity, and coal placement only.
 */
@Mod.EventBusSubscriber(modid = Foundry.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class StarterSeedCli {
    private static final String ENABLE_PROPERTY = "foundry.starterSeedSearch";
    private static final ResourceLocation COAL_VEIN =
            new ResourceLocation("createoreexcavation", "ore_vein_type/coal");

    private static final int BATCH_SIZE = 20_000;
    private static final int MEDIUM_CANDIDATES_PER_BATCH = 8;
    private static final int EXACT_FINALISTS_PER_BATCH = 2;

    // 41x41 = 1,681 real height probes per medium candidate.
    private static final int MEDIUM_RADIUS = 640;
    private static final int MEDIUM_STEP = 32;

    // 81x81 = 6,561 probes, used only for the final two candidates.
    private static final int EXACT_RADIUS = 640;
    private static final int EXACT_STEP = 16;

    // Hard acceptance gates for the canonical Tiger Ascent starter geography.
    private static final int MIN_ISLAND_SPAN = 240;
    private static final int MAX_ISLAND_SPAN = 420;
    private static final int MIN_NEIGHBOR_GAP = 180;
    private static final int MAX_NEIGHBOR_GAP = 450;
    private static final int MAX_ORIGIN_DISTANCE = 180;
    private static final double MAX_HEIGHT_STD_DEV = 10.0;

    private static final double TARGET_ISLAND_SPAN = 320.0;
    private static final double TARGET_NEIGHBOR_GAP = 300.0;

    private StarterSeedCli() {
    }

    @SubscribeEvent
    public static void onServerStarted(ServerStartedEvent event) {
        if (!Boolean.getBoolean(ENABLE_PROPERTY)) {
            return;
        }

        MinecraftServer server = event.getServer();
        System.out.println("[Foundry] CLI starter-seed search enabled.");
        System.out.println("[Foundry] Pipeline: 20k cheap seeds -> 8 real 32-block island rasters -> 2 exact 16-block finalists.");
        System.out.println(String.format(
                Locale.ROOT,
                "[Foundry] FINAL GATES: island %d-%d | neighbor %d-%d | starter land <= %d from origin | height SD <= %.1f | coal ON ISLAND | biome unrestricted",
                MIN_ISLAND_SPAN,
                MAX_ISLAND_SPAN,
                MIN_NEIGHBOR_GAP,
                MAX_NEIGHBOR_GAP,
                MAX_ORIGIN_DISTANCE,
                MAX_HEIGHT_STD_DEV
        ));
        System.out.println("[Foundry] Winner:    run-seed-search/starter-seed-result.txt");
        System.out.println("[Foundry] Near miss: run-seed-search/starter-seed-nearmiss.txt");

        CompletableFuture.runAsync(() -> runUntilFound(server));
    }

    private static void runUntilFound(MinecraftServer server) {
        try {
            ServerLevel level = server.overworld();
            if (!(level.getChunkSource().getGenerator() instanceof NoiseBasedChunkGenerator generator)) {
                throw new IllegalStateException("Overworld is not using a NoiseBasedChunkGenerator");
            }

            VeinRecipe coal = level.getRecipeManager().byKey(COAL_VEIN)
                    .filter(VeinRecipe.class::isInstance)
                    .map(VeinRecipe.class::cast)
                    .orElseThrow(() -> new IllegalStateException("COE coal vein recipe is not loaded"));
            RandomSpreadStructurePlacement coalPlacement = coal.getPlacement();

            NoiseGeneratorSettings settings = generator.generatorSettings().value();
            Registry<NormalNoise.NoiseParameters> noiseRegistry =
                    level.registryAccess().registryOrThrow(Registries.NOISE);

            Method coarseMethod = StarterSeedSearch.class.getDeclaredMethod(
                    "coarseCandidate",
                    long.class,
                    RandomSpreadStructurePlacement.class
            );
            coarseMethod.setAccessible(true);

            long tested = 0L;
            long globalIndex = 0L;
            long startedNanos = System.nanoTime();
            PhysicalCandidate bestNearMiss = null;
            double bestNearMissScore = Double.POSITIVE_INFINITY;

            while (server.isRunning()) {
                List<ScoredCandidate> coarse = new ArrayList<>();
                long batchFirstSeedIndex = globalIndex;

                for (int i = 0; i < BATCH_SIZE && server.isRunning(); i++, globalIndex++) {
                    long seed = alternatingSeed(globalIndex);
                    Object candidate = coarseMethod.invoke(null, seed, coalPlacement);
                    tested++;
                    if (candidate != null) {
                        coarse.add(new ScoredCandidate(
                                seed,
                                doubleAccessor(candidate, "score")
                        ));
                    }
                }

                coarse.sort(Comparator.comparingDouble(ScoredCandidate::score));
                int mediumCount = Math.min(MEDIUM_CANDIDATES_PER_BATCH, coarse.size());
                System.out.println(String.format(
                        Locale.ROOT,
                        "[Foundry] %,d seeds cheap-screened | %,d strategic+coal survivors | physical-screening %d...",
                        tested,
                        coarse.size(),
                        mediumCount
                ));

                List<PhysicalCandidate> medium = new ArrayList<>(mediumCount);
                for (int i = 0; i < mediumCount && server.isRunning(); i++) {
                    ScoredCandidate candidate = coarse.get(i);
                    RandomState randomState = RandomState.create(
                            settings,
                            noiseRegistry.asLookup(),
                            candidate.seed()
                    );
                    PhysicalCandidate physical = evaluatePhysical(
                            level,
                            generator,
                            randomState,
                            coalPlacement,
                            candidate.seed(),
                            MEDIUM_RADIUS,
                            MEDIUM_STEP
                    );
                    if (physical != null) {
                        medium.add(physical.withScore(mediumSelectionScore(physical, candidate.score())));
                    }
                }

                medium.sort(Comparator.comparingDouble(PhysicalCandidate::score));
                int exactCount = Math.min(EXACT_FINALISTS_PER_BATCH, medium.size());
                System.out.println(String.format(
                        Locale.ROOT,
                        "[Foundry] physical screen kept %d/%d | exact-verifying %d...",
                        medium.size(),
                        mediumCount,
                        exactCount
                ));

                PhysicalCandidate bestBatchExact = null;
                double bestBatchMissScore = Double.POSITIVE_INFINITY;

                for (int i = 0; i < exactCount && server.isRunning(); i++) {
                    PhysicalCandidate preview = medium.get(i);
                    PhysicalCandidate exact = evaluatePhysical(
                            level,
                            generator,
                            preview.randomState(),
                            coalPlacement,
                            preview.seed(),
                            EXACT_RADIUS,
                            EXACT_STEP
                    );
                    if (exact == null) {
                        System.out.println("[Foundry] exact seed " + preview.seed() + " had no usable physical island near origin.");
                        continue;
                    }

                    double missScore = hardGateMissScore(exact);
                    if (missScore < bestBatchMissScore) {
                        bestBatchMissScore = missScore;
                        bestBatchExact = exact;
                    }

                    if (missScore < bestNearMissScore) {
                        bestNearMissScore = missScore;
                        bestNearMiss = exact;
                        saveNearMiss(exact, tested, startedNanos);
                    }

                    if (passesHardGates(exact)) {
                        printAndSaveResult(exact, tested, startedNanos);
                        server.execute(() -> server.halt(false));
                        return;
                    }

                    System.out.println("[Foundry] near miss: " + summarize(exact));
                    System.out.println("[Foundry] FAIL: " + failureReasons(exact));
                }

                double elapsedSeconds = (System.nanoTime() - startedNanos) / 1_000_000_000.0;
                double seedsPerSecond = tested / Math.max(0.001, elapsedSeconds);
                String bestSummary = bestBatchExact == null
                        ? "no exact physical candidate survived"
                        : summarize(bestBatchExact) + " | FAIL: " + failureReasons(bestBatchExact);
                System.out.println(String.format(
                        Locale.ROOT,
                        "[Foundry] batch index %,d-%,d complete | tested %,d total | %.0f seeds/s overall | best: %s",
                        batchFirstSeedIndex,
                        globalIndex - 1,
                        tested,
                        seedsPerSecond,
                        bestSummary
                ));
            }
        } catch (Throwable error) {
            System.err.println("[Foundry] CLI starter-seed search failed: " + error);
            error.printStackTrace();
            server.execute(() -> server.halt(false));
        }
    }

    private static PhysicalCandidate evaluatePhysical(
            ServerLevel level,
            NoiseBasedChunkGenerator generator,
            RandomState randomState,
            RandomSpreadStructurePlacement coalPlacement,
            long seed,
            int radius,
            int step
    ) {
        int cells = radius * 2 / step + 1;
        boolean[][] land = new boolean[cells][cells];
        int[][] heights = new int[cells][cells];
        int seaLevel = generator.getSeaLevel();

        int nearestX = -1;
        int nearestZ = -1;
        double nearestOriginSq = Double.POSITIVE_INFINITY;

        for (int gz = 0; gz < cells; gz++) {
            int z = -radius + gz * step;
            for (int gx = 0; gx < cells; gx++) {
                int x = -radius + gx * step;
                int height = generator.getBaseHeight(
                        x,
                        z,
                        Heightmap.Types.OCEAN_FLOOR_WG,
                        level,
                        randomState
                );
                heights[gz][gx] = height;
                if (height <= seaLevel) {
                    continue;
                }

                land[gz][gx] = true;
                double distanceSq = (double) x * x + (double) z * z;
                if (distanceSq < nearestOriginSq) {
                    nearestOriginSq = distanceSq;
                    nearestX = gx;
                    nearestZ = gz;
                }
            }
        }

        if (nearestX < 0) {
            return null;
        }

        LandComponent component = floodComponent(land, nearestX, nearestZ, step, radius);
        if (component == null || component.cells().isEmpty()) {
            return null;
        }

        int neighborGap = nearestOtherLandGap(land, component, step, radius);

        double heightSum = 0.0;
        double heightSqSum = 0.0;
        for (Cell cell : component.cells()) {
            int height = heights[cell.gridZ()][cell.gridX()];
            heightSum += height;
            heightSqSum += (double) height * height;
        }
        int heightCount = component.cells().size();
        double mean = heightSum / Math.max(1, heightCount);
        double variance = heightSqSum / Math.max(1, heightCount) - mean * mean;
        double heightStdDev = Math.sqrt(Math.max(0.0, variance));

        CoalLocation coal = nearestCoal(
                seed,
                coalPlacement,
                component.centerX(),
                component.centerZ(),
                1_200
        );
        if (coal == null) {
            return null;
        }

        boolean coalOnIsland = containsWorld(component, coal.x(), coal.z(), step, radius);
        int coalDistance = distanceToComponent(component, coal.x(), coal.z(), step, radius);

        return new PhysicalCandidate(
                seed,
                0.0,
                component.width(),
                component.height(),
                neighborGap,
                (int) Math.round(Math.sqrt(nearestOriginSq)),
                heightStdDev,
                coal.x(),
                coal.z(),
                coalOnIsland,
                coalDistance,
                randomState
        );
    }

    private static double mediumSelectionScore(PhysicalCandidate candidate, double coarseScore) {
        int span = Math.max(candidate.width(), candidate.height());
        double score = coarseScore * 0.20
                + Math.abs(span - TARGET_ISLAND_SPAN)
                + Math.abs(candidate.neighborGap() - TARGET_NEIGHBOR_GAP) * 0.70
                + candidate.heightStdDev() * 16.0
                + candidate.originDistance() * 0.60;

        score += outsideDistance(span, MIN_ISLAND_SPAN, MAX_ISLAND_SPAN) * 6.0;
        score += outsideDistance(candidate.neighborGap(), MIN_NEIGHBOR_GAP, MAX_NEIGHBOR_GAP) * 3.0;
        score += Math.max(0.0, candidate.heightStdDev() - MAX_HEIGHT_STD_DEV) * 90.0;
        score += Math.max(0, candidate.originDistance() - MAX_ORIGIN_DISTANCE) * 5.0;
        if (!candidate.coalOnIsland()) {
            score += 450.0 + candidate.coalDistance() * 5.0;
        }
        return score;
    }

    private static boolean passesHardGates(PhysicalCandidate candidate) {
        int span = Math.max(candidate.width(), candidate.height());
        return span >= MIN_ISLAND_SPAN
                && span <= MAX_ISLAND_SPAN
                && candidate.neighborGap() >= MIN_NEIGHBOR_GAP
                && candidate.neighborGap() <= MAX_NEIGHBOR_GAP
                && candidate.originDistance() <= MAX_ORIGIN_DISTANCE
                && candidate.heightStdDev() <= MAX_HEIGHT_STD_DEV
                && candidate.coalOnIsland();
    }

    private static double hardGateMissScore(PhysicalCandidate candidate) {
        int span = Math.max(candidate.width(), candidate.height());
        double score = outsideDistance(span, MIN_ISLAND_SPAN, MAX_ISLAND_SPAN) * 6.0
                + outsideDistance(candidate.neighborGap(), MIN_NEIGHBOR_GAP, MAX_NEIGHBOR_GAP) * 3.0
                + Math.max(0.0, candidate.heightStdDev() - MAX_HEIGHT_STD_DEV) * 100.0
                + Math.max(0, candidate.originDistance() - MAX_ORIGIN_DISTANCE) * 5.0;
        if (!candidate.coalOnIsland()) {
            score += 500.0 + candidate.coalDistance() * 5.0;
        }
        // Among equally passing/near-passing candidates, prefer the requested ~320/~300 shape.
        score += Math.abs(span - TARGET_ISLAND_SPAN) * 0.02;
        score += Math.abs(candidate.neighborGap() - TARGET_NEIGHBOR_GAP) * 0.01;
        return score;
    }

    private static double outsideDistance(double value, double min, double max) {
        if (value < min) {
            return min - value;
        }
        if (value > max) {
            return value - max;
        }
        return 0.0;
    }

    private static String failureReasons(PhysicalCandidate candidate) {
        List<String> failures = new ArrayList<>();
        int span = Math.max(candidate.width(), candidate.height());
        if (span < MIN_ISLAND_SPAN) {
            failures.add("island span " + span + " < " + MIN_ISLAND_SPAN);
        } else if (span > MAX_ISLAND_SPAN) {
            failures.add("island span " + span + " > " + MAX_ISLAND_SPAN);
        }
        if (candidate.neighborGap() < MIN_NEIGHBOR_GAP) {
            failures.add("neighbor gap " + candidate.neighborGap() + " < " + MIN_NEIGHBOR_GAP);
        } else if (candidate.neighborGap() > MAX_NEIGHBOR_GAP) {
            failures.add("neighbor gap " + candidate.neighborGap() + " > " + MAX_NEIGHBOR_GAP);
        }
        if (candidate.originDistance() > MAX_ORIGIN_DISTANCE) {
            failures.add("starter land " + candidate.originDistance() + " from origin > " + MAX_ORIGIN_DISTANCE);
        }
        if (candidate.heightStdDev() > MAX_HEIGHT_STD_DEV) {
            failures.add(String.format(Locale.ROOT, "height SD %.1f > %.1f", candidate.heightStdDev(), MAX_HEIGHT_STD_DEV));
        }
        if (!candidate.coalOnIsland()) {
            failures.add("coal OFF ISLAND by ~" + candidate.coalDistance() + " blocks");
        }
        return failures.isEmpty() ? "none" : String.join("; ", failures);
    }

    private static void printAndSaveResult(PhysicalCandidate candidate, long tested, long startedNanos)
            throws IOException {
        double elapsedSeconds = (System.nanoTime() - startedNanos) / 1_000_000_000.0;
        String result = String.format(
                Locale.ROOT,
                "========================================%n"
                        + "FOUNDRY STARTER SEED FOUND%n"
                        + "========================================%n"
                        + "Seed: %d%n%n"
                        + "Island:       %d x %d%n"
                        + "Neighbor gap: %d blocks%n"
                        + "Origin land:  %d blocks%n"
                        + "Height SD:    %.2f%n"
                        + "Coal:         ON ISLAND at %d, %d%n"
                        + "Biome:        unrestricted / not scored%n"
                        + "Seeds tested: %,d%n"
                        + "Elapsed:      %.1f seconds%n"
                        + "========================================%n",
                candidate.seed(),
                candidate.width(),
                candidate.height(),
                candidate.neighborGap(),
                candidate.originDistance(),
                candidate.heightStdDev(),
                candidate.coalX(),
                candidate.coalZ(),
                tested,
                elapsedSeconds
        );

        System.out.print(result);
        Files.writeString(Path.of("starter-seed-result.txt"), result, StandardCharsets.UTF_8);
        System.out.println("[Foundry] Saved result to run-seed-search/starter-seed-result.txt");
    }

    private static void saveNearMiss(PhysicalCandidate candidate, long tested, long startedNanos)
            throws IOException {
        double elapsedSeconds = (System.nanoTime() - startedNanos) / 1_000_000_000.0;
        String result = String.format(
                Locale.ROOT,
                "FOUNDRY BEST STARTER-SEED NEAR MISS%n"
                        + "Seed: %d%n"
                        + "Island: %d x %d%n"
                        + "Neighbor gap: %d blocks%n"
                        + "Origin land: %d blocks%n"
                        + "Height SD: %.2f%n"
                        + "Coal: %s at %d, %d (distance to island ~%d)%n"
                        + "FAIL: %s%n"
                        + "Seeds tested when recorded: %,d%n"
                        + "Elapsed when recorded: %.1f seconds%n",
                candidate.seed(),
                candidate.width(),
                candidate.height(),
                candidate.neighborGap(),
                candidate.originDistance(),
                candidate.heightStdDev(),
                candidate.coalOnIsland() ? "ON ISLAND" : "OFF ISLAND",
                candidate.coalX(),
                candidate.coalZ(),
                candidate.coalDistance(),
                failureReasons(candidate),
                tested,
                elapsedSeconds
        );
        Files.writeString(Path.of("starter-seed-nearmiss.txt"), result, StandardCharsets.UTF_8);
    }

    private static String summarize(PhysicalCandidate candidate) {
        return String.format(
                Locale.ROOT,
                "seed %d | %dx%d | gap %d | origin %d | height SD %.1f | coal %s%s",
                candidate.seed(),
                candidate.width(),
                candidate.height(),
                candidate.neighborGap(),
                candidate.originDistance(),
                candidate.heightStdDev(),
                candidate.coalOnIsland() ? "ON ISLAND" : "OFF ISLAND",
                candidate.coalOnIsland() ? "" : " (~" + candidate.coalDistance() + " blocks)"
        );
    }

    private static LandComponent floodComponent(
            boolean[][] land,
            int startX,
            int startZ,
            int step,
            int radius
    ) {
        int size = land.length;
        boolean[][] visited = new boolean[size][size];
        ArrayDeque<Cell> queue = new ArrayDeque<>();
        List<Cell> cells = new ArrayList<>();
        queue.add(new Cell(startX, startZ));
        visited[startZ][startX] = true;

        int minX = startX;
        int maxX = startX;
        int minZ = startZ;
        int maxZ = startZ;
        int[] dx = {1, -1, 0, 0};
        int[] dz = {0, 0, 1, -1};

        while (!queue.isEmpty()) {
            Cell cell = queue.removeFirst();
            cells.add(cell);
            minX = Math.min(minX, cell.gridX());
            maxX = Math.max(maxX, cell.gridX());
            minZ = Math.min(minZ, cell.gridZ());
            maxZ = Math.max(maxZ, cell.gridZ());

            for (int direction = 0; direction < 4; direction++) {
                int nx = cell.gridX() + dx[direction];
                int nz = cell.gridZ() + dz[direction];
                if (nx < 0 || nz < 0 || nx >= size || nz >= size) {
                    continue;
                }
                if (!land[nz][nx] || visited[nz][nx]) {
                    continue;
                }
                visited[nz][nx] = true;
                queue.addLast(new Cell(nx, nz));
            }
        }

        int centerGridX = (minX + maxX) / 2;
        int centerGridZ = (minZ + maxZ) / 2;
        return new LandComponent(
                cells,
                visited,
                (maxX - minX + 1) * step,
                (maxZ - minZ + 1) * step,
                -radius + centerGridX * step,
                -radius + centerGridZ * step
        );
    }

    private static int nearestOtherLandGap(
            boolean[][] land,
            LandComponent component,
            int step,
            int radius
    ) {
        int bestSq = Integer.MAX_VALUE;
        int size = land.length;
        for (int gz = 0; gz < size; gz++) {
            for (int gx = 0; gx < size; gx++) {
                if (!land[gz][gx] || component.visited()[gz][gx]) {
                    continue;
                }
                for (Cell own : component.cells()) {
                    int dx = (gx - own.gridX()) * step;
                    int dz = (gz - own.gridZ()) * step;
                    int distanceSq = dx * dx + dz * dz;
                    if (distanceSq < bestSq) {
                        bestSq = distanceSq;
                    }
                }
            }
        }
        if (bestSq == Integer.MAX_VALUE) {
            return radius * 2;
        }
        return Math.max(0, (int) Math.round(Math.sqrt(bestSq)) - step);
    }

    private static CoalLocation nearestCoal(
            long seed,
            RandomSpreadStructurePlacement placement,
            int targetX,
            int targetZ,
            int searchRadius
    ) {
        int spacing = placement.spacing();
        int targetChunkX = Math.floorDiv(targetX, 16);
        int targetChunkZ = Math.floorDiv(targetZ, 16);
        int targetRegionX = Math.floorDiv(targetChunkX, spacing);
        int targetRegionZ = Math.floorDiv(targetChunkZ, spacing);
        int regionRadius = Math.max(2, searchRadius / (spacing * 16) + 2);

        CoalLocation best = null;
        double bestDistance = Double.POSITIVE_INFINITY;
        for (int rz = targetRegionZ - regionRadius; rz <= targetRegionZ + regionRadius; rz++) {
            for (int rx = targetRegionX - regionRadius; rx <= targetRegionX + regionRadius; rx++) {
                ChunkPos chunk = placement.getPotentialStructureChunk(seed, rx * spacing, rz * spacing);
                int x = chunk.getMiddleBlockPosition(0).getX();
                int z = chunk.getMiddleBlockPosition(0).getZ();
                double distance = Math.hypot(x - targetX, z - targetZ);
                if (distance < bestDistance) {
                    bestDistance = distance;
                    best = new CoalLocation(x, z);
                }
            }
        }
        return best;
    }

    private static boolean containsWorld(
            LandComponent component,
            int worldX,
            int worldZ,
            int step,
            int radius
    ) {
        int gx = (int) Math.round((worldX + radius) / (double) step);
        int gz = (int) Math.round((worldZ + radius) / (double) step);
        if (gx < 0 || gz < 0 || gz >= component.visited().length || gx >= component.visited()[0].length) {
            return false;
        }
        return component.visited()[gz][gx];
    }

    private static int distanceToComponent(
            LandComponent component,
            int worldX,
            int worldZ,
            int step,
            int radius
    ) {
        double bestSq = Double.POSITIVE_INFINITY;
        for (Cell cell : component.cells()) {
            int x = -radius + cell.gridX() * step;
            int z = -radius + cell.gridZ() * step;
            double dx = x - worldX;
            double dz = z - worldZ;
            bestSq = Math.min(bestSq, dx * dx + dz * dz);
        }
        return (int) Math.round(Math.sqrt(bestSq));
    }

    private static long alternatingSeed(long index) {
        long value = index / 2L;
        return (index & 1L) == 0L ? value : -value - 1L;
    }

    private static double doubleAccessor(Object target, String name) throws ReflectiveOperationException {
        Method method = target.getClass().getDeclaredMethod(name);
        method.setAccessible(true);
        return (double) method.invoke(target);
    }

    private record ScoredCandidate(long seed, double score) {
    }

    private record Cell(int gridX, int gridZ) {
    }

    private record LandComponent(
            List<Cell> cells,
            boolean[][] visited,
            int width,
            int height,
            int centerX,
            int centerZ
    ) {
    }

    private record CoalLocation(int x, int z) {
    }

    private record PhysicalCandidate(
            long seed,
            double score,
            int width,
            int height,
            int neighborGap,
            int originDistance,
            double heightStdDev,
            int coalX,
            int coalZ,
            boolean coalOnIsland,
            int coalDistance,
            RandomState randomState
    ) {
        private PhysicalCandidate withScore(double newScore) {
            return new PhysicalCandidate(
                    seed,
                    newScore,
                    width,
                    height,
                    neighborGap,
                    originDistance,
                    heightStdDev,
                    coalX,
                    coalZ,
                    coalOnIsland,
                    coalDistance,
                    randomState
            );
        }
    }
}
