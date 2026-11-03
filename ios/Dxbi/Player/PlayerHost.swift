import SwiftUI
import UIKit

/**
 * The player's own screen, sideways. Shown over the app (which stays upright underneath) rather than
 * as a SwiftUI cover: a cover turned the whole app sideways with it, and the title page came back
 * laid out wrong when it turned upright again.
 */
final class PlayerHost: UIHostingController<AnyView> {

    private static weak var shown: PlayerHost?

    override var supportedInterfaceOrientations: UIInterfaceOrientationMask { .landscape }
    override var preferredInterfaceOrientationForPresentation: UIInterfaceOrientation { .landscapeRight }
    override var prefersStatusBarHidden: Bool { true }
    override var prefersHomeIndicatorAutoHidden: Bool { true }

    @MainActor
    static func show(_ playback: Playback, stremio: Stremio) {
        shown?.dismiss(animated: false)
        guard let scene = UIApplication.shared.connectedScenes.compactMap({ $0 as? UIWindowScene }).first,
              var top = scene.keyWindow?.rootViewController else { return }
        while let next = top.presentedViewController { top = next }
        let host = PlayerHost(rootView: AnyView(PlayerScreen(playback: playback).environment(stremio).preferredColorScheme(.dark)))
        host.modalPresentationStyle = .fullScreen
        host.view.backgroundColor = .black
        AppDelegate.orientations = .landscape
        shown = host
        top.present(host, animated: true)
    }

    @MainActor
    static func hide() {
        guard let host = shown else { return }
        shown = nil
        AppDelegate.orientations = .portrait
        let presenter = host.presentingViewController
        host.dismiss(animated: true) {
            presenter?.setNeedsUpdateOfSupportedInterfaceOrientations()
            (presenter?.view.window?.windowScene)?.requestGeometryUpdate(.iOS(interfaceOrientations: .portrait)) { _ in }
        }
    }
}
