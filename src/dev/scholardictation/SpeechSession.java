package dev.scholardictation;

import java.io.IOException;

public interface SpeechSession extends AutoCloseable {
    enum State { STARTING, LISTENING, FINISHING }
    record Result(String text, String error) {}

    static boolean supported() {
        return MacSpeechSession.supported() || PortableSpeechSession.supported();
    }

    static SpeechSession open(String mode, String device) throws IOException {
        if (MacSpeechSession.supported()) return new MacSpeechSession(mode, device);
        if (PortableSpeechSession.supported()) return new PortableSpeechSession(mode, device);
        throw new IOException("Dictation is unavailable on this operating system and architecture.");
    }

    State state();
    void stop() throws IOException;
    String preview() throws IOException;
    Result poll() throws IOException;
    @Override void close();
}
