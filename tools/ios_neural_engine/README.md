# Neural Engine models for the AI test (iPhone/Mac): dormant

The six Core ML packages the AI section ran on the Apple Neural Engine (`CoreMLRunner` in
`CpuThrottlingTest/Benchmark/AI/AiBench.swift`, loaded by name as `<asset>.mlmodelc`). Scoring version 5 doesn't score
the NPU on any platform (no NPU path reaches every device), so they're out of the app (~47 MB) and kept here for a
later version. To measure the Neural Engine again in a development build, move `models/` back to
`CpuThrottlingTest/Benchmark/AI/NeuralEngine`, run `xcodegen generate`, and launch with `-npuReport` (report only).
