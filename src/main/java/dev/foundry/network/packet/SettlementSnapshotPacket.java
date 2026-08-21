package dev.foundry.network.packet;

import dev.foundry.client.ClientSettlementScreens;
import dev.foundry.settlement.IndustryType;
import dev.foundry.settlement.Settlement;
import dev.foundry.settlement.SettlementFinance;
import dev.foundry.settlement.SettlementSavedData;
import dev.foundry.settlement.SettlementTier;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

public record SettlementSnapshotPacket(
        String settlementTier,
        int claimRadius,
        String currencyName,
        String currencySymbol,
        String currencyCode,
        long localLiquidity,
        long treasuryBalance,
        int commercialTaxPercent,
        int breadUnitPrice,
        int brickUnitPrice,
        long importValueToday,
        long exportValueToday,
        long tradeBalanceToday,
        long taxRevenueToday,
        long publicSpendingToday,
        long budgetBalanceToday,
        long importValueAverage7d,
        long exportValueAverage7d,
        long taxRevenueAverage7d,
        long publicSpendingAverage7d,
        int population,
        int workforce,
        int employed,
        int unemployed,
        int foodJobs,
        int foodEmployed,
        int constructionJobs,
        int constructionEmployed,
        String laborPriority,
        int foodOutputToday,
        int foodOutputAverage7d,
        int constructionOutputToday,
        int constructionOutputAverage7d,
        int breadImportsToday,
        int breadExportsToday,
        int brickImportsToday,
        int brickExportsToday,
        int breadImportsAverage7d,
        int breadExportsAverage7d,
        int brickImportsAverage7d,
        int brickExportsAverage7d,
        int breadSupplied,
        int breadTarget,
        int dailyBreadConsumption,
        int buildingMaterialsSupplied,
        int buildingMaterialsTarget,
        int growthMaterialCost,
        int dailyGrowthAmount,
        int prosperity,
        boolean growthReady,
        List<HistoryPointSnapshot> history,
        List<FinancePointSnapshot> financeHistory
) {
    public static SettlementSnapshotPacket from(Settlement settlement, SettlementSavedData data) {
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

        SettlementFinance finance = data.getFinance(settlement);
        List<FinancePointSnapshot> financeHistory = finance == null
                ? List.of()
                : finance.getHistory().stream()
                .map(point -> new FinancePointSnapshot(
                        point.day(),
                        point.localLiquidity(),
                        point.treasuryBalance(),
                        point.importValue(),
                        point.exportValue(),
                        point.taxRevenue(),
                        point.publicSpending()
                ))
                .toList();
        SettlementTier tier = data.getSettlementTier(settlement);

        return new SettlementSnapshotPacket(
                tier.displayName(),
                tier.claimRadius(),
                SettlementFinance.CURRENCY_NAME,
                SettlementFinance.CURRENCY_SYMBOL,
                SettlementFinance.CURRENCY_CODE,
                finance == null ? 0L : finance.getLocalLiquidity(),
                finance == null ? 0L : finance.getTreasuryBalance(),
                finance == null ? SettlementFinance.DEFAULT_COMMERCIAL_TAX_PERCENT : finance.getCommercialTaxPercent(),
                data.getCommodityUnitPrice(settlement, IndustryType.BAKERY),
                data.getCommodityUnitPrice(settlement, IndustryType.BRICKWORKS),
                finance == null ? 0L : finance.getImportValueToday(),
                finance == null ? 0L : finance.getExportValueToday(),
                finance == null ? 0L : finance.getTradeBalanceToday(),
                finance == null ? 0L : finance.getTaxRevenueToday(),
                finance == null ? 0L : finance.getPublicSpendingToday(),
                finance == null ? 0L : finance.getBudgetBalanceToday(),
                finance == null ? 0L : finance.getImportValueAverage(7),
                finance == null ? 0L : finance.getExportValueAverage(7),
                finance == null ? 0L : finance.getTaxRevenueAverage(7),
                finance == null ? 0L : finance.getPublicSpendingAverage(7),
                settlement.getPopulation(),
                settlement.getWorkforce(),
                settlement.getEmployed(),
                settlement.getUnemployed(),
                settlement.getFoodJobCapacity(),
                settlement.getFoodEmployed(),
                settlement.getConstructionJobCapacity(),
                settlement.getConstructionEmployed(),
                settlement.getLaborPriority().displayName(),
                settlement.getFoodOutputToday(),
                settlement.getFoodOutputAverage(7),
                settlement.getConstructionOutputToday(),
                settlement.getConstructionOutputAverage(7),
                settlement.getBreadImportsToday(),
                settlement.getBreadExportsToday(),
                settlement.getBrickImportsToday(),
                settlement.getBrickExportsToday(),
                settlement.getBreadImportsAverage(7),
                settlement.getBreadExportsAverage(7),
                settlement.getBrickImportsAverage(7),
                settlement.getBrickExportsAverage(7),
                settlement.getBreadSupplied(),
                settlement.getBreadTarget(),
                settlement.getDailyBreadConsumption(),
                settlement.getBuildingMaterialsSupplied(),
                settlement.getBuildingMaterialsTarget(),
                settlement.getGrowthMaterialCost(),
                settlement.getDailyGrowthAmount(),
                settlement.getProsperity(),
                settlement.isGrowthReady(),
                history,
                financeHistory
        );
    }

    public static void encode(SettlementSnapshotPacket packet, FriendlyByteBuf buffer) {
        buffer.writeUtf(packet.settlementTier, 32);
        buffer.writeVarInt(packet.claimRadius);
        buffer.writeUtf(packet.currencyName, 32);
        buffer.writeUtf(packet.currencySymbol, 8);
        buffer.writeUtf(packet.currencyCode, 8);
        buffer.writeLong(packet.localLiquidity);
        buffer.writeLong(packet.treasuryBalance);
        buffer.writeVarInt(packet.commercialTaxPercent);
        buffer.writeVarInt(packet.breadUnitPrice);
        buffer.writeVarInt(packet.brickUnitPrice);
        buffer.writeLong(packet.importValueToday);
        buffer.writeLong(packet.exportValueToday);
        buffer.writeLong(packet.tradeBalanceToday);
        buffer.writeLong(packet.taxRevenueToday);
        buffer.writeLong(packet.publicSpendingToday);
        buffer.writeLong(packet.budgetBalanceToday);
        buffer.writeLong(packet.importValueAverage7d);
        buffer.writeLong(packet.exportValueAverage7d);
        buffer.writeLong(packet.taxRevenueAverage7d);
        buffer.writeLong(packet.publicSpendingAverage7d);
        buffer.writeVarInt(packet.population);
        buffer.writeVarInt(packet.workforce);
        buffer.writeVarInt(packet.employed);
        buffer.writeVarInt(packet.unemployed);
        buffer.writeVarInt(packet.foodJobs);
        buffer.writeVarInt(packet.foodEmployed);
        buffer.writeVarInt(packet.constructionJobs);
        buffer.writeVarInt(packet.constructionEmployed);
        buffer.writeUtf(packet.laborPriority, 32);
        buffer.writeVarInt(packet.foodOutputToday);
        buffer.writeVarInt(packet.foodOutputAverage7d);
        buffer.writeVarInt(packet.constructionOutputToday);
        buffer.writeVarInt(packet.constructionOutputAverage7d);
        buffer.writeVarInt(packet.breadImportsToday);
        buffer.writeVarInt(packet.breadExportsToday);
        buffer.writeVarInt(packet.brickImportsToday);
        buffer.writeVarInt(packet.brickExportsToday);
        buffer.writeVarInt(packet.breadImportsAverage7d);
        buffer.writeVarInt(packet.breadExportsAverage7d);
        buffer.writeVarInt(packet.brickImportsAverage7d);
        buffer.writeVarInt(packet.brickExportsAverage7d);
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

        buffer.writeVarInt(packet.financeHistory.size());
        for (FinancePointSnapshot point : packet.financeHistory) {
            buffer.writeVarLong(point.day());
            buffer.writeLong(point.localLiquidity());
            buffer.writeLong(point.treasuryBalance());
            buffer.writeLong(point.importValue());
            buffer.writeLong(point.exportValue());
            buffer.writeLong(point.taxRevenue());
            buffer.writeLong(point.publicSpending());
        }
    }

    public static SettlementSnapshotPacket decode(FriendlyByteBuf buffer) {
        String settlementTier = buffer.readUtf(32);
        int claimRadius = buffer.readVarInt();
        String currencyName = buffer.readUtf(32);
        String currencySymbol = buffer.readUtf(8);
        String currencyCode = buffer.readUtf(8);
        long localLiquidity = buffer.readLong();
        long treasuryBalance = buffer.readLong();
        int commercialTaxPercent = buffer.readVarInt();
        int breadUnitPrice = buffer.readVarInt();
        int brickUnitPrice = buffer.readVarInt();
        long importValueToday = buffer.readLong();
        long exportValueToday = buffer.readLong();
        long tradeBalanceToday = buffer.readLong();
        long taxRevenueToday = buffer.readLong();
        long publicSpendingToday = buffer.readLong();
        long budgetBalanceToday = buffer.readLong();
        long importValueAverage7d = buffer.readLong();
        long exportValueAverage7d = buffer.readLong();
        long taxRevenueAverage7d = buffer.readLong();
        long publicSpendingAverage7d = buffer.readLong();
        int population = buffer.readVarInt();
        int workforce = buffer.readVarInt();
        int employed = buffer.readVarInt();
        int unemployed = buffer.readVarInt();
        int foodJobs = buffer.readVarInt();
        int foodEmployed = buffer.readVarInt();
        int constructionJobs = buffer.readVarInt();
        int constructionEmployed = buffer.readVarInt();
        String laborPriority = buffer.readUtf(32);
        int foodOutputToday = buffer.readVarInt();
        int foodOutputAverage7d = buffer.readVarInt();
        int constructionOutputToday = buffer.readVarInt();
        int constructionOutputAverage7d = buffer.readVarInt();
        int breadImportsToday = buffer.readVarInt();
        int breadExportsToday = buffer.readVarInt();
        int brickImportsToday = buffer.readVarInt();
        int brickExportsToday = buffer.readVarInt();
        int breadImportsAverage7d = buffer.readVarInt();
        int breadExportsAverage7d = buffer.readVarInt();
        int brickImportsAverage7d = buffer.readVarInt();
        int brickExportsAverage7d = buffer.readVarInt();
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

        int financeHistorySize = Math.min(buffer.readVarInt(), Settlement.HISTORY_LIMIT);
        List<FinancePointSnapshot> financeHistory = new ArrayList<>(financeHistorySize);
        for (int i = 0; i < financeHistorySize; i++) {
            financeHistory.add(new FinancePointSnapshot(
                    buffer.readVarLong(),
                    buffer.readLong(),
                    buffer.readLong(),
                    buffer.readLong(),
                    buffer.readLong(),
                    buffer.readLong(),
                    buffer.readLong()
            ));
        }

        return new SettlementSnapshotPacket(
                settlementTier,
                claimRadius,
                currencyName,
                currencySymbol,
                currencyCode,
                localLiquidity,
                treasuryBalance,
                commercialTaxPercent,
                breadUnitPrice,
                brickUnitPrice,
                importValueToday,
                exportValueToday,
                tradeBalanceToday,
                taxRevenueToday,
                publicSpendingToday,
                budgetBalanceToday,
                importValueAverage7d,
                exportValueAverage7d,
                taxRevenueAverage7d,
                publicSpendingAverage7d,
                population,
                workforce,
                employed,
                unemployed,
                foodJobs,
                foodEmployed,
                constructionJobs,
                constructionEmployed,
                laborPriority,
                foodOutputToday,
                foodOutputAverage7d,
                constructionOutputToday,
                constructionOutputAverage7d,
                breadImportsToday,
                breadExportsToday,
                brickImportsToday,
                brickExportsToday,
                breadImportsAverage7d,
                breadExportsAverage7d,
                brickImportsAverage7d,
                brickExportsAverage7d,
                breadSupplied,
                breadTarget,
                dailyBreadConsumption,
                buildingMaterialsSupplied,
                buildingMaterialsTarget,
                growthMaterialCost,
                dailyGrowthAmount,
                prosperity,
                growthReady,
                history,
                financeHistory
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

    public record FinancePointSnapshot(
            long day,
            long localLiquidity,
            long treasuryBalance,
            long importValue,
            long exportValue,
            long taxRevenue,
            long publicSpending
    ) {
        public long tradeBalance() {
            return exportValue - importValue;
        }

        public long budgetBalance() {
            return taxRevenue - publicSpending;
        }
    }
}
