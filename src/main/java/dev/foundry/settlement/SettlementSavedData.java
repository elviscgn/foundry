package dev.foundry.settlement;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;

public final class SettlementSavedData extends SavedData {
    private static final String DATA_NAME = "foundry_settlements";
    private static final String TAG_SETTLEMENTS = "Settlements";
    private static final String TAG_DEPOTS = "Depots";
    private static final String TAG_LAST_PROCESSED_DAY = "LastProcessedDay";
    private static final int DEFAULT_DEPOT_LINK_RANGE = 128;
    private static final long TICKS_PER_DAY = 24_000L;

    private final Map<UUID, Settlement> settlementsById = new HashMap<>();
    private final Map<String, UUID> settlementIdsByLocation = new HashMap<>();
    private final Map<String, DepotLink> depotLinksByLocation = new HashMap<>();
    private long lastProcessedDay = -1L;

    public static SettlementSavedData get(ServerLevel level) {
        ServerLevel overworld = level.getServer().overworld();
        return overworld.getDataStorage().computeIfAbsent(
                SettlementSavedData::load,
                SettlementSavedData::new,
                DATA_NAME
        );
    }

    public static SettlementSavedData load(CompoundTag tag) {
        SettlementSavedData data = new SettlementSavedData();
        ListTag settlements = tag.getList(TAG_SETTLEMENTS, Tag.TAG_COMPOUND);

        for (int i = 0; i < settlements.size(); i++) {
            Settlement settlement = Settlement.load(settlements.getCompound(i));
            data.settlementsById.put(settlement.getId(), settlement);
            data.settlementIdsByLocation.put(settlement.locationKey(), settlement.getId());
        }

        ListTag depots = tag.getList(TAG_DEPOTS, Tag.TAG_COMPOUND);
        for (int i = 0; i < depots.size(); i++) {
            DepotLink depotLink = DepotLink.load(depots.getCompound(i));
            if (data.settlementsById.containsKey(depotLink.settlementId())) {
                data.depotLinksByLocation.put(depotLink.locationKey(), depotLink);
            }
        }

        if (tag.contains(TAG_LAST_PROCESSED_DAY, Tag.TAG_LONG)) {
            data.lastProcessedDay = tag.getLong(TAG_LAST_PROCESSED_DAY);
        }

        return data;
    }

    public Settlement getOrCreate(ResourceKey<Level> dimension, BlockPos pos) {
        String locationKey = Settlement.locationKey(dimension, pos);
        UUID existingId = settlementIdsByLocation.get(locationKey);
        if (existingId != null) {
            Settlement existing = settlementsById.get(existingId);
            if (existing != null) {
                return existing;
            }
        }

        Settlement settlement = Settlement.create(dimension, pos);
        if (lastProcessedDay >= 0L) {
            settlement.recordHistory(lastProcessedDay);
        }
        settlementsById.put(settlement.getId(), settlement);
        settlementIdsByLocation.put(locationKey, settlement.getId());
        setDirty();
        return settlement;
    }

    public void remove(ResourceKey<Level> dimension, BlockPos pos) {
        String locationKey = Settlement.locationKey(dimension, pos);
        UUID settlementId = settlementIdsByLocation.remove(locationKey);
        if (settlementId == null) {
            return;
        }

        boolean changed = settlementsById.remove(settlementId) != null;
        Iterator<DepotLink> depotIterator = depotLinksByLocation.values().iterator();
        while (depotIterator.hasNext()) {
            if (depotIterator.next().settlementId().equals(settlementId)) {
                depotIterator.remove();
                changed = true;
            }
        }

        if (changed) {
            setDirty();
        }
    }

    public Settlement linkDepot(ResourceKey<Level> dimension, BlockPos depotPos) {
        Settlement existing = getSettlementForDepot(dimension, depotPos);
        if (existing != null) {
            return existing;
        }

        Settlement nearest = findNearestSettlement(dimension, depotPos, DEFAULT_DEPOT_LINK_RANGE);
        if (nearest == null) {
            return null;
        }

        DepotLink depotLink = new DepotLink(
                dimension.location().toString(),
                depotPos.asLong(),
                nearest.getId()
        );
        depotLinksByLocation.put(depotLink.locationKey(), depotLink);
        setDirty();
        return nearest;
    }

    public Settlement getSettlementForDepot(ResourceKey<Level> dimension, BlockPos depotPos) {
        String locationKey = Settlement.locationKey(dimension, depotPos);
        DepotLink depotLink = depotLinksByLocation.get(locationKey);
        if (depotLink == null) {
            return null;
        }

        Settlement settlement = settlementsById.get(depotLink.settlementId());
        if (settlement == null) {
            depotLinksByLocation.remove(locationKey);
            setDirty();
        }
        return settlement;
    }

    public void removeDepot(ResourceKey<Level> dimension, BlockPos depotPos) {
        String locationKey = Settlement.locationKey(dimension, depotPos);
        if (depotLinksByLocation.remove(locationKey) != null) {
            setDirty();
        }
    }

    public long advanceEconomy(long dayTime) {
        long currentDay = Math.floorDiv(dayTime, TICKS_PER_DAY);
        if (lastProcessedDay < 0L) {
            lastProcessedDay = currentDay;
            for (Settlement settlement : settlementsById.values()) {
                settlement.recordHistory(currentDay);
            }
            setDirty();
            return 0L;
        }

        if (currentDay < lastProcessedDay) {
            lastProcessedDay = currentDay;
            for (Settlement settlement : settlementsById.values()) {
                settlement.resetHistory(currentDay);
            }
            setDirty();
            return 0L;
        }

        if (currentDay == lastProcessedDay) {
            return 0L;
        }

        long daysElapsed = currentDay - lastProcessedDay;
        long skippedDays = Math.max(0L, daysElapsed - Settlement.HISTORY_LIMIT);
        if (skippedDays > 0L) {
            for (Settlement settlement : settlementsById.values()) {
                settlement.advanceEconomyForDays(skippedDays);
            }
        }

        long firstRecordedDay = lastProcessedDay + skippedDays + 1L;
        for (long day = firstRecordedDay; day <= currentDay; day++) {
            for (Settlement settlement : settlementsById.values()) {
                settlement.advanceEconomyForDay();
                settlement.recordHistory(day);
            }
        }

        lastProcessedDay = currentDay;
        setDirty();
        return daysElapsed;
    }

    private Settlement findNearestSettlement(ResourceKey<Level> dimension, BlockPos pos, int maxDistance) {
        Settlement nearest = null;
        double nearestDistance = (double) maxDistance * maxDistance;

        for (Settlement settlement : settlementsById.values()) {
            if (!settlement.isInDimension(dimension)) {
                continue;
            }

            double distance = settlement.distanceToSqr(pos);
            if (distance <= nearestDistance) {
                nearest = settlement;
                nearestDistance = distance;
            }
        }

        return nearest;
    }

    @Override
    public CompoundTag save(CompoundTag tag) {
        ListTag settlements = new ListTag();
        for (Settlement settlement : settlementsById.values()) {
            settlements.add(settlement.save());
        }
        tag.put(TAG_SETTLEMENTS, settlements);

        ListTag depots = new ListTag();
        for (DepotLink depotLink : depotLinksByLocation.values()) {
            depots.add(depotLink.save());
        }
        tag.put(TAG_DEPOTS, depots);
        tag.putLong(TAG_LAST_PROCESSED_DAY, lastProcessedDay);
        return tag;
    }

    private record DepotLink(String dimension, long pos, UUID settlementId) {
        private static final String TAG_DIMENSION = "Dimension";
        private static final String TAG_POS = "Pos";
        private static final String TAG_SETTLEMENT_ID = "SettlementId";

        static DepotLink load(CompoundTag tag) {
            return new DepotLink(
                    tag.getString(TAG_DIMENSION),
                    tag.getLong(TAG_POS),
                    tag.getUUID(TAG_SETTLEMENT_ID)
            );
        }

        CompoundTag save() {
            CompoundTag tag = new CompoundTag();
            tag.putString(TAG_DIMENSION, dimension);
            tag.putLong(TAG_POS, pos);
            tag.putUUID(TAG_SETTLEMENT_ID, settlementId);
            return tag;
        }

        String locationKey() {
            return Settlement.locationKey(dimension, pos);
        }
    }
}
