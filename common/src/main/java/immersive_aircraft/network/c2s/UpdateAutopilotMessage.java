package immersive_aircraft.network.c2s;

import immersive_aircraft.cobalt.network.Message;
import immersive_aircraft.entity.AutonomousBiplaneEntity;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;

public class UpdateAutopilotMessage extends Message {
    public final int x;
    public final int z;
    public final boolean enabled;

    public UpdateAutopilotMessage(int x, int z, boolean enabled) {
        this.x = x;
        this.z = z;
        this.enabled = enabled;
    }

    public UpdateAutopilotMessage(FriendlyByteBuf buf) {
        this.x = buf.readInt();
        this.z = buf.readInt();
        this.enabled = buf.readBoolean();
    }

    @Override
    public void encode(FriendlyByteBuf buf) {
        buf.writeInt(x);
        buf.writeInt(z);
        buf.writeBoolean(enabled);
    }

    @Override
    public void receive(Player player) {
        Entity vehicle = player.getVehicle();
        if (vehicle instanceof AutonomousBiplaneEntity plane) {
            plane.setTarget(x, z);
            plane.setAutopilotEnabled(enabled);
        }
    }
}
