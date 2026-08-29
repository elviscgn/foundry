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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.PriorityQueue;
import java.util.concurrent.CompletableFuture;

/**
 * Headless, continuous starter-seed curator used by the Gradle starterSeedSearch task.
 *
 * Search pipeline:
 *  1. Enumerate the exact StrategicMacroMask site lattice across the whole starter region and use
 *     COE coal placement to cheaply rank small-island opportunities for each seed.
 *  2. The best site candidates get a 32-block real-Tectonic physical island raster.
 *  3. Only the best physical candidates pay for the authoritative 16-block raster.
 *
 * Biome is unrestricted. A winner is selected by physical island geometry, nearby land separation,
 * relief, starter-region distance, and coal actually lying on the island. The island does NOT need
 * to contain world spawn; the result prints a ready-to-paste teleport command.
 */
@Mod.EventBusSubscriber(modid = Foundry.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class StarterSeedCli {
    private static final String ENABLE_PROPERTY = "foundry.starterSeedSearch";
    private static final ResourceLocation COAL_VEIN =
            new ResourceLocation("createoreexcavation", "ore_vein_type/coal");

    private static final int BATCH_SIZE = 20_000;
    private static final int COARSE_SHORTLIST_PER_BATCH = 128;
    private static final int MEDIUM_CANDIDATES_PER_BATCH = 16;
    private static final int EXACT_FINALISTS_PER_BATCH = 3;

    // Search slightly beyond the hard 5k rule so a site's center may sit outside the radius while
    // its actual usable island land still lies inside it.
    private static final int MAX_STARTER_DISTANCE = 5_000;
    private static final int SITE_ENUMERATION_RADIUS = 5_500;

    // Small physical islands normally come from the low end of the strategic diameter distribution.
    // Keep this intentionally broad because Tectonic/coast carving determines the final land span.
    private static final double MIN_COARSE_NOMINAL_DIAMETER = 500.0;
    private static final double MAX_COARSE_NOMINAL_DIAMETER = 950.0;
    private static final double TARGET_COARSE_NOMINAL_DIAMETER = 575.0;
    private static final int MAX_COARSE_COAL_CENTER_DISTANCE = 500;

    // Radius 704 captures a 420-block island plus a 450-block sea gap with sampling margin.
    private static final int MEDIUM_RADIUS = 704;
    private static final int MEDIUM_STEP = 32;
    private static final int EXACT_RADIUS = 704;
    private static final int EXACT_STEP = 16;

    // Hard acceptance gates for the requested starter geography.
    private static final int MIN_ISLAND_SPAN = 240;
    private static final int MAX_ISLAND_SPAN = 420;
    private static final int MIN_NEIGHBOR_GAP = 180;
    private static final int MAX_NEIGHBOR_GAP = 450;
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
        System.out.println("[Foundry] Search region: any qualifying island whose usable TP point is within 5,000 blocks of world origin/spawn area.");
        System.out.println("[Foundry] Pipeline: exact strategic-site index -> 16 real 32-block island rasters -> 3 exact 16-block finalists.");
        System.out.println(String.format(
                Locale.ROOT,
                "[Foundry] FINAL GATES: island %d-%d | neighbor %d-%d | TP distance <= %,d | height SD <= %.1f | coal ON ISLAND | biome unrestricted",
                MIN_ISLAND_SPAN,
                MAX_ISLAND_SPAN,
                MIN_NEIGHBOR_GAP,
                MAX_NEIGHBOR_GAP,
                MAX_STARTER_DISTANCE,
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

            long tested = 0L;
            long globalIndex = 0L;
            long startedNanos = System.nanoTime();
            PhysicalCandidate bestNearMiss = null;
            double bestNearMissScore = Double.POSITIVE_INFINITY;

            while (server.isRunning()) {
                long batchFirstSeedIndex = globalIndex;
                PriorityQueue<CoarseCandidate> shortlist = new PriorityQueue<>(
                        Comparator.comparingDouble(CoarseCandidate::score).reversed()
                );
                long strategicSitesInspected = 0L;
                long smallSitesWithCoal = 0L;

                for (int i = 0; i < BATCH_SIZE && server.isRunning(); i++, globalIndex++) {
                    long seed = alternatingSeed(globalIndex);
                    List<StrategicStarterSiteIndex.Site> sites =
                            StrategicStarterSiteIndex.sitesWithin(seed, SITE_ENUMERATION_RADIUS);
                    strategicSitesInspected += sites.size();

                    for (StrategicStarterSiteIndex.Site site : sites) {
                        double diameter = site.nominalDiameter();
                        if (diameter < MIN_COARSE_NOMINAL_DIAMETER
                                || diameter > MAX_COARSE_NOMINAL_DIAMETER) {
                            continue;
                        }

                        CoalLocation coalLocation = nearestCoalRegions(
                                seed,
                                coalPlacement,
                                site.centerX(),
                                site.centerZ(),
                                1
                        );
                        if (coalLocation == null) {
                            continue;
                        }

                        int coalCenterDistance = (int) Math.round(Math.hypot(
                                coalLocation.x() - site.centerX(),
                                coalLocation.z() - site.centerZ()
                        ));
                        if (coalCenterDistance > MAX_COARSE_COAL_CENTER_DISTANCE) {
                            continue;
                        }
                        smallSitesWithCoal++;

                        double siteDistance = Math.hypot(site.centerX(), site.centerZ());
                        double score = Math.abs(diameter - TARGET_COARSE_NOMINAL_DIAMETER)
                                + coalCenterDistance * 0.65
                                + Math.max(0.0, siteDistance - MAX_STARTER_DISTANCE) * 2.0
                                + siteDistance * 0.001;

                        offerShortlist(shortlist, new CoarseCandidate(
                                seed,
                                site.centerX(),
                                site.centerZ(),
                                diameter,
                                coalCenterDistance,
                                score
                        ));
                    }
                    tested++;
                }

                List<CoarseCandidate> coarse = new ArrayList<>(shortlist);
                coarse.sort(Comparator.comparingDouble(CoarseCandidate::score));
                int mediumCount = Math.min(MEDIUM_CANDIDATES_PER_BATCH, coarse.size());
                System.out.println(String.format(
                        Locale.ROOT,
                        "[Foundry] %,d seeds screened | %,d strategic sites inspected | %,d small+coal sites | physical-screening %d...",
                        tested,
                        strategicSitesInspected,
                        smallSitesWithCoal,
                        mediumCount
                ));

                List<PhysicalCandidate> medium = new ArrayList<>(mediumCount);
                for (int i = 0; i < mediumCount && server.isRunning(); i++) {
                    CoarseCandidate candidate = coarse.get(i);
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
                            candidate.anchorX(),
                            candidate.anchorZ(),
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
                            preview.tpX(),
                            preview.tpZ(),
                            EXACT_RADIUS,
                            EXACT_STEP
                    );
                    if (exact == null) {
                        System.out.println("[Foundry] exact seed " + preview.seed()
                                + " had no usable physical island around candidate coordinates "+ preview.tpX() + "," + preview.tpZ() + ".");
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

    private static void offerShortlist(PriorityQueue<CoarseCandidate> shortlist, CoarseCandidate candidate) {
        if (shortlist.size() < COARSE_SHORTLIST_PER_BATCH) {
            shortlist.add(candidate);
            return;
        }
        CoarseCandidate worst = shortlist.peek();
        if (worst != null && candidate.score() < worst.score()) {
            shortlist.poll();
            shortlist.add(candidate);
        }
    }

    private static PhysicalCandidate evaluatePhysical(
            ServerLevel level,
            NoiseBasedChunkGenerator generator,
            RandomState randomState,
            RandomSpreadStructurePlacement coalPlacement,
            long seed,
            int anchorX,
            int anchorZ,
            int radius,
            int step
    ) {
        int cells = radius * 2 / step + 1;
        boolean[][] land = new boolean[cells][cells];
        int[][] heights = new int[cells][cells];
        int seaLevel = generator.getSeaLevel();

        int nearestX = -1;
        int nearestZ = -1;
        double nearestAnchorSq = Double.POSITIVE_INFINITY;

        for (int gz = 0; gz < cells; gz++) {
            int z = anchorZ - radius + gz * step;
            for (int gx = 0; gx < cells; gx++) {
                int x = anchorX - radius + gx * step;
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
                double dx = x - anchorX;
                double dz = z - anchorZ;
                double distanceSq = dx * dx + dz * dz;
                if (distanceSq < nearestAnchorSq) {
                    nearestAnchorSq = distanceSq;
                    nearestX = gx;
                    nearestZ = gz;
                }
            }
        }

        if (nearestX < 0) {
            return null;
        }

        LandComponent component = floodComponent(
                land,
                nearestX,
                nearestZ,
                step,
                radius,
                anchorX,
                anchorZ
        );
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

        int tpX = anchorX - radius + component.representativeGridX() * step;
        int tpZ = anchorZ - radius + component.representativeGridZ() * step;
        int tpY = heights[component.representativeGridZ()][component.representativeGridX()] + 2;
        int starterDistance = (int) Math.round(Math.hypot(tpX, tpZ));

        CoalLocation coal = nearestCoalRegions(
                seed,
                coalPlacement,
                component.centerX(),
                component.centerZ(),
                3
        );
        if (coal == null) {
            return null;
        }

        boolean coalOnIsland = containsWorld(
                component,
                coal.x(),
                coal.z(),
                step,
                radius,
                anchorX,
                anchorZ
        );
        int coalDistance = distanceToComponent(
                component,
                coal.x(),
                coal.z(),
                step,
                radius,
                anchorX,
                anchorZ
        );

        return new PhysicalCandidate(
                seed,
                0.0,
                component.width(),
                component.height(),
                neighborGap,
                starterDistance,
                heightStdDev,
                tpX,
                tpY,
                tpZ,
                coal.x(),
                coal.z(),
                coalOnIsland,
                coalDistance,
                randomState
        );
    }

    private static double mediumSelectionScore(PhysicalCandidate candidate, double coarseScore) {
        int span = Math.max(candidate.width(), candidate.height());
        double score = coarseScore * 0.08
                + Math.abs(span - TARGET_ISLAND_SPAN)
                + Math.abs(candidate.neighborGap() - TARGET_NEIGHBOR_GAP) * 0.70
                + candidate.heightStdDev() * 16.0
                + candidate.starterDistance() * 0.002;

        score += outsideDistance(span, MIN_ISLAND_SPAN, MAX_ISLAND_SPAN) * 6.0;
        score += outsideDistance(candidate.neighborGap(), MIN_NEIGHBOR_GAP, MAX_NEIGHBOR_GAP) * 3.0;
        score += Math.max(0.0, candidate.heightStdDev() - MAX_HEIGHT_STD_DEV) * 90.0;
        score += Math.max(0, candidate.starterDistance() - MAX_STARTER_DISTANCE) * 5.0;
        if (!candidate.coalOnIsland()) {
            // Medium 32-block coast sampling can be wrong by roughly one sample near the shoreline,
            // so penalize rather than discard and let the 16-block pass make the final decision.
            score += 160.0 + candidate.coalDistance() * 2.0;
        }
        return score;
    }

    private static boolean passesHardGates(PhysicalCandidate candidate) {
        int span = Math.max(candidate.width(), candidate.height());
        return span >= MIN_ISLAND_SPAN
                && span <= MAX_ISLAND_SPAN
                && candidate.neighborGap() >= MIN_NEIGHBOR_GAP
                && candidate.neighborGap() <= MAX_NEIGHBOR_GAP
                && candidate.starterDistance() <= MAX_STARTER_DISTANCE
                && candidate.heightStdDev() <= MAX_HEIGHT_STD_DEV
                && candidate.coalOnIsland();
    }

    private static double hardGateMissScore(PhysicalCandidate candidate) {
        int span = Math.max(candidate.width(), candidate.height());
        double score = outsideDistance(span, MIN_ISLAND_SPAN, MAX_ISLAND_SPAN) * 6.0
                + outsideDistance(candidate.neighborGap(), MIN_NEIGHBOR_GAP, MAX_NEIGHBOR_GAP) * 3.0
                + Math.max(0.0, candidate.heightStdDev() - MAX_HEIGHT_STD_DEV) * 100.0
                + Math.max(0, candidate.starterDistance() - MAX_STARTER_DISTANCE) * 5.0;
        if (!candidate.coalOnIsland()) {
            score += 500.0 + candidate.coalDistance() * 5.0;
        }
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
        if (candidate.starterDistance() > MAX_STARTER_DISTANCE) {
            failures.add("TP point " + candidate.starterDistance() + " from 0,0 > " + MAX_STARTER_DISTANCE);
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
                        + "Starter TP:    /tp @s %d %d %d%n"
                        + "Starter coords: %d, %d, %d%n"
                        + "Distance 0,0:  %d blocks (spawn-area proxy)%n"
                        + "Island:        %d x %d%n"
                        + "Neighbor gap:  %d blocks%n"
                        + "Height SD:     %.2f%n"
                        + "Coal:          ON ISLAND at %d, %d%n"
                        + "Biome:         unrestricted / not scored%n"
                        + "Seeds tested:  %,d%n"
                        + "Elapsed:       %.1f seconds%n"
                        + "========================================%n",
                candidate.seed(),
                candidate.tpX(),
                candidate.tpY(),
                candidate.tpZ(),
                candidate.tpX(),
                candidate.tpY(),
                candidate.tpZ(),
                candidate.starterDistance(),
                candidate.width(),
                candidate.height(),
                candidate.neighborGap(),
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
                        + "Starter TP: /tp @s %d %d %d%n"
                        + "Distance 0,0: %d blocks (spawn-area proxy)%n"
                        + "Island: %d x %d%n"
                        + "Neighbor gap: %d blocks%n"
                        + "Height SD: %.2f%n"
                        + "Coal: %s at %d, %d (distance to island ~%d)%n"
                        + "FAIL: %s%n"
                        + "Seeds tested when recorded: %,d%n"
                        + "Elapsed when recorded: %.1f seconds%n",
                candidate.seed(),
                candidate.tpX(),
                candidate.tpY(),
                candidate.tpZ(),
                candidate.starterDistance(),
                candidate.width(),
                candidate.height(),
                candidate.neighborGap(),
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
                "seed %d | TP %d,%d,%d | %dx%d | gap %d | dist %.1fk | height SD %.1f | coal %s%s",
                candidate.seed(),
                candidate.tpX(),
                candidate.tpY(),
                candidate.tpZ(),
                candidate.width(),
                candidate.height(),
                candidate.neighborGap(),
                candidate.starterDistance() / 1_000.0,
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
            int radius,
            int anchorX,
            int anchorZ
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
        Cell representative = cells.get(0);
        int bestRepresentativeSq = Integer.MAX_VALUE;
        for (Cell cell : cells) {
            int ddx = cell.gridX() - centerGridX;
            int ddz = cell.gridZ() - centerGridZ;
            int distanceSq = ddx * ddx + ddz * ddz;
            if (distanceSq < bestRepresentativeSq) {
                bestRepresentativeSq = distanceSq;
                representative = cell;
            }
        }

        return new LandComponent(
                cells,
                visited,
                (maxX - minX + 1) * step,
                (maxZ - minZ + 1) * step,
                anchorX - radius + centerGridX * step,
                anchorZ - radius + centerGridZ * step,
                representative.gridX(),
                representative.gridZ()
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

    private static CoalLocation nearestCoalRegions(
            long seed,
            RandomSpreadStructurePlacement placement,
            int targetX,
            int targetZ,
            int regionRadius
    ) {
        int spacing = placement.spacing();
        int targetChunkX = Math.floorDiv(targetX, 16);
        int targetChunkZ = Math.floorDiv(targetZ, 16);
        int targetRegionX = Math.floorDiv(targetChunkX, spacing);
        int targetRegionZ = Math.floorDiv(targetChunkZ, spacing);

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
            int radius,
            int anchorX,
            int anchorZ
    ) {
        int gx = (int) Math.round((worldX - (anchorX - radius)) / (double) step);
        int gz = (int) Math.round((worldZ - (anchorZ - radius)) / (double) step);
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
            int radius,
            int anchorX,
            int anchorZ
    ) {
        double bestSq = Double.POSITIVE_INFINITY;
        for (Cell cell : component.cells()) {
            int x = anchorX - radius + cell.gridX() * step;
            int z = anchorZ - radius + cell.gridZ() * step;
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

    private record CoarseCandidate(
            long seed,
            int anchorX,
            int anchorZ,
            double nominalDiameter,
            int coalCenterDistance,
            double score
    ) {
    }

    private record Cell(int gridX, int gridZ) {
    }

    private record LandComponent(
            List<Cell> cells,
            boolean[][] visited,
            int width,
            int height,
            int centerX,
            int centerZ,
            int representativeGridX,
            int representativeGridZ
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
            int starterDistance,
            double heightStdDev,
            int tpX,
            int tpY,
            int tpZ,
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
                    starterDistance,
                    heightStdDev,
                    tpX,
                    tpY,
                    tpZ,
                    coalX,
                    coalZ,
                    coalOnIsland,
                    coalDistance,
                    randomState
            );
        }
    }
}
