package immersive_aircraft.client.gui;

import immersive_aircraft.cobalt.network.NetworkHandler;
import immersive_aircraft.entity.AutonomousBiplaneEntity;
import immersive_aircraft.network.c2s.AutopilotMessage;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

public class AutopilotScreen extends Screen {
    private final AutonomousBiplaneEntity vehicle;

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

        // Shift main controls up to make room for presets
        int mainY = centerY - 55;

        // Coordinate labels and fields
        xField = new EditBox(this.font, startX, mainY, fieldWidth, fieldHeight, Component.literal("X"));
        xField.setValue(String.valueOf(vehicle.getDestX()));
        xField.setFilter(AutopilotScreen::isValidCoordinate);
        this.addRenderableWidget(xField);

        yField = new EditBox(this.font, startX + fieldWidth + spacing, mainY, fieldWidth, fieldHeight, Component.literal("Y"));
        yField.setValue(String.valueOf(vehicle.getDestY()));
        yField.setFilter(AutopilotScreen::isValidCoordinate);
        this.addRenderableWidget(yField);

        zField = new EditBox(this.font, startX + (fieldWidth + spacing) * 2, mainY, fieldWidth, fieldHeight, Component.literal("Z"));
        zField.setValue(String.valueOf(vehicle.getDestZ()));
        zField.setFilter(AutopilotScreen::isValidCoordinate);
        this.addRenderableWidget(zField);

        // Toggle autopilot button
        toggleButton = Button.builder(getToggleText(), button -> {
            boolean newState = !vehicle.isAutopilotEnabled();
            applyDestination();
            vehicle.setAutopilotEnabled(newState);
            NetworkHandler.sendToServer(new AutopilotMessage(newState,
                    vehicle.getDestX(), vehicle.getDestY(), vehicle.getDestZ()));
            updateToggleButton();
        }).bounds(centerX - 75, mainY + 28, 150, 20).build();
        this.addRenderableWidget(toggleButton);

        // Apply button (set coordinates without changing autopilot state)
        Button applyButton = Button.builder(Component.translatable("immersive_aircraft.autopilot.apply"), button -> {
            applyDestination();
            NetworkHandler.sendToServer(new AutopilotMessage(vehicle.isAutopilotEnabled(),
                    vehicle.getDestX(), vehicle.getDestY(), vehicle.getDestZ()));
        }).bounds(centerX - 75, mainY + 53, 150, 20).build();
        this.addRenderableWidget(applyButton);

        // --- Preset destination buttons (5 slots) ---
        int presetStartY = mainY + 82;
        int presetBtnWidth = 60;
        int saveBtnWidth = 40;
        int presetSpacing = 3;
        int presetRowHeight = 18;

        for (int i = 0; i < AutonomousBiplaneEntity.MAX_PRESETS; i++) {
            final int slot = i;
            int rowY = presetStartY + i * (presetRowHeight + presetSpacing);

            // Load button: loads preset into the coordinate fields
            Button loadBtn = Button.builder(getPresetLabel(slot), button -> {
                if (vehicle.hasPreset(slot)) {
                    xField.setValue(String.valueOf(vehicle.getPresetX(slot)));
                    yField.setValue(String.valueOf(vehicle.getPresetY(slot)));
                    zField.setValue(String.valueOf(vehicle.getPresetZ(slot)));
                }
            }).bounds(centerX - 75, rowY, presetBtnWidth, presetRowHeight).build();
            this.addRenderableWidget(loadBtn);

            // Save button: saves current coords to this preset slot
            Button saveBtn = Button.builder(Component.translatable("immersive_aircraft.autopilot.save"), button -> {
                int px = parseCoord(xField.getValue(), vehicle.getDestX());
                int py = parseCoord(yField.getValue(), vehicle.getDestY());
                int pz = parseCoord(zField.getValue(), vehicle.getDestZ());
                vehicle.savePreset(slot, px, py, pz);
                NetworkHandler.sendToServer(new AutopilotMessage(slot, px, py, pz));
                // Refresh the load button label to show new coords
                rebuildWidgets();
            }).bounds(centerX - 75 + presetBtnWidth + presetSpacing, rowY, saveBtnWidth, presetRowHeight).build();
            this.addRenderableWidget(saveBtn);
        }
    }

    private Component getPresetLabel(int slot) {
        if (vehicle.hasPreset(slot)) {
            return Component.literal((slot + 1) + ": " +
                    vehicle.getPresetX(slot) + ", " +
                    vehicle.getPresetY(slot) + ", " +
                    vehicle.getPresetZ(slot));
        }
        return Component.translatable("immersive_aircraft.autopilot.preset_empty", slot + 1);
    }

    private Component getToggleText() {
        return vehicle.isAutopilotEnabled()
                ? Component.translatable("immersive_aircraft.autopilot.disable")
                : Component.translatable("immersive_aircraft.autopilot.enable");
    }

    private void updateToggleButton() {
        toggleButton.setMessage(getToggleText());
    }

    private void applyDestination() {
        int destX = parseCoord(xField.getValue(), vehicle.getDestX());
        int destY = parseCoord(yField.getValue(), vehicle.getDestY());
        int destZ = parseCoord(zField.getValue(), vehicle.getDestZ());
        vehicle.setDestination(destX, destY, destZ);
    }

    @Override
    public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        this.renderBackground(guiGraphics);
        int centerX = this.width / 2;
        int centerY = this.height / 2;
        int mainY = centerY - 55;

        guiGraphics.drawCenteredString(this.font, this.title, centerX, mainY - 15, 0xFFFFFF);

        int fieldWidth = 80;
        int spacing = 5;
        int totalWidth = fieldWidth * 3 + spacing * 2;
        int startX = centerX - totalWidth / 2;

        guiGraphics.drawString(this.font, "X:", startX - 15, mainY + 5, 0xFFAAAA);
        guiGraphics.drawString(this.font, "Y:", startX + fieldWidth + spacing - 15, mainY + 5, 0xAAFFAA);
        guiGraphics.drawString(this.font, "Z:", startX + (fieldWidth + spacing) * 2 - 15, mainY + 5, 0xAAAAFF);

        // Status display
        int statusY = mainY + 75;
        if (vehicle.isAutopilotEnabled()) {
            guiGraphics.drawCenteredString(this.font,
                    Component.translatable("immersive_aircraft.autopilot.status.active"),
                    centerX, statusY, 0x55FF55);
        } else {
            guiGraphics.drawCenteredString(this.font,
                    Component.translatable("immersive_aircraft.autopilot.status.inactive"),
                    centerX, statusY, 0xFF5555);
        }

        // Presets header
        int presetHeaderY = mainY + 82 - 12;
        guiGraphics.drawString(this.font,
                Component.translatable("immersive_aircraft.autopilot.presets_header"),
                centerX - 75, presetHeaderY, 0xCCCCCC);

        super.render(guiGraphics, mouseX, mouseY, partialTick);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    private static boolean isValidCoordinate(String text) {
        if (text.isEmpty() || text.equals("-")) return true;
        try {
            Integer.parseInt(text);
            return true;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private static int parseCoord(String text, int fallback) {
        try {
            return Integer.parseInt(text);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
