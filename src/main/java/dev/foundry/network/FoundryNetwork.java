package dev.foundry.network;

import dev.foundry.Foundry;
import dev.foundry.network.packet.NationalStatisticsPacket;
import dev.foundry.network.packet.RequestSettlementSnapshotPacket;
import dev.foundry.network.packet.SettlementSnapshotPacket;
import dev.foundry.settlement.Settlement;
import dev.foundry.settlement.SettlementSavedData;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.simple.SimpleChannel;

import java.util.UUID;

public final class FoundryNetwork {
    private static final String PROTOCOL_VERSION = "6";
    private static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
            new ResourceLocation(Foundry.MOD_ID, "main"),
            () -> PROTOCOL_VERSION,
            PROTOCOL_VERSION::equals,
            PROTOCOL_VERSION::equals
    );
    private static int nextPacketId = 0;

    private FoundryNetwork() { }

    public static void register() {
        CHANNEL.registerMessage(nextPacketId++, SettlementSnapshotPacket.class,
                SettlementSnapshotPacket::encode, SettlementSnapshotPacket::decode, SettlementSnapshotPacket::handle);
        CHANNEL.registerMessage(nextPacketId++, NationalStatisticsPacket.class,
                NationalStatisticsPacket::encode, NationalStatisticsPacket::decode, NationalStatisticsPacket::handle);
        CHANNEL.registerMessage(nextPacketId++, RequestSettlementSnapshotPacket.class,
                RequestSettlementSnapshotPacket::encode,
                RequestSettlementSnapshotPacket::decode,
                RequestSettlementSnapshotPacket::handle);
    }

    public static void sendSettlementSnapshot(ServerPlayer player, Settlement settlement) {
        SettlementSavedData data = SettlementSavedData.get(player.serverLevel());
        CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), SettlementSnapshotPacket.from(settlement, data));
    }

    public static void sendNationalStatistics(ServerPlayer player) {
        SettlementSavedData data = SettlementSavedData.get(player.serverLevel());
        CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), NationalStatisticsPacket.from(data));
    }

    public static void requestSettlementSnapshot(UUID settlementId) {
        if (settlementId != null) CHANNEL.sendToServer(new RequestSettlementSnapshotPacket(settlementId));
    }
}
