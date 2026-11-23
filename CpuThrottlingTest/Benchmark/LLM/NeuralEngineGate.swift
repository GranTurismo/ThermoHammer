import Foundation

/**
 * Which devices' Neural Engine can run the LLM test's model at all (AneLlm). ANEMLL documents that the A14 and M1
 * generation can only hold 512-token monolithic models (non-uniform state shapes), and the test needs a 1,280-token
 * chunked one; Intel Macs have no Neural Engine. On those the NPU third counts 0 — the hardware's limit, like an Android
 * chip without an NPU — and the ~1 GB model is never downloaded.
 */
enum NeuralEngineGate {
    /** Why this device can't run the model, or nil if it should. */
    static let hardwareLimit: String? = {
        #if targetEnvironment(simulator)
        return "the simulator has no Neural Engine"
        #else
        var onMac = ProcessInfo.processInfo.isiOSAppOnMac
        #if targetEnvironment(macCatalyst)
        onMac = true
        #endif
        if onMac {
            let brand = sysctlString("machdep.cpu.brand_string") ?? ""
            if !brand.hasPrefix("Apple") { return "this Mac has no Neural Engine" }
            if brand.contains("M1") { return "the M1's Neural Engine can't hold the model (1,280-token context)" }
            return nil
        }
        // "iPhone13,2", "iPad13,4": generation 13 is the A14 (iPhone 12) and the M1/A14 iPads; lower is older still
        let machine = sysctlString("hw.machine") ?? ""
        for family in ["iPhone", "iPad"] where machine.hasPrefix(family) {
            if let generation = Int(machine.dropFirst(family.count).prefix(while: { $0.isNumber })), generation <= 13 {
                return "an A14/M1-generation or older Neural Engine can't hold the model (1,280-token context)"
            }
        }
        return nil
        #endif
    }()

    private static func sysctlString(_ name: String) -> String? {
        var size = 0
        guard sysctlbyname(name, nil, &size, nil, 0) == 0, size > 0 else { return nil }
        var buffer = [CChar](repeating: 0, count: size)
        guard sysctlbyname(name, &buffer, &size, nil, 0) == 0 else { return nil }
        return String(cString: buffer)
    }
}
