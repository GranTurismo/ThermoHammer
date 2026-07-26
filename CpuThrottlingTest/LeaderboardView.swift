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

    // Multi-select comparison system
    @State private var selectedCompareRuns: [PendingTestResult] = []
    @State private var showAutoCompareSheet = false
    @State private var comparisonMismatchAlert: String? = nil
    
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
        
        return ranked.filter { item in
            let matchesSearch: Bool
            if searchText.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
                matchesSearch = true
            } else {
                let query = searchText.lowercased()
                let modelMatch = item.entry.deviceModel.lowercased().contains(query)
                let vendorMatch = item.entry.deviceManufacturer.lowercased().contains(query)
                let osMatch = item.entry.osVersion.lowercased().contains(query)
                matchesSearch = modelMatch || vendorMatch || osMatch
            }
            
            let matchesDevice = showOnlyMyDevice ? item.entry.deviceModel.caseInsensitiveCompare(myDeviceModel) == .orderedSame : true
            let matchesOS = showOnlyMyOSVersion ? item.entry.osVersion.contains(myOSVersion) : true
            
            let matchesPlatform: Bool
            switch selectedPlatformFilter {
            case 1: matchesPlatform = item.entry.os == 1
            case 2: matchesPlatform = item.entry.os == 2
            default: matchesPlatform = true
            }
            
            let matchesDuration: Bool
            switch selectedDurationFilter {
            case 0: matchesDuration = item.entry.type == 0
            case 1: matchesDuration = item.entry.type == 1
            case 2: matchesDuration = item.entry.type == 2
            default: matchesDuration = true
            }
            
            return matchesSearch && matchesDevice && matchesOS && matchesPlatform && matchesDuration
        }
    }

    private func toggleCompareRun(_ run: PendingTestResult) {
        if let idx = selectedCompareRuns.firstIndex(where: { $0.sessionId == run.sessionId && $0.deviceModel == run.deviceModel }) {
            selectedCompareRuns.remove(at: idx)
        } else {
            if let firstRun = selectedCompareRuns.first {
                let firstTT = firstRun.testThreadingType ?? 1
                let nextTT = run.testThreadingType ?? 1
                if firstTT != nextTT {
                    let modeA = firstTT == 0 ? "Single Thread" : "Multi Thread"
                    let modeB = nextTT == 0 ? "Single Thread" : "Multi Thread"
                    comparisonMismatchAlert = "Cannot compare \(modeA) with \(modeB) test! Please select matching test types."
                    return
                }
            }
            selectedCompareRuns.append(run)
            if selectedCompareRuns.count >= 2 {
                selectedCompareRuns = Array(selectedCompareRuns.prefix(2))
                showAutoCompareSheet = true
            }
        }
    }
    
    var body: some View {
        ZStack {
            Color.black.ignoresSafeArea()
            
            VStack(spacing: 0) {
                // Header Bar
                headerView
                
                // Main Content Body
                if isLoading {
                    loadingView
                } else if let msg = errorMessage {
                    errorView(msg: msg)
                } else {
                    mainContentView
                }
            }

            // Floating Sticky Comparison Selection Bar at Bottom
            if !selectedCompareRuns.isEmpty {
                let reqMode = (selectedCompareRuns.first?.testThreadingType ?? 1) == 0 ? "1 THREAD" : "MULTI THREAD"
                VStack {
                    Spacer()
                    HStack {
                        VStack(alignment: .leading, spacing: 2) {
                            Text("\(selectedCompareRuns.count) / 2 TESTS SELECTED (\(reqMode))")
                                .font(.system(size: 10, weight: .black, design: .monospaced))
                                .foregroundColor(.black)
                            Text(selectedCompareRuns.count == 1 ? "Tap 1 more \(reqMode) test to compare!" : "Tap to view detailed side-by-side analysis")
                                .font(.system(size: 9, design: .monospaced))
                                .foregroundColor(.black.opacity(0.7))
                        }
                        Spacer()
                        Button(action: { selectedCompareRuns.removeAll() }) {
                            Text("CLEAR")
                                .font(.system(size: 9, weight: .bold, design: .monospaced))
                                .foregroundColor(.black)
                                .padding(.horizontal, 10)
                                .padding(.vertical, 4)
                                .background(Color.black.opacity(0.2))
                                .cornerRadius(12)
                        }
                    }
                    .padding(14)
                    .background(Color(red: 0.0, green: 0.9, blue: 1.0))
                    .cornerRadius(16)
                    .shadow(color: .black.opacity(0.4), radius: 10)
                    .padding(16)
                    .onTapGesture {
                        if selectedCompareRuns.count >= 2 {
                            showAutoCompareSheet = true
                        }
                    }
                }
            }
            
            // Detail Modal Popup
            if let entry = selectedEntry {
                detailSheet(entry: entry)
            }
        }
        .sheet(isPresented: $showAutoCompareSheet, onDismiss: { selectedCompareRuns.removeAll() }) {
            if selectedCompareRuns.count >= 2 {
                ComparisonView(preselectedRunA: selectedCompareRuns[0], preselectedRunB: selectedCompareRuns[1])
            }
        }
        .alert(item: Binding<AlertItem?>(
            get: { comparisonMismatchAlert != nil ? AlertItem(message: comparisonMismatchAlert!) : nil },
            set: { _ in comparisonMismatchAlert = nil }
        )) { alert in
            Alert(title: Text("Comparison Mismatch"), message: Text(alert.message), dismissButton: .default(Text("OK")))
        }
        .onAppear {
            Task { await loadData() }
        }
    }

    struct AlertItem: Identifiable {
        let id = UUID()
        let message: String
    }
    
    // Header section view
    private var headerView: some View {
        HStack {
            VStack(alignment: .leading, spacing: 2) {
                Text("GLOBAL LEADERBOARD")
                    .font(.system(size: 18, weight: .black, design: .monospaced))
                    .tracking(1.5)
                    .foregroundColor(.white)
                Text("Select 2 Tests to Compare side-by-side")
                    .font(.system(size: 10, design: .monospaced))
                    .foregroundColor(.secondary)
            }
            Spacer()
            
            Button(action: {
                Task { await loadData() }
            }) {
                Image(systemName: "arrow.clockwise")
                    .font(.system(size: 14, weight: .bold))
                    .foregroundColor(.white)
                    .padding(8)
                    .background(Color.white.opacity(0.08))
                    .clipShape(Circle())
            }
        }
        .padding(.horizontal)
        .padding(.top, 16)
        .padding(.bottom, 12)
    }
    
    // Loading View
    private var loadingView: some View {
        VStack(spacing: 12) {
            Spacer()
            ProgressView()
                .progressViewStyle(CircularProgressViewStyle(tint: .blue))
                .scaleEffect(1.2)
            Text("LOADING LEADERBOARD...")
                .font(.system(size: 11, weight: .bold, design: .monospaced))
                .foregroundColor(.secondary)
            Spacer()
        }
    }
    
    // Error View
    private func errorView(msg: String) -> some View {
        VStack(spacing: 12) {
            Spacer()
            Image(systemName: "wifi.exclamationmark")
                .font(.system(size: 32))
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
        let itemRun = item.entry.toPendingTestResult()
        let isSelected = selectedCompareRuns.contains(where: { $0.sessionId == itemRun.sessionId && $0.deviceModel == itemRun.deviceModel })
        let threadText = (item.entry.testThreadingType ?? 1) == 0 ? "1 THREAD" : "MULTI"
        let threadColor = (item.entry.testThreadingType ?? 1) == 0 ? Color.orange : Color.green
        
        return HStack(spacing: 10) {
            // Compare button toggle (ICON ONLY, NO TEXT)
            Button(action: { toggleCompareRun(itemRun) }) {
                Text(isSelected ? "✓" : "⚖️")
                    .font(.system(size: 12, weight: .bold, design: .monospaced))
                    .foregroundColor(isSelected ? .black : Color(red: 0.0, green: 0.9, blue: 1.0))
                    .padding(.horizontal, 10)
                    .padding(.vertical, 8)
                    .background(isSelected ? Color(red: 0.0, green: 0.9, blue: 1.0) : Color.white.opacity(0.05))
                    .cornerRadius(8)
                    .overlay(
                        RoundedRectangle(cornerRadius: 8)
                            .stroke(isSelected ? Color(red: 0.0, green: 0.9, blue: 1.0) : Color.white.opacity(0.15), lineWidth: 1)
                    )
            }
            .buttonStyle(PlainButtonStyle())

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
                HStack(spacing: 10) {
                    // Rank Number / Badge
                    ZStack {
                        if item.rank == 1 {
                            Circle()
                                .fill(LinearGradient(colors: [Color(red: 0.98, green: 0.8, blue: 0.2), Color(red: 0.9, green: 0.65, blue: 0.0)], startPoint: .top, endPoint: .bottom))
                                .frame(width: 28, height: 28)
                            Text("1")
                                .font(.system(size: 11, weight: .black, design: .monospaced))
                                .foregroundColor(.black)
                        } else if item.rank == 2 {
                            Circle()
                                .fill(LinearGradient(colors: [Color(red: 0.85, green: 0.85, blue: 0.85), Color(red: 0.65, green: 0.65, blue: 0.65)], startPoint: .top, endPoint: .bottom))
                                .frame(width: 28, height: 28)
                            Text("2")
                                .font(.system(size: 11, weight: .black, design: .monospaced))
                                .foregroundColor(.black)
                        } else if item.rank == 3 {
                            Circle()
                                .fill(LinearGradient(colors: [Color(red: 0.8, green: 0.5, blue: 0.3), Color(red: 0.6, green: 0.35, blue: 0.2)], startPoint: .top, endPoint: .bottom))
                                .frame(width: 28, height: 28)
                            Text("3")
                                .font(.system(size: 11, weight: .black, design: .monospaced))
                                .foregroundColor(.black)
                        } else {
                            RoundedRectangle(cornerRadius: 8)
                                .fill(Color.white.opacity(0.04))
                                .frame(width: 28, height: 28)
                            Text("#\(item.rank)")
                                .font(.system(size: 9, weight: .bold, design: .monospaced))
                                .foregroundColor(.secondary)
                        }
                    }
                    
                    // Device info & Thread Badge
                    VStack(alignment: .leading, spacing: 2) {
                        Text("\(item.entry.deviceManufacturer) \(item.entry.deviceModel)")
                            .font(.system(size: 12, weight: .bold, design: .monospaced))
                            .foregroundColor(.white)
                            .lineLimit(1)
                        
                        HStack(spacing: 4) {
                            Text("\(item.entry.os == 1 ? "iOS" : "Android") \(item.entry.osVersion)")
                                .font(.system(size: 8, design: .monospaced))
                                .foregroundColor(.secondary)
                            Text("•")
                                .font(.system(size: 8))
                                .foregroundColor(.secondary)
                            Text(threadText)
                                .font(.system(size: 8, weight: .bold, design: .monospaced))
                                .foregroundColor(threadColor)
                        }
                    }
                    
                    Spacer()
                    
                    // Stability score
                    VStack(alignment: .trailing, spacing: 2) {
                        Text(String(format: "%.1f%%", item.stability))
                            .font(.system(size: 13, weight: .black, design: .monospaced))
                            .foregroundColor(stabilityColor(for: item.stability))
                        Text("STABILITY")
                            .font(.system(size: 7, weight: .bold, design: .monospaced))
                            .foregroundColor(.secondary)
                    }
                }
            }
            .buttonStyle(PlainButtonStyle())
        }
        .padding(12)
        .background(Color(white: 0.08).opacity(0.6))
        .cornerRadius(16)
        .overlay(
            RoundedRectangle(cornerRadius: 16)
                .stroke(isSelected ? Color(red: 0.0, green: 0.9, blue: 1.0) : Color.white.opacity(0.06), lineWidth: 1)
        )
    }

    private func loadData() async {
        isLoading = true
        errorMessage = nil
        do {
            let items = try await LeaderboardService.shared.fetchLeaderboard()
            await MainActor.run {
                self.entries = items
                self.isLoading = false
            }
        } catch {
            await MainActor.run {
                self.errorMessage = error.localizedDescription
                self.isLoading = false
            }
        }
    }

    private func submitPendingRun(_ pending: PendingTestResult) {
        uploadingId = pending.id
        uploadError = nil
        Task {
            do {
                let session = try await LeaderboardService.shared.createSession()
                let hmacHash = ThermoHasher.computeHash(
                    encryptionKey: session.encryptionKey,
                    stamps: pending.stamps
                )
                let payload = HammerPayload(
                    stamps: pending.stamps,
                    type: pending.testDurationType,
                    testThreadingType: pending.testThreadingType ?? 1,
                    deviceManufacturer: pending.deviceManufacturer,
                    deviceModel: pending.deviceModel,
                    os: 1, // iOS
                    osVersion: pending.osVersion,
                    sessionId: session.id,
                    hash: hmacHash
                )
                try await LeaderboardService.shared.submitScore(payload: payload)
                await MainActor.run {
                    PendingResultStore.shared.deleteResult(id: pending.id)
                    uploadingId = nil
                }
            } catch {
                await MainActor.run {
                    uploadError = error.localizedDescription
                    uploadingId = nil
                }
            }
        }
    }
    
    private func detailSheet(entry: HammerDto) -> some View {
        let stabilityVal = Double(entry.stabilityPercentage)
        
        return ZStack {
            Color.black.opacity(0.85)
                .ignoresSafeArea()
                .onTapGesture {
                    withAnimation(.spring()) {
                        selectedEntry = nil
                    }
                }
            
            VStack(spacing: 16) {
                // Header title
                HStack {
                    VStack(alignment: .leading, spacing: 2) {
                        Text("\(entry.deviceManufacturer) \(entry.deviceModel)")
                            .font(.system(size: 16, weight: .black, design: .monospaced))
                            .foregroundColor(.white)
                        Text("\(entry.deviceManufacturer) • \(entry.os == 1 ? "iOS" : "Android") \(entry.osVersion)")
                            .font(.system(size: 10, design: .monospaced))
                            .foregroundColor(.secondary)
                    }
                    Spacer()
                    Button(action: {
                        withAnimation(.spring()) {
                            selectedEntry = nil
                        }
                    }) {
                        Image(systemName: "xmark")
                            .font(.system(size: 12, weight: .bold))
                            .foregroundColor(.white)
                            .frame(width: 32, height: 32)
                            .background(Color.white.opacity(0.08))
                            .clipShape(Circle())
                    }
                }
                
                Divider()
                    .background(Color.white.opacity(0.1))
                
                ScrollView(.vertical, showsIndicators: false) {
                    VStack(spacing: 16) {
                        if isStampsLoading {
                            VStack(spacing: 12) {
                                ProgressView()
                                    .progressViewStyle(CircularProgressViewStyle(tint: .blue))
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
                
                Button(action: {
                    toggleCompareRun(entry.toPendingTestResult(stampsList: stamps))
                }) {
                    HStack {
                        Spacer()
                        Text("⚖️ ADD TO COMPARISON")
                            .font(.system(size: 11, weight: .bold, design: .monospaced))
                            .foregroundColor(Color(red: 0.0, green: 0.9, blue: 1.0))
                        Spacer()
                    }
                    .padding(.vertical, 12)
                    .background(Color(red: 0.0, green: 0.9, blue: 1.0).opacity(0.12))
                    .cornerRadius(14)
                    .overlay(RoundedRectangle(cornerRadius: 14).stroke(Color(red: 0.0, green: 0.9, blue: 1.0).opacity(0.3), lineWidth: 1))
                }
            }
        }
    }
    
    @ViewBuilder
    private var pendingSection: some View {
        if !pendingStore.results.isEmpty {
            VStack(alignment: .leading, spacing: 8) {
                Text("PENDING OFFLINE RUNS (\(pendingStore.results.count))")
                    .font(.system(size: 11, weight: .black, design: .monospaced))
                    .foregroundColor(Color(red: 0.95, green: 0.7, blue: 0.1))
                    .padding(.vertical, 4)
                
                ForEach(pendingStore.results) { pending in
                    let isSelected = selectedCompareRuns.contains(where: { $0.id == pending.id })
                    let threadText = (pending.testThreadingType ?? 1) == 0 ? "1 THREAD" : "MULTI"
                    let threadColor = (pending.testThreadingType ?? 1) == 0 ? Color.orange : Color.green
                    
                    VStack(alignment: .leading, spacing: 10) {
                        HStack {
                            VStack(alignment: .leading, spacing: 2) {
                                HStack(spacing: 6) {
                                    Text("OFFLINE RUN")
                                        .font(.system(size: 9, weight: .black, design: .monospaced))
                                        .foregroundColor(Color(red: 0.95, green: 0.7, blue: 0.1))
                                    Text("•")
                                        .font(.system(size: 9))
                                        .foregroundColor(.secondary)
                                    Text(durationName(for: pending.testDurationType))
                                        .font(.system(size: 9, weight: .bold, design: .monospaced))
                                        .foregroundColor(.secondary)
                                    Text("•")
                                        .font(.system(size: 9))
                                        .foregroundColor(.secondary)
                                    Text(threadText)
                                        .font(.system(size: 9, weight: .bold, design: .monospaced))
                                        .foregroundColor(threadColor)
                                }
                                Text("\(pending.deviceManufacturer) \(pending.deviceModel)")
                                    .font(.system(size: 13, weight: .bold, design: .monospaced))
                                    .foregroundColor(.white)
                            }
                            Spacer()
                            VStack(alignment: .trailing, spacing: 2) {
                                Text(String(format: "%.1f%%", pending.finalStability))
                                    .font(.system(size: 14, weight: .black, design: .monospaced))
                                    .foregroundColor(stabilityColor(for: pending.finalStability))
                                Text("STABILITY")
                                    .font(.system(size: 7, weight: .bold, design: .monospaced))
                                    .foregroundColor(.secondary)
                            }
                        }
                        
                        HStack(spacing: 8) {
                            // Compare button toggle (ICON ONLY, NO TEXT)
                            Button(action: { toggleCompareRun(pending) }) {
                                Text(isSelected ? "✓" : "⚖️")
                                    .font(.system(size: 11, weight: .bold, design: .monospaced))
                                    .foregroundColor(isSelected ? .black : Color(red: 0.0, green: 0.9, blue: 1.0))
                                    .padding(.horizontal, 10)
                                    .padding(.vertical, 6)
                                    .background(isSelected ? Color(red: 0.0, green: 0.9, blue: 1.0) : Color.white.opacity(0.08))
                                    .cornerRadius(8)
                                    .overlay(
                                        RoundedRectangle(cornerRadius: 8)
                                            .stroke(isSelected ? Color(red: 0.0, green: 0.9, blue: 1.0) : Color.white.opacity(0.15), lineWidth: 1)
                                    )
                            }
                            
                            if networkMonitor.isConnected {
                                Button(action: { submitPendingRun(pending) }) {
                                    Text("SUBMIT")
                                        .font(.system(size: 9, weight: .bold, design: .monospaced))
                                        .foregroundColor(.white)
                                        .padding(.horizontal, 10)
                                        .padding(.vertical, 6)
                                        .background(Color.blue)
                                        .cornerRadius(8)
                                }
                            }
                            
                            Spacer()
                            
                            Button(action: {
                                PendingResultStore.shared.deleteResult(id: pending.id)
                            }) {
                                Text("DELETE")
                                    .font(.system(size: 9, weight: .bold, design: .monospaced))
                                    .foregroundColor(.red)
                            }
                        }
                    }
                    .padding(14)
                    .background(Color(red: 0.95, green: 0.7, blue: 0.1).opacity(0.06))
                    .cornerRadius(16)
                    .overlay(
                        RoundedRectangle(cornerRadius: 16)
                            .stroke(Color(red: 0.95, green: 0.7, blue: 0.1).opacity(0.2), lineWidth: 1)
                    )
                }
                
                Divider()
                    .background(Color.white.opacity(0.08))
                    .padding(.vertical, 8)
            }
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
    
    private func detailRow(label: String, value: String, valColor: Color = .white) -> some View {
        HStack {
            Text(label)
                .font(.system(size: 10, weight: .bold, design: .monospaced))
                .foregroundColor(.secondary)
            Spacer()
            Text(value)
                .font(.system(size: 12, weight: .bold, design: .monospaced))
                .foregroundColor(valColor)
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
    
    private func formatInteger(_ number: Int) -> String {
        let formatter = NumberFormatter()
        formatter.numberStyle = .decimal
        return formatter.string(from: NSNumber(value: number)) ?? "\(number)"
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
