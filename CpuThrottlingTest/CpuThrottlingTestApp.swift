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
        }
    }
}
