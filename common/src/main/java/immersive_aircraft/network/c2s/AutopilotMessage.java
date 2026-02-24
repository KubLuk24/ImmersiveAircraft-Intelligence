package immersive_aircraft.network.c2s;

import immersive_aircraft.cobalt.network.Message;
import immersive_aircraft.entity.AutonomousBiplaneEntity;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.entity.player.Player;

/**
 * Network message sent from client to server for autopilot operations.
 *
 * Supports three actions:
 * - SET_DESTINATION (0): set destination and toggle autopilot
 * - SAVE_PRESET (1): save a destination to a preset slot
 * - LOAD_PRESET (2): load a destination from a preset slot
 */
public class AutopilotMessage extends Message {

    /** Action codes */
    public static final int ACTION_SET_DESTINATION = 0;
    public static final int ACTION_SAVE_PRESET = 1;
    public static final int ACTION_LOAD_PRESET = 2;

    private final int action;
    private final boolean enabled;
    private final int destX;
    private final int destY;
    private final int destZ;
    private final int presetSlot;
    private final String presetName;

    /** Standard constructor for setting destination and toggling autopilot */
    public AutopilotMessage(boolean enabled, int destX, int destY, int destZ) {
        this.action = ACTION_SET_DESTINATION;
        this.enabled = enabled;
        this.destX = destX;
        this.destY = destY;
        this.destZ = destZ;
        this.presetSlot = -1;
        this.presetName = "";
    }

    /** Constructor for saving a destination preset */
    public static AutopilotMessage savePreset(int slot, String name, int x, int y, int z) {
        return new AutopilotMessage(ACTION_SAVE_PRESET, false, x, y, z, slot, name);
    }

    /** Constructor for loading a destination preset */
    public static AutopilotMessage loadPreset(int slot) {
        return new AutopilotMessage(ACTION_LOAD_PRESET, false, 0, 0, 0, slot, "");
    }

    /** Internal all-args constructor */
    private AutopilotMessage(int action, boolean enabled, int destX, int destY, int destZ,
                             int presetSlot, String presetName) {
        this.action = action;
        this.enabled = enabled;
        this.destX = destX;
        this.destY = destY;
        this.destZ = destZ;
        this.presetSlot = presetSlot;
        this.presetName = presetName;
    }

    /** Deserialization constructor */
    public AutopilotMessage(FriendlyByteBuf b) {
        action = b.readInt();
        enabled = b.readBoolean();
        destX = b.readInt();
        destY = b.readInt();
        destZ = b.readInt();
        presetSlot = b.readInt();
        presetName = b.readUtf(64);
    }

    @Override
    public void encode(FriendlyByteBuf b) {
        b.writeInt(action);
        b.writeBoolean(enabled);
        b.writeInt(destX);
        b.writeInt(destY);
        b.writeInt(destZ);
        b.writeInt(presetSlot);
        b.writeUtf(presetName, 64);
    }

    @Override
    public void receive(Player e) {
        if (e.getRootVehicle() instanceof AutonomousBiplaneEntity vehicle) {
            switch (action) {
                case ACTION_SET_DESTINATION -> {
                    vehicle.setDestination(destX, destY, destZ);
                    if (enabled) {
                        vehicle.tryEnableAutopilot();
                    } else {
                        vehicle.setAutopilotEnabled(false);
                    }
                }
                case ACTION_SAVE_PRESET -> {
                    vehicle.getDestinationMemory().savePreset(presetSlot, presetName, destX, destY, destZ);
                }
                case ACTION_LOAD_PRESET -> {
                    var preset = vehicle.getDestinationMemory().getPreset(presetSlot);
                    if (preset != null && preset.isOccupied()) {
                        vehicle.setDestination(preset.getX(), preset.getY(), preset.getZ());
                    }
                }
            }
        }
    }
}
