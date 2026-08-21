package dev.foundry.settlement;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * Monetary state for one settlement.
 *
 * Kora is conserved by endogenous flows. Domestic trade transfers liquidity between local
 * economies, taxes transfer local liquidity into the treasury, and public works transfer
 * treasury money back into the local economy instead of deleting or minting currency.
 */
public final class SettlementFinance {
    public static final String CURRENCY_NAME = "Kora";
    public static final String CURRENCY_SYMBOL = "K";
    public static final String CURRENCY_CODE = "KRA";
    public static final int DEFAULT_COMMERCIAL_TAX_PERCENT = 10;

    private static final int INITIAL_LOCAL_KORA_PER_CAPITA = 20;
    private static final int INITIAL_TREASURY_KORA_PER_CAPITA = 5;
    private static final int BASE_BREAD_PRICE = 6;
    private static final int BASE_BRICK_PRICE = 12;

    private static final String TAG_SETTLEMENT_ID = "SettlementId";
    private static final String TAG_LOCAL_LIQUIDITY = "LocalLiquidity";
    private static final String TAG_TREASURY = "Treasury";
    private static final String TAG_COMMERCIAL_TAX_PERCENT = "CommercialTaxPercent";
    private static final String TAG_HISTORY = "History";

    private final UUID settlementId;
    private final List<FiscalPoint> history;
    private long localLiquidity;
    private long treasuryBalance;
    private int commercialTaxPercent;

    private SettlementFinance(UUID settlementId, long localLiquidity, long treasuryBalance,
                              int commercialTaxPercent, List<FiscalPoint> history) {
        this.settlementId = settlementId;
        this.localLiquidity = Math.max(0L, localLiquidity);
        this.treasuryBalance = Math.max(0L, treasuryBalance);
        this.commercialTaxPercent = clampTaxRate(commercialTaxPercent);
        this.history = new ArrayList<>(history);
        trimHistory();
    }

    public static SettlementFinance create(Settlement settlement) {
        long population = Math.max(1, settlement.getPopulation());
        return new SettlementFinance(
                settlement.getId(),
                population * INITIAL_LOCAL_KORA_PER_CAPITA,
                population * INITIAL_TREASURY_KORA_PER_CAPITA,
                DEFAULT_COMMERCIAL_TAX_PERCENT,
                List.of()
        );
    }

    public static SettlementFinance load(CompoundTag tag) {
        List<FiscalPoint> history = new ArrayList<>();
        ListTag historyTag = tag.getList(TAG_HISTORY, Tag.TAG_COMPOUND);
        for (int i = 0; i < historyTag.size(); i++) history.add(FiscalPoint.load(historyTag.getCompound(i)));
        int taxPercent = tag.contains(TAG_COMMERCIAL_TAX_PERCENT, Tag.TAG_INT)
                ? tag.getInt(TAG_COMMERCIAL_TAX_PERCENT) : DEFAULT_COMMERCIAL_TAX_PERCENT;
        return new SettlementFinance(tag.getUUID(TAG_SETTLEMENT_ID),
                Math.max(0L, tag.getLong(TAG_LOCAL_LIQUIDITY)),
                Math.max(0L, tag.getLong(TAG_TREASURY)), taxPercent, history);
    }

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putUUID(TAG_SETTLEMENT_ID, settlementId);
        tag.putLong(TAG_LOCAL_LIQUIDITY, localLiquidity);
        tag.putLong(TAG_TREASURY, treasuryBalance);
        tag.putInt(TAG_COMMERCIAL_TAX_PERCENT, commercialTaxPercent);
        ListTag historyTag = new ListTag();
        for (FiscalPoint point : history) historyTag.add(point.save());
        tag.put(TAG_HISTORY, historyTag);
        return tag;
    }

    public static int quoteUnitPrice(Settlement settlement, IndustryType type) {
        if (settlement == null || type == null) return 1;
        int basePrice;
        int supplied;
        int target;
        if (type == IndustryType.BAKERY) {
            basePrice = BASE_BREAD_PRICE;
            supplied = settlement.getBreadSupplied();
            target = settlement.getBreadTarget();
        } else {
            basePrice = BASE_BRICK_PRICE;
            supplied = settlement.getBuildingMaterialsSupplied();
            target = settlement.getBuildingMaterialsTarget();
        }
        target = Math.max(1, target);
        int shortage = Math.max(0, target - supplied);
        int shortagePremium = (basePrice * shortage + target - 1) / target;
        return Math.max(1, basePrice + shortagePremium);
    }

    public void ensureDay(long day) {
        if (!history.isEmpty() && day < history.get(history.size() - 1).day()) {
            resetHistory(day);
            return;
        }
        if (history.isEmpty()) {
            history.add(FiscalPoint.empty(day, localLiquidity, treasuryBalance));
            return;
        }
        long lastDay = history.get(history.size() - 1).day();
        if (lastDay == day) {
            refreshCurrentBalances(day);
            return;
        }
        long firstMissingDay = lastDay + 1L;
        if (day - firstMissingDay + 1L > Settlement.HISTORY_LIMIT) {
            history.clear();
            firstMissingDay = day - Settlement.HISTORY_LIMIT + 1L;
        }
        for (long missingDay = firstMissingDay; missingDay <= day; missingDay++) {
            history.add(FiscalPoint.empty(missingDay, localLiquidity, treasuryBalance));
        }
        trimHistory();
    }

    public void resetHistory(long day) {
        history.clear();
        history.add(FiscalPoint.empty(day, localLiquidity, treasuryBalance));
    }

    public void recordImport(long day, long value) {
        if (value <= 0L) return;
        ensureDay(day);
        int index = history.size() - 1;
        FiscalPoint point = history.get(index);
        history.set(index, new FiscalPoint(day, localLiquidity, treasuryBalance,
                point.importValue() + value, point.exportValue(), point.taxRevenue(), point.publicSpending()));
    }

    public void recordExport(long day, long value, long taxRevenue) {
        if (value <= 0L) return;
        ensureDay(day);
        int index = history.size() - 1;
        FiscalPoint point = history.get(index);
        history.set(index, new FiscalPoint(day, localLiquidity, treasuryBalance,
                point.importValue(), point.exportValue() + value,
                point.taxRevenue() + Math.max(0L, taxRevenue), point.publicSpending()));
    }

    public void recordPublicSpending(long day, long amount) {
        if (amount <= 0L) return;
        ensureDay(day);
        int index = history.size() - 1;
        FiscalPoint point = history.get(index);
        history.set(index, new FiscalPoint(day, localLiquidity, treasuryBalance,
                point.importValue(), point.exportValue(), point.taxRevenue(), point.publicSpending() + amount));
    }

    /**
     * Pays a municipal project from the treasury into the settlement's private/local economy.
     * This is a transfer, not money destruction: total Kora before and after is identical.
     */
    public boolean spendTreasuryIntoLocalEconomy(long day, long amount) {
        if (amount <= 0L) return true;
        if (treasuryBalance < amount) return false;
        long before = getTotalMoney();
        treasuryBalance -= amount;
        localLiquidity += amount;
        recordPublicSpending(day, amount);
        refreshCurrentBalances(day);
        if (getTotalMoney() != before) {
            throw new IllegalStateException("Kora conservation invariant violated during public spending");
        }
        return true;
    }

    private void refreshCurrentBalances(long day) {
        if (history.isEmpty() || history.get(history.size() - 1).day() != day) return;
        int index = history.size() - 1;
        FiscalPoint point = history.get(index);
        history.set(index, new FiscalPoint(point.day(), localLiquidity, treasuryBalance,
                point.importValue(), point.exportValue(), point.taxRevenue(), point.publicSpending()));
    }

    private void trimHistory() {
        while (history.size() > Settlement.HISTORY_LIMIT) history.remove(0);
    }

    public long calculateCommercialTax(long grossValue) {
        if (grossValue <= 0L || commercialTaxPercent <= 0) return 0L;
        return grossValue * commercialTaxPercent / 100L;
    }

    public boolean canDebitLocal(long amount) { return amount >= 0L && localLiquidity >= amount; }

    public boolean debitLocal(long amount) {
        if (!canDebitLocal(amount)) return false;
        localLiquidity -= amount;
        return true;
    }

    public void creditLocal(long amount) {
        if (amount > 0L) localLiquidity += amount;
    }

    public boolean moveLocalToTreasury(long amount) {
        if (amount <= 0L) return true;
        if (!debitLocal(amount)) return false;
        treasuryBalance += amount;
        return true;
    }

    public long getTotalMoney() { return localLiquidity + treasuryBalance; }
    public UUID getSettlementId() { return settlementId; }
    public long getLocalLiquidity() { return localLiquidity; }
    public long getTreasuryBalance() { return treasuryBalance; }
    public int getCommercialTaxPercent() { return commercialTaxPercent; }
    public long getImportValueToday() { return history.isEmpty() ? 0L : history.get(history.size() - 1).importValue(); }
    public long getExportValueToday() { return history.isEmpty() ? 0L : history.get(history.size() - 1).exportValue(); }
    public long getTaxRevenueToday() { return history.isEmpty() ? 0L : history.get(history.size() - 1).taxRevenue(); }
    public long getPublicSpendingToday() { return history.isEmpty() ? 0L : history.get(history.size() - 1).publicSpending(); }
    public long getTradeBalanceToday() { return getExportValueToday() - getImportValueToday(); }
    public long getBudgetBalanceToday() { return getTaxRevenueToday() - getPublicSpendingToday(); }
    public long getImportValueAverage(int days) { return average(days, 0); }
    public long getExportValueAverage(int days) { return average(days, 1); }
    public long getTaxRevenueAverage(int days) { return average(days, 2); }
    public long getPublicSpendingAverage(int days) { return average(days, 3); }

    private long average(int days, int metric) {
        if (days <= 0 || history.isEmpty()) return 0L;
        int count = Math.min(days, history.size());
        long total = 0L;
        for (int i = history.size() - count; i < history.size(); i++) {
            FiscalPoint point = history.get(i);
            total += switch (metric) {
                case 0 -> point.importValue();
                case 1 -> point.exportValue();
                case 2 -> point.taxRevenue();
                default -> point.publicSpending();
            };
        }
        return (total + count / 2L) / count;
    }

    public List<FiscalPoint> getHistory() { return Collections.unmodifiableList(history); }
    private static int clampTaxRate(int value) { return Math.max(0, Math.min(100, value)); }

    public record FiscalPoint(long day, long localLiquidity, long treasuryBalance, long importValue,
                              long exportValue, long taxRevenue, long publicSpending) {
        private static final String TAG_DAY = "Day";
        private static final String TAG_LOCAL_LIQUIDITY = "LocalLiquidity";
        private static final String TAG_TREASURY = "Treasury";
        private static final String TAG_IMPORT_VALUE = "ImportValue";
        private static final String TAG_EXPORT_VALUE = "ExportValue";
        private static final String TAG_TAX_REVENUE = "TaxRevenue";
        private static final String TAG_PUBLIC_SPENDING = "PublicSpending";

        static FiscalPoint empty(long day, long localLiquidity, long treasuryBalance) {
            return new FiscalPoint(day, localLiquidity, treasuryBalance, 0L, 0L, 0L, 0L);
        }

        static FiscalPoint load(CompoundTag tag) {
            return new FiscalPoint(tag.getLong(TAG_DAY), Math.max(0L, tag.getLong(TAG_LOCAL_LIQUIDITY)),
                    Math.max(0L, tag.getLong(TAG_TREASURY)), Math.max(0L, tag.getLong(TAG_IMPORT_VALUE)),
                    Math.max(0L, tag.getLong(TAG_EXPORT_VALUE)), Math.max(0L, tag.getLong(TAG_TAX_REVENUE)),
                    Math.max(0L, tag.getLong(TAG_PUBLIC_SPENDING)));
        }

        CompoundTag save() {
            CompoundTag tag = new CompoundTag();
            tag.putLong(TAG_DAY, day);
            tag.putLong(TAG_LOCAL_LIQUIDITY, localLiquidity);
            tag.putLong(TAG_TREASURY, treasuryBalance);
            tag.putLong(TAG_IMPORT_VALUE, importValue);
            tag.putLong(TAG_EXPORT_VALUE, exportValue);
            tag.putLong(TAG_TAX_REVENUE, taxRevenue);
            tag.putLong(TAG_PUBLIC_SPENDING, publicSpending);
            return tag;
        }
    }
}
