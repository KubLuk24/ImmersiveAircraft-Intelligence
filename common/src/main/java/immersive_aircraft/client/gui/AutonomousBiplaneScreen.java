package immersive_aircraft.client.gui;

import immersive_aircraft.cobalt.network.NetworkHandler;
import immersive_aircraft.entity.AutonomousBiplaneEntity;
import immersive_aircraft.network.c2s.UpdateAutopilotMessage;
import immersive_aircraft.screen.VehicleScreenHandler;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;

public class AutonomousBiplaneScreen extends VehicleScreen {
    private EditBox xInput;
    private EditBox zInput;
    private Button toggleButton;

    public AutonomousBiplaneScreen(VehicleScreenHandler handler, Inventory inventory, Component title) {
        super(handler, inventory, title);
    }

    @Override
    protected void init() {
        super.init();

        AutonomousBiplaneEntity vehicle = (AutonomousBiplaneEntity) this.menu.getVehicle();

        int startX = this.leftPos + this.imageWidth + 10;
        int startY = this.topPos;

        this.xInput = new EditBox(this.font, startX, startY + 20, 50, 20, Component.literal("X"));
        this.xInput.setValue(String.valueOf(vehicle.getTargetX()));
        this.xInput.setResponder(s -> updateAutopilot());
        this.addRenderableWidget(this.xInput);

        this.zInput = new EditBox(this.font, startX, startY + 50, 50, 20, Component.literal("Z"));
        this.zInput.setValue(String.valueOf(vehicle.getTargetZ()));
        this.zInput.setResponder(s -> updateAutopilot());
        this.addRenderableWidget(this.zInput);

        this.toggleButton = Button.builder(
                Component.literal(vehicle.isAutopilotEnabled() ? "Autopilot: ON" : "Autopilot: OFF"),
                b -> {
                    boolean newState = !vehicle.isAutopilotEnabled();
                    vehicle.setAutopilotEnabled(newState);
                    updateButtonText();
                    updateAutopilot();
                })
                .bounds(startX, startY + 80, 90, 20)
                .build();
        this.addRenderableWidget(this.toggleButton);
    }

    private void updateButtonText() {
        AutonomousBiplaneEntity vehicle = (AutonomousBiplaneEntity) this.menu.getVehicle();
        this.toggleButton.setMessage(Component.literal(vehicle.isAutopilotEnabled() ? "Autopilot: ON" : "Autopilot: OFF"));
    }

    private void updateAutopilot() {
        try {
            int x = Integer.parseInt(this.xInput.getValue());
            int z = Integer.parseInt(this.zInput.getValue());
            AutonomousBiplaneEntity vehicle = (AutonomousBiplaneEntity) this.menu.getVehicle();
            NetworkHandler.sendToServer(new UpdateAutopilotMessage(x, z, vehicle.isAutopilotEnabled()));
        } catch (NumberFormatException e) {
            // Ignore
        }
    }

    @Override
    public void render(GuiGraphics context, int mouseX, int mouseY, float delta) {
        super.render(context, mouseX, mouseY, delta);

        int startX = this.leftPos + this.imageWidth + 10;
        int startY = this.topPos;

        context.drawString(this.font, "Target X:", startX, startY + 10, 0xFFFFFF);
        context.drawString(this.font, "Target Z:", startX, startY + 40, 0xFFFFFF);
    }
}
