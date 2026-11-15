package dev.scholardictation;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;

public final class DictationClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (client.gui.screen() instanceof DictationScreen screen) {
                screen.scholarDictation$tick();
            }
        });
    }
}
