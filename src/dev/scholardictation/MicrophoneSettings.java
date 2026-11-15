package dev.scholardictation;

import com.google.gson.Gson;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import net.fabricmc.loader.api.FabricLoader;

public final class MicrophoneSettings {
    static final Device SYSTEM_DEFAULT = new Device("", "");
    private static final Gson JSON = new Gson();
    public record Device(String id, String name) {}

    private static Path path() {
        return FabricLoader.getInstance().getConfigDir().resolve("scholar-dictation.json");
    }

    public static Device selected() {
        try {
            Device value = JSON.fromJson(Files.readString(path()), Device.class);
            if (value != null && value.id() != null && value.name() != null) return value;
        } catch (IOException | com.google.gson.JsonParseException ignored) {
            // A new profile or unreadable preference uses the system microphone.
        }
        return SYSTEM_DEFAULT;
    }

    static void save(Device device) throws IOException {
        Path path = path();
        Files.createDirectories(path.getParent());
        Path temporary = Files.createTempFile(path.getParent(), "scholar-mic-", ".json");
        try {
            Files.writeString(temporary, JSON.toJson(device));
            Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }
}
