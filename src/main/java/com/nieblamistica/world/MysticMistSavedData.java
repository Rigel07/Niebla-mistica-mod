package com.nieblamistica.world;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.saveddata.SavedData;

public class MysticMistSavedData extends SavedData {
    private boolean active;
    private long remainingTicks;

    public boolean isActive() {
        return active;
    }

    public long getRemainingTicks() {
        return remainingTicks;
    }

    public void start(long durationTicks) {
        active = true;
        remainingTicks = durationTicks;
        setDirty();
    }

    public void stop() {
        active = false;
        remainingTicks = 0;
        setDirty();
    }

    public void tick() {
        if (active && remainingTicks > 0) {
            remainingTicks--;
            if (remainingTicks == 0) {
                active = false;
            }
            setDirty();
        }
    }

    public static MysticMistSavedData load(CompoundTag tag) {
        MysticMistSavedData data = new MysticMistSavedData();
        data.active = tag.getBoolean("Active");
        data.remainingTicks = tag.getLong("RemainingTicks");
        return data;
    }

    @Override
    public CompoundTag save(CompoundTag tag) {
        tag.putBoolean("Active", active);
        tag.putLong("RemainingTicks", remainingTicks);
        return tag;
    }
}
