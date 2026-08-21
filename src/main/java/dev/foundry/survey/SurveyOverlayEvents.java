package dev.foundry.survey;

import dev.foundry.Foundry;
import dev.foundry.registry.ModItems;
import dev.foundry.settlement.SettlementSavedData;
import dev.foundry.settlement.SettlementSurveySnapshot;
import dev.foundry.settlement.SettlementSurveySnapshot.SurveyIndustry;
import dev.foundry.settlement.SettlementSurveySnapshot.SurveySettlement;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.joml.Vector3f;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

@Mod.EventBusSubscriber(modid = Foundry.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class SurveyOverlayEvents {
    private static final int SURVEY_RADIUS = 192;
    private static final int SAMPLE_STEP = 4;
    private static final int REFRESH_TICKS = 5;
    private static final int LINK_PARTICLE_SPACING = 6;
    private static final int CLAIM_RING_STEP_DEGREES = 4;
    private static final int BOUNDARY_POST_LEVELS = 5;

    private SurveyOverlayEvents() {
    }

    @SubscribeEvent
    public static void onPlayerTick(TickEvent.PlayerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || !(event.player instanceof ServerPlayer player)) {
            return;
        }
        if (player.tickCount % REFRESH_TICKS != 0 || !isHoldingSurveyorsRod(player)) {
            return;
        }

        ServerLevel level = player.serverLevel();
        SettlementSurveySnapshot snapshot = SettlementSurveySnapshot.create(
                SettlementSavedData.get(level),
                level.dimension(),
                player.blockPosition(),
                SURVEY_RADIUS
        );
        if (snapshot.settlements().isEmpty()) {
            return;
        }

        renderBoundaries(level, player, snapshot.settlements());
        renderCivicLinks(level, player, snapshot.settlements());
    }

    private static boolean isHoldingSurveyorsRod(ServerPlayer player) {
        return player.getMainHandItem().is(ModItems.SURVEYORS_ROD.get())
                || player.getOffhandItem().is(ModItems.SURVEYORS_ROD.get());
    }

    private static void renderBoundaries(ServerLevel level, ServerPlayer player, List<SurveySettlement> towns) {
        BlockPos center = player.blockPosition();
        UUID currentTown = ownerAt(towns, center);

        // Always draw the explicit 128-block claim perimeter. This makes a single town's
        // outer edge immediately legible instead of requiring another town to create a split.
        renderClaimPerimeters(level, player, towns, currentTown);

        int minX = Math.floorDiv(center.getX() - SURVEY_RADIUS, SAMPLE_STEP) * SAMPLE_STEP;
        int maxX = center.getX() + SURVEY_RADIUS;
        int minZ = Math.floorDiv(center.getZ() - SURVEY_RADIUS, SAMPLE_STEP) * SAMPLE_STEP;
        int maxZ = center.getZ() + SURVEY_RADIUS;
        int columns = (maxX - minX) / SAMPLE_STEP + 1;
        int rows = (maxZ - minZ) / SAMPLE_STEP + 1;
        Cell[][] cells = new Cell[columns][rows];

        for (int ix = 0; ix < columns; ix++) {
            int x = minX + ix * SAMPLE_STEP;
            for (int iz = 0; iz < rows; iz++) {
                int z = minZ + iz * SAMPLE_STEP;
                Integer y = surfaceY(level, x, z, center.getY());
                if (y == null) {
                    continue;
                }
                cells[ix][iz] = new Cell(ownerAt(towns, new BlockPos(x, y, z)), y);
            }
        }

        // Also trace ownership changes inside overlapping claim circles. This is the
        // effective border between two towns where nearest-Town-Hall ownership changes.
        for (int ix = 0; ix < columns; ix++) {
            int x = minX + ix * SAMPLE_STEP;
            for (int iz = 0; iz < rows; iz++) {
                int z = minZ + iz * SAMPLE_STEP;
                Cell cell = cells[ix][iz];
                if (cell == null) {
                    continue;
                }

                if (ix + 1 < columns) {
                    Cell east = cells[ix + 1][iz];
                    if (east != null && !Objects.equals(cell.owner(), east.owner())) {
                        renderBoundaryDot(level, player, x + SAMPLE_STEP / 2.0, z,
                                cell.owner(), east.owner(), currentTown, center.getY());
                    }
                }
                if (iz + 1 < rows) {
                    Cell south = cells[ix][iz + 1];
                    if (south != null && !Objects.equals(cell.owner(), south.owner())) {
                        renderBoundaryDot(level, player, x, z + SAMPLE_STEP / 2.0,
                                cell.owner(), south.owner(), currentTown, center.getY());
                    }
                }
            }
        }
    }

    private static void renderClaimPerimeters(ServerLevel level, ServerPlayer player,
                                              List<SurveySettlement> towns, UUID currentTown) {
        BlockPos center = player.blockPosition();
        long visibleSqr = (long) SURVEY_RADIUS * SURVEY_RADIUS;
        int claimRange = SettlementSurveySnapshot.CLAIM_RANGE;

        for (SurveySettlement town : towns) {
            BlockPos hall = town.townHallPos();
            DustParticleOptions particle = townDust(
                    town.id(),
                    town.id().equals(currentTown) ? 1.5F : 1.05F
            );

            for (int degrees = 0; degrees < 360; degrees += CLAIM_RING_STEP_DEGREES) {
                double angle = Math.toRadians(degrees);
                double cos = Math.cos(angle);
                double sin = Math.sin(angle);
                double x = hall.getX() + 0.5 + cos * claimRange;
                double z = hall.getZ() + 0.5 + sin * claimRange;

                BlockPos boundaryPos = new BlockPos((int) Math.floor(x), center.getY(), (int) Math.floor(z));
                if (horizontalDistanceSqr(center, boundaryPos) > visibleSqr) {
                    continue;
                }

                Integer y = surfaceY(level, boundaryPos.getX(), boundaryPos.getZ(), center.getY());
                if (y == null) {
                    continue;
                }

                // Only draw the outer ring where it is still this town's real edge.
                // If another town has already taken ownership before the 128-block edge,
                // the interior split is drawn by the sampled ownership-border pass above.
                BlockPos justInside = new BlockPos(
                        (int) Math.floor(hall.getX() + 0.5 + cos * (claimRange - 2)),
                        y,
                        (int) Math.floor(hall.getZ() + 0.5 + sin * (claimRange - 2))
                );
                BlockPos justOutside = new BlockPos(
                        (int) Math.floor(hall.getX() + 0.5 + cos * (claimRange + 2)),
                        y,
                        (int) Math.floor(hall.getZ() + 0.5 + sin * (claimRange + 2))
                );
                if (!town.id().equals(ownerAt(towns, justInside))
                        || town.id().equals(ownerAt(towns, justOutside))) {
                    continue;
                }

                renderBoundaryPost(level, player, x, y, z, particle, degrees % 16 == 0);
            }
        }
    }

    private static void renderBoundaryDot(ServerLevel level, ServerPlayer player, double x, double z,
                                          UUID first, UUID second, UUID currentTown, int fallbackY) {
        Integer y = surfaceY(level, (int) Math.floor(x), (int) Math.floor(z), fallbackY);
        if (y == null) {
            return;
        }

        if (first != null) {
            float scale = first.equals(currentTown) ? 1.45F : 1.0F;
            renderBoundaryPost(level, player, x, y, z, townDust(first, scale), false);
        }
        if (second != null && !second.equals(first)) {
            float scale = second.equals(currentTown) ? 1.45F : 1.0F;
            renderBoundaryPost(level, player, x + 0.16, y, z + 0.16, townDust(second, scale), false);
        }
    }

    private static void renderBoundaryPost(ServerLevel level, ServerPlayer player, double x, int y, double z,
                                           DustParticleOptions particle, boolean tallMarker) {
        int levels = tallMarker ? BOUNDARY_POST_LEVELS : 3;
        for (int i = 0; i < levels; i++) {
            sendParticle(level, player, particle, x, y + 0.22 + i * 0.72, z);
        }
        if (tallMarker) {
            sendParticle(level, player, ParticleTypes.END_ROD, x, y + 4.1, z);
        }
    }

    private static void renderCivicLinks(ServerLevel level, ServerPlayer player, List<SurveySettlement> towns) {
        BlockPos center = player.blockPosition();
        long visibleSqr = (long) SURVEY_RADIUS * SURVEY_RADIUS;

        for (SurveySettlement town : towns) {
            BlockPos hall = town.townHallPos();
            if (horizontalDistanceSqr(center, hall) <= visibleSqr) {
                renderTownHallMarker(level, player, hall, town.id());
            }

            DustParticleOptions townLine = townDust(town.id(), 0.65F);
            for (BlockPos depot : town.depotPositions()) {
                renderSurfaceLine(level, player, hall, depot, townLine, center.getY());
                renderNodeMarker(level, player, depot, townDust(town.id(), 1.15F), center.getY());
            }

            for (SurveyIndustry industry : town.industries()) {
                renderSurfaceLine(level, player, hall, industry.pos(), townLine, center.getY());
                renderNodeMarker(level, player, industry.pos(), townDust(town.id(), 0.9F), center.getY());
                if (industry.linkedDepotPos() != null) {
                    renderSurfaceLine(level, player, industry.pos(), industry.linkedDepotPos(),
                            new DustParticleOptions(new Vector3f(1.0F, 0.72F, 0.22F), 0.8F), center.getY());
                }
            }
        }
    }

    private static void renderTownHallMarker(ServerLevel level, ServerPlayer player, BlockPos hall, UUID townId) {
        for (int i = 1; i <= 7; i++) {
            sendParticle(level, player, ParticleTypes.END_ROD,
                    hall.getX() + 0.5, hall.getY() + 0.8 + i * 0.75, hall.getZ() + 0.5);
        }
        sendParticle(level, player, townDust(townId, 1.6F),
                hall.getX() + 0.5, hall.getY() + 1.15, hall.getZ() + 0.5);
    }

    private static void renderNodeMarker(ServerLevel level, ServerPlayer player, BlockPos pos,
                                         DustParticleOptions particle, int fallbackY) {
        Integer y = surfaceY(level, pos.getX(), pos.getZ(), fallbackY);
        if (y == null) {
            return;
        }
        sendParticle(level, player, particle, pos.getX() + 0.5, y + 0.35, pos.getZ() + 0.5);
        sendParticle(level, player, particle, pos.getX() + 0.5, y + 0.75, pos.getZ() + 0.5);
    }

    private static void renderSurfaceLine(ServerLevel level, ServerPlayer player, BlockPos from, BlockPos to,
                                          DustParticleOptions particle, int fallbackY) {
        double dx = to.getX() - from.getX();
        double dz = to.getZ() - from.getZ();
        double distance = Math.sqrt(dx * dx + dz * dz);
        int steps = Math.min(64, Math.max(1, (int) Math.ceil(distance / LINK_PARTICLE_SPACING)));

        for (int i = 1; i < steps; i++) {
            double t = i / (double) steps;
            double x = from.getX() + 0.5 + dx * t;
            double z = from.getZ() + 0.5 + dz * t;
            Integer y = surfaceY(level, (int) Math.floor(x), (int) Math.floor(z), fallbackY);
            if (y != null) {
                sendParticle(level, player, particle, x, y + 0.28, z);
            }
        }
    }

    private static UUID ownerAt(List<SurveySettlement> towns, BlockPos pos) {
        UUID nearest = null;
        long nearestDistance = (long) SettlementSurveySnapshot.CLAIM_RANGE
                * SettlementSurveySnapshot.CLAIM_RANGE;

        for (SurveySettlement town : towns) {
            long distance = horizontalDistanceSqr(town.townHallPos(), pos);
            if (distance <= nearestDistance) {
                nearest = town.id();
                nearestDistance = distance;
            }
        }
        return nearest;
    }

    private static Integer surfaceY(ServerLevel level, int x, int z, int fallbackY) {
        BlockPos probe = new BlockPos(x, fallbackY, z);
        if (!level.hasChunkAt(probe)) {
            return null;
        }
        return level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
    }

    private static long horizontalDistanceSqr(BlockPos a, BlockPos b) {
        long dx = (long) a.getX() - b.getX();
        long dz = (long) a.getZ() - b.getZ();
        return dx * dx + dz * dz;
    }

    private static DustParticleOptions townDust(UUID townId, float scale) {
        Vector3f color = switch (Math.floorMod(townId.hashCode(), 6)) {
            case 0 -> new Vector3f(0.25F, 0.85F, 1.00F);
            case 1 -> new Vector3f(1.00F, 0.62F, 0.22F);
            case 2 -> new Vector3f(0.48F, 1.00F, 0.46F);
            case 3 -> new Vector3f(0.80F, 0.48F, 1.00F);
            case 4 -> new Vector3f(1.00F, 0.42F, 0.58F);
            default -> new Vector3f(0.48F, 0.66F, 1.00F);
        };
        return new DustParticleOptions(color, scale);
    }

    private static <T extends ParticleOptions> void sendParticle(ServerLevel level, ServerPlayer player,
                                                                  T particle, double x, double y, double z) {
        level.sendParticles(player, particle, true, x, y, z, 1, 0.0, 0.0, 0.0, 0.0);
    }

    private record Cell(UUID owner, int surfaceY) {
    }
}
