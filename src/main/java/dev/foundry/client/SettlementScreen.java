package dev.foundry.client;

import dev.foundry.network.packet.SettlementSnapshotPacket;
import dev.foundry.network.packet.SettlementSnapshotPacket.HistoryPointSnapshot;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

import java.util.List;
import java.util.function.ToIntFunction;

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

        int panelWidth = Math.min(600, width - 20);
        int panelHeight = Math.min(340, height - 20);
        int panelX = (width - panelWidth) / 2;
        int panelY = (height - panelHeight) / 2;
        int padding = 14;
        int innerX = panelX + padding;
        int innerWidth = panelWidth - padding * 2;

        guiGraphics.fill(panelX, panelY, panelX + panelWidth, panelY + panelHeight, PANEL_BORDER);
        guiGraphics.fill(panelX + 1, panelY + 1, panelX + panelWidth - 1, panelY + panelHeight - 1, PANEL_BG);

        int titleY = panelY + 12;
        guiGraphics.drawString(font, Component.literal("FOUNDRY // TOWN HALL LEDGER"), innerX, titleY, GOLD);

        String supplyState = snapshot.breadSupplied() >= snapshot.breadTarget() ? "SUPPLIED" : "SHORTAGE";
        int supplyColor = snapshot.breadSupplied() >= snapshot.breadTarget() ? GREEN : RED;
        guiGraphics.drawString(font, Component.literal(supplyState),
                panelX + panelWidth - padding - font.width(supplyState), titleY, supplyColor);

        int statsY = panelY + 31;
        boolean compactStats = innerWidth < 430;
        if (compactStats) {
            String population = "Population  " + snapshot.population();
            String prosperity = "Prosperity  " + snapshot.prosperity();
            guiGraphics.drawString(font, Component.literal(population), innerX, statsY, TEXT);
            guiGraphics.drawString(font, Component.literal(prosperity),
                    innerX + innerWidth - font.width(prosperity), statsY, TEXT);

            String bread = "Bread  " + snapshot.breadSupplied() + "/" + snapshot.breadTarget()
                    + "   -" + snapshot.dailyBreadConsumption() + "/day";
            guiGraphics.drawString(font, Component.literal(bread), innerX, statsY + 14, TEXT);
        } else {
            String population = "Population  " + snapshot.population();
            String prosperity = "Prosperity  " + snapshot.prosperity();
            String bread = "Bread  " + snapshot.breadSupplied() + "/" + snapshot.breadTarget()
                    + "   -" + snapshot.dailyBreadConsumption() + "/day";

            guiGraphics.drawString(font, Component.literal(population), innerX, statsY, TEXT);
            guiGraphics.drawCenteredString(font, Component.literal(prosperity),
                    innerX + innerWidth / 2, statsY, TEXT);
            guiGraphics.drawString(font, Component.literal(bread),
                    innerX + innerWidth - font.width(bread), statsY, TEXT);
        }

        int contentTop = statsY + (compactStats ? 34 : 22);
        int contentBottom = panelY + panelHeight - 14;
        int availableHeight = Math.max(1, contentBottom - contentTop);

        int miniGraphHeight = Math.min(62, Math.max(30, (availableHeight - 24) / 3));
        int miniGraphY = contentBottom - miniGraphHeight;
        int miniLabelY = miniGraphY - 11;

        int breadLabelY = contentTop;
        int breadGraphY = breadLabelY + 12;
        int breadGraphBottom = miniLabelY - 12;
        int breadGraphHeight = Math.max(20, breadGraphBottom - breadGraphY);

        drawBreadHeader(guiGraphics, innerX, breadLabelY, innerWidth);
        drawBreadGraph(guiGraphics, snapshot.history(), innerX, breadGraphY, innerWidth, breadGraphHeight);

        int miniGap = 12;
        int miniWidth = (innerWidth - miniGap) / 2;
        drawTrendGraph(guiGraphics, snapshot.history(), innerX, miniLabelY, miniGraphY, miniWidth, miniGraphHeight,
                "PROSPERITY", HistoryPointSnapshot::prosperity, GOLD);
        drawTrendGraph(guiGraphics, snapshot.history(), innerX + miniWidth + miniGap, miniLabelY, miniGraphY,
                miniWidth, miniGraphHeight, "POPULATION", HistoryPointSnapshot::population, STEEL);

        super.render(guiGraphics, mouseX, mouseY, partialTick);
    }

    private void drawBreadHeader(GuiGraphics guiGraphics, int x, int y, int width) {
        String label = "BREAD RESERVE // LAST 30 DAYS";
        String target = "TARGET " + snapshot.breadTarget();
        guiGraphics.drawString(font, Component.literal(label), x, y, MUTED_TEXT);
        if (font.width(label) + font.width(target) + 12 < width) {
            guiGraphics.drawString(font, Component.literal(target), x + width - font.width(target), y, PANEL_BORDER);
        }
    }

    private void drawBreadGraph(GuiGraphics guiGraphics, List<HistoryPointSnapshot> history,
                                int x, int y, int width, int height) {
        guiGraphics.fill(x, y, x + width, y + height, GRAPH_BG);

        if (history.isEmpty()) {
            guiGraphics.drawCenteredString(font, Component.literal("History starts after the first economy tick"),
                    x + width / 2, y + height / 2 - 4, MUTED_TEXT);
            return;
        }

        int innerX = x + 8;
        int innerY = y + 6;
        int innerWidth = Math.max(1, width - 16);
        int footerHeight = 12;
        int plotBottom = y + height - footerHeight;
        int innerHeight = Math.max(1, plotBottom - innerY - 2);
        int count = history.size();
        int gap = 1;
        int barWidth = Math.max(2, (innerWidth - Math.max(0, count - 1) * gap) / Math.max(1, count));
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
            HistoryPointSnapshot point = history.get(i);
            int barHeight = point.breadSupplied() <= 0
                    ? 1
                    : Math.max(1, point.breadSupplied() * innerHeight / scaleMax);
            int barX = startX + i * (barWidth + gap);
            int barY = innerY + innerHeight - barHeight;
            int color = point.breadSupplied() >= point.breadTarget() ? GREEN : RED;
            guiGraphics.fill(barX, barY, barX + barWidth, innerY + innerHeight, color);
        }

        HistoryPointSnapshot first = history.get(0);
        HistoryPointSnapshot last = history.get(history.size() - 1);
        int footerY = y + height - 9;
        guiGraphics.drawString(font, Component.literal("Day " + first.day()), innerX, footerY, MUTED_TEXT);
        String lastDay = "Day " + last.day();
        guiGraphics.drawString(font, Component.literal(lastDay),
                innerX + innerWidth - font.width(lastDay), footerY, MUTED_TEXT);
    }

    private void drawTrendGraph(GuiGraphics guiGraphics, List<HistoryPointSnapshot> history,
                                int x, int labelY, int graphY, int width, int height, String label,
                                ToIntFunction<HistoryPointSnapshot> valueGetter, int color) {
        int min = history.stream().mapToInt(valueGetter).min().orElse(0);
        int max = history.stream().mapToInt(valueGetter).max().orElse(min);
        String range = history.isEmpty() ? "--" : (min == max ? Integer.toString(max) : min + "-" + max);

        guiGraphics.drawString(font, Component.literal(label), x, labelY, MUTED_TEXT);
        guiGraphics.drawString(font, Component.literal(range), x + width - font.width(range), labelY, MUTED_TEXT);
        guiGraphics.fill(x, graphY, x + width, graphY + height, GRAPH_BG);

        if (history.isEmpty()) {
            return;
        }

        int innerX = x + 6;
        int innerY = graphY + 6;
        int innerWidth = Math.max(2, width - 12);
        int innerHeight = Math.max(2, height - 12);
        int count = history.size();

        int previousX = -1;
        int previousY = -1;
        for (int i = 0; i < count; i++) {
            int value = valueGetter.applyAsInt(history.get(i));
            int pointX = count == 1
                    ? innerX + innerWidth / 2
                    : innerX + i * (innerWidth - 1) / (count - 1);
            int pointY = max == min
                    ? innerY + innerHeight / 2
                    : innerY + innerHeight - 1 - (value - min) * (innerHeight - 1) / (max - min);

            if (previousX >= 0) {
                drawLine(guiGraphics, previousX, previousY, pointX, pointY, color);
            }
            guiGraphics.fill(pointX, pointY, pointX + 2, pointY + 2, color);
            previousX = pointX;
            previousY = pointY;
        }
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
