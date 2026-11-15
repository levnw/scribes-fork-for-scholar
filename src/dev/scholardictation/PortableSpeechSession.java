package dev.scholardictation;

import com.google.gson.Gson;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.LineUnavailableException;
import javax.sound.sampled.Mixer;
import javax.sound.sampled.TargetDataLine;

final class PortableSpeechSession implements SpeechSession {
    private static final List<Float> SAMPLE_RATES = List.of(16000f, 48000f, 44100f);
    private volatile State state = State.STARTING;
    private volatile String preview = "";
    private volatile Result result;
    private volatile boolean cancelled;
    private TargetDataLine microphone;

    static boolean supported() {
        String os = System.getProperty("os.name").toLowerCase(Locale.ROOT);
        String arch = System.getProperty("os.arch");
        boolean x64 = arch.equals("amd64") || arch.equals("x86_64");
        boolean arm64 = arch.equals("aarch64") || arch.equals("arm64");
        if (os.startsWith("windows")) return x64;
        return (os.contains("mac") || os.contains("linux")) && (x64 || arm64);
    }

    PortableSpeechSession(String mode, String device) {
        Thread.ofVirtual().name("scribe-speech").start(() -> {
            try {
                if (mode.equals("list")) {
                    result = new Result(new Gson().toJson(devices()), null);
                } else {
                    record(device);
                }
            } catch (Exception | LinkageError error) {
                if (!cancelled) result = new Result(null, error.getMessage() == null
                    ? error.getClass().getSimpleName() : error.getMessage());
            }
        });
    }

    private static List<MicrophoneSettings.Device> devices() {
        var devices = new ArrayList<MicrophoneSettings.Device>();
        for (Mixer.Info info : AudioSystem.getMixerInfo()) {
            var mixer = AudioSystem.getMixer(info);
            if (SAMPLE_RATES.stream().anyMatch(rate -> mixer.isLineSupported(captureInfo(rate)))) {
                devices.add(new MicrophoneSettings.Device(deviceId(info), info.getName()));
            }
        }
        return devices;
    }

    private static String deviceId(Mixer.Info info) {
        return "java:" + info.getName() + "|" + info.getVendor() + "|" + info.getDescription();
    }

    private static DataLine.Info captureInfo(float rate) {
        return new DataLine.Info(TargetDataLine.class, format(rate));
    }

    private static AudioFormat format(float rate) {
        return new AudioFormat(rate, 16, 1, true, false);
    }

    private static TargetDataLine openLine(String device) throws IOException {
        Mixer.Info selected = null;
        if (device.startsWith("java:")) {
            for (Mixer.Info info : AudioSystem.getMixerInfo()) {
                if (deviceId(info).equals(device)) {
                    selected = info;
                    break;
                }
            }
            if (selected == null) {
                throw new IOException("The selected microphone is unavailable. Choose another in Mod Menu.");
            }
        }

        Exception lastError = null;
        for (float rate : SAMPLE_RATES) {
            AudioFormat format = format(rate);
            try {
                var line = AudioSystem.getTargetDataLine(format, selected);
                try {
                    line.open(format);
                    return line;
                } catch (LineUnavailableException | IllegalArgumentException error) {
                    line.close();
                    throw error;
                }
            } catch (LineUnavailableException | IllegalArgumentException error) {
                lastError = error;
            }
        }
        throw new IOException("Could not open the microphone. Check system microphone permissions and input settings.",
            lastError);
    }

    private void record(String device) throws IOException {
        var model = PortableSpeechModel.install();
        if (cancelled) return;
        try (var line = openLine(device);
             var recognizer = new VoskRecognizer(model, line.getFormat().getSampleRate())) {
            synchronized (this) {
                if (cancelled) return;
                line.start();
                microphone = line;
                state = State.LISTENING;
            }
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(45);
            var buffer = new byte[(int) line.getFormat().getSampleRate() / 5];
            var completed = new StringBuilder();
            while (state == State.LISTENING && !cancelled && System.nanoTime() < deadline) {
                int count = line.read(buffer, 0, buffer.length);
                if (count <= 0) break;
                if (recognizer.acceptWaveform(buffer, count)) {
                    append(completed, value(recognizer.segment(), "text"));
                    preview = completed.toString();
                } else {
                    String partial = value(recognizer.partial(), "partial");
                    preview = completed.isEmpty() || partial.isBlank()
                        ? completed + partial : completed + " " + partial;
                }
            }
            if (!cancelled) {
                state = State.FINISHING;
                append(completed, value(recognizer.finish(), "text"));
                result = new Result(completed.toString(), null);
            }
        } finally {
            synchronized (this) {
                microphone = null;
            }
        }
    }

    private static String value(String json, String key) {
        return JsonParser.parseString(json).getAsJsonObject().get(key).getAsString().strip();
    }

    private static void append(StringBuilder text, String segment) {
        if (segment.isBlank()) return;
        if (!text.isEmpty()) text.append(' ');
        text.append(segment);
    }

    @Override public State state() { return state; }
    @Override public String preview() { return preview; }
    @Override public Result poll() { return result; }

    @Override
    public synchronized void stop() {
        state = State.FINISHING;
        if (microphone != null) microphone.stop();
    }

    @Override
    public synchronized void close() {
        cancelled = true;
        if (microphone != null) microphone.close();
    }
}
