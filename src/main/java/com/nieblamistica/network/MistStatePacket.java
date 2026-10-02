package com.nieblamistica.network;

import com.nieblamistica.client.MistClientState;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

public record MistStatePacket(boolean active, long remainingTicks) {

    public static void encode(MistStatePacket packet, FriendlyByteBuf buf) {
        buf.writeBoolean(packet.active());
        buf.writeLong(packet.remainingTicks());
    }

    public static MistStatePacket decode(FriendlyByteBuf buf) {
        return new MistStatePacket(buf.readBoolean(), buf.readLong());
    }

    public static void handle(
            MistStatePacket packet,
            Supplier<NetworkEvent.Context> contextSupplier) {

        NetworkEvent.Context context = contextSupplier.get();
        context.enqueueWork(() ->
                MistClientState.set(packet.active(), packet.remainingTicks()));
        context.setPacketHandled(true);
    }
}
