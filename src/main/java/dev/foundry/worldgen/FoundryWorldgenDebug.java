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
import java.util.Arrays;
import java.util.Locale;

/**
 * Fast worldgen calibration map for Tiger Ascent's compact strategic geography.
 *
 * <p>The exporter samples the active continentalness router once, then previews exact linear
 * output shifts that would produce 40%, 35%, and 30% land in the sampled region. This makes
 * ocean balance a predictable control instead of repeatedly changing the pre-spline noise field.</p>
 */
@Mod.EventBusSubscriber(modid = Foundry.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class FoundryWorldgenDebug {
    private static final int DEFAULT_SPAN = 12_288;
    private static final int DEFAULT_STEP = 32;
    private static final int MAX_SAMPLES_PER_AXIS = 512;
    private static final int GRID_BLOCKS = 1_000;
    private static final double OCEAN_LAND_THRESHOLD = -0.19;
    private static final String DIAGNOSTIC_VERSION = "FAST-V3 / LINEAR-CALIBRATION";
    private static final String LATEST_PNG = "worldgen-calibration-latest.png";
    private static final String LATEST_REPORT = "worldgen-calibration-latest.txt";

    private FoundryWorldgenDebug() {
    }

    @SubscribeEvent
    public static void registerCommands(RegisterCommandsEvent event) {
        event.getDispatcher().register(
                Commands.literal("foundry")
                        .then(Commands.literal("worldgenmap")
                                .executes(context -> export(context.getSource(), DEFAULT_SPAN, DEFAULT_STEP))
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

        source.sendSystemMessage(Component.literal("[Foundry] WORLDGEN CALIBRATION STARTED — " + DIAGNOSTIC_VERSION)
                .withStyle(ChatFormatting.YELLOW, ChatFormatting.BOLD));

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

            double[] values = new double[samples * samples];
            double minContinentalness = Double.POSITIVE_INFINITY;
            double maxContinentalness = Double.NEGATIVE_INFINITY;

            for (int py = 0; py < samples; py++) {
                int worldZ = startZ + py * step;
                for (int px = 0; px < samples; px++) {
                    int worldX = startX + px * step;
                    int index = py * samples + px;
                    double c = continentalness.compute(new DensityFunction.SinglePointContext(worldX, 0, worldZ));
                    values[index] = c;
                    minContinentalness = Math.min(minContinentalness, c);
                    maxContinentalness = Math.max(maxContinentalness, c);
                }
            }

            double[] sorted = values.clone();
            Arrays.sort(sorted);

            Candidate current = buildCandidate("CURRENT ACTIVE", values, samples, step, 0.0);
            Candidate target40 = buildTargetCandidate("TARGET 40% LAND", values, sorted, samples, step, 0.40);
            Candidate target35 = buildTargetCandidate("TARGET 35% LAND", values, sorted, samples, step, 0.35);
            Candidate target30 = buildTargetCandidate("TARGET 30% LAND", values, sorted, samples, step, 0.30);

            double elapsedSeconds = (System.nanoTime() - startedNanos) / 1_000_000_000.0;
            BufferedImage diagnostic = composeDiagnostic(
                    current,
                    target40,
                    target35,
                    target30,
                    startX,
                    startZ,
                    coveredSpan,
                    step,
                    minContinentalness,
                    maxContinentalness,
                    elapsedSeconds
            );

            Path outputDir = FMLPaths.GAMEDIR.get().resolve("foundry-debug");
            Files.createDirectories(outputDir);
            Path pngPath = outputDir.resolve(LATEST_PNG).toAbsolutePath().normalize();
            Path reportPath = outputDir.resolve(LATEST_REPORT).toAbsolutePath().normalize();
            ImageIO.write(diagnostic, "png", pngPath.toFile());

            String report = "Foundry worldgen linear calibration\n"
                    + "diagnostic version: " + DIAGNOSTIC_VERSION + "\n"
                    + "center: " + centerX + ", " + centerZ + "\n"
                    + "span: " + coveredSpan + " blocks\n"
                    + "sample step: " + step + " blocks\n"
                    + "land/ocean threshold: " + OCEAN_LAND_THRESHOLD + "\n"
                    + candidateReportLine(current)
                    + candidateReportLine(target40)
                    + candidateReportLine(target35)
                    + candidateReportLine(target30)
                    + String.format(Locale.ROOT, "continentalness range: %.3f to %.3f\n", minContinentalness, maxContinentalness)
                    + String.format(Locale.ROOT, "sampling time: %.2f seconds\n", elapsedSeconds)
                    + "PNG: " + pngPath + "\n";
            Files.writeString(reportPath, report);

            source.sendSystemMessage(Component.literal("[Foundry] WORLDGEN CALIBRATION SUCCESS")
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
                    "current %.1f%% land | exact extra shifts: 40%% %+.3f | 35%% %+.3f | 30%% %+.3f",
                    current.landShare(),
                    target40.shift(),
                    target35.shift(),
                    target30.shift()
            )).withStyle(ChatFormatting.GRAY));
            return 1;
        } catch (Exception exception) {
            FoundryWorldgenDebugLog.error("Failed to export worldgen calibration", exception);
            source.sendSystemMessage(Component.literal("[Foundry] WORLDGEN CALIBRATION FAILED")
                    .withStyle(ChatFormatting.RED, ChatFormatting.BOLD));
            source.sendSystemMessage(Component.literal(
                    exception.getClass().getSimpleName() + ": " + String.valueOf(exception.getMessage())
            ).withStyle(ChatFormatting.RED));
            return 0;
        }
    }

    private static Candidate buildTargetCandidate(
            String label,
            double[] values,
            double[] sorted,
            int size,
            int step,
            double targetLandFraction
    ) {
        double quantile = 1.0 - targetLandFraction;
        int index = (int) Math.floor(quantile * (sorted.length - 1));
        index = Math.max(0, Math.min(sorted.length - 1, index));
        double requiredUnshiftedThreshold = sorted[index];
        double shift = OCEAN_LAND_THRESHOLD - requiredUnshiftedThreshold;
        return buildCandidate(label, values, size, step, shift);
    }

    private static Candidate buildCandidate(String label, double[] values, int size, int step, double shift) {
        BufferedImage image = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        boolean[] land = new boolean[values.length];
        int landSamples = 0;

        for (int index = 0; index < values.length; index++) {
            double shifted = values[index] + shift;
            boolean isLand = shifted >= OCEAN_LAND_THRESHOLD;
            land[index] = isLand;
            if (isLand) {
                landSamples++;
            }
            int x = index % size;
            int y = index / size;
            image.setRGB(x, y, maskColor(shifted).getRGB());
        }

        double landShare = landSamples * 100.0 / values.length;
        LargestComponent largest = findLargestLandComponent(land, size, step);
        return new Candidate(label, shift, landShare, largest, image);
    }

    private static BufferedImage composeDiagnostic(
            Candidate current,
            Candidate target40,
            Candidate target35,
            Candidate target30,
            int startX,
            int startZ,
            int coveredSpan,
            int step,
            double minContinentalness,
            double maxContinentalness,
            double elapsedSeconds
    ) {
        int panelSize = current.image().getWidth();
        int margin = 28;
        int gap = 24;
        int rowGap = 24;
        int labelHeight = 42;
        int footer = 94;
        int width = margin * 2 + panelSize * 2 + gap;
        int height = margin + (labelHeight + panelSize) * 2 + rowGap + footer;

        BufferedImage output = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = output.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g.setColor(new Color(20, 23, 27));
            g.fillRect(0, 0, width, height);

            int leftX = margin;
            int rightX = margin + panelSize + gap;
            int topY = margin;
            int bottomY = margin + labelHeight + panelSize + rowGap;

            drawCandidatePanel(g, current, leftX, topY, panelSize, startX, startZ, coveredSpan, step);
            drawCandidatePanel(g, target40, rightX, topY, panelSize, startX, startZ, coveredSpan, step);
            drawCandidatePanel(g, target35, leftX, bottomY, panelSize, startX, startZ, coveredSpan, step);
            drawCandidatePanel(g, target30, rightX, bottomY, panelSize, startX, startZ, coveredSpan, step);

            int footerY = bottomY + labelHeight + panelSize + 24;
            g.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
            g.setColor(new Color(225, 229, 235));
            g.drawString("diagnostic: " + DIAGNOSTIC_VERSION, margin, footerY);
            g.drawString(String.format(Locale.ROOT, "span %,d | step %d | active range %.3f .. %.3f", coveredSpan, step, minContinentalness, maxContinentalness), margin, footerY + 18);
            g.drawString(String.format(Locale.ROOT, "sampling %.2fs | candidate shifts are ADDITIONAL linear output shifts", elapsedSeconds), margin, footerY + 36);
            g.drawString("Choose by connectivity + strategic scale, not land percentage alone.", margin, footerY + 54);
        } finally {
            g.dispose();
        }
        return output;
    }

    private static void drawCandidatePanel(
            Graphics2D g,
            Candidate candidate,
            int panelX,
            int panelTop,
            int panelSize,
            int startX,
            int startZ,
            int coveredSpan,
            int step
    ) {
        g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 14));
        g.setColor(Color.WHITE);
        g.drawString(candidate.label(), panelX, panelTop + 14);

        String largestText = largestText(candidate.largest());
        g.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 11));
        g.setColor(new Color(190, 196, 205));
        g.drawString(String.format(Locale.ROOT, "shift %+.3f | land %.1f%% | largest %s", candidate.shift(), candidate.landShare(), largestText), panelX, panelTop + 32);

        int imageY = panelTop + 42;
        g.drawImage(candidate.image(), panelX, imageY, null);
        drawGrid(g, panelX, imageY, panelSize, startX, startZ, coveredSpan, step);
        g.setColor(new Color(245, 245, 245));
        g.setStroke(new BasicStroke(1.2f));
        g.drawRect(panelX, imageY, panelSize - 1, panelSize - 1);
    }

    private static String candidateReportLine(Candidate candidate) {
        return String.format(
                Locale.ROOT,
                "%s: shift %+.4f | land %.1f%% | largest %s | touches edge %s\n",
                candidate.label(),
                candidate.shift(),
                candidate.landShare(),
                largestText(candidate.largest()),
                candidate.largest().touchesEdge()
        );
    }

    private static String largestText(LargestComponent largest) {
        if (largest.areaSamples() == 0) {
            return "none";
        }
        return (largest.touchesEdge() ? ">=" : "")
                + largest.widthBlocks() + "x" + largest.heightBlocks();
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

    private record Candidate(String label, double shift, double landShare, LargestComponent largest, BufferedImage image) {
    }

    private record LargestComponent(int areaSamples, int widthBlocks, int heightBlocks, boolean touchesEdge) {
    }

    private static final class FoundryWorldgenDebugLog {
        private FoundryWorldgenDebugLog() {
        }

        private static void error(String message, Throwable throwable) {
            System.err.println("[Foundry] " + message);
            throwable.printStackTrace(System.err);
        }
    }
}
