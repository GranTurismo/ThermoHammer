import SwiftUI

/** The full-screen layers any screen can open — the speed check, a run's presentation, the health certificate, MY RUNS.
 *  ContentView shows them above everything else (the benchmark's result included). */
final class AppOverlays: ObservableObject {
    static let shared = AppOverlays()

    @Published var lostSpeed = false
    @Published var present: ShareCard?
    @Published var certificate: CertificateRequest?
    @Published var myRuns = false              // MY RUNS: this device's benchmark history

    private init() {}
}
