import SwiftUI

@main
struct CubeARApp: App {
    var body: some Scene {
        WindowGroup {
            CubeARWrapperView()
                .ignoresSafeArea()
                .statusBarHidden()
                .preferredColorScheme(.dark)
        }
    }
}

/// UIKit 包装,承载 CameraARViewController。
struct CubeARWrapperView: UIViewControllerRepresentable {
    func makeUIViewController(context: Context) -> CubeARViewController {
        CubeARViewController()
    }

    func updateUIViewController(_ uiViewController: CubeARViewController, context: Context) {}
}
