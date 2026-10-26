import CoreText
import SwiftUI

@main
struct CpuThrottlingTestApp: App {
    init() {
        // XcodeGen's INFOPLIST_KEY_UIAppFonts never reaches the generated Info.plist, so the bundled fonts
        // (JetBrains Mono, Space Grotesk) are registered here instead.
        for url in Bundle.main.urls(forResourcesWithExtension: "ttf", subdirectory: nil) ?? [] {
            CTFontManagerRegisterFontsForURL(url as CFURL, .process, nil)
        }
    }

    var body: some Scene {
        WindowGroup {
            ContentView()
                .background(DesktopWindowSizer())
        }
    }
}

/// On a Mac: a desktop window, wide enough for the sidebar and two columns (ContentView.desktopLayout). Set from a view
/// inside the window, once it's in one — the scene isn't connected yet when the app's first view appears. (A Mac shows
/// an iPad app at 77%, so these are iPad points: 1300 × 860 is about 1000 × 660 on the Mac's screen.)
private struct DesktopWindowSizer: UIViewRepresentable {
    final class Probe: UIView {
        override func didMoveToWindow() {
            super.didMoveToWindow()
            guard LeaderboardService.isMac, let window, let restrictions = window.windowScene?.sizeRestrictions else { return }
            let resizable = { restrictions.minimumSize = CGSize(width: 1000, height: 700)
                              restrictions.maximumSize = CGSize(width: 2600, height: 1800) }
            if window.bounds.height > window.bounds.width {
                // still tall (a first launch, or the phone-shaped window of an earlier version): open it landscape,
                // then let it be resized — a size picked later is the one macOS restores
                restrictions.minimumSize = CGSize(width: 1300, height: 860)
                restrictions.maximumSize = CGSize(width: 1300, height: 860)
                DispatchQueue.main.asyncAfter(deadline: .now() + 0.6, execute: resizable)
            } else {
                resizable()
            }
        }
    }
    func makeUIView(context: Context) -> Probe {
        let p = Probe()
        p.isUserInteractionEnabled = false          // only there to find the window: never in the way of a click
        return p
    }
    func updateUIView(_ uiView: Probe, context: Context) {}
}
