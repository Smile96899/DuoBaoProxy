import SwiftUI
import AVFoundation
import PhotosUI

/// 扫一扫: camera QR scanning (AVFoundation) plus 相册 (decoded in Go, like Android).
struct QRScanView: View {
    @Environment(\.dismiss) private var dismiss
    @State private var pick: PhotosPickerItem?
    @State private var error: String?
    let onResult: (String) -> Void

    var body: some View {
        NavigationStack {
            ZStack(alignment: .bottom) {
                CameraScanner(onCode: onResult).ignoresSafeArea()
                RoundedRectangle(cornerRadius: 20).stroke(.white.opacity(0.9), lineWidth: 2)
                    .frame(width: 240, height: 240)
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
                if let error {
                    Text(error).font(.system(size: 14)).foregroundStyle(.white)
                        .padding(12).background(.black.opacity(0.6), in: RoundedRectangle(cornerRadius: 10))
                        .padding(.bottom, 40)
                }
            }
            .background(.black)
            .navigationTitle("扫一扫")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("关闭") { dismiss() } }
                ToolbarItem(placement: .primaryAction) {
                    PhotosPicker(selection: $pick, matching: .images) { Text("相册") }
                }
            }
            .onChange(of: pick) { item in
                guard let item else { return }
                Task {
                    guard let data = try? await item.loadTransferable(type: Data.self) else { return }
                    let r = await Task.detached { () -> Result<String, Error> in Result { try Core.decodeQR(data) } }.value
                    switch r {
                    case .success(let text): onResult(text)
                    case .failure(let e): error = e.localizedDescription
                    }
                }
            }
        }
    }
}

private struct CameraScanner: UIViewControllerRepresentable {
    let onCode: (String) -> Void

    func makeUIViewController(context: Context) -> ScannerController {
        let vc = ScannerController()
        vc.onCode = onCode
        return vc
    }

    func updateUIViewController(_ vc: ScannerController, context: Context) {}
}

final class ScannerController: UIViewController, AVCaptureMetadataOutputObjectsDelegate {
    var onCode: ((String) -> Void)?
    private let session = AVCaptureSession()
    private var done = false

    override func viewDidLoad() {
        super.viewDidLoad()
        view.backgroundColor = .black
        guard let device = AVCaptureDevice.default(for: .video),
              let input = try? AVCaptureDeviceInput(device: device), session.canAddInput(input) else { return }
        session.addInput(input)
        let output = AVCaptureMetadataOutput()
        guard session.canAddOutput(output) else { return }
        session.addOutput(output)
        output.setMetadataObjectsDelegate(self, queue: .main)
        output.metadataObjectTypes = [.qr]
        let layer = AVCaptureVideoPreviewLayer(session: session)
        layer.videoGravity = .resizeAspectFill
        layer.frame = view.bounds
        view.layer.addSublayer(layer)
        DispatchQueue.global(qos: .userInitiated).async { self.session.startRunning() }
    }

    override func viewDidLayoutSubviews() {
        super.viewDidLayoutSubviews()
        view.layer.sublayers?.first?.frame = view.bounds
    }

    override func viewWillDisappear(_ animated: Bool) {
        super.viewWillDisappear(animated)
        if session.isRunning { session.stopRunning() }
    }

    func metadataOutput(_ output: AVCaptureMetadataOutput, didOutput objects: [AVMetadataObject], from connection: AVCaptureConnection) {
        guard !done, let code = objects.compactMap({ ($0 as? AVMetadataMachineReadableCodeObject)?.stringValue }).first else { return }
        done = true
        UINotificationFeedbackGenerator().notificationOccurred(.success)
        onCode?(code)
    }
}
