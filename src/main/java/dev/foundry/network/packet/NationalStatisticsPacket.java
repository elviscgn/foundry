package dev.foundry.network.packet;

import dev.foundry.client.ClientSettlementScreens;
import dev.foundry.settlement.Settlement;
import dev.foundry.settlement.SettlementFinance;
import dev.foundry.settlement.SettlementIdentity;
import dev.foundry.settlement.SettlementSavedData;
import dev.foundry.settlement.SettlementTier;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Supplier;

public record NationalStatisticsPacket(List<Entry> settlements) {
    public static NationalStatisticsPacket from(SettlementSavedData data) {
        List<Entry> rows = data.getSettlements().stream().map(settlement -> {
            SettlementTier tier = data.getSettlementTier(settlement);
            SettlementFinance finance = data.getFinance(settlement);
            long tradeBalance7d = finance.getExportValueAverage(7) - finance.getImportValueAverage(7);
            int production7d = settlement.getFoodOutputAverage(7) + settlement.getConstructionOutputAverage(7);
            return new Entry(
                    SettlementIdentity.registryLabel(settlement, tier),
                    tier.displayName(),
                    settlement.getProsperity(),
                    settlement.getPopulation(),
                    settlement.getEmployed(),
                    settlement.getTotalJobCapacity(),
                    production7d,
                    tradeBalance7d,
                    finance.getTreasuryBalance(),
                    data.getCommissionedWarehouseCount(settlement.getId())
            );
        }).sorted(Comparator.comparingInt(Entry::prosperity).reversed()
                .thenComparing(Comparator.comparingInt(Entry::population).reversed())
                .thenComparing(Entry::name))
                .toList();
        return new NationalStatisticsPacket(rows);
    }

    public static void encode(NationalStatisticsPacket packet, FriendlyByteBuf buffer) {
        buffer.writeVarInt(packet.settlements.size());
        for (Entry entry : packet.settlements) {
            buffer.writeUtf(entry.name, 64);
            buffer.writeUtf(entry.tier, 16);
            buffer.writeVarInt(entry.prosperity);
            buffer.writeVarInt(entry.population);
            buffer.writeVarInt(entry.employed);
            buffer.writeVarInt(entry.jobs);
            buffer.writeVarInt(entry.production7d);
            buffer.writeLong(entry.tradeBalance7d);
            buffer.writeLong(entry.treasury);
            buffer.writeVarInt(entry.warehouses);
        }
    }

    public static NationalStatisticsPacket decode(FriendlyByteBuf buffer) {
        int size = Math.min(buffer.readVarInt(), 512);
        List<Entry> rows = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            rows.add(new Entry(
                    buffer.readUtf(64),
                    buffer.readUtf(16),
                    buffer.readVarInt(),
                    buffer.readVarInt(),
                    buffer.readVarInt(),
                    buffer.readVarInt(),
                    buffer.readVarInt(),
                    buffer.readLong(),
                    buffer.readLong(),
                    buffer.readVarInt()
            ));
        }
        return new NationalStatisticsPacket(rows);
    }

    public static void handle(NationalStatisticsPacket packet, Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context context = contextSupplier.get();
        context.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                () -> () -> ClientSettlementScreens.openNational(packet)));
        context.setPacketHandled(true);
    }

    public record Entry(String name, String tier, int prosperity, int population, int employed, int jobs,
                        int production7d, long tradeBalance7d, long treasury, int warehouses) { }
}
