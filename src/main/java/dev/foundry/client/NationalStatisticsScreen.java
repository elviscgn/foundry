package dev.foundry.client;

import dev.foundry.network.packet.NationalStatisticsPacket;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

import java.util.List;

@OnlyIn(Dist.CLIENT)
public final class NationalStatisticsScreen extends Screen {
    private static final int PANEL_MAX_WIDTH = 860;
    private static final int PANEL_MAX_HEIGHT = 500;
    private static final int ROW_HEIGHT = 18;
    private final NationalStatisticsPacket packet;
    private int scrollRow;

    public NationalStatisticsScreen(NationalStatisticsPacket packet) {
        super(Component.literal("National Statistics Bureau"));
        this.packet = packet;
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

        graphics.fill(left, top, right, bottom, 0xF20B1015);
        graphics.fill(left, top, right, top + 3, 0xFFB69454);
        graphics.drawString(font, "FOUNDRY // NATIONAL STATISTICS BUREAU", left + 14, top + 12, 0xFFE9DFC8, false);
        graphics.drawString(font, "SETTLEMENT REGISTER // DEVELOPMENT & FISCAL RETURNS", left + 14, top + 26, 0xFF8F9BA5, false);

        List<NationalStatisticsPacket.Entry> rows = packet.settlements();
        long population = rows.stream().mapToLong(NationalStatisticsPacket.Entry::population).sum();
        long treasury = rows.stream().mapToLong(NationalStatisticsPacket.Entry::treasury).sum();
        int averageProsperity = rows.isEmpty() ? 0 : (int) Math.round(rows.stream().mapToInt(NationalStatisticsPacket.Entry::prosperity).average().orElse(0));
        String summary = "Settlements " + rows.size() + "   Population " + population
                + "   Avg development " + averageProsperity + "/100   Municipal treasuries K" + money(treasury);
        graphics.drawString(font, summary, left + 14, top + 45, 0xFFD5C59F, false);

        int headerY = top + 70;
        graphics.fill(left + 10, headerY - 5, right - 10, headerY + 11, 0xFF151D24);
        drawHeaders(graphics, left, right, headerY);

        int firstY = headerY + 18;
        int available = Math.max(1, (bottom - firstY - 18) / ROW_HEIGHT);
        int maxScroll = Math.max(0, rows.size() - available);
        scrollRow = Math.min(scrollRow, maxScroll);

        for (int visible = 0; visible < available; visible++) {
            int index = scrollRow + visible;
            if (index >= rows.size()) break;
            NationalStatisticsPacket.Entry row = rows.get(index);
            int y = firstY + visible * ROW_HEIGHT;
            if ((index & 1) == 1) graphics.fill(left + 10, y - 4, right - 10, y + 12, 0x6619222A);
            drawRow(graphics, row, index + 1, left, right, y);
        }

        if (rows.isEmpty()) {
            graphics.drawCenteredString(font, "NO REGISTERED SETTLEMENTS", width / 2, firstY + 25, 0xFF8F9BA5);
        }
        graphics.drawString(font, "Ranked by structural Prosperity. Scroll to inspect the national register.",
                left + 14, bottom - 15, 0xFF6E7C87, false);
        super.render(graphics, mouseX, mouseY, partialTick);
    }

    private void drawHeaders(GuiGraphics g, int left, int right, int y) {
        int w = right - left;
        g.drawString(font, "#", left + 16, y, 0xFF9DA8B0, false);
        g.drawString(font, "SETTLEMENT", left + 38, y, 0xFF9DA8B0, false);
        g.drawString(font, "TIER", left + w * 35 / 100, y, 0xFF9DA8B0, false);
        g.drawString(font, "DEV", left + w * 44 / 100, y, 0xFF9DA8B0, false);
        g.drawString(font, "POP", left + w * 51 / 100, y, 0xFF9DA8B0, false);
        g.drawString(font, "WORK", left + w * 59 / 100, y, 0xFF9DA8B0, false);
        g.drawString(font, "PROD 7D", left + w * 68 / 100, y, 0xFF9DA8B0, false);
        g.drawString(font, "TRADE", left + w * 77 / 100, y, 0xFF9DA8B0, false);
        g.drawString(font, "TREASURY", left + w * 87 / 100, y, 0xFF9DA8B0, false);
    }

    private void drawRow(GuiGraphics g, NationalStatisticsPacket.Entry row, int rank, int left, int right, int y) {
        int w = right - left;
        int developmentColor = row.prosperity() >= 60 ? 0xFF7ED7A3 : row.prosperity() >= 30 ? 0xFFE2C87B : 0xFFB8A37C;
        g.drawString(font, Integer.toString(rank), left + 16, y, 0xFFB69454, false);
        g.drawString(font, trim(row.name(), 28), left + 38, y, 0xFFE5E9EC, false);
        g.drawString(font, row.tier(), left + w * 35 / 100, y, 0xFFB8C1C8, false);
        g.drawString(font, row.prosperity() + "/100", left + w * 44 / 100, y, developmentColor, false);
        g.drawString(font, Integer.toString(row.population()), left + w * 51 / 100, y, 0xFFD5DADF, false);
        g.drawString(font, row.employed() + "/" + row.jobs(), left + w * 59 / 100, y, 0xFFD5DADF, false);
        g.drawString(font, Integer.toString(row.production7d()), left + w * 68 / 100, y, 0xFFD5DADF, false);
        g.drawString(font, signedMoney(row.tradeBalance7d()), left + w * 77 / 100, y,
                row.tradeBalance7d() >= 0 ? 0xFF8CC8A2 : 0xFFD58C8C, false);
        g.drawString(font, "K" + money(row.treasury()), left + w * 87 / 100, y, 0xFFD7C48C, false);
        if (row.warehouses() > 0) {
            g.drawString(font, "W" + row.warehouses(), right - 28, y, 0xFF879AA8, false);
        }
    }

    private static String trim(String value, int max) {
        return value.length() <= max ? value : value.substring(0, Math.max(1, max - 1)) + "…";
    }

    private static String money(long value) { return String.format("%,d", value); }
    private static String signedMoney(long value) { return (value >= 0 ? "+K" : "-K") + money(Math.abs(value)); }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        int visible = Math.max(1, (Math.min(PANEL_MAX_HEIGHT, height - 24) - 106) / ROW_HEIGHT);
        int maxScroll = Math.max(0, packet.settlements().size() - visible);
        scrollRow = Math.max(0, Math.min(maxScroll, scrollRow + (delta < 0 ? 1 : -1)));
        return true;
    }

    @Override
    public boolean isPauseScreen() { return false; }
}
