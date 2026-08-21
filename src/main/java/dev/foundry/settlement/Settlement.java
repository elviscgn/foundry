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
    private static final int BASE_BREAD_TARGET = 64;
    private static final int BASE_DAILY_BREAD_CONSUMPTION = 16;

    private static final String TAG_ID = "Id";
    private static final String TAG_DIMENSION = "Dimension";
    private static final String TAG_TOWN_HALL_POS = "TownHallPos";
    private static final String TAG_POPULATION = "Population";
    private static final String TAG_BREAD_SUPPLIED = "BreadSupplied";
    private static final String TAG_PROSPERITY = "Prosperity";
    private static final String TAG_HISTORY = "History";

    private final UUID id;
    private final String dimension;
    private final long townHallPos;
    private final List<HistoryPoint> history;
    private int population;
    private int breadSupplied;
    private int prosperity;

    private Settlement(UUID id, String dimension, long townHallPos, int population, int breadSupplied,
                       int prosperity, List<HistoryPoint> history) {
        this.id = id;
        this.dimension = dimension;
        this.townHallPos = townHallPos;
        this.population = population;
        this.breadSupplied = breadSupplied;
        this.prosperity = prosperity;
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
                List.of()
        );
    }

    public static Settlement load(CompoundTag tag) {
        List<HistoryPoint> history = new ArrayList<>();
        ListTag historyTag = tag.getList(TAG_HISTORY, Tag.TAG_COMPOUND);
        for (int i = 0; i < historyTag.size(); i++) {
            history.add(HistoryPoint.load(historyTag.getCompound(i)));
        }

        return new Settlement(
                tag.getUUID(TAG_ID),
                tag.getString(TAG_DIMENSION),
                tag.getLong(TAG_TOWN_HALL_POS),
                tag.getInt(TAG_POPULATION),
                tag.getInt(TAG_BREAD_SUPPLIED),
                tag.getInt(TAG_PROSPERITY),
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
        tag.putInt(TAG_PROSPERITY, prosperity);

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

    public void recordHistory(long day) {
        HistoryPoint point = new HistoryPoint(
                day,
                population,
                breadSupplied,
                getBreadTarget(),
                prosperity
        );

        if (!history.isEmpty() && history.get(history.size() - 1).day() == day) {
            history.set(history.size() - 1, point);
        } else {
            history.add(point);
        }
        trimHistory();
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

    private int scaledForPopulation(int baseAmount) {
        long scaled = ((long) Math.max(population, 1) * baseAmount + DEFAULT_POPULATION - 1L)
                / DEFAULT_POPULATION;
        return (int) Math.max(1L, scaled);
    }

    public boolean isSupplied() {
        return breadSupplied >= getBreadTarget();
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

    public int getProsperity() {
        return prosperity;
    }

    public List<HistoryPoint> getHistory() {
        return Collections.unmodifiableList(history);
    }

    public record HistoryPoint(long day, int population, int breadSupplied, int breadTarget, int prosperity) {
        private static final String TAG_DAY = "Day";
        private static final String TAG_POPULATION = "Population";
        private static final String TAG_BREAD_SUPPLIED = "BreadSupplied";
        private static final String TAG_BREAD_TARGET = "BreadTarget";
        private static final String TAG_PROSPERITY = "Prosperity";

        static HistoryPoint load(CompoundTag tag) {
            return new HistoryPoint(
                    tag.getLong(TAG_DAY),
                    tag.getInt(TAG_POPULATION),
                    tag.getInt(TAG_BREAD_SUPPLIED),
                    tag.getInt(TAG_BREAD_TARGET),
                    tag.getInt(TAG_PROSPERITY)
            );
        }

        CompoundTag save() {
            CompoundTag tag = new CompoundTag();
            tag.putLong(TAG_DAY, day);
            tag.putInt(TAG_POPULATION, population);
            tag.putInt(TAG_BREAD_SUPPLIED, breadSupplied);
            tag.putInt(TAG_BREAD_TARGET, breadTarget);
            tag.putInt(TAG_PROSPERITY, prosperity);
            return tag;
        }
    }
}
