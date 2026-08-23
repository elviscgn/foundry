package dev.foundry.worldgen;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import dev.foundry.Foundry;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.loading.FMLPaths;

import javax.imageio.ImageIO;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/**
 * Fast development diagnostic for tuning Tiger Ascent's national-scale geography.
 *
 * <p>This deliberately samples the active continentalness router directly instead of asking the
 * chunk generator for a full terrain height at every pixel. Full height generation was far too
 * expensive for a 12k-block overview and could stall the integrated server for minutes. The fast
 * mask uses Minecraft's ocean/land continentalness boundary (-0.19), which is exactly the layer we
 * are tuning for continent size and ocean separation.</p>
 */
@Mod.EventBusSubscriber(modid = Foundry.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class FoundryWorldgenDebug {
    private static final int DEFAULT_SPAN = 12_288;
    private static final int DEFAULT_STEP = 32;
    private static final int MAX_SAMPLES_PER_AXIS = 512;
    private static final int GRID_BLOCKS = 1_000;
    private static final double OCEAN_LAND_THRESHOLD = -0.19;
    private static final String DIAGNOSTIC_VERSION = "FAST-V2 / SCALE-CHECK";
    private static final String LATEST_PNG = "worldgen-fast-latest.png";
    private static final String LATEST_REPORT = "worldgen-fast-latest.txt";

    private FoundryWorldgenDebug() {
    }

    @SubscribeEvent
    public static void registerCommands(RegisterCommandsEvent event) {
        event.getDispatcher().register(
                Commands.literal("foundry")
                        .then(Commands.literal("worldgenmap")
                                .executes(context -> export(
                                        context.getSource(),
                                        DEFAULT_SPAN,
                                        DEFAULT_STEP
                                ))
                                .then(Commands.argument("span", IntegerArgumentType.integer(2_048, 20_000))
                                        .executes(context -> export(
                                                context.getSource(),
                                                IntegerArgumentType.getInteger(context, "span"),
                                                DEFAULT_STEP
                                        ))
                                        .then(Commands.argument("step", IntegerArgumentType.integer(8, 256))
                                                .executes(context -> export(
                                                        context.getSource(),
                                                        IntegerArgumentType.getInteger(context, "span"),
                                                        IntegerArgumentType.getInteger(context, "step")
                                                )))))
        );
    }

    private static int export(CommandSourceStack source, int requestedSpan, int step) {
        int samples = (requestedSpan + step - 1) / step;
        if (samples > MAX_SAMPLES_PER_AXIS) {
            int suggestedStep = (requestedSpan + MAX_SAMPLES_PER_AXIS - 1) / MAX_SAMPLES_PER_AXIS;
            source.sendSystemMessage(Component.literal("[Foundry] WORLDGEN EXPORT REJECTED")
                    .withStyle(ChatFormatting.RED, ChatFormatting.BOLD));
            source.sendSystemMessage(Component.literal(
                    "Map would be " + samples + "x" + samples + " samples. Use step >= " + suggestedStep + "."
            ).withStyle(ChatFormatting.RED));
            return 0;
        }

        source.sendSystemMessage(Component.literal("[Foundry] FAST WORLDGEN EXPORT STARTED — " + DIAGNOSTIC_VERSION)
                .withStyle(ChatFormatting.YELLOW, ChatFormatting.BOLD));
        source.sendSystemMessage(Component.literal(
                "Sampling active continentalness across " + requestedSpan + " x " + requestedSpan
                        + " blocks at " + step + "-block resolution..."
        ).withStyle(ChatFormatting.GRAY));

        long startedNanos = System.nanoTime();

        try {
            ServerLevel level = source.getLevel();
            RandomState randomState = level.getChunkSource().randomState();
            DensityFunction continentalness = randomState.router().continents();

            int centerX = (int) Math.floor(source.getPosition().x);
            int centerZ = (int) Math.floor(source.getPosition().z);
            int coveredSpan = samples * step;
            int startX = centerX - coveredSpan / 2;
            int startZ = centerZ - coveredSpan / 2;

            BufferedImage mask = new BufferedImage(samples, samples, BufferedImage.TYPE_INT_ARGB);
            BufferedImage continents = new BufferedImage(samples, samples, BufferedImage.TYPE_INT_ARGB);
            boolean[] landMask = new boolean[samples * samples];

            int landSamples = 0;
            double minContinentalness = Double.POSITIVE_INFINITY;
            double maxContinentalness = Double.NEGATIVE_INFINITY;

            for (int py = 0; py < samples; py++) {
                int worldZ = startZ + py * step;
                for (int px = 0; px < samples; px++) {
                    int worldX = startX + px * step;
                    int index = py * samples + px;

                    double c = continentalness.compute(new DensityFunction.SinglePointContext(worldX, 0, worldZ));
                    minContinentalness = Math.min(minContinentalness, c);
                    maxContinentalness = Math.max(maxContinentalness, c);

                    boolean isLand = c >= OCEAN_LAND_THRESHOLD;
                    landMask[index] = isLand;
                    if (isLand) {
                        landSamples++;
                    }

                    mask.setRGB(px, py, maskColor(c).getRGB());
                    continents.setRGB(px, py, continentalnessColor(c).getRGB());
                }
            }

            LargestComponent largest = findLargestLandComponent(landMask, samples, step);
            double landShare = landSamples * 100.0 / landMask.length;
            double elapsedSeconds = (System.nanoTime() - startedNanos) / 1_000_000_000.0;

            BufferedImage diagnostic = composeDiagnostic(
                    mask,
                    continents,
                    startX,
                    startZ,
                    coveredSpan,
                    step,
                    landShare,
                    largest,
                    minContinentalness,
                    maxContinentalness,
                    elapsedSeconds
            );

            Path outputDir = FMLPaths.GAMEDIR.get().resolve("foundry-debug");
            Files.createDirectories(outputDir);
            Path pngPath = outputDir.resolve(LATEST_PNG).toAbsolutePath().normalize();
            Path reportPath = outputDir.resolve(LATEST_REPORT).toAbsolutePath().normalize();
            ImageIO.write(diagnostic, "png", pngPath.toFile());

            String largestText = largest.areaSamples() == 0
                    ? "none"
                    : (largest.touchesEdge() ? ">= " : "")
                    + largest.widthBlocks() + " x " + largest.heightBlocks() + " blocks";
            String report = "Foundry fast worldgen diagnostic\n"
                    + "diagnostic version: " + DIAGNOSTIC_VERSION + "\n"
                    + "center: " + centerX + ", " + centerZ + "\n"
                    + "span: " + coveredSpan + " blocks\n"
                    + "sample step: " + step + " blocks\n"
                    + "land/ocean classification: continentalness >= " + OCEAN_LAND_THRESHOLD + " is land\n"
                    + String.format(Locale.ROOT, "land share: %.1f%%\n", landShare)
                    + "largest visible connected landmass: " + largestText + "\n"
                    + "largest touches map edge: " + largest.touchesEdge() + "\n"
                    + String.format(Locale.ROOT, "continentalness range: %.3f to %.3f\n", minContinentalness, maxContinentalness)
                    + String.format(Locale.ROOT, "sampling time: %.2f seconds\n", elapsedSeconds)
                    + "PNG: " + pngPath + "\n";
            Files.writeString(reportPath, report);

            source.sendSystemMessage(Component.literal("[Foundry] WORLDGEN EXPORT SUCCESS — " + DIAGNOSTIC_VERSION)
                    .withStyle(ChatFormatting.GREEN, ChatFormatting.BOLD));
            source.sendSystemMessage(Component.literal("UPLOAD THIS FILE HERE: ")
                    .withStyle(ChatFormatting.WHITE)
                    .append(Component.literal("run/foundry-debug/" + LATEST_PNG)
                            .withStyle(ChatFormatting.AQUA, ChatFormatting.UNDERLINE)));
            source.sendSystemMessage(Component.literal("[COPY FULL PATH]")
                    .withStyle(style -> style
                            .withColor(ChatFormatting.AQUA)
                            .withUnderlined(true)
                            .withClickEvent(new ClickEvent(
                                    ClickEvent.Action.COPY_TO_CLIPBOARD,
                                    pngPath.toString()
                            ))));
            source.sendSystemMessage(Component.literal(String.format(
                    Locale.ROOT,
                    "%.2fs | land %.1f%% | largest visible landmass %s | span %,d blocks",
                    elapsedSeconds,
                    landShare,
                    largestText,
                    coveredSpan
            )).withStyle(ChatFormatting.GRAY));
            return 1;
        } catch (Exception exception) {
            FoundryWorldgenDebugLog.error("Failed to export worldgen diagnostic", exception);
            source.sendSystemMessage(Component.literal("[Foundry] WORLDGEN EXPORT FAILED")
                    .withStyle(ChatFormatting.RED, ChatFormatting.BOLD));
            source.sendSystemMessage(Component.literal(
                    exception.getClass().getSimpleName() + ": " + String.valueOf(exception.getMessage())
            ).withStyle(ChatFormatting.RED));
            return 0;
        }
    }

    private static BufferedImage composeDiagnostic(
            BufferedImage mask,
            BufferedImage continents,
            int startX,
            int startZ,
            int coveredSpan,
            int step,
            double landShare,
            LargestComponent largest,
            double minContinentalness,
            double maxContinentalness,
            double elapsedSeconds
    ) {
        int panelSize = mask.getWidth();
        int margin = 28;
        int header = 68;
        int footer = 114;
        int gap = 24;
        int width = margin * 2 + panelSize * 2 + gap;
        int height = header + panelSize + footer;

        BufferedImage output = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = output.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g.setColor(new Color(20, 23, 27));
            g.fillRect(0, 0, width, height);

            g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 15));
            g.setColor(Color.WHITE);
            g.drawString("CONTINENT MASK (FAST-V2)", margin, 24);
            g.drawString("ACTIVE CONTINENTALNESS", margin + panelSize + gap, 24);

            g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 12));
            g.setColor(new Color(190, 196, 205));
            g.drawString("Ocean c < -0.19 | land c >= -0.19", margin, 44);
            g.drawString("Raw router output; blue = oceanward, green = landward", margin + panelSize + gap, 44);

            int panelY = header;
            int maskX = margin;
            int continentsX = margin + panelSize + gap;
            g.drawImage(mask, maskX, panelY, null);
            g.drawImage(continents, continentsX, panelY, null);

            drawGrid(g, maskX, panelY, panelSize, startX, startZ, coveredSpan, step);
            drawGrid(g, continentsX, panelY, panelSize, startX, startZ, coveredSpan, step);

            g.setColor(new Color(245, 245, 245));
            g.setStroke(new BasicStroke(1.2f));
            g.drawRect(maskX, panelY, panelSize - 1, panelSize - 1);
            g.drawRect(continentsX, panelY, panelSize - 1, panelSize - 1);

            String largestText = largest.areaSamples() == 0
                    ? "none"
                    : (largest.touchesEdge() ? ">= " : "")
                    + largest.widthBlocks() + " x " + largest.heightBlocks() + " blocks";

            int footerY = panelY + panelSize + 24;
            g.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
            g.setColor(new Color(225, 229, 235));
            g.drawString("diagnostic: " + DIAGNOSTIC_VERSION, margin, footerY);
            g.drawString(String.format(Locale.ROOT, "span %,d blocks | step %d | land %.1f%%", coveredSpan, step, landShare), margin, footerY + 18);
            g.drawString("largest visible landmass: " + largestText, margin, footerY + 36);
            g.drawString(String.format(Locale.ROOT, "continentalness %.3f .. %.3f", minContinentalness, maxContinentalness), margin, footerY + 54);
            g.drawString(String.format(Locale.ROOT, "sampling %.2fs", elapsedSeconds), margin, footerY + 72);
            g.drawString("N ^   +X east ->   +Z south", continentsX, footerY);
        } finally {
            g.dispose();
        }
        return output;
    }

    private static void drawGrid(
            Graphics2D g,
            int panelX,
            int panelY,
            int panelSize,
            int startX,
            int startZ,
            int coveredSpan,
            int step
    ) {
        int endX = startX + coveredSpan;
        int endZ = startZ + coveredSpan;
        int firstX = Math.floorDiv(startX + GRID_BLOCKS - 1, GRID_BLOCKS) * GRID_BLOCKS;
        int firstZ = Math.floorDiv(startZ + GRID_BLOCKS - 1, GRID_BLOCKS) * GRID_BLOCKS;

        g.setColor(new Color(255, 255, 255, 65));
        g.setStroke(new BasicStroke(1.0f));
        for (int x = firstX; x <= endX; x += GRID_BLOCKS) {
            int px = (x - startX) / step;
            if (px >= 0 && px < panelSize) {
                g.drawLine(panelX + px, panelY, panelX + px, panelY + panelSize - 1);
            }
        }
        for (int z = firstZ; z <= endZ; z += GRID_BLOCKS) {
            int py = (z - startZ) / step;
            if (py >= 0 && py < panelSize) {
                g.drawLine(panelX, panelY + py, panelX + panelSize - 1, panelY + py);
            }
        }
    }

    private static Color maskColor(double value) {
        if (value < -0.45) {
            return new Color(23, 60, 105);
        }
        if (value < OCEAN_LAND_THRESHOLD) {
            return new Color(55, 116, 157);
        }
        if (value < -0.11) {
            return new Color(194, 181, 128);
        }
        return new Color(83, 127, 74);
    }

    private static Color continentalnessColor(double value) {
        if (value < -0.95) {
            return new Color(18, 43, 79);
        }
        if (value < -0.45) {
            return new Color(29, 78, 121);
        }
        if (value < OCEAN_LAND_THRESHOLD) {
            return new Color(62, 130, 159);
        }
        if (value < 0.0) {
            return new Color(188, 176, 125);
        }
        if (value < 0.35) {
            return new Color(86, 128, 75);
        }
        return new Color(126, 111, 81);
    }

    private static LargestComponent findLargestLandComponent(boolean[] land, int size, int step) {
        boolean[] visited = new boolean[land.length];
        int[] queue = new int[land.length];
        LargestComponent best = new LargestComponent(0, 0, 0, false);
        int[] dx = {1, -1, 0, 0};
        int[] dy = {0, 0, 1, -1};

        for (int start = 0; start < land.length; start++) {
            if (!land[start] || visited[start]) {
                continue;
            }

            int head = 0;
            int tail = 0;
            queue[tail++] = start;
            visited[start] = true;

            int area = 0;
            int minX = size;
            int maxX = -1;
            int minY = size;
            int maxY = -1;
            boolean touchesEdge = false;

            while (head < tail) {
                int index = queue[head++];
                int x = index % size;
                int y = index / size;
                area++;
                minX = Math.min(minX, x);
                maxX = Math.max(maxX, x);
                minY = Math.min(minY, y);
                maxY = Math.max(maxY, y);
                if (x == 0 || y == 0 || x == size - 1 || y == size - 1) {
                    touchesEdge = true;
                }

                for (int direction = 0; direction < 4; direction++) {
                    int nx = x + dx[direction];
                    int ny = y + dy[direction];
                    if (nx < 0 || ny < 0 || nx >= size || ny >= size) {
                        continue;
                    }
                    int neighbor = ny * size + nx;
                    if (land[neighbor] && !visited[neighbor]) {
                        visited[neighbor] = true;
                        queue[tail++] = neighbor;
                    }
                }
            }

            if (area > best.areaSamples()) {
                best = new LargestComponent(
                        area,
                        (maxX - minX + 1) * step,
                        (maxY - minY + 1) * step,
                        touchesEdge
                );
            }
        }
        return best;
    }

    private record LargestComponent(int areaSamples, int widthBlocks, int heightBlocks, boolean touchesEdge) {
    }

    /** Keeps this helper isolated from the main mod logger API while still surfacing stack traces. */
    private static final class FoundryWorldgenDebugLog {
        private FoundryWorldgenDebugLog() {
        }

        private static void error(String message, Throwable throwable) {
            System.err.println("[Foundry] " + message);
            throwable.printStackTrace(System.err);
        }
    }
}
