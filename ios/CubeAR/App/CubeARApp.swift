import SwiftUI

@main
struct CubeARApp: App {
    var body: some Scene {
        WindowGroup {
            CubeARWrapperView()
                .ignoresSafeArea()
                .preferredColorScheme(.dark)
        }
    }
}

/// UIKit 包装,承载 CubeARViewController。
struct CubeARWrapperView: UIViewControllerRepresentable {
    func makeUIViewController(context: Context) -> CubeARViewController {
        CubeARViewController()
    }

    func updateUIViewController(_ uiViewController: CubeARViewController, context: Context) {}
}
