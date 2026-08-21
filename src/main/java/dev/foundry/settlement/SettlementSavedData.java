package dev.foundry.settlement;

import dev.foundry.block.IndustryBlock;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;

public final class SettlementSavedData extends SavedData {
    private static final String DATA_NAME = "foundry_settlements";
    private static final String TAG_SETTLEMENTS = "Settlements";
    private static final String TAG_DEPOTS = "Depots";
    private static final String TAG_INDUSTRIES = "Industries";
    private static final String TAG_LAST_PROCESSED_DAY = "LastProcessedDay";
    private static final int DEFAULT_DEPOT_LINK_RANGE = 128;
    private static final int DEFAULT_INDUSTRY_LINK_RANGE = 128;
    private static final long TICKS_PER_DAY = 24_000L;

    private final Map<UUID, Settlement> settlementsById = new HashMap<>();
    private final Map<String, UUID> settlementIdsByLocation = new HashMap<>();
    private final Map<String, DepotLink> depotLinksByLocation = new HashMap<>();
    private final Map<String, IndustrySite> industrySitesByLocation = new HashMap<>();
    private final Map<UUID, String> pendingIndustryLinksByPlayer = new HashMap<>();
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

        ListTag industries = tag.getList(TAG_INDUSTRIES, Tag.TAG_COMPOUND);
        for (int i = 0; i < industries.size(); i++) {
            IndustrySite industrySite = IndustrySite.load(industries.getCompound(i));
            if (industrySite != null && data.settlementsById.containsKey(industrySite.settlementId())) {
                data.industrySitesByLocation.put(industrySite.locationKey(), industrySite);
            }
        }
        data.recalculateAllIndustryJobs();

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
            settlement.ensureProductionDay(lastProcessedDay);
            settlement.ensureTradeDay(lastProcessedDay);
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

        Iterator<IndustrySite> industryIterator = industrySitesByLocation.values().iterator();
        while (industryIterator.hasNext()) {
            if (industryIterator.next().settlementId().equals(settlementId)) {
                industryIterator.remove();
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
        boolean changed = depotLinksByLocation.remove(locationKey) != null;
        String dimensionName = dimension.location().toString();

        for (Map.Entry<String, IndustrySite> entry : industrySitesByLocation.entrySet()) {
            IndustrySite site = entry.getValue();
            if (site.isLinkedToDepot(dimensionName, depotPos.asLong())) {
                entry.setValue(site.withoutDepotLink());
                changed = true;
            }
        }

        if (changed) {
            setDirty();
        }
    }

    public Settlement registerIndustry(ResourceKey<Level> dimension, BlockPos industryPos, IndustryType type) {
        String locationKey = Settlement.locationKey(dimension, industryPos);
        IndustrySite existingSite = industrySitesByLocation.get(locationKey);
        if (existingSite != null && existingSite.type() == type) {
            Settlement existingSettlement = settlementsById.get(existingSite.settlementId());
            if (existingSettlement != null) {
                return existingSettlement;
            }
        }

        UUID oldSettlementId = existingSite == null ? null : existingSite.settlementId();
        Settlement nearest = findNearestSettlement(dimension, industryPos, DEFAULT_INDUSTRY_LINK_RANGE);
        if (nearest == null) {
            if (existingSite != null) {
                industrySitesByLocation.remove(locationKey);
                recalculateIndustryJobs(oldSettlementId);
                setDirty();
            }
            return null;
        }

        IndustrySite industrySite = existingSite != null && existingSite.type() == type
                ? existingSite.withSettlement(nearest.getId())
                : IndustrySite.create(dimension.location().toString(), industryPos.asLong(), nearest.getId(), type);
        industrySitesByLocation.put(locationKey, industrySite);
        if (oldSettlementId != null && !oldSettlementId.equals(nearest.getId())) {
            recalculateIndustryJobs(oldSettlementId);
        }
        recalculateIndustryJobs(nearest.getId());
        setDirty();
        return nearest;
    }

    public Settlement getSettlementForIndustry(ResourceKey<Level> dimension, BlockPos industryPos) {
        String locationKey = Settlement.locationKey(dimension, industryPos);
        IndustrySite industrySite = industrySitesByLocation.get(locationKey);
        if (industrySite == null) {
            return null;
        }

        Settlement settlement = settlementsById.get(industrySite.settlementId());
        if (settlement == null) {
            industrySitesByLocation.remove(locationKey);
            setDirty();
        }
        return settlement;
    }

    public IndustryLinkResult beginIndustryDepotLink(UUID playerId, ResourceKey<Level> dimension,
                                                      BlockPos industryPos, IndustryType type) {
        Settlement settlement = registerIndustry(dimension, industryPos, type);
        if (settlement == null) {
            pendingIndustryLinksByPlayer.remove(playerId);
            return new IndustryLinkResult(
                    true,
                    false,
                    type.displayName() + " // LINK FAILED // No Town Hall within 128 blocks"
            );
        }

        pendingIndustryLinksByPlayer.put(playerId, Settlement.locationKey(dimension, industryPos));
        return new IndustryLinkResult(
                true,
                true,
                type.displayName() + " // LINE SELECTED // Sneak-right-click a Freight Depot"
        );
    }

    public IndustryLinkResult completeIndustryDepotLink(UUID playerId, ResourceKey<Level> dimension,
                                                         BlockPos depotPos) {
        String selectedKey = pendingIndustryLinksByPlayer.remove(playerId);
        if (selectedKey == null) {
            return IndustryLinkResult.notHandled();
        }

        IndustrySite selectedSite = industrySitesByLocation.get(selectedKey);
        if (selectedSite == null) {
            return new IndustryLinkResult(true, false, "Industry link expired; select the industry again");
        }

        Settlement depotSettlement = getSettlementForDepot(dimension, depotPos);
        if (depotSettlement == null) {
            depotSettlement = linkDepot(dimension, depotPos);
        }
        if (depotSettlement == null) {
            return new IndustryLinkResult(true, false, "Freight Depot // LINK FAILED // No Town Hall within 128 blocks");
        }

        String depotDimension = dimension.location().toString();
        if (!selectedSite.dimension().equals(depotDimension)
                || !selectedSite.settlementId().equals(depotSettlement.getId())) {
            return new IndustryLinkResult(
                    true,
                    false,
                    selectedSite.type().displayName() + " // LINK FAILED // Depot belongs to another settlement"
            );
        }

        long depotPosLong = depotPos.asLong();
        for (Map.Entry<String, IndustrySite> entry : industrySitesByLocation.entrySet()) {
            IndustrySite site = entry.getValue();
            if (!entry.getKey().equals(selectedKey)
                    && site.type() == selectedSite.type()
                    && site.isLinkedToDepot(depotDimension, depotPosLong)) {
                entry.setValue(site.withoutDepotLink());
            }
        }

        selectedSite = selectedSite.withDepotLink(depotDimension, depotPosLong);
        industrySitesByLocation.put(selectedKey, selectedSite);
        setDirty();
        return new IndustryLinkResult(
                true,
                true,
                selectedSite.type().displayName() + " // PRODUCTION LINE LINKED // Freight Depot will meter automated output"
        );
    }

    public IndustryTelemetry getIndustryTelemetry(ResourceKey<Level> dimension, BlockPos industryPos, long currentDay) {
        IndustrySite site = industrySitesByLocation.get(Settlement.locationKey(dimension, industryPos));
        if (site == null) {
            return IndustryTelemetry.EMPTY;
        }

        Settlement settlement = settlementsById.get(site.settlementId());
        int today = site.outputDay() == currentDay ? site.outputToday() : 0;
        int average = 0;
        if (settlement != null) {
            average = site.type() == IndustryType.BAKERY
                    ? settlement.getFoodOutputAverage(7)
                    : settlement.getConstructionOutputAverage(7);
        }
        return new IndustryTelemetry(site.depotLinked(), today, site.lifetimeOutput(), average);
    }

    public boolean recordIndustryOutputForDepot(ResourceKey<Level> dimension, BlockPos depotPos,
                                                 IndustryType type, int amount, long day) {
        if (type == null || amount <= 0) {
            return false;
        }

        Settlement settlement = getSettlementForDepot(dimension, depotPos);
        if (settlement == null) {
            return false;
        }

        String depotDimension = dimension.location().toString();
        long depotPosLong = depotPos.asLong();
        for (Map.Entry<String, IndustrySite> entry : industrySitesByLocation.entrySet()) {
            IndustrySite site = entry.getValue();
            if (!site.settlementId().equals(settlement.getId())
                    || site.type() != type
                    || !site.isLinkedToDepot(depotDimension, depotPosLong)) {
                continue;
            }

            entry.setValue(site.recordOutput(day, amount));
            settlement.recordIndustryOutput(day, type, amount);
            setDirty();
            return true;
        }
        return false;
    }

    public void recordDomesticExport(Settlement origin, IndustryType type, int amount, long day) {
        if (origin == null || type == null || amount <= 0) {
            return;
        }
        origin.recordTradeExport(day, type, amount);
        setDirty();
    }

    public boolean recordDomesticImport(UUID originSettlementId, Settlement destination,
                                        IndustryType type, int amount, long day) {
        if (originSettlementId == null || destination == null || type == null || amount <= 0) {
            return false;
        }
        if (originSettlementId.equals(destination.getId())) {
            return false;
        }

        destination.recordTradeImport(day, type, amount);
        setDirty();
        return true;
    }

    public int getIndustryStaffingSignal(ResourceKey<Level> dimension, BlockPos industryPos) {
        IndustrySite site = industrySitesByLocation.get(Settlement.locationKey(dimension, industryPos));
        if (site == null) {
            return 0;
        }
        Settlement settlement = settlementsById.get(site.settlementId());
        return settlement == null ? 0 : settlement.getIndustryStaffingSignal(site.type());
    }

    public void refreshIndustrySignals(MinecraftServer server) {
        for (IndustrySite site : industrySitesByLocation.values()) {
            Settlement settlement = settlementsById.get(site.settlementId());
            if (settlement == null) {
                continue;
            }

            int staffing = settlement.getIndustryStaffingSignal(site.type());
            BlockPos pos = BlockPos.of(site.pos());
            for (ServerLevel level : server.getAllLevels()) {
                if (!level.dimension().location().toString().equals(site.dimension()) || !level.hasChunkAt(pos)) {
                    continue;
                }

                BlockState state = level.getBlockState(pos);
                if (state.getBlock() instanceof IndustryBlock industryBlock) {
                    industryBlock.syncStaffing(level, pos, staffing);
                }
                break;
            }
        }
    }

    public void removeIndustry(ResourceKey<Level> dimension, BlockPos industryPos) {
        String locationKey = Settlement.locationKey(dimension, industryPos);
        IndustrySite removed = industrySitesByLocation.remove(locationKey);
        if (removed != null) {
            pendingIndustryLinksByPlayer.values().removeIf(locationKey::equals);
            recalculateIndustryJobs(removed.settlementId());
            setDirty();
        }
    }

    private void recalculateAllIndustryJobs() {
        for (UUID settlementId : settlementsById.keySet()) {
            recalculateIndustryJobs(settlementId);
        }
    }

    private void recalculateIndustryJobs(UUID settlementId) {
        if (settlementId == null) {
            return;
        }
        Settlement settlement = settlementsById.get(settlementId);
        if (settlement == null) {
            return;
        }

        int foodJobs = 0;
        int constructionJobs = 0;
        for (IndustrySite site : industrySitesByLocation.values()) {
            if (!site.settlementId().equals(settlementId)) {
                continue;
            }
            if (site.type() == IndustryType.BAKERY) {
                foodJobs += site.type().jobs();
            } else if (site.type() == IndustryType.BRICKWORKS) {
                constructionJobs += site.type().jobs();
            }
        }
        settlement.setIndustryJobCapacity(foodJobs, constructionJobs);
    }

    public long advanceEconomy(long dayTime) {
        long currentDay = Math.floorDiv(dayTime, TICKS_PER_DAY);
        if (lastProcessedDay < 0L) {
            lastProcessedDay = currentDay;
            for (Settlement settlement : settlementsById.values()) {
                settlement.recordHistory(currentDay);
                settlement.ensureProductionDay(currentDay);
                settlement.ensureTradeDay(currentDay);
            }
            setDirty();
            return 0L;
        }

        if (currentDay < lastProcessedDay) {
            lastProcessedDay = currentDay;
            for (Settlement settlement : settlementsById.values()) {
                settlement.resetHistory(currentDay);
                settlement.resetProductionHistory(currentDay);
                settlement.resetTradeHistory(currentDay);
            }
            setDirty();
            return 0L;
        }

        if (currentDay == lastProcessedDay) {
            for (Settlement settlement : settlementsById.values()) {
                settlement.ensureProductionDay(currentDay);
                settlement.ensureTradeDay(currentDay);
            }
            return 0L;
        }

        long daysElapsed = currentDay - lastProcessedDay;
        long skippedDays = Math.max(0L, daysElapsed - Settlement.HISTORY_LIMIT);
        if (skippedDays > 0L) {
            long skippedToDay = lastProcessedDay + skippedDays;
            for (Settlement settlement : settlementsById.values()) {
                settlement.advanceEconomyForDays(skippedDays);
                settlement.ensureProductionDay(skippedToDay);
                settlement.ensureTradeDay(skippedToDay);
            }
        }

        long firstRecordedDay = lastProcessedDay + skippedDays + 1L;
        for (long day = firstRecordedDay; day <= currentDay; day++) {
            for (Settlement settlement : settlementsById.values()) {
                settlement.advanceEconomyForDay();
                settlement.recordHistory(day);
                settlement.ensureProductionDay(day);
                settlement.ensureTradeDay(day);
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

        ListTag industries = new ListTag();
        for (IndustrySite industrySite : industrySitesByLocation.values()) {
            industries.add(industrySite.save());
        }
        tag.put(TAG_INDUSTRIES, industries);

        tag.putLong(TAG_LAST_PROCESSED_DAY, lastProcessedDay);
        return tag;
    }

    public record IndustryLinkResult(boolean handled, boolean success, String message) {
        private static IndustryLinkResult notHandled() {
            return new IndustryLinkResult(false, false, "");
        }
    }

    public record IndustryTelemetry(boolean depotLinked, int outputToday, long lifetimeOutput, int sectorAverage7d) {
        private static final IndustryTelemetry EMPTY = new IndustryTelemetry(false, 0, 0L, 0);
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

    private record IndustrySite(
            String dimension,
            long pos,
            UUID settlementId,
            IndustryType type,
            boolean depotLinked,
            String depotDimension,
            long depotPos,
            long outputDay,
            int outputToday,
            long lifetimeOutput
    ) {
        private static final String TAG_DIMENSION = "Dimension";
        private static final String TAG_POS = "Pos";
        private static final String TAG_SETTLEMENT_ID = "SettlementId";
        private static final String TAG_TYPE = "Type";
        private static final String TAG_DEPOT_LINKED = "DepotLinked";
        private static final String TAG_DEPOT_DIMENSION = "DepotDimension";
        private static final String TAG_DEPOT_POS = "DepotPos";
        private static final String TAG_OUTPUT_DAY = "OutputDay";
        private static final String TAG_OUTPUT_TODAY = "OutputToday";
        private static final String TAG_LIFETIME_OUTPUT = "LifetimeOutput";

        static IndustrySite create(String dimension, long pos, UUID settlementId, IndustryType type) {
            return new IndustrySite(dimension, pos, settlementId, type, false, "", 0L, -1L, 0, 0L);
        }

        static IndustrySite load(CompoundTag tag) {
            IndustryType type = IndustryType.fromSerializedName(tag.getString(TAG_TYPE));
            if (type == null) {
                return null;
            }

            boolean depotLinked = tag.getBoolean(TAG_DEPOT_LINKED);
            long outputDay = tag.contains(TAG_OUTPUT_DAY, Tag.TAG_LONG) ? tag.getLong(TAG_OUTPUT_DAY) : -1L;
            long lifetimeOutput = tag.contains(TAG_LIFETIME_OUTPUT, Tag.TAG_LONG)
                    ? Math.max(0L, tag.getLong(TAG_LIFETIME_OUTPUT))
                    : 0L;
            return new IndustrySite(
                    tag.getString(TAG_DIMENSION),
                    tag.getLong(TAG_POS),
                    tag.getUUID(TAG_SETTLEMENT_ID),
                    type,
                    depotLinked,
                    depotLinked ? tag.getString(TAG_DEPOT_DIMENSION) : "",
                    depotLinked ? tag.getLong(TAG_DEPOT_POS) : 0L,
                    outputDay,
                    Math.max(0, tag.getInt(TAG_OUTPUT_TODAY)),
                    lifetimeOutput
            );
        }

        CompoundTag save() {
            CompoundTag tag = new CompoundTag();
            tag.putString(TAG_DIMENSION, dimension);
            tag.putLong(TAG_POS, pos);
            tag.putUUID(TAG_SETTLEMENT_ID, settlementId);
            tag.putString(TAG_TYPE, type.serializedName());
            tag.putBoolean(TAG_DEPOT_LINKED, depotLinked);
            if (depotLinked) {
                tag.putString(TAG_DEPOT_DIMENSION, depotDimension);
                tag.putLong(TAG_DEPOT_POS, depotPos);
            }
            tag.putLong(TAG_OUTPUT_DAY, outputDay);
            tag.putInt(TAG_OUTPUT_TODAY, outputToday);
            tag.putLong(TAG_LIFETIME_OUTPUT, lifetimeOutput);
            return tag;
        }

        String locationKey() {
            return Settlement.locationKey(dimension, pos);
        }

        boolean isLinkedToDepot(String targetDimension, long targetPos) {
            return depotLinked && depotDimension.equals(targetDimension) && depotPos == targetPos;
        }

        IndustrySite withSettlement(UUID newSettlementId) {
            return new IndustrySite(
                    dimension,
                    pos,
                    newSettlementId,
                    type,
                    depotLinked,
                    depotDimension,
                    depotPos,
                    outputDay,
                    outputToday,
                    lifetimeOutput
            );
        }

        IndustrySite withDepotLink(String newDepotDimension, long newDepotPos) {
            return new IndustrySite(
                    dimension,
                    pos,
                    settlementId,
                    type,
                    true,
                    newDepotDimension,
                    newDepotPos,
                    outputDay,
                    outputToday,
                    lifetimeOutput
            );
        }

        IndustrySite withoutDepotLink() {
            return new IndustrySite(
                    dimension,
                    pos,
                    settlementId,
                    type,
                    false,
                    "",
                    0L,
                    outputDay,
                    outputToday,
                    lifetimeOutput
            );
        }

        IndustrySite recordOutput(long day, int amount) {
            int newToday = outputDay == day ? outputToday + amount : amount;
            return new IndustrySite(
                    dimension,
                    pos,
                    settlementId,
                    type,
                    depotLinked,
                    depotDimension,
                    depotPos,
                    day,
                    newToday,
                    lifetimeOutput + amount
            );
        }
    }
}
