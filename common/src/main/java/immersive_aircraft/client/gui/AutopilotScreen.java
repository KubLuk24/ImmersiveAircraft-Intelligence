package immersive_aircraft.client.gui;

import immersive_aircraft.cobalt.network.NetworkHandler;
import immersive_aircraft.entity.AutonomousBiplaneEntity;
import immersive_aircraft.entity.autopilot.DestinationMemory;
import immersive_aircraft.network.c2s.AutopilotMessage;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * Client-side GUI screen for the autopilot system.
 *
 * Allows the player to:
 * - Enter destination coordinates (X, Y, Z)
 * - Toggle autopilot on/off
 * - Save destinations to preset slots (up to 5)
 * - Load destinations from preset slots
 */
public class AutopilotScreen extends Screen {
    private final AutonomousBiplaneEntity vehicle;

    // Coordinate input fields
    private EditBox xField;
    private EditBox yField;
    private EditBox zField;
    private Button toggleButton;

    public AutopilotScreen(AutonomousBiplaneEntity vehicle) {
        super(Component.translatable("immersive_aircraft.autopilot.title"));
        this.vehicle = vehicle;
    }

    @Override
    protected void init() {
        super.init();

        int centerX = this.width / 2;
        int centerY = this.height / 2;
        int fieldWidth = 80;
        int fieldHeight = 20;
        int spacing = 5;
        int totalWidth = fieldWidth * 3 + spacing * 2;
        int startX = centerX - totalWidth / 2;

        // --- Coordinate input fields ---
        xField = new EditBox(this.font, startX, centerY - 30, fieldWidth, fieldHeight, Component.literal("X"));
        xField.setValue(String.valueOf(vehicle.getDestX()));
        xField.setFilter(AutopilotScreen::isValidCoordinate);
        this.addRenderableWidget(xField);

        yField = new EditBox(this.font, startX + fieldWidth + spacing, centerY - 30, fieldWidth, fieldHeight, Component.literal("Y"));
        yField.setValue(String.valueOf(vehicle.getDestY()));
        yField.setFilter(AutopilotScreen::isValidCoordinate);
        this.addRenderableWidget(yField);

        zField = new EditBox(this.font, startX + (fieldWidth + spacing) * 2, centerY - 30, fieldWidth, fieldHeight, Component.literal("Z"));
        zField.setValue(String.valueOf(vehicle.getDestZ()));
        zField.setFilter(AutopilotScreen::isValidCoordinate);
        this.addRenderableWidget(zField);

        // --- Toggle autopilot button ---
        toggleButton = Button.builder(getToggleText(), button -> {
            boolean newState = !vehicle.isAutopilotEnabled();
            applyDestination();
            vehicle.setAutopilotEnabled(newState);
            NetworkHandler.sendToServer(new AutopilotMessage(newState,
                    vehicle.getDestX(), vehicle.getDestY(), vehicle.getDestZ()));
            updateToggleButton();
        }).bounds(centerX - 75, centerY + 10, 150, 20).build();
        this.addRenderableWidget(toggleButton);

        // --- Apply button (set coordinates without toggling autopilot) ---
        Button applyButton = Button.builder(Component.translatable("immersive_aircraft.autopilot.apply"), button -> {
            applyDestination();
            NetworkHandler.sendToServer(new AutopilotMessage(vehicle.isAutopilotEnabled(),
                    vehicle.getDestX(), vehicle.getDestY(), vehicle.getDestZ()));
        }).bounds(centerX - 75, centerY + 35, 150, 20).build();
        this.addRenderableWidget(applyButton);

        // --- Preset destination buttons ---
        int presetY = centerY + 65;
        int presetBtnWidth = 45;
        int presetSpacing = 4;
        DestinationMemory memory = vehicle.getDestinationMemory();

        for (int i = 0; i < DestinationMemory.MAX_PRESETS; i++) {
            final int slot = i;
            DestinationMemory.Preset preset = memory.getPreset(slot);

            // Load button: loads preset coordinates into the fields
            String loadLabel = preset.isOccupied()
                    ? (slot + 1) + ": " + truncate(preset.getName(), 6)
                    : (slot + 1) + ": ---";

            Button loadBtn = Button.builder(Component.literal(loadLabel), button -> {
                DestinationMemory.Preset p = memory.getPreset(slot);
                if (p != null && p.isOccupied()) {
                    xField.setValue(String.valueOf(p.getX()));
                    yField.setValue(String.valueOf(p.getY()));
                    zField.setValue(String.valueOf(p.getZ()));
                    // Also send to server to set destination
                    vehicle.setDestination(p.getX(), p.getY(), p.getZ());
                    NetworkHandler.sendToServer(AutopilotMessage.loadPreset(slot));
                }
            }).bounds(startX + (presetBtnWidth + presetSpacing) * slot, presetY,
                    presetBtnWidth, 16).build();
            this.addRenderableWidget(loadBtn);

            // Save button: saves current field values to this preset slot
            Button saveBtn = Button.builder(Component.translatable("immersive_aircraft.autopilot.save"), button -> {
                int px = parseCoord(xField.getValue(), vehicle.getDestX());
                int py = parseCoord(yField.getValue(), vehicle.getDestY());
                int pz = parseCoord(zField.getValue(), vehicle.getDestZ());
                String name = "P" + (slot + 1);
                memory.savePreset(slot, name, px, py, pz);
                NetworkHandler.sendToServer(AutopilotMessage.savePreset(slot, name, px, py, pz));
                // Rebuild the screen to refresh labels
                rebuildWidgets();
            }).bounds(startX + (presetBtnWidth + presetSpacing) * slot, presetY + 18,
                    presetBtnWidth, 16).build();
            this.addRenderableWidget(saveBtn);
        }
    }

    private Component getToggleText() {
        return vehicle.isAutopilotEnabled()
                ? Component.translatable("immersive_aircraft.autopilot.disable")
                : Component.translatable("immersive_aircraft.autopilot.enable");
    }

    private void updateToggleButton() {
        toggleButton.setMessage(getToggleText());
    }

    /** Parse coordinate fields and set them on the vehicle. */
    private void applyDestination() {
        int destX = parseCoord(xField.getValue(), vehicle.getDestX());
        int destY = parseCoord(yField.getValue(), vehicle.getDestY());
        int destZ = parseCoord(zField.getValue(), vehicle.getDestZ());
        vehicle.setDestination(destX, destY, destZ);
    }

    @Override
    public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        this.renderBackground(guiGraphics);
        guiGraphics.drawCenteredString(this.font, this.title, this.width / 2, this.height / 2 - 60, 0xFFFFFF);

        int centerX = this.width / 2;
        int centerY = this.height / 2;
        int fieldWidth = 80;
        int spacing = 5;
        int totalWidth = fieldWidth * 3 + spacing * 2;
        int startX = centerX - totalWidth / 2;

        // Coordinate labels
        guiGraphics.drawString(this.font, "X:", startX - 15, centerY - 25, 0xFFAAAA);
        guiGraphics.drawString(this.font, "Y:", startX + fieldWidth + spacing - 15, centerY - 25, 0xAAFFAA);
        guiGraphics.drawString(this.font, "Z:", startX + (fieldWidth + spacing) * 2 - 15, centerY - 25, 0xAAAAFF);

        // Autopilot status
        if (vehicle.isAutopilotEnabled()) {
            guiGraphics.drawCenteredString(this.font,
                    Component.translatable("immersive_aircraft.autopilot.status.active"),
                    centerX, centerY + 57, 0x55FF55);
        } else {
            guiGraphics.drawCenteredString(this.font,
                    Component.translatable("immersive_aircraft.autopilot.status.inactive"),
                    centerX, centerY + 57, 0xFF5555);
        }

        // Presets label
        guiGraphics.drawString(this.font,
                Component.translatable("immersive_aircraft.autopilot.presets"),
                startX - 15, centerY + 67, 0xAAAAAA);

        super.render(guiGraphics, mouseX, mouseY, partialTick);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    /** Validate that the text is a valid integer coordinate (or partial input). */
    private static boolean isValidCoordinate(String text) {
        if (text.isEmpty() || text.equals("-")) return true;
        try {
            Integer.parseInt(text);
            return true;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    /** Parse an integer coordinate from text, returning fallback on failure. */
    private static int parseCoord(String text, int fallback) {
        try {
            return Integer.parseInt(text);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    /** Truncate a string to the given max length, adding "…" if needed. */
    private static String truncate(String s, int maxLen) {
        if (s.length() <= maxLen) return s;
        return s.substring(0, maxLen - 1) + "…";
    }
}
