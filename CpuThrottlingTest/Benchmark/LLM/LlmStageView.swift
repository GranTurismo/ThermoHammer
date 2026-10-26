import SwiftUI

// ─────────────────────────────────────────────────────────────────────────────
// LLM STAGE — the context the model works on: the 512-token prompt lighting up
// as each pass reads it in one batch, then the reply written token by token
// (the ids, coloured per token). The weights come from a seed, so the words are
// random; the work is the real model's. (Android's LlmStage.)
// ─────────────────────────────────────────────────────────────────────────────

struct LlmStageView: View {
    @ObservedObject var engine: BenchmarkEngine
    let accent: Color

    private static let promptCols = 32, promptRows = 16, replyCols = 8

    var body: some View {
        let accel = engine.llmAccel ?? .cpu
        // the iPhone stage is portrait: the prompt above, the reply below
        VStack(alignment: .leading, spacing: 14) {
            HStack(spacing: 10) {
                ForEach(LlmAccel.allCases, id: \.rawValue) { a in
                    let now = a == accel, done = a.rawValue < accel.rawValue
                    Text((done ? "✓ " : "") + a.label)
                        .font(ThermoFont.mono(9, weight: .bold)).tracking(1.5)
                        .foregroundColor(now ? accent : Forge.ink2)
                        .padding(.horizontal, 10).padding(.vertical, 4)
                        .background(now ? accent.opacity(0.18) : Color.clear)
                        .cornerRadius(8)
                        .overlay(RoundedRectangle(cornerRadius: 8).stroke(now ? accent : Forge.ink0.opacity(0.12), lineWidth: 1))
                }
                Spacer()
            }
            Text("\(LlmSection.model) · 1.8 GB · 28 LAYERS").font(ThermoFont.mono(9)).tracking(1.5).foregroundColor(Forge.ink1)
                .lineLimit(1).minimumScaleFactor(0.7)
            VStack(alignment: .leading, spacing: 6) {
                Text("PROMPT · 512 TOKENS · ONE BATCH" + (engine.llmPrompts > 0 ? " · ×\(engine.llmPrompts)" : ""))
                    .font(ThermoFont.mono(9, weight: .bold)).tracking(1.5)
                    .foregroundColor(engine.llmPhase == .prefill ? accent : Forge.ink2)
                PromptGrid(phase: engine.llmPhase, prompts: engine.llmPrompts, accent: accent,
                           cols: Self.promptCols, rows: Self.promptRows)
                    .aspectRatio(CGFloat(Self.promptCols) / CGFloat(Self.promptRows), contentMode: .fit)
            }
            VStack(alignment: .leading, spacing: 6) {
                Text("REPLY · TOKEN BY TOKEN").font(ThermoFont.mono(9, weight: .bold)).tracking(1.5)
                    .foregroundColor(engine.llmPhase == .decode ? accent : Forge.ink2)
                ReplyGrid(reply: engine.llmReply, cols: Self.replyCols, count: LlmSection.reply)
                    .frame(height: 150)
            }
            Spacer(minLength: 0)
            Text("WEIGHTS FROM A SEED: THE WORDS ARE RANDOM, THE WORK IS THE REAL MODEL'S")
                .font(ThermoFont.mono(8)).tracking(1.2).foregroundColor(Forge.ink2).lineLimit(2)
        }
        .padding(.vertical, 6)
    }
}

/** The prompt's 512 tokens: dim until read; each prefill pass sweeps through them at once (one batch). */
private struct PromptGrid: View {
    let phase: LlmPhase
    let prompts: Int
    let accent: Color
    let cols: Int, rows: Int
    @State private var sweepStart = Date.distantPast

    var body: some View {
        TimelineView(.animation) { tl in
            let sweep = min(1, tl.date.timeIntervalSince(sweepStart) / 0.52)
            Canvas { ctx, size in
                let gap: CGFloat = 2
                let cw = (size.width - gap * CGFloat(cols - 1)) / CGFloat(cols)
                let ch = (size.height - gap * CGFloat(rows - 1)) / CGFloat(rows)
                let read = prompts > 0 || phase == .decode
                let front = sweep * Double(cols * rows)
                for i in 0..<(cols * rows) {
                    let rect = CGRect(x: CGFloat(i % cols) * (cw + gap), y: CGFloat(i / cols) * (ch + gap), width: cw, height: ch)
                    let alpha = !read ? 0.08 : phase == .prefill && Double(i) < front && Double(i) > front - 64 ? 1 : 0.42
                    ctx.fill(Path(roundedRect: rect, cornerRadius: min(cw, ch) * 0.2), with: .color(accent.opacity(alpha)))
                }
            }
        }
        .onChange(of: prompts) { _ in sweepStart = Date() }
    }
}

/** The reply so far: one chip per generated token, its id in its own colour. */
private struct ReplyGrid: View {
    let reply: [Int]
    let cols: Int, count: Int

    var body: some View {
        let rows = (count + cols - 1) / cols
        VStack(spacing: 4) {
            ForEach(0..<rows, id: \.self) { r in
                HStack(spacing: 4) {
                    ForEach(0..<cols, id: \.self) { c in
                        let k = r * cols + c
                        let token = k < reply.count ? reply[k] : nil
                        let color = token.map(Self.color)
                        ZStack {
                            RoundedRectangle(cornerRadius: 5).fill(color?.opacity(0.16) ?? Forge.ink0.opacity(0.04))
                            RoundedRectangle(cornerRadius: 5).stroke(color?.opacity(0.6) ?? .clear, lineWidth: 1)
                            if let token, let color {
                                Text(verbatim: String(token)).font(ThermoFont.mono(9, weight: .bold)).foregroundColor(color)
                                    .lineLimit(1).minimumScaleFactor(0.6)
                            }
                        }
                    }
                }
            }
        }
    }

    /** A steady colour per token id, spread round the hue circle. */
    static func color(_ id: Int) -> Color {
        Color(hue: (Double(id) * 0.618034).truncatingRemainder(dividingBy: 1), saturation: 0.5, brightness: 0.97)
    }
}
