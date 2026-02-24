package immersive_aircraft.network.c2s;

import immersive_aircraft.cobalt.network.Message;
import immersive_aircraft.entity.AutonomousBiplaneEntity;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.entity.player.Player;

public class AutopilotMessage extends Message {
    private final boolean enabled;
    private final int destX;
    private final int destY;
    private final int destZ;

    public AutopilotMessage(boolean enabled, int destX, int destY, int destZ) {
        this.enabled = enabled;
        this.destX = destX;
        this.destY = destY;
        this.destZ = destZ;
    }

    public AutopilotMessage(FriendlyByteBuf b) {
        enabled = b.readBoolean();
        destX = b.readInt();
        destY = b.readInt();
        destZ = b.readInt();
    }

    @Override
    public void encode(FriendlyByteBuf b) {
        b.writeBoolean(enabled);
        b.writeInt(destX);
        b.writeInt(destY);
        b.writeInt(destZ);
    }

    @Override
    public void receive(Player e) {
        if (e.getRootVehicle() instanceof AutonomousBiplaneEntity vehicle) {
            vehicle.setDestination(destX, destY, destZ);
            vehicle.setAutopilotEnabled(enabled);
        }
    }
}
