package dev.foundry.settlement;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

public final class Settlement {
    public static final int DEFAULT_POPULATION = 120;
    public static final int HISTORY_LIMIT = 30;
    public static final int MAX_PROSPERITY = SettlementDevelopment.MAX_SCORE;
    public static final int GROWTH_PROSPERITY_THRESHOLD = 20;
    public static final int LABOR_FORCE_PERCENT = 50;
    public static final int PROTOTYPE_RESERVE_MULTIPLIER = 4;
    private static final int BASE_BREAD_TARGET = 64;
    private static final int BASE_DAILY_BREAD_CONSUMPTION = 16;
    private static final int BASE_BUILDING_MATERIAL_TARGET = 32;
    private static final int BASE_GROWTH_MATERIAL_COST = 8;

    private static final String TAG_ID = "Id";
    private static final String TAG_DIMENSION = "Dimension";
    private static final String TAG_TOWN_HALL_POS = "TownHallPos";
    private static final String TAG_POPULATION = "Population";
    private static final String TAG_BREAD_SUPPLIED = "BreadSupplied";
    private static final String TAG_BUILDING_MATERIALS_SUPPLIED = "BuildingMaterialsSupplied";
    private static final String TAG_PROSPERITY = "Prosperity";
    private static final String TAG_FOOD_JOB_CAPACITY = "FoodJobCapacity";
    private static final String TAG_CONSTRUCTION_JOB_CAPACITY = "ConstructionJobCapacity";
    private static final String TAG_LABOR_PRIORITY = "LaborPriority";
    private static final String TAG_HISTORY = "History";
    private static final String TAG_PRODUCTION_HISTORY = "ProductionHistory";
    private static final String TAG_TRADE_HISTORY = "TradeHistory";

    private final UUID id;
    private final String dimension;
    private final long townHallPos;
    private final List<HistoryPoint> history;
    private final List<ProductionPoint> productionHistory;
    private final List<TradePoint> tradeHistory;
    private int population;
    private int breadSupplied;
    private int buildingMaterialsSupplied;
    private int foodJobCapacity;
    private int constructionJobCapacity;
    private LaborPriority laborPriority;

    private Settlement(UUID id, String dimension, long townHallPos, int population, int breadSupplied,
                       int buildingMaterialsSupplied, int foodJobCapacity,
                       int constructionJobCapacity, LaborPriority laborPriority, List<HistoryPoint> history,
                       List<ProductionPoint> productionHistory, List<TradePoint> tradeHistory) {
        this.id = id;
        this.dimension = dimension;
        this.townHallPos = townHallPos;
        this.population = population;
        this.breadSupplied = breadSupplied;
        this.buildingMaterialsSupplied = buildingMaterialsSupplied;
        this.foodJobCapacity = Math.max(0, foodJobCapacity);
        this.constructionJobCapacity = Math.max(0, constructionJobCapacity);
        this.laborPriority = laborPriority == null ? LaborPriority.BALANCED : laborPriority;
        this.history = new ArrayList<>(history);
        this.productionHistory = new ArrayList<>(productionHistory);
        this.tradeHistory = new ArrayList<>(tradeHistory);
        trimHistory();
        trimProductionHistory();
        trimTradeHistory();
    }

    public static Settlement create(ResourceKey<Level> dimension, BlockPos townHallPos) {
        return new Settlement(
                UUID.randomUUID(),
                dimension.location().toString(),
                townHallPos.asLong(),
                DEFAULT_POPULATION,
                0,
                0,
                0,
                0,
                LaborPriority.BALANCED,
                List.of(),
                List.of(),
                List.of()
        );
    }

    public static Settlement load(CompoundTag tag) {
        List<HistoryPoint> history = new ArrayList<>();
        ListTag historyTag = tag.getList(TAG_HISTORY, Tag.TAG_COMPOUND);
        for (int i = 0; i < historyTag.size(); i++) {
            history.add(HistoryPoint.load(historyTag.getCompound(i)));
        }

        List<ProductionPoint> productionHistory = new ArrayList<>();
        ListTag productionTag = tag.getList(TAG_PRODUCTION_HISTORY, Tag.TAG_COMPOUND);
        for (int i = 0; i < productionTag.size(); i++) {
            productionHistory.add(ProductionPoint.load(productionTag.getCompound(i)));
        }

        List<TradePoint> tradeHistory = new ArrayList<>();
        ListTag tradeTag = tag.getList(TAG_TRADE_HISTORY, Tag.TAG_COMPOUND);
        for (int i = 0; i < tradeTag.size(); i++) {
            tradeHistory.add(TradePoint.load(tradeTag.getCompound(i)));
        }

        LaborPriority laborPriority = tag.contains(TAG_LABOR_PRIORITY, Tag.TAG_STRING)
                ? LaborPriority.fromSerializedName(tag.getString(TAG_LABOR_PRIORITY))
                : LaborPriority.BALANCED;

        return new Settlement(
                tag.getUUID(TAG_ID),
                tag.getString(TAG_DIMENSION),
                tag.getLong(TAG_TOWN_HALL_POS),
                tag.getInt(TAG_POPULATION),
                tag.getInt(TAG_BREAD_SUPPLIED),
                tag.getInt(TAG_BUILDING_MATERIALS_SUPPLIED),
                tag.getInt(TAG_FOOD_JOB_CAPACITY),
                tag.getInt(TAG_CONSTRUCTION_JOB_CAPACITY),
                laborPriority,
                history,
                productionHistory,
                tradeHistory
        );
    }

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putUUID(TAG_ID, id);
        tag.putString(TAG_DIMENSION, dimension);
        tag.putLong(TAG_TOWN_HALL_POS, townHallPos);
        tag.putInt(TAG_POPULATION, population);
        tag.putInt(TAG_BREAD_SUPPLIED, breadSupplied);
        tag.putInt(TAG_BUILDING_MATERIALS_SUPPLIED, buildingMaterialsSupplied);
        tag.putInt(TAG_PROSPERITY, getProsperity());
        tag.putInt(TAG_FOOD_JOB_CAPACITY, foodJobCapacity);
        tag.putInt(TAG_CONSTRUCTION_JOB_CAPACITY, constructionJobCapacity);
        tag.putString(TAG_LABOR_PRIORITY, laborPriority.serializedName());

        ListTag historyTag = new ListTag();
        for (HistoryPoint historyPoint : history) {
            historyTag.add(historyPoint.save());
        }
        tag.put(TAG_HISTORY, historyTag);

        ListTag productionTag = new ListTag();
        for (ProductionPoint productionPoint : productionHistory) {
            productionTag.add(productionPoint.save());
        }
        tag.put(TAG_PRODUCTION_HISTORY, productionTag);

        ListTag tradeTag = new ListTag();
        for (TradePoint tradePoint : tradeHistory) {
            tradeTag.add(tradePoint.save());
        }
        tag.put(TAG_TRADE_HISTORY, tradeTag);
        return tag;
    }

    public int deliverBread(int offered) {
        int accepted = Math.min(Math.max(offered, 0), getBreadReserveCapacity() - breadSupplied);
        if (accepted <= 0) {
            return 0;
        }

        breadSupplied += accepted;
        return accepted;
    }

    public int deliverBuildingMaterials(int offered) {
        int accepted = Math.min(Math.max(offered, 0), getBuildingMaterialsReserveCapacity() - buildingMaterialsSupplied);
        if (accepted <= 0) {
            return 0;
        }

        buildingMaterialsSupplied += accepted;
        return accepted;
    }

    public int withdrawBread(int requested) {
        int removed = Math.min(Math.max(requested, 0), breadSupplied);
        if (removed > 0) {
            breadSupplied -= removed;
        }
        return removed;
    }

    public int withdrawBuildingMaterials(int requested) {
        int removed = Math.min(Math.max(requested, 0), buildingMaterialsSupplied);
        if (removed > 0) {
            buildingMaterialsSupplied -= removed;
        }
        return removed;
    }

    public boolean consumeBreadForDays(long daysElapsed) {
        if (daysElapsed <= 0 || breadSupplied <= 0) {
            return false;
        }

        long requestedConsumption = daysElapsed * (long) getDailyBreadConsumption();
        int consumed = (int) Math.min((long) breadSupplied, requestedConsumption);
        if (consumed <= 0) {
            return false;
        }

        breadSupplied -= consumed;
        return true;
    }

    public boolean advanceEconomyForDay() {
        boolean grew = tryGrowPopulation();
        consumeBreadForDays(1L);
        return grew;
    }

    public void advanceEconomyForDays(long daysElapsed) {
        if (daysElapsed <= 0) {
            return;
        }

        advanceEconomyForDay();
        if (daysElapsed > 1L) {
            consumeBreadForDays(daysElapsed - 1L);
        }
    }

    private boolean tryGrowPopulation() {
        if (!isGrowthReady()) {
            return false;
        }

        buildingMaterialsSupplied = Math.max(0, buildingMaterialsSupplied - getGrowthMaterialCost());
        population += getDailyGrowthAmount();
        return true;
    }

    public void recordHistory(long day) {
        HistoryPoint point = new HistoryPoint(
                day,
                population,
                breadSupplied,
                getBreadTarget(),
                buildingMaterialsSupplied,
                getBuildingMaterialsTarget(),
                getProsperity()
        );

        if (!history.isEmpty() && history.get(history.size() - 1).day() == day) {
            history.set(history.size() - 1, point);
        } else {
            history.add(point);
        }
        trimHistory();
    }

    public void resetHistory(long day) {
        history.clear();
        recordHistory(day);
    }

    private void trimHistory() {
        while (history.size() > HISTORY_LIMIT) {
            history.remove(0);
        }
    }

    public void ensureProductionDay(long day) {
        if (!productionHistory.isEmpty() && day < productionHistory.get(productionHistory.size() - 1).day()) {
            resetProductionHistory(day);
            return;
        }

        if (productionHistory.isEmpty()) {
            productionHistory.add(new ProductionPoint(day, 0, 0));
            return;
        }

        long lastDay = productionHistory.get(productionHistory.size() - 1).day();
        if (lastDay == day) {
            return;
        }

        long firstMissingDay = lastDay + 1L;
        if (day - firstMissingDay + 1L > HISTORY_LIMIT) {
            productionHistory.clear();
            firstMissingDay = day - HISTORY_LIMIT + 1L;
        }

        for (long missingDay = firstMissingDay; missingDay <= day; missingDay++) {
            productionHistory.add(new ProductionPoint(missingDay, 0, 0));
        }
        trimProductionHistory();
    }

    public void resetProductionHistory(long day) {
        productionHistory.clear();
        productionHistory.add(new ProductionPoint(day, 0, 0));
    }

    public void recordIndustryOutput(long day, IndustryType type, int amount) {
        if (amount <= 0 || type == null) {
            return;
        }

        ensureProductionDay(day);
        int lastIndex = productionHistory.size() - 1;
        ProductionPoint current = productionHistory.get(lastIndex);
        if (current.day() != day) {
            return;
        }

        if (type == IndustryType.BAKERY) {
            productionHistory.set(lastIndex, new ProductionPoint(
                    day,
                    current.foodOutput() + amount,
                    current.constructionOutput()
            ));
        } else if (type == IndustryType.BRICKWORKS) {
            productionHistory.set(lastIndex, new ProductionPoint(
                    day,
                    current.foodOutput(),
                    current.constructionOutput() + amount
            ));
        }
    }

    private void trimProductionHistory() {
        while (productionHistory.size() > HISTORY_LIMIT) {
            productionHistory.remove(0);
        }
    }

    public void ensureTradeDay(long day) {
        if (!tradeHistory.isEmpty() && day < tradeHistory.get(tradeHistory.size() - 1).day()) {
            resetTradeHistory(day);
            return;
        }

        if (tradeHistory.isEmpty()) {
            tradeHistory.add(TradePoint.empty(day));
            return;
        }

        long lastDay = tradeHistory.get(tradeHistory.size() - 1).day();
        if (lastDay == day) {
            return;
        }

        long firstMissingDay = lastDay + 1L;
        if (day - firstMissingDay + 1L > HISTORY_LIMIT) {
            tradeHistory.clear();
            firstMissingDay = day - HISTORY_LIMIT + 1L;
        }

        for (long missingDay = firstMissingDay; missingDay <= day; missingDay++) {
            tradeHistory.add(TradePoint.empty(missingDay));
        }
        trimTradeHistory();
    }

    public void resetTradeHistory(long day) {
        tradeHistory.clear();
        tradeHistory.add(TradePoint.empty(day));
    }

    public void recordTradeImport(long day, IndustryType type, int amount) {
        updateTrade(day, type, Math.max(0, amount), 0);
    }

    public void recordTradeExport(long day, IndustryType type, int amount) {
        updateTrade(day, type, 0, Math.max(0, amount));
    }

    private void updateTrade(long day, IndustryType type, int imported, int exported) {
        if (type == null || (imported <= 0 && exported <= 0)) {
            return;
        }

        ensureTradeDay(day);
        int index = tradeHistory.size() - 1;
        TradePoint current = tradeHistory.get(index);
        if (current.day() != day) {
            return;
        }

        if (type == IndustryType.BAKERY) {
            tradeHistory.set(index, new TradePoint(
                    day,
                    current.breadImports() + imported,
                    current.breadExports() + exported,
                    current.brickImports(),
                    current.brickExports()
            ));
        } else if (type == IndustryType.BRICKWORKS) {
            tradeHistory.set(index, new TradePoint(
                    day,
                    current.breadImports(),
                    current.breadExports(),
                    current.brickImports() + imported,
                    current.brickExports() + exported
            ));
        }
    }

    private void trimTradeHistory() {
        while (tradeHistory.size() > HISTORY_LIMIT) {
            tradeHistory.remove(0);
        }
    }

    public int getFoodOutputToday() {
        return productionHistory.isEmpty() ? 0 : productionHistory.get(productionHistory.size() - 1).foodOutput();
    }

    public int getConstructionOutputToday() {
        return productionHistory.isEmpty() ? 0 : productionHistory.get(productionHistory.size() - 1).constructionOutput();
    }

    public int getFoodOutputAverage(int days) {
        return productionAverage(days, true);
    }

    public int getConstructionOutputAverage(int days) {
        return productionAverage(days, false);
    }

    private int productionAverage(int days, boolean food) {
        if (days <= 0 || productionHistory.isEmpty()) {
            return 0;
        }

        int count = Math.min(days, productionHistory.size());
        long total = 0L;
        for (int i = productionHistory.size() - count; i < productionHistory.size(); i++) {
            ProductionPoint point = productionHistory.get(i);
            total += food ? point.foodOutput() : point.constructionOutput();
        }
        return (int) ((total + count / 2L) / count);
    }

    public int getBreadImportsToday() {
        return tradeHistory.isEmpty() ? 0 : tradeHistory.get(tradeHistory.size() - 1).breadImports();
    }

    public int getBreadExportsToday() {
        return tradeHistory.isEmpty() ? 0 : tradeHistory.get(tradeHistory.size() - 1).breadExports();
    }

    public int getBrickImportsToday() {
        return tradeHistory.isEmpty() ? 0 : tradeHistory.get(tradeHistory.size() - 1).brickImports();
    }

    public int getBrickExportsToday() {
        return tradeHistory.isEmpty() ? 0 : tradeHistory.get(tradeHistory.size() - 1).brickExports();
    }

    public int getBreadImportsAverage(int days) {
        return tradeAverage(days, 0);
    }

    public int getBreadExportsAverage(int days) {
        return tradeAverage(days, 1);
    }

    public int getBrickImportsAverage(int days) {
        return tradeAverage(days, 2);
    }

    public int getBrickExportsAverage(int days) {
        return tradeAverage(days, 3);
    }

    private int tradeAverage(int days, int metric) {
        if (days <= 0 || tradeHistory.isEmpty()) {
            return 0;
        }

        int count = Math.min(days, tradeHistory.size());
        long total = 0L;
        for (int i = tradeHistory.size() - count; i < tradeHistory.size(); i++) {
            TradePoint point = tradeHistory.get(i);
            total += switch (metric) {
                case 0 -> point.breadImports();
                case 1 -> point.breadExports();
                case 2 -> point.brickImports();
                default -> point.brickExports();
            };
        }
        return (int) ((total + count / 2L) / count);
    }

    public int getBreadTarget() {
        return scaledForPopulation(BASE_BREAD_TARGET);
    }

    public int getBreadReserveCapacity() {
        return reserveCapacity(getBreadTarget());
    }

    public int getDailyBreadConsumption() {
        return scaledForPopulation(BASE_DAILY_BREAD_CONSUMPTION);
    }

    public int getBuildingMaterialsTarget() {
        return scaledForPopulation(BASE_BUILDING_MATERIAL_TARGET);
    }

    public int getBuildingMaterialsReserveCapacity() {
        return reserveCapacity(getBuildingMaterialsTarget());
    }

    public int getGrowthMaterialCost() {
        return scaledForPopulation(BASE_GROWTH_MATERIAL_COST);
    }

    public int getDailyGrowthAmount() {
        return Math.max(1, (population + 59) / 60);
    }

    private int scaledForPopulation(int baseAmount) {
        long scaled = ((long) Math.max(population, 1) * baseAmount + DEFAULT_POPULATION - 1L)
                / DEFAULT_POPULATION;
        return (int) Math.max(1L, scaled);
    }

    private static int reserveCapacity(int target) {
        long capacity = (long) Math.max(1, target) * PROTOTYPE_RESERVE_MULTIPLIER;
        return (int) Math.min(Integer.MAX_VALUE, capacity);
    }

    public int getWorkforce() {
        return Math.max(1, (population * LABOR_FORCE_PERCENT + 99) / 100);
    }

    public int getEmployed() {
        return Math.min(getWorkforce(), getTotalJobCapacity());
    }

    public int getUnemployed() {
        return Math.max(0, getWorkforce() - getEmployed());
    }

    public int getVacancies() {
        return Math.max(0, getTotalJobCapacity() - getEmployed());
    }

    public int getTotalJobCapacity() {
        return foodJobCapacity + constructionJobCapacity;
    }

    public int getFoodJobCapacity() {
        return foodJobCapacity;
    }

    public int getConstructionJobCapacity() {
        return constructionJobCapacity;
    }

    public int getFoodEmployed() {
        return employmentAllocation().food();
    }

    public int getConstructionEmployed() {
        return employmentAllocation().construction();
    }

    private EmploymentAllocation employmentAllocation() {
        int totalEmployed = getEmployed();
        if (totalEmployed <= 0) {
            return new EmploymentAllocation(0, 0);
        }

        if (laborPriority == LaborPriority.FOOD) {
            int food = Math.min(foodJobCapacity, totalEmployed);
            int construction = Math.min(constructionJobCapacity, totalEmployed - food);
            return new EmploymentAllocation(food, construction);
        }

        if (laborPriority == LaborPriority.CONSTRUCTION) {
            int construction = Math.min(constructionJobCapacity, totalEmployed);
            int food = Math.min(foodJobCapacity, totalEmployed - construction);
            return new EmploymentAllocation(food, construction);
        }

        int totalJobs = getTotalJobCapacity();
        if (totalJobs <= 0) {
            return new EmploymentAllocation(0, 0);
        }

        int food = (int) (((long) totalEmployed * foodJobCapacity + totalJobs / 2L) / totalJobs);
        food = Math.min(foodJobCapacity, Math.max(0, food));
        int construction = Math.min(constructionJobCapacity, Math.max(0, totalEmployed - food));
        int remaining = totalEmployed - food - construction;

        if (remaining > 0) {
            int extraFood = Math.min(foodJobCapacity - food, remaining);
            food += extraFood;
            remaining -= extraFood;
        }
        if (remaining > 0) {
            construction += Math.min(constructionJobCapacity - construction, remaining);
        }

        return new EmploymentAllocation(food, construction);
    }

    public int getIndustryJobCapacity(IndustryType type) {
        return type == IndustryType.BAKERY ? getFoodJobCapacity() : getConstructionJobCapacity();
    }

    public int getIndustryEmployed(IndustryType type) {
        return type == IndustryType.BAKERY ? getFoodEmployed() : getConstructionEmployed();
    }

    public int getIndustryStaffingPercent(IndustryType type) {
        int jobs = getIndustryJobCapacity(type);
        if (jobs <= 0) {
            return 0;
        }
        return Math.min(100, getIndustryEmployed(type) * 100 / jobs);
    }

    public int getIndustryStaffingSignal(IndustryType type) {
        int jobs = getIndustryJobCapacity(type);
        int employed = getIndustryEmployed(type);
        if (jobs <= 0 || employed <= 0) {
            return 0;
        }
        if (employed >= jobs) {
            return 15;
        }
        return Math.max(1, employed * 15 / jobs);
    }

    void setIndustryJobCapacity(int foodJobCapacity, int constructionJobCapacity) {
        this.foodJobCapacity = Math.max(0, foodJobCapacity);
        this.constructionJobCapacity = Math.max(0, constructionJobCapacity);
    }

    public LaborPriority getLaborPriority() {
        return laborPriority;
    }

    public LaborPriority cycleLaborPriority() {
        laborPriority = laborPriority.next();
        return laborPriority;
    }

    public boolean isSupplied() {
        return breadSupplied >= getBreadTarget();
    }

    public boolean hasBuildingMaterials() {
        return buildingMaterialsSupplied >= getBuildingMaterialsTarget();
    }

    public boolean isGrowthReady() {
        return isSupplied()
                && hasBuildingMaterials()
                && getProsperity() >= GROWTH_PROSPERITY_THRESHOLD;
    }

    public boolean matches(ResourceKey<Level> dimension, BlockPos pos) {
        return isInDimension(dimension) && this.townHallPos == pos.asLong();
    }

    public boolean isInDimension(ResourceKey<Level> dimension) {
        return this.dimension.equals(dimension.location().toString());
    }

    public double distanceToSqr(BlockPos pos) {
        return BlockPos.of(townHallPos).distSqr(pos);
    }

    public String locationKey() {
        return locationKey(dimension, townHallPos);
    }

    public static String locationKey(ResourceKey<Level> dimension, BlockPos pos) {
        return locationKey(dimension.location().toString(), pos.asLong());
    }

    public static String locationKey(String dimension, long pos) {
        return dimension + "@" + pos;
    }

    public UUID getId() {
        return id;
    }

    public int getPopulation() {
        return population;
    }

    public int getBreadSupplied() {
        return breadSupplied;
    }

    public int getBuildingMaterialsSupplied() {
        return buildingMaterialsSupplied;
    }

    public int getProsperity() {
        return SettlementDevelopment.score(this);
    }

    public List<HistoryPoint> getHistory() {
        return Collections.unmodifiableList(history);
    }

    public List<ProductionPoint> getProductionHistory() {
        return Collections.unmodifiableList(productionHistory);
    }

    public List<TradePoint> getTradeHistory() {
        return Collections.unmodifiableList(tradeHistory);
    }

    private record EmploymentAllocation(int food, int construction) {
    }

    public record HistoryPoint(
            long day,
            int population,
            int breadSupplied,
            int breadTarget,
            int buildingMaterialsSupplied,
            int buildingMaterialsTarget,
            int prosperity
    ) {
        private static final String TAG_DAY = "Day";
        private static final String TAG_POPULATION = "Population";
        private static final String TAG_BREAD_SUPPLIED = "BreadSupplied";
        private static final String TAG_BREAD_TARGET = "BreadTarget";
        private static final String TAG_BUILDING_MATERIALS_SUPPLIED = "BuildingMaterialsSupplied";
        private static final String TAG_BUILDING_MATERIALS_TARGET = "BuildingMaterialsTarget";
        private static final String TAG_PROSPERITY = "Prosperity";

        static HistoryPoint load(CompoundTag tag) {
            return new HistoryPoint(
                    tag.getLong(TAG_DAY),
                    tag.getInt(TAG_POPULATION),
                    tag.getInt(TAG_BREAD_SUPPLIED),
                    tag.getInt(TAG_BREAD_TARGET),
                    tag.getInt(TAG_BUILDING_MATERIALS_SUPPLIED),
                    tag.getInt(TAG_BUILDING_MATERIALS_TARGET),
                    Math.max(0, Math.min(MAX_PROSPERITY, tag.getInt(TAG_PROSPERITY)))
            );
        }

        CompoundTag save() {
            CompoundTag tag = new CompoundTag();
            tag.putLong(TAG_DAY, day);
            tag.putInt(TAG_POPULATION, population);
            tag.putInt(TAG_BREAD_SUPPLIED, breadSupplied);
            tag.putInt(TAG_BREAD_TARGET, breadTarget);
            tag.putInt(TAG_BUILDING_MATERIALS_SUPPLIED, buildingMaterialsSupplied);
            tag.putInt(TAG_BUILDING_MATERIALS_TARGET, buildingMaterialsTarget);
            tag.putInt(TAG_PROSPERITY, prosperity);
            return tag;
        }
    }

    public record ProductionPoint(long day, int foodOutput, int constructionOutput) {
        private static final String TAG_DAY = "Day";
        private static final String TAG_FOOD_OUTPUT = "FoodOutput";
        private static final String TAG_CONSTRUCTION_OUTPUT = "ConstructionOutput";

        static ProductionPoint load(CompoundTag tag) {
            return new ProductionPoint(
                    tag.getLong(TAG_DAY),
                    Math.max(0, tag.getInt(TAG_FOOD_OUTPUT)),
                    Math.max(0, tag.getInt(TAG_CONSTRUCTION_OUTPUT))
            );
        }

        CompoundTag save() {
            CompoundTag tag = new CompoundTag();
            tag.putLong(TAG_DAY, day);
            tag.putInt(TAG_FOOD_OUTPUT, foodOutput);
            tag.putInt(TAG_CONSTRUCTION_OUTPUT, constructionOutput);
            return tag;
        }
    }

    public record TradePoint(long day, int breadImports, int breadExports, int brickImports, int brickExports) {
        private static final String TAG_DAY = "Day";
        private static final String TAG_BREAD_IMPORTS = "BreadImports";
        private static final String TAG_BREAD_EXPORTS = "BreadExports";
        private static final String TAG_BRICK_IMPORTS = "BrickImports";
        private static final String TAG_BRICK_EXPORTS = "BrickExports";

        static TradePoint empty(long day) {
            return new TradePoint(day, 0, 0, 0, 0);
        }

        static TradePoint load(CompoundTag tag) {
            return new TradePoint(
                    tag.getLong(TAG_DAY),
                    Math.max(0, tag.getInt(TAG_BREAD_IMPORTS)),
                    Math.max(0, tag.getInt(TAG_BREAD_EXPORTS)),
                    Math.max(0, tag.getInt(TAG_BRICK_IMPORTS)),
                    Math.max(0, tag.getInt(TAG_BRICK_EXPORTS))
            );
        }

        CompoundTag save() {
            CompoundTag tag = new CompoundTag();
            tag.putLong(TAG_DAY, day);
            tag.putInt(TAG_BREAD_IMPORTS, breadImports);
            tag.putInt(TAG_BREAD_EXPORTS, breadExports);
            tag.putInt(TAG_BRICK_IMPORTS, brickImports);
            tag.putInt(TAG_BRICK_EXPORTS, brickExports);
            return tag;
        }
    }
}
