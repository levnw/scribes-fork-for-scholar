package dev.scholardictation;

import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;
import net.neoforged.neoforge.common.NeoForge;

@Mod(value = "scholar_dictation", dist = Dist.CLIENT)
public final class NeoForgeDictationClient {
    public NeoForgeDictationClient(ModContainer container) {
        container.registerExtensionPoint(IConfigScreenFactory.class,
            (mod, parent) -> new MicrophoneScreen(parent));
        NeoForge.EVENT_BUS.addListener(NeoForgeDictationClient::tick);
    }

    private static void tick(ClientTickEvent.Post event) {
        if (Minecraft.getInstance().gui.screen() instanceof DictationScreen screen) {
            screen.scholarDictation$tick();
        }
    }
}
