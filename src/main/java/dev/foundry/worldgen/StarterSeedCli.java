package dev.foundry.worldgen;

import com.tom.createores.recipe.VeinRecipe;
import dev.foundry.Foundry;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.biome.BiomeSource;
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
 * The actual geography/terrain checks remain authoritative in StarterSeedSearch. This class
 * deliberately reuses those exact coarse and exact evaluators, but advances through unbounded
 * seed ranges until a candidate meets hard acceptance criteria instead of merely returning the
 * best candidate from a fixed-size batch.
 */
@Mod.EventBusSubscriber(modid = Foundry.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class StarterSeedCli {
    private static final String ENABLE_PROPERTY = "foundry.starterSeedSearch";
    private static final ResourceLocation COAL_VEIN =
            new ResourceLocation("createoreexcavation", "ore_vein_type/coal");

    private static final int BATCH_SIZE = 10_000;
    private static final int FINALISTS_PER_BATCH = 24;

    // Hard acceptance gates for the canonical Tiger Ascent starter geography.
    private static final int MIN_ISLAND_SPAN = 240;
    private static final int MAX_ISLAND_SPAN = 420;
    private static final int MIN_NEIGHBOR_GAP = 180;
    private static final int MAX_NEIGHBOR_GAP = 450;
    private static final double MIN_JUNGLE_SHARE = 0.60;
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
        System.out.println("[Foundry] Searching continuously until a seed passes every hard gate.");
        System.out.println(String.format(
                Locale.ROOT,
                "[Foundry] Gates: island %d-%d blocks | neighbor %d-%d | jungle >= %.0f%% | height SD <= %.1f | coal ON ISLAND",
                MIN_ISLAND_SPAN,
                MAX_ISLAND_SPAN,
                MIN_NEIGHBOR_GAP,
                MAX_NEIGHBOR_GAP,
                MIN_JUNGLE_SHARE * 100.0,
                MAX_HEIGHT_STD_DEV
        ));

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
                long batchStart = tested;

                for (int i = 0; i < BATCH_SIZE && server.isRunning(); i++, globalIndex++) {
                    long seed = alternatingSeed(globalIndex);
                    Object candidate = coarseMethod.invoke(null, seed, coalPlacement);
                    tested++;
                    if (candidate != null) {
                        coarse.add(new ScoredCandidate(candidate, doubleAccessor(candidate, "score")));
                    }
                }

                coarse.sort(Comparator.comparingDouble(ScoredCandidate::score));
                int finalists = Math.min(FINALISTS_PER_BATCH, coarse.size());

                Object bestThisBatch = null;
                double bestScore = Double.POSITIVE_INFINITY;

                for (int i = 0; i < finalists && server.isRunning(); i++) {
                    Object coarseCandidate = coarse.get(i).candidate();
                    long seed = longAccessor(coarseCandidate, "seed");
                    RandomState randomState = RandomState.create(settings, noiseRegistry.asLookup(), seed);
                    Object exact = exactMethod.invoke(
                            null,
                            level,
                            generator,
                            biomeSource,
                            randomState,
                            coalPlacement,
                            seed
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
                String bestSummary = bestThisBatch == null
                        ? "no exact finalist"
                        : summarize(bestThisBatch);
                System.out.println(String.format(
                        Locale.ROOT,
                        "[Foundry] tested %,d seeds (batch %,d-%,d) in %.1fs | best: %s",
                        tested,
                        batchStart,
                        tested - 1,
                        elapsedSeconds,
                        bestSummary
                ));
            }
        } catch (Throwable error) {
            System.err.println("[Foundry] CLI starter-seed search failed: " + error);
            error.printStackTrace();
            server.execute(() -> server.halt(false));
        }
    }

    private static boolean passesHardGates(Object candidate) throws ReflectiveOperationException {
        int width = intAccessor(candidate, "width");
        int height = intAccessor(candidate, "height");
        int span = Math.max(width, height);
        int neighborGap = intAccessor(candidate, "neighborGap");
        double jungleShare = doubleAccessor(candidate, "jungleShare");
        double heightStdDev = doubleAccessor(candidate, "heightStdDev");
        boolean coalOnIsland = booleanAccessor(candidate, "coalOnIsland");

        return span >= MIN_ISLAND_SPAN
                && span <= MAX_ISLAND_SPAN
                && neighborGap >= MIN_NEIGHBOR_GAP
                && neighborGap <= MAX_NEIGHBOR_GAP
                && jungleShare >= MIN_JUNGLE_SHARE
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
                        + "Jungle:       %.1f%%%n"
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
        System.out.println("[Foundry] Saved result to starter-seed-result.txt");
    }

    private static String summarize(Object candidate) throws ReflectiveOperationException {
        return String.format(
                Locale.ROOT,
                "seed %d | %dx%d | gap %d | jungle %.0f%% | height SD %.1f | coal %s",
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
}
