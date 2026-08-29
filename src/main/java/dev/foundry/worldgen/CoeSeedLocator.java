package dev.foundry.worldgen;

import com.tom.createores.CreateOreExcavation;
import com.tom.createores.recipe.VeinRecipe;
import net.minecraft.core.Holder;
import net.minecraft.core.QuartPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.levelgen.LegacyRandomSource;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.WorldgenRandom;
import net.minecraft.world.level.levelgen.structure.placement.RandomSpreadStructurePlacement;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Seed-aware mirror of Create Ore Excavation's RandomSpreadGenerator placement validation.
 *
 * <p>The normal COE locator reads {@link ServerLevel#getSeed()}, which cannot be used while the
 * starter curator is evaluating thousands of synthetic candidate seeds inside one headless server.
 * This class reproduces COE's actual recipe-priority and biome checks, but evaluates them against
 * the candidate seed's RandomState. Raw RandomSpreadStructurePlacement coordinates alone are not
 * enough to prove a vein really exists.</p>
 */
final class CoeSeedLocator {
    private CoeSeedLocator() {
    }

    static List<VeinRecipe> orderedVeinRecipes(ServerLevel level) {
        List<VeinRecipe> recipes = new ArrayList<>(
                level.getRecipeManager().getAllRecipesFor(
                        CreateOreExcavation.VEIN_RECIPES.getRecipeType()
                )
        );
        recipes.sort(Comparator
                .comparingInt(VeinRecipe::getNegGenerationPriority)
                .thenComparing(VeinRecipe::getId));
        return List.copyOf(recipes);
    }

    static Location nearestActualVein(
            ServerLevel level,
            NoiseBasedChunkGenerator generator,
            RandomState randomState,
            List<VeinRecipe> orderedRecipes,
            VeinRecipe targetRecipe,
            long seed,
            int targetX,
            int targetZ,
            int regionRadius
    ) {
        RandomSpreadStructurePlacement placement = targetRecipe.getPlacement();
        int spacing = placement.spacing();
        int targetChunkX = Math.floorDiv(targetX, 16);
        int targetChunkZ = Math.floorDiv(targetZ, 16);

        Location best = null;
        double bestDistanceSq = Double.POSITIVE_INFINITY;

        // This matches COE's own search geometry: probe a chunk coordinate separated by the
        // recipe's spacing, then let RandomSpreadStructurePlacement select the potential chunk.
        for (int dz = -regionRadius; dz <= regionRadius; dz++) {
            for (int dx = -regionRadius; dx <= regionRadius; dx++) {
                int probeChunkX = targetChunkX + spacing * dx;
                int probeChunkZ = targetChunkZ + spacing * dz;
                ChunkPos potential = placement.getPotentialStructureChunk(
                        seed,
                        probeChunkX,
                        probeChunkZ
                );

                VeinRecipe actual = pickActualRecipe(
                        level,
                        generator,
                        randomState,
                        orderedRecipes,
                        seed,
                        potential
                );
                if (actual == null || !actual.getId().equals(targetRecipe.getId())) {
                    continue;
                }

                int x = potential.getMiddleBlockPosition(0).getX();
                int z = potential.getMiddleBlockPosition(0).getZ();
                double ddx = x - targetX;
                double ddz = z - targetZ;
                double distanceSq = ddx * ddx + ddz * ddz;
                if (distanceSq < bestDistanceSq) {
                    bestDistanceSq = distanceSq;
                    best = new Location(x, z, targetRecipe.getId());
                }
            }
        }

        return best;
    }

    private static VeinRecipe pickActualRecipe(
            ServerLevel level,
            NoiseBasedChunkGenerator generator,
            RandomState randomState,
            List<VeinRecipe> orderedRecipes,
            long seed,
            ChunkPos chunk
    ) {
        int minY = QuartPos.fromBlock(level.getMinBuildHeight());
        int maxY = minY + QuartPos.fromBlock(level.getHeight()) - 1;

        for (VeinRecipe recipe : orderedRecipes) {
            ChunkPos potential = recipe.getPlacement().getPotentialStructureChunk(
                    seed,
                    chunk.x,
                    chunk.z
            );
            if (potential.x != chunk.x || potential.z != chunk.z) {
                continue;
            }

            // Reproduce RandomSpreadGenerator.pick(ServerLevel, ChunkPos, ...): COE seeds this RNG
            // from the world seed and candidate chunk, then samples one biome quart inside it.
            WorldgenRandom rng = new WorldgenRandom(new LegacyRandomSource(0L));
            rng.setLargeFeatureSeed(seed, chunk.x, chunk.z);
            Holder<Biome> biome = generator.getBiomeSource().getNoiseBiome(
                    QuartPos.fromSection(chunk.x) + rng.nextInt(4),
                    minY + rng.nextInt(maxY),
                    QuartPos.fromSection(chunk.z) + rng.nextInt(4),
                    randomState.sampler()
            );

            if (recipe.canGenerate(level, biome)) {
                return recipe;
            }
        }

        return null;
    }

    record Location(int x, int z, ResourceLocation veinId) {
    }
}
