import AVFoundation
import UIKit

/// AVFoundation 相机管理器:输出 RGBA 帧到分析回调。
/// 对应安卓 CameraX ImageAnalysis + OUTPUT_IMAGE_FORMAT_RGBA_8888。
final class CameraManager: NSObject, AVCaptureVideoDataOutputSampleBufferDelegate {
    private let session = AVCaptureSession()
    private let output = AVCaptureVideoDataOutput()
    private let queue = DispatchQueue(label: "camera.analysis", qos: .userInitiated)
    private var currentRGBA: [UInt8] = []
    private var frameWidth = 0
    private var frameHeight = 0
    private let lock = NSLock()

    var onFrame: (([UInt8], Int, Int) -> Void)?

    var isRunning: Bool { session.isRunning }

    func start(in layer: AVCaptureVideoPreviewLayer) {
        session.beginConfiguration()
        session.sessionPreset = .hd1920x1080
        guard let device = AVCaptureDevice.default(.builtInWideAngleCamera, for: .video, position: .back),
              let input = try? AVCaptureDeviceInput(device: device),
              session.canAddInput(input) else {
            session.commitConfiguration()
            return
        }
        session.addInput(input)
        output.videoSettings = [
            kCVPixelBufferPixelFormatTypeKey as String: kCVPixelFormatType_32BGRA,
        ]
        output.alwaysDiscardsLateVideoFrames = true
        output.setSampleBufferDelegate(self, queue: queue)
        guard session.canAddOutput(output) else {
            session.commitConfiguration()
            return
        }
        session.addOutput(output)
        session.commitConfiguration()

        layer.session = session
        layer.videoGravity = .resizeAspectFill
        DispatchQueue.global(qos: .userInitiated).async { [weak self] in
            self?.session.startRunning()
        }
    }

    func stop() {
        queue.async { [weak self] in
            self?.session.stopRunning()
        }
    }

    /// 最近一帧的 RGBA 数据(供截图和重放)。
    func latestFrame() -> ([UInt8], Int, Int)? {
        lock.lock()
        defer { lock.unlock() }
        guard !currentRGBA.isEmpty else { return nil }
        return (currentRGBA, frameWidth, frameHeight)
    }

    func captureOutput(_ output: AVCaptureOutput, didOutput sampleBuffer: CMSampleBuffer,
                       from connection: AVCaptureConnection) {
        guard let pixelBuffer = CMSampleBufferGetImageBuffer(sampleBuffer) else { return }
        let width = CVPixelBufferGetWidth(pixelBuffer)
        let height = CVPixelBufferGetHeight(pixelBuffer)
        CVPixelBufferLockBaseAddress(pixelBuffer, .readOnly)
        defer { CVPixelBufferUnlockBaseAddress(pixelBuffer, .readOnly) }
        guard let base = CVPixelBufferGetBaseAddress(pixelBuffer) else { return }
        let bytesPerRow = CVPixelBufferGetBytesPerRow(pixelBuffer)

        // BGRA → RGBA
        var rgba = [UInt8](repeating: 0, count: width * height * 4)
        let buffer = base.assumingMemoryBound(to: UInt8.self)
        for y in 0..<height {
            let srcBase = y * bytesPerRow
            let dstBase = y * width * 4
            for x in 0..<width {
                let s = srcBase + x * 4
                let d = dstBase + x * 4
                rgba[d] = buffer[s + 2]     // R ← B
                rgba[d + 1] = buffer[s + 1] // G
                rgba[d + 2] = buffer[s]     // B ← R
                rgba[d + 3] = 255
            }
        }

        lock.lock()
        currentRGBA = rgba
        frameWidth = width
        frameHeight = height
        lock.unlock()

        onFrame?(rgba, width, height)
    }
}
