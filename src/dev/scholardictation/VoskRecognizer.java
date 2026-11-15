package dev.scholardictation;

import com.sun.jna.Library;
import com.sun.jna.Native;
import com.sun.jna.NativeLibrary;
import com.sun.jna.Platform;
import com.sun.jna.Pointer;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

final class VoskRecognizer implements AutoCloseable {
    public interface Api extends Library {
        Pointer vosk_model_new(String path);
        void vosk_model_free(Pointer model);
        Pointer vosk_recognizer_new(Pointer model, float rate);
        void vosk_recognizer_free(Pointer recognizer);
        int vosk_recognizer_accept_waveform(Pointer recognizer, byte[] audio, int bytes);
        String vosk_recognizer_partial_result(Pointer recognizer);
        String vosk_recognizer_result(Pointer recognizer);
        String vosk_recognizer_final_result(Pointer recognizer);
    }

    private static Api library;
    private static final List<NativeLibrary> WINDOWS_DEPENDENCIES = new ArrayList<>();
    private final Api api;
    private final Pointer model;
    private final Pointer recognizer;

    private static synchronized Api load() throws IOException {
        if (library != null) return library;
        String name = "vosk";
        if (Platform.isWindows()) {
            // Windows resolves dependent DLLs by filename. Load them from the same directory.
            Path folder = Files.createTempDirectory("scribes-fork-vosk-");
            folder.toFile().deleteOnExit();
            var names = List.of("libwinpthread-1.dll", "libgcc_s_seh-1.dll", "libstdc++-6.dll", "libvosk.dll");
            for (String dll : names) {
                Path destination = folder.resolve(dll);
                try (var resource = VoskRecognizer.class.getResourceAsStream("/win32-x86-64/" + dll)) {
                    if (resource == null) throw new IOException("The bundled speech library is missing: " + dll);
                    Files.copy(resource, destination);
                }
                destination.toFile().deleteOnExit();
                if (!dll.equals("libvosk.dll")) {
                    WINDOWS_DEPENDENCIES.add(NativeLibrary.getInstance(destination.toString()));
                }
            }
            name = folder.resolve("libvosk.dll").toString();
        }
        library = Native.load(name, Api.class, Map.of(Library.OPTION_STRING_ENCODING, "UTF-8"));
        return library;
    }

    VoskRecognizer(Path path, float rate) throws IOException {
        api = load();
        model = api.vosk_model_new(path.toString());
        if (model == null) throw new IOException("Could not load the bundled English speech model.");
        try {
            recognizer = api.vosk_recognizer_new(model, rate);
            if (recognizer == null) throw new IOException("Could not start the speech recognizer.");
        } catch (IOException | RuntimeException | LinkageError error) {
            api.vosk_model_free(model);
            throw error;
        }
    }

    boolean acceptWaveform(byte[] audio, int bytes) throws IOException {
        int result = api.vosk_recognizer_accept_waveform(recognizer, audio, bytes);
        if (result < 0) throw new IOException("The speech recognizer could not read the microphone audio.");
        return result == 1;
    }

    String partial() { return api.vosk_recognizer_partial_result(recognizer); }
    String segment() { return api.vosk_recognizer_result(recognizer); }
    String finish() { return api.vosk_recognizer_final_result(recognizer); }

    @Override
    public void close() {
        api.vosk_recognizer_free(recognizer);
        api.vosk_model_free(model);
    }
}
