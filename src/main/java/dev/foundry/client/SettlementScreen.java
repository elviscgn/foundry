package dev.foundry.client;

import dev.foundry.network.packet.SettlementSnapshotPacket;
import dev.foundry.network.packet.SettlementSnapshotPacket.FinancePointSnapshot;
import dev.foundry.network.packet.SettlementSnapshotPacket.HistoryPointSnapshot;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

import java.util.List;
import java.util.Locale;
import java.util.function.ToIntFunction;
import java.util.function.ToLongFunction;

@OnlyIn(Dist.CLIENT)
public final class SettlementScreen extends Screen {
    private static final int PANEL_BG = 0xF0181816;
    private static final int PANEL_BORDER = 0xFF7A6441;
    private static final int GRAPH_BG = 0xFF10100F;
    private static final int TEXT = 0xFFE8E0CF;
    private static final int MUTED_TEXT = 0xFF9C9588;
    private static final int GOLD = 0xFFD6A84B;
    private static final int GREEN = 0xFF6FAF73;
    private static final int RED = 0xFFC76B5D;
    private static final int STEEL = 0xFF7F9BA5;

    private final SettlementSnapshotPacket snapshot;

    public SettlementScreen(SettlementSnapshotPacket snapshot) {
        super(Component.literal("Foundry Settlement Ledger"));
        this.snapshot = snapshot;
    }

    @Override
    public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(guiGraphics);

        int panelWidth = Math.min(760, Math.max(1, width - 20));
        int panelHeight = Math.min(500, Math.max(1, height - 20));
        int panelX = (width - panelWidth) / 2;
        int panelY = (height - panelHeight) / 2;
        int padding = panelWidth < 460 ? 10 : 14;
        int innerX = panelX + padding;
        int innerWidth = Math.max(1, panelWidth - padding * 2);

        guiGraphics.fill(panelX, panelY, panelX + panelWidth, panelY + panelHeight, PANEL_BORDER);
        guiGraphics.fill(panelX + 1, panelY + 1, panelX + panelWidth - 1, panelY + panelHeight - 1, PANEL_BG);

        int titleY = panelY + 12;
        String title = innerWidth < 360
                ? "FOUNDRY // CIVIC LEDGER"
                : "FOUNDRY // " + snapshot.settlementTier().toUpperCase(Locale.ROOT) + " CIVIC LEDGER";
        guiGraphics.drawString(font, Component.literal(title), innerX, titleY, GOLD);

        String economyState = economyState();
        int stateColor = economyStateColor();
        int stateX = panelX + panelWidth - padding - font.width(economyState);
        if (stateX > innerX + font.width(title) + 8) {
            guiGraphics.drawString(font, Component.literal(economyState), stateX, titleY, stateColor);
        }

        int statsY = panelY + 31;
        boolean compactStats = innerWidth < 560;
        int statsHeight = drawStats(guiGraphics, innerX, innerWidth, statsY, compactStats);

        int contentTop = statsY + statsHeight + 8;
        int contentBottom = panelY + panelHeight - padding;
        int availableHeight = Math.max(1, contentBottom - contentTop);

        if (innerWidth >= 560 && availableHeight >= 190) {
            drawWideGraphs(guiGraphics, innerX, innerWidth, contentTop, contentBottom);
        } else {
            drawCompactGraphs(guiGraphics, innerX, innerWidth, contentTop, contentBottom);
        }

        super.render(guiGraphics, mouseX, mouseY, partialTick);
    }

    private void drawWideGraphs(GuiGraphics guiGraphics, int x, int width, int top, int bottom) {
        int available = Math.max(1, bottom - top);
        int miniHeight = clamp(available / 4, 48, 72);
        int miniLabelY = bottom - miniHeight - 11;
        int mainBottom = miniLabelY - 10;
        int mainGraphY = top + 12;
        int mainHeight = Math.max(48, mainBottom - mainGraphY);
        int gap = 12;
        int halfWidth = (width - gap) / 2;

        drawBreadHeader(guiGraphics, x, top, halfWidth);
        drawBreadGraph(guiGraphics, snapshot.history(), x, mainGraphY, halfWidth, mainHeight);

        int financeX = x + halfWidth + gap;
        drawFinanceHeader(guiGraphics, financeX, top, halfWidth);
        drawFinanceGraph(guiGraphics, snapshot.financeHistory(), financeX, mainGraphY, halfWidth, mainHeight);

        int miniGap = 10;
        int miniWidth = (width - miniGap * 2) / 3;
        drawFinanceTrendGraph(
                guiGraphics,
                snapshot.financeHistory(),
                x,
                miniLabelY,
                bottom - miniHeight,
                miniWidth,
                miniHeight,
                "TRADE BALANCE",
                FinancePointSnapshot::tradeBalance,
                STEEL,
                true
        );
        drawFinanceTrendGraph(
                guiGraphics,
                snapshot.financeHistory(),
                x + miniWidth + miniGap,
                miniLabelY,
                bottom - miniHeight,
                miniWidth,
                miniHeight,
                "TAX REVENUE",
                FinancePointSnapshot::taxRevenue,
                GOLD,
                false
        );
        drawTrendGraph(
                guiGraphics,
                snapshot.history(),
                x + (miniWidth + miniGap) * 2,
                miniLabelY,
                bottom - miniHeight,
                miniWidth,
                miniHeight,
                "POPULATION",
                HistoryPointSnapshot::population,
                GREEN
        );
    }

    private void drawCompactGraphs(GuiGraphics guiGraphics, int x, int width, int top, int bottom) {
        int available = Math.max(1, bottom - top);
        if (available < 70) {
            return;
        }

        int labelY = top;
        int graphY = labelY + 12;
        int graphHeight = Math.max(40, available - 12);
        drawFinanceHeader(guiGraphics, x, labelY, width);
        drawFinanceGraph(guiGraphics, snapshot.financeHistory(), x, graphY, width, graphHeight);
    }

    private String economyState() {
        if (snapshot.localLiquidity() <= 0L
                && (snapshot.breadSupplied() < snapshot.breadTarget()
                || snapshot.buildingMaterialsSupplied() < snapshot.buildingMaterialsTarget())) {
            return "LIQUIDITY CRUNCH";
        }
        if (snapshot.breadSupplied() < snapshot.breadTarget()) {
            return "SHORTAGE";
        }
        if (snapshot.buildingMaterialsSupplied() < snapshot.buildingMaterialsTarget()) {
            return "NEEDS BRICKS";
        }
        if (snapshot.growthReady()) {
            return "GROWTH READY";
        }
        return "SUPPLIED";
    }

    private int economyStateColor() {
        if (economyState().equals("LIQUIDITY CRUNCH") || snapshot.breadSupplied() < snapshot.breadTarget()) {
            return RED;
        }
        if (snapshot.buildingMaterialsSupplied() < snapshot.buildingMaterialsTarget()) {
            return GOLD;
        }
        return GREEN;
    }

    private int drawStats(GuiGraphics guiGraphics, int x, int width, int y, boolean compact) {
        int offset = 0;
        offset += drawPair(
                guiGraphics, x, width, y + offset, compact,
                snapshot.settlementTier() + "  //  Territory " + snapshot.claimRadius() + " blocks", GOLD,
                "Population " + snapshot.population() + "   Prosperity " + snapshot.prosperity(), TEXT
        );
        offset += drawPair(
                guiGraphics, x, width, y + offset, compact,
                "Local economy  " + formatKora(snapshot.localLiquidity()), GREEN,
                "Municipal treasury  " + formatKora(snapshot.treasuryBalance()), GOLD
        );
        offset += drawPair(
                guiGraphics, x, width, y + offset, compact,
                "Commercial tax  " + snapshot.commercialTaxPercent() + "%", MUTED_TEXT,
                "Fiscal balance today  " + formatSignedKora(snapshot.budgetBalanceToday()),
                snapshot.budgetBalanceToday() >= 0L ? GREEN : RED
        );
        offset += drawPair(
                guiGraphics, x, width, y + offset, compact,
                "Bread  " + formatKora(snapshot.breadUnitPrice()) + "  "
                        + snapshot.breadSupplied() + "/" + snapshot.breadTarget()
                        + "  -" + snapshot.dailyBreadConsumption() + "/day",
                snapshot.breadSupplied() >= snapshot.breadTarget() ? GREEN : TEXT,
                "Bricks  " + formatKora(snapshot.brickUnitPrice()) + "  "
                        + snapshot.buildingMaterialsSupplied() + "/" + snapshot.buildingMaterialsTarget(),
                snapshot.buildingMaterialsSupplied() >= snapshot.buildingMaterialsTarget() ? GREEN : TEXT
        );
        offset += drawPair(
                guiGraphics, x, width, y + offset, compact,
                "Settled trade today  +" + formatKora(snapshot.exportValueToday())
                        + " / -" + formatKora(snapshot.importValueToday()), STEEL,
                "Trade balance  " + formatSignedKora(snapshot.tradeBalanceToday()),
                snapshot.tradeBalanceToday() >= 0L ? GREEN : RED
        );
        offset += drawPair(
                guiGraphics, x, width, y + offset, compact,
                "Trade 7d avg  +" + formatKora(snapshot.exportValueAverage7d())
                        + " / -" + formatKora(snapshot.importValueAverage7d()), MUTED_TEXT,
                "Tax  " + formatKora(snapshot.taxRevenueToday()) + " today  "
                        + formatKora(snapshot.taxRevenueAverage7d()) + "/day 7d", GOLD
        );

        int totalJobs = snapshot.foodJobs() + snapshot.constructionJobs();
        int vacancies = Math.max(0, totalJobs - snapshot.employed());
        offset += drawPair(
                guiGraphics, x, width, y + offset, compact,
                "Workforce  " + snapshot.employed() + "/" + snapshot.workforce() + " employed", TEXT,
                "Jobs  " + snapshot.employed() + "/" + totalJobs + " filled   Vacant " + vacancies,
                vacancies > 0 ? GOLD : MUTED_TEXT
        );
        offset += drawPair(
                guiGraphics, x, width, y + offset, compact,
                "Labor policy  " + snapshot.laborPriority(), GOLD,
                "Food " + snapshot.foodEmployed() + "/" + snapshot.foodJobs()
                        + "   Construction " + snapshot.constructionEmployed() + "/" + snapshot.constructionJobs(),
                STEEL
        );
        offset += drawPair(
                guiGraphics, x, width, y + offset, compact,
                "Output today  Food " + snapshot.foodOutputToday()
                        + "   Construction " + snapshot.constructionOutputToday(),
                snapshot.foodOutputToday() + snapshot.constructionOutputToday() > 0 ? GREEN : MUTED_TEXT,
                "7d avg  Food " + snapshot.foodOutputAverage7d()
                        + "   Construction " + snapshot.constructionOutputAverage7d(), MUTED_TEXT
        );
        offset += drawPair(
                guiGraphics, x, width, y + offset, compact,
                "Physical trade  Bread +" + snapshot.breadImportsToday() + "/-" + snapshot.breadExportsToday(),
                MUTED_TEXT,
                "Bricks +" + snapshot.brickImportsToday() + "/-" + snapshot.brickExportsToday(), MUTED_TEXT
        );
        return offset;
    }

    private int drawPair(GuiGraphics guiGraphics, int x, int width, int y, boolean compact,
                         String left, int leftColor, String right, int rightColor) {
        guiGraphics.drawString(font, Component.literal(left), x, y, leftColor);
        int rightX = x + width - font.width(right);
        if (!compact && rightX > x + font.width(left) + 10) {
            guiGraphics.drawString(font, Component.literal(right), rightX, y, rightColor);
            return 14;
        }

        guiGraphics.drawString(font, Component.literal(right), x, y + 14, rightColor);
        return 28;
    }

    private void drawBreadHeader(GuiGraphics guiGraphics, int x, int y, int width) {
        String label = "BREAD RESERVE // 30 DAYS";
        String target = "TARGET " + snapshot.breadTarget();
        guiGraphics.drawString(font, Component.literal(label), x, y, MUTED_TEXT);
        if (font.width(label) + font.width(target) + 12 < width) {
            guiGraphics.drawString(font, Component.literal(target), x + width - font.width(target), y, PANEL_BORDER);
        }
    }

    private void drawFinanceHeader(GuiGraphics guiGraphics, int x, int y, int width) {
        String label = snapshot.currencyCode() + " LIQUIDITY // 30 DAYS";
        String legend = "LOCAL / TREASURY";
        guiGraphics.drawString(font, Component.literal(label), x, y, MUTED_TEXT);
        if (font.width(label) + font.width(legend) + 12 < width) {
            guiGraphics.drawString(font, Component.literal(legend), x + width - font.width(legend), y, PANEL_BORDER);
        }
    }

    private void drawBreadGraph(GuiGraphics guiGraphics, List<HistoryPointSnapshot> history,
                                int x, int y, int width, int height) {
        guiGraphics.fill(x, y, x + width, y + height, GRAPH_BG);

        if (history.isEmpty()) {
            return;
        }

        int innerX = x + 8;
        int innerY = y + 6;
        int innerWidth = Math.max(1, width - 16);
        int footerHeight = height >= 34 ? 12 : 0;
        int plotBottom = y + height - footerHeight;
        int innerHeight = Math.max(1, plotBottom - innerY - 2);

        int count = Math.min(history.size(), innerWidth);
        int firstVisible = history.size() - count;
        int gap = innerWidth >= count * 3 ? 1 : 0;
        int barWidth = Math.max(1,
                (innerWidth - Math.max(0, count - 1) * gap) / Math.max(1, count));
        int usedWidth = count * barWidth + Math.max(0, count - 1) * gap;
        int startX = innerX + Math.max(0, innerWidth - usedWidth);

        int maxTarget = Math.max(snapshot.breadTarget(), history.stream()
                .mapToInt(HistoryPointSnapshot::breadTarget)
                .max()
                .orElse(1));
        int maxSupply = history.stream().mapToInt(HistoryPointSnapshot::breadSupplied).max().orElse(1);
        int scaleMax = Math.max(1, Math.max(maxTarget, maxSupply));
        scaleMax += Math.max(1, scaleMax / 8);

        int targetY = innerY + innerHeight - snapshot.breadTarget() * innerHeight / scaleMax;
        guiGraphics.fill(innerX, targetY, innerX + innerWidth, targetY + 1, PANEL_BORDER);

        for (int i = 0; i < count; i++) {
            HistoryPointSnapshot point = history.get(firstVisible + i);
            int barHeight = point.breadSupplied() <= 0
                    ? 1
                    : Math.max(1, point.breadSupplied() * innerHeight / scaleMax);
            int barX = startX + i * (barWidth + gap);
            int barY = innerY + innerHeight - barHeight;
            int color = point.breadSupplied() >= point.breadTarget() ? GREEN : RED;
            guiGraphics.fill(barX, barY, Math.min(innerX + innerWidth, barX + barWidth), innerY + innerHeight, color);
        }

        drawDayFooter(guiGraphics, history.get(firstVisible).day(), history.get(history.size() - 1).day(),
                innerX, y, innerWidth, height);
    }

    private void drawFinanceGraph(GuiGraphics guiGraphics, List<FinancePointSnapshot> history,
                                  int x, int y, int width, int height) {
        guiGraphics.fill(x, y, x + width, y + height, GRAPH_BG);
        if (history.isEmpty()) {
            return;
        }

        int innerX = x + 8;
        int innerY = y + 6;
        int innerWidth = Math.max(2, width - 16);
        int footerHeight = height >= 34 ? 12 : 0;
        int innerHeight = Math.max(2, height - footerHeight - 12);
        int count = Math.min(history.size(), innerWidth);
        int firstVisible = history.size() - count;

        long max = 1L;
        for (int i = firstVisible; i < history.size(); i++) {
            FinancePointSnapshot point = history.get(i);
            max = Math.max(max, Math.max(point.localLiquidity(), point.treasuryBalance()));
        }

        drawFinanceSeries(guiGraphics, history, firstVisible, count, innerX, innerY, innerWidth, innerHeight,
                FinancePointSnapshot::localLiquidity, 0L, max, GREEN);
        drawFinanceSeries(guiGraphics, history, firstVisible, count, innerX, innerY, innerWidth, innerHeight,
                FinancePointSnapshot::treasuryBalance, 0L, max, GOLD);

        drawDayFooter(guiGraphics, history.get(firstVisible).day(), history.get(history.size() - 1).day(),
                innerX, y, innerWidth, height);
    }

    private void drawFinanceTrendGraph(GuiGraphics guiGraphics, List<FinancePointSnapshot> history,
                                       int x, int labelY, int graphY, int width, int height, String label,
                                       ToLongFunction<FinancePointSnapshot> valueGetter, int color, boolean signed) {
        long min = history.stream().mapToLong(valueGetter).min().orElse(0L);
        long max = history.stream().mapToLong(valueGetter).max().orElse(min);
        long current = history.isEmpty() ? 0L : valueGetter.applyAsLong(history.get(history.size() - 1));
        String value = history.isEmpty() ? "--" : (signed ? formatSignedKora(current) : formatKora(current));

        guiGraphics.drawString(font, Component.literal(label), x, labelY, MUTED_TEXT);
        if (font.width(label) + font.width(value) + 10 < width) {
            guiGraphics.drawString(font, Component.literal(value), x + width - font.width(value), labelY,
                    signed && current < 0L ? RED : TEXT);
        }
        guiGraphics.fill(x, graphY, x + width, graphY + height, GRAPH_BG);

        if (history.isEmpty()) {
            return;
        }

        int innerX = x + 6;
        int innerY = graphY + 6;
        int innerWidth = Math.max(2, width - 12);
        int innerHeight = Math.max(2, height - 12);
        int count = Math.min(history.size(), innerWidth);
        int firstVisible = history.size() - count;
        drawFinanceSeries(guiGraphics, history, firstVisible, count, innerX, innerY, innerWidth, innerHeight,
                valueGetter, min, max, color);
    }

    private void drawFinanceSeries(GuiGraphics guiGraphics, List<FinancePointSnapshot> history,
                                   int firstVisible, int count, int innerX, int innerY,
                                   int innerWidth, int innerHeight,
                                   ToLongFunction<FinancePointSnapshot> valueGetter,
                                   long min, long max, int color) {
        int previousX = -1;
        int previousY = -1;
        long span = Math.max(1L, max - min);

        for (int i = 0; i < count; i++) {
            long value = valueGetter.applyAsLong(history.get(firstVisible + i));
            int pointX = count == 1
                    ? innerX + innerWidth / 2
                    : innerX + i * (innerWidth - 1) / (count - 1);
            int pointY = max == min
                    ? innerY + innerHeight / 2
                    : innerY + innerHeight - 1
                    - (int) ((value - min) * (innerHeight - 1) / span);

            if (previousX >= 0) {
                drawLine(guiGraphics, previousX, previousY, pointX, pointY, color);
            }
            guiGraphics.fill(pointX, pointY, pointX + 2, pointY + 2, color);
            previousX = pointX;
            previousY = pointY;
        }
    }

    private void drawTrendGraph(GuiGraphics guiGraphics, List<HistoryPointSnapshot> history,
                                int x, int labelY, int graphY, int width, int height, String label,
                                ToIntFunction<HistoryPointSnapshot> valueGetter, int color) {
        int min = history.stream().mapToInt(valueGetter).min().orElse(0);
        int max = history.stream().mapToInt(valueGetter).max().orElse(min);
        int current = history.isEmpty() ? 0 : valueGetter.applyAsInt(history.get(history.size() - 1));
        String value = history.isEmpty() ? "--" : Integer.toString(current);

        guiGraphics.drawString(font, Component.literal(label), x, labelY, MUTED_TEXT);
        if (font.width(label) + font.width(value) + 10 < width) {
            guiGraphics.drawString(font, Component.literal(value), x + width - font.width(value), labelY, TEXT);
        }
        guiGraphics.fill(x, graphY, x + width, graphY + height, GRAPH_BG);

        if (history.isEmpty()) {
            return;
        }

        int innerX = x + 6;
        int innerY = graphY + 6;
        int innerWidth = Math.max(2, width - 12);
        int innerHeight = Math.max(2, height - 12);
        int count = Math.min(history.size(), innerWidth);
        int firstVisible = history.size() - count;

        int previousX = -1;
        int previousY = -1;
        for (int i = 0; i < count; i++) {
            int valueAtPoint = valueGetter.applyAsInt(history.get(firstVisible + i));
            int pointX = count == 1
                    ? innerX + innerWidth / 2
                    : innerX + i * (innerWidth - 1) / (count - 1);
            int pointY = max == min
                    ? innerY + innerHeight / 2
                    : innerY + innerHeight - 1 - (valueAtPoint - min) * (innerHeight - 1) / (max - min);

            if (previousX >= 0) {
                drawLine(guiGraphics, previousX, previousY, pointX, pointY, color);
            }
            guiGraphics.fill(pointX, pointY, pointX + 2, pointY + 2, color);
            previousX = pointX;
            previousY = pointY;
        }
    }

    private void drawDayFooter(GuiGraphics guiGraphics, long firstDayValue, long lastDayValue,
                               int x, int graphY, int width, int height) {
        if (height < 34) {
            return;
        }
        int footerY = graphY + height - 9;
        String firstDay = "Day " + firstDayValue;
        String lastDay = "Day " + lastDayValue;
        guiGraphics.drawString(font, Component.literal(firstDay), x, footerY, MUTED_TEXT);
        if (font.width(firstDay) + font.width(lastDay) + 12 < width) {
            guiGraphics.drawString(font, Component.literal(lastDay),
                    x + width - font.width(lastDay), footerY, MUTED_TEXT);
        }
    }

    private String formatKora(long value) {
        return snapshot.currencySymbol() + String.format(Locale.ROOT, "%,d", Math.max(0L, value));
    }

    private String formatSignedKora(long value) {
        if (value > 0L) {
            return "+" + snapshot.currencySymbol() + String.format(Locale.ROOT, "%,d", value);
        }
        if (value < 0L) {
            return "-" + snapshot.currencySymbol() + String.format(Locale.ROOT, "%,d", Math.abs(value));
        }
        return snapshot.currencySymbol() + "0";
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static void drawLine(GuiGraphics guiGraphics, int x0, int y0, int x1, int y1, int color) {
        int dx = Math.abs(x1 - x0);
        int sx = x0 < x1 ? 1 : -1;
        int dy = -Math.abs(y1 - y0);
        int sy = y0 < y1 ? 1 : -1;
        int error = dx + dy;

        while (true) {
            guiGraphics.fill(x0, y0, x0 + 1, y0 + 1, color);
            if (x0 == x1 && y0 == y1) {
                break;
            }

            int twiceError = 2 * error;
            if (twiceError >= dy) {
                error += dy;
                x0 += sx;
            }
            if (twiceError <= dx) {
                error += dx;
                y0 += sy;
            }
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
