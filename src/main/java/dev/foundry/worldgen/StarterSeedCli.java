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
 * Headless continuous curator for the canonical Tiger Ascent starter geography.
 *
 * Cheap strategic/placement math is ranking only. Physical acceptance comes from the live
 * Tectonic/Foundry NoiseBasedChunkGenerator with a seed-specific RandomState, and coal acceptance
 * mirrors Create Ore Excavation's real recipe-priority + biome generation rules.
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

    private static final int MAX_STARTER_DISTANCE = 5_000;
    private static final int SITE_ENUMERATION_RADIUS = 5_500;

    private static final double MIN_COARSE_NOMINAL_DIAMETER = 500.0;
    private static final double MAX_COARSE_NOMINAL_DIAMETER = 950.0;
    private static final double TARGET_COARSE_NOMINAL_DIAMETER = 575.0;
    private static final int MAX_COARSE_COAL_CENTER_DISTANCE = 500;

    private static final int MEDIUM_RADIUS = 704;
    private static final int MEDIUM_STEP = 32;
    private static final int EXACT_RADIUS = 704;
    private static final int EXACT_STEP = 16;

    private static final int MIN_ISLAND_SPAN = 240;
    private static final int MAX_ISLAND_SPAN = 420;
    private static final int MIN_NEIGHBOR_GAP = 180;
    private static final int MAX_NEIGHBOR_GAP = 450;
    private static final double MAX_HEIGHT_STD_DEV = 10.0;

    private static final int MIN_MEANINGFUL_NEIGHBOR_SPAN = 160;
    private static final int MIN_MEANINGFUL_NEIGHBOR_AREA = 12_000;

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
        System.out.println("[Foundry] Search region: qualifying TP point within 5,000 blocks of 0,0/spawn area; island need not contain spawn.");
        System.out.println("[Foundry] Pipeline: strategic-site index -> 16 real 32-block rasters -> 3 exact 16-block finalists.");
        System.out.println(String.format(
                Locale.ROOT,
                "[Foundry] FINAL GATES: island %d-%d | meaningful neighbor %d-%d | TP <= %,d from 0,0 | height SD <= %.1f | VERIFIED COE coal ON ISLAND | biome unrestricted",
                MIN_ISLAND_SPAN,
                MAX_ISLAND_SPAN,
                MIN_NEIGHBOR_GAP,
                MAX_NEIGHBOR_GAP,
                MAX_STARTER_DISTANCE,
                MAX_HEIGHT_STD_DEV
        ));
        System.out.println(String.format(
                Locale.ROOT,
                "[Foundry] Meaningful neighbor: separate landmass >= %d blocks across and ~%,d+ estimated land area; tiny rocks ignored.",
                MIN_MEANINGFUL_NEIGHBOR_SPAN,
                MIN_MEANINGFUL_NEIGHBOR_AREA
        ));
        System.out.println("[Foundry] Coal hard gate now mirrors COE recipe priority + biome generation; raw potential placements do NOT count.");
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
            List<VeinRecipe> orderedVeinRecipes = CoeSeedLocator.orderedVeinRecipes(level);

            NoiseGeneratorSettings settings = generator.generatorSettings().value();
            Registry<NormalNoise.NoiseParameters> noiseRegistry =
                    level.registryAccess().registryOrThrow(Registries.NOISE);

            long tested = 0L;
            long globalIndex = 0L;
            long startedNanos = System.nanoTime();
            double bestNearMissScore = Double.POSITIVE_INFINITY;

            while (server.isRunning()) {
                long batchFirstSeedIndex = globalIndex;
                PriorityQueue<CoarseCandidate> shortlist = new PriorityQueue<>(
                        Comparator.comparingDouble(CoarseCandidate::score).reversed()
                );
                long strategicSitesInspected = 0L;
                long smallSitesWithPotentialCoal = 0L;

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

                        // Cheap ranking only. Actual COE generation is checked after RandomState is
                        // created for the physical stage.
                        CoalLocation potentialCoal = nearestPotentialCoal(
                                seed,
                                coalPlacement,
                                site.centerX(),
                                site.centerZ(),
                                1
                        );
                        if (potentialCoal == null) {
                            continue;
                        }

                        int coalCenterDistance = (int) Math.round(Math.hypot(
                                potentialCoal.x() - site.centerX(),
                                potentialCoal.z() - site.centerZ()
                        ));
                        if (coalCenterDistance > MAX_COARSE_COAL_CENTER_DISTANCE) {
                            continue;
                        }
                        smallSitesWithPotentialCoal++;

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
                        "[Foundry] %,d seeds screened | %,d strategic sites | %,d small+potential-coal sites | physical-screening %d...",
                        tested,
                        strategicSitesInspected,
                        smallSitesWithPotentialCoal,
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
                            orderedVeinRecipes,
                            coal,
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
                            orderedVeinRecipes,
                            coal,
                            preview.seed(),
                            preview.tpX(),
                            preview.tpZ(),
                            EXACT_RADIUS,
                            EXACT_STEP
                    );
                    if (exact == null) {
                        System.out.println("[Foundry] exact seed " + preview.seed()
                                + " had no usable island/verified coal around "
                                + preview.tpX() + "," + preview.tpZ() + ".");
                        continue;
                    }

                    double missScore = hardGateMissScore(exact);
                    if (missScore < bestBatchMissScore) {
                        bestBatchMissScore = missScore;
                        bestBatchExact = exact;
                    }
                    if (missScore < bestNearMissScore) {
                        bestNearMissScore = missScore;
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
                        ? "no exact physical candidate with verified COE coal survived"
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
            List<VeinRecipe> orderedVeinRecipes,
            VeinRecipe coalRecipe,
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
                int height = baseHeight(level, generator, randomState, x, z);
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

        NeighborInfo neighbor = nearestMeaningfulNeighbor(land, component, step, radius);

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
        int verifiedTpSurface = baseHeight(level, generator, randomState, tpX, tpZ);
        if (verifiedTpSurface <= seaLevel) {
            return null;
        }
        int tpY = verifiedTpSurface + 2;
        int starterDistance = (int) Math.round(Math.hypot(tpX, tpZ));

        CoeSeedLocator.Location coal = CoeSeedLocator.nearestActualVein(
                level,
                generator,
                randomState,
                orderedVeinRecipes,
                coalRecipe,
                seed,
                component.centerX(),
                component.centerZ(),
                3
        );
        if (coal == null) {
            return null;
        }

        // Raw raster membership is not enough for a hard gate. Directly evaluate terrain at the
        // exact generated COE vein coordinate, then require its nearest exact/preview component cell
        // to belong to this same starter island.
        int coalSurface = baseHeight(level, generator, randomState, coal.x(), coal.z());
        boolean coalIsPhysicalLand = coalSurface > seaLevel;
        boolean coalMapsToStarter = containsWorld(
                component,
                coal.x(),
                coal.z(),
                step,
                radius,
                anchorX,
                anchorZ
        );
        boolean coalOnIsland = coalIsPhysicalLand && coalMapsToStarter;
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
                neighbor.gap(),
                neighbor.span(),
                neighbor.estimatedArea(),
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

    private static int baseHeight(
            ServerLevel level,
            NoiseBasedChunkGenerator generator,
            RandomState randomState,
            int x,
            int z
    ) {
        return generator.getBaseHeight(
                x,
                z,
                Heightmap.Types.OCEAN_FLOOR_WG,
                level,
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
            score += 200.0 + candidate.coalDistance() * 3.0;
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
        if (value < min) return min - value;
        if (value > max) return value - max;
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
            failures.add("meaningful neighbor gap " + candidate.neighborGap() + " < " + MIN_NEIGHBOR_GAP);
        } else if (candidate.neighborGap() > MAX_NEIGHBOR_GAP) {
            failures.add("meaningful neighbor gap " + candidate.neighborGap() + " > " + MAX_NEIGHBOR_GAP);
        }
        if (candidate.starterDistance() > MAX_STARTER_DISTANCE) {
            failures.add("TP point " + candidate.starterDistance() + " from 0,0 > " + MAX_STARTER_DISTANCE);
        }
        if (candidate.heightStdDev() > MAX_HEIGHT_STD_DEV) {
            failures.add(String.format(Locale.ROOT, "height SD %.1f > %.1f", candidate.heightStdDev(), MAX_HEIGHT_STD_DEV));
        }
        if (!candidate.coalOnIsland()) {
            failures.add("verified COE coal OFF STARTER ISLAND by ~" + candidate.coalDistance() + " blocks");
        }
        return failures.isEmpty() ? "none" : String.join("; ", failures);
    }

    private static void printAndSaveResult(PhysicalCandidate c, long tested, long startedNanos)
            throws IOException {
        double elapsedSeconds = (System.nanoTime() - startedNanos) / 1_000_000_000.0;
        String result = String.format(
                Locale.ROOT,
                "========================================%n"
                        + "FOUNDRY STARTER SEED FOUND%n"
                        + "========================================%n"
                        + "LOAD WORLD SEED: %d%n"
                        + "THEN TELEPORT:   /tp @s %d %d %d%n%n"
                        + "Starter coords:  %d, %d, %d%n"
                        + "Distance 0,0:    %d blocks (spawn-area proxy)%n"
                        + "Island:          %d x %d%n"
                        + "Neighbor gap:    %d blocks to meaningful land%n"
                        + "Neighbor size:   ~%d-block span / ~%,d estimated land area%n"
                        + "Height SD:       %.2f%n"
                        + "Coal:            VERIFIED COE coal ON ISLAND at %d, %d%n"
                        + "Biome:           unrestricted / not scored%n"
                        + "Seeds tested:    %,d%n"
                        + "Elapsed:         %.1f seconds%n"
                        + "========================================%n",
                c.seed(), c.tpX(), c.tpY(), c.tpZ(),
                c.tpX(), c.tpY(), c.tpZ(), c.starterDistance(),
                c.width(), c.height(), c.neighborGap(), c.neighborSpan(),
                c.neighborEstimatedArea(), c.heightStdDev(), c.coalX(), c.coalZ(),
                tested, elapsedSeconds
        );
        System.out.print(result);
        Files.writeString(Path.of("starter-seed-result.txt"), result, StandardCharsets.UTF_8);
        System.out.println("[Foundry] Saved result to run-seed-search/starter-seed-result.txt");
    }

    private static void saveNearMiss(PhysicalCandidate c, long tested, long startedNanos)
            throws IOException {
        double elapsedSeconds = (System.nanoTime() - startedNanos) / 1_000_000_000.0;
        String result = String.format(
                Locale.ROOT,
                "FOUNDRY BEST STARTER-SEED NEAR MISS%n"
                        + "LOAD WORLD SEED: %d%n"
                        + "Starter TP: /tp @s %d %d %d%n"
                        + "Distance 0,0: %d blocks (spawn-area proxy)%n"
                        + "Island: %d x %d%n"
                        + "Meaningful neighbor gap: %d blocks%n"
                        + "Neighbor size: ~%d-block span / ~%,d estimated land area%n"
                        + "Height SD: %.2f%n"
                        + "Coal: %s at %d, %d%s%n"
                        + "FAIL: %s%n"
                        + "Seeds tested when recorded: %,d%n"
                        + "Elapsed when recorded: %.1f seconds%n",
                c.seed(), c.tpX(), c.tpY(), c.tpZ(), c.starterDistance(),
                c.width(), c.height(), c.neighborGap(), c.neighborSpan(),
                c.neighborEstimatedArea(), c.heightStdDev(),
                c.coalOnIsland() ? "VERIFIED COE coal ON ISLAND" : "VERIFIED COE coal OFF ISLAND",
                c.coalX(), c.coalZ(),
                c.coalOnIsland() ? "" : " (~" + c.coalDistance() + " blocks from sampled island)",
                failureReasons(c), tested, elapsedSeconds
        );
        Files.writeString(Path.of("starter-seed-nearmiss.txt"), result, StandardCharsets.UTF_8);
    }

    private static String summarize(PhysicalCandidate c) {
        return String.format(
                Locale.ROOT,
                "seed %d | TP %d,%d,%d | %dx%d | meaningful gap %d -> ~%d span | dist %.1fk | SD %.1f | %s",
                c.seed(), c.tpX(), c.tpY(), c.tpZ(), c.width(), c.height(),
                c.neighborGap(), c.neighborSpan(), c.starterDistance() / 1_000.0,
                c.heightStdDev(), c.coalOnIsland() ? "COE COAL ON ISLAND" : "COE coal off island"
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
        boolean[][] visited = new boolean[land.length][land[0].length];
        ComponentShape shape = collectComponent(land, startX, startZ, visited);
        if (shape.cells().isEmpty()) return null;

        int centerGridX = (shape.minX() + shape.maxX()) / 2;
        int centerGridZ = (shape.minZ() + shape.maxZ()) / 2;
        Cell representative = shape.cells().get(0);
        int bestRepresentativeSq = Integer.MAX_VALUE;
        for (Cell cell : shape.cells()) {
            int dx = cell.gridX() - centerGridX;
            int dz = cell.gridZ() - centerGridZ;
            int distanceSq = dx * dx + dz * dz;
            if (distanceSq < bestRepresentativeSq) {
                bestRepresentativeSq = distanceSq;
                representative = cell;
            }
        }

        return new LandComponent(
                shape.cells(),
                visited,
                (shape.maxX() - shape.minX() + 1) * step,
                (shape.maxZ() - shape.minZ() + 1) * step,
                anchorX - radius + centerGridX * step,
                anchorZ - radius + centerGridZ * step,
                representative.gridX(),
                representative.gridZ()
        );
    }

    private static NeighborInfo nearestMeaningfulNeighbor(
            boolean[][] land,
            LandComponent starter,
            int step,
            int radius
    ) {
        int rows = land.length;
        int cols = land[0].length;
        boolean[][] seen = new boolean[rows][cols];
        for (Cell cell : starter.cells()) {
            seen[cell.gridZ()][cell.gridX()] = true;
        }

        int bestGap = Integer.MAX_VALUE;
        int bestSpan = 0;
        int bestArea = 0;

        for (int gz = 0; gz < rows; gz++) {
            for (int gx = 0; gx < cols; gx++) {
                if (!land[gz][gx] || seen[gz][gx]) continue;

                ComponentShape other = collectComponent(land, gx, gz, seen);
                int width = (other.maxX() - other.minX() + 1) * step;
                int height = (other.maxZ() - other.minZ() + 1) * step;
                int span = Math.max(width, height);
                int estimatedArea = other.cells().size() * step * step;
                boolean touchesBorder = other.minX() == 0
                        || other.minZ() == 0
                        || other.maxX() == cols - 1
                        || other.maxZ() == rows - 1;
                boolean meaningful = span >= MIN_MEANINGFUL_NEIGHBOR_SPAN
                        && (estimatedArea >= MIN_MEANINGFUL_NEIGHBOR_AREA || touchesBorder);
                if (!meaningful) continue;

                int gap = componentGap(starter.cells(), other.cells(), step);
                if (gap < bestGap) {
                    bestGap = gap;
                    bestSpan = span;
                    bestArea = estimatedArea;
                }
            }
        }

        if (bestGap == Integer.MAX_VALUE) {
            return new NeighborInfo(radius * 2, 0, 0);
        }
        return new NeighborInfo(bestGap, bestSpan, bestArea);
    }

    private static ComponentShape collectComponent(
            boolean[][] land,
            int startX,
            int startZ,
            boolean[][] visited
    ) {
        int rows = land.length;
        int cols = land[0].length;
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
                if (nx < 0 || nz < 0 || nx >= cols || nz >= rows) continue;
                if (!land[nz][nx] || visited[nz][nx]) continue;
                visited[nz][nx] = true;
                queue.addLast(new Cell(nx, nz));
            }
        }
        return new ComponentShape(cells, minX, maxX, minZ, maxZ);
    }

    private static int componentGap(List<Cell> first, List<Cell> second, int step) {
        int bestSq = Integer.MAX_VALUE;
        for (Cell a : first) {
            for (Cell b : second) {
                int dx = (a.gridX() - b.gridX()) * step;
                int dz = (a.gridZ() - b.gridZ()) * step;
                int distanceSq = dx * dx + dz * dz;
                if (distanceSq < bestSq) bestSq = distanceSq;
            }
        }
        return Math.max(0, (int) Math.round(Math.sqrt(bestSq)) - step);
    }

    /** Cheap potential-placement lookup used ONLY before a candidate RandomState exists. */
    private static CoalLocation nearestPotentialCoal(
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
    ) {}

    private record Cell(int gridX, int gridZ) {}

    private record ComponentShape(
            List<Cell> cells,
            int minX,
            int maxX,
            int minZ,
            int maxZ
    ) {}

    private record LandComponent(
            List<Cell> cells,
            boolean[][] visited,
            int width,
            int height,
            int centerX,
            int centerZ,
            int representativeGridX,
            int representativeGridZ
    ) {}

    private record NeighborInfo(int gap, int span, int estimatedArea) {}

    private record CoalLocation(int x, int z) {}

    private record PhysicalCandidate(
            long seed,
            double score,
            int width,
            int height,
            int neighborGap,
            int neighborSpan,
            int neighborEstimatedArea,
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
                    seed, newScore, width, height, neighborGap, neighborSpan,
                    neighborEstimatedArea, starterDistance, heightStdDev,
                    tpX, tpY, tpZ, coalX, coalZ, coalOnIsland, coalDistance, randomState
            );
        }
    }
}
