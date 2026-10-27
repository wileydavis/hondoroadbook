import CoreLocation
import Foundation

/// The phone's one GPS listener. The map, the ride and the recorder all read from it; the ride tunes it
/// (accuracy and distance filter) to what the route needs right now. Background updates are on, with the blue
/// indicator, so a ride or a recording carries on with the screen off.
final class Location: NSObject, CLLocationManagerDelegate, ObservableObject {
    static let shared = Location()
    private let lm = CLLocationManager()
    @Published private(set) var last: CLLocation?
    @Published private(set) var authorized = false
    @Published private(set) var denied = false
    private var listeners: [UUID: (CLLocation) -> Void] = [:]
    private var running = false

    private override init() {
        super.init()
        lm.delegate = self
        lm.desiredAccuracy = kCLLocationAccuracyBestForNavigation
        lm.distanceFilter = kCLDistanceFilterNone
        lm.activityType = .otherNavigation
        lm.pausesLocationUpdatesAutomatically = false
        lm.allowsBackgroundLocationUpdates = true
        lm.showsBackgroundLocationIndicator = true
    }

    func subscribe(_ f: @escaping (CLLocation) -> Void) -> UUID { let id = UUID(); listeners[id] = f; return id }
    func unsubscribe(_ id: UUID) { listeners[id] = nil }

    /// Asks for permission if needed and starts updates.
    func start() {
        switch lm.authorizationStatus {
        case .notDetermined: lm.requestWhenInUseAuthorization()
        case .authorizedWhenInUse, .authorizedAlways: authorized = true; run()
        default: denied = true
        }
    }

    private func run() {
        guard !running else { return }
        running = true
        lm.startUpdatingLocation()
    }

    /// The ride's demand: best accuracy and every fix when a cue is near; a wider distance filter on a quiet leg
    /// so the app wakes less often (the GPS itself stays on — out there, there is nothing else to fix from).
    func tune(fixIntervalMs: Int64, awake: Bool, worstSpeed: Double) {
        if awake || fixIntervalMs <= 2000 {
            lm.desiredAccuracy = kCLLocationAccuracyBestForNavigation; lm.distanceFilter = kCLDistanceFilterNone
        } else {
            lm.desiredAccuracy = kCLLocationAccuracyBest
            lm.distanceFilter = max(10, worstSpeed * Double(fixIntervalMs) / 1000 * 0.5)
        }
    }
    func tuneDefault() { lm.desiredAccuracy = kCLLocationAccuracyBestForNavigation; lm.distanceFilter = kCLDistanceFilterNone }
    func setActivity(bike: Bool) { lm.activityType = bike ? .otherNavigation : .automotiveNavigation }

    var lastKnown: CLLocation? { last ?? lm.location }

    func locationManagerDidChangeAuthorization(_ manager: CLLocationManager) {
        switch manager.authorizationStatus {
        case .authorizedWhenInUse, .authorizedAlways: authorized = true; denied = false; run()
        case .denied, .restricted: denied = true; authorized = false
        default: break
        }
    }

    func locationManager(_ manager: CLLocationManager, didUpdateLocations locations: [CLLocation]) {
        for l in locations where l.horizontalAccuracy >= 0 {
            last = l
            for f in listeners.values { f(l) }
        }
    }

    func locationManager(_ manager: CLLocationManager, didFailWithError error: Error) {
        NSLog("Roadbook: location error \(error.localizedDescription)")
    }
}
