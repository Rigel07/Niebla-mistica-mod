package com.nieblamistica.client;

import com.nieblamistica.NieblaMistica;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.FogRenderer;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.material.FogType;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.client.event.ViewportEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * Renderizado de la niebla.
 *
 * La versión anterior usaba una capa negra sobre toda la interfaz, lo que
 * hacía que pareciera un filtro de pantalla. Aquí la niebla se consigue
 * principalmente con profundidad + color ambiental, como una niebla real.
 */
@Mod.EventBusSubscriber(
        modid = NieblaMistica.MOD_ID,
        value = Dist.CLIENT,
        bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class MistClientEvents {
    private static final float OUTDOOR_NEAR = 4.5F;
    private static final float OUTDOOR_FAR = 34.0F;
    private static final float INDOOR_NEAR = 7.0F;
    private static final float INDOOR_FAR = 48.0F;

    private MistClientEvents() {}

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;

        Minecraft mc = Minecraft.getInstance();
        float target = 0F;

        if (mc.level != null && mc.player != null
                && MistClientState.isActive()
                && mc.level.dimension() == Level.OVERWORLD) {

            boolean outdoor = mc.level.canSeeSky(mc.player.blockPosition());

            // Bajo techo la niebla sigue existiendo, pero no invade la cámara
            // con la misma intensidad. Esto evita que parezca una textura negra.
            target = outdoor ? 1.0F : 0.32F;
        }

        MistClientState.clientTick(target);

        if (mc.level != null && mc.player != null && !mc.isPaused()) {
            spawnWisps(mc, MistClientState.getIntensity());
        }
    }

    /**
     * Pequeñas partículas dispersas, mucho más discretas que una nube grande.
     * La mayor parte del efecto visual procede del fog renderer.
     */
    private static void spawnWisps(Minecraft mc, float intensity) {
        if (intensity < 0.12F) return;

        RandomSource random = mc.level.random;

        // Una partícula aproximadamente cada pocos ticks, no una nube
        // constante alrededor de la cámara.
        if (random.nextInt(5) != 0) return;

        double angle = random.nextDouble() * Math.PI * 2.0D;
        double distance = 5.0D + random.nextDouble() * 11.0D;
        double x = mc.player.getX() + Math.cos(angle) * distance;
        double y = mc.player.getY() + 0.15D + random.nextDouble() * 1.7D;
        double z = mc.player.getZ() + Math.sin(angle) * distance;

        // WHITE_ASH son partículas pequeñas y suaves: sirven como motas
        // suspendidas sin convertir la escena en humo blanco.
        mc.level.addParticle(
                ParticleTypes.WHITE_ASH,
                x, y, z,
                (random.nextDouble() - 0.5D) * 0.008D,
                0.004D + random.nextDouble() * 0.006D,
                (random.nextDouble() - 0.5D) * 0.008D
        );
    }

    @SubscribeEvent
    public static void onLogout(ClientPlayerNetworkEvent.LoggingOut event) {
        MistClientState.reset();
    }

    @SubscribeEvent
    public static void onFogColor(ViewportEvent.ComputeFogColor event) {
        float i = getNaturalIntensity();

        if (i <= 0.001F) return;

        /*
         * En lugar de convertir el mundo en casi negro, desaturamos el color
         * hacia un gris frío. La iluminación de Minecraft sigue participando.
         */
        event.setRed(Mth.lerp(i, event.getRed(), 0.46F));
        event.setGreen(Mth.lerp(i, event.getGreen(), 0.49F));
        event.setBlue(Mth.lerp(i, event.getBlue(), 0.52F));
    }

    @SubscribeEvent
    public static void onRenderFog(ViewportEvent.RenderFog event) {
        float i = getNaturalIntensity();

        if (i <= 0.001F) return;
        if (event.getType() != FogType.NONE) return;
        if (event.getMode() != FogRenderer.FogMode.FOG_TERRAIN) return;

        float vanillaNear = event.getNearPlaneDistance();
        float vanillaFar = event.getFarPlaneDistance();

        boolean underground = false;
        Minecraft mc = Minecraft.getInstance();
        if (mc.level != null && mc.player != null) {
            underground = !mc.level.canSeeSky(mc.player.blockPosition());
        }

        float targetNear = underground ? INDOOR_NEAR : OUTDOOR_NEAR;
        float targetFar = underground ? INDOOR_FAR : OUTDOOR_FAR;

        /*
         * Una pequeña oscilación evita que la niebla parezca una caja rígida.
         * El cambio es lento y casi imperceptible: simula bancos de niebla.
         */
        float wave = 0.94F + 0.06F * Mth.sin(
                (mc.level != null ? mc.level.getGameTime() : 0L) * 0.025F
                        + (mc.player != null ? (float) (mc.player.getX() + mc.player.getZ()) * 0.018F : 0F));

        targetFar *= wave;

        event.setNearPlaneDistance(Mth.lerp(i, vanillaNear, targetNear));
        event.setFarPlaneDistance(Mth.lerp(i, vanillaFar, targetFar));
        event.setCanceled(true);
    }

    /**
     * Intensidad con una ligera variación natural. No modifica el estado del
     * servidor: solamente hace que la niebla respire visualmente.
     */
    private static float getNaturalIntensity() {
        float i = MistClientState.getIntensity();
        if (i <= 0.001F) return 0F;

        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return i;

        float wave = 0.96F + 0.04F * Mth.sin(mc.level.getGameTime() * 0.018F);
        return Mth.clamp(i * wave, 0F, 1F);
    }
}
