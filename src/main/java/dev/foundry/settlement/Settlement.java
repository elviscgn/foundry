package dev.foundry.settlement;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

import java.util.UUID;

public final class Settlement {
    public static final int DEFAULT_POPULATION = 120;
    public static final int BREAD_TARGET = 64;

    private static final String TAG_ID = "Id";
    private static final String TAG_DIMENSION = "Dimension";
    private static final String TAG_TOWN_HALL_POS = "TownHallPos";
    private static final String TAG_POPULATION = "Population";
    private static final String TAG_BREAD_SUPPLIED = "BreadSupplied";
    private static final String TAG_PROSPERITY = "Prosperity";

    private final UUID id;
    private final String dimension;
    private final long townHallPos;
    private int population;
    private int breadSupplied;
    private int prosperity;

    private Settlement(UUID id, String dimension, long townHallPos, int population, int breadSupplied, int prosperity) {
        this.id = id;
        this.dimension = dimension;
        this.townHallPos = townHallPos;
        this.population = population;
        this.breadSupplied = breadSupplied;
        this.prosperity = prosperity;
    }

    public static Settlement create(ResourceKey<Level> dimension, BlockPos townHallPos) {
        return new Settlement(
                UUID.randomUUID(),
                dimension.location().toString(),
                townHallPos.asLong(),
                DEFAULT_POPULATION,
                0,
                0
        );
    }

    public static Settlement load(CompoundTag tag) {
        return new Settlement(
                tag.getUUID(TAG_ID),
                tag.getString(TAG_DIMENSION),
                tag.getLong(TAG_TOWN_HALL_POS),
                tag.getInt(TAG_POPULATION),
                tag.getInt(TAG_BREAD_SUPPLIED),
                tag.getInt(TAG_PROSPERITY)
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
        return tag;
    }

    public int deliverBread(int offered) {
        int accepted = Math.min(Math.max(offered, 0), BREAD_TARGET - breadSupplied);
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

    public boolean isSupplied() {
        return breadSupplied >= BREAD_TARGET;
    }

    public boolean matches(ResourceKey<Level> dimension, BlockPos pos) {
        return this.dimension.equals(dimension.location().toString()) && this.townHallPos == pos.asLong();
    }

    public String locationKey() {
        return locationKey(dimension, townHallPos);
    }

    public static String locationKey(ResourceKey<Level> dimension, BlockPos pos) {
        return locationKey(dimension.location().toString(), pos.asLong());
    }

    private static String locationKey(String dimension, long pos) {
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
}
