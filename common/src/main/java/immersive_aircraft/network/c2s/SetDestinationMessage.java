package immersive_aircraft.network.c2s;

import immersive_aircraft.cobalt.network.Message;
import immersive_aircraft.entity.AutonomousPlaneEntity;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;

/**
 * Client-to-server message for controlling an autonomous plane's destination and mode.
 */
public class SetDestinationMessage extends Message {

    public enum Action {
        SET_DESTINATION,
        TOGGLE_AUTONOMOUS,
        SAVE_DESTINATION,
        LOAD_DESTINATION,
        REMOVE_DESTINATION
    }

    private final Action action;
    private final int x;
    private final int y;
    private final int z;
    private final String name;
    private final int index;

    public SetDestinationMessage(int x, int y, int z) {
        this.action = Action.SET_DESTINATION;
        this.x = x;
        this.y = y;
        this.z = z;
        this.name = "";
        this.index = 0;
    }

    public SetDestinationMessage(boolean toggleAutonomous) {
        this.action = Action.TOGGLE_AUTONOMOUS;
        this.x = 0;
        this.y = 0;
        this.z = 0;
        this.name = "";
        this.index = 0;
    }

    public SetDestinationMessage(String name) {
        this.action = Action.SAVE_DESTINATION;
        this.x = 0;
        this.y = 0;
        this.z = 0;
        this.name = name;
        this.index = 0;
    }

    public SetDestinationMessage(Action action, int index) {
        this.action = action;
        this.x = 0;
        this.y = 0;
        this.z = 0;
        this.name = "";
        this.index = index;
    }

    public SetDestinationMessage(FriendlyByteBuf b) {
        action = Action.values()[b.readInt()];
        x = b.readInt();
        y = b.readInt();
        z = b.readInt();
        name = b.readUtf();
        index = b.readInt();
    }

    @Override
    public void encode(FriendlyByteBuf b) {
        b.writeInt(action.ordinal());
        b.writeInt(x);
        b.writeInt(y);
        b.writeInt(z);
        b.writeUtf(name);
        b.writeInt(index);
    }

    @Override
    public void receive(Player e) {
        if (e.getRootVehicle() instanceof AutonomousPlaneEntity plane && plane.hasPassenger(e)) {
            switch (action) {
                case SET_DESTINATION -> {
                    plane.setDestination(x, y, z);
                    if (e instanceof ServerPlayer sp) {
                        sp.displayClientMessage(Component.literal("Destination set to " + x + ", " + y + ", " + z), true);
                    }
                }
                case TOGGLE_AUTONOMOUS -> {
                    plane.setAutonomousMode(!plane.isAutonomousMode());
                    if (e instanceof ServerPlayer sp) {
                        sp.displayClientMessage(Component.literal(
                                plane.isAutonomousMode() ? "Autonomous mode enabled" : "Autonomous mode disabled"
                        ), true);
                    }
                }
                case SAVE_DESTINATION -> {
                    plane.saveDestination(name);
                    if (e instanceof ServerPlayer sp) {
                        sp.displayClientMessage(Component.literal("Destination '" + name + "' saved"), true);
                    }
                }
                case LOAD_DESTINATION -> {
                    plane.loadDestination(index);
                    if (e instanceof ServerPlayer sp) {
                        sp.displayClientMessage(Component.literal("Destination loaded"), true);
                    }
                }
                case REMOVE_DESTINATION -> {
                    plane.removeDestination(index);
                    if (e instanceof ServerPlayer sp) {
                        sp.displayClientMessage(Component.literal("Destination removed"), true);
                    }
                }
            }
        }
    }
}
