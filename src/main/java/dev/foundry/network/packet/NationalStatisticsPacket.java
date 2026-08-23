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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Country-level statistical return produced by the National Statistics Bureau.
 *
 * GTP is deliberately a measured, constant-price production statistic rather than a money flow:
 * current Foundry physical output is valued at the canonical base prices (Bread K6, Bricks K12).
 * It does not credit anyone, debit anyone, or mint Kora. As more sectors become physical, they can
 * be added to this accounting identity without changing the monetary constitution.
 */
public record NationalStatisticsPacket(List<Entry> settlements, List<NationalPoint> history) {
    private static final int GTP_BREAD_BASE_PRICE = 6;
    private static final int GTP_BRICK_BASE_PRICE = 12;

    public static NationalStatisticsPacket from(SettlementSavedData data) {
        List<Entry> rows = data.getSettlements().stream().map(settlement -> {
            SettlementTier tier = data.getSettlementTier(settlement);
            SettlementFinance finance = data.getFinance(settlement);
            long tradeBalance7d = finance.getExportValueAverage(7) - finance.getImportValueAverage(7);
            int physicalOutput7d = settlement.getFoodOutputAverage(7) + settlement.getConstructionOutputAverage(7);
            long gtp7d = measuredGtpAverage(settlement, 7);
            return new Entry(
                    settlement.getId(),
                    SettlementIdentity.registryLabel(settlement, tier),
                    tier.displayName(),
                    settlement.getProsperity(),
                    settlement.getPopulation(),
                    settlement.getEmployed(),
                    settlement.getTotalJobCapacity(),
                    physicalOutput7d,
                    gtp7d,
                    tradeBalance7d,
                    finance.getTreasuryBalance(),
                    data.getCommissionedWarehouseCount(settlement.getId())
            );
        }).sorted(Comparator.comparingLong(Entry::gtp7d).reversed()
                .thenComparing(Comparator.comparingInt(Entry::prosperity).reversed())
                .thenComparing(Comparator.comparingInt(Entry::population).reversed())
                .thenComparing(Entry::name))
                .toList();

        return new NationalStatisticsPacket(rows, buildNationalHistory(data));
    }

    private static long measuredGtpAverage(Settlement settlement, int days) {
        List<Settlement.ProductionPoint> production = settlement.getProductionHistory();
        if (production.isEmpty() || days <= 0) return 0L;
        int count = Math.min(days, production.size());
        long total = 0L;
        for (int i = production.size() - count; i < production.size(); i++) {
            total += measuredGtp(production.get(i));
        }
        return (total + count / 2L) / count;
    }

    private static long measuredGtp(Settlement.ProductionPoint point) {
        return (long) point.foodOutput() * GTP_BREAD_BASE_PRICE
                + (long) point.constructionOutput() * GTP_BRICK_BASE_PRICE;
    }

    private static List<NationalPoint> buildNationalHistory(SettlementSavedData data) {
        TreeMap<Long, NationalAccumulator> days = new TreeMap<>();

        for (Settlement settlement : data.getSettlements()) {
            Map<Long, Settlement.ProductionPoint> productionByDay = new HashMap<>();
            for (Settlement.ProductionPoint point : settlement.getProductionHistory()) {
                productionByDay.put(point.day(), point);
            }

            SettlementFinance finance = data.getFinance(settlement);
            Map<Long, SettlementFinance.FiscalPoint> financeByDay = new HashMap<>();
            for (SettlementFinance.FiscalPoint point : finance.getHistory()) {
                financeByDay.put(point.day(), point);
            }

            for (Settlement.HistoryPoint point : settlement.getHistory()) {
                NationalAccumulator accumulator = days.computeIfAbsent(point.day(), ignored -> new NationalAccumulator());
                accumulator.population += point.population();
                accumulator.development += point.prosperity();
                accumulator.settlements++;

                Settlement.ProductionPoint production = productionByDay.get(point.day());
                if (production != null) accumulator.gtp += measuredGtp(production);

                SettlementFinance.FiscalPoint fiscal = financeByDay.get(point.day());
                if (fiscal != null) {
                    accumulator.tradeBalance += fiscal.exportValue() - fiscal.importValue();
                    accumulator.municipalTreasuries += fiscal.treasuryBalance();
                }
            }
        }

        List<NationalPoint> result = new ArrayList<>();
        for (Map.Entry<Long, NationalAccumulator> entry : days.entrySet()) {
            NationalAccumulator value = entry.getValue();
            int averageDevelopment = value.settlements <= 0
                    ? 0
                    : (int) Math.round((double) value.development / value.settlements);
            result.add(new NationalPoint(
                    entry.getKey(),
                    value.gtp,
                    value.population,
                    averageDevelopment,
                    value.tradeBalance,
                    value.municipalTreasuries
            ));
        }

        if (result.size() > Settlement.HISTORY_LIMIT) {
            return List.copyOf(result.subList(result.size() - Settlement.HISTORY_LIMIT, result.size()));
        }
        return List.copyOf(result);
    }

    public static void encode(NationalStatisticsPacket packet, FriendlyByteBuf buffer) {
        buffer.writeVarInt(packet.settlements.size());
        for (Entry entry : packet.settlements) {
            buffer.writeUUID(entry.settlementId);
            buffer.writeUtf(entry.name, 64);
            buffer.writeUtf(entry.tier, 16);
            buffer.writeVarInt(entry.prosperity);
            buffer.writeVarInt(entry.population);
            buffer.writeVarInt(entry.employed);
            buffer.writeVarInt(entry.jobs);
            buffer.writeVarInt(entry.physicalOutput7d);
            buffer.writeLong(entry.gtp7d);
            buffer.writeLong(entry.tradeBalance7d);
            buffer.writeLong(entry.treasury);
            buffer.writeVarInt(entry.warehouses);
        }

        buffer.writeVarInt(packet.history.size());
        for (NationalPoint point : packet.history) {
            buffer.writeVarLong(point.day);
            buffer.writeLong(point.gtp);
            buffer.writeLong(point.population);
            buffer.writeVarInt(point.averageDevelopment);
            buffer.writeLong(point.tradeBalance);
            buffer.writeLong(point.municipalTreasuries);
        }
    }

    public static NationalStatisticsPacket decode(FriendlyByteBuf buffer) {
        int size = Math.min(buffer.readVarInt(), 512);
        List<Entry> rows = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            rows.add(new Entry(
                    buffer.readUUID(),
                    buffer.readUtf(64),
                    buffer.readUtf(16),
                    buffer.readVarInt(),
                    buffer.readVarInt(),
                    buffer.readVarInt(),
                    buffer.readVarInt(),
                    buffer.readVarInt(),
                    buffer.readLong(),
                    buffer.readLong(),
                    buffer.readLong(),
                    buffer.readVarInt()
            ));
        }

        int historySize = Math.min(buffer.readVarInt(), Settlement.HISTORY_LIMIT);
        List<NationalPoint> history = new ArrayList<>(historySize);
        for (int i = 0; i < historySize; i++) {
            history.add(new NationalPoint(
                    buffer.readVarLong(),
                    buffer.readLong(),
                    buffer.readLong(),
                    buffer.readVarInt(),
                    buffer.readLong(),
                    buffer.readLong()
            ));
        }
        return new NationalStatisticsPacket(rows, history);
    }

    public static void handle(NationalStatisticsPacket packet, Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context context = contextSupplier.get();
        context.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                () -> () -> ClientSettlementScreens.openNational(packet)));
        context.setPacketHandled(true);
    }

    public record Entry(UUID settlementId, String name, String tier, int prosperity, int population, int employed, int jobs,
                        int physicalOutput7d, long gtp7d, long tradeBalance7d, long treasury, int warehouses) { }

    public record NationalPoint(long day, long gtp, long population, int averageDevelopment,
                                long tradeBalance, long municipalTreasuries) { }

    private static final class NationalAccumulator {
        private long gtp;
        private long population;
        private long development;
        private int settlements;
        private long tradeBalance;
        private long municipalTreasuries;
    }
}
