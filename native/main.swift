import AppKit
import AVFoundation
import os

final class Dictation: NSObject, NSApplicationDelegate {
    struct Device: Encodable { let id: String; let name: String }
    let directory: URL
    let parent: pid_t
    let mode: String
    let selectedDevice: String
    let capture = AVCaptureSession()
    let captureQueue = DispatchQueue(label: "dev.scholardictation.capture")
    let audioSink = AudioSink()
    let logger = Logger(subsystem: "dev.scholardictation.helper", category: "Session")
    var timer: Timer?
    var listeningSince: Date?
    var processing = false
    var ended = false

    init(directory: URL, parent: pid_t, mode: String, selectedDevice: String) {
        self.directory = directory
        self.parent = parent
        self.mode = mode
        self.selectedDevice = selectedDevice
    }

    func applicationDidFinishLaunching(_ notification: Notification) {
        guard !cancelled else { finish(); return }
        if mode == "list" {
            do {
                let data = try JSONEncoder().encode(microphones().map { Device(id: $0.uniqueID, name: $0.localizedName) })
                finish(text: String(decoding: data, as: UTF8.self))
            } catch { finish(error: error.localizedDescription) }
            return
        }
        timer = Timer.scheduledTimer(withTimeInterval: 0.15, repeats: true) { [weak self] _ in self?.checkSession() }
        if AVCaptureDevice.authorizationStatus(for: .audio) == .notDetermined { NSApplication.shared.activate() }
        AVCaptureDevice.requestAccess(for: .audio) { allowed in
            DispatchQueue.main.async {
                guard !self.ended, !self.cancelled else { self.finish(); return }
                if allowed {
                    self.start()
                } else {
                    self.finish(error: "Allow Scholar Dictation under System Settings → Privacy & Security → Microphone.")
                }
            }
        }
    }

    var cancelled: Bool {
        let files = FileManager.default
        return kill(parent, 0) != 0 ||
            !files.fileExists(atPath: directory.path) ||
            files.fileExists(atPath: directory.appendingPathComponent("cancel").path)
    }

    func microphones() -> [AVCaptureDevice] {
        AVCaptureDevice.DiscoverySession(deviceTypes: [.microphone], mediaType: .audio, position: .unspecified).devices
    }

    func start() {
        let device = selectedDevice.isEmpty ? AVCaptureDevice.default(for: .audio) : microphones().first { $0.uniqueID == selectedDevice }
        guard let device else {
            finish(error: "That microphone is unavailable. Choose System Default or reconnect it.")
            return
        }
        do {
            let input = try AVCaptureDeviceInput(device: device)
            let output = AVCaptureAudioDataOutput()
            output.setSampleBufferDelegate(audioSink, queue: captureQueue)
            guard capture.canAddInput(input), capture.canAddOutput(output) else {
                finish(error: "Could not open the selected microphone.")
                return
            }
            capture.addInput(input)
            capture.addOutput(output)
            capture.startRunning()
            guard capture.isRunning else { finish(error: "The microphone did not start."); return }
            try Data().write(to: directory.appendingPathComponent("ready"), options: .atomic)
            listeningSince = Date()
            logger.info("Microphone is running")
            let preview = directory.appendingPathComponent("partial")
            Task { @MainActor in
                do {
                    let locale = Locale(identifier: Locale.preferredLanguages.first ?? "en-US")
                    let text = try await Transcription.recognize(audioSink.stream, locale: locale) { text in
                        try? Data(text.utf8).write(to: preview, options: .atomic)
                    }
                    self.logger.info("Transcription completed with \(text.count, privacy: .public) characters")
                    self.finish(text: text)
                } catch {
                    self.finish(error: error.localizedDescription)
                }
            }
        } catch {
            finish(error: error.localizedDescription)
        }
    }

    func checkSession() {
        if cancelled { finish(); return }
        guard !processing, let listeningSince else { return }
        if Date().timeIntervalSince(listeningSince) >= 45 ||
            FileManager.default.fileExists(atPath: directory.appendingPathComponent("stop").path) {
            processing = true
            capture.stopRunning()
            captureQueue.sync { audioSink.continuation.finish() }
        }
    }

    func finish(text: String? = nil, error: String? = nil) {
        guard !ended else { return }
        ended = true
        timer?.invalidate()
        if capture.isRunning { capture.stopRunning() }
        audioSink.continuation.finish()
        if !cancelled {
            if let text { write(text, to: "transcript") }
            if let error {
                logger.error("Dictation failed: \(error, privacy: .public)")
                write(error, to: "error")
            }
        }
        NSApplication.shared.terminate(nil)
    }

    func write(_ value: String, to name: String) {
        do { try Data(value.utf8).write(to: directory.appendingPathComponent(name), options: .atomic) }
        catch { logger.error("Could not write session result: \(error.localizedDescription, privacy: .public)") }
    }
}

final class AudioSink: NSObject, AVCaptureAudioDataOutputSampleBufferDelegate {
    let (stream, continuation) = AsyncStream<AVAudioPCMBuffer>.makeStream()

    func captureOutput(_ output: AVCaptureOutput, didOutput sample: CMSampleBuffer, from connection: AVCaptureConnection) {
        guard let description = CMSampleBufferGetFormatDescription(sample),
              let asbd = CMAudioFormatDescriptionGetStreamBasicDescription(description),
              let format = AVAudioFormat(streamDescription: asbd) else { return }
        let count = CMSampleBufferGetNumSamples(sample)
        guard count > 0, let buffer = AVAudioPCMBuffer(pcmFormat: format, frameCapacity: UInt32(count)) else { return }
        buffer.frameLength = UInt32(count)
        guard CMSampleBufferCopyPCMDataIntoAudioBufferList(sample, at: 0, frameCount: Int32(count), into: buffer.mutableAudioBufferList) == noErr else { return }
        continuation.yield(buffer)
    }
}

let args = Array(CommandLine.arguments.dropFirst())
guard let flag = args.firstIndex(of: "--scholar-session"), args.count > flag + 4,
      let parent = pid_t(args[flag + 2]), parent > 1 else { exit(2) }
let delegate = Dictation(directory: URL(fileURLWithPath: args[flag + 1]), parent: parent, mode: args[flag + 3], selectedDevice: args[flag + 4])
let app = NSApplication.shared
app.delegate = delegate
app.setActivationPolicy(.accessory)
app.run()
