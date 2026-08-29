package dev.foundry.worldgen;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.tom.createores.recipe.VeinRecipe;
import dev.foundry.Foundry;
import net.minecraft.ChatFormatting;
import net.minecraft.Util;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.Holder;
import net.minecraft.core.QuartPos;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BiomeTags;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.NormalNoise;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.structure.placement.RandomSpreadStructurePlacement;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;

/**
 * Searches arbitrary seeds against the real Foundry/Tectonic noise stack without generating
 * chunks. A cheap strategic-mask pass rejects bad seeds first; only a tiny shortlist pays for
 * real terrain height + biome sampling through a seed-specific RandomState.
 */
@Mod.EventBusSubscriber(modid = Foundry.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class StarterSeedSearch {
    private static final ResourceLocation COAL_VEIN =
            new ResourceLocation("createoreexcavation", "ore_vein_type/coal");

    private static final int DEFAULT_SEEDS = 8_000;
    private static final int COARSE_STEP = 64;
    private static final int COARSE_RADIUS = 512;
    private static final int EXACT_STEP = 16;
    private static final int EXACT_RADIUS = 640;
    private static final double LAND_THRESHOLD = -0.19;

    // Requested starter profile. These are scoring targets, not hard worldgen changes.
    private static final double TARGET_ISLAND_SPAN = 320.0;
    private static final double TARGET_NEIGHBOR_GAP = 300.0;
    private static final double TARGET_JUNGLE_SHARE = 0.70;

    private StarterSeedSearch() {
    }

    @SubscribeEvent
    public static void registerCommands(RegisterCommandsEvent event) {
        event.getDispatcher().register(
                Commands.literal("foundry")
                        .requires(source -> source.hasPermission(2))
                        .then(Commands.literal("starterseed")
                                .executes(context -> start(context.getSource(), DEFAULT_SEEDS))
                                .then(Commands.argument("seeds", IntegerArgumentType.integer(1_000, 50_000))
                                        .executes(context -> start(
                                                context.getSource(),
                                                IntegerArgumentType.getInteger(context, "seeds")
                                        ))))
        );
    }

    private static int start(CommandSourceStack source, int seedCount) {
        ServerLevel level = source.getServer().overworld();
        if (!(level.getChunkSource().getGenerator() instanceof NoiseBasedChunkGenerator generator)) {
            source.sendFailure(Component.literal("Foundry starter search requires the Overworld noise generator."));
            return 0;
        }

        VeinRecipe coal = level.getRecipeManager().byKey(COAL_VEIN)
                .filter(VeinRecipe.class::isInstance)
                .map(VeinRecipe.class::cast)
                .orElse(null);
        if (coal == null) {
            source.sendFailure(Component.literal("COE coal vein recipe is not loaded."));
            return 0;
        }

        RandomSpreadStructurePlacement coalPlacement = coal.getPlacement();
        NoiseGeneratorSettings settings = generator.generatorSettings().value();
        Registry<NormalNoise.NoiseParameters> noiseRegistry =
                level.registryAccess().registryOrThrow(Registries.NOISE);
        BiomeSource biomeSource = generator.getBiomeSource();

        source.sendSystemMessage(Component.literal(
                "[Foundry] Searching " + seedCount
                        + " seeds for ~300-block jungle starter island + ~300-block neighbor + coal..."
        ).withStyle(ChatFormatting.YELLOW));
        source.sendSystemMessage(Component.literal(
                "Cheap pass uses the exact StrategicMacroMask + COE placement; finalists use real Tectonic heights/biomes."
        ).withStyle(ChatFormatting.GRAY));

        CompletableFuture
                .supplyAsync(
                        () -> search(
                                level,
                                generator,
                                settings,
                                noiseRegistry,
                                biomeSource,
                                coalPlacement,
                                seedCount
                        ),
                        Util.backgroundExecutor()
                )
                .whenComplete((results, error) -> source.getServer().execute(() -> {
                    if (error != null) {
                        source.sendFailure(Component.literal(
                                "Starter seed search failed: " + error.getClass().getSimpleName()
                                        + ": " + error.getMessage()
                        ));
                        error.printStackTrace();
                        return;
                    }
                    if (results.isEmpty()) {
                        source.sendFailure(Component.literal(
                                "No suitable starter candidate survived this search window. Try /foundry starterseed 30000"
                        ));
                        return;
                    }

                    source.sendSystemMessage(Component.literal("[Foundry] STARTER SEED CANDIDATES")
                            .withStyle(ChatFormatting.GREEN, ChatFormatting.BOLD));
                    for (int i = 0; i < results.size(); i++) {
                        ExactCandidate candidate = results.get(i);
                        source.sendSystemMessage(Component.literal(String.format(
                                Locale.ROOT,
                                "%d) seed %d | island ~%dx%d | nearest land ~%d blocks | jungle %.0f%% | height sd %.1f | coal (%d,%d)%s",
                                i + 1,
                                candidate.seed(),
                                candidate.width(),
                                candidate.height(),
                                candidate.neighborGap(),
                                candidate.jungleShare() * 100.0,
                                candidate.heightStdDev(),
                                candidate.coalX(),
                                candidate.coalZ(),
                                candidate.coalOnIsland() ? " ON ISLAND" : " nearby"
                        )).withStyle(i == 0 ? ChatFormatting.AQUA : ChatFormatting.WHITE));
                    }
                    source.sendSystemMessage(Component.literal(
                            "Top result is the one to create first; search does not generate or save any chunks."
                    ).withStyle(ChatFormatting.GRAY));
                }));

        return 1;
    }

    private static List<ExactCandidate> search(
            ServerLevel level,
            NoiseBasedChunkGenerator generator,
            NoiseGeneratorSettings settings,
            Registry<NormalNoise.NoiseParameters> noiseRegistry,
            BiomeSource biomeSource,
            RandomSpreadStructurePlacement coalPlacement,
            int seedCount
    ) {
        List<CoarseCandidate> coarse = new ArrayList<>();

        for (int index = 0; index < seedCount; index++) {
            long seed = alternatingSeed(index);
            CoarseCandidate candidate = coarseCandidate(seed, coalPlacement);
            if (candidate != null) {
                coarse.add(candidate);
            }
        }

        coarse.sort(Comparator.comparingDouble(CoarseCandidate::score));
        int finalists = Math.min(24, coarse.size());
        List<ExactCandidate> exact = new ArrayList<>(finalists);

        for (int i = 0; i < finalists; i++) {
            CoarseCandidate candidate = coarse.get(i);
            RandomState randomState = RandomState.create(
                    settings,
                    noiseRegistry.asLookup(),
                    candidate.seed()
            );
            ExactCandidate result = exactCandidate(
                    level,
                    generator,
                    biomeSource,
                    randomState,
                    coalPlacement,
                    candidate.seed()
            );
            if (result != null) {
                exact.add(result);
            }
        }

        exact.sort(Comparator.comparingDouble(ExactCandidate::score));
        if (exact.size() > 5) {
            return new ArrayList<>(exact.subList(0, 5));
        }
        return exact;
    }

    private static CoarseCandidate coarseCandidate(
            long seed,
            RandomSpreadStructurePlacement coalPlacement
    ) {
        GridComponent grid = buildStrategicGrid(seed, COARSE_RADIUS, COARSE_STEP);
        if (grid == null) {
            return null;
        }

        int span = Math.max(grid.width(), grid.height());
        if (span < 190 || span > 520 || grid.originDistance() > 160) {
            return null;
        }
        if (grid.neighborGap() < 90 || grid.neighborGap() > 700) {
            return null;
        }

        CoalLocation coal = nearestCoal(seed, coalPlacement, grid.centerX(), grid.centerZ(), 1_000);
        if (coal == null || coal.distanceToComponent() > 180) {
            return null;
        }

        double score = Math.abs(span - TARGET_ISLAND_SPAN)
                + Math.abs(grid.neighborGap() - TARGET_NEIGHBOR_GAP) * 0.65
                + grid.originDistance() * 1.4
                + coal.distanceToComponent() * 1.8;

        return new CoarseCandidate(seed, score);
    }

    private static ExactCandidate exactCandidate(
            ServerLevel level,
            NoiseBasedChunkGenerator generator,
            BiomeSource biomeSource,
            RandomState randomState,
            RandomSpreadStructurePlacement coalPlacement,
            long seed
    ) {
        int cells = EXACT_RADIUS * 2 / EXACT_STEP + 1;
        boolean[][] land = new boolean[cells][cells];
        int[][] heights = new int[cells][cells];
        int seaLevel = generator.getSeaLevel();

        int nearestX = -1;
        int nearestZ = -1;
        double nearestOriginSq = Double.POSITIVE_INFINITY;

        for (int gz = 0; gz < cells; gz++) {
            int z = -EXACT_RADIUS + gz * EXACT_STEP;
            for (int gx = 0; gx < cells; gx++) {
                int x = -EXACT_RADIUS + gx * EXACT_STEP;
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

        if (nearestX < 0 || Math.sqrt(nearestOriginSq) > 180.0) {
            return null;
        }

        Component component = floodComponent(land, nearestX, nearestZ, EXACT_STEP, EXACT_RADIUS);
        if (component == null) {
            return null;
        }

        int span = Math.max(component.width(), component.height());
        if (span < 180 || span > 520) {
            return null;
        }

        int neighborGap = nearestOtherLandGap(land, component, EXACT_STEP, EXACT_RADIUS);
        if (neighborGap < 80 || neighborGap > 800) {
            return null;
        }

        double heightSum = 0.0;
        double heightSqSum = 0.0;
        int heightCount = 0;
        int jungleCount = 0;
        int biomeCount = 0;

        for (Cell cell : component.cells()) {
            int height = heights[cell.gridZ()][cell.gridX()];
            heightSum += height;
            heightSqSum += (double) height * height;
            heightCount++;

            // Half-resolution biome sampling is enough to characterize a ~300-block island while
            // keeping the expensive finalist pass quick.
            if (((cell.gridX() + cell.gridZ()) & 1) == 0) {
                int x = -EXACT_RADIUS + cell.gridX() * EXACT_STEP;
                int z = -EXACT_RADIUS + cell.gridZ() * EXACT_STEP;
                Holder<Biome> biome = biomeSource.getNoiseBiome(
                        QuartPos.fromBlock(x),
                        QuartPos.fromBlock(height),
                        QuartPos.fromBlock(z),
                        randomState.sampler()
                );
                biomeCount++;
                if (biome.is(BiomeTags.IS_JUNGLE)) {
                    jungleCount++;
                }
            }
        }

        double mean = heightSum / Math.max(1, heightCount);
        double variance = heightSqSum / Math.max(1, heightCount) - mean * mean;
        double heightStdDev = Math.sqrt(Math.max(0.0, variance));
        double jungleShare = biomeCount == 0 ? 0.0 : (double) jungleCount / biomeCount;

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
        boolean coalOnIsland = containsWorld(component, coal.x(), coal.z(), EXACT_STEP, EXACT_RADIUS);
        if (!coalOnIsland && coal.distanceToComponent() > 160) {
            return null;
        }

        double score = Math.abs(span - TARGET_ISLAND_SPAN)
                + Math.abs(neighborGap - TARGET_NEIGHBOR_GAP) * 0.65
                + Math.max(0.0, TARGET_JUNGLE_SHARE - jungleShare) * 850.0
                + heightStdDev * 16.0
                + (coalOnIsland ? 0.0 : coal.distanceToComponent() * 2.0);

        return new ExactCandidate(
                seed,
                score,
                component.width(),
                component.height(),
                neighborGap,
                jungleShare,
                heightStdDev,
                coal.x(),
                coal.z(),
                coalOnIsland
        );
    }

    private static GridComponent buildStrategicGrid(long seed, int radius, int step) {
        int cells = radius * 2 / step + 1;
        boolean[][] land = new boolean[cells][cells];
        int nearestX = -1;
        int nearestZ = -1;
        double nearestSq = Double.POSITIVE_INFINITY;

        for (int gz = 0; gz < cells; gz++) {
            int z = -radius + gz * step;
            for (int gx = 0; gx < cells; gx++) {
                int x = -radius + gx * step;
                if (StrategicMacroMask.sample(seed, x, z) < LAND_THRESHOLD) {
                    continue;
                }
                land[gz][gx] = true;
                double distanceSq = (double) x * x + (double) z * z;
                if (distanceSq < nearestSq) {
                    nearestSq = distanceSq;
                    nearestX = gx;
                    nearestZ = gz;
                }
            }
        }

        if (nearestX < 0) {
            return null;
        }

        Component component = floodComponent(land, nearestX, nearestZ, step, radius);
        if (component == null) {
            return null;
        }
        int gap = nearestOtherLandGap(land, component, step, radius);
        return new GridComponent(
                component.width(),
                component.height(),
                gap,
                (int) Math.round(Math.sqrt(nearestSq)),
                component.centerX(),
                component.centerZ()
        );
    }

    private static Component floodComponent(
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
        int centerWorldX = -radius + centerGridX * step;
        int centerWorldZ = -radius + centerGridZ * step;

        return new Component(
                cells,
                visited,
                (maxX - minX + 1) * step,
                (maxZ - minZ + 1) * step,
                centerWorldX,
                centerWorldZ
        );
    }

    private static int nearestOtherLandGap(
            boolean[][] land,
            Component component,
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
                ChunkPos chunk = placement.getPotentialStructureChunk(
                        seed,
                        rx * spacing,
                        rz * spacing
                );
                int x = chunk.getMiddleBlockPosition(0).getX();
                int z = chunk.getMiddleBlockPosition(0).getZ();
                double distance = Math.hypot(x - targetX, z - targetZ);
                if (distance < bestDistance) {
                    bestDistance = distance;
                    best = new CoalLocation(x, z, (int) Math.round(distance));
                }
            }
        }

        return best;
    }

    private static boolean containsWorld(
            Component component,
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

    private static long alternatingSeed(int index) {
        long value = index / 2L;
        return (index & 1) == 0 ? value : -value - 1L;
    }

    private record Cell(int gridX, int gridZ) {
    }

    private record Component(
            List<Cell> cells,
            boolean[][] visited,
            int width,
            int height,
            int centerX,
            int centerZ
    ) {
    }

    private record GridComponent(
            int width,
            int height,
            int neighborGap,
            int originDistance,
            int centerX,
            int centerZ
    ) {
    }

    private record CoalLocation(int x, int z, int distanceToComponent) {
    }

    private record CoarseCandidate(long seed, double score) {
    }

    private record ExactCandidate(
            long seed,
            double score,
            int width,
            int height,
            int neighborGap,
            double jungleShare,
            double heightStdDev,
            int coalX,
            int coalZ,
            boolean coalOnIsland
    ) {
    }
}
