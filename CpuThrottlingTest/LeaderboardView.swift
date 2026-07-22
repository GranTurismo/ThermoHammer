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
    @ObservedObject private var pendingStore = PendingResultStore.shared
    @State private var uploadingId: UUID? = nil
    @State private var uploadError: String? = nil
    @State private var failedUploadId: UUID? = nil
    
    // Client-side Filters
    @State private var showOnlyMyDevice = false
    @State private var showOnlyMyOSVersion = false
    @State private var selectedPlatformFilter: Int? = nil // nil = All, 1 = iOS, 2 = Android
    @State private var selectedDurationFilter: Int? = nil // nil = All, 0 = 5 Min, 1 = 15 Min, 2 = 30 Min
    
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
        let myDeviceModel = LeaderboardService.shared.getDeviceModelName()
        let myOSVersion = UIDevice.current.systemVersion
        
        return ranked.filter { entry in
            let matchesSearch = searchText.isEmpty ||
                entry.entry.deviceModel.localizedCaseInsensitiveContains(searchText) ||
                entry.entry.deviceManufacturer.localizedCaseInsensitiveContains(searchText) ||
                entry.entry.osVersion.localizedCaseInsensitiveContains(searchText)
            
            let matchesDevice = !showOnlyMyDevice ||
                entry.entry.deviceModel.localizedCaseInsensitiveCompare(myDeviceModel) == .orderedSame
            
            let matchesOS = !showOnlyMyOSVersion ||
                entry.entry.osVersion.localizedCaseInsensitiveContains(myOSVersion)
            
            let matchesPlatform: Bool
            switch selectedPlatformFilter {
            case 1: matchesPlatform = entry.entry.os == 1
            case 2: matchesPlatform = entry.entry.os == 2
            default: matchesPlatform = true
            }
            
            let matchesDuration: Bool
            switch selectedDurationFilter {
            case 0: matchesDuration = entry.entry.type == 0
            case 1: matchesDuration = entry.entry.type == 1
            case 2: matchesDuration = entry.entry.type == 2
            default: matchesDuration = true
            }
            
            return matchesSearch && matchesDevice && matchesOS && matchesPlatform && matchesDuration
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
                    ScrollView(.vertical, showsIndicators: false) {
                        VStack(spacing: 16) {
                            pendingSection
                            offlineView
                        }
                    }
                } else if isLoading {
                    loadingView
                } else if let error = errorMessage {
                    ScrollView(.vertical, showsIndicators: false) {
                        VStack(spacing: 16) {
                            pendingSection
                            errorView(error)
                        }
                    }
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
            
            // Client-Side Filters Scroll Row
            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: 8) {
                    let myDeviceModel = LeaderboardService.shared.getDeviceModelName()
                    let myOSVersion = UIDevice.current.systemVersion
                    
                    customFilterChip(selected: showOnlyMyDevice, label: "Device: \(myDeviceModel)") {
                        showOnlyMyDevice.toggle()
                    }
                    
                    customFilterChip(selected: showOnlyMyOSVersion, label: "OS: v\(myOSVersion)") {
                        showOnlyMyOSVersion.toggle()
                    }
                    
                    customFilterChip(
                        selected: selectedPlatformFilter != nil,
                        label: {
                            switch selectedPlatformFilter {
                            case 1: return "Platform: iOS"
                            case 2: return "Platform: Android"
                            default: return "Platform: All"
                            }
                        }()
                    ) {
                        selectedPlatformFilter = {
                            switch selectedPlatformFilter {
                            case .none: return 1
                            case 1: return 2
                            default: return nil
                            }
                        }()
                    }
                    
                    customFilterChip(
                        selected: selectedDurationFilter != nil,
                        label: {
                            switch selectedDurationFilter {
                            case 0: return "Duration: 5 Min"
                            case 1: return "Duration: 15 Min"
                            case 2: return "Duration: 30 Min"
                            default: return "Duration: All"
                            }
                        }()
                    ) {
                        selectedDurationFilter = {
                            switch selectedDurationFilter {
                            case .none: return 0
                            case 0: return 1
                            case 1: return 2
                            default: return nil
                            }
                        }()
                    }
                }
                .padding(.horizontal)
            }
            .padding(.bottom, 4)
            
            ScrollView(.vertical, showsIndicators: false) {
                LazyVStack(spacing: 10) {
                    pendingSection
                    
                    HStack {
                        Text("ONLINE RANKINGS")
                            .font(.system(size: 11, weight: .bold, design: .monospaced))
                            .foregroundColor(.secondary)
                        Spacer()
                    }
                    .padding(.vertical, 4)
                    
                    if filteredEntries.isEmpty {
                        VStack(spacing: 10) {
                            Text("NO ENTRIES FOUND")
                                .font(.system(size: 11, weight: .bold, design: .monospaced))
                                .foregroundColor(.secondary)
                                .padding(.top, 40)
                        }
                    } else {
                        ForEach(filteredEntries) { entryWithStability in
                            leaderboardRow(entryWithStability)
                        }
                    }
                }
                .padding(.horizontal)
                .padding(.bottom, 24)
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
                        
                        Circle()
                            .fill(Color.white.opacity(0.15))
                            .frame(width: 3, height: 3)
                        
                        Text((item.entry.testThreadingType ?? 1) == 0 ? "1 THREAD" : "MULTI")
                            .font(.system(size: 9, weight: .bold, design: .monospaced))
                            .foregroundColor((item.entry.testThreadingType ?? 1) == 0 ? .orange : .secondary)
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
        
        let calculatedStability = stabilityVal
        
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
    
    @ViewBuilder
    private var pendingSection: some View {
        if !pendingStore.results.isEmpty {
            VStack(alignment: .leading, spacing: 8) {
                HStack {
                    Text("PENDING OFFLINE RUNS (\(pendingStore.results.count))")
                        .font(.system(size: 11, weight: .black, design: .monospaced))
                        .foregroundColor(Color(red: 0.95, green: 0.7, blue: 0.1))
                    Spacer()
                }
                .padding(.vertical, 4)
                
                ForEach(pendingStore.results) { pending in
                    pendingResultRow(pending)
                }
                
                Divider()
                    .background(Color.white.opacity(0.08))
                    .padding(.vertical, 8)
            }
        }
    }
    
    @ViewBuilder
    private func pendingResultRow(_ pending: PendingTestResult) -> some View {
        HStack(spacing: 12) {
            VStack(alignment: .leading, spacing: 4) {
                Text(pending.deviceModel)
                    .font(.system(size: 13, weight: .bold, design: .monospaced))
                    .foregroundColor(.white)
                    .lineLimit(1)
                
                HStack(spacing: 6) {
                    Text(durationName(for: pending.testDurationType))
                        .font(.system(size: 9, weight: .bold, design: .monospaced))
                        .foregroundColor(Color(red: 0.95, green: 0.7, blue: 0.1))
                    
                    Circle()
                        .frame(width: 3, height: 3)
                        .foregroundColor(.white.opacity(0.15))
                    
                    Text(String(format: "Score stability: %.0f%%", pending.finalStability))
                        .font(.system(size: 9))
                        .foregroundColor(.secondary)
                }
                
                if failedUploadId == pending.id, let error = uploadError {
                    Text("Error: \(error)")
                        .font(.system(size: 8, design: .monospaced))
                        .foregroundColor(.red)
                }
            }
            
            Spacer()
            
            HStack(spacing: 8) {
                // Delete button
                Button(action: {
                    pendingStore.deleteResult(id: pending.id)
                }) {
                    Text("✕")
                        .font(.system(size: 12, weight: .bold))
                        .foregroundColor(.white.opacity(0.4))
                        .padding(6)
                }
                
                // Upload button
                if uploadingId == pending.id {
                    ProgressView()
                        .progressViewStyle(CircularProgressViewStyle(tint: Color(red: 0.95, green: 0.7, blue: 0.1)))
                        .scaleEffect(0.8)
                } else {
                    Button(action: {
                        uploadingId = pending.id
                        failedUploadId = nil
                        uploadError = nil
                        Task {
                            do {
                                // Request a fresh session on the server for pending uploads to avoid expired sessions
                                let session = try await LeaderboardService.shared.createSession()
                                let finalSessionId = session.id
                                let finalEncryptionKey = session.encryptionKey
                                
                                let hmacHash = ThermoHasher.computeHash(
                                    encryptionKey: finalEncryptionKey,
                                    stamps: pending.stamps
                                )
                                let payload = HammerPayload(
                                    stamps: pending.stamps,
                                    type: pending.testDurationType,
                                    testThreadingType: pending.testThreadingType ?? 1,
                                    deviceManufacturer: pending.deviceManufacturer,
                                    deviceModel: pending.deviceModel,
                                    os: 1,
                                    osVersion: pending.osVersion,
                                    sessionId: finalSessionId,
                                    hash: hmacHash
                                )
                                try await LeaderboardService.shared.submitScore(payload: payload)
                                await MainActor.run {
                                    pendingStore.deleteResult(id: pending.id)
                                    uploadingId = nil
                                    Task { await loadData() }
                                }
                            } catch {
                                await MainActor.run {
                                    uploadError = error.localizedDescription
                                    failedUploadId = pending.id
                                    uploadingId = nil
                                }
                            }
                        }
                    }) {
                        Text(networkMonitor.isConnected ? "SUBMIT" : "OFFLINE")
                            .font(.system(size: 9, weight: .bold, design: .monospaced))
                            .foregroundColor(networkMonitor.isConnected ? .black : .white.opacity(0.4))
                            .padding(.horizontal, 10)
                            .padding(.vertical, 4)
                            .background(networkMonitor.isConnected ? Color(red: 0.95, green: 0.7, blue: 0.1) : Color.white.opacity(0.08))
                            .cornerRadius(8)
                    }
                    .disabled(!networkMonitor.isConnected)
                }
            }
        }
        .padding(14)
        .background(Color.white.opacity(0.03))
        .cornerRadius(16)
        .overlay(
            RoundedRectangle(cornerRadius: 16)
                .stroke(Color(red: 0.95, green: 0.7, blue: 0.1).opacity(0.25), lineWidth: 1)
        )
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
    private func customFilterChip(selected: Bool, label: String, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Text(label)
                .font(.system(size: 9, weight: .bold, design: .monospaced))
                .foregroundColor(selected ? Color(red: 0.95, green: 0.7, blue: 0.1) : .white.opacity(0.6))
                .padding(.horizontal, 12)
                .padding(.vertical, 6)
                .background(
                    RoundedRectangle(cornerRadius: 8)
                        .fill(selected ? Color(red: 0.95, green: 0.7, blue: 0.1).opacity(0.2) : Color.white.opacity(0.04))
                )
                .overlay(
                    RoundedRectangle(cornerRadius: 8)
                        .stroke(selected ? Color(red: 0.95, green: 0.7, blue: 0.1) : Color.white.opacity(0.08), lineWidth: 1)
                )
        }
    }
}
