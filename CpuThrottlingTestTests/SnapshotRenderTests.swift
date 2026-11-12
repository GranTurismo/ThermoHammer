import SwiftUI
import XCTest
@testable import CpuThrottlingTest

/**
 * The share cards render at 1080 × 1350 px. With `TEST_RUNNER_SNAPSHOT_DIR=/some/dir xcodebuild test …` the rendered
 * images are also written there, to look at.
 */
@MainActor
final class SnapshotRenderTests: XCTestCase {

    private func save(_ png: Data, _ name: String) throws {
        guard let dir = ProcessInfo.processInfo.environment["SNAPSHOT_DIR"], !dir.isEmpty else { return }
        try png.write(to: URL(fileURLWithPath: dir).appendingPathComponent(name))
    }

    private func render<V: View>(_ view: V, size: CGSize, scale: CGFloat = 1) -> Data? {
        let r = ImageRenderer(content: view)
        r.scale = scale
        r.proposedSize = ProposedViewSize(size)
        return r.uiImage?.pngData()
    }

    static let benchmark = BenchmarkCard(
        device: "iPhone 17 Pro", os: "iOS 26.4", date: Date(timeIntervalSince1970: 1_762_950_000), chip: "A19 Pro", total: 41_873,
        sections: [SectionScore(key: "cpu", label: "CPU", points: 7_412), SectionScore(key: "mem", label: "MEM", points: 6_120),
                   SectionScore(key: "gpu", label: "GPU", points: 8_904), SectionScore(key: "ux", label: "UX", points: 6_877),
                   SectionScore(key: "ai", label: "AI", points: 7_301), SectionScore(key: "llm", label: "LLM", points: 5_259)],
        verified: true, serverId: 1234, mine: true)

    /// Five minutes: holds, throttles at 2:10, settles near 72 %.
    static let stress: StressCard = {
        var raw: [Double] = []
        for s in 0..<300 {
            let wobble = Double(s % 5) * 0.8
            if s < 130 { raw.append(100 - Double(s % 7) * 0.4) } else { raw.append(max(70, 100 - Double(s - 130) * 0.6) + wobble) }
        }
        return StressCard(device: "iPhone 17 Pro", os: "iOS 26.4", date: Date(timeIntervalSince1970: 1_762_950_000), chip: "A19 Pro",
                          capacity: 81.4, minutes: 5, load: "CPU", threads: "MULTI THREAD", curve: StressCurve.downsample(raw),
                          durationS: 300, onsetSec: 130, verified: true, serverId: nil, mine: true)
    }()

    func testShareCardsRenderAt1080x1350() throws {
        for (card, name) in [(ShareCard.benchmark(Self.benchmark), "share-card-benchmark.png"), (.stress(Self.stress), "share-card-stress.png")] {
            let png = try XCTUnwrap(ShareService.png(card))
            let image = try XCTUnwrap(UIImage(data: png))
            XCTAssertEqual(image.size.width * image.scale, 1080)
            XCTAssertEqual(image.size.height * image.scale, 1350)
            try save(png, name)
        }
    }

    /// The presentation lays itself out for a phone in portrait and a landscape iPad / Mac window.
    func testPresentationRendersPortraitAndLandscape() throws {
        let shots: [(ShareCard, CGSize, String)] = [
            (.stress(Self.stress), CGSize(width: 402, height: 874), "present-stress-portrait.png"),
            (.stress(Self.stress), CGSize(width: 1210, height: 834), "present-stress-landscape.png"),
            (.benchmark(Self.benchmark), CGSize(width: 402, height: 874), "present-benchmark-portrait.png"),
            (.benchmark(Self.benchmark), CGSize(width: 1210, height: 834), "present-benchmark-landscape.png"),
        ]
        for (card, size, name) in shots {
            let png = try XCTUnwrap(render(PresentView(card: card, onClose: {}).frame(width: size.width, height: size.height), size: size, scale: 2))
            try save(png, name)
        }
    }
}
