package dev.scholardictation;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Locale;
import net.fabricmc.loader.api.FabricLoader;

public final class MacSpeechSession implements AutoCloseable {
    private static final String APP = "Scholar Dictation.app";
    private static final String BUILD = "0.1.3";
    private final Path directory;
    private final Process launcher;
    private final long started = System.nanoTime();
    private long stopped;

    public enum State { STARTING, LISTENING, FINISHING }
    public record Result(String text, String error) {}

    public static boolean supported() {
        return System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("mac")
            && System.getProperty("os.arch").equals("aarch64")
            && Runtime.version().feature() >= 25
            && Integer.parseInt(System.getProperty("os.version").split("\\.")[0]) >= 26;
    }

    public MacSpeechSession(String mode, String device) throws IOException {
        Path app = installHelper();
        directory = Files.createTempDirectory("scholar-dictation-");
        try {
            launcher = new ProcessBuilder("/usr/bin/open", "-n", "-g", "-W", "-a", app.toString(),
                "--args", "--scholar-session", directory.toString(), Long.toString(ProcessHandle.current().pid()),
                mode, device)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start();
        } catch (IOException failure) {
            close();
            throw failure;
        }
    }

    private static Path installHelper() throws IOException {
        Path root = FabricLoader.getInstance().getConfigDir().resolve("scholar-dictation").resolve(BUILD);
        Path app = root.resolve(APP);
        String executable = "Contents/MacOS/ScholarDictation";
        for (String file : new String[]{"Contents/Info.plist", "Contents/_CodeSignature/CodeResources", executable}) {
            Path target = app.resolve(file);
            Files.createDirectories(target.getParent());
            try (InputStream bundled = MacSpeechSession.class.getResourceAsStream("/native/helper/" + file)) {
                if (bundled == null) {
                    throw new IOException("The bundled macOS helper is missing.");
                }
                byte[] bytes = bundled.readAllBytes();
                if (!Files.exists(target) || !java.util.Arrays.equals(bytes, Files.readAllBytes(target))) {
                    Path temp = Files.createTempFile(target.getParent(), "helper-", ".tmp");
                    try {
                        Files.write(temp, bytes);
                        Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                    } finally {
                        Files.deleteIfExists(temp);
                    }
                }
            }
        }
        if (!app.resolve(executable).toFile().setExecutable(true, true)) {
            throw new IOException("Could not make the macOS helper executable.");
        }
        return app;
    }

    public State state() {
        if (stopped != 0) return State.FINISHING;
        return Files.exists(directory.resolve("ready")) ? State.LISTENING : State.STARTING;
    }

    public void stop() throws IOException {
        Files.createFile(directory.resolve("stop"));
        stopped = System.nanoTime();
    }

    public String preview() throws IOException {
        Path partial = directory.resolve("partial");
        return Files.exists(partial) ? Files.readString(partial) : "";
    }

    public Result poll() throws IOException {
        boolean exited = !launcher.isAlive();
        Path error = directory.resolve("error");
        Path transcript = directory.resolve("transcript");
        if (Files.exists(error)) return new Result(null, Files.readString(error));
        if (Files.exists(transcript)) return new Result(Files.readString(transcript), null);
        if (exited) {
            return new Result(null, "The macOS speech helper closed before returning a result.");
        }
        long now = System.nanoTime();
        if (now - started > 120_000_000_000L || (stopped != 0 && now - stopped > 55_000_000_000L)) {
            return new Result(null, "The speech helper timed out. Check macOS permissions and try again.");
        }
        return null;
    }

    @Override
    public void close() {
        try {
            // The helper watches the session directory and exits when it disappears.
            try (var files = Files.newDirectoryStream(directory)) {
                for (Path file : files) Files.deleteIfExists(file);
            }
            Files.deleteIfExists(directory);
        } catch (IOException ignored) {
            // A concurrent result write may race cleanup. Let the helper observe cancellation.
            try {
                Files.writeString(directory.resolve("cancel"), "");
            } catch (IOException ignoredAgain) {
                // The directory may already be gone.
            }
        }
    }
}
