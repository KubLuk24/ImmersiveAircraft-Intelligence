package immersive_aircraft.entity.autopilot;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.core.BlockPos;

/**
 * Stores up to {@link #MAX_PRESETS} named destination presets that the
 * player can save and recall from the autopilot GUI.
 *
 * Each preset holds a name and XYZ coordinates.
 * The presets are persisted in NBT alongside the entity data.
 */
public class DestinationMemory {

    /** Maximum number of destination presets */
    public static final int MAX_PRESETS = 5;

    /** Stored presets */
    private final Preset[] presets;

    /**
     * A single saved destination.
     */
    public static class Preset {
        private String name;
        private int x;
        private int y;
        private int z;
        private boolean occupied;

        public Preset() {
            this.name = "";
            this.x = 0;
            this.y = 100;
            this.z = 0;
            this.occupied = false;
        }

        public Preset(String name, int x, int y, int z) {
            this.name = name;
            this.x = x;
            this.y = y;
            this.z = z;
            this.occupied = true;
        }

        public String getName() { return name; }
        public int getX() { return x; }
        public int getY() { return y; }
        public int getZ() { return z; }
        public boolean isOccupied() { return occupied; }

        public void set(String name, int x, int y, int z) {
            this.name = name;
            this.x = x;
            this.y = y;
            this.z = z;
            this.occupied = true;
        }

        public void clear() {
            this.name = "";
            this.x = 0;
            this.y = 100;
            this.z = 0;
            this.occupied = false;
        }

        /** Serialize this preset into an NBT compound. */
        public CompoundTag toNbt() {
            CompoundTag tag = new CompoundTag();
            tag.putString("Name", name);
            tag.putInt("X", x);
            tag.putInt("Y", y);
            tag.putInt("Z", z);
            tag.putBoolean("Occupied", occupied);
            return tag;
        }

        /** Deserialize this preset from an NBT compound. */
        public void fromNbt(CompoundTag tag) {
            name = tag.getString("Name");
            x = tag.getInt("X");
            y = tag.getInt("Y");
            z = tag.getInt("Z");
            occupied = tag.getBoolean("Occupied");
        }
    }

    public DestinationMemory() {
        presets = new Preset[MAX_PRESETS];
        for (int i = 0; i < MAX_PRESETS; i++) {
            presets[i] = new Preset();
        }
    }

    /**
     * Get a preset by slot index (0-based).
     *
     * @param slot slot index (0 to MAX_PRESETS-1)
     * @return the preset, or null if the slot is out of range
     */
    public Preset getPreset(int slot) {
        if (slot < 0 || slot >= MAX_PRESETS) return null;
        return presets[slot];
    }

    /**
     * Save a destination to a preset slot.
     *
     * @param slot slot index (0 to MAX_PRESETS-1)
     * @param name display name for the preset
     * @param x    X coordinate
     * @param y    Y coordinate
     * @param z    Z coordinate
     */
    public void savePreset(int slot, String name, int x, int y, int z) {
        if (slot < 0 || slot >= MAX_PRESETS) return;
        presets[slot].set(name, x, y, z);
    }

    /**
     * Clear a preset slot.
     *
     * @param slot slot index to clear
     */
    public void clearPreset(int slot) {
        if (slot < 0 || slot >= MAX_PRESETS) return;
        presets[slot].clear();
    }

    /**
     * Serialize all presets into an NBT list tag.
     */
    public ListTag toNbt() {
        ListTag list = new ListTag();
        for (int i = 0; i < MAX_PRESETS; i++) {
            list.add(presets[i].toNbt());
        }
        return list;
    }

    /**
     * Deserialize all presets from an NBT list tag.
     */
    public void fromNbt(ListTag list) {
        for (int i = 0; i < Math.min(list.size(), MAX_PRESETS); i++) {
            presets[i].fromNbt(list.getCompound(i));
        }
    }
}
