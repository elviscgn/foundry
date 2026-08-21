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
        int breadSupplied,
        int breadTarget,
        int dailyBreadConsumption,
        int prosperity,
        List<HistoryPointSnapshot> history
) {
    public static SettlementSnapshotPacket from(Settlement settlement) {
        List<HistoryPointSnapshot> history = settlement.getHistory().stream()
                .map(point -> new HistoryPointSnapshot(
                        point.day(),
                        point.population(),
                        point.breadSupplied(),
                        point.breadTarget(),
                        point.prosperity()
                ))
                .toList();

        return new SettlementSnapshotPacket(
                settlement.getPopulation(),
                settlement.getBreadSupplied(),
                settlement.getBreadTarget(),
                settlement.getDailyBreadConsumption(),
                settlement.getProsperity(),
                history
        );
    }

    public static void encode(SettlementSnapshotPacket packet, FriendlyByteBuf buffer) {
        buffer.writeVarInt(packet.population);
        buffer.writeVarInt(packet.breadSupplied);
        buffer.writeVarInt(packet.breadTarget);
        buffer.writeVarInt(packet.dailyBreadConsumption);
        buffer.writeVarInt(packet.prosperity);
        buffer.writeVarInt(packet.history.size());

        for (HistoryPointSnapshot point : packet.history) {
            buffer.writeVarLong(point.day());
            buffer.writeVarInt(point.population());
            buffer.writeVarInt(point.breadSupplied());
            buffer.writeVarInt(point.breadTarget());
            buffer.writeVarInt(point.prosperity());
        }
    }

    public static SettlementSnapshotPacket decode(FriendlyByteBuf buffer) {
        int population = buffer.readVarInt();
        int breadSupplied = buffer.readVarInt();
        int breadTarget = buffer.readVarInt();
        int dailyBreadConsumption = buffer.readVarInt();
        int prosperity = buffer.readVarInt();
        int historySize = Math.min(buffer.readVarInt(), Settlement.HISTORY_LIMIT);
        List<HistoryPointSnapshot> history = new ArrayList<>(historySize);

        for (int i = 0; i < historySize; i++) {
            history.add(new HistoryPointSnapshot(
                    buffer.readVarLong(),
                    buffer.readVarInt(),
                    buffer.readVarInt(),
                    buffer.readVarInt(),
                    buffer.readVarInt()
            ));
        }

        return new SettlementSnapshotPacket(
                population,
                breadSupplied,
                breadTarget,
                dailyBreadConsumption,
                prosperity,
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
            int prosperity
    ) {
    }
}
