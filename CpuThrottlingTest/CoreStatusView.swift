import SwiftUI

struct CoreMeter: View {
    let index: Int
    let impact: Double // 0.0 to 100.0 (current speed percentage)
    
    private var isIdle: Bool {
        impact <= 0.5
    }
    
    var body: some View {
        VStack(spacing: 8) {
            ZStack {
                // Outer glow for low-performance cores
                if !isIdle && impact < 95 {
                    Circle()
                        .stroke(ringColor(for: impact).opacity(0.15), lineWidth: 12)
                        .blur(radius: 4)
                        .animation(.easeInOut(duration: 0.5), value: impact)
                }
                
                // Track ring
                Circle()
                    .stroke(Color.white.opacity(0.05), lineWidth: 6)
                
                // Active ring arc
                if !isIdle {
                    Circle()
                        .trim(from: 0.0, to: CGFloat(max(0.05, impact / 100.0)))
                        .stroke(
                            ringGradient(for: impact),
                            style: StrokeStyle(lineWidth: 6, lineCap: .round)
                        )
                        .rotationEffect(.degrees(-90))
                        .animation(.easeInOut(duration: 0.5), value: impact)
                }
                
                // Core status text
                VStack(spacing: 1) {
                    Text("CORE \(index)")
                        .font(.system(size: 9, weight: .bold, design: .monospaced))
                        .foregroundColor(.secondary)
                    
                    if isIdle {
                        Text("IDLE")
                            .font(.system(size: 11, weight: .bold, design: .monospaced))
                            .foregroundColor(.secondary)
                    } else if impact >= 99.5 {
                        Text("100%")
                            .font(.system(size: 13, weight: .bold, design: .monospaced))
                            .foregroundColor(.white)
                    } else {
                        let loss = Int(round(100.0 - impact))
                        Text("-\(loss)%")
                            .font(.system(size: 13, weight: .bold, design: .monospaced))
                            .foregroundColor(ringColor(for: impact))
                        Text("IMPACT")
                            .font(.system(size: 7, weight: .semibold, design: .monospaced))
                            .foregroundColor(.secondary)
                    }
                }
            }
            .frame(width: 72, height: 72)
        }
        .padding(.vertical, 10)
        .padding(.horizontal, 6)
        .background(
            RoundedRectangle(cornerRadius: 16)
                .fill(Color.white.opacity(0.03))
                .overlay(
                    RoundedRectangle(cornerRadius: 16)
                        .stroke(Color.white.opacity(0.08), lineWidth: 1)
                )
        )
    }
    
    private func ringColor(for val: Double) -> Color {
        if val <= 0.5 {
            return Color.white.opacity(0.15)
        } else if val >= 95 {
            return Color(red: 0.2, green: 0.8, blue: 0.4) // Vibrant Green
        } else if val >= 80 {
            return Color(red: 0.95, green: 0.7, blue: 0.1) // Amber
        } else {
            return Color(red: 0.9, green: 0.35, blue: 0.1) // Deep Orange/Red
        }
    }
    
    private func ringGradient(for val: Double) -> LinearGradient {
        let color = ringColor(for: val)
        return LinearGradient(
            colors: [color, color.opacity(0.6)],
            startPoint: .topLeading,
            endPoint: .bottomTrailing
        )
    }
}

struct GpuMeter: View {
    let impact: Double // 0.0 to 100.0
    
    private var isIdle: Bool {
        impact <= 0.5
    }
    
    var body: some View {
        VStack(spacing: 8) {
            ZStack {
                if !isIdle {
                    Circle()
                        .stroke(Color.purple.opacity(0.15), lineWidth: 12)
                        .blur(radius: 4)
                        .animation(.easeInOut(duration: 0.5), value: impact)
                }
                
                Circle()
                    .stroke(Color.white.opacity(0.05), lineWidth: 6)
                
                if !isIdle {
                    Circle()
                        .trim(from: 0.0, to: CGFloat(max(0.05, impact / 100.0)))
                        .stroke(
                            LinearGradient(colors: [Color.purple, Color.pink], startPoint: .topLeading, endPoint: .bottomTrailing),
                            style: StrokeStyle(lineWidth: 6, lineCap: .round)
                        )
                        .rotationEffect(.degrees(-90))
                        .animation(.easeInOut(duration: 0.5), value: impact)
                }
                
                VStack(spacing: 1) {
                    Text("GPU CORE")
                        .font(.system(size: 9, weight: .bold, design: .monospaced))
                        .foregroundColor(Color.purple.opacity(0.9))
                    
                    if isIdle {
                        Text("IDLE")
                            .font(.system(size: 11, weight: .bold, design: .monospaced))
                            .foregroundColor(.secondary)
                    } else if impact >= 99.5 {
                        Text("100%")
                            .font(.system(size: 13, weight: .bold, design: .monospaced))
                            .foregroundColor(.white)
                    } else {
                        let loss = Int(round(100.0 - impact))
                        Text("-\(loss)%")
                            .font(.system(size: 13, weight: .bold, design: .monospaced))
                            .foregroundColor(Color.purple)
                        Text("IMPACT")
                            .font(.system(size: 7, weight: .semibold, design: .monospaced))
                            .foregroundColor(.secondary)
                    }
                }
            }
            .frame(width: 72, height: 72)
        }
        .padding(.vertical, 10)
        .padding(.horizontal, 6)
        .background(
            RoundedRectangle(cornerRadius: 16)
                .fill(Color.purple.opacity(0.05))
                .overlay(
                    RoundedRectangle(cornerRadius: 16)
                        .stroke(Color.purple.opacity(0.25), lineWidth: 1)
                )
        )
    }
}

struct CoreStatusView: View {
    let coreImpacts: [Double]
    var gpuImpact: Double? = 0.0
    
    // Grid configuration: adaptive columns to prevent overlapping on narrow screens
    private let columns = [
        GridItem(.adaptive(minimum: 80), spacing: 10)
    ]
    
    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            HStack {
                Image(systemName: "cpu")
                    .font(.system(size: 14, weight: .semibold))
                    .foregroundColor(.secondary)
                Text("HARDWARE RELATIVE IMPACT")
                    .font(.system(size: 12, weight: .bold, design: .monospaced))
                    .foregroundColor(.secondary)
                Spacer()
            }
            .padding(.horizontal, 4)
            
            LazyVGrid(columns: columns, spacing: 10) {
                if let gpu = gpuImpact {
                    GpuMeter(impact: gpu)
                }
                ForEach(0..<coreImpacts.count, id: \.self) { index in
                    CoreMeter(index: index + 1, impact: coreImpacts[index])
                }
            }
        }
        .padding(16)
        .background(
            RoundedRectangle(cornerRadius: 24)
                .fill(Color(white: 0.08).opacity(0.6))
                .overlay(
                    RoundedRectangle(cornerRadius: 24)
                        .stroke(Color.white.opacity(0.06), lineWidth: 1)
                )
        )
    }
}

struct CoreStatusView_Previews: PreviewProvider {
    static var previews: some View {
        ZStack {
            Color.black.ignoresSafeArea()
            CoreStatusView(coreImpacts: [100.0, 95.0, 85.0, 70.0, 100.0, 100.0], gpuImpact: 100.0)
                .padding()
        }
    }
}
