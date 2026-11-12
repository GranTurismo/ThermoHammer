import XCTest
@testable import CpuThrottlingTest

/** EXPORT CSV: header first, commas, "." decimals, no comment lines — the same columns on every platform. */
final class CsvExportTests: XCTestCase {

    private func rows(_ csv: String) -> [[String]] {
        csv.split(separator: "\n", omittingEmptySubsequences: false).dropLast().map { $0.components(separatedBy: ",") }
    }

    func testFieldsAndNumbers() {
        XCTAssertEqual(CsvExport.field("plain"), "plain")
        XCTAssertEqual(CsvExport.field("a,b"), "\"a,b\"")
        XCTAssertEqual(CsvExport.field("say \"hi\""), "\"say \"\"hi\"\"\"")
        XCTAssertEqual(CsvExport.num(1234.5678), "1234.57")          // no grouping, "." decimals
        XCTAssertEqual(CsvExport.num(0.5, 0), "0")
        XCTAssertEqual(CsvExport.num(.nan), "")
        XCTAssertEqual(CsvExport.key("MATRIX MULTIPLY"), "matrix_multiply")
        XCTAssertEqual(CsvExport.key("JSON & XML"), "json_xml")
    }

    func testOwnStressRun() {
        let stamps = [
            StampV2(tNs: 1_000_000_000, windowNs: 1_002_000_000, ipsTotal: 2_000_000, ipsPerWorker: [1_000_000, 1_000_000],
                    schedFracAvg: 0.99, thermalState: 0, systemLoad: 0.97, attribution: 0, envFlags: 0),
            StampV2(tNs: 2_001_000_000, windowNs: 1_001_000_000, ipsTotal: 1_500_000, ipsPerWorker: [800_000, 700_000],
                    schedFracAvg: 0.95, thermalState: 2, systemLoad: 0.99, attribution: 1, envFlags: EnvFlag.charging),
        ]
        let r = rows(CsvExport.stress(stamps, baseline: 2_000_000))
        XCTAssertEqual(Array(r[0].prefix(4)), ["elapsed_s", "throughput", "percent_of_baseline", "thermal_state"])
        XCTAssertEqual(r[0].count, 12)                                              // + 6 iOS columns + 2 workers
        XCTAssertEqual(r[0].last, "worker_2_throughput")
        XCTAssertEqual(r[1], ["1.000", "2000000", "100.00", "0", "1.002", "99.0", "97.0", "none", "0", "0", "1000000", "1000000"])
        XCTAssertEqual(Array(r[2].prefix(4)), ["2.001", "1500000", "75.00", "2"])
        XCTAssertEqual(r[2][7], "thermal")
        XCTAssertEqual(r[2][8], "1")
    }

    func testLeaderboardStressRun() {
        let csv = CsvExport.leaderboardStress([DeviceHammerStamp(elapsedMs: 1500, score: 900, thermalState: 1)])
        XCTAssertEqual(csv, "elapsed_s,score,thermal_state\n1.500,900,1\n")
    }

    func testBenchmarkRun() {
        var r = BenchmarkResult()
        r.cpu = CpuResult(runs: CpuTest.allCases.map { CpuTestRun(test: $0, rates: [("MATRIX MULTIPLY", 1000.25), ("FFT", 2000)]) })
        r.mem = MemResult(ram: RamResult(read: 50, write: 25, copy: 15, latencyNs: 240),
                          storage: StorageResult(seqRead: 1700, seqWrite: 1200, randomRead: 28_000, randomWrite: 13_000, direct: true, fileMb: 512),
                          db: DbResult(insert: 9000, update: 30_000, query: 20_000))
        let lines = rows(CsvExport.benchmark(r))
        XCTAssertEqual(lines[0], ["section", "metric", "value", "unit"])
        XCTAssertTrue(lines.allSatisfy { $0.count == 4 }, "every row is section,metric,value,unit")
        XCTAssertTrue(lines.contains(["cpu", "math_matrix_multiply", "1000.25", "ops/s"]))
        XCTAssertTrue(lines.contains(["mem", "ram_latency", "240.00", "ns"]))
        XCTAssertTrue(lines.contains(["mem", "storage_direct_io", "1", "bool"]))
        // the section scores, then the total, last
        XCTAssertEqual(lines.suffix(3).map { $0[0] }, ["score", "score", "score"])
        XCTAssertEqual(lines[lines.count - 3][1], "cpu")
        XCTAssertEqual(lines[lines.count - 2][1], "mem")
        XCTAssertEqual(lines.last!, ["score", "total", "\(r.total)", "points"])
        XCTAssertFalse(lines.contains { $0[0] == "gpu" })                           // a section the run doesn't have
    }
}
