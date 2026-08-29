package dev.foundry.worldgen;

import com.tom.createores.recipe.VeinRecipe;
import dev.foundry.Foundry;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.biome.BiomeSource;
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
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;

/**
 * Headless, continuous starter-seed curator used by the Gradle starterSeedSearch task.
 *
 * The search is deliberately staged so the expensive Tectonic height solver is only used where
 * it buys us information:
 *
 *  1. StrategicMacroMask + COE placement cheaply screen a large deterministic seed range.
 *  2. A sparse 7x7 real-Tectonic preview estimates physical relief for the best geometry.
 *  3. Only the two best previews pay for StarterSeedSearch's full 16-block terrain/biome raster.
 *
 * Biome mix is still measured and reported by the final validator, but it is not a selection or
 * acceptance requirement. A reported winner therefore passes the high-resolution geography,
 * flatness and coal checks without being biased toward jungle.
 */
@Mod.EventBusSubscriber(modid = Foundry.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class StarterSeedCli {
    private static final String ENABLE_PROPERTY = "foundry.starterSeedSearch";
    private static final ResourceLocation COAL_VEIN =
            new ResourceLocation("createoreexcavation", "ore_vein_type/coal");

    // Cheap strategic math is fast, so amortize each expensive Tectonic verification over a large batch.
    private static final int BATCH_SIZE = 20_000;
    private static final int PREVIEW_CANDIDATES_PER_BATCH = 24;
    private static final int EXACT_FINALISTS_PER_BATCH = 2;

    // Sparse physical preview: 7x7 = 49 real height probes per candidate instead of 6,561.
    private static final int PREVIEW_RADIUS = 192;
    private static final int PREVIEW_STEP = 64;
    private static final double PREVIEW_MAX_HEIGHT_STD_DEV = 18.0;
    private static final int PREVIEW_MIN_LAND_SAMPLES = 5;

    // Hard acceptance gates for the canonical Tiger Ascent starter geography.
    private static final int MIN_ISLAND_SPAN = 240;
    private static final int MAX_ISLAND_SPAN = 420;
    private static final int MIN_NEIGHBOR_GAP = 180;
    private static final int MAX_NEIGHBOR_GAP = 450;
    private static final double MAX_HEIGHT_STD_DEV = 10.0;

    private StarterSeedCli() {
    }

    @SubscribeEvent
    public static void onServerStarted(ServerStartedEvent event) {
        if (!Boolean.getBoolean(ENABLE_PROPERTY)) {
            return;
        }

        MinecraftServer server = event.getServer();
        System.out.println("[Foundry] CLI starter-seed search enabled.");
        System.out.println("[Foundry] Optimized pipeline: 20k cheap seeds -> 24 sparse previews -> 2 exact finalists.");
        System.out.println(String.format(
                Locale.ROOT,
                "[Foundry] FINAL GATES: island %d-%d blocks | neighbor %d-%d | height SD <= %.1f | coal ON ISLAND | biome unrestricted",
                MIN_ISLAND_SPAN,
                MAX_ISLAND_SPAN,
                MIN_NEIGHBOR_GAP,
                MAX_NEIGHBOR_GAP,
                MAX_HEIGHT_STD_DEV
        ));
        System.out.println("[Foundry] Winner will be saved to run-seed-search/starter-seed-result.txt");

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
            BiomeSource biomeSource = generator.getBiomeSource();

            Method coarseMethod = StarterSeedSearch.class.getDeclaredMethod(
                    "coarseCandidate",
                    long.class,
                    RandomSpreadStructurePlacement.class
            );
            Method exactMethod = StarterSeedSearch.class.getDeclaredMethod(
                    "exactCandidate",
                    ServerLevel.class,
                    NoiseBasedChunkGenerator.class,
                    BiomeSource.class,
                    RandomState.class,
                    RandomSpreadStructurePlacement.class,
                    long.class
            );
            coarseMethod.setAccessible(true);
            exactMethod.setAccessible(true);

            long tested = 0L;
            long globalIndex = 0L;
            long startedNanos = System.nanoTime();

            while (server.isRunning()) {
                List<ScoredCandidate> coarse = new ArrayList<>();
                long batchFirstSeedIndex = globalIndex;

                for (int i = 0; i < BATCH_SIZE && server.isRunning(); i++, globalIndex++) {
                    long seed = alternatingSeed(globalIndex);
                    Object candidate = coarseMethod.invoke(null, seed, coalPlacement);
                    tested++;
                    if (candidate != null) {
                        coarse.add(new ScoredCandidate(candidate, doubleAccessor(candidate, "score")));
                    }
                }

                coarse.sort(Comparator.comparingDouble(ScoredCandidate::score));
                int previewCount = Math.min(PREVIEW_CANDIDATES_PER_BATCH, coarse.size());
                System.out.println(String.format(
                        Locale.ROOT,
                        "[Foundry] %,d seeds cheap-screened | %,d strategic+coal survivors | sparse-previewing %d...",
                        tested,
                        coarse.size(),
                        previewCount
                ));

                List<PreviewCandidate> previews = new ArrayList<>(previewCount);
                for (int i = 0; i < previewCount && server.isRunning(); i++) {
                    Object coarseCandidate = coarse.get(i).candidate();
                    long seed = longAccessor(coarseCandidate, "seed");
                    double coarseScore = coarse.get(i).score();
                    RandomState randomState = RandomState.create(settings, noiseRegistry.asLookup(), seed);

                    PreviewCandidate preview = sparsePreview(
                            level,
                            generator,
                            randomState,
                            seed,
                            coarseScore
                    );
                    if (preview != null) {
                        previews.add(preview);
                    }
                }

                previews.sort(Comparator.comparingDouble(PreviewCandidate::score));
                int exactCount = Math.min(EXACT_FINALISTS_PER_BATCH, previews.size());
                System.out.println(String.format(
                        Locale.ROOT,
                        "[Foundry] sparse preview kept %d/%d | exact-verifying %d...",
                        previews.size(),
                        previewCount,
                        exactCount
                ));

                Object bestThisBatch = null;
                double bestScore = Double.POSITIVE_INFINITY;

                for (int i = 0; i < exactCount && server.isRunning(); i++) {
                    PreviewCandidate preview = previews.get(i);
                    Object exact = exactMethod.invoke(
                            null,
                            level,
                            generator,
                            biomeSource,
                            preview.randomState(),
                            coalPlacement,
                            preview.seed()
                    );
                    if (exact == null) {
                        continue;
                    }

                    double score = doubleAccessor(exact, "score");
                    if (score < bestScore) {
                        bestScore = score;
                        bestThisBatch = exact;
                    }

                    if (passesHardGates(exact)) {
                        printAndSaveResult(exact, tested, startedNanos);
                        server.execute(() -> server.halt(false));
                        return;
                    }
                }

                double elapsedSeconds = (System.nanoTime() - startedNanos) / 1_000_000_000.0;
                double seedsPerSecond = tested / Math.max(0.001, elapsedSeconds);
                String bestSummary = bestThisBatch == null
                        ? (previews.isEmpty() ? "no physical preview survived" : "no exact finalist survived")
                        : summarize(bestThisBatch);
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

    /**
     * Very cheap real-world preview used only to rank strategic-mask survivors. It intentionally
     * uses loose terrain gates so a potentially good candidate is not rejected because of the
     * 64-block sampling grid. Biome is deliberately ignored here. The final 16-block raster
     * remains authoritative for geography, relief, coal location, and biome reporting.
     */
    private static PreviewCandidate sparsePreview(
            ServerLevel level,
            NoiseBasedChunkGenerator generator,
            RandomState randomState,
            long seed,
            double coarseScore
    ) {
        int seaLevel = generator.getSeaLevel();
        double heightSum = 0.0;
        double heightSqSum = 0.0;
        int landSamples = 0;

        for (int z = -PREVIEW_RADIUS; z <= PREVIEW_RADIUS; z += PREVIEW_STEP) {
            for (int x = -PREVIEW_RADIUS; x <= PREVIEW_RADIUS; x += PREVIEW_STEP) {
                int height = generator.getBaseHeight(
                        x,
                        z,
                        Heightmap.Types.OCEAN_FLOOR_WG,
                        level,
                        randomState
                );
                if (height <= seaLevel) {
                    continue;
                }

                landSamples++;
                heightSum += height;
                heightSqSum += (double) height * height;
            }
        }

        if (landSamples < PREVIEW_MIN_LAND_SAMPLES) {
            return null;
        }

        double mean = heightSum / landSamples;
        double variance = heightSqSum / landSamples - mean * mean;
        double heightStdDev = Math.sqrt(Math.max(0.0, variance));

        // Loose preview gate: only obvious high-relief candidates are discarded here.
        if (heightStdDev > PREVIEW_MAX_HEIGHT_STD_DEV) {
            return null;
        }

        double score = coarseScore + heightStdDev * 12.0;

        return new PreviewCandidate(seed, score, heightStdDev, landSamples, randomState);
    }

    private static boolean passesHardGates(Object candidate) throws ReflectiveOperationException {
        int width = intAccessor(candidate, "width");
        int height = intAccessor(candidate, "height");
        int span = Math.max(width, height);
        int neighborGap = intAccessor(candidate, "neighborGap");
        double heightStdDev = doubleAccessor(candidate, "heightStdDev");
        boolean coalOnIsland = booleanAccessor(candidate, "coalOnIsland");

        return span >= MIN_ISLAND_SPAN
                && span <= MAX_ISLAND_SPAN
                && neighborGap >= MIN_NEIGHBOR_GAP
                && neighborGap <= MAX_NEIGHBOR_GAP
                && heightStdDev <= MAX_HEIGHT_STD_DEV
                && coalOnIsland;
    }

    private static void printAndSaveResult(Object candidate, long tested, long startedNanos)
            throws ReflectiveOperationException, IOException {
        long seed = longAccessor(candidate, "seed");
        int width = intAccessor(candidate, "width");
        int height = intAccessor(candidate, "height");
        int neighborGap = intAccessor(candidate, "neighborGap");
        double jungleShare = doubleAccessor(candidate, "jungleShare");
        double heightStdDev = doubleAccessor(candidate, "heightStdDev");
        int coalX = intAccessor(candidate, "coalX");
        int coalZ = intAccessor(candidate, "coalZ");
        double elapsedSeconds = (System.nanoTime() - startedNanos) / 1_000_000_000.0;

        String result = String.format(
                Locale.ROOT,
                "========================================%n"
                        + "FOUNDRY STARTER SEED FOUND%n"
                        + "========================================%n"
                        + "Seed: %d%n%n"
                        + "Island:       %d x %d%n"
                        + "Neighbor gap: %d blocks%n"
                        + "Jungle:       %.1f%% (informational only)%n"
                        + "Height SD:    %.2f%n"
                        + "Coal:         ON ISLAND at %d, %d%n"
                        + "Seeds tested: %,d%n"
                        + "Elapsed:      %.1f seconds%n"
                        + "========================================%n",
                seed,
                width,
                height,
                neighborGap,
                jungleShare * 100.0,
                heightStdDev,
                coalX,
                coalZ,
                tested,
                elapsedSeconds
        );

        System.out.print(result);
        Files.writeString(
                Path.of("starter-seed-result.txt"),
                result,
                StandardCharsets.UTF_8
        );
        System.out.println("[Foundry] Saved result to run-seed-search/starter-seed-result.txt");
    }

    private static String summarize(Object candidate) throws ReflectiveOperationException {
        return String.format(
                Locale.ROOT,
                "seed %d | %dx%d | gap %d | jungle %.0f%% info | height SD %.1f | coal %s",
                longAccessor(candidate, "seed"),
                intAccessor(candidate, "width"),
                intAccessor(candidate, "height"),
                intAccessor(candidate, "neighborGap"),
                doubleAccessor(candidate, "jungleShare") * 100.0,
                doubleAccessor(candidate, "heightStdDev"),
                booleanAccessor(candidate, "coalOnIsland") ? "ON ISLAND" : "nearby"
        );
    }

    private static long alternatingSeed(long index) {
        long value = index / 2L;
        return (index & 1L) == 0L ? value : -value - 1L;
    }

    private static int intAccessor(Object target, String name) throws ReflectiveOperationException {
        return (int) accessor(target, name).invoke(target);
    }

    private static long longAccessor(Object target, String name) throws ReflectiveOperationException {
        return (long) accessor(target, name).invoke(target);
    }

    private static double doubleAccessor(Object target, String name) throws ReflectiveOperationException {
        return (double) accessor(target, name).invoke(target);
    }

    private static boolean booleanAccessor(Object target, String name) throws ReflectiveOperationException {
        return (boolean) accessor(target, name).invoke(target);
    }

    private static Method accessor(Object target, String name) throws NoSuchMethodException {
        Method method = target.getClass().getDeclaredMethod(name);
        method.setAccessible(true);
        return method;
    }

    private record ScoredCandidate(Object candidate, double score) {
    }

    private record PreviewCandidate(
            long seed,
            double score,
            double heightStdDev,
            int landSamples,
            RandomState randomState
    ) {
    }
}
