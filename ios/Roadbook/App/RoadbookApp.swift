import SwiftUI
import UIKit

@main
struct RoadbookApp: App {
    @UIApplicationDelegateAdaptor(AppDelegate.self) var delegate
    @StateObject private var model = AppModel()

    var body: some Scene {
        WindowGroup {
            ContentView()
                .environmentObject(model)
                .preferredColorScheme(model.prefs.night && !model.prefs.sun ? .dark : .light)
                .onOpenURL { url in model.open(url: url) }
        }
    }
}

/// Orientation lock and the crash file. No crash reporting leaves the phone by itself: an uncaught error is
/// written to a private file, and SEND FEEDBACK offers to include it in the rider's own email.
final class AppDelegate: NSObject, UIApplicationDelegate {
    /// the orientations the app allows right now: everything, or the one the rider locked
    static var orientations: UIInterfaceOrientationMask = .all

    func application(_ application: UIApplication, didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]? = nil) -> Bool {
        NSSetUncaughtExceptionHandler { e in
            let text = "Roadbook \(Device.version) · \(Device.model) · iOS \(Device.osVersion)\n\(Date())\n\n\(e.name.rawValue): \(e.reason ?? "")\n\(e.callStackSymbols.joined(separator: "\n"))"
            try? text.write(to: Files.crashFile, atomically: true, encoding: .utf8)
        }
        return true
    }

    func application(_ application: UIApplication, supportedInterfaceOrientationsFor window: UIWindow?) -> UIInterfaceOrientationMask {
        AppDelegate.orientations
    }
}

enum Device {
    static var version: String { (Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String ?? "?") + " (" + (Bundle.main.infoDictionary?["CFBundleVersion"] as? String ?? "?") + ")" }
    static var osVersion: String { UIDevice.current.systemVersion }
    static var model: String {
        var sys = utsname(); uname(&sys)
        let id = withUnsafePointer(to: &sys.machine) { $0.withMemoryRebound(to: CChar.self, capacity: 1) { String(cString: $0) } }
        return id
    }
}
