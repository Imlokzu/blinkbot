import SwiftUI
import UIKit
import ClaudeBot

@main
struct ClaudeBotApp: App {
    @StateObject private var host = AppHost()

    var body: some Scene {
        WindowGroup {
            ComposeHost(host: host)
                .ignoresSafeArea(.container, edges: .all)
                .onOpenURL { url in host.bridge.deliverIncomingPairing(value: url.absoluteString) }
        }
    }
}

final class AppHost: ObservableObject {
    let native: NativeBridge
    let bridge: IosBridge
    let controller: UIViewController

    init() {
        let native = NativeBridge()
        let bridge = IosBridge(delegate: native)
        self.native = native
        self.bridge = bridge
        controller = PlatformHostController(content: MainViewControllerKt.MainViewController(bridge: bridge),
                                            native: native)
        native.attach(controller: controller, bridge: bridge)
    }
}

private final class PlatformHostController: UIViewController {
    private let content: UIViewController
    private let native: NativeBridge

    init(content: UIViewController, native: NativeBridge) {
        self.content = content
        self.native = native
        super.init(nibName: nil, bundle: nil)
    }

    required init?(coder: NSCoder) { return nil }

    func detach() { native.detach() }

    override func viewDidLoad() {
        super.viewDidLoad()
        addChild(content)
        content.view.translatesAutoresizingMaskIntoConstraints = false
        view.addSubview(content.view)
        NSLayoutConstraint.activate([
            content.view.leadingAnchor.constraint(equalTo: view.leadingAnchor),
            content.view.trailingAnchor.constraint(equalTo: view.trailingAnchor),
            content.view.topAnchor.constraint(equalTo: view.topAnchor),
            content.view.bottomAnchor.constraint(equalTo: view.bottomAnchor)
        ])
        content.didMove(toParent: self)
    }

    override func viewDidAppear(_ animated: Bool) {
        super.viewDidAppear(animated)
        if let scene = view.window?.windowScene { native.observeScene(scene) }
    }
}

private struct ComposeHost: UIViewControllerRepresentable {
    let host: AppHost

    func makeUIViewController(context: Context) -> UIViewController {
        host.native.attach(controller: host.controller, bridge: host.bridge)
        return host.controller
    }
    func updateUIViewController(_ controller: UIViewController, context: Context) {}

    static func dismantleUIViewController(_ controller: UIViewController, coordinator: Void) {
        (controller as? PlatformHostController)?.detach()
    }
}
