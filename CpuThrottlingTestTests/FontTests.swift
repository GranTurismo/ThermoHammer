import XCTest
import UIKit
@testable import CpuThrottlingTest

/** Every typeface name the app asks for must exist. SwiftUI's `.custom` falls back to the system font without a word
 *  when a name is wrong — that is how Space Grotesk went missing from every heading until 2.1. */
final class FontTests: XCTestCase {

    func testEveryBundledFontNameResolves() {
        // the host app registered the bundled files at launch (CpuThrottlingTestApp.init)
        let names = [ThermoFont.monoRegular, ThermoFont.monoMedium, ThermoFont.monoBold, ThermoFont.monoBlack,
                     ThermoFont.displayMed, ThermoFont.displayBold]
        for name in names {
            let font = UIFont(name: name, size: 17)
            XCTAssertNotNil(font, "\(name) isn't a font in the app bundle")
            XCTAssertEqual(font?.fontName, name)
        }
    }

    func testDisplayWeightsAreMediumAndBold() {
        func weight(_ name: String) -> CGFloat {
            let traits = UIFont(name: name, size: 17)?.fontDescriptor.object(forKey: .traits) as? [UIFontDescriptor.TraitKey: Any]
            return traits?[.weight] as? CGFloat ?? -1
        }
        XCTAssertLessThan(weight(ThermoFont.displayMed), weight(ThermoFont.displayBold))
        XCTAssertGreaterThan(weight(ThermoFont.displayMed), 0)     // 500, not Light, despite the file's name
    }
}
