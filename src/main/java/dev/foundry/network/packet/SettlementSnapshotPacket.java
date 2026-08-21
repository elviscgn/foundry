package dev.foundry.network.packet;

import dev.foundry.client.ClientSettlementScreens;
import dev.foundry.settlement.Settlement;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

public record SettlementSnapshotPacket(
        int population,
        int workforce,
        int employed,
        int unemployed,
        int foodJobs,
        int foodEmployed,
        int constructionJobs,
        int constructionEmployed,
        int breadSupplied,
        int breadTarget,
        int dailyBreadConsumption,
        int buildingMaterialsSupplied,
        int buildingMaterialsTarget,
        int growthMaterialCost,
        int dailyGrowthAmount,
        int prosperity,
        boolean growthReady,
        List<HistoryPointSnapshot> history
) {
    public static SettlementSnapshotPacket from(Settlement settlement) {
        List<HistoryPointSnapshot> history = settlement.getHistory().stream()
                .map(point -> new HistoryPointSnapshot(
                        point.day(),
                        point.population(),
                        point.breadSupplied(),
                        point.breadTarget(),
                        point.buildingMaterialsSupplied(),
                        point.buildingMaterialsTarget(),
                        point.prosperity()
                ))
                .toList();

        return new SettlementSnapshotPacket(
                settlement.getPopulation(),
                settlement.getWorkforce(),
                settlement.getEmployed(),
                settlement.getUnemployed(),
                settlement.getFoodJobCapacity(),
                settlement.getFoodEmployed(),
                settlement.getConstructionJobCapacity(),
                settlement.getConstructionEmployed(),
                settlement.getBreadSupplied(),
                settlement.getBreadTarget(),
                settlement.getDailyBreadConsumption(),
                settlement.getBuildingMaterialsSupplied(),
                settlement.getBuildingMaterialsTarget(),
                settlement.getGrowthMaterialCost(),
                settlement.getDailyGrowthAmount(),
                settlement.getProsperity(),
                settlement.isGrowthReady(),
                history
        );
    }

    public static void encode(SettlementSnapshotPacket packet, FriendlyByteBuf buffer) {
        buffer.writeVarInt(packet.population);
        buffer.writeVarInt(packet.workforce);
        buffer.writeVarInt(packet.employed);
        buffer.writeVarInt(packet.unemployed);
        buffer.writeVarInt(packet.foodJobs);
        buffer.writeVarInt(packet.foodEmployed);
        buffer.writeVarInt(packet.constructionJobs);
        buffer.writeVarInt(packet.constructionEmployed);
        buffer.writeVarInt(packet.breadSupplied);
        buffer.writeVarInt(packet.breadTarget);
        buffer.writeVarInt(packet.dailyBreadConsumption);
        buffer.writeVarInt(packet.buildingMaterialsSupplied);
        buffer.writeVarInt(packet.buildingMaterialsTarget);
        buffer.writeVarInt(packet.growthMaterialCost);
        buffer.writeVarInt(packet.dailyGrowthAmount);
        buffer.writeVarInt(packet.prosperity);
        buffer.writeBoolean(packet.growthReady);
        buffer.writeVarInt(packet.history.size());

        for (HistoryPointSnapshot point : packet.history) {
            buffer.writeVarLong(point.day());
            buffer.writeVarInt(point.population());
            buffer.writeVarInt(point.breadSupplied());
            buffer.writeVarInt(point.breadTarget());
            buffer.writeVarInt(point.buildingMaterialsSupplied());
            buffer.writeVarInt(point.buildingMaterialsTarget());
            buffer.writeVarInt(point.prosperity());
        }
    }

    public static SettlementSnapshotPacket decode(FriendlyByteBuf buffer) {
        int population = buffer.readVarInt();
        int workforce = buffer.readVarInt();
        int employed = buffer.readVarInt();
        int unemployed = buffer.readVarInt();
        int foodJobs = buffer.readVarInt();
        int foodEmployed = buffer.readVarInt();
        int constructionJobs = buffer.readVarInt();
        int constructionEmployed = buffer.readVarInt();
        int breadSupplied = buffer.readVarInt();
        int breadTarget = buffer.readVarInt();
        int dailyBreadConsumption = buffer.readVarInt();
        int buildingMaterialsSupplied = buffer.readVarInt();
        int buildingMaterialsTarget = buffer.readVarInt();
        int growthMaterialCost = buffer.readVarInt();
        int dailyGrowthAmount = buffer.readVarInt();
        int prosperity = buffer.readVarInt();
        boolean growthReady = buffer.readBoolean();
        int historySize = Math.min(buffer.readVarInt(), Settlement.HISTORY_LIMIT);
        List<HistoryPointSnapshot> history = new ArrayList<>(historySize);

        for (int i = 0; i < historySize; i++) {
            history.add(new HistoryPointSnapshot(
                    buffer.readVarLong(),
                    buffer.readVarInt(),
                    buffer.readVarInt(),
                    buffer.readVarInt(),
                    buffer.readVarInt(),
                    buffer.readVarInt(),
                    buffer.readVarInt()
            ));
        }

        return new SettlementSnapshotPacket(
                population,
                workforce,
                employed,
                unemployed,
                foodJobs,
                foodEmployed,
                constructionJobs,
                constructionEmployed,
                breadSupplied,
                breadTarget,
                dailyBreadConsumption,
                buildingMaterialsSupplied,
                buildingMaterialsTarget,
                growthMaterialCost,
                dailyGrowthAmount,
                prosperity,
                growthReady,
                history
        );
    }

    public static void handle(SettlementSnapshotPacket packet, Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context context = contextSupplier.get();
        context.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(
                Dist.CLIENT,
                () -> () -> ClientSettlementScreens.open(packet)
        ));
        context.setPacketHandled(true);
    }

    public record HistoryPointSnapshot(
            long day,
            int population,
            int breadSupplied,
            int breadTarget,
            int buildingMaterialsSupplied,
            int buildingMaterialsTarget,
            int prosperity
    ) {
    }
}
