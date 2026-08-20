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
import java.util.Map;
import java.util.UUID;

public final class SettlementSavedData extends SavedData {
    private static final String DATA_NAME = "foundry_settlements";
    private static final String TAG_SETTLEMENTS = "Settlements";

    private final Map<UUID, Settlement> settlementsById = new HashMap<>();
    private final Map<String, UUID> settlementIdsByLocation = new HashMap<>();

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
        settlementsById.put(settlement.getId(), settlement);
        settlementIdsByLocation.put(locationKey, settlement.getId());
        setDirty();
        return settlement;
    }

    public void remove(ResourceKey<Level> dimension, BlockPos pos) {
        String locationKey = Settlement.locationKey(dimension, pos);
        UUID settlementId = settlementIdsByLocation.remove(locationKey);
        if (settlementId != null && settlementsById.remove(settlementId) != null) {
            setDirty();
        }
    }

    @Override
    public CompoundTag save(CompoundTag tag) {
        ListTag settlements = new ListTag();
        for (Settlement settlement : settlementsById.values()) {
            settlements.add(settlement.save());
        }
        tag.put(TAG_SETTLEMENTS, settlements);
        return tag;
    }
}
