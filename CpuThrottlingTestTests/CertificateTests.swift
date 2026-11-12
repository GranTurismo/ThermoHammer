import CoreImage
import SwiftUI
import XCTest
@testable import CpuThrottlingTest

/** The health certificate's QR code: BenchCore's encoder, a quiet zone, and a scanner reads the link back. */
@MainActor
final class CertificateTests: XCTestCase {

    func testQrHasQuietZoneAndReadsBack() throws {
        let url = ShareLinks.certificate(1234)
        let m = try XCTUnwrap(QrCode.modules(url))
        XCTAssertEqual(m.dark.count, m.side * m.side)
        XCTAssertTrue((21...177).contains(m.side) && (m.side - 21) % 4 == 0, "a QR version's side")
        let image = try XCTUnwrap(QrCode.image(url))
        XCTAssertEqual(image.width, m.side + 8)                          // 4 light modules on every side

        // scaled up 8× without smoothing, as the screen shows it — and scanned
        let big = CIImage(cgImage: image).samplingNearest().transformed(by: CGAffineTransform(scaleX: 8, y: 8))
        let detector = try XCTUnwrap(CIDetector(ofType: CIDetectorTypeQRCode, context: nil, options: [CIDetectorAccuracy: CIDetectorAccuracyHigh]))
        let found = detector.features(in: big).compactMap { ($0 as? CIQRCodeFeature)?.messageString }
        XCTAssertEqual(found, [url])
    }

    func testEmptyTextHasNoCode() {
        XCTAssertNil(QrCode.modules(""))
    }

    /// The certificate as the phone shows it (written out with TEST_RUNNER_SNAPSHOT_DIR).
    func testCertificateRenders() throws {
        let card = CertificateCard(device: "iPhone 17 Pro", line: "iOS 26.4 · 12 Nov 2026", total: 41_873,
                                   healthLine: "98% of the median iPhone 17 Pro (37 runs)", url: ShareLinks.certificate(1234))
            .padding(20).frame(width: 380).background(Forge.raised)
        let r = ImageRenderer(content: card)
        r.scale = 3
        let png = try XCTUnwrap(r.uiImage?.pngData())
        if let dir = ProcessInfo.processInfo.environment["SNAPSHOT_DIR"], !dir.isEmpty {
            try png.write(to: URL(fileURLWithPath: dir).appendingPathComponent("certificate.png"))
        }
    }
}
