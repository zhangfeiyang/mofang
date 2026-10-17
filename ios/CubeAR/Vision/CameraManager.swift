import AVFoundation
import Accelerate
import UIKit

/// AVFoundation 相机:预览 + 分析帧。对应安卓 CameraX Preview + ImageAnalysis。
///
/// 分析帧与安卓一致:竖屏 720×1280、R,G,B,A 字节序——检测网络按这个画面比例训练,
/// 横屏帧压进竖屏输入会让检测几乎全废。帧在转换像素之前先问 wantsFrame:被节流跳过
/// 的帧不做任何拷贝。
final class CameraManager: NSObject, AVCaptureVideoDataOutputSampleBufferDelegate {
    let session = AVCaptureSession()
    /// 分析线程:帧回调、模型加载与 CubeAnalyzer 都只在这里运行。
    let analysisQueue = DispatchQueue(label: "cubear.analysis", qos: .userInitiated)
    private let sessionQueue = DispatchQueue(label: "cubear.camera")
    private let output = AVCaptureVideoDataOutput()
    private var device: AVCaptureDevice?
    private var configured = false
    private var rgba: [UInt8] = []
    /// 测光点的代数:新的测光请求让旧的"恢复中心测光"作废。
    private var meteringGeneration = 0

    /// 分析线程:这一刻要不要分析一帧(参数为单调时钟纳秒)。
    var wantsFrame: ((Double) -> Bool)?
    /// 分析线程:一帧打包 RGBA(行优先,无填充)、宽、高、单调时钟纳秒。
    var onFrame: (([UInt8], Int, Int, Double) -> Void)?

    /// 配置完成后才有意义。
    private(set) var hasTorch = false
    private(set) var torchOn = false

    /// 配置(只做一次)并启动相机;完成后在主线程回调是否成功。调用前须已获相机权限。
    func start(_ completion: @escaping (Bool) -> Void) {
        sessionQueue.async { [weak self] in
            guard let self = self else { return }
            let ready = self.configured || self.configure()
            if ready && !self.session.isRunning { self.session.startRunning() }
            let torch = self.device?.hasTorch ?? false
            DispatchQueue.main.async {
                self.hasTorch = torch
                completion(ready)
            }
        }
    }

    func stop() {
        sessionQueue.async { [weak self] in
            guard let self = self, self.session.isRunning else { return }
            self.session.stopRunning()
        }
        torchOn = false
    }

    private func configure() -> Bool {
        session.beginConfiguration()
        defer { session.commitConfiguration() }
        // 720p 足够读贴纸,也正是安卓的分析分辨率;更高只会拖慢每一帧。
        if session.canSetSessionPreset(.hd1280x720) { session.sessionPreset = .hd1280x720 }
        guard let device = AVCaptureDevice.default(.builtInWideAngleCamera, for: .video, position: .back),
              let input = try? AVCaptureDeviceInput(device: device),
              session.canAddInput(input) else { return false }
        session.addInput(input)
        output.videoSettings = [kCVPixelBufferPixelFormatTypeKey as String: kCVPixelFormatType_32BGRA]
        output.alwaysDiscardsLateVideoFrames = true
        output.setSampleBufferDelegate(self, queue: analysisQueue)
        guard session.canAddOutput(output) else { return false }
        session.addOutput(output)
        if let connection = output.connection(with: .video) {
            if #available(iOS 17.0, *) {
                if connection.isVideoRotationAngleSupported(90) { connection.videoRotationAngle = 90 }
            } else if connection.isVideoOrientationSupported {
                connection.videoOrientation = .portrait
            }
        }
        if (try? device.lockForConfiguration()) != nil {
            if device.isFocusModeSupported(.continuousAutoFocus) { device.focusMode = .continuousAutoFocus }
            if device.isExposureModeSupported(.continuousAutoExposure) { device.exposureMode = .continuousAutoExposure }
            if device.isLowLightBoostSupported { device.automaticallyEnablesLowLightBoostWhenAvailable = true }
            // 魔方举在 20–30 厘米:限制在近处对焦,对焦更快,也不会被远处背景拉走。
            if device.isAutoFocusRangeRestrictionSupported { device.autoFocusRangeRestriction = .near }
            device.unlockForConfiguration()
        }
        self.device = device
        configured = true
        return true
    }

    func setTorch(_ on: Bool) {
        torchOn = on && hasTorch
        let wanted = torchOn
        sessionQueue.async { [weak self] in
            guard let device = self?.device, device.hasTorch,
                  (try? device.lockForConfiguration()) != nil else { return }
            if wanted {
                try? device.setTorchModeOn(level: AVCaptureDevice.maxAvailableTorchLevel)
            } else {
                device.torchMode = .off
            }
            device.unlockForConfiguration()
        }
    }

    /// 把对焦与测光对准魔方(设备坐标 0..1)。
    ///
    /// 连续对焦偏向画面里占主导的东西——魔方举得近时常是它身后的背景——中心加权测光
    /// 又会让明亮的桌面压暗贴纸。四秒没有新请求就回到画面中心,与安卓的自动取消一致。
    func focus(at point: CGPoint) {
        meteringGeneration += 1
        let generation = meteringGeneration
        sessionQueue.async { [weak self] in
            self?.applyPointOfInterest(point)
        }
        sessionQueue.asyncAfter(deadline: .now() + 4) { [weak self] in
            DispatchQueue.main.async {
                guard let self = self, self.meteringGeneration == generation else { return }
                self.sessionQueue.async { self.applyPointOfInterest(CGPoint(x: 0.5, y: 0.5)) }
            }
        }
    }

    private func applyPointOfInterest(_ point: CGPoint) {
        guard let device = device, (try? device.lockForConfiguration()) != nil else { return }
        if device.isFocusPointOfInterestSupported && device.isFocusModeSupported(.continuousAutoFocus) {
            device.focusPointOfInterest = point
            device.focusMode = .continuousAutoFocus
        }
        if device.isExposurePointOfInterestSupported && device.isExposureModeSupported(.continuousAutoExposure) {
            device.exposurePointOfInterest = point
            device.exposureMode = .continuousAutoExposure
        }
        device.unlockForConfiguration()
    }

    // MARK: - 分析帧

    func captureOutput(_ output: AVCaptureOutput, didOutput sampleBuffer: CMSampleBuffer,
                       from connection: AVCaptureConnection) {
        let nanos = Double(DispatchTime.now().uptimeNanoseconds)
        if let wants = wantsFrame, !wants(nanos) { return }
        guard let pixelBuffer = CMSampleBufferGetImageBuffer(sampleBuffer) else { return }
        CVPixelBufferLockBaseAddress(pixelBuffer, .readOnly)
        defer { CVPixelBufferUnlockBaseAddress(pixelBuffer, .readOnly) }
        guard let base = CVPixelBufferGetBaseAddress(pixelBuffer) else { return }
        let width = CVPixelBufferGetWidth(pixelBuffer)
        let height = CVPixelBufferGetHeight(pixelBuffer)
        let needed = width * height * 4
        if rgba.count != needed { rgba = [UInt8](repeating: 0, count: needed) }

        // BGRA → RGBA,顺带去掉行尾填充。
        var source = vImage_Buffer(data: base, height: vImagePixelCount(height),
                                   width: vImagePixelCount(width),
                                   rowBytes: CVPixelBufferGetBytesPerRow(pixelBuffer))
        let map: [UInt8] = [2, 1, 0, 3]
        let error = rgba.withUnsafeMutableBytes { bytes -> vImage_Error in
            var target = vImage_Buffer(data: bytes.baseAddress, height: vImagePixelCount(height),
                                       width: vImagePixelCount(width), rowBytes: width * 4)
            return vImagePermuteChannels_ARGB8888(&source, &target, map, vImage_Flags(kvImageNoFlags))
        }
        guard error == kvImageNoError else { return }
        onFrame?(rgba, width, height, nanos)
    }
}
