import SwiftUI
import UIKit

// ─────────────────────────────────────────────────────────────────────────────
// VERIFIED HEALTH CERTIFICATE — for selling a used phone. The certificate is a
// page on the website, built from the run the server verified; the phone shows
// it as a QR code, so the buyer scans the code shown BY the phone being sold.
// ─────────────────────────────────────────────────────────────────────────────

/** A QR code from BenchCore's encoder (bc_qr_encode: error correction M, no quiet zone). */
enum QrCode {
    /// The code's modules row by row (true = dark), side × side; nil when `text` doesn't fit.
    static func modules(_ text: String) -> (side: Int, dark: [Bool])? {
        var buf = [UInt8](repeating: 0, count: 177 * 177)
        let side = Int(text.withCString { bc_qr_encode($0, &buf, Int32(buf.count)) })
        guard side > 0 else { return nil }
        return (side, buf.prefix(side * side).map { $0 != 0 })
    }

    /// The code one pixel per module, dark on white, with the 4-module quiet zone scanners need. Scaled up with
    /// `.interpolation(.none)` it stays sharp at any size.
    static func image(_ text: String) -> CGImage? {
        guard let m = modules(text) else { return nil }
        let side = m.side, dark = m.dark, quiet = 4, w = side + 2 * quiet
        var px = [UInt8](repeating: 255, count: w * w)
        for y in 0..<side { for x in 0..<side where dark[y * side + x] { px[(y + quiet) * w + x + quiet] = 0 } }
        guard let provider = CGDataProvider(data: Data(px) as CFData) else { return nil }
        return CGImage(width: w, height: w, bitsPerComponent: 8, bitsPerPixel: 8, bytesPerRow: w,
                       space: CGColorSpaceCreateDeviceGray(), bitmapInfo: CGBitmapInfo(rawValue: CGImageAlphaInfo.none.rawValue),
                       provider: provider, decode: nil, shouldInterpolate: false, intent: .defaultIntent)
    }
}

/** What the certificate is of: this device's own benchmark run. */
struct CertificateRequest {
    let record: BenchmarkRecord
    let device: String
    let os: String              // "iOS 26.4"
    let total: Int
}

struct CertificateView: View {
    let request: CertificateRequest
    let onClose: () -> Void
    @ObservedObject private var history = BenchmarkHistoryStore.shared
    @ObservedObject private var network = NetworkMonitor.shared
    @State private var submitting = false
    @State private var submitError: String?

    private var serverId: Int? { history.record(request.record.id)?.serverId ?? request.record.serverId }

    /// "96% of the median iPhone 15 (41 runs)", from this run's Phone Health.
    private var healthLine: String? {
        guard let m = PhoneHealthMemo.last, m.recordId == request.record.id else { return nil }
        return "\(m.percent)% of the median \(m.model) (\(m.runs) runs)"
    }

    var body: some View {
        ForgeOverlayScrim {
            ForgeCard {
                ViewThatFits(in: .vertical) {
                    content
                    ScrollView(showsIndicators: false) { content }
                }
            }
            .frame(maxWidth: 420)
            .padding(.horizontal, 14).padding(.vertical, 24)
        }
    }

    private var content: some View {
        VStack(alignment: .leading, spacing: 12) {
            HStack(spacing: 8) {
                StatusLED(color: Forge.phaseMeasured)
                Engraved(text: "VERIFIED HEALTH CERTIFICATE", color: Forge.phaseMeasured, size: 10)
                Spacer()
                Button(action: onClose) { Text("✕").font(ThermoFont.mono(12)).foregroundColor(Forge.ink2).padding(.horizontal, 6) }
                    .keyboardShortcut(.cancelAction)
            }
            if let id = serverId { certificate(id) } else { notOnTheBoard }
        }
    }

    private func certificate(_ id: Int) -> some View {
        let url = ShareLinks.certificate(id)
        return VStack(alignment: .leading, spacing: 12) {
            CertificateCard(device: request.device, line: "\(request.os) · \(ShareLinks.cardDate(request.record.timestamp))",
                            total: request.total, healthLine: healthLine, url: url)
            Text("Buyer: scan this code from the \(ShareService.noun) being sold. The page shows this exact run as the server verified it.")
                .font(ThermoFont.mono(9)).foregroundColor(Forge.ink1)
            HStack(spacing: 10) {
                AnchoredButton(action: { anchor in
                    let items: [Any] = [ShareLinks.certificateText(device: request.device, url: url)] + [URL(string: url)].compactMap { $0 }
                    ShareSheet.present(items, from: anchor)
                }) {
                    Text("SHARE LINK").font(ThermoFont.mono(11, weight: .black)).foregroundColor(Forge.bg)
                        .frame(maxWidth: .infinity).padding(.vertical, 12).background(Forge.phaseMeasured).cornerRadius(10)
                }
                Button(action: onClose) {
                    Text("DONE").font(ThermoFont.mono(11, weight: .black)).foregroundColor(Forge.ink0)
                        .frame(maxWidth: .infinity).padding(.vertical, 12).background(Forge.interact).cornerRadius(10)
                }
            }
        }
    }

    /// Not submitted yet: the certificate is the run's page on the site, so it has to be on the leaderboard first.
    private var notOnTheBoard: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text("The certificate is a page on thermohammer.gtgroup.dev, so the run has to be on the leaderboard first.")
                .font(ThermoFont.mono(10)).foregroundColor(Forge.ink0)
            if let submitError { Text(submitError).font(ThermoFont.mono(9)).foregroundColor(Ramp.at(0.85)) }
            if !network.isConnected {
                Text("No internet connection. Turn on Wi-Fi or mobile data to submit this run.")
                    .font(ThermoFont.mono(9)).foregroundColor(Ramp.at(0.5))
            } else {
                Button {
                    submitting = true
                    submitError = nil
                    Task {
                        do { _ = try await BenchmarkUpload.submitAndRemember(request.record) } catch { submitError = error.localizedDescription }
                        submitting = false
                    }
                } label: {
                    Text(submitting ? "SUBMITTING…" : "SUBMIT TO LEADERBOARD").font(ThermoFont.mono(12, weight: .black)).foregroundColor(Forge.bg)
                        .frame(maxWidth: .infinity).padding(.vertical, 14).background(Forge.phaseMeasured).cornerRadius(12)
                }
                .disabled(submitting)
            }
            Button(action: onClose) {
                Text("DONE").font(ThermoFont.mono(11, weight: .bold)).foregroundColor(Forge.ink1)
                    .frame(maxWidth: .infinity).padding(.vertical, 11).background(Forge.interact).cornerRadius(10)
            }
        }
    }
}

/** The certificate itself: the device, its OS and the run's date, the score, its health, the QR code and the link. */
struct CertificateCard: View {
    let device: String
    let line: String
    let total: Int
    let healthLine: String?
    let url: String

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            VStack(alignment: .leading, spacing: 3) {
                Text(device).font(ThermoFont.display(24, weight: .bold)).foregroundColor(Forge.ink0).lineLimit(2).minimumScaleFactor(0.7)
                Text(line).font(ThermoFont.mono(10)).foregroundColor(Forge.ink2)
            }
            HStack(alignment: .lastTextBaseline, spacing: 8) {
                Text(total.formatted()).font(ThermoFont.mono(30, weight: .black)).foregroundColor(Forge.ink0)
                Engraved(text: "BENCHMARK POINTS", size: 8)
            }
            if let healthLine {
                Text(healthLine).font(ThermoFont.mono(10, weight: .bold)).foregroundColor(Forge.phaseMeasured)
            }
            if let qr = QrCode.image(url) {
                GeometryReader { g in
                    // whole points per module: on a phone's 2× / 3× screen every module is the same number of pixels
                    let module = max(1, floor(min(g.size.width, 300) / CGFloat(qr.width)))
                    Image(decorative: qr, scale: 1)
                        .resizable()
                        .interpolation(.none)
                        .frame(width: module * CGFloat(qr.width), height: module * CGFloat(qr.width))
                        .frame(width: g.size.width)
                        .accessibilityLabel("QR code of the certificate's link")
                }
                .aspectRatio(1, contentMode: .fit)
                .frame(maxWidth: 300)
                .frame(maxWidth: .infinity)
            }
            Text(ShareLinks.display(url)).font(ThermoFont.mono(11, weight: .bold)).foregroundColor(Forge.phaseMeasured)
                .lineLimit(1).minimumScaleFactor(0.6)
                .frame(maxWidth: .infinity)
        }
    }
}
