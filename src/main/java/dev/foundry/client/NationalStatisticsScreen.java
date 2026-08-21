package dev.foundry.client;

import dev.foundry.network.FoundryNetwork;
import dev.foundry.network.packet.NationalStatisticsPacket;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.api.distmarker.OnlyIn;

import java.util.List;
import java.util.function.ToLongFunction;

@OnlyIn(Dist.CLIENT)
public final class NationalStatisticsScreen extends Screen {
    private static final int PANEL_MAX_WIDTH = 1120;
    private static final int PANEL_MAX_HEIGHT = 680;
    private static final int ROW_HEIGHT = 18;
    private static final int MAX_VISIBLE_ROWS = 9;

    private static final int PANEL = 0xF20B1015;
    private static final int GOLD = 0xFFB69454;
    private static final int TEXT = 0xFFE9DFC8;
    private static final int MUTED = 0xFF8F9BA5;
    private static final int GRID = 0xFF1B252D;
    private static final int GREEN = 0xFF78B88F;
    private static final int RED = 0xFFC87979;
    private static final int STEEL = 0xFF7FA2B2;
    private static final int BLUE = 0xFF7397C5;

    private final NationalStatisticsPacket packet;
    private Page page = Page.REGISTER;
    private int scrollRow;
    private int selectedRow = -1;
    private Button registerButton;
    private Button trendsButton;
    private Button dossierButton;

    public NationalStatisticsScreen(NationalStatisticsPacket packet) {
        super(Component.literal("National Statistics Bureau"));
        this.packet = packet;
    }

    @Override
    protected void init() {
        int panelWidth = Math.min(PANEL_MAX_WIDTH, width - 24);
        int panelHeight = Math.min(PANEL_MAX_HEIGHT, height - 24);
        int left = (width - panelWidth) / 2;
        int top = (height - panelHeight) / 2;
        int right = left + panelWidth;

        int y = top + 8;
        int dossierWidth = 116;
        int trendsWidth = 94;
        int registerWidth = 94;
        int gap = 5;
        int x = right - 14 - dossierWidth;

        dossierButton = addRenderableWidget(Button.builder(Component.literal("OPEN DOSSIER"), button -> openSelected())
                .bounds(x, y, dossierWidth, 20).build());
        x -= gap + trendsWidth;
        trendsButton = addRenderableWidget(Button.builder(Component.literal("NATIONAL TRENDS"), button -> setPage(Page.TRENDS))
                .bounds(x, y, trendsWidth, 20).build());
        x -= gap + registerWidth;
        registerButton = addRenderableWidget(Button.builder(Component.literal("SETTLEMENTS"), button -> setPage(Page.REGISTER))
                .bounds(x, y, registerWidth, 20).build());

        refreshButtons();
    }

    private void setPage(Page page) {
        this.page = page;
        refreshButtons();
    }

    private void refreshButtons() {
        if (registerButton != null) registerButton.active = page != Page.REGISTER;
        if (trendsButton != null) trendsButton.active = page != Page.TRENDS;
        if (dossierButton != null) dossierButton.active = selectedRow >= 0 && selectedRow < packet.settlements().size();
    }

    private void openSelected() {
        if (selectedRow < 0 || selectedRow >= packet.settlements().size()) return;
        FoundryNetwork.requestSettlementSnapshot(packet.settlements().get(selectedRow).settlementId());
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(graphics);
        int panelWidth = Math.min(PANEL_MAX_WIDTH, width - 24);
        int panelHeight = Math.min(PANEL_MAX_HEIGHT, height - 24);
        int left = (width - panelWidth) / 2;
        int top = (height - panelHeight) / 2;
        int right = left + panelWidth;
        int bottom = top + panelHeight;
        int innerLeft = left + 14;
        int innerRight = right - 14;
        int innerWidth = innerRight - innerLeft;

        graphics.fill(left, top, right, bottom, PANEL);
        graphics.fill(left, top, right, top + 3, GOLD);
        graphics.drawString(font, "FOUNDRY // NATIONAL STATISTICS BUREAU", innerLeft, top + 12, TEXT, false);
        graphics.drawString(font, page == Page.REGISTER
                        ? "SETTLEMENT REGISTER // SELECT A TOWN FOR ITS CIVIC DOSSIER"
                        : "NATIONAL ACCOUNTS // 30-DAY DEVELOPMENT & ECONOMIC SERIES",
                innerLeft, top + 27, MUTED, false);

        List<NationalStatisticsPacket.Entry> rows = packet.settlements();
        long population = rows.stream().mapToLong(NationalStatisticsPacket.Entry::population).sum();
        long treasury = rows.stream().mapToLong(NationalStatisticsPacket.Entry::treasury).sum();
        long gtp7d = rows.stream().mapToLong(NationalStatisticsPacket.Entry::gtp7d).sum();
        int physicalOutput7d = rows.stream().mapToInt(NationalStatisticsPacket.Entry::physicalOutput7d).sum();
        long tradeBalance7d = rows.stream().mapToLong(NationalStatisticsPacket.Entry::tradeBalance7d).sum();
        int averageProsperity = rows.isEmpty() ? 0
                : (int) Math.round(rows.stream().mapToInt(NationalStatisticsPacket.Entry::prosperity).average().orElse(0));

        int cardY = top + 44;
        int cardGap = 8;
        int cardWidth = (innerWidth - cardGap * 3) / 4;
        drawSummaryCard(graphics, innerLeft, cardY, cardWidth, "GROSS TOWN PRODUCT / DAY",
                "K" + money(gtp7d), physicalOutput7d + " physical units / day", GOLD);
        drawSummaryCard(graphics, innerLeft + cardWidth + cardGap, cardY, cardWidth, "POPULATION",
                money(population), rows.size() + " settlements", BLUE);
        drawSummaryCard(graphics, innerLeft + (cardWidth + cardGap) * 2, cardY, cardWidth, "TRADE BALANCE / DAY",
                signedMoney(tradeBalance7d), "7-day domestic average", tradeBalance7d >= 0 ? GREEN : RED);
        drawSummaryCard(graphics, innerLeft + (cardWidth + cardGap) * 3, cardY, cardWidth, "MUNICIPAL TREASURIES",
                "K" + money(treasury), "Avg development " + averageProsperity + "/100", STEEL);

        if (page == Page.REGISTER) {
            renderRegister(graphics, mouseX, mouseY, left, right, top, bottom, innerLeft, innerRight);
        } else {
            renderTrends(graphics, innerLeft, innerRight, top, bottom);
        }

        super.render(graphics, mouseX, mouseY, partialTick);
    }

    private void renderRegister(GuiGraphics graphics, int mouseX, int mouseY,
                                int left, int right, int top, int bottom, int innerLeft, int innerRight) {
        List<NationalStatisticsPacket.Entry> rows = packet.settlements();
        int headerY = top + 116;
        graphics.fill(innerLeft - 4, headerY - 5, innerRight + 4, headerY + 11, 0xFF151D24);
        drawHeaders(graphics, left, right, headerY);

        int firstY = headerY + 18;
        int visibleRows = Math.min(MAX_VISIBLE_ROWS, Math.max(1, rows.size()));
        int maxScroll = Math.max(0, rows.size() - visibleRows);
        scrollRow = Math.min(scrollRow, maxScroll);

        for (int visible = 0; visible < visibleRows; visible++) {
            int index = scrollRow + visible;
            if (index >= rows.size()) break;
            NationalStatisticsPacket.Entry row = rows.get(index);
            int y = firstY + visible * ROW_HEIGHT;
            boolean hovered = mouseX >= innerLeft - 4 && mouseX <= innerRight + 4
                    && mouseY >= y - 4 && mouseY <= y + 12;
            if (index == selectedRow) {
                graphics.fill(innerLeft - 4, y - 4, innerRight + 4, y + 12, 0xAA283746);
                graphics.fill(innerLeft - 4, y - 4, innerLeft - 1, y + 12, GOLD);
            } else if (hovered) {
                graphics.fill(innerLeft - 4, y - 4, innerRight + 4, y + 12, 0x881D2932);
            } else if ((index & 1) == 1) {
                graphics.fill(innerLeft - 4, y - 4, innerRight + 4, y + 12, 0x6619222A);
            }
            drawRow(graphics, row, index + 1, left, right, y);
        }

        if (rows.isEmpty()) {
            graphics.drawCenteredString(font, "NO REGISTERED SETTLEMENTS", width / 2, firstY + 20, MUTED);
        }

        int footerY = Math.min(bottom - 46, firstY + MAX_VISIBLE_ROWS * ROW_HEIGHT + 12);
        graphics.fill(innerLeft, footerY, innerRight, footerY + 28, 0x9910181E);
        if (selectedRow >= 0 && selectedRow < rows.size()) {
            NationalStatisticsPacket.Entry selected = rows.get(selectedRow);
            graphics.drawString(font, selected.name() + " // " + selected.tier()
                            + " // GTP K" + money(selected.gtp7d())
                            + " // Development " + selected.prosperity() + "/100",
                    innerLeft + 8, footerY + 6, TEXT, false);
            graphics.drawString(font, "Use OPEN DOSSIER for the town's full local ledger, fiscal history and graphs.",
                    innerLeft + 8, footerY + 17, MUTED, false);
        } else {
            graphics.drawString(font, "Select a settlement row. The register is a gateway, not the whole statistics system.",
                    innerLeft + 8, footerY + 10, MUTED, false);
        }

        graphics.drawString(font,
                "GTP = measured value of physical Foundry output at base prices; valuation only — no Kora is minted.",
                innerLeft, bottom - 17, 0xFF6E7C87, false);
    }

    private void renderTrends(GuiGraphics graphics, int innerLeft, int innerRight, int top, int bottom) {
        int chartsTop = top + 110;
        int chartsBottom = bottom - 31;
        if (innerRight - innerLeft >= 620 && chartsBottom - chartsTop >= 180) {
            drawCharts(graphics, innerLeft, innerRight, chartsTop, chartsBottom);
        } else {
            graphics.drawCenteredString(font, "ENLARGE WINDOW TO INSPECT NATIONAL SERIES",
                    width / 2, chartsTop + 35, MUTED);
        }
        graphics.drawString(font,
                "National series aggregate the registered settlements. Use SETTLEMENTS to drill back down.",
                innerLeft, bottom - 17, 0xFF6E7C87, false);
    }

    private void drawSummaryCard(GuiGraphics g, int x, int y, int width, String title,
                                 String value, String detail, int accent) {
        g.fill(x, y, x + width, y + 48, 0xCC11181E);
        g.fill(x, y, x + 3, y + 48, accent);
        g.drawString(font, title, x + 9, y + 7, MUTED, false);
        g.drawString(font, value, x + 9, y + 20, TEXT, false);
        g.drawString(font, detail, x + 9, y + 34, 0xFF74818B, false);
    }

    private void drawHeaders(GuiGraphics g, int left, int right, int y) {
        int w = right - left;
        g.drawString(font, "#", left + 16, y, MUTED, false);
        g.drawString(font, "SETTLEMENT", left + 38, y, MUTED, false);
        g.drawString(font, "TIER", left + w * 31 / 100, y, MUTED, false);
        g.drawString(font, "DEV", left + w * 39 / 100, y, MUTED, false);
        g.drawString(font, "POP", left + w * 46 / 100, y, MUTED, false);
        g.drawString(font, "WORK", left + w * 54 / 100, y, MUTED, false);
        g.drawString(font, "GTP 7D", left + w * 64 / 100, y, MUTED, false);
        g.drawString(font, "TRADE 7D", left + w * 76 / 100, y, MUTED, false);
        g.drawString(font, "TREASURY", left + w * 88 / 100, y, MUTED, false);
    }

    private void drawRow(GuiGraphics g, NationalStatisticsPacket.Entry row, int rank, int left, int right, int y) {
        int w = right - left;
        int developmentColor = row.prosperity() >= 60 ? 0xFF7ED7A3
                : row.prosperity() >= 30 ? 0xFFE2C87B : 0xFFB8A37C;
        g.drawString(font, Integer.toString(rank), left + 16, y, GOLD, false);
        g.drawString(font, trim(row.name(), 28), left + 38, y, 0xFFE5E9EC, false);
        g.drawString(font, row.tier(), left + w * 31 / 100, y, 0xFFB8C1C8, false);
        g.drawString(font, row.prosperity() + "/100", left + w * 39 / 100, y, developmentColor, false);
        g.drawString(font, Integer.toString(row.population()), left + w * 46 / 100, y, 0xFFD5DADF, false);
        g.drawString(font, row.employed() + "/" + row.jobs(), left + w * 54 / 100, y, 0xFFD5DADF, false);
        g.drawString(font, "K" + money(row.gtp7d()), left + w * 64 / 100, y, GOLD, false);
        g.drawString(font, signedMoney(row.tradeBalance7d()), left + w * 76 / 100, y,
                row.tradeBalance7d() >= 0 ? GREEN : RED, false);
        g.drawString(font, "K" + money(row.treasury()), left + w * 88 / 100, y, 0xFFD7C48C, false);
    }

    private void drawCharts(GuiGraphics g, int left, int right, int top, int bottom) {
        int gap = 10;
        int width = right - left;
        int chartWidth = (width - gap) / 2;
        int chartHeight = Math.max(78, (bottom - top - gap) / 2);
        List<NationalStatisticsPacket.NationalPoint> history = packet.history();

        drawBarChart(g, history, left, top, chartWidth, chartHeight,
                "GROSS TOWN PRODUCT // 30D", NationalStatisticsPacket.NationalPoint::gtp, GOLD, true, false);
        drawBarChart(g, history, left + chartWidth + gap, top, chartWidth, chartHeight,
                "POPULATION // 30D", NationalStatisticsPacket.NationalPoint::population, BLUE, false, false);
        drawBarChart(g, history, left, top + chartHeight + gap, chartWidth, chartHeight,
                "AVG DEVELOPMENT // 30D", point -> point.averageDevelopment(), STEEL, false, false);
        drawBarChart(g, history, left + chartWidth + gap, top + chartHeight + gap, chartWidth, chartHeight,
                "DOMESTIC TRADE BALANCE // 30D", NationalStatisticsPacket.NationalPoint::tradeBalance,
                GREEN, true, true);
    }

    private void drawBarChart(GuiGraphics g, List<NationalStatisticsPacket.NationalPoint> history,
                              int x, int y, int width, int height, String title,
                              ToLongFunction<NationalStatisticsPacket.NationalPoint> metric,
                              int barColor, boolean moneyMetric, boolean signed) {
        g.fill(x, y, x + width, y + height, 0xCC0D1318);
        g.fill(x, y, x + width, y + 1, GRID);
        g.drawString(font, title, x + 8, y + 7, MUTED, false);
        if (history.isEmpty()) {
            g.drawCenteredString(font, "NO SERIES YET", x + width / 2, y + height / 2, 0xFF65737D);
            return;
        }

        long min = signed ? 0L : Long.MAX_VALUE;
        long max = signed ? 0L : Long.MIN_VALUE;
        for (NationalStatisticsPacket.NationalPoint point : history) {
            long value = metric.applyAsLong(point);
            min = Math.min(min, value);
            max = Math.max(max, value);
        }
        if (!signed) min = Math.min(0L, min);

        long latest = metric.applyAsLong(history.get(history.size() - 1));
        String latestLabel = moneyMetric
                ? (signed ? signedMoney(latest) : "K" + money(latest))
                : money(latest);
        g.drawString(font, latestLabel, x + width - font.width(latestLabel) - 8, y + 7,
                signed && latest < 0 ? RED : TEXT, false);

        int graphLeft = x + 8;
        int graphRight = x + width - 8;
        int graphTop = y + 23;
        int graphBottom = y + height - 12;
        int graphHeight = Math.max(1, graphBottom - graphTop);
        int count = history.size();
        int slot = Math.max(1, (graphRight - graphLeft) / Math.max(1, count));
        int barWidth = Math.max(1, slot - 1);

        if (signed) {
            long absolute = Math.max(1L, Math.max(Math.abs(min), Math.abs(max)));
            int baseline = graphTop + graphHeight / 2;
            g.fill(graphLeft, baseline, graphRight, baseline + 1, 0xFF34414A);
            for (int i = 0; i < count; i++) {
                long value = metric.applyAsLong(history.get(i));
                int h = (int) Math.min(graphHeight / 2L, Math.abs(value) * (graphHeight / 2L) / absolute);
                int bx = graphLeft + i * slot;
                if (value >= 0) {
                    g.fill(bx, baseline - h, Math.min(graphRight, bx + barWidth), baseline, barColor);
                } else {
                    g.fill(bx, baseline + 1, Math.min(graphRight, bx + barWidth), baseline + 1 + h, RED);
                }
            }
        } else {
            long range = Math.max(1L, max - min);
            for (int i = 0; i < count; i++) {
                long value = metric.applyAsLong(history.get(i));
                int h = (int) Math.min(graphHeight, Math.max(1L, (value - min) * graphHeight / range));
                int bx = graphLeft + i * slot;
                g.fill(bx, graphBottom - h, Math.min(graphRight, bx + barWidth), graphBottom, barColor);
            }
        }

        long firstDay = history.get(0).day();
        long lastDay = history.get(history.size() - 1).day();
        g.drawString(font, "D" + firstDay, graphLeft, y + height - 10, 0xFF5F6C75, false);
        String last = "D" + lastDay;
        g.drawString(font, last, graphRight - font.width(last), y + height - 10, 0xFF5F6C75, false);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (super.mouseClicked(mouseX, mouseY, button)) return true;
        if (page != Page.REGISTER || button != 0) return false;

        int panelWidth = Math.min(PANEL_MAX_WIDTH, width - 24);
        int panelHeight = Math.min(PANEL_MAX_HEIGHT, height - 24);
        int left = (width - panelWidth) / 2;
        int top = (height - panelHeight) / 2;
        int right = left + panelWidth;
        int innerLeft = left + 14;
        int innerRight = right - 14;
        int firstY = top + 116 + 18;

        if (mouseX < innerLeft - 4 || mouseX > innerRight + 4) return false;
        int relative = (int) mouseY - (firstY - 4);
        if (relative < 0) return false;
        int visible = relative / ROW_HEIGHT;
        if (visible < 0 || visible >= MAX_VISIBLE_ROWS) return false;
        int index = scrollRow + visible;
        if (index < 0 || index >= packet.settlements().size()) return false;

        selectedRow = index;
        refreshButtons();
        return true;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        if (page != Page.REGISTER) return false;
        int maxScroll = Math.max(0, packet.settlements().size() - MAX_VISIBLE_ROWS);
        scrollRow = Math.max(0, Math.min(maxScroll, scrollRow + (delta < 0 ? 1 : -1)));
        return true;
    }

    private static String trim(String value, int max) {
        return value.length() <= max ? value : value.substring(0, Math.max(1, max - 1)) + "…";
    }

    private static String money(long value) { return String.format("%,d", value); }
    private static String signedMoney(long value) { return (value >= 0 ? "+K" : "-K") + money(Math.abs(value)); }

    @Override
    public boolean isPauseScreen() { return false; }

    private enum Page {
        REGISTER,
        TRENDS
    }
}
