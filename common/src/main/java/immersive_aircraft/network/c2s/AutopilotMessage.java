package immersive_aircraft.network.c2s;

import immersive_aircraft.Main;
import immersive_aircraft.cobalt.network.Message;
import immersive_aircraft.entity.AutonomousBiplaneEntity;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;

public class AutopilotMessage extends Message {
    /** Action 0 = set destination + toggle, 1 = save preset, 2 = set destination only */
    private final int action;
    private final boolean enabled;
    private final int destX;
    private final int destY;
    private final int destZ;
    private final int presetSlot;

    /** Standard destination + toggle constructor */
    public AutopilotMessage(boolean enabled, int destX, int destY, int destZ) {
        this.action = 0;
        this.enabled = enabled;
        this.destX = destX;
        this.destY = destY;
        this.destZ = destZ;
        this.presetSlot = -1;
    }

    /** Save preset constructor */
    public AutopilotMessage(int presetSlot, int destX, int destY, int destZ) {
        this.action = 1;
        this.enabled = false;
        this.destX = destX;
        this.destY = destY;
        this.destZ = destZ;
        this.presetSlot = presetSlot;
    }

    public AutopilotMessage(FriendlyByteBuf b) {
        action = b.readByte();
        enabled = b.readBoolean();
        destX = b.readInt();
        destY = b.readInt();
        destZ = b.readInt();
        presetSlot = b.readByte();
    }

    @Override
    public void encode(FriendlyByteBuf b) {
        b.writeByte(action);
        b.writeBoolean(enabled);
        b.writeInt(destX);
        b.writeInt(destY);
        b.writeInt(destZ);
        b.writeByte(presetSlot);
    }

    @Override
    public void receive(Player e) {
        if (e.getRootVehicle() instanceof AutonomousBiplaneEntity vehicle) {
            switch (action) {
                case 0 -> {
                    vehicle.setDestination(destX, destY, destZ);
                    boolean success = vehicle.setAutopilotEnabled(enabled);
                    if (!success && e instanceof ServerPlayer sp) {
                        sp.displayClientMessage(
                                Component.translatable("immersive_aircraft.autopilot.blocked"), true);
                    }
                }
                case 1 -> {
                    if (presetSlot >= 0 && presetSlot < AutonomousBiplaneEntity.MAX_PRESETS) {
                        vehicle.savePreset(presetSlot, destX, destY, destZ);
                    }
                }
                default -> Main.LOGGER.warn("Unknown autopilot action: {}", action);
            }
        }
    }
}
