package dev.foundry.command;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import dev.foundry.Foundry;
import dev.foundry.settlement.SettlementSavedData;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

@Mod.EventBusSubscriber(modid = Foundry.MOD_ID)
public final class FoundryCommands {
    private static final long TICKS_PER_DAY = 24_000L;

    private FoundryCommands() {
    }

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        event.getDispatcher().register(
                Commands.literal("foundry")
                        .requires(source -> source.hasPermission(2))
                        .then(Commands.literal("advance")
                                .then(Commands.argument("days", IntegerArgumentType.integer(1, 3650))
                                        .executes(context -> advanceDays(
                                                context.getSource(),
                                                IntegerArgumentType.getInteger(context, "days")
                                        ))))
        );
    }

    private static int advanceDays(CommandSourceStack source, int days) {
        ServerLevel overworld = source.getServer().overworld();
        long tickDelta = (long) days * TICKS_PER_DAY;
        overworld.setDayTime(overworld.getDayTime() + tickDelta);

        long processedDays = SettlementSavedData.get(overworld).advanceEconomy(overworld.getDayTime());
        source.sendSuccess(
                () -> Component.literal("Foundry advanced " + processedDays + " economy day(s)."),
                true
        );
        return (int) Math.min(processedDays, Integer.MAX_VALUE);
    }
}
