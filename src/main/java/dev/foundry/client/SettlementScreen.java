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

        int panelWidth = Math.min(560, width - 32);
        int panelHeight = Math.min(360, height - 32);
        int panelX = (width - panelWidth) / 2;
        int panelY = (height - panelHeight) / 2;

        guiGraphics.fill(panelX, panelY, panelX + panelWidth, panelY + panelHeight, PANEL_BORDER);
        guiGraphics.fill(panelX + 1, panelY + 1, panelX + panelWidth - 1, panelY + panelHeight - 1, PANEL_BG);

        guiGraphics.drawString(font, Component.literal("FOUNDRY // TOWN HALL LEDGER"), panelX + 16, panelY + 14, GOLD);

        String supplyState = snapshot.breadSupplied() >= snapshot.breadTarget() ? "SUPPLIED" : "SHORTAGE";
        int supplyColor = snapshot.breadSupplied() >= snapshot.breadTarget() ? GREEN : RED;
        guiGraphics.drawString(font, Component.literal(supplyState), panelX + panelWidth - 16 - font.width(supplyState), panelY + 14, supplyColor);

        int statsY = panelY + 34;
        guiGraphics.drawString(font, Component.literal("Population  " + snapshot.population()), panelX + 16, statsY, TEXT);
        guiGraphics.drawString(font, Component.literal("Prosperity  " + snapshot.prosperity()), panelX + 150, statsY, TEXT);
        guiGraphics.drawString(font,
                Component.literal("Bread  " + snapshot.breadSupplied() + "/" + snapshot.breadTarget() + "   -" + snapshot.dailyBreadConsumption() + "/day"),
                panelX + 285, statsY, TEXT);

        int chartX = panelX + 16;
        int chartWidth = panelWidth - 32;
        int breadY = panelY + 62;
        int breadHeight = 132;
        drawBreadGraph(guiGraphics, snapshot.history(), chartX, breadY, chartWidth, breadHeight);

        int miniY = breadY + breadHeight + 30;
        int miniGap = 12;
        int miniWidth = (chartWidth - miniGap) / 2;
        int miniHeight = Math.max(72, panelY + panelHeight - 18 - miniY);
        drawMiniGraph(guiGraphics, snapshot.history(), chartX, miniY, miniWidth, miniHeight,
                "PROSPERITY", HistoryPointSnapshot::prosperity, GOLD);
        drawMiniGraph(guiGraphics, snapshot.history(), chartX + miniWidth + miniGap, miniY, miniWidth, miniHeight,
                "POPULATION", HistoryPointSnapshot::population, STEEL);

        super.render(guiGraphics, mouseX, mouseY, partialTick);
    }

    private void drawBreadGraph(GuiGraphics guiGraphics, List<HistoryPointSnapshot> history,
                                int x, int y, int width, int height) {
        guiGraphics.drawString(font, Component.literal("BREAD RESERVE // LAST 30 DAYS"), x, y - 13, MUTED_TEXT);
        guiGraphics.fill(x, y, x + width, y + height, GRAPH_BG);

        if (history.isEmpty()) {
            guiGraphics.drawCenteredString(font, Component.literal("History starts after the first economy tick"),
                    x + width / 2, y + height / 2 - 4, MUTED_TEXT);
            return;
        }

        int innerX = x + 8;
        int innerY = y + 8;
        int innerWidth = width - 16;
        int innerHeight = height - 18;
        int count = history.size();
        int gap = 1;
        int barWidth = Math.max(2, (innerWidth - (count - 1) * gap) / count);
        int usedWidth = count * barWidth + (count - 1) * gap;
        int startX = innerX + Math.max(0, innerWidth - usedWidth);

        int maxTarget = Math.max(snapshot.breadTarget(), history.stream()
                .mapToInt(HistoryPointSnapshot::breadTarget)
                .max()
                .orElse(1));

        int targetY = innerY + innerHeight - Math.max(1, snapshot.breadTarget() * innerHeight / Math.max(1, maxTarget));
        guiGraphics.fill(innerX, targetY, innerX + innerWidth, targetY + 1, PANEL_BORDER);

        for (int i = 0; i < count; i++) {
            HistoryPointSnapshot point = history.get(i);
            int barHeight = point.breadSupplied() <= 0
                    ? 1
                    : Math.max(1, point.breadSupplied() * innerHeight / Math.max(1, maxTarget));
            int barX = startX + i * (barWidth + gap);
            int barY = innerY + innerHeight - barHeight;
            int color = point.breadSupplied() >= point.breadTarget() ? GREEN : RED;
            guiGraphics.fill(barX, barY, barX + barWidth, innerY + innerHeight, color);
        }

        HistoryPointSnapshot first = history.get(0);
        HistoryPointSnapshot last = history.get(history.size() - 1);
        guiGraphics.drawString(font, Component.literal("Day " + first.day()), innerX, y + height - 9, MUTED_TEXT);
        String lastDay = "Day " + last.day();
        guiGraphics.drawString(font, Component.literal(lastDay), innerX + innerWidth - font.width(lastDay), y + height - 9, MUTED_TEXT);
    }

    private void drawMiniGraph(GuiGraphics guiGraphics, List<HistoryPointSnapshot> history,
                               int x, int y, int width, int height, String label,
                               ToIntFunction<HistoryPointSnapshot> valueGetter, int color) {
        guiGraphics.drawString(font, Component.literal(label), x, y - 13, MUTED_TEXT);
        guiGraphics.fill(x, y, x + width, y + height, GRAPH_BG);

        if (history.isEmpty()) {
            return;
        }

        int min = history.stream().mapToInt(valueGetter).min().orElse(0);
        int max = history.stream().mapToInt(valueGetter).max().orElse(min);
        int innerX = x + 6;
        int innerY = y + 6;
        int innerWidth = width - 12;
        int innerHeight = height - 12;
        int count = history.size();
        int gap = 1;
        int barWidth = Math.max(2, (innerWidth - (count - 1) * gap) / count);
        int usedWidth = count * barWidth + (count - 1) * gap;
        int startX = innerX + Math.max(0, innerWidth - usedWidth);

        for (int i = 0; i < count; i++) {
            int value = valueGetter.applyAsInt(history.get(i));
            int barHeight;
            if (max == min) {
                barHeight = Math.max(2, innerHeight / 2);
            } else {
                barHeight = 2 + (value - min) * Math.max(1, innerHeight - 2) / (max - min);
            }
            int barX = startX + i * (barWidth + gap);
            guiGraphics.fill(barX, innerY + innerHeight - barHeight, barX + barWidth, innerY + innerHeight, color);
        }

        String range = min == max ? Integer.toString(max) : min + "–" + max;
        guiGraphics.drawString(font, Component.literal(range), x + width - 6 - font.width(range), y + 5, MUTED_TEXT);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
