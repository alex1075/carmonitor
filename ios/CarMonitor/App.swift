import SwiftUI

@main
struct CarMonitorApp: App {
    @StateObject private var session = Session()
    var body: some Scene {
        WindowGroup { RootView().environmentObject(session) }
    }
}
