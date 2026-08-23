package dev.foundry.network.packet;

import dev.foundry.network.FoundryNetwork;
import dev.foundry.settlement.Settlement;
import dev.foundry.settlement.SettlementSavedData;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import java.util.UUID;
import java.util.function.Supplier;

/** Requests the authoritative civic ledger for one settlement selected in the national register. */
public record RequestSettlementSnapshotPacket(UUID settlementId) {
    public static void encode(RequestSettlementSnapshotPacket packet, FriendlyByteBuf buffer) {
        buffer.writeUUID(packet.settlementId);
    }

    public static RequestSettlementSnapshotPacket decode(FriendlyByteBuf buffer) {
        return new RequestSettlementSnapshotPacket(buffer.readUUID());
    }

    public static void handle(RequestSettlementSnapshotPacket packet,
                              Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context context = contextSupplier.get();
        context.enqueueWork(() -> {
            ServerPlayer sender = context.getSender();
            if (sender == null) return;
            SettlementSavedData data = SettlementSavedData.get(sender.serverLevel());
            Settlement settlement = data.getSettlement(packet.settlementId);
            if (settlement != null) FoundryNetwork.sendSettlementSnapshot(sender, settlement);
        });
        context.setPacketHandled(true);
    }
}
