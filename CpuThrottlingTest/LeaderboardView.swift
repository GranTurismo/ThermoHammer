import SwiftUI

struct EntryWithStability: Identifiable {
    let id: Int
    let entry: HammerDto
    let stability: Double
    let rank: Int
}

struct LeaderboardView: View {
    @State private var entries: [HammerDto] = []
    @State private var isLoading = false
    @State private var errorMessage: String? = nil
    @State private var selectedEntry: HammerDto? = nil
    @State private var searchText = ""
    
    // Lazy stamps loading states
    @State private var detailedStamps: [DeviceHammerStamp] = []
    @State private var isStampsLoading = false
    @State private var stampsErrorMessage: String? = nil
    
    @StateObject private var networkMonitor = NetworkMonitor.shared
    
    var rankedEntries: [EntryWithStability] {
        let mapped = entries.map { entry in
            EntryWithStability(
                id: entry.id,
                entry: entry,
                stability: Double(entry.stabilityPercentage),
                rank: 0
            )
        }
        
        let sorted = mapped.sorted { $0.stability > $1.stability }
        
        return sorted.enumerated().map { index, element in
            EntryWithStability(
                id: element.id,
                entry: element.entry,
                stability: element.stability,
                rank: index + 1
            )
        }
    }
    
    var filteredEntries: [EntryWithStability] {
        let ranked = rankedEntries
        if searchText.isEmpty {
            return ranked
        } else {
            return ranked.filter { entry in
                entry.entry.deviceModel.localizedCaseInsensitiveContains(searchText) ||
                entry.entry.deviceManufacturer.localizedCaseInsensitiveContains(searchText) ||
                entry.entry.osVersion.localizedCaseInsensitiveContains(searchText)
            }
        }
    }
    
    var body: some View {
        ZStack {
            VStack(spacing: 0) {
                // Header
                HStack {
                    VStack(alignment: .leading, spacing: 4) {
                        Text("GLOBAL LEADERBOARD")
                            .font(.system(size: 20, weight: .black, design: .monospaced))
                            .tracking(1.5)
                            .foregroundColor(.white)
                        
                        Text("Sustained CPU Performance Stability Rankings")
                            .font(.system(size: 10, weight: .bold, design: .monospaced))
                            .foregroundColor(.secondary)
                    }
                    Spacer()
                    
                    Button(action: {
                        Task { await loadData() }
                    }) {
                        Image(systemName: "arrow.clockwise")
                            .font(.system(size: 14, weight: .bold))
                            .foregroundColor(.white)
                            .padding(10)
                            .background(Color.white.opacity(0.08))
                            .clipShape(Circle())
                    }
                    .disabled(isLoading)
                }
                .padding(.horizontal)
                .padding(.top, 16)
                .padding(.bottom, 12)
                
                if !networkMonitor.isConnected {
                    offlineView
                } else if isLoading {
                    loadingView
                } else if let error = errorMessage {
                    errorView(error)
                } else {
                    mainContentView
                }
            }
            
            // Detail Sheet Overlay
            if let entry = selectedEntry {
                detailOverlay(entry)
            }
        }
        .onAppear {
            Task { await loadData() }
        }
    }
    
    // Offline message
    private var offlineView: some View {
        VStack(spacing: 20) {
            Spacer()
            Image(systemName: "wifi.slash")
                .font(.system(size: 50))
                .foregroundColor(.secondary)
                .shadow(color: .black.opacity(0.3), radius: 5)
            
            Text("LEADERBOARD OFFLINE")
                .font(.system(size: 14, weight: .bold, design: .monospaced))
                .foregroundColor(.white)
            
            Text("Please connect to the internet to fetch and view global stability rankings.")
                .font(.system(size: 11, weight: .medium))
                .foregroundColor(.secondary)
                .multilineTextAlignment(.center)
                .padding(.horizontal, 40)
            
            Spacer()
        }
    }
    
    // Loading Screen
    private var loadingView: some View {
        VStack(spacing: 20) {
            Spacer()
            ProgressView()
                .progressViewStyle(CircularProgressViewStyle(tint: .blue))
                .scaleEffect(1.3)
            
            Text("FETCHING RANKINGS...")
                .font(.system(size: 11, weight: .bold, design: .monospaced))
                .foregroundColor(.secondary)
            Spacer()
        }
    }
    
    // Error screen
    private func errorView(_ msg: String) -> some View {
        VStack(spacing: 16) {
            Spacer()
            Image(systemName: "exclamationmark.octagon.fill")
                .font(.system(size: 40))
                .foregroundColor(.red.opacity(0.8))
            
            Text("COULD NOT LOAD LEADERBOARD")
                .font(.system(size: 12, weight: .bold, design: .monospaced))
                .foregroundColor(.white)
            
            Text(msg)
                .font(.system(size: 10))
                .foregroundColor(.secondary)
                .multilineTextAlignment(.center)
                .padding(.horizontal, 32)
            
            Button(action: {
                Task { await loadData() }
            }) {
                Text("RETRY")
                    .font(.system(size: 11, weight: .bold, design: .monospaced))
                    .foregroundColor(.black)
                    .padding(.horizontal, 24)
                    .padding(.vertical, 8)
                    .background(Color.white)
                    .cornerRadius(8)
            }
            Spacer()
        }
    }
    
    // Main scroll list
    private var mainContentView: some View {
        VStack(spacing: 12) {
            // Search field
            HStack {
                Image(systemName: "magnifyingglass")
                    .foregroundColor(.secondary)
                    .font(.system(size: 14))
                
                TextField("Search model, vendor, or OS...", text: $searchText)
                    .font(.system(size: 12, design: .monospaced))
                    .foregroundColor(.white)
                    .disableAutocorrection(true)
            }
            .padding(.horizontal, 12)
            .padding(.vertical, 10)
            .background(Color.white.opacity(0.04))
            .cornerRadius(12)
            .overlay(
                RoundedRectangle(cornerRadius: 12)
                    .stroke(Color.white.opacity(0.08), lineWidth: 1)
            )
            .padding(.horizontal)
            
            if filteredEntries.isEmpty {
                VStack(spacing: 10) {
                    Spacer()
                    Text("NO ENTRIES FOUND")
                        .font(.system(size: 11, weight: .bold, design: .monospaced))
                        .foregroundColor(.secondary)
                    Spacer()
                }
            } else {
                ScrollView(.vertical, showsIndicators: false) {
                    LazyVStack(spacing: 10) {
                        ForEach(filteredEntries) { entryWithStability in
                            leaderboardRow(entryWithStability)
                        }
                    }
                    .padding(.horizontal)
                    .padding(.bottom, 24)
                }
            }
        }
    }
    
    // Row Item View
    private func leaderboardRow(_ item: EntryWithStability) -> some View {
        Button(action: {
            withAnimation(.spring(response: 0.35, dampingFraction: 0.85)) {
                selectedEntry = item.entry
            }
            detailedStamps = []
            stampsErrorMessage = nil
            isStampsLoading = true
            
            let entryId = item.entry.id
            Task {
                do {
                    let fetchedStamps = try await LeaderboardService.shared.fetchStamps(for: entryId)
                    await MainActor.run {
                        detailedStamps = fetchedStamps
                        isStampsLoading = false
                    }
                } catch {
                    await MainActor.run {
                        stampsErrorMessage = error.localizedDescription
                        isStampsLoading = false
                    }
                }
            }
        }) {
            HStack(spacing: 16) {
                // Rank Number / Badge
                ZStack {
                    if item.rank == 1 {
                        Circle()
                            .fill(LinearGradient(colors: [Color(red: 0.98, green: 0.8, blue: 0.2), Color(red: 0.9, green: 0.65, blue: 0.0)], startPoint: .top, endPoint: .bottom))
                            .frame(width: 32, height: 32)
                            .shadow(color: Color.yellow.opacity(0.3), radius: 4)
                        Image(systemName: "crown.fill")
                            .font(.system(size: 10))
                            .foregroundColor(.black)
                            .offset(y: -5)
                        Text("1")
                            .font(.system(size: 10, weight: .black, design: .monospaced))
                            .foregroundColor(.black)
                            .offset(y: 4)
                    } else if item.rank == 2 {
                        Circle()
                            .fill(LinearGradient(colors: [Color(red: 0.85, green: 0.85, blue: 0.85), Color(red: 0.65, green: 0.65, blue: 0.65)], startPoint: .top, endPoint: .bottom))
                            .frame(width: 32, height: 32)
                        Text("2")
                            .font(.system(size: 12, weight: .black, design: .monospaced))
                            .foregroundColor(.black)
                    } else if item.rank == 3 {
                        Circle()
                            .fill(LinearGradient(colors: [Color(red: 0.8, green: 0.5, blue: 0.3), Color(red: 0.6, green: 0.35, blue: 0.2)], startPoint: .top, endPoint: .bottom))
                            .frame(width: 32, height: 32)
                        Text("3")
                            .font(.system(size: 12, weight: .black, design: .monospaced))
                            .foregroundColor(.black)
                    } else {
                        RoundedRectangle(cornerRadius: 8)
                            .fill(Color.white.opacity(0.04))
                            .frame(width: 32, height: 32)
                        Text("#\(item.rank)")
                            .font(.system(size: 10, weight: .bold, design: .monospaced))
                            .foregroundColor(.secondary)
                    }
                }
                
                // Device Info
                VStack(alignment: .leading, spacing: 4) {
                    Text(item.entry.deviceModel)
                        .font(.system(size: 13, weight: .bold, design: .monospaced))
                        .foregroundColor(.white)
                        .lineLimit(1)
                    
                    HStack(spacing: 6) {
                        Text(item.entry.deviceManufacturer)
                            .font(.system(size: 9, weight: .bold, design: .monospaced))
                            .foregroundColor(.secondary)
                        
                        Circle()
                            .fill(Color.white.opacity(0.15))
                            .frame(width: 3, height: 3)
                        
                        Text(item.entry.os == 1 ? "iOS \(item.entry.osVersion)" : "Android \(item.entry.osVersion)")
                            .font(.system(size: 9, weight: .medium, design: .monospaced))
                            .foregroundColor(.secondary)
                        
                        Circle()
                            .fill(Color.white.opacity(0.15))
                            .frame(width: 3, height: 3)
                        
                        Text(durationName(for: item.entry.type))
                            .font(.system(size: 9, weight: .bold, design: .monospaced))
                            .foregroundColor(item.entry.type == 0 ? .green : (item.entry.type == 1 ? .blue : .purple))
                    }
                }
                
                Spacer()
                
                // Stability Score
                VStack(alignment: .trailing, spacing: 2) {
                    Text(String(format: "%.1f%%", item.stability))
                        .font(.system(size: 14, weight: .black, design: .monospaced))
                        .foregroundColor(stabilityColor(for: item.stability))
                    
                    Text("STABILITY")
                        .font(.system(size: 7, weight: .bold, design: .monospaced))
                        .foregroundColor(.secondary)
                }
            }
            .padding(14)
            .background(Color.white.opacity(0.03))
            .cornerRadius(16)
            .overlay(
                RoundedRectangle(cornerRadius: 16)
                    .stroke(Color.white.opacity(0.06), lineWidth: 1)
            )
        }
        .buttonStyle(PlainButtonStyle())
    }
    
    // Fetch data from service
    private func loadData() async {
        await MainActor.run {
            isLoading = true
            errorMessage = nil
        }
        
        do {
            let res = try await LeaderboardService.shared.fetchLeaderboard()
            await MainActor.run {
                entries = res
                isLoading = false
            }
        } catch {
            await MainActor.run {
                errorMessage = error.localizedDescription
                isLoading = false
            }
        }
    }
    
    // Device detail popup sheet
    private func detailOverlay(_ entry: HammerDto) -> some View {
        let stabilityVal = Double(entry.stabilityPercentage)
        
        return ZStack {
            Color.black.opacity(0.85)
                .ignoresSafeArea()
                .onTapGesture {
                    withAnimation(.spring(response: 0.35, dampingFraction: 0.85)) {
                        selectedEntry = nil
                    }
                }
            
            VStack(spacing: 20) {
                // Header details
                HStack {
                    VStack(alignment: .leading, spacing: 4) {
                        Text(entry.deviceModel)
                            .font(.system(size: 16, weight: .black, design: .monospaced))
                            .foregroundColor(.white)
                        
                        Text("\(entry.deviceManufacturer) • \(entry.os == 1 ? "iOS" : "Android") \(entry.osVersion)")
                            .font(.system(size: 10, weight: .bold, design: .monospaced))
                            .foregroundColor(.secondary)
                    }
                    Spacer()
                    
                    Button(action: {
                        withAnimation(.spring(response: 0.35, dampingFraction: 0.85)) {
                            selectedEntry = nil
                        }
                    }) {
                        Image(systemName: "xmark")
                            .font(.system(size: 12, weight: .black))
                            .foregroundColor(.white)
                            .padding(8)
                            .background(Color.white.opacity(0.08))
                            .clipShape(Circle())
                    }
                }
                .padding(.top, 10)
                
                Divider()
                    .background(Color.white.opacity(0.1))
                
                ScrollView(showsIndicators: false) {
                    VStack(spacing: 20) {
                        if isStampsLoading {
                            VStack(spacing: 16) {
                                ProgressView()
                                    .progressViewStyle(CircularProgressViewStyle(tint: .blue))
                                    .scaleEffect(1.2)
                                Text("LOADING STAMP DATA...")
                                    .font(.system(size: 10, weight: .bold, design: .monospaced))
                                    .foregroundColor(.secondary)
                            }
                            .frame(height: 320)
                            .frame(maxWidth: .infinity)
                        } else if let errorMsg = stampsErrorMessage {
                            VStack(spacing: 16) {
                                Image(systemName: "exclamationmark.triangle.fill")
                                    .font(.system(size: 32))
                                    .foregroundColor(.orange)
                                Text("FAILED TO LOAD STAMPS")
                                    .font(.system(size: 11, weight: .bold, design: .monospaced))
                                    .foregroundColor(.white)
                                Text(errorMsg)
                                    .font(.system(size: 9))
                                    .foregroundColor(.secondary)
                                    .multilineTextAlignment(.center)
                                    .padding(.horizontal)
                                
                                Button(action: {
                                    stampsErrorMessage = nil
                                    isStampsLoading = true
                                    let entryId = entry.id
                                    Task {
                                        do {
                                            let fetchedStamps = try await LeaderboardService.shared.fetchStamps(for: entryId)
                                            await MainActor.run {
                                                detailedStamps = fetchedStamps
                                                isStampsLoading = false
                                            }
                                        } catch {
                                            await MainActor.run {
                                                stampsErrorMessage = error.localizedDescription
                                                isStampsLoading = false
                                            }
                                        }
                                    }
                                }) {
                                    Text("RETRY")
                                        .font(.system(size: 10, weight: .bold, design: .monospaced))
                                        .foregroundColor(.black)
                                        .padding(.horizontal, 16)
                                        .padding(.vertical, 6)
                                        .background(Color.white)
                                        .cornerRadius(6)
                                }
                            }
                            .frame(height: 320)
                            .frame(maxWidth: .infinity)
                        } else {
                            // Stamps are loaded successfully!
                            stampsDetailCard(entry: entry, stamps: detailedStamps, stabilityVal: stabilityVal)
                        }
                    }
                }
            }
            .padding(24)
            .frame(width: UIScreen.main.bounds.width * 0.92, height: UIScreen.main.bounds.height * 0.92)
            .background(
                RoundedRectangle(cornerRadius: 24)
                    .fill(Color(white: 0.1))
                    .overlay(
                        RoundedRectangle(cornerRadius: 24)
                            .stroke(Color.white.opacity(0.1), lineWidth: 1)
                    )
            )
            .shadow(color: .black, radius: 15)
        }
    }
    
    private func getChartPoints(from stamps: [DeviceHammerStamp], maxScore: Double) -> [StabilityPoint] {
        return stamps.map { stamp in
            StabilityPoint(
                time: TimeInterval(stamp.elapsedMs) / 1000.0,
                score: (Double(stamp.score) / maxScore) * 100.0
            )
        }
    }
    
    private func getThermalEvents(from stamps: [DeviceHammerStamp]) -> [ThermalEvent] {
        var chartEvents: [ThermalEvent] = []
        var lastState: Int? = nil
        for stamp in stamps {
            if stamp.thermalState != lastState {
                let state: ProcessInfo.ThermalState
                switch stamp.thermalState {
                case 0: state = .nominal
                case 1: state = .fair
                case 2: state = .serious
                case 3: state = .critical
                default: state = .nominal
                }
                chartEvents.append(ThermalEvent(time: TimeInterval(stamp.elapsedMs) / 1000.0, state: state))
                lastState = stamp.thermalState
            }
        }
        return chartEvents
    }
    
    private func stampsDetailCard(entry: HammerDto, stamps: [DeviceHammerStamp], stabilityVal: Double) -> some View {
        let scores = stamps.map { Double($0.score) }
        let maxScore = scores.max() ?? 1.0
        let minScore = scores.min() ?? 0.0
        let calculatedStability = maxScore > 0 ? (minScore / maxScore) * 100.0 : stabilityVal
        
        let chartPoints = getChartPoints(from: stamps, maxScore: maxScore)
        let chartEvents = getThermalEvents(from: stamps)
        
        return VStack(spacing: 20) {
            // Numerical Metrics Card
            VStack(spacing: 12) {
                detailRow(label: "TEST TYPE", value: durationName(for: entry.type))
                detailRow(label: "STABILITY", value: String(format: "%.1f%%", calculatedStability), valColor: stabilityColor(for: calculatedStability))
                detailRow(label: "PEAK SCORE (IPS)", value: formatInteger(Int(maxScore)))
                detailRow(label: "MIN SCORE (IPS)", value: formatInteger(Int(minScore)))
                detailRow(label: "SAMPLES RECORDED", value: String(stamps.count))
            }
            .padding(14)
            .background(Color.white.opacity(0.03))
            .cornerRadius(16)
            
            Divider()
                .background(Color.white.opacity(0.1))
            
            // Graph visual
            VStack(alignment: .leading, spacing: 8) {
                Text("PERFORMANCE CURVE")
                    .font(.system(size: 10, weight: .black, design: .monospaced))
                    .foregroundColor(.secondary)
                    .padding(.horizontal, 4)
                
                StabilityChart(points: chartPoints, events: chartEvents)
                    .frame(height: 280)
                    .padding(8)
                    .background(Color.black.opacity(0.2))
                    .cornerRadius(16)
                    .overlay(
                        RoundedRectangle(cornerRadius: 16)
                            .stroke(Color.white.opacity(0.04), lineWidth: 1)
                    )
            }
        }
    }
    
    private func detailRow(label: String, value: String, valColor: Color = .white) -> some View {
        HStack {
            Text(label)
                .font(.system(size: 10, weight: .bold, design: .monospaced))
                .foregroundColor(.secondary)
            Spacer()
            Text(value)
                .font(.system(size: 11, weight: .black, design: .monospaced))
                .foregroundColor(valColor)
        }
    }
    
    private func durationName(for type: Int) -> String {
        switch type {
        case 0: return "5 MIN"
        case 1: return "15 MIN"
        case 2: return "30 MIN"
        default: return "5 MIN"
        }
    }
    
    private func stabilityColor(for score: Double) -> Color {
        if score >= 90 {
            return Color(red: 0.2, green: 0.8, blue: 0.4)
        } else if score >= 75 {
            return Color(red: 0.95, green: 0.7, blue: 0.1)
        } else {
            return Color(red: 0.9, green: 0.35, blue: 0.1)
        }
    }
    
    private func formatInteger(_ val: Int) -> String {
        let formatter = NumberFormatter()
        formatter.numberStyle = .decimal
        return formatter.string(from: NSNumber(value: val)) ?? "\(val)"
    }
}
