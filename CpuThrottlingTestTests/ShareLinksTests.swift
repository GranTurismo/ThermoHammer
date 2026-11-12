import XCTest
@testable import CpuThrottlingTest

/** The share card's links, texts and file names — the same on Android and Windows. */
final class ShareLinksTests: XCTestCase {

    func testLinks() {
        XCTAssertEqual(ShareLinks.run(123), "https://thermohammer.gtgroup.dev/run/123/")
        XCTAssertEqual(ShareLinks.stress(45), "https://thermohammer.gtgroup.dev/stress/45/")
        XCTAssertEqual(ShareLinks.certificate(9), "https://thermohammer.gtgroup.dev/cert/9/")
        // not submitted: the site, never a broken link
        XCTAssertEqual(ShareLinks.run(nil), "https://thermohammer.gtgroup.dev/")
        XCTAssertEqual(ShareLinks.stress(nil), "https://thermohammer.gtgroup.dev/")
    }

    func testDisplayLink() {
        XCTAssertEqual(ShareLinks.display("https://thermohammer.gtgroup.dev/run/123/"), "thermohammer.gtgroup.dev/run/123")
        XCTAssertEqual(ShareLinks.display("https://thermohammer.gtgroup.dev/"), "thermohammer.gtgroup.dev")
    }

    func testBenchmarkTexts() {
        XCTAssertEqual(ShareLinks.benchmarkText(device: "iPhone 17 Pro", total: "31,245", url: "U", mine: true, noun: "phone"),
                       "My iPhone 17 Pro scored 31,245 on the ThermoHammer benchmark. Can your phone beat it? U")
        XCTAssertEqual(ShareLinks.benchmarkText(device: "HONOR 600 Pro", total: "28,001", url: "U", mine: false, noun: "phone"),
                       "HONOR 600 Pro scored 28,001 on ThermoHammer. Can your phone beat it? U")
    }

    func testStressTexts() {
        XCTAssertEqual(ShareLinks.stressText(device: "iPhone 17 Pro", capacity: 86.6, minutes: 15, url: "U", mine: true, noun: "phone"),
                       "My iPhone 17 Pro kept 87% of its speed through a 15-minute ThermoHammer stress test. Can your phone do better? U")
        XCTAssertEqual(ShareLinks.stressText(device: "Pixel 9", capacity: 71.2, minutes: 5, url: "U", mine: false, noun: "phone"),
                       "Pixel 9 kept 71% of its speed through a 5-minute ThermoHammer stress test. U")
        XCTAssertEqual(ShareLinks.challenge(noun: "phone"), "CAN YOUR PHONE BEAT THIS?")
        XCTAssertEqual(ShareLinks.certificateText(device: "iPhone 15", url: "U"), "Health certificate for my iPhone 15: U")
    }

    func testFileNames() {
        var c = DateComponents()
        c.year = 2026; c.month = 11; c.day = 12; c.hour = 15; c.minute = 7
        let date = Calendar(identifier: .gregorian).date(from: c)!
        XCTAssertEqual(ShareLinks.slug("Mac (M5)"), "mac-m5")
        XCTAssertEqual(ShareLinks.slug("iPhone 17 Pro Max"), "iphone-17-pro-max")
        XCTAssertEqual(ShareLinks.slug("  ***  "), "device")
        XCTAssertEqual(ShareLinks.csvFileName(stress: true, device: "iPhone 17 Pro", date: date), "thermohammer-stress-iphone-17-pro-20261112-1507.csv")
        XCTAssertEqual(ShareLinks.csvFileName(stress: false, device: "Mac (M5)", date: date), "thermohammer-benchmark-mac-m5-20261112-1507.csv")
        XCTAssertEqual(ShareLinks.imageFileName(stress: false, serverId: 123, date: date), "thermohammer-run-123.png")
        XCTAssertEqual(ShareLinks.imageFileName(stress: true, serverId: nil, date: Date(timeIntervalSince1970: 1_762_000_000)),
                       "thermohammer-stress-1762000000.png")
        XCTAssertEqual(ShareLinks.cardDate(date), "12 Nov 2026")
    }
}
