package dev.scholardictation;

import com.google.gson.Gson;
import com.google.gson.JsonParseException;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;

final class MicrophoneScreen extends Screen {
    private final Screen parent;
    private MacSpeechSession scan;
    private List<MicrophoneSettings.Device> devices = List.of(MicrophoneSettings.SYSTEM_DEFAULT);
    private Component status;
    private CycleButton<MicrophoneSettings.Device> picker;

    MicrophoneScreen(Screen parent) {
        super(label("settings"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        buildPicker();
        addRenderableWidget(Button.builder(label("refresh"), _ -> refresh())
            .bounds(width / 2 - 110, height / 2 + 22, 220, 20).build()).active = MacSpeechSession.supported();
        addRenderableWidget(Button.builder(CommonComponents.GUI_DONE, _ -> onClose())
            .bounds(width / 2 - 110, height / 2 + 52, 220, 20).build());
        if (scan == null && status == null) refresh();
    }

    private void buildPicker() {
        if (picker != null) removeWidget(picker);
        var current = MicrophoneSettings.selected();
        var choices = new ArrayList<>(devices);
        var selected = choices.stream().filter(device -> device.id().equals(current.id())).findFirst().orElse(current);
        if (!choices.contains(selected)) choices.add(selected);
        picker = addRenderableWidget(CycleButton.<MicrophoneSettings.Device>builder(
            device -> device.id().isEmpty() ? label("default") : Component.literal(device.name()), selected)
            .withValues(choices)
            .create(width / 2 - 110, height / 2 - 12, 220, 20, label("device"), (button, device) -> {
                try {
                    MicrophoneSettings.save(device);
                } catch (IOException error) {
                    button.setValue(MicrophoneSettings.selected());
                    status = Component.literal(error.getMessage());
                }
            }));
        picker.active = MacSpeechSession.supported() && scan == null;
    }

    private void refresh() {
        if (!MacSpeechSession.supported()) {
            status = label("unavailable");
            return;
        }
        closeScan();
        try {
            scan = new MacSpeechSession("list", "");
            picker.active = false;
            status = label("loading");
        } catch (IOException error) {
            status = Component.literal(error.getMessage());
        }
    }

    @Override
    public void tick() {
        if (scan == null) return;
        try {
            var result = scan.poll();
            if (result == null) return;
            if (result.error() != null) {
                status = Component.literal(result.error());
            } else {
                var data = new Gson().fromJson(result.text(), MicrophoneSettings.Device[].class);
                var choices = new ArrayList<MicrophoneSettings.Device>();
                choices.add(MicrophoneSettings.SYSTEM_DEFAULT);
                choices.addAll(List.of(data));
                devices = choices;
                status = label("device_help");
            }
        } catch (IOException | JsonParseException error) {
            status = Component.literal(error.getMessage());
        }
        closeScan();
        buildPicker();
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int x, int y, float delta) {
        super.extractRenderState(graphics, x, y, delta);
        graphics.centeredText(font, title, width / 2, height / 2 - 48, -1);
        if (status != null) graphics.centeredText(font, status, width / 2, height / 2 + 11, 0xffcccccc);
    }

    @Override
    public void onClose() {
        minecraft.gui.setScreen(parent);
    }

    @Override
    public void removed() {
        closeScan();
        super.removed();
    }

    private void closeScan() {
        if (scan == null) return;
        scan.close();
        scan = null;
    }

    private static Component label(String key) {
        return Component.translatable("gui.scholar_dictation." + key);
    }
}
