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

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class SettlementSavedData extends SavedData {
    private static final String DATA_NAME = "foundry_settlements";
    private static final String TAG_SETTLEMENTS = "Settlements";
    private static final String TAG_FINANCES = "Finances";
    private static final String TAG_DEPOTS = "Depots";
    private static final String TAG_INDUSTRIES = "Industries";
    private static final String TAG_WAREHOUSES = "Warehouses";
    private static final String TAG_LAST_PROCESSED_DAY = "LastProcessedDay";
    public static final int DEFAULT_SETTLEMENT_RADIUS = SettlementTier.HAMLET.claimRadius();
    public static final long WAREHOUSE_KORA_COST = 1_200L;
    public static final int WAREHOUSE_BRICK_COST = 24;
    public static final int WAREHOUSE_BREAD_CAPACITY = 128;
    public static final int WAREHOUSE_BRICK_CAPACITY = 64;
    private static final long TICKS_PER_DAY = 24_000L;

    private final Map<UUID, Settlement> settlementsById = new HashMap<>();
    private final Map<UUID, SettlementFinance> financesBySettlementId = new HashMap<>();
    private final Map<String, UUID> settlementIdsByLocation = new HashMap<>();
    private final Map<String, DepotLink> depotLinksByLocation = new HashMap<>();
    private final Map<String, IndustrySite> industrySitesByLocation = new HashMap<>();
    private final Map<String, WarehouseSite> warehouseSitesByLocation = new HashMap<>();
    private final Map<UUID, String> pendingIndustryLinksByPlayer = new HashMap<>();
    private long lastProcessedDay = -1L;

    public static SettlementSavedData get(ServerLevel level) {
        ServerLevel overworld = level.getServer().overworld();
        return overworld.getDataStorage().computeIfAbsent(SettlementSavedData::load, SettlementSavedData::new, DATA_NAME);
    }

    public static SettlementSavedData load(CompoundTag tag) {
        SettlementSavedData data = new SettlementSavedData();
        ListTag settlements = tag.getList(TAG_SETTLEMENTS, Tag.TAG_COMPOUND);
        for (int i = 0; i < settlements.size(); i++) {
            Settlement settlement = Settlement.load(settlements.getCompound(i));
            data.settlementsById.put(settlement.getId(), settlement);
            data.settlementIdsByLocation.put(settlement.locationKey(), settlement.getId());
        }

        ListTag finances = tag.getList(TAG_FINANCES, Tag.TAG_COMPOUND);
        for (int i = 0; i < finances.size(); i++) {
            SettlementFinance finance = SettlementFinance.load(finances.getCompound(i));
            if (data.settlementsById.containsKey(finance.getSettlementId())) {
                data.financesBySettlementId.put(finance.getSettlementId(), finance);
            }
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
            IndustrySite site = IndustrySite.load(industries.getCompound(i));
            if (site != null && data.settlementsById.containsKey(site.settlementId())) {
                data.industrySitesByLocation.put(site.locationKey(), site);
            }
        }

        ListTag warehouses = tag.getList(TAG_WAREHOUSES, Tag.TAG_COMPOUND);
        for (int i = 0; i < warehouses.size(); i++) {
            WarehouseSite site = WarehouseSite.load(warehouses.getCompound(i));
            if (site != null && data.settlementsById.containsKey(site.settlementId())) {
                data.warehouseSitesByLocation.put(site.locationKey(), site);
            }
        }

        data.recalculateAllIndustryJobs();
        data.recalculateAllStorageBonuses();
        if (tag.contains(TAG_LAST_PROCESSED_DAY, Tag.TAG_LONG)) data.lastProcessedDay = tag.getLong(TAG_LAST_PROCESSED_DAY);

        boolean migratedFinance = false;
        for (Settlement settlement : data.settlementsById.values()) {
            SettlementFinance finance = data.financesBySettlementId.get(settlement.getId());
            if (finance == null) {
                finance = SettlementFinance.create(settlement);
                data.financesBySettlementId.put(settlement.getId(), finance);
                migratedFinance = true;
            }
            if (data.lastProcessedDay >= 0L) finance.ensureDay(data.lastProcessedDay);
        }
        if (migratedFinance) data.setDirty();
        return data;
    }

    public Settlement getOrCreate(ResourceKey<Level> dimension, BlockPos pos) {
        String locationKey = Settlement.locationKey(dimension, pos);
        UUID existingId = settlementIdsByLocation.get(locationKey);
        if (existingId != null) {
            Settlement existing = settlementsById.get(existingId);
            if (existing != null) {
                ensureFinance(existing);
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
        SettlementFinance finance = SettlementFinance.create(settlement);
        if (lastProcessedDay >= 0L) finance.ensureDay(lastProcessedDay);
        financesBySettlementId.put(settlement.getId(), finance);
        setDirty();
        return settlement;
    }

    public Settlement getSettlement(UUID settlementId) { return settlementId == null ? null : settlementsById.get(settlementId); }
    public Collection<Settlement> getSettlements() { return List.copyOf(settlementsById.values()); }
    public Settlement findSettlementForPosition(ResourceKey<Level> dimension, BlockPos pos) { return findNearestSettlement(dimension, pos); }

    public SettlementFinance getFinance(Settlement settlement) { return settlement == null ? null : ensureFinance(settlement); }
    public SettlementFinance getFinance(UUID settlementId) {
        Settlement settlement = getSettlement(settlementId);
        return settlement == null ? null : ensureFinance(settlement);
    }

    private SettlementFinance ensureFinance(Settlement settlement) {
        SettlementFinance finance = financesBySettlementId.get(settlement.getId());
        if (finance != null) return finance;
        finance = SettlementFinance.create(settlement);
        if (lastProcessedDay >= 0L) finance.ensureDay(lastProcessedDay);
        financesBySettlementId.put(settlement.getId(), finance);
        setDirty();
        return finance;
    }

    public int getCommodityUnitPrice(Settlement settlement, IndustryType type) { return SettlementFinance.quoteUnitPrice(settlement, type); }

    public int getAffordableDomesticImportAmount(UUID originSettlementId, Settlement destination,
                                                  IndustryType type, int unitPrice, int requestedAmount) {
        if (originSettlementId == null || destination == null || type == null || requestedAmount <= 0) return 0;
        if (originSettlementId.equals(destination.getId())) return requestedAmount;
        Settlement origin = settlementsById.get(originSettlementId);
        if (origin == null) return 0;
        int effectiveUnitPrice = unitPrice > 0 ? unitPrice : getCommodityUnitPrice(origin, type);
        long affordable = ensureFinance(destination).getLocalLiquidity() / Math.max(1, effectiveUnitPrice);
        return (int) Math.min((long) requestedAmount, affordable);
    }

    public SettlementTier getSettlementTier(Settlement settlement) { return SettlementTier.forSettlement(settlement); }
    public SettlementTier getSettlementTier(UUID settlementId) { return getSettlementTier(getSettlement(settlementId)); }

    /** Reserved for a future explicit abandon/dissolve settlement action. */
    public void remove(ResourceKey<Level> dimension, BlockPos pos) {
        String locationKey = Settlement.locationKey(dimension, pos);
        UUID settlementId = settlementIdsByLocation.remove(locationKey);
        if (settlementId == null) return;
        boolean changed = settlementsById.remove(settlementId) != null;
        changed |= financesBySettlementId.remove(settlementId) != null;
        changed |= depotLinksByLocation.values().removeIf(link -> link.settlementId().equals(settlementId));
        changed |= industrySitesByLocation.values().removeIf(site -> site.settlementId().equals(settlementId));
        changed |= warehouseSitesByLocation.values().removeIf(site -> site.settlementId().equals(settlementId));
        if (changed) setDirty();
    }

    public Settlement linkDepot(ResourceKey<Level> dimension, BlockPos depotPos) {
        Settlement existing = getSettlementForDepot(dimension, depotPos);
        if (existing != null) return existing;
        Settlement nearest = findNearestSettlement(dimension, depotPos);
        if (nearest == null) return null;
        DepotLink link = new DepotLink(dimension.location().toString(), depotPos.asLong(), nearest.getId());
        depotLinksByLocation.put(link.locationKey(), link);
        setDirty();
        return nearest;
    }

    public Settlement getSettlementForDepot(ResourceKey<Level> dimension, BlockPos depotPos) {
        String key = Settlement.locationKey(dimension, depotPos);
        DepotLink link = depotLinksByLocation.get(key);
        if (link == null) return null;
        Settlement settlement = settlementsById.get(link.settlementId());
        if (settlement == null) {
            depotLinksByLocation.remove(key);
            setDirty();
        }
        return settlement;
    }

    public void removeDepot(ResourceKey<Level> dimension, BlockPos depotPos) {
        String key = Settlement.locationKey(dimension, depotPos);
        boolean changed = depotLinksByLocation.remove(key) != null;
        String dimensionName = dimension.location().toString();
        for (Map.Entry<String, IndustrySite> entry : industrySitesByLocation.entrySet()) {
            IndustrySite site = entry.getValue();
            if (site.isLinkedToDepot(dimensionName, depotPos.asLong())) {
                entry.setValue(site.withoutDepotLink());
                changed = true;
            }
        }
        if (changed) setDirty();
    }

    public WarehouseStatus registerWarehouse(ResourceKey<Level> dimension, BlockPos pos) {
        String key = Settlement.locationKey(dimension, pos);
        WarehouseSite existing = warehouseSitesByLocation.get(key);
        if (existing != null) return warehouseStatus(existing);
        Settlement settlement = findNearestSettlement(dimension, pos);
        if (settlement == null) return WarehouseStatus.unlinked();
        WarehouseSite site = WarehouseSite.create(dimension.location().toString(), pos.asLong(), settlement.getId());
        warehouseSitesByLocation.put(key, site);
        recalculateStorageBonuses(settlement.getId());
        setDirty();
        return warehouseStatus(site);
    }

    public WarehouseStatus getWarehouseStatus(ResourceKey<Level> dimension, BlockPos pos) {
        WarehouseSite site = warehouseSitesByLocation.get(Settlement.locationKey(dimension, pos));
        return site == null ? WarehouseStatus.unlinked() : warehouseStatus(site);
    }

    public WarehouseStatus commissionWarehouse(ResourceKey<Level> dimension, BlockPos pos, long currentDay) {
        String key = Settlement.locationKey(dimension, pos);
        WarehouseSite site = warehouseSitesByLocation.get(key);
        if (site == null) {
            registerWarehouse(dimension, pos);
            site = warehouseSitesByLocation.get(key);
        }
        if (site == null) return WarehouseStatus.unlinked();
        if (site.commissioned()) return warehouseStatus(site);
        Settlement settlement = settlementsById.get(site.settlementId());
        if (settlement == null) return WarehouseStatus.unlinked();
        SettlementFinance finance = ensureFinance(settlement);
        if (finance.getTreasuryBalance() < WAREHOUSE_KORA_COST) return warehouseStatus(site).withFailure("TREASURY SHORTFALL");
        if (settlement.getBuildingMaterialsSupplied() < WAREHOUSE_BRICK_COST) return warehouseStatus(site).withFailure("NEEDS BRICKS");

        int removed = settlement.withdrawBuildingMaterials(WAREHOUSE_BRICK_COST);
        if (removed != WAREHOUSE_BRICK_COST) return warehouseStatus(site).withFailure("NEEDS BRICKS");
        if (!finance.spendTreasuryIntoLocalEconomy(currentDay, WAREHOUSE_KORA_COST)) {
            settlement.deliverBuildingMaterials(removed);
            return warehouseStatus(site).withFailure("TREASURY SHORTFALL");
        }

        site = site.commission();
        warehouseSitesByLocation.put(key, site);
        recalculateStorageBonuses(settlement.getId());
        setDirty();
        return warehouseStatus(site);
    }

    public void removeWarehouse(ResourceKey<Level> dimension, BlockPos pos) {
        WarehouseSite removed = warehouseSitesByLocation.remove(Settlement.locationKey(dimension, pos));
        if (removed != null) {
            recalculateStorageBonuses(removed.settlementId());
            setDirty();
        }
    }

    public int getCommissionedWarehouseCount(UUID settlementId) {
        int count = 0;
        for (WarehouseSite site : warehouseSitesByLocation.values()) {
            if (site.commissioned() && site.settlementId().equals(settlementId)) count++;
        }
        return count;
    }

    private WarehouseStatus warehouseStatus(WarehouseSite site) {
        Settlement settlement = settlementsById.get(site.settlementId());
        if (settlement == null) return WarehouseStatus.unlinked();
        return new WarehouseStatus(true, site.commissioned(), "", settlement,
                getCommissionedWarehouseCount(settlement.getId()),
                settlement.getBreadReserveCapacity(), settlement.getBuildingMaterialsReserveCapacity());
    }

    private void recalculateAllStorageBonuses() {
        for (Settlement settlement : settlementsById.values()) recalculateStorageBonuses(settlement.getId());
    }

    private void recalculateStorageBonuses(UUID settlementId) {
        Settlement settlement = settlementsById.get(settlementId);
        if (settlement == null) return;
        int count = getCommissionedWarehouseCount(settlementId);
        long bread = (long) count * WAREHOUSE_BREAD_CAPACITY;
        long bricks = (long) count * WAREHOUSE_BRICK_CAPACITY;
        settlement.setStorageCapacityBonus((int) Math.min(Integer.MAX_VALUE, bread),
                (int) Math.min(Integer.MAX_VALUE, bricks));
    }

    public Settlement registerIndustry(ResourceKey<Level> dimension, BlockPos industryPos, IndustryType type) {
        String key = Settlement.locationKey(dimension, industryPos);
        IndustrySite existing = industrySitesByLocation.get(key);
        if (existing != null && existing.type() == type) {
            Settlement current = settlementsById.get(existing.settlementId());
            if (current != null) return current;
        }
        UUID oldSettlementId = existing == null ? null : existing.settlementId();
        Settlement nearest = findNearestSettlement(dimension, industryPos);
        if (nearest == null) {
            if (existing != null) {
                industrySitesByLocation.remove(key);
                recalculateIndustryJobs(oldSettlementId);
                setDirty();
            }
            return null;
        }
        IndustrySite site = existing != null && existing.type() == type
                ? existing.withSettlement(nearest.getId())
                : IndustrySite.create(dimension.location().toString(), industryPos.asLong(), nearest.getId(), type);
        industrySitesByLocation.put(key, site);
        if (oldSettlementId != null && !oldSettlementId.equals(nearest.getId())) recalculateIndustryJobs(oldSettlementId);
        recalculateIndustryJobs(nearest.getId());
        setDirty();
        return nearest;
    }

    public Settlement getSettlementForIndustry(ResourceKey<Level> dimension, BlockPos industryPos) {
        String key = Settlement.locationKey(dimension, industryPos);
        IndustrySite site = industrySitesByLocation.get(key);
        if (site == null) return null;
        Settlement settlement = settlementsById.get(site.settlementId());
        if (settlement == null) {
            industrySitesByLocation.remove(key);
            setDirty();
        }
        return settlement;
    }

    public IndustryLinkResult beginIndustryDepotLink(UUID playerId, ResourceKey<Level> dimension,
                                                      BlockPos industryPos, IndustryType type) {
        Settlement settlement = registerIndustry(dimension, industryPos, type);
        if (settlement == null) {
            pendingIndustryLinksByPlayer.remove(playerId);
            return new IndustryLinkResult(true, false, type.displayName() + " // LINK FAILED // No settlement territory covers this block");
        }
        pendingIndustryLinksByPlayer.put(playerId, Settlement.locationKey(dimension, industryPos));
        return new IndustryLinkResult(true, true, type.displayName() + " // LINE SELECTED // Sneak-right-click a Freight Depot");
    }

    public IndustryLinkResult completeIndustryDepotLink(UUID playerId, ResourceKey<Level> dimension, BlockPos depotPos) {
        String selectedKey = pendingIndustryLinksByPlayer.remove(playerId);
        if (selectedKey == null) return IndustryLinkResult.notHandled();
        IndustrySite selected = industrySitesByLocation.get(selectedKey);
        if (selected == null) return new IndustryLinkResult(true, false, "Industry link expired; select the industry again");
        Settlement depotSettlement = getSettlementForDepot(dimension, depotPos);
        if (depotSettlement == null) depotSettlement = linkDepot(dimension, depotPos);
        if (depotSettlement == null) return new IndustryLinkResult(true, false, "Freight Depot // LINK FAILED // No settlement territory covers this block");
        String depotDimension = dimension.location().toString();
        if (!selected.dimension().equals(depotDimension) || !selected.settlementId().equals(depotSettlement.getId())) {
            return new IndustryLinkResult(true, false, selected.type().displayName() + " // LINK FAILED // Depot belongs to another settlement");
        }
        long depotPosLong = depotPos.asLong();
        for (Map.Entry<String, IndustrySite> entry : industrySitesByLocation.entrySet()) {
            IndustrySite site = entry.getValue();
            if (!entry.getKey().equals(selectedKey) && site.type() == selected.type()
                    && site.isLinkedToDepot(depotDimension, depotPosLong)) entry.setValue(site.withoutDepotLink());
        }
        industrySitesByLocation.put(selectedKey, selected.withDepotLink(depotDimension, depotPosLong));
        setDirty();
        return new IndustryLinkResult(true, true, selected.type().displayName() + " // PRODUCTION LINE LINKED // Freight Depot will meter automated output");
    }

    public IndustryTelemetry getIndustryTelemetry(ResourceKey<Level> dimension, BlockPos industryPos, long currentDay) {
        IndustrySite site = industrySitesByLocation.get(Settlement.locationKey(dimension, industryPos));
        if (site == null) return IndustryTelemetry.EMPTY;
        Settlement settlement = settlementsById.get(site.settlementId());
        int today = site.outputDay() == currentDay ? site.outputToday() : 0;
        int average = settlement == null ? 0 : (site.type() == IndustryType.BAKERY
                ? settlement.getFoodOutputAverage(7) : settlement.getConstructionOutputAverage(7));
        return new IndustryTelemetry(site.depotLinked(), today, site.lifetimeOutput(), average);
    }

    public boolean recordIndustryOutputForDepot(ResourceKey<Level> dimension, BlockPos depotPos,
                                                 IndustryType type, int amount, long day) {
        if (type == null || amount <= 0) return false;
        Settlement settlement = getSettlementForDepot(dimension, depotPos);
        if (settlement == null) return false;
        String depotDimension = dimension.location().toString();
        long depotPosLong = depotPos.asLong();
        for (Map.Entry<String, IndustrySite> entry : industrySitesByLocation.entrySet()) {
            IndustrySite site = entry.getValue();
            if (!site.settlementId().equals(settlement.getId()) || site.type() != type
                    || !site.isLinkedToDepot(depotDimension, depotPosLong)) continue;
            entry.setValue(site.recordOutput(day, amount));
            settlement.recordIndustryOutput(day, type, amount);
            setDirty();
            return true;
        }
        return false;
    }

    public void recordDomesticExport(Settlement origin, IndustryType type, int amount, long day) {
        if (origin == null || type == null || amount <= 0) return;
        origin.recordTradeExport(day, type, amount);
        setDirty();
    }

    public boolean recordDomesticImport(UUID originSettlementId, Settlement destination,
                                        IndustryType type, int amount, int unitPrice, long day) {
        if (originSettlementId == null || destination == null || type == null || amount <= 0) return false;
        if (originSettlementId.equals(destination.getId())) return false;
        Settlement origin = settlementsById.get(originSettlementId);
        if (origin == null) return false;
        int effectiveUnitPrice = unitPrice > 0 ? unitPrice : getCommodityUnitPrice(origin, type);
        long grossValue = (long) effectiveUnitPrice * amount;
        SettlementFinance originFinance = ensureFinance(origin);
        SettlementFinance destinationFinance = ensureFinance(destination);
        if (!destinationFinance.canDebitLocal(grossValue)) return false;
        long moneyBefore = originFinance.getTotalMoney() + destinationFinance.getTotalMoney();
        if (!destinationFinance.debitLocal(grossValue)) return false;
        originFinance.creditLocal(grossValue);
        long tax = originFinance.calculateCommercialTax(grossValue);
        if (tax > 0L && !originFinance.moveLocalToTreasury(tax)) {
            throw new IllegalStateException("Kora commercial tax transfer failed after a settled domestic sale");
        }
        if (moneyBefore != originFinance.getTotalMoney() + destinationFinance.getTotalMoney()) {
            throw new IllegalStateException("Kora conservation invariant violated during domestic trade");
        }
        destination.recordTradeImport(day, type, amount);
        destinationFinance.recordImport(day, grossValue);
        originFinance.recordExport(day, grossValue, tax);
        setDirty();
        return true;
    }

    public int getIndustryStaffingSignal(ResourceKey<Level> dimension, BlockPos industryPos) {
        IndustrySite site = industrySitesByLocation.get(Settlement.locationKey(dimension, industryPos));
        if (site == null) return 0;
        Settlement settlement = settlementsById.get(site.settlementId());
        return settlement == null ? 0 : settlement.getIndustryStaffingSignal(site.type());
    }

    public void refreshIndustrySignals(MinecraftServer server) {
        for (IndustrySite site : industrySitesByLocation.values()) {
            Settlement settlement = settlementsById.get(site.settlementId());
            if (settlement == null) continue;
            int staffing = settlement.getIndustryStaffingSignal(site.type());
            BlockPos pos = BlockPos.of(site.pos());
            for (ServerLevel level : server.getAllLevels()) {
                if (!level.dimension().location().toString().equals(site.dimension()) || !level.hasChunkAt(pos)) continue;
                BlockState state = level.getBlockState(pos);
                if (state.getBlock() instanceof IndustryBlock industryBlock) industryBlock.syncStaffing(level, pos, staffing);
                break;
            }
        }
    }

    public void removeIndustry(ResourceKey<Level> dimension, BlockPos industryPos) {
        String key = Settlement.locationKey(dimension, industryPos);
        IndustrySite removed = industrySitesByLocation.remove(key);
        if (removed != null) {
            pendingIndustryLinksByPlayer.values().removeIf(key::equals);
            recalculateIndustryJobs(removed.settlementId());
            setDirty();
        }
    }

    private void recalculateAllIndustryJobs() {
        for (UUID settlementId : settlementsById.keySet()) recalculateIndustryJobs(settlementId);
    }

    private void recalculateIndustryJobs(UUID settlementId) {
        if (settlementId == null) return;
        Settlement settlement = settlementsById.get(settlementId);
        if (settlement == null) return;
        int foodJobs = 0;
        int constructionJobs = 0;
        for (IndustrySite site : industrySitesByLocation.values()) {
            if (!site.settlementId().equals(settlementId)) continue;
            if (site.type() == IndustryType.BAKERY) foodJobs += site.type().jobs();
            else if (site.type() == IndustryType.BRICKWORKS) constructionJobs += site.type().jobs();
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
                ensureFinance(settlement).ensureDay(currentDay);
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
                ensureFinance(settlement).resetHistory(currentDay);
            }
            setDirty();
            return 0L;
        }
        if (currentDay == lastProcessedDay) {
            for (Settlement settlement : settlementsById.values()) {
                settlement.ensureProductionDay(currentDay);
                settlement.ensureTradeDay(currentDay);
                ensureFinance(settlement).ensureDay(currentDay);
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
                ensureFinance(settlement).ensureDay(skippedToDay);
            }
        }
        long firstRecordedDay = lastProcessedDay + skippedDays + 1L;
        for (long day = firstRecordedDay; day <= currentDay; day++) {
            for (Settlement settlement : settlementsById.values()) {
                settlement.advanceEconomyForDay();
                settlement.recordHistory(day);
                settlement.ensureProductionDay(day);
                settlement.ensureTradeDay(day);
                ensureFinance(settlement).ensureDay(day);
            }
        }
        lastProcessedDay = currentDay;
        setDirty();
        return daysElapsed;
    }

    private Settlement findNearestSettlement(ResourceKey<Level> dimension, BlockPos pos) {
        Settlement nearest = null;
        double nearestDistance = Double.MAX_VALUE;
        for (Settlement settlement : settlementsById.values()) {
            if (!settlement.isInDimension(dimension)) continue;
            int claimRadius = getSettlementTier(settlement).claimRadius();
            double distance = settlement.distanceToSqr(pos);
            if (distance <= (double) claimRadius * claimRadius && distance < nearestDistance) {
                nearest = settlement;
                nearestDistance = distance;
            }
        }
        return nearest;
    }

    @Override
    public CompoundTag save(CompoundTag tag) {
        ListTag settlements = new ListTag();
        for (Settlement settlement : settlementsById.values()) settlements.add(settlement.save());
        tag.put(TAG_SETTLEMENTS, settlements);
        ListTag finances = new ListTag();
        for (SettlementFinance finance : financesBySettlementId.values()) finances.add(finance.save());
        tag.put(TAG_FINANCES, finances);
        ListTag depots = new ListTag();
        for (DepotLink link : depotLinksByLocation.values()) depots.add(link.save());
        tag.put(TAG_DEPOTS, depots);
        ListTag industries = new ListTag();
        for (IndustrySite site : industrySitesByLocation.values()) industries.add(site.save());
        tag.put(TAG_INDUSTRIES, industries);
        ListTag warehouses = new ListTag();
        for (WarehouseSite site : warehouseSitesByLocation.values()) warehouses.add(site.save());
        tag.put(TAG_WAREHOUSES, warehouses);
        tag.putLong(TAG_LAST_PROCESSED_DAY, lastProcessedDay);
        return tag;
    }

    public record IndustryLinkResult(boolean handled, boolean success, String message) {
        private static IndustryLinkResult notHandled() { return new IndustryLinkResult(false, false, ""); }
    }

    public record IndustryTelemetry(boolean depotLinked, int outputToday, long lifetimeOutput, int sectorAverage7d) {
        private static final IndustryTelemetry EMPTY = new IndustryTelemetry(false, 0, 0L, 0);
    }

    public record WarehouseStatus(boolean linked, boolean commissioned, String failure, Settlement settlement,
                                  int commissionedWarehouses, int breadCapacity, int brickCapacity) {
        static WarehouseStatus unlinked() { return new WarehouseStatus(false, false, "UNLINKED", null, 0, 0, 0); }
        WarehouseStatus withFailure(String reason) {
            return new WarehouseStatus(linked, commissioned, reason, settlement, commissionedWarehouses, breadCapacity, brickCapacity);
        }
    }

    private record DepotLink(String dimension, long pos, UUID settlementId) {
        private static final String TAG_DIMENSION = "Dimension";
        private static final String TAG_POS = "Pos";
        private static final String TAG_SETTLEMENT_ID = "SettlementId";
        static DepotLink load(CompoundTag tag) { return new DepotLink(tag.getString(TAG_DIMENSION), tag.getLong(TAG_POS), tag.getUUID(TAG_SETTLEMENT_ID)); }
        CompoundTag save() {
            CompoundTag tag = new CompoundTag();
            tag.putString(TAG_DIMENSION, dimension); tag.putLong(TAG_POS, pos); tag.putUUID(TAG_SETTLEMENT_ID, settlementId); return tag;
        }
        String locationKey() { return Settlement.locationKey(dimension, pos); }
    }

    private record WarehouseSite(String dimension, long pos, UUID settlementId, boolean commissioned) {
        private static final String TAG_DIMENSION = "Dimension";
        private static final String TAG_POS = "Pos";
        private static final String TAG_SETTLEMENT_ID = "SettlementId";
        private static final String TAG_COMMISSIONED = "Commissioned";
        static WarehouseSite create(String dimension, long pos, UUID settlementId) { return new WarehouseSite(dimension, pos, settlementId, false); }
        static WarehouseSite load(CompoundTag tag) {
            if (!tag.hasUUID(TAG_SETTLEMENT_ID)) return null;
            return new WarehouseSite(tag.getString(TAG_DIMENSION), tag.getLong(TAG_POS), tag.getUUID(TAG_SETTLEMENT_ID), tag.getBoolean(TAG_COMMISSIONED));
        }
        WarehouseSite commission() { return new WarehouseSite(dimension, pos, settlementId, true); }
        String locationKey() { return Settlement.locationKey(dimension, pos); }
        CompoundTag save() {
            CompoundTag tag = new CompoundTag();
            tag.putString(TAG_DIMENSION, dimension); tag.putLong(TAG_POS, pos); tag.putUUID(TAG_SETTLEMENT_ID, settlementId); tag.putBoolean(TAG_COMMISSIONED, commissioned); return tag;
        }
    }

    private record IndustrySite(String dimension, long pos, UUID settlementId, IndustryType type,
                                boolean depotLinked, String depotDimension, long depotPos,
                                long outputDay, int outputToday, long lifetimeOutput) {
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
            if (type == null) return null;
            boolean linked = tag.getBoolean(TAG_DEPOT_LINKED);
            return new IndustrySite(tag.getString(TAG_DIMENSION), tag.getLong(TAG_POS), tag.getUUID(TAG_SETTLEMENT_ID), type,
                    linked, linked ? tag.getString(TAG_DEPOT_DIMENSION) : "", linked ? tag.getLong(TAG_DEPOT_POS) : 0L,
                    tag.contains(TAG_OUTPUT_DAY, Tag.TAG_LONG) ? tag.getLong(TAG_OUTPUT_DAY) : -1L,
                    Math.max(0, tag.getInt(TAG_OUTPUT_TODAY)),
                    tag.contains(TAG_LIFETIME_OUTPUT, Tag.TAG_LONG) ? Math.max(0L, tag.getLong(TAG_LIFETIME_OUTPUT)) : 0L);
        }
        CompoundTag save() {
            CompoundTag tag = new CompoundTag();
            tag.putString(TAG_DIMENSION, dimension); tag.putLong(TAG_POS, pos); tag.putUUID(TAG_SETTLEMENT_ID, settlementId);
            tag.putString(TAG_TYPE, type.serializedName()); tag.putBoolean(TAG_DEPOT_LINKED, depotLinked);
            if (depotLinked) { tag.putString(TAG_DEPOT_DIMENSION, depotDimension); tag.putLong(TAG_DEPOT_POS, depotPos); }
            tag.putLong(TAG_OUTPUT_DAY, outputDay); tag.putInt(TAG_OUTPUT_TODAY, outputToday); tag.putLong(TAG_LIFETIME_OUTPUT, lifetimeOutput);
            return tag;
        }
        String locationKey() { return Settlement.locationKey(dimension, pos); }
        boolean isLinkedToDepot(String targetDimension, long targetPos) { return depotLinked && depotDimension.equals(targetDimension) && depotPos == targetPos; }
        IndustrySite withSettlement(UUID id) { return new IndustrySite(dimension, pos, id, type, depotLinked, depotDimension, depotPos, outputDay, outputToday, lifetimeOutput); }
        IndustrySite withDepotLink(String dim, long pos) { return new IndustrySite(dimension, this.pos, settlementId, type, true, dim, pos, outputDay, outputToday, lifetimeOutput); }
        IndustrySite withoutDepotLink() { return new IndustrySite(dimension, pos, settlementId, type, false, "", 0L, outputDay, outputToday, lifetimeOutput); }
        IndustrySite recordOutput(long day, int amount) {
            int today = outputDay == day ? outputToday + amount : amount;
            return new IndustrySite(dimension, pos, settlementId, type, depotLinked, depotDimension, depotPos, day, today, lifetimeOutput + amount);
        }
    }
}
