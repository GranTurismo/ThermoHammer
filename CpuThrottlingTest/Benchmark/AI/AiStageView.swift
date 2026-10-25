import SwiftUI

// ─────────────────────────────────────────────────────────────────────────────
// AI STAGE — what each model saw and what it made of it, while it is timed on
// the CPU, the GPU and the Neural Engine: classes with confidence bars,
// detection boxes, the street's class map, the ×4 upscale beside its
// pixelated input, the depth map beside the photo, and what YAMNet heard in
// the spectrogram. (Android's AiStage.)
// ─────────────────────────────────────────────────────────────────────────────

struct AiStageView: View {
    let visual: AiVisual?
    let accent: Color

    var body: some View {
        ZStack {
            Color.black
            if let v = visual {
                switch v.test {
                case .upscale: sideBySide(v, "INPUT · 128×128", "AI ×4 · 512×512", pixelated: true)
                case .depth: sideBySide(v, "PHOTO", "DEPTH · NEAR = BRIGHT", pixelated: false)
                default: overlaid(v)
                }
            }
        }
    }

    /** The picture fitted into the stage, with boxes, a class map or labels drawn over it. */
    private func overlaid(_ v: AiVisual) -> some View {
        let pw = v.picture.size.width, ph = v.picture.size.height
        return GeometryReader { g in
            let s = min(g.size.width / pw, g.size.height / ph)
            ZStack(alignment: .topLeading) {
                Image(uiImage: v.picture).resizable()
                if let r = v.result { Image(uiImage: r).resizable() }
                if !v.boxes.isEmpty {
                    Canvas { ctx, _ in
                        for b in v.boxes {
                            ctx.stroke(Path(CGRect(x: b.left * s, y: b.top * s, width: (b.right - b.left) * s, height: (b.bottom - b.top) * s)),
                                       with: .color(accent), lineWidth: 2)
                        }
                    }
                    ForEach(Array(v.boxes.prefix(12).enumerated()), id: \.offset) { _, b in
                        Text("\(b.label) \(Int((b.score * 100).rounded()))%")
                            .font(ThermoFont.mono(8, weight: .bold)).foregroundColor(.black)
                            .padding(.horizontal, 3).background(accent.opacity(0.85))
                            .offset(x: b.left * s, y: max(0, b.top * s - 13))
                    }
                }
                if !v.labels.isEmpty {
                    labels(v.labels).frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .bottomLeading).padding(10)
                }
            }
            .frame(width: pw * s, height: ph * s)
            .clipShape(RoundedRectangle(cornerRadius: 10))
            .frame(maxWidth: .infinity, maxHeight: .infinity)
        }
    }

    private func sideBySide(_ v: AiVisual, _ left: String, _ right: String, pixelated: Bool) -> some View {
        HStack(spacing: 12) {
            panel(v.picture, left, crisp: pixelated)
            panel(v.result, right, crisp: false)
        }
    }

    private func panel(_ img: UIImage?, _ label: String, crisp: Bool) -> some View {
        VStack(spacing: 6) {
            ZStack {
                Forge.surface
                if let img { Image(uiImage: img).interpolation(crisp ? .none : .medium).resizable() }
            }
            .aspectRatio(1, contentMode: .fit)
            .clipShape(RoundedRectangle(cornerRadius: 10))
            Text(label).font(ThermoFont.mono(9, weight: .black)).tracking(1).foregroundColor(accent)
        }
    }

    private func labels(_ labels: [(String, Float)]) -> some View {
        VStack(alignment: .leading, spacing: 5) {
            ForEach(Array(labels.enumerated()), id: \.offset) { _, l in
                HStack(spacing: 8) {
                    GeometryReader { g in
                        ZStack(alignment: .leading) {
                            Capsule().fill(accent.opacity(0.2))
                            Capsule().fill(accent).frame(width: g.size.width * CGFloat(max(0.02, min(1, l.1))))
                        }
                    }
                    .frame(width: 90, height: 6)
                    Text("\(l.0.uppercased()) · \(Int((l.1 * 100).rounded()))%").font(ThermoFont.mono(10, weight: .bold)).foregroundColor(Forge.ink0)
                }
            }
        }
        .padding(10)
        .background(Forge.bg.opacity(0.78))
        .clipShape(RoundedRectangle(cornerRadius: 8))
        .fixedSize()
    }
}
