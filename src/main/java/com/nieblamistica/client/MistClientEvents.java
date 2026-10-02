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
import net.minecraftforge.client.event.RenderGuiOverlayEvent;
import net.minecraftforge.client.event.ViewportEvent;
import net.minecraftforge.client.gui.overlay.VanillaGuiOverlay;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

@Mod.EventBusSubscriber(
        modid = NieblaMistica.MOD_ID,
        value = Dist.CLIENT,
        bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class MistClientEvents {
    private static final float MIST_FAR = 18.0F;

    private MistClientEvents() {}

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;

        Minecraft mc = Minecraft.getInstance();
        float target = 0F;
        // Solo en el Overworld; bajo tierra la niebla se suaviza.
        if (mc.level != null && mc.player != null && MistClientState.isActive()
                && mc.level.dimension() == Level.OVERWORLD) {
            target = mc.level.canSeeSky(mc.player.blockPosition()) ? 1.0F : 0.45F;
        }
        MistClientState.clientTick(target);

        if (mc.level != null && mc.player != null && !mc.isPaused()) {
            spawnWisps(mc, MistClientState.getIntensity());
        }
    }

    /** Volutas de niebla flotando alrededor del jugador. */
    private static void spawnWisps(Minecraft mc, float intensity) {
        if (intensity < 0.2F) return;
        RandomSource r = mc.level.random;
        if (r.nextInt(3) != 0) return;
        double x = mc.player.getX() + (r.nextDouble() - 0.5D) * 24.0D;
        double y = mc.player.getY() + r.nextDouble() * 4.0D - 0.5D;
        double z = mc.player.getZ() + (r.nextDouble() - 0.5D) * 24.0D;
        mc.level.addParticle(ParticleTypes.CLOUD, x, y, z,
                (r.nextDouble() - 0.5D) * 0.01D, 0.0D, (r.nextDouble() - 0.5D) * 0.01D);
    }

    @SubscribeEvent
    public static void onLogout(ClientPlayerNetworkEvent.LoggingOut event) {
        MistClientState.reset();
    }

    @SubscribeEvent
    public static void onFogColor(ViewportEvent.ComputeFogColor event) {
        float i = MistClientState.getIntensity();
        if (i <= 0.001F) return;

        event.setRed(Mth.lerp(i, event.getRed(), 0.075F));
        event.setGreen(Mth.lerp(i, event.getGreen(), 0.085F));
        event.setBlue(Mth.lerp(i, event.getBlue(), 0.095F));
    }

    @SubscribeEvent
    public static void onRenderFog(ViewportEvent.RenderFog event) {
        float i = MistClientState.getIntensity();
        if (i <= 0.001F) return;
        // No tocar la niebla de agua, lava o nieve en polvo, ni la del cielo.
        if (event.getType() != FogType.NONE) return;
        if (event.getMode() != FogRenderer.FogMode.FOG_TERRAIN) return;

        float vanillaNear = event.getNearPlaneDistance();
        float vanillaFar = event.getFarPlaneDistance();
        event.setNearPlaneDistance(Mth.lerp(i, vanillaNear, 1.0F));
        event.setFarPlaneDistance(Mth.lerp(i, vanillaFar, Math.min(vanillaFar, MIST_FAR)));
        event.setCanceled(true);
    }

    /** Capa oscura sobre el mundo (debajo del HUD). Se dibuja UNA vez por fotograma. */
    @SubscribeEvent
    public static void onOverlay(RenderGuiOverlayEvent.Pre event) {
        if (event.getOverlay() != VanillaGuiOverlay.HOTBAR.type()) return;

        float i = MistClientState.getIntensity();
        if (i <= 0.001F) return;

        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) return;

        int width = mc.getWindow().getGuiScaledWidth();
        int height = mc.getWindow().getGuiScaledHeight();

        int veil = ((int) (0x42 * i) << 24) | 0x090D12;
        event.getGuiGraphics().fill(0, 0, width, height, veil);

        int edge = Math.max(18, width / 18);
        int shade = (int) (0x30 * i) << 24;
        event.getGuiGraphics().fill(0, 0, edge, height, shade);
        event.getGuiGraphics().fill(width - edge, 0, width, height, shade);
    }
}
