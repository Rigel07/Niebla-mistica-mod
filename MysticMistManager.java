package com.nieblamistica.world;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.nieblamistica.MistConfig;
import com.nieblamistica.NieblaMistica;
import com.nieblamistica.network.MistStatePacket;
import com.nieblamistica.network.ModNetwork;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.Difficulty;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Servidor: ciclo de la niebla, eventos sobrenaturales y viaje de Excalibur. */
@Mod.EventBusSubscriber(modid = NieblaMistica.MOD_ID)
public final class MysticMistManager {
    private static final String DATA_ID = "nieblamistica_state";

    private static final Map<MinecraftServer, Integer> ticksSinceCheck = new HashMap<>();
    private static final Map<MinecraftServer, Boolean> previousState = new HashMap<>();
    private static final Map<MinecraftServer, Integer> effectTicks = new HashMap<>();
    private static final Map<UUID, Integer> playerEventTicks = new HashMap<>();
    private static final Map<UUID, MysticForestEvent> forestEvents = new HashMap<>();
    private static final Map<UUID, Map<BlockPos, BlockState>> temporaryHouses = new HashMap<>();
    private static final Map<UUID, Integer> houseTimers = new HashMap<>();
    private static final String SCENE_TAG = "MysticMistScene";
    private static final String TTL_TAG = "MysticMistTTL";

    private MysticMistManager() {}

    public static MysticMistSavedData getData(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(
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
                if (!blocked && overworld.random.nextDouble() < chance) start(server);
            }
        }

        tickForestEvents(overworld);
        tickSpecialScenes(overworld);

        if (data.isActive()) {
            int ticks = effectTicks.merge(server, 1, Integer::sum);
            if (ticks >= 40) {
                effectTicks.put(server, 0);
                applyMistEffects(overworld);
            }
            processMistEvents(overworld);
        } else {
            effectTicks.put(server, 0);
            if (wasActive) {
                for (UUID id : new ArrayList<>(temporaryHouses.keySet())) restoreHouse(overworld, id);
                for (var e : overworld.getEntitiesOfClass(net.minecraft.world.entity.Entity.class, new net.minecraft.world.phys.AABB(-3.0E7, -64, -3.0E7, 3.0E7, 320, 3.0E7), x -> x.getTags().contains(SCENE_TAG))) e.discard();
            }
        }

        boolean isActive = data.isActive();
        Boolean old = previousState.put(server, isActive);
        if (old == null || old != isActive) {
            if (!isActive && wasActive) broadcast(server, Component.translatable("nieblamistica.end"));
            sync(server, isActive, data.getRemainingTicks());
        }
    }

    private static void applyMistEffects(ServerLevel level) {
        for (ServerPlayer player : level.getServer().getPlayerList().getPlayers()) {
            if (player.level() != level || !level.canSeeSky(player.blockPosition())) continue;

            List<Monster> nearby = level.getEntitiesOfClass(
                    Monster.class,
                    player.getBoundingBox().inflate(30.0D),
                    mob -> mob.isAlive() && mob.distanceToSqr(player) <= 900.0D
            );
            for (Monster mob : nearby) {
                mob.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SPEED, 70, 0, true, false, false));
            }

            if (level.random.nextInt(16) == 0) {
                player.playNotifySound(SoundEvents.AMBIENT_CAVE, SoundSource.AMBIENT, 0.5F,
                        0.55F + level.random.nextFloat() * 0.25F);
            }
            if (level.random.nextInt(42) == 0) {
                player.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN, 24, 0, true, false, false));
            }
        }
    }

    private static void tickForestEvents(ServerLevel level) {
        for (ServerPlayer player : level.getServer().getPlayerList().getPlayers()) {
            MysticForestEvent forest = forestEvents.get(player.getUUID());
            if (forest == null) continue;

            if (player.level() != level) {
                // Si un comando/mod intenta sacar al jugador del bosque, restauramos
                // el escenario y devolvemos el viaje a su punto de origen.
                forest.restoreAndReturn(level, player);
                continue;
            }

            forest.tick(level, player);
            forest.pickupCheck(level, player);
        }
    }

    /** Cada jugador recibe acontecimientos diferentes. Excalibur sigue siendo extremadamente raro. */
    private static void processMistEvents(ServerLevel level) {
        if (!MistConfig.MYSTIC_EVENTS.get()) return;

        for (ServerPlayer player : level.getServer().getPlayerList().getPlayers()) {
            if (player.level() != level || !level.canSeeSky(player.blockPosition())) continue;
            if (forestEvents.containsKey(player.getUUID())) continue;

            UUID id = player.getUUID();
            int ticks = playerEventTicks.merge(id, 1, Integer::sum);
            if (ticks < 240 || ticks % 240 != 0) continue; // 12 s entre tiradas

            int roll = level.random.nextInt(10000);
            if (roll < 4700) {
                spawnRandomMobs(level, player);
            } else if (roll < 6500) {
                spectralWhisper(level, player);
            } else if (roll < 8000) {
                mistAmbush(level, player);
            } else if (roll < 8900) {
                fogPulse(level, player);
            } else if (roll < 9500) {
                lostPath(level, player);
            } else if (roll < 9750) {
                nightHunter(level, player);
            } else if (roll < 9850) {
                abandonedHouse(level, player);
            } else if (roll < 9925) {
                watchingStatues(level, player);
            } else if (roll < 9970) {
                silentObserver(level, player);
            } else if (roll < 9990) {
                forestSilhouette(level, player);
            } else if (roll < 9998) {
                strangeAnimal(level, player);
            } else {
                beginExcaliburQuest(level, player); // 0,02 %
            }
        }
    }

    /** Casa temporal abandonada: contiene un objeto único y desaparece cuando la escena termina. */
    private static void abandonedHouse(ServerLevel level, ServerPlayer player) {
        if (temporaryHouses.containsKey(player.getUUID())) return;
        double angle = level.random.nextDouble() * Math.PI * 2.0D;
        int cx = Mth.floor(player.getX() + Math.cos(angle) * 18D);
        int cz = Mth.floor(player.getZ() + Math.sin(angle) * 18D);
        int cy = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, cx, cz);
        if (cy <= level.getMinBuildHeight() + 3) return;
        BlockPos base = new BlockPos(cx, cy, cz);
        Map<BlockPos, BlockState> originals = new HashMap<>();
        for (int x = -3; x <= 3; x++) for (int z = -3; z <= 3; z++) for (int y = 0; y <= 4; y++) {
            BlockPos p = base.offset(x, y, z);
            originals.put(p.immutable(), level.getBlockState(p));
        }
        temporaryHouses.put(player.getUUID(), originals);
        houseTimers.put(player.getUUID(), 520);
        for (int x = -3; x <= 3; x++) for (int z = -3; z <= 3; z++) {
            level.setBlock(base.offset(x, 0, z), Blocks.COBBLESTONE.defaultBlockState(), 3);
            if (Math.abs(x) == 3 || Math.abs(z) == 3) {
                for (int y = 1; y <= 3; y++) level.setBlock(base.offset(x, y, z), Blocks.DARK_OAK_PLANKS.defaultBlockState(), 3);
            }
        }
        for (int x = -2; x <= 2; x++) for (int z = -2; z <= 2; z++) {
            level.setBlock(base.offset(x, 4, z), Blocks.DARK_OAK_PLANKS.defaultBlockState(), 3);
        }
        level.setBlock(base.offset(0, 1, -3), Blocks.AIR.defaultBlockState(), 3);
        level.setBlock(base.offset(0, 2, -3), Blocks.AIR.defaultBlockState(), 3);
        level.setBlock(base.offset(-2, 2, -3), Blocks.GLASS_PANE.defaultBlockState(), 3);
        level.setBlock(base.offset(2, 2, -3), Blocks.GLASS_PANE.defaultBlockState(), 3);
        level.setBlock(base.offset(-3, 2, 0), Blocks.GLASS_PANE.defaultBlockState(), 3);
        level.setBlock(base.offset(3, 2, 0), Blocks.GLASS_PANE.defaultBlockState(), 3);
        BlockPos chestPos = base.offset(0, 1, 0);
        level.setBlock(chestPos, Blocks.CHEST.defaultBlockState(), 3);
        if (level.getBlockEntity(chestPos) instanceof ChestBlockEntity chest) {
            ItemStack eye = new ItemStack(Items.ENDER_EYE);
            eye.setHoverName(Component.literal("Ojo de la Niebla"));
            eye.getOrCreateTag().putBoolean("MysticMistUnique", true);
            chest.setItem(13, eye);
        }
        player.playNotifySound(SoundEvents.CHEST_OPEN, SoundSource.AMBIENT, .7F, .45F);
        player.displayClientMessage(Component.literal("Entre la niebla ha aparecido una casa que no estaba ahí."), false);
    }

    /** Varias estatuas aparecen orientadas exactamente hacia el jugador. */
    private static void watchingStatues(ServerLevel level, ServerPlayer player) {
        List<Vec3> spots = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            double angle = (Math.PI * 2D / 3D) * i + level.random.nextDouble() * .7D;
            double dist = 8D + level.random.nextDouble() * 8D;
            spots.add(new Vec3(player.getX() + Math.cos(angle) * dist, player.getY(), player.getZ() + Math.sin(angle) * dist));
        }
        int spawned = 0;
        for (Vec3 v : spots) {
            int x = Mth.floor(v.x), z = Mth.floor(v.z);
            int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
            if (!level.getBlockState(new BlockPos(x, y - 1, z)).isSolid()) continue;
            ArmorStand statue = new ArmorStand(level, x + .5D, y, z + .5D);
            statue.setNoGravity(true);
            statue.setInvulnerable(true);
            statue.addTag(SCENE_TAG);
            statue.getPersistentData().putInt(TTL_TAG, 260);
            statue.setItemSlot(EquipmentSlot.HEAD, new ItemStack(Items.IRON_HELMET));
            statue.setItemSlot(EquipmentSlot.CHEST, new ItemStack(Items.IRON_CHESTPLATE));
            statue.setItemSlot(EquipmentSlot.LEGS, new ItemStack(Items.IRON_LEGGINGS));
            statue.setItemSlot(EquipmentSlot.FEET, new ItemStack(Items.IRON_BOOTS));
            statue.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.STONE_SWORD));
            statue.setYRot((float)(Math.toDegrees(Math.atan2(player.getZ() - statue.getZ(), player.getX() - statue.getX())) - 90F));
            level.addFreshEntity(statue);
            spawned++;
        }
        if (spawned > 0) {
            player.playNotifySound(SoundEvents.AMBIENT_CAVE, SoundSource.AMBIENT, .8F, .35F);
            player.displayClientMessage(Component.literal("Hay algo inmóvil entre los árboles... y te está mirando."), false);
        }
    }

    /** Una criatura inmóvil observa al jugador, pero jamás tiene IA ni ataque. */
    private static void silentObserver(ServerLevel level, ServerPlayer player) {
        double angle = level.random.nextDouble() * Math.PI * 2D;
        double dist = 11D + level.random.nextDouble() * 7D;
        int x = Mth.floor(player.getX() + Math.cos(angle) * dist);
        int z = Mth.floor(player.getZ() + Math.sin(angle) * dist);
        int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
        var observer = EntityType.ENDERMAN.create(level);
        if (observer == null) return;
        observer.moveTo(x + .5D, y, z + .5D, 0F, 0F);
        observer.setNoAi(true);
        observer.setInvulnerable(true);
        observer.addTag(SCENE_TAG);
        observer.getPersistentData().putInt(TTL_TAG, 180);
        observer.setCustomName(Component.literal("El Observador"));
        observer.setCustomNameVisible(false);
        level.addFreshEntity(observer);
        player.playNotifySound(SoundEvents.ENDERMAN_STARE, SoundSource.AMBIENT, .8F, .6F);
        player.displayClientMessage(Component.literal("No está atacándote. Solo te observa."), true);
    }

    /** Silueta: aparece a distancia y desaparece al acercarse. */
    private static void forestSilhouette(ServerLevel level, ServerPlayer player) {
        double angle = level.random.nextDouble() * Math.PI * 2D;
        double dist = 18D + level.random.nextDouble() * 10D;
        int x = Mth.floor(player.getX() + Math.cos(angle) * dist);
        int z = Mth.floor(player.getZ() + Math.sin(angle) * dist);
        int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
        ArmorStand silhouette = new ArmorStand(level, x + .5D, y, z + .5D);
        silhouette.setNoGravity(true);
        silhouette.setInvisible(false);
        silhouette.setInvulnerable(true);
        silhouette.addTag(SCENE_TAG);
        silhouette.getPersistentData().putInt(TTL_TAG, 130);
        silhouette.setItemSlot(EquipmentSlot.HEAD, new ItemStack(Items.BLACK_WOOL));
        level.addFreshEntity(silhouette);
        player.playNotifySound(SoundEvents.AMBIENT_CAVE, SoundSource.AMBIENT, .45F, .45F);
        player.displayClientMessage(Component.literal("Una silueta se mueve entre los árboles."), true);
    }

    /** Animal extraño: aparece, mira al jugador y se desvanece si este se aproxima. */
    private static void strangeAnimal(ServerLevel level, ServerPlayer player) {
        double angle = level.random.nextDouble() * Math.PI * 2D;
        double dist = 12D + level.random.nextDouble() * 9D;
        int x = Mth.floor(player.getX() + Math.cos(angle) * dist);
        int z = Mth.floor(player.getZ() + Math.sin(angle) * dist);
        int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
        var animal = EntityType.FOX.create(level);
        if (animal == null) return;
        animal.moveTo(x + .5D, y, z + .5D, 0F, 0F);
        animal.setNoAi(true);
        animal.setInvulnerable(true);
        animal.addTag(SCENE_TAG);
        animal.getPersistentData().putInt(TTL_TAG, 110);
        animal.setCustomName(Component.literal("Animal de la Niebla"));
        level.addFreshEntity(animal);
        player.playNotifySound(SoundEvents.FOX_AMBIENT, SoundSource.AMBIENT, .65F, .55F);
    }

    private static void tickSpecialScenes(ServerLevel level) {
        for (ServerPlayer player : level.getServer().getPlayerList().getPlayers()) {
            for (var entity : new ArrayList<>(level.getEntitiesOfClass(net.minecraft.world.entity.Entity.class,
                    player.getBoundingBox().inflate(32D), e -> e.getTags().contains(SCENE_TAG)))) {
                int ttl = entity.getPersistentData().getInt(TTL_TAG) - 1;
                entity.getPersistentData().putInt(TTL_TAG, ttl);
                if (entity instanceof ArmorStand stand) {
                    stand.setYRot((float)(Math.toDegrees(Math.atan2(player.getZ() - stand.getZ(), player.getX() - stand.getX())) - 90F));
                    if (stand.isInvisible() && stand.distanceTo(player) < 6D) ttl = 0;
                }
                if (entity.getType() == EntityType.ENDERMAN && entity.distanceTo(player) < 5D) ttl = 0;
                if (entity.getType() == EntityType.FOX && entity.distanceTo(player) < 7D) ttl = 0;
                if (ttl <= 0) entity.discard();
            }
        }
        for (UUID id : new ArrayList<>(houseTimers.keySet())) {
            int left = houseTimers.merge(id, -1, Integer::sum);
            if (left <= 0) restoreHouse(level, id);
        }
    }

    private static void restoreHouse(ServerLevel level, UUID id) {
        Map<BlockPos, BlockState> originals = temporaryHouses.remove(id);
        houseTimers.remove(id);
        if (originals == null) return;
        for (Map.Entry<BlockPos, BlockState> e : originals.entrySet()) level.setBlock(e.getKey(), e.getValue(), 3);
    }

    private static void spawnRandomMobs(ServerLevel level, ServerPlayer player) {
        List<EntityType<?>> candidates = new ArrayList<>();
        for (EntityType<?> type : ForgeRegistries.ENTITY_TYPES.getValues()) {
            MobCategory category = type.getCategory();
            if (category == MobCategory.MISC || category == MobCategory.AMBIENT
                    || category == MobCategory.WATER_AMBIENT || category == MobCategory.WATER_CREATURE
                    || category == MobCategory.UNDERGROUND_WATER_CREATURE) continue;
            if (type == EntityType.ENDER_DRAGON || type == EntityType.WITHER) continue;
            candidates.add(type);
        }
        if (candidates.isEmpty()) return;

        int amount = 2 + level.random.nextInt(4);
        int spawned = 0;
        for (int i = 0; i < amount; i++) {
            EntityType<?> type = candidates.get(level.random.nextInt(candidates.size()));
            Mob mob = createSafeMob(level, type);
            if (mob == null) continue;

            double angle = level.random.nextDouble() * Math.PI * 2.0D;
            double distance = 10.0D + level.random.nextDouble() * 14.0D;
            int x = Mth.floor(player.getX() + Math.cos(angle) * distance);
            int z = Mth.floor(player.getZ() + Math.sin(angle) * distance);
            int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
            BlockPos pos = new BlockPos(x, y, z);
            if (!level.getBlockState(pos.below()).isSolid() || !level.getBlockState(pos).isAir()) continue;

            mob.moveTo(x + .5D, y, z + .5D, level.random.nextFloat() * 360F, 0F);
            try {
                mob.finalizeSpawn(level, level.getCurrentDifficultyAt(pos), net.minecraft.world.entity.MobSpawnType.EVENT, null, null);
                level.addFreshEntity(mob);
                spawned++;
            } catch (Exception ignored) {
                mob.discard();
            }
        }

        if (spawned > 0) {
            player.playNotifySound(SoundEvents.ZOMBIE_AMBIENT, SoundSource.AMBIENT, .45F, .5F + level.random.nextFloat() * .3F);
            player.displayClientMessage(Component.literal("Algo se mueve dentro de la niebla..."), true);
        }
    }

    private static Mob createSafeMob(ServerLevel level, EntityType<?> type) {
        try {
            var entity = type.create(level);
            return entity instanceof Mob mob ? mob : null;
        } catch (Exception ignored) {
            return null;
        }
    }

    private static void spectralWhisper(ServerLevel level, ServerPlayer player) {
        player.playNotifySound(SoundEvents.ALLAY_AMBIENT_WITH_ITEM, SoundSource.AMBIENT, .7F,
                .42F + level.random.nextFloat() * .28F);
        player.addEffect(new MobEffectInstance(MobEffects.DARKNESS, 55, 0, true, false, false));
        player.displayClientMessage(Component.literal("Has oído algo... pero no hay nadie."), true);
        for (int i = 0; i < 24; i++) {
            double a = level.random.nextDouble() * Math.PI * 2D;
            double d = 4D + level.random.nextDouble() * 10D;
            level.sendParticles(net.minecraft.core.particles.ParticleTypes.SOUL_FIRE_FLAME,
                    player.getX() + Math.cos(a) * d,
                    player.getY() + .2D + level.random.nextDouble() * 1.5D,
                    player.getZ() + Math.sin(a) * d,
                    1, 0, .15D, 0, .01D);
        }
    }

    private static void mistAmbush(ServerLevel level, ServerPlayer player) {
        spawnRandomMobs(level, player);
        spawnRandomMobs(level, player);
        player.addEffect(new MobEffectInstance(MobEffects.DARKNESS, 80, 0, true, false, false));
        player.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN, 80, 0, true, false, false));
        player.displayClientMessage(Component.literal("La niebla se cierra a tu alrededor."), true);
    }

    private static void fogPulse(ServerLevel level, ServerPlayer player) {
        player.addEffect(new MobEffectInstance(MobEffects.CONFUSION, 100, 0, true, false, false));
        player.addEffect(new MobEffectInstance(MobEffects.DARKNESS, 35, 0, true, false, false));
        player.playNotifySound(SoundEvents.WARDEN_HEARTBEAT, SoundSource.AMBIENT, .55F, .65F);
        level.sendParticles(net.minecraft.core.particles.ParticleTypes.CLOUD,
                player.getX(), player.getY() + 1, player.getZ(), 35, 2.5, .8, 2.5, .015D);
        player.displayClientMessage(Component.literal("La niebla parece respirar."), true);
    }

    private static void lostPath(ServerLevel level, ServerPlayer player) {
        player.addEffect(new MobEffectInstance(MobEffects.BLINDNESS, 45, 0, true, false, false));
        player.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN, 90, 0, true, false, false));
        double a = level.random.nextDouble() * Math.PI * 2D;
        double d = 10D + level.random.nextDouble() * 8D;
        level.sendParticles(net.minecraft.core.particles.ParticleTypes.SPORE_BLOSSOM_AIR,
                player.getX() + Math.cos(a) * d, player.getY() + 1, player.getZ() + Math.sin(a) * d,
                50, 3, 1.5, 3, .02D);
        player.displayClientMessage(Component.literal("Por un instante, no recuerdas de dónde vienes."), true);
    }

    private static void nightHunter(ServerLevel level, ServerPlayer player) {
        Mob hunter = createSafeMob(level, EntityType.HUSK);
        if (hunter == null) return;
        double a = level.random.nextDouble() * Math.PI * 2D;
        double d = 14D;
        int x = Mth.floor(player.getX() + Math.cos(a) * d);
        int z = Mth.floor(player.getZ() + Math.sin(a) * d);
        int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
        BlockPos pos = new BlockPos(x, y, z);
        hunter.moveTo(x + .5D, y, z + .5D, 0, 0);
        hunter.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SPEED, 20 * 45, 1, false, false, true));
        hunter.addEffect(new MobEffectInstance(MobEffects.DAMAGE_BOOST, 20 * 45, 1, false, false, true));
        try {
            hunter.finalizeSpawn(level, level.getCurrentDifficultyAt(pos), net.minecraft.world.entity.MobSpawnType.EVENT, null, null);
            level.addFreshEntity(hunter);
            player.displayClientMessage(Component.literal("Hay algo que te está siguiendo."), true);
            player.playNotifySound(SoundEvents.HUSK_AMBIENT, SoundSource.AMBIENT, .8F, .45F);
        } catch (Exception ignored) {
            hunter.discard();
        }
    }

    private static void beginExcaliburQuest(ServerLevel level, ServerPlayer player) {
        if (forestEvents.containsKey(player.getUUID())) return;
        BlockPos origin = findForestLocation(level, player.blockPosition());
        if (origin == null) return;

        MysticForestEvent event = new MysticForestEvent(
                player.getUUID(), player.position(), player.getYRot(), player.getXRot(), origin);
        if (!buildMysticForest(level, origin, event)) return;

        forestEvents.put(player.getUUID(), event);
        player.teleportTo(level, origin.getX() + .5D, origin.getY() + 1D, origin.getZ() + .5D,
                player.getYRot(), player.getXRot());
        player.playNotifySound(SoundEvents.ENDERMAN_TELEPORT, SoundSource.AMBIENT, 1F, .5F);
        player.displayClientMessage(Component.literal("La niebla te ha llevado a un bosque que no reconoces."), false);
        player.displayClientMessage(Component.literal("Encuentra el lago. Solo allí encontrarás la salida."), false);
    }

    private static BlockPos findForestLocation(ServerLevel level, BlockPos near) {
        for (int attempt = 0; attempt < 40; attempt++) {
            double a = level.random.nextDouble() * Math.PI * 2D;
            int distance = 180 + level.random.nextInt(181);
            int x = near.getX() + Mth.floor(Math.cos(a) * distance);
            int z = near.getZ() + Mth.floor(Math.sin(a) * distance);
            int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
            if (y > 55 && y < 150) return new BlockPos(x, y, z);
        }
        return null;
    }

    private static boolean buildMysticForest(ServerLevel level, BlockPos c, MysticForestEvent event) {
        int r = 18;
        for (int x = -r; x <= r; x++) for (int z = -r; z <= r; z++) {
            for (int y = 0; y <= 16; y++) {
                BlockPos p = c.offset(x, y, z);
                if (level.getBlockEntity(p) != null) return false;
                event.originalBlocks.put(p.immutable(), level.getBlockState(p));
            }
        }

        for (int x = -r; x <= r; x++) for (int z = -r; z <= r; z++) {
            BlockPos p = c.offset(x, 0, z);
            level.setBlock(p, Blocks.GRASS_BLOCK.defaultBlockState(), 3);
            for (int yy = 1; yy <= 16; yy++) level.setBlock(p.above(yy), Blocks.AIR.defaultBlockState(), 3);
        }

        // Recinto invisible: evita escapar sin convertirlo en una jaula visible.
        for (int x = -r; x <= r; x++) for (int z = -r; z <= r; z++) {
            if (Math.abs(x) == r || Math.abs(z) == r) {
                for (int yy = 1; yy <= 15; yy++) level.setBlock(c.offset(x, yy, z), Blocks.BARRIER.defaultBlockState(), 3);
            }
            level.setBlock(c.offset(x, 16, z), Blocks.BARRIER.defaultBlockState(), 3);
        }

        // Lago irregular con una pequeña zona de arena alrededor.
        for (int x = -7; x <= 7; x++) for (int z = -6; z <= 6; z++) {
            double ellipse = (x * x) / 49.0D + (z * z) / 36.0D;
            if (ellipse <= 1.0D) {
                level.setBlock(c.offset(x, 0, z), Blocks.WATER.defaultBlockState(), 3);
                level.setBlock(c.offset(x, -1, z), Blocks.SAND.defaultBlockState(), 3);
            } else if (ellipse <= 1.35D) {
                level.setBlock(c.offset(x, 0, z), Blocks.MOSS_BLOCK.defaultBlockState(), 3);
            }
        }

        // Bosque muy cerrado: troncos + copas a distintas alturas para crear laberinto visual.
        for (int i = 0; i < 42; i++) {
            int x = -15 + level.random.nextInt(31);
            int z = -15 + level.random.nextInt(31);
            if (x * x + z * z < 90) continue;
            int h = 4 + level.random.nextInt(5);
            for (int yy = 1; yy <= h; yy++) level.setBlock(c.offset(x, yy, z), Blocks.DARK_OAK_LOG.defaultBlockState(), 3);
            for (int dx = -2; dx <= 2; dx++) for (int dz = -2; dz <= 2; dz++) for (int dy = 0; dy <= 2; dy++) {
                if (Math.abs(dx) + Math.abs(dz) + dy <= 4)
                    level.setBlock(c.offset(x + dx, h + dy, z + dz), Blocks.DARK_OAK_LEAVES.defaultBlockState(), 3);
            }
        }

        level.sendParticles(net.minecraft.core.particles.ParticleTypes.SPORE_BLOSSOM_AIR,
                c.getX(), c.getY() + 2, c.getZ(), 80, 14, 4, 14, .03D);
        return true;
    }

    private static void restoreForest(ServerLevel level, MysticForestEvent event) {
        for (Map.Entry<BlockPos, BlockState> entry : event.originalBlocks.entrySet()) {
            level.setBlock(entry.getKey(), entry.getValue(), 3);
        }
    }

    private static final class MysticForestEvent {
        private final UUID playerId;
        private final Vec3 returnPos;
        private final float yaw;
        private final float pitch;
        private final BlockPos origin;
        private final Map<BlockPos, BlockState> originalBlocks = new HashMap<>();
        private boolean itemAccepted;
        private int age;
        private int swordDelay;
        private boolean swordSpawned;

        private MysticForestEvent(UUID playerId, Vec3 returnPos, float yaw, float pitch, BlockPos origin) {
            this.playerId = playerId;
            this.returnPos = returnPos;
            this.yaw = yaw;
            this.pitch = pitch;
            this.origin = origin;
        }

        void tick(ServerLevel level, ServerPlayer player) {
            age++;

            // El viaje es autónomo: la niebla exterior puede terminar y el jugador sigue atrapado.
            if (player.distanceToSqr(origin.getX() + .5D, origin.getY() + 1D, origin.getZ() + .5D) > 500D) {
                player.teleportTo(level, origin.getX() + .5D, origin.getY() + 1D, origin.getZ() + .5D,
                        player.getYRot(), player.getXRot());
            }

            // Ambiente dinámico del bosque.
            if (age % 35 == 0) {
                level.sendParticles(net.minecraft.core.particles.ParticleTypes.WHITE_ASH,
                        player.getX(), player.getY() + 1, player.getZ(), 8, 3, 1, 3, .008D);
            }
            if (age % 160 == 0) {
                player.playNotifySound(SoundEvents.AMBIENT_CAVE, SoundSource.AMBIENT, .35F, .55F);
            }

            if (!itemAccepted && age > 100) {
                for (ItemEntity item : level.getEntitiesOfClass(ItemEntity.class, player.getBoundingBox().inflate(22D), e -> !e.getItem().isEmpty())) {
                    BlockPos p = item.blockPosition();
                    int dx = p.getX() - origin.getX();
                    int dz = p.getZ() - origin.getZ();
                    if (dx * dx / 49.0D + dz * dz / 36.0D <= 1.25D && p.getY() <= origin.getY() + 1) {
                        item.discard();
                        itemAccepted = true;
                        swordDelay = 1;
                        player.displayClientMessage(Component.literal("El lago ha aceptado tu ofrenda."), false);
                        player.playNotifySound(SoundEvents.CONDUIT_ACTIVATE, SoundSource.AMBIENT, 1F, .7F);
                        level.sendParticles(net.minecraft.core.particles.ParticleTypes.ENCHANT,
                                origin.getX() + .5D, origin.getY() + 1, origin.getZ() + .5D,
                                45, 3, .5, 3, .2D);
                        break;
                    }
                }
            }

            if (itemAccepted && !swordSpawned) {
                swordDelay++;
                if (swordDelay >= 70) spawnExcalibur(level, player);
            }
        }

        private void spawnExcalibur(ServerLevel level, ServerPlayer player) {
            swordSpawned = true;
            ItemStack stack = new ItemStack(Items.DIAMOND_SWORD);
            stack.setHoverName(Component.literal("Excalibur"));
            stack.enchant(Enchantments.SHARPNESS, 5);
            stack.enchant(Enchantments.UNBREAKING, 3);
            stack.enchant(Enchantments.MENDING, 1);
            stack.getOrCreateTag().putBoolean("MysticExcalibur", true);

            ItemEntity sword = new ItemEntity(level, origin.getX() + .5D, origin.getY() + 1.25D, origin.getZ() + .5D, stack);
            sword.setGlowingTag(true);
            level.addFreshEntity(sword);
            level.sendParticles(net.minecraft.core.particles.ParticleTypes.END_ROD,
                    sword.getX(), sword.getY(), sword.getZ(), 60, .7, 1.3, .7, .045D);
            level.sendParticles(net.minecraft.core.particles.ParticleTypes.ENCHANT,
                    sword.getX(), sword.getY(), sword.getZ(), 80, 1, 1, 1, .15D);
            player.playNotifySound(SoundEvents.PLAYER_LEVELUP, SoundSource.AMBIENT, 1F, .65F);
            player.displayClientMessage(Component.literal("Algo emerge del lago... EXCALIBUR."), false);
        }

        void restoreAndReturn(ServerLevel level, ServerPlayer player) {
            restoreForest(level, this);
            player.teleportTo(level, returnPos.x, returnPos.y, returnPos.z, yaw, pitch);
            forestEvents.remove(playerId);
            playerEventTicks.put(playerId, 0);
        }

        void pickupCheck(ServerLevel level, ServerPlayer player) {
            if (!swordSpawned) return;
            for (ItemEntity item : level.getEntitiesOfClass(ItemEntity.class,
                    player.getBoundingBox().inflate(2D),
                    e -> e.getItem().getOrCreateTag().getBoolean("MysticExcalibur"))) {
                item.discard();
                ItemStack sword = new ItemStack(Items.DIAMOND_SWORD);
                sword.setHoverName(Component.literal("Excalibur"));
                sword.enchant(Enchantments.SHARPNESS, 5);
                sword.enchant(Enchantments.UNBREAKING, 3);
                sword.enchant(Enchantments.MENDING, 1);
                sword.getOrCreateTag().putBoolean("MysticExcalibur", true);
                if (!player.getInventory().add(sword)) {
                    player.drop(sword, false);
                }
                restoreForest(level, this);
                player.teleportTo(level, returnPos.x, returnPos.y, returnPos.z, yaw, pitch);
                player.playNotifySound(SoundEvents.PLAYER_LEVELUP, SoundSource.PLAYERS, 1F, .8F);
                forestEvents.remove(playerId);
                playerEventTicks.put(playerId, 0);
                player.displayClientMessage(Component.literal("La niebla te devuelve exactamente al lugar donde estabas."), false);
                break;
            }
        }
    }

    private static long randomDuration(ServerLevel level) {
        int min = MistConfig.MIN_SECONDS.get() * 20;
        int max = Math.max(min, MistConfig.MAX_SECONDS.get() * 20);
        return min + level.random.nextInt(max - min + 1);
    }

    public static boolean start(MinecraftServer server) {
        ServerLevel level = server.getLevel(Level.OVERWORLD);
        if (level == null) return false;
        return start(server, randomDuration(level));
    }

    public static boolean start(MinecraftServer server, long durationTicks) {
        ServerLevel level = server.getLevel(Level.OVERWORLD);
        if (level == null) return false;
        MysticMistSavedData data = getData(level);
        if (data.isActive()) return false;

        data.start(durationTicks);
        for (ServerPlayer p : server.getPlayerList().getPlayers()) playerEventTicks.put(p.getUUID(), 0);
        broadcast(server, Component.translatable("nieblamistica.warning"));
        broadcast(server, Component.translatable("nieblamistica.begin"));
        if (MistConfig.PLAY_SOUND.get()) {
            for (ServerPlayer p : server.getPlayerList().getPlayers()) {
                p.playNotifySound(SoundEvents.WARDEN_NEARBY_CLOSE, SoundSource.AMBIENT, .8F, .6F);
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
        for (ServerPlayer p : server.getPlayerList().getPlayers()) playerEventTicks.put(p.getUUID(), 0);
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
                        ctx.getSource().sendSuccess(() -> Component.translatable("commands.nieblamistica.status.active", seconds), false);
                    } else {
                        ctx.getSource().sendSuccess(() -> Component.translatable("commands.nieblamistica.status.inactive"), false);
                    }
                    return 1;
                })));
    }
}
