package com.nieblamistica.client;

/** Estado de la niebla en el cliente. 'intensity' sube/baja suavemente entre 0 y 1. */
public final class MistClientState {
    private static final float FADE_PER_TICK = 0.01F; // ~5 segundos de transición

    private static boolean active;
    private static long remainingTicks;
    private static float intensity;

    private MistClientState() {}

    public static void set(boolean newActive, long ticks) {
        active = newActive;
        remainingTicks = ticks;
    }

    public static void reset() {
        active = false;
        remainingTicks = 0;
        intensity = 0F;
    }

    public static boolean isActive() {
        return active;
    }

    public static long getRemainingTicks() {
        return remainingTicks;
    }

    /** 0 = sin niebla, 1 = niebla completa. */
    public static float getIntensity() {
        return intensity;
    }

    /** Se llama cada tick del cliente. 'target' es la intensidad deseada (0 si no aplica). */
    public static void clientTick(float target) {
        if (active && remainingTicks > 0) {
            remainingTicks--;
            if (remainingTicks == 0) {
                active = false;
            }
        }
        if (intensity < target) {
            intensity = Math.min(target, intensity + FADE_PER_TICK);
        } else if (intensity > target) {
            intensity = Math.max(target, intensity - FADE_PER_TICK);
        }
    }
}
