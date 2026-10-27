import SwiftUI
import MapLibre

/// The map in SwiftUI: one MLNMapView for the life of the app, owned by the model's MapController.
struct MapViewRep: UIViewRepresentable {
    let controller: MapController
    func makeUIView(context: Context) -> MLNMapView { controller.mapView }
    func updateUIView(_ uiView: MLNMapView, context: Context) {}
}
