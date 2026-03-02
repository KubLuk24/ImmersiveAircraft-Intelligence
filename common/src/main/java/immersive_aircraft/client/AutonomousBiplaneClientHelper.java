package immersive_aircraft.client;

import immersive_aircraft.client.gui.AutopilotScreen;
import immersive_aircraft.entity.AutonomousBiplaneEntity;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.player.Player;

public class AutonomousBiplaneClientHelper {
    public static void handleTick(AutonomousBiplaneEntity vehicle) {
        for (var entity : vehicle.getPassengers()) {
            if (entity instanceof Player player && player.isLocalPlayer()) {
                if (KeyBindings.autopilot.consumeClick()) {
                    Minecraft.getInstance().setScreen(new AutopilotScreen(vehicle));
                }
            }
        }
    }
}
