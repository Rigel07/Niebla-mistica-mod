package com.nieblamistica.network;

import com.nieblamistica.NieblaMistica;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.simple.SimpleChannel;

public final class ModNetwork {
    private static final String PROTOCOL = "1";

    public static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
            new ResourceLocation(NieblaMistica.MOD_ID, "main"),
            () -> PROTOCOL,
            PROTOCOL::equals,
            PROTOCOL::equals
    );

    private static int packetId;

    private ModNetwork() {}

    public static void register() {
        CHANNEL.registerMessage(
                packetId++,
                MistStatePacket.class,
                MistStatePacket::encode,
                MistStatePacket::decode,
                MistStatePacket::handle
        );
    }

    public static void sendToPlayer(ServerPlayer player, MistStatePacket packet) {
        CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), packet);
    }

    public static void sendToAll(MinecraftServer server, MistStatePacket packet) {
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            sendToPlayer(player, packet);
        }
    }
}
