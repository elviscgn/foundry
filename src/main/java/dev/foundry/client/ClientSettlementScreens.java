package dev.foundry.client;

import dev.foundry.network.packet.SettlementSnapshotPacket;
import net.minecraft.client.Minecraft;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

@OnlyIn(Dist.CLIENT)
public final class ClientSettlementScreens {
    private ClientSettlementScreens() {
    }

    public static void open(SettlementSnapshotPacket packet) {
        Minecraft.getInstance().setScreen(new SettlementScreen(packet));
    }
}
