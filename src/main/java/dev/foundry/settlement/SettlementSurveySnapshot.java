package dev.foundry.settlement;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Read-only survey projection of the authoritative settlement SavedData.
 * This deliberately carries only spatial data needed by the in-world survey overlay.
 */
public record SettlementSurveySnapshot(List<SurveySettlement> settlements) {
    public static final int CLAIM_RANGE = SettlementSavedData.DEFAULT_SETTLEMENT_RADIUS;

    public SettlementSurveySnapshot {
        settlements = List.copyOf(settlements);
    }

    public static SettlementSurveySnapshot create(SettlementSavedData data, ResourceKey<Level> dimension,
                                                   BlockPos center, int visibleRange) {
        CompoundTag root = data.save(new CompoundTag());
        String dimensionName = dimension.location().toString();
        int hallRange = Math.max(0, visibleRange) + CLAIM_RANGE;
        long hallRangeSqr = (long) hallRange * hallRange;
        long nodeRangeSqr = (long) Math.max(0, visibleRange) * Math.max(0, visibleRange);

        Map<UUID, MutableSurveySettlement> visible = new LinkedHashMap<>();
        ListTag settlementTags = root.getList("Settlements", Tag.TAG_COMPOUND);
        for (int i = 0; i < settlementTags.size(); i++) {
            CompoundTag tag = settlementTags.getCompound(i);
            if (!dimensionName.equals(tag.getString("Dimension")) || !tag.hasUUID("Id")) {
                continue;
            }

            BlockPos hallPos = BlockPos.of(tag.getLong("TownHallPos"));
            if (horizontalDistanceSqr(center, hallPos) > hallRangeSqr) {
                continue;
            }
            UUID id = tag.getUUID("Id");
            visible.put(id, new MutableSurveySettlement(id, hallPos));
        }

        if (visible.isEmpty()) {
            return new SettlementSurveySnapshot(List.of());
        }

        ListTag depotTags = root.getList("Depots", Tag.TAG_COMPOUND);
        for (int i = 0; i < depotTags.size(); i++) {
            CompoundTag tag = depotTags.getCompound(i);
            if (!dimensionName.equals(tag.getString("Dimension")) || !tag.hasUUID("SettlementId")) {
                continue;
            }
            MutableSurveySettlement town = visible.get(tag.getUUID("SettlementId"));
            if (town == null) {
                continue;
            }
            BlockPos pos = BlockPos.of(tag.getLong("Pos"));
            if (horizontalDistanceSqr(center, pos) <= nodeRangeSqr) {
                town.depots.add(pos);
            }
        }

        ListTag industryTags = root.getList("Industries", Tag.TAG_COMPOUND);
        for (int i = 0; i < industryTags.size(); i++) {
            CompoundTag tag = industryTags.getCompound(i);
            if (!dimensionName.equals(tag.getString("Dimension")) || !tag.hasUUID("SettlementId")) {
                continue;
            }
            MutableSurveySettlement town = visible.get(tag.getUUID("SettlementId"));
            if (town == null) {
                continue;
            }

            BlockPos pos = BlockPos.of(tag.getLong("Pos"));
            if (horizontalDistanceSqr(center, pos) > nodeRangeSqr) {
                continue;
            }

            IndustryType type = IndustryType.fromSerializedName(tag.getString("Type"));
            if (type == null) {
                continue;
            }

            BlockPos linkedDepot = null;
            if (tag.getBoolean("DepotLinked") && dimensionName.equals(tag.getString("DepotDimension"))) {
                linkedDepot = BlockPos.of(tag.getLong("DepotPos"));
            }
            town.industries.add(new SurveyIndustry(pos, type, linkedDepot));
        }

        List<SurveySettlement> result = new ArrayList<>(visible.size());
        for (MutableSurveySettlement town : visible.values()) {
            result.add(new SurveySettlement(
                    town.id,
                    town.townHallPos,
                    List.copyOf(town.depots),
                    List.copyOf(town.industries)
            ));
        }
        return new SettlementSurveySnapshot(result);
    }

    private static long horizontalDistanceSqr(BlockPos a, BlockPos b) {
        long dx = (long) a.getX() - b.getX();
        long dz = (long) a.getZ() - b.getZ();
        return dx * dx + dz * dz;
    }

    public record SurveySettlement(
            UUID id,
            BlockPos townHallPos,
            List<BlockPos> depotPositions,
            List<SurveyIndustry> industries
    ) {
    }

    public record SurveyIndustry(BlockPos pos, IndustryType type, BlockPos linkedDepotPos) {
    }

    private static final class MutableSurveySettlement {
        private final UUID id;
        private final BlockPos townHallPos;
        private final List<BlockPos> depots = new ArrayList<>();
        private final List<SurveyIndustry> industries = new ArrayList<>();

        private MutableSurveySettlement(UUID id, BlockPos townHallPos) {
            this.id = id;
            this.townHallPos = townHallPos;
        }
    }
}
