import SwiftUI

struct StabilityChart: View {
    let points: [StabilityPoint]
    let events: [ThermalEvent]
    
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
            // Chart Title
            HStack {
                Image(systemName: "chart.xyaxis.line")
                    .font(.system(size: 14, weight: .semibold))
                    .foregroundColor(.secondary)
                Text("UNIFIED STABILITY CHART")
                    .font(.system(size: 12, weight: .bold, design: .monospaced))
                    .foregroundColor(.secondary)
                Spacer()
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
                            // Vertical dotted marker line
                            Path { path in
                                path.move(to: CGPoint(x: xPos, y: 15))
                                path.addLine(to: CGPoint(x: xPos, y: height))
                            }
                            .stroke(event.color.opacity(0.5), style: StrokeStyle(lineWidth: 1, dash: [4, 4]))
                            
                            // Annotated Badge Bubble
                            VStack(spacing: 2) {
                                Image(systemName: event.iconName)
                                    .font(.system(size: 9, weight: .bold))
                                    .foregroundColor(.white)
                                    .frame(width: 18, height: 18)
                                    .background(
                                        Circle()
                                            .fill(event.color)
                                            .shadow(color: event.color.opacity(0.4), radius: 4)
                                    )
                                
                                Text(event.name)
                                    .font(.system(size: 6, weight: .bold, design: .monospaced))
                                    .foregroundColor(event.color)
                                    .padding(.horizontal, 4)
                                    .padding(.vertical, 1)
                                    .background(Color.black.opacity(0.7))
                                    .cornerRadius(4)
                            }
                            .position(x: xPos, y: 20)
                        }
                    }
                }
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
    
    // Grid Lines Drawer
    @ViewBuilder
    private func gridLines(width: CGFloat, height: CGFloat) -> some View {
        ZStack {
            // Horizontal planes (Y-Axis references: 100%, 75%, 50%, 25%)
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
    
    // Helper to calculate X coordinate for a timestamp
    private func getXPosition(for time: TimeInterval, width: CGFloat) -> CGFloat? {
        guard let first = points.first else { return nil }
        let lastTime = max(1.0, points.last?.time ?? 1.0)
        let totalRange = lastTime - first.time
        guard totalRange > 0 else { return 0 }
        
        let fraction = (time - first.time) / totalRange
        return CGFloat(fraction) * width
    }
    
    // Smooth Bezier Curve Path Builder
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
            
            // Safety bound coordinates
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
            
            // Cubic bezier smoothing
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
    
    // Format Time Interval to m:ss
    private func formatTime(_ time: TimeInterval) -> String {
        let minutes = Int(time) / 60
        let seconds = Int(time) % 60
        return String(format: "%dm:%02ds", minutes, seconds)
    }
}

struct StabilityChart_Previews: PreviewProvider {
    static var previews: some View {
        ZStack {
            Color.black.ignoresSafeArea()
            StabilityChart(
                points: [
                    StabilityPoint(time: 0, score: 100),
                    StabilityPoint(time: 10, score: 100),
                    StabilityPoint(time: 20, score: 95),
                    StabilityPoint(time: 30, score: 92),
                    StabilityPoint(time: 40, score: 85),
                    StabilityPoint(time: 50, score: 75),
                    StabilityPoint(time: 60, score: 78)
                ],
                events: [
                    ThermalEvent(time: 20, state: .fair),
                    ThermalEvent(time: 40, state: .serious)
                ]
            )
            .padding()
        }
    }
}
