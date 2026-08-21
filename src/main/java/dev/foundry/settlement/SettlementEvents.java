package dev.foundry.settlement;

import dev.foundry.Foundry;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.server.ServerLifecycleHooks;

@Mod.EventBusSubscriber(modid = Foundry.MOD_ID)
public final class SettlementEvents {
    private static final long ECONOMY_CHECK_INTERVAL_TICKS = 20L;

    private SettlementEvents() {
    }

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }

        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server == null) {
            return;
        }

        ServerLevel overworld = server.overworld();
        if (overworld.getGameTime() % ECONOMY_CHECK_INTERVAL_TICKS != 0L) {
            return;
        }

        SettlementSavedData.get(overworld).advanceEconomy(overworld.getDayTime());
    }
}
