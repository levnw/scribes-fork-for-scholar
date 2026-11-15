package dev.scholardictation;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.zip.ZipInputStream;
import net.minecraft.client.Minecraft;

final class PortableSpeechModel {
    private static final String NAME = "vosk-model-small-en-us-0.15";

    private PortableSpeechModel() {}

    static synchronized Path install() throws IOException {
        Path cache = Minecraft.getInstance().gameDirectory.toPath().resolve("config").resolve("scholar-dictation");
        Path model = cache.resolve(NAME);
        if (Files.isDirectory(model)) return model;

        Files.createDirectories(cache);
        Path temporary = Files.createTempDirectory(cache, "speech-model-");
        try {
            try (var resource = PortableSpeechModel.class.getResourceAsStream("/speech/" + NAME + ".zip")) {
                if (resource == null) throw new IOException("The bundled English speech model is missing.");
                try (var zip = new ZipInputStream(resource)) {
                    for (var entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
                        Path output = temporary.resolve(entry.getName()).normalize();
                        if (!output.startsWith(temporary.resolve(NAME))) {
                            throw new IOException("Unexpected entry in the speech model archive.");
                        }
                        if (entry.isDirectory()) {
                            Files.createDirectories(output);
                        } else {
                            Files.createDirectories(output.getParent());
                            Files.copy(zip, output);
                        }
                    }
                }
            }
            try {
                Files.move(temporary.resolve(NAME), model);
            } catch (IOException error) {
                if (!Files.isDirectory(model)) throw error;
            }
            return model;
        } finally {
            try (var paths = Files.walk(temporary)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
            }
        }
    }
}
