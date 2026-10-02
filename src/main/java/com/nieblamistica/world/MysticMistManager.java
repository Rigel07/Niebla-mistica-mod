package com.nieblamistica.world;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.nieblamistica.MistConfig;
import com.nieblamistica.NieblaMistica;
import com.nieblamistica.network.MistStatePacket;
import com.nieblamistica.network.ModNetwork;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.DimensionDataStorage;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.server.ServerStoppedEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.HashMap;
import java.util.Map;

/** Lógica del servidor: cuándo empieza y termina la Niebla Mística. */
@Mod.EventBusSubscriber(modid = NieblaMistica.MOD_ID)
public final class MysticMistManager {
    private static final String DATA_ID = "nieblamistica_state";

    private static final Map<MinecraftServer, Integer> ticksSinceCheck = new HashMap<>();
    private static final Map<MinecraftServer, Boolean> previousState = new HashMap<>();

    private MysticMistManager() {}

    public static MysticMistSavedData getData(ServerLevel level) {
        DimensionDataStorage storage = level.getDataStorage();
        return storage.computeIfAbsent(
                MysticMistSavedData::load,
                MysticMistSavedData::new,
                DATA_ID
        );
    }

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;

        MinecraftServer server = event.getServer();
        ServerLevel overworld = server.getLevel(Level.OVERWORLD);
        if (overworld == null) return;

        MysticMistSavedData data = getData(overworld);
        boolean wasActive = data.isActive();

        if (wasActive) {
            data.tick();
        } else if (MistConfig.SPONTANEOUS.get()) {
            int ticks = ticksSinceCheck.merge(server, 1, Integer::sum);
            if (ticks >= MistConfig.CHECK_SECONDS.get() * 20) {
                ticksSinceCheck.put(server, 0);
                double chance = MistConfig.CHANCE_PERCENT.get() / 100.0D;
                boolean blocked = MistConfig.NOT_IN_PEACEFUL.get() && overworld.getDifficulty() == Difficulty.PEACEFUL;
                if (!blocked && overworld.random.nextDouble() < chance) {
                    start(server);
                }
            }
        }

        boolean isActive = data.isActive();
        Boolean old = previousState.put(server, isActive);
        if (old == null || old != isActive) {
            if (!isActive && wasActive) {
                broadcast(server, Component.translatable("nieblamistica.end"));
            }
            sync(server, isActive, data.getRemainingTicks());
        }
    }

    private static long randomDuration(ServerLevel level) {
        int min = MistConfig.MIN_SECONDS.get() * 20;
        int max = Math.max(min, MistConfig.MAX_SECONDS.get() * 20);
        return min + level.random.nextInt(max - min + 1);
    }

    /** Empieza con duración aleatoria (según la config). */
    public static boolean start(MinecraftServer server) {
        ServerLevel level = server.getLevel(Level.OVERWORLD);
        if (level == null) return false;
        return start(server, randomDuration(level));
    }

    /** Empieza con una duración concreta en ticks. @return false si ya estaba activa. */
    public static boolean start(MinecraftServer server, long durationTicks) {
        ServerLevel level = server.getLevel(Level.OVERWORLD);
        if (level == null) return false;

        MysticMistSavedData data = getData(level);
        if (data.isActive()) return false;

        data.start(durationTicks);

        broadcast(server, Component.translatable("nieblamistica.warning"));
        broadcast(server, Component.translatable("nieblamistica.begin"));
        if (MistConfig.PLAY_SOUND.get()) {
            for (ServerPlayer p : server.getPlayerList().getPlayers()) {
                p.playNotifySound(SoundEvents.WARDEN_NEARBY_CLOSE, SoundSource.AMBIENT, 0.8F, 0.6F);
            }
        }
        sync(server, true, durationTicks);
        return true;
    }

    public static boolean stop(MinecraftServer server) {
        ServerLevel level = server.getLevel(Level.OVERWORLD);
        if (level == null) return false;

        MysticMistSavedData data = getData(level);
        if (!data.isActive()) return false;

        data.stop();
        broadcast(server, Component.translatable("nieblamistica.end"));
        sync(server, false, 0);
        return true;
    }

    private static void broadcast(MinecraftServer server, Component message) {
        server.getPlayerList().broadcastSystemMessage(message, false);
    }

    private static void sync(MinecraftServer server, boolean active, long remaining) {
        ModNetwork.sendToAll(server, new MistStatePacket(active, remaining));
    }

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("mistica")
                .requires(source -> source.hasPermission(2))
                .then(Commands.literal("start")
                        .executes(ctx -> {
                            if (start(ctx.getSource().getServer())) {
                                ctx.getSource().sendSuccess(() -> Component.translatable("commands.nieblamistica.start"), true);
                                return 1;
                            }
                            ctx.getSource().sendFailure(Component.translatable("commands.nieblamistica.already_active"));
                            return 0;
                        })
                        .then(Commands.argument("segundos", IntegerArgumentType.integer(5, 7200))
                                .executes(ctx -> {
                                    int seconds = IntegerArgumentType.getInteger(ctx, "segundos");
                                    if (start(ctx.getSource().getServer(), seconds * 20L)) {
                                        ctx.getSource().sendSuccess(() -> Component.translatable("commands.nieblamistica.start"), true);
                                        return 1;
                                    }
                                    ctx.getSource().sendFailure(Component.translatable("commands.nieblamistica.already_active"));
                                    return 0;
                                })))
                .then(Commands.literal("stop").executes(ctx -> {
                    if (stop(ctx.getSource().getServer())) {
                        ctx.getSource().sendSuccess(() -> Component.translatable("commands.nieblamistica.stop"), true);
                        return 1;
                    }
                    ctx.getSource().sendFailure(Component.translatable("commands.nieblamistica.not_active"));
                    return 0;
                }))
                .then(Commands.literal("status").executes(ctx -> {
                    ServerLevel level = ctx.getSource().getServer().getLevel(Level.OVERWORLD);
                    if (level == null) return 0;
                    MysticMistSavedData data = getData(level);
                    if (data.isActive()) {
                        long seconds = data.getRemainingTicks() / 20;
                        ctx.getSource().sendSuccess(
                                () -> Component.translatable("commands.nieblamistica.status.active", seconds), false);
                    } else {
                        ctx.getSource().sendSuccess(
                                () -> Component.translatable("commands.nieblamistica.status.inactive"), false);
                    }
                    return 1;
                })));
    }

    @SubscribeEvent
    public static void onPlayerLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;

        MinecraftServer server = player.getServer();
        if (server == null) return;

        ServerLevel level = server.getLevel(Level.OVERWORLD);
        if (level == null) return;

        MysticMistSavedData data = getData(level);
        ModNetwork.sendToPlayer(player, new MistStatePacket(data.isActive(), data.getRemainingTicks()));
    }

    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        ticksSinceCheck.remove(event.getServer());
        previousState.remove(event.getServer());
    }
}
