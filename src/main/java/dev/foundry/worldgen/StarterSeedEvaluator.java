package dev.foundry.worldgen;

import com.tom.createores.recipe.VeinRecipe;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.RandomState;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

/**
 * Physical starter-island evaluator shared by the staged CLI search.
 *
 * <p>All terrain measurements come from the live Tectonic/Foundry generator. Preview resolutions
 * are ranking-only; the 16-block pass remains authoritative for acceptance.</p>
 */
final class StarterSeedEvaluator {
    private static final int MIN_MEANINGFUL_NEIGHBOR_SPAN = 160;
    private static final int MIN_MEANINGFUL_NEIGHBOR_AREA = 12_000;

    private StarterSeedEvaluator() {
    }

    static Result evaluate(
            ServerLevel level,
            NoiseBasedChunkGenerator generator,
            RandomState randomState,
            List<VeinRecipe> orderedVeinRecipes,
            VeinRecipe coalRecipe,
            long seed,
            int anchorX,
            int anchorZ,
            int radius,
            int step,
            CoeSeedLocator.Location verifiedCoalHint
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

        CoeSeedLocator.Location coal = verifiedCoalHint;
        if (coal == null) {
            coal = CoeSeedLocator.nearestActualVein(
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
        }
        if (coal == null) {
            return null;
        }

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

        return new Result(
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
        if (shape.cells().isEmpty()) {
            return null;
        }

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
                if (!land[gz][gx] || seen[gz][gx]) {
                    continue;
                }

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
                if (!meaningful) {
                    continue;
                }

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
                if (nx < 0 || nz < 0 || nx >= cols || nz >= rows) {
                    continue;
                }
                if (!land[nz][nx] || visited[nz][nx]) {
                    continue;
                }
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
                if (distanceSq < bestSq) {
                    bestSq = distanceSq;
                }
            }
        }
        return Math.max(0, (int) Math.round(Math.sqrt(bestSq)) - step);
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

    private record Cell(int gridX, int gridZ) {
    }

    private record ComponentShape(
            List<Cell> cells,
            int minX,
            int maxX,
            int minZ,
            int maxZ
    ) {
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

    private record NeighborInfo(int gap, int span, int estimatedArea) {
    }

    record Result(
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
        Result withScore(double newScore) {
            return new Result(
                    seed,
                    newScore,
                    width,
                    height,
                    neighborGap,
                    neighborSpan,
                    neighborEstimatedArea,
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
