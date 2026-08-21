package dev.foundry.network;

import dev.foundry.Foundry;
import dev.foundry.network.packet.SettlementSnapshotPacket;
import dev.foundry.settlement.Settlement;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.simple.SimpleChannel;

public final class FoundryNetwork {
    private static final String PROTOCOL_VERSION = "2";
    private static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
            new ResourceLocation(Foundry.MOD_ID, "main"),
            () -> PROTOCOL_VERSION,
            PROTOCOL_VERSION::equals,
            PROTOCOL_VERSION::equals
    );

    private static int nextPacketId = 0;

    private FoundryNetwork() {
    }

    public static void register() {
        CHANNEL.registerMessage(
                nextPacketId++,
                SettlementSnapshotPacket.class,
                SettlementSnapshotPacket::encode,
                SettlementSnapshotPacket::decode,
                SettlementSnapshotPacket::handle
        );
    }

    public static void sendSettlementSnapshot(ServerPlayer player, Settlement settlement) {
        CHANNEL.send(
                PacketDistributor.PLAYER.with(() -> player),
                SettlementSnapshotPacket.from(settlement)
        );
    }
}
