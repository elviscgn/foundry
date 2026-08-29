package dev.foundry.worldgen;

import com.tom.createores.recipe.VeinRecipe;
import dev.foundry.Foundry;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
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
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.concurrent.CompletableFuture;

/**
 * Headless continuous curator for the canonical Tiger Ascent starter geography.
 *
 * <p>The pipeline deliberately front-loads cheap deterministic work. Actual COE coal generation is
 * verified before any expensive terrain raster. Tectonic terrain then progresses through 64-, 32-,
 * and finally authoritative 16-block sampling. Final acceptance gates are unchanged.</p>
 */
@Mod.EventBusSubscriber(modid = Foundry.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class StarterSeedCli {
    private static final String ENABLE_PROPERTY = "foundry.starterSeedSearch";
    private static final ResourceLocation COAL_VEIN =
            new ResourceLocation("createoreexcavation", "ore_vein_type/coal");

    private static final int BATCH_SIZE = 20_000;
    private static final int COARSE_SHORTLIST_PER_BATCH = 128;
    private static final int COAL_VERIFIED_CANDIDATES_PER_BATCH = 24;
    private static final int PREVIEW_CANDIDATES_PER_BATCH = 16;
    private static final int MEDIUM_CANDIDATES_PER_BATCH = 5;
    private static final int EXACT_FINALISTS_PER_BATCH = 2;

    private static final int MAX_STARTER_DISTANCE = 5_000;
    private static final int SITE_ENUMERATION_RADIUS = 5_500;

    private static final double MIN_COARSE_NOMINAL_DIAMETER = 500.0;
    private static final double MAX_COARSE_NOMINAL_DIAMETER = 950.0;
    private static final double TARGET_COARSE_NOMINAL_DIAMETER = 575.0;
    private static final int MAX_COARSE_COAL_CENTER_DISTANCE = 500;
    private static final int MAX_VERIFIED_COAL_CENTER_DISTANCE = 600;

    private static final int PREVIEW_RADIUS = 704;
    private static final int PREVIEW_STEP = 64;
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
        System.out.println("[Foundry] Optimized pipeline: 20k strategic -> 24 verified-coal -> 16 @64-block -> 5 @32-block -> 2 @16-block exact.");
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
        System.out.println("[Foundry] COE recipe priority + biome generation is verified BEFORE expensive terrain work.");
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
                long batchStartedNanos = System.nanoTime();
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

                long afterCheapNanos = System.nanoTime();
                List<CoarseCandidate> coarse = new ArrayList<>(shortlist);
                coarse.sort(Comparator.comparingDouble(CoarseCandidate::score));

                // Verify real COE generation before touching getBaseHeight(). Reuse RandomState for
                // multiple strategic sites from the same seed instead of rebuilding it repeatedly.
                Map<Long, RandomState> randomStateCache = new HashMap<>();
                List<VerifiedCandidate> verified = new ArrayList<>();
                int coeChecks = 0;
                for (CoarseCandidate candidate : coarse) {
                    if (!server.isRunning() || verified.size() >= COAL_VERIFIED_CANDIDATES_PER_BATCH) {
                        break;
                    }

                    RandomState randomState = randomStateCache.computeIfAbsent(
                            candidate.seed(),
                            seed -> RandomState.create(settings, noiseRegistry.asLookup(), seed)
                    );
                    CoeSeedLocator.Location actualCoal = CoeSeedLocator.nearestActualVein(
                            level,
                            generator,
                            randomState,
                            orderedVeinRecipes,
                            coal,
                            candidate.seed(),
                            candidate.anchorX(),
                            candidate.anchorZ(),
                            2
                    );
                    coeChecks++;
                    if (actualCoal == null) {
                        continue;
                    }

                    int actualCoalDistance = (int) Math.round(Math.hypot(
                            actualCoal.x() - candidate.anchorX(),
                            actualCoal.z() - candidate.anchorZ()
                    ));
                    if (actualCoalDistance > MAX_VERIFIED_COAL_CENTER_DISTANCE) {
                        continue;
                    }

                    double verifiedScore = candidate.score()
                            + Math.max(0, actualCoalDistance - candidate.potentialCoalDistance()) * 0.65;
                    verified.add(new VerifiedCandidate(
                            candidate,
                            randomState,
                            actualCoal,
                            actualCoalDistance,
                            verifiedScore
                    ));
                }
                verified.sort(Comparator.comparingDouble(VerifiedCandidate::score));
                long afterCoeNanos = System.nanoTime();

                System.out.println(String.format(
                        Locale.ROOT,
                        "[Foundry] %,d seeds screened | %,d strategic sites | %,d small+potential-coal | COE checked %d shortlist sites -> %d verified-coal",
                        tested,
                        strategicSitesInspected,
                        smallSitesWithPotentialCoal,
                        coeChecks,
                        verified.size()
                ));

                long terrainGridProbes = 0L;

                int previewCount = Math.min(PREVIEW_CANDIDATES_PER_BATCH, verified.size());
                List<StageCandidate> previews = new ArrayList<>(previewCount);
                for (int i = 0; i < previewCount && server.isRunning(); i++) {
                    VerifiedCandidate candidate = verified.get(i);
                    StarterSeedEvaluator.Result result = StarterSeedEvaluator.evaluate(
                            level,
                            generator,
                            candidate.randomState(),
                            orderedVeinRecipes,
                            coal,
                            candidate.coarse().seed(),
                            candidate.coarse().anchorX(),
                            candidate.coarse().anchorZ(),
                            PREVIEW_RADIUS,
                            PREVIEW_STEP,
                            candidate.coal()
                    );
                    terrainGridProbes += rasterProbeCount(PREVIEW_RADIUS, PREVIEW_STEP);
                    if (result != null) {
                        result = result.withScore(stageSelectionScore(result, candidate.score()));
                        previews.add(new StageCandidate(result, candidate.coal()));
                    }
                }
                previews.sort(Comparator.comparingDouble(c -> c.result().score()));
                long afterPreviewNanos = System.nanoTime();

                int mediumCount = Math.min(MEDIUM_CANDIDATES_PER_BATCH, previews.size());
                List<StageCandidate> medium = new ArrayList<>(mediumCount);
                for (int i = 0; i < mediumCount && server.isRunning(); i++) {
                    StageCandidate preview = previews.get(i);
                    StarterSeedEvaluator.Result result = StarterSeedEvaluator.evaluate(
                            level,
                            generator,
                            preview.result().randomState(),
                            orderedVeinRecipes,
                            coal,
                            preview.result().seed(),
                            preview.result().tpX(),
                            preview.result().tpZ(),
                            MEDIUM_RADIUS,
                            MEDIUM_STEP,
                            preview.coalHint()
                    );
                    terrainGridProbes += rasterProbeCount(MEDIUM_RADIUS, MEDIUM_STEP);
                    if (result != null) {
                        result = result.withScore(stageSelectionScore(result, preview.result().score()));
                        medium.add(new StageCandidate(result, preview.coalHint()));
                    }
                }
                medium.sort(Comparator.comparingDouble(c -> c.result().score()));
                long afterMediumNanos = System.nanoTime();

                int exactCount = Math.min(EXACT_FINALISTS_PER_BATCH, medium.size());
                System.out.println(String.format(
                        Locale.ROOT,
                        "[Foundry] physical ranking: %d/%d sparse previews -> %d medium -> %d exact",
                        previews.size(),
                        previewCount,
                        medium.size(),
                        exactCount
                ));

                StarterSeedEvaluator.Result bestBatchExact = null;
                double bestBatchMissScore = Double.POSITIVE_INFINITY;

                for (int i = 0; i < exactCount && server.isRunning(); i++) {
                    StageCandidate candidate = medium.get(i);
                    // Exact pass deliberately re-runs the real COE locator around the measured
                    // component instead of trusting the earlier site-level coal hint.
                    StarterSeedEvaluator.Result exact = StarterSeedEvaluator.evaluate(
                            level,
                            generator,
                            candidate.result().randomState(),
                            orderedVeinRecipes,
                            coal,
                            candidate.result().seed(),
                            candidate.result().tpX(),
                            candidate.result().tpZ(),
                            EXACT_RADIUS,
                            EXACT_STEP,
                            null
                    );
                    terrainGridProbes += rasterProbeCount(EXACT_RADIUS, EXACT_STEP);
                    if (exact == null) {
                        System.out.println("[Foundry] exact seed " + candidate.result().seed()
                                + " had no usable island/verified coal around "
                                + candidate.result().tpX() + "," + candidate.result().tpZ() + ".");
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

                long afterExactNanos = System.nanoTime();
                double elapsedSeconds = (afterExactNanos - startedNanos) / 1_000_000_000.0;
                double seedsPerSecond = tested / Math.max(0.001, elapsedSeconds);
                String bestSummary = bestBatchExact == null
                        ? "no exact physical candidate with verified COE coal survived"
                        : summarize(bestBatchExact) + " | FAIL: " + failureReasons(bestBatchExact);

                System.out.println(String.format(
                        Locale.ROOT,
                        "[Foundry] batch %,d-%,d | tested %,d | %.0f seeds/s | ~%,d heavy grid probes | best: %s",
                        batchFirstSeedIndex,
                        globalIndex - 1,
                        tested,
                        seedsPerSecond,
                        terrainGridProbes,
                        bestSummary
                ));
                System.out.println(String.format(
                        Locale.ROOT,
                        "[Foundry] timing: strategic %.1fs | COE %.1fs | sparse %.1fs | medium %.1fs | exact %.1fs | batch %.1fs",
                        secondsBetween(batchStartedNanos, afterCheapNanos),
                        secondsBetween(afterCheapNanos, afterCoeNanos),
                        secondsBetween(afterCoeNanos, afterPreviewNanos),
                        secondsBetween(afterPreviewNanos, afterMediumNanos),
                        secondsBetween(afterMediumNanos, afterExactNanos),
                        secondsBetween(batchStartedNanos, afterExactNanos)
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

    private static double stageSelectionScore(StarterSeedEvaluator.Result candidate, double priorScore) {
        int span = Math.max(candidate.width(), candidate.height());
        double score = priorScore * 0.10
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

    private static boolean passesHardGates(StarterSeedEvaluator.Result candidate) {
        int span = Math.max(candidate.width(), candidate.height());
        return span >= MIN_ISLAND_SPAN
                && span <= MAX_ISLAND_SPAN
                && candidate.neighborGap() >= MIN_NEIGHBOR_GAP
                && candidate.neighborGap() <= MAX_NEIGHBOR_GAP
                && candidate.starterDistance() <= MAX_STARTER_DISTANCE
                && candidate.heightStdDev() <= MAX_HEIGHT_STD_DEV
                && candidate.coalOnIsland();
    }

    private static double hardGateMissScore(StarterSeedEvaluator.Result candidate) {
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

    private static String failureReasons(StarterSeedEvaluator.Result candidate) {
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

    private static void printAndSaveResult(
            StarterSeedEvaluator.Result c,
            long tested,
            long startedNanos
    ) throws IOException {
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

    private static void saveNearMiss(
            StarterSeedEvaluator.Result c,
            long tested,
            long startedNanos
    ) throws IOException {
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

    private static String summarize(StarterSeedEvaluator.Result c) {
        return String.format(
                Locale.ROOT,
                "seed %d | TP %d,%d,%d | %dx%d | meaningful gap %d -> ~%d span | dist %.1fk | SD %.1f | %s",
                c.seed(), c.tpX(), c.tpY(), c.tpZ(), c.width(), c.height(),
                c.neighborGap(), c.neighborSpan(), c.starterDistance() / 1_000.0,
                c.heightStdDev(), c.coalOnIsland() ? "COE COAL ON ISLAND" : "COE coal off island"
        );
    }

    /** Cheap potential-placement lookup used only for pre-RandomState ranking. */
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

    private static long rasterProbeCount(int radius, int step) {
        long cells = radius * 2L / step + 1L;
        return cells * cells;
    }

    private static double secondsBetween(long startNanos, long endNanos) {
        return (endNanos - startNanos) / 1_000_000_000.0;
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
            int potentialCoalDistance,
            double score
    ) {
    }

    private record VerifiedCandidate(
            CoarseCandidate coarse,
            RandomState randomState,
            CoeSeedLocator.Location coal,
            int actualCoalDistance,
            double score
    ) {
    }

    private record StageCandidate(
            StarterSeedEvaluator.Result result,
            CoeSeedLocator.Location coalHint
    ) {
    }

    private record CoalLocation(int x, int z) {
    }
}
