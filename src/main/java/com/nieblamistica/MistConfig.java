package com.nieblamistica;

import net.minecraftforge.common.ForgeConfigSpec;

/** Ajustes en config/nieblamistica-common.toml */
public final class MistConfig {
    public static final ForgeConfigSpec SPEC;

    public static final ForgeConfigSpec.BooleanValue SPONTANEOUS;
    public static final ForgeConfigSpec.DoubleValue CHANCE_PERCENT;
    public static final ForgeConfigSpec.IntValue CHECK_SECONDS;
    public static final ForgeConfigSpec.IntValue MIN_SECONDS;
    public static final ForgeConfigSpec.IntValue MAX_SECONDS;
    public static final ForgeConfigSpec.BooleanValue NOT_IN_PEACEFUL;
    public static final ForgeConfigSpec.BooleanValue PLAY_SOUND;

    static {
        ForgeConfigSpec.Builder b = new ForgeConfigSpec.Builder();

        b.push("mist");
        SPONTANEOUS = b.comment("Si es false, la niebla solo aparece con /mistica start.")
                .define("spontaneous", true);
        CHANCE_PERCENT = b.comment("Probabilidad (%) de que empiece la niebla en cada comprobacion.")
                .defineInRange("chancePercent", 8.0, 0.0, 100.0);
        CHECK_SECONDS = b.comment("Cada cuantos segundos se hace la comprobacion.")
                .defineInRange("checkIntervalSeconds", 30, 1, 3600);
        MIN_SECONDS = b.comment("Duracion minima de la niebla (segundos).")
                .defineInRange("minDurationSeconds", 60, 5, 7200);
        MAX_SECONDS = b.comment("Duracion maxima de la niebla (segundos).")
                .defineInRange("maxDurationSeconds", 180, 5, 7200);
        NOT_IN_PEACEFUL = b.comment("No aparece sola en dificultad Pacifica.")
                .define("notInPeaceful", true);
        PLAY_SOUND = b.comment("Suena un sonido inquietante cuando empieza la niebla.")
                .define("playSound", true);
        b.pop();

        SPEC = b.build();
    }

    private MistConfig() {}
}
