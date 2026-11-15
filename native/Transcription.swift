import AVFoundation
import Speech
import CoreMedia

enum Transcription {
    static func recognize(
        _ buffers: AsyncStream<AVAudioPCMBuffer>,
        locale: Locale,
        update: @escaping @Sendable (String) -> Void
    ) async throws -> String {
        guard let supported = await SpeechTranscriber.supportedLocale(equivalentTo: locale) else {
            throw failure("On-device transcription is unavailable for \(locale.identifier).")
        }
        let transcriber = SpeechTranscriber(locale: supported, preset: .progressiveTranscription)
        let modules = [transcriber]
        if let request = try await AssetInventory.assetInstallationRequest(supporting: modules) {
            try await request.downloadAndInstall()
        }
        guard let outputFormat = await SpeechAnalyzer.bestAvailableAudioFormat(compatibleWith: modules) else {
            throw failure("Could not prepare microphone audio for speech recognition.")
        }
        let analyzer = SpeechAnalyzer(modules: modules)
        try await analyzer.prepareToAnalyze(in: outputFormat)
        async let text: String = collect(transcriber, update: update)
        do {
            let input = AsyncThrowingStream<AnalyzerInput, Error> { continuation in
                let producer = Task {
                    do {
                        var converter: AVAudioConverter?
                        for await buffer in buffers where buffer.frameLength > 0 {
                            if buffer.format == outputFormat {
                                continuation.yield(AnalyzerInput(buffer: buffer))
                            } else {
                                if converter?.inputFormat != buffer.format {
                                    converter = AVAudioConverter(from: buffer.format, to: outputFormat)
                                }
                                guard let converter else { throw failure("The microphone audio format is unsupported.") }
                                let output = try convert(buffer, with: converter)
                                if output.frameLength > 0 { continuation.yield(AnalyzerInput(buffer: output)) }
                            }
                        }
                        continuation.finish()
                    } catch { continuation.finish(throwing: error) }
                }
                continuation.onTermination = { _ in producer.cancel() }
            }
            if let end = try await analyzer.analyzeSequence(input) {
                try await analyzer.finalizeAndFinish(through: end)
            } else {
                await analyzer.cancelAndFinishNow()
            }
            return try await text
        } catch {
            await analyzer.cancelAndFinishNow()
            throw error
        }
    }

    private static func collect(_ transcriber: SpeechTranscriber, update: @Sendable (String) -> Void) async throws -> String {
        var committed = [String]()
        var lastFinalEnd = CMTime.invalid
        for try await result in transcriber.results {
            let text = String(result.text.characters).trimmingCharacters(in: .whitespacesAndNewlines)
            if result.isFinal {
                if !text.isEmpty { committed.append(text) }
                lastFinalEnd = result.range.end
                update(committed.joined(separator: " "))
            } else if !lastFinalEnd.isValid || result.range.start >= lastFinalEnd {
                update((committed + (text.isEmpty ? [] : [text])).joined(separator: " "))
            }
        }
        return committed.joined(separator: " ")
    }

    private static func convert(_ buffer: AVAudioPCMBuffer, with converter: AVAudioConverter) throws -> AVAudioPCMBuffer {
        let target = converter.outputFormat
        let ratio = target.sampleRate / buffer.format.sampleRate
        let capacity = AVAudioFrameCount(ceil(Double(buffer.frameLength) * ratio)) + 256
        guard let output = AVAudioPCMBuffer(pcmFormat: target, frameCapacity: capacity) else {
            throw failure("Could not allocate a speech audio buffer.")
        }
        var supplied = false
        var error: NSError?
        let status = converter.convert(to: output, error: &error) { _, inputStatus in
            if supplied {
                inputStatus.pointee = .noDataNow
                return nil
            }
            supplied = true
            inputStatus.pointee = .haveData
            return buffer
        }
        if status == .error { throw error ?? failure("Could not convert microphone audio.") }
        return output
    }

    static func failure(_ message: String) -> NSError {
        NSError(domain: "ScholarDictation", code: 1, userInfo: [NSLocalizedDescriptionKey: message])
    }
}
