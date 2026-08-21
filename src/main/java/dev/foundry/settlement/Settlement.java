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
    public static final int GROWTH_PROSPERITY_THRESHOLD = 3;
    public static final int LABOR_FORCE_PERCENT = 50;
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

    private final UUID id;
    private final String dimension;
    private final long townHallPos;
    private final List<HistoryPoint> history;
    private int population;
    private int breadSupplied;
    private int buildingMaterialsSupplied;
    private int prosperity;
    private int foodJobCapacity;
    private int constructionJobCapacity;
    private LaborPriority laborPriority;

    private Settlement(UUID id, String dimension, long townHallPos, int population, int breadSupplied,
                       int buildingMaterialsSupplied, int prosperity, int foodJobCapacity,
                       int constructionJobCapacity, LaborPriority laborPriority, List<HistoryPoint> history) {
        this.id = id;
        this.dimension = dimension;
        this.townHallPos = townHallPos;
        this.population = population;
        this.breadSupplied = breadSupplied;
        this.buildingMaterialsSupplied = buildingMaterialsSupplied;
        this.prosperity = prosperity;
        this.foodJobCapacity = Math.max(0, foodJobCapacity);
        this.constructionJobCapacity = Math.max(0, constructionJobCapacity);
        this.laborPriority = laborPriority == null ? LaborPriority.BALANCED : laborPriority;
        this.history = new ArrayList<>(history);
        trimHistory();
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
                0,
                LaborPriority.BALANCED,
                List.of()
        );
    }

    public static Settlement load(CompoundTag tag) {
        List<HistoryPoint> history = new ArrayList<>();
        ListTag historyTag = tag.getList(TAG_HISTORY, Tag.TAG_COMPOUND);
        for (int i = 0; i < historyTag.size(); i++) {
            history.add(HistoryPoint.load(historyTag.getCompound(i)));
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
                tag.getInt(TAG_PROSPERITY),
                tag.getInt(TAG_FOOD_JOB_CAPACITY),
                tag.getInt(TAG_CONSTRUCTION_JOB_CAPACITY),
                laborPriority,
                history
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
        tag.putInt(TAG_PROSPERITY, prosperity);
        tag.putInt(TAG_FOOD_JOB_CAPACITY, foodJobCapacity);
        tag.putInt(TAG_CONSTRUCTION_JOB_CAPACITY, constructionJobCapacity);
        tag.putString(TAG_LABOR_PRIORITY, laborPriority.serializedName());

        ListTag historyTag = new ListTag();
        for (HistoryPoint historyPoint : history) {
            historyTag.add(historyPoint.save());
        }
        tag.put(TAG_HISTORY, historyTag);
        return tag;
    }

    public int deliverBread(int offered) {
        int accepted = Math.min(Math.max(offered, 0), getBreadTarget() - breadSupplied);
        if (accepted <= 0) {
            return 0;
        }

        boolean wasSupplied = isSupplied();
        breadSupplied += accepted;
        if (!wasSupplied && isSupplied()) {
            prosperity += 1;
        }
        return accepted;
    }

    public int deliverBuildingMaterials(int offered) {
        int accepted = Math.min(Math.max(offered, 0), getBuildingMaterialsTarget() - buildingMaterialsSupplied);
        if (accepted <= 0) {
            return 0;
        }

        buildingMaterialsSupplied += accepted;
        return accepted;
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
                prosperity
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

    public int getBreadTarget() {
        return scaledForPopulation(BASE_BREAD_TARGET);
    }

    public int getDailyBreadConsumption() {
        return scaledForPopulation(BASE_DAILY_BREAD_CONSUMPTION);
    }

    public int getBuildingMaterialsTarget() {
        return scaledForPopulation(BASE_BUILDING_MATERIAL_TARGET);
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
                && prosperity >= GROWTH_PROSPERITY_THRESHOLD;
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
        return prosperity;
    }

    public List<HistoryPoint> getHistory() {
        return Collections.unmodifiableList(history);
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
                    tag.getInt(TAG_PROSPERITY)
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
}
