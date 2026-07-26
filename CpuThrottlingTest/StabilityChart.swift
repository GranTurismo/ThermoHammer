import SwiftUI

struct StabilityChart: View {
    let points: [StabilityPoint]
    let events: [ThermalEvent]
    
    @State private var selectedPoint: StabilityPoint? = nil
    @State private var touchLocationX: CGFloat? = nil
    
    // Gradient definitions for the line and filled area
    private var chartGradient: LinearGradient {
        LinearGradient(
            stops: [
                .init(color: Color(red: 0.2, green: 0.8, blue: 0.4), location: 0.0),  // 100% - Green
                .init(color: Color(red: 0.95, green: 0.7, blue: 0.1), location: 0.25), // 75% - Amber
                .init(color: Color(red: 0.9, green: 0.35, blue: 0.1), location: 0.5),  // 50% - Deep Orange
                .init(color: Color(red: 0.85, green: 0.2, blue: 0.2), location: 1.0)   // 0% - Red
            ],
            startPoint: .top,
            endPoint: .bottom
        )
    }
    
    private var fillGradient: LinearGradient {
        LinearGradient(
            stops: [
                .init(color: Color(red: 0.2, green: 0.8, blue: 0.4).opacity(0.15), location: 0.0),
                .init(color: Color(red: 0.95, green: 0.7, blue: 0.1).opacity(0.1), location: 0.25),
                .init(color: Color(red: 0.9, green: 0.35, blue: 0.1).opacity(0.05), location: 0.5),
                .init(color: Color.clear, location: 1.0)
            ],
            startPoint: .top,
            endPoint: .bottom
        )
    }
    
    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            // Chart Title & Touch Info Header (Disappears when touch is released)
            HStack {
                Image(systemName: "chart.xyaxis.line")
                    .font(.system(size: 14, weight: .semibold))
                    .foregroundColor(.secondary)
                Text("UNIFIED STABILITY CHART")
                    .font(.system(size: 12, weight: .bold, design: .monospaced))
                    .foregroundColor(.secondary)
                Spacer()
                
                if touchLocationX != nil, let pt = selectedPoint {
                    HStack(spacing: 6) {
                        Text(formatTime(pt.time))
                            .font(.system(size: 10, weight: .bold, design: .monospaced))
                            .foregroundColor(Color(red: 0.0, green: 0.9, blue: 1.0))
                        Text("SCORE: \(String(format: "%.1f", pt.score))%")
                            .font(.system(size: 10, weight: .black, design: .monospaced))
                            .foregroundColor(pt.score >= 90 ? Color.green : (pt.score >= 75 ? Color.orange : Color.red))
                    }
                    .padding(.horizontal, 8)
                    .padding(.vertical, 3)
                    .background(Color(red: 0.0, green: 0.9, blue: 1.0).opacity(0.15))
                    .cornerRadius(8)
                    .overlay(
                        RoundedRectangle(cornerRadius: 8)
                            .stroke(Color(red: 0.0, green: 0.9, blue: 1.0).opacity(0.3), lineWidth: 1)
                    )
                }
            }
            .padding(.horizontal, 4)
            
            GeometryReader { geometry in
                let width = geometry.size.width
                let height = geometry.size.height
                
                ZStack(alignment: .topLeading) {
                    // 1. Minimal Grid Planes
                    gridLines(width: width, height: height)
                    
                    // 2. Line Chart & Area Fill
                    if points.count > 0 {
                        // Area fill
                        chartPath(in: geometry.size, isFilled: true)
                            .fill(fillGradient)
                        
                        // Bezier path line
                        chartPath(in: geometry.size, isFilled: false)
                            .stroke(
                                chartGradient,
                                style: StrokeStyle(lineWidth: 3.5, lineCap: .round, lineJoin: .round)
                            )
                            .shadow(color: Color.black.opacity(0.3), radius: 3, x: 0, y: 2)
                    }
                    
                    // 3. Thermal Event Annotations
                    ForEach(events) { event in
                        if let xPos = getXPosition(for: event.time, width: width) {
                            Path { path in
                                path.move(to: CGPoint(x: xPos, y: 15))
                                path.addLine(to: CGPoint(x: xPos, y: height))
                            }
                            .stroke(event.color.opacity(0.5), style: StrokeStyle(lineWidth: 1, dash: [4, 4]))
                        }
                    }
                    
                    // 4. Interactive Touch Selector Line & Node (Only visible while touching)
                    if let tx = touchLocationX, points.count > 0 {
                        let nearest = findNearestPoint(x: tx, width: width)
                        if let targetPt = nearest, let targetX = getXPosition(for: targetPt.time, width: width) {
                            let targetY = height - (CGFloat(targetPt.score / 100.0) * height)
                            
                            // Dotted selector line
                            Path { path in
                                path.move(to: CGPoint(x: targetX, y: 0))
                                path.addLine(to: CGPoint(x: targetX, y: height))
                            }
                            .stroke(Color(red: 0.0, green: 0.9, blue: 1.0).opacity(0.7), style: StrokeStyle(lineWidth: 1.5, dash: [6, 4]))
                            
                            // Outer aura node
                            Circle()
                                .fill(Color(red: 0.0, green: 0.9, blue: 1.0).opacity(0.25))
                                .frame(width: 20, height: 20)
                                .position(x: targetX, y: targetY)
                            
                            // Inner node
                            Circle()
                                .fill(Color(red: 0.0, green: 0.9, blue: 1.0))
                                .frame(width: 8, height: 8)
                                .position(x: targetX, y: targetY)
                        }
                    }
                }
                .contentShape(Rectangle())
                .gesture(
                    DragGesture(minimumDistance: 0)
                        .onChanged { value in
                            let locX = min(width, max(0, value.location.x))
                            touchLocationX = locX
                            selectedPoint = findNearestPoint(x: locX, width: width)
                        }
                        .onEnded { _ in
                            touchLocationX = nil
                            selectedPoint = nil
                        }
                )
            }
            .frame(height: 180)
            
            // X-Axis Timeline Indicator
            HStack {
                Text("0m:00s")
                Spacer()
                if let lastPoint = points.last {
                    Text(formatTime(lastPoint.time))
                } else {
                    Text("0m:00s")
                }
            }
            .font(.system(size: 10, weight: .semibold, design: .monospaced))
            .foregroundColor(.secondary)
            .padding(.horizontal, 4)
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
    
    private func findNearestPoint(x: CGFloat, width: CGFloat) -> StabilityPoint? {
        guard points.count > 0 else { return nil }
        guard let first = points.first else { return nil }
        let lastTime = max(1.0, points.last?.time ?? 1.0)
        let totalRange = lastTime - first.time
        guard totalRange > 0 else { return first }
        
        let fraction = Double(x / width)
        let targetTime = first.time + fraction * totalRange
        return points.min(by: { abs($0.time - targetTime) < abs($1.time - targetTime) })
    }
    
    // Grid Lines Drawer
    @ViewBuilder
    private func gridLines(width: CGFloat, height: CGFloat) -> some View {
        ZStack {
            ForEach([0.0, 0.25, 0.5, 0.75, 1.0], id: \.self) { fraction in
                let y = height * CGFloat(fraction)
                
                if fraction > 0.0 && fraction < 1.0 {
                    Path { path in
                        path.move(to: CGPoint(x: 0, y: y))
                        path.addLine(to: CGPoint(x: width, y: y))
                    }
                    .stroke(Color.white.opacity(0.04), lineWidth: 1)
                }
                
                let scoreText = "\(Int(100 - (fraction * 100)))%"
                Text(scoreText)
                    .font(.system(size: 8, weight: .medium, design: .monospaced))
                    .foregroundColor(.secondary.opacity(0.7))
                    .position(x: 18, y: y - 6)
            }
        }
    }
    
    private func getXPosition(for time: TimeInterval, width: CGFloat) -> CGFloat? {
        guard let first = points.first else { return nil }
        let lastTime = max(1.0, points.last?.time ?? 1.0)
        let totalRange = lastTime - first.time
        guard totalRange > 0 else { return 0 }
        
        let fraction = (time - first.time) / totalRange
        return CGFloat(fraction) * width
    }
    
    private func chartPath(in size: CGSize, isFilled: Bool) -> Path {
        var path = Path()
        guard points.count > 0 else { return path }
        
        let first = points.first!
        let lastTime = max(1.0, points.last?.time ?? 1.0)
        let totalRange = lastTime - first.time
        
        func getCGPoint(for pt: StabilityPoint) -> CGPoint {
            let xFraction = totalRange == 0 ? 0.0 : (pt.time - first.time) / totalRange
            let yFraction = pt.score / 100.0
            
            let x = CGFloat(xFraction) * size.width
            let y = size.height - CGFloat(yFraction) * size.height
            
            return CGPoint(
                x: min(size.width, max(0, x)),
                y: min(size.height, max(0, y))
            )
        }
        
        if isFilled {
            path.move(to: CGPoint(x: 0, y: size.height))
        }
        
        let firstPoint = getCGPoint(for: first)
        if isFilled {
            path.addLine(to: firstPoint)
        } else {
            path.move(to: firstPoint)
        }
        
        if points.count == 1 {
            path.addLine(to: CGPoint(x: size.width, y: firstPoint.y))
            if isFilled {
                path.addLine(to: CGPoint(x: size.width, y: size.height))
                path.closeSubpath()
            }
            return path
        }
        
        for idx in 0..<(points.count - 1) {
            let p0 = getCGPoint(for: points[idx])
            let p1 = getCGPoint(for: points[idx + 1])
            
            let control1 = CGPoint(x: p0.x + (p1.x - p0.x) / 2.0, y: p0.y)
            let control2 = CGPoint(x: p0.x + (p1.x - p0.x) / 2.0, y: p1.y)
            
            path.addCurve(to: p1, control1: control1, control2: control2)
        }
        
        if isFilled {
            let finalPoint = getCGPoint(for: points.last!)
            path.addLine(to: CGPoint(x: finalPoint.x, y: size.height))
            path.closeSubpath()
        }
        
        return path
    }
    
    private func formatTime(_ time: TimeInterval) -> String {
        let minutes = Int(time) / 60
        let seconds = Int(time) % 60
        return String(format: "%dm:%02ds", minutes, seconds)
    }
}

// ── Dual Stability Chart View for Client-Side Run Comparison ───────────────

struct DualStabilityChart: View {
    let pointsA: [StabilityPoint]
    let labelA: String
    let pointsB: [StabilityPoint]
    let labelB: String
    
    @State private var touchLocationX: CGFloat? = nil
    
    let colorA = Color(red: 0.0, green: 0.9, blue: 1.0)
    let colorB = Color(red: 1.0, green: 0.67, blue: 0.0)
    
    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            HStack {
                Text("📊 DUAL STABILITY COMPARISON")
                    .font(.system(size: 11, weight: .bold, design: .monospaced))
                    .foregroundColor(.secondary)
                Spacer()
                
                HStack(spacing: 12) {
                    HStack(spacing: 4) {
                        Circle().fill(colorA).frame(width: 8, height: 8)
                        Text(labelA)
                            .font(.system(size: 9, weight: .bold, design: .monospaced))
                            .foregroundColor(colorA)
                    }
                    HStack(spacing: 4) {
                        Circle().fill(colorB).frame(width: 8, height: 8)
                        Text(labelB)
                            .font(.system(size: 9, weight: .bold, design: .monospaced))
                            .foregroundColor(colorB)
                    }
                }
            }
            
            GeometryReader { geometry in
                let width = geometry.size.width
                let height = geometry.size.height
                
                ZStack(alignment: .topLeading) {
                    // Grid lines
                    ForEach([0.25, 0.5, 0.75], id: \.self) { fraction in
                        let y = height * CGFloat(fraction)
                        Path { path in
                            path.move(to: CGPoint(x: 0, y: y))
                            path.addLine(to: CGPoint(x: width, y: y))
                        }
                        .stroke(Color.white.opacity(0.04), lineWidth: 1)
                    }
                    
                    let maxTimeA = pointsA.last?.time ?? 1.0
                    let maxTimeB = pointsB.last?.time ?? 1.0
                    let totalMax = max(1.0, max(maxTimeA, maxTimeB))
                    
                    // Curve A
                    drawCurve(points: pointsA, totalTime: totalMax, color: colorA, in: geometry.size)
                    // Curve B
                    drawCurve(points: pointsB, totalTime: totalMax, color: colorB, in: geometry.size)
                    
                    // Selector line (Only visible while touching)
                    if let tx = touchLocationX {
                        Path { path in
                            path.move(to: CGPoint(x: tx, y: 0))
                            path.addLine(to: CGPoint(x: tx, y: height))
                        }
                        .stroke(Color.white.opacity(0.5), style: StrokeStyle(lineWidth: 1.5, dash: [6, 4]))
                    }
                }
                .contentShape(Rectangle())
                .gesture(
                    DragGesture(minimumDistance: 0)
                        .onChanged { value in
                            let locX = min(width, max(0, value.location.x))
                            touchLocationX = locX
                        }
                        .onEnded { _ in
                            touchLocationX = nil
                        }
                )
            }
            .frame(height: 200)
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
    
    @ViewBuilder
    private func drawCurve(points: [StabilityPoint], totalTime: Double, color: Color, in size: CGSize) -> some View {
        if points.count > 0 {
            Path { path in
                let first = points.first!
                let getPoint: (StabilityPoint) -> CGPoint = { pt in
                    let x = CGFloat(pt.time / totalTime) * size.width
                    let y = size.height - CGFloat(pt.score / 100.0) * size.height
                    return CGPoint(x: min(size.width, max(0, x)), y: min(size.height, max(0, y)))
                }
                
                path.move(to: getPoint(first))
                for i in 0..<(points.count - 1) {
                    let p0 = getPoint(points[i])
                    let p1 = getPoint(points[i + 1])
                    let c1 = CGPoint(x: p0.x + (p1.x - p0.x) / 2.0, y: p0.y)
                    let c2 = CGPoint(x: p0.x + (p1.x - p0.x) / 2.0, y: p1.y)
                    path.addCurve(to: p1, control1: c1, control2: c2)
                }
            }
            .stroke(color, style: StrokeStyle(lineWidth: 3, lineCap: .round, lineJoin: .round))
        }
    }
}
