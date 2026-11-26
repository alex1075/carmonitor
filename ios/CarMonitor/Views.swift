import SwiftUI
import CarMonitorKit

struct RootView: View {
    @EnvironmentObject var s: Session
    var body: some View {
        TabView {
            HomeView().tabItem { Label("Car", systemImage: "car") }
            SetupView().tabItem { Label("Setup", systemImage: "gearshape") }
            LogView().tabItem { Label("Log", systemImage: "list.bullet.rectangle") }
        }
    }
}

struct HomeView: View {
    @EnvironmentObject var s: Session
    @State private var uploading = false
    var body: some View {
        NavigationStack {
            List {
                Section {
                    HStack {
                        VStack(alignment: .leading) {
                            Text(s.settings.vehicleLabel.isEmpty ? "Generic OBD-II" : s.settings.vehicleLabel).font(.headline)
                            Text(s.state).font(.subheadline).foregroundStyle(.secondary)
                        }
                        Spacer()
                        Text(s.connected ? "Connected" : "Not connected")
                            .font(.caption).padding(.horizontal, 10).padding(.vertical, 6)
                            .background(s.connected ? Color.green.opacity(0.2) : Color.gray.opacity(0.2), in: Capsule())
                    }
                    Button(s.running ? "Stop logging" : "Start logging") { s.running ? s.stop() : s.start() }
                }
                Section("Home Assistant") {
                    if s.unsent > 0 {
                        Button(uploading ? "Uploading…" : "Upload drives (\(s.unsent))") {
                            uploading = true
                            Task { await s.uploadDrives(); uploading = false }
                        }.disabled(uploading)
                    } else {
                        Text("Every drive on the phone is in Home Assistant").foregroundStyle(.secondary)
                    }
                }
                if !s.live.isEmpty {
                    Section("Live") {
                        ForEach(s.live.values.sorted { $0.name < $1.name }) { v in
                            LabeledContent(v.name, value: v.value)
                        }
                    }
                }
            }
            .navigationTitle("CarMonitor")
        }
    }
}

struct SetupView: View {
    @EnvironmentObject var s: Session
    @State private var scanning = false
    @State private var query = ""
    @State private var results: [VehicleRef] = []
    @State private var message = ""
    @State private var webhook = ""
    @State private var year = 0
    @State private var gps = true
    @State private var past = false
    @State private var auto = false

    var body: some View {
        NavigationStack {
            Form {
                Section("Adapter") {
                    Text(s.settings.adapterName.isEmpty ? "None chosen" : s.settings.adapterName)
                    Button(scanning ? "Stop scanning" : "Scan for adapters") {
                        scanning.toggle(); scanning ? s.ble.scan() : s.ble.stopScan()
                    }
                    if scanning {
                        ForEach(s.found.sorted { $0.rssi > $1.rssi }) { f in
                            Button("\(f.name)  (\(f.rssi) dBm)") {
                                s.settings.adapterId = f.id; s.settings.adapterName = f.name
                                scanning = false; s.ble.stopScan()
                                s.objectWillChange.send()
                            }
                        }
                    }
                    Toggle("Start when the car is on", isOn: $auto).onChange(of: auto) { _, v in s.settings.autoStart = v }
                }
                Section("Vehicle") {
                    Text(s.settings.vehicleLabel.isEmpty ? "Generic OBD-II only" : s.settings.vehicleLabel)
                    Picker("Model year", selection: $year) {
                        Text("Not set").tag(0)
                        ForEach((1996...2030).reversed(), id: \.self) { Text(String($0)).tag($0) }
                    }.onChange(of: year) { _, v in s.settings.year = v }
                    TextField("Search OBDb (make or model)", text: $query).autocorrectionDisabled()
                        .onSubmit { Task { do { results = try await s.searchVehicles(query) } catch { message = error.localizedDescription } } }
                    ForEach(results.prefix(20), id: \.self) { v in
                        Button(v.label) {
                            Task {
                                do { try await s.chooseVehicle(v); results = []; message = "Vehicle set: \(v.label)" }
                                catch { message = error.localizedDescription }
                            }
                        }
                    }
                }
                Section("Home Assistant") {
                    SecureField("Webhook URL from the CarMonitor integration", text: $webhook)
                        .textInputAutocapitalization(.never).autocorrectionDisabled()
                        .onSubmit { s.settings.webhookUrl = webhook.trimmingCharacters(in: .whitespaces) }
                    Button("Save and test") {
                        s.settings.webhookUrl = webhook.trimmingCharacters(in: .whitespaces)
                        Task { message = await s.testWebhook() }
                    }
                    Toggle("Upload past drives", isOn: $past).onChange(of: past) { _, v in s.settings.uploadPast = v }
                    Toggle("Record GPS", isOn: $gps).onChange(of: gps) { _, v in s.settings.gps = v }
                }
                if !message.isEmpty { Section { Text(message).font(.footnote) } }
            }
            .navigationTitle("Setup")
            .onAppear {
                webhook = s.settings.webhookUrl; year = s.settings.year; gps = s.settings.gps
                past = s.settings.uploadPast; auto = s.settings.autoStart
            }
        }
    }
}

struct LogView: View {
    @EnvironmentObject var s: Session
    var body: some View {
        NavigationStack {
            ScrollView {
                Text(s.logLines.joined(separator: "\n")).font(.system(.caption, design: .monospaced))
                    .frame(maxWidth: .infinity, alignment: .leading).padding().textSelection(.enabled)
            }
            .navigationTitle("Log")
            .toolbar { ShareLink(item: s.logLines.joined(separator: "\n")) }
        }
    }
}
