package fold.app

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import android.os.PowerManager
import android.content.Context
import android.content.Intent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.ContextCompat
import fold.core.Cue
import fold.core.Fix
import fold.core.FixState
import fold.core.Follower
import fold.core.Geo
import fold.core.Gpx
import fold.core.Hit
import fold.core.Maneuver
import fold.core.NavController
import fold.core.NavView
import fold.core.Profile
import fold.core.Route
import fold.core.Turn
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory.*
import org.maplibre.android.style.expressions.Expression as E
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point
import java.util.concurrent.Executors
import kotlin.math.max
import kotlin.math.min

class MainActivity : AppCompatActivity() {

    enum class Mode { IDLE, PIN, COMPARE, NAV, EDIT }

    // ---- the track being followed or edited, with its authored cues (Roadbook)
    // (kept in NavSession so a night-mode recreation keeps the editor and the followed track's cues)
    private var trackLats: DoubleArray?
        get() = NavSession.trackLats; set(v) { NavSession.trackLats = v }
    private var trackLons: DoubleArray?
        get() = NavSession.trackLons; set(v) { NavSession.trackLons = v }
    private var trackName: String
        get() = NavSession.trackName; set(v) { NavSession.trackName = v }
    private var trackUri: Uri?
        get() = NavSession.trackUri; set(v) { NavSession.trackUri = v }
    private val trackCues: ArrayList<Cue> get() = NavSession.trackCues
    private val editCues: ArrayList<Cue> get() = NavSession.editCues
    private var compareModes: List<Profile>?
        get() = NavSession.compareModes; set(v) { NavSession.compareModes = v }
    /** A planned route with the file's authored cues on it (when a file is being edited). */
    private fun planned(r: Route): Route = if (editCues.isEmpty()) r else try { Route.withCues(r, Route.authoredCues(editCues, r)) } catch (_: Throwable) { r }
    private var srcCues: GeoJsonSource? = null
    private var srcTurns: GeoJsonSource? = null
    private var srcPlaces: GeoJsonSource? = null
    /** the packs whose places are on the map right now */
    private var placesFor: List<Pack> = emptyList()
    private lateinit var panelEdit: View
    private lateinit var recStrip: View
    private lateinit var recStatus: TextView

    /** Process-wide holder so toggling night mode (which recreates the activity) never loses a route. */
    object NavSession {
        var mode = Mode.IDLE; var lastFix: Fix? = null; var pin: LatLng? = null
        var route: Route? = null; var isTrack = false; var destination: LatLng? = null
        /** points the route must pass through, in order; "go by Spokane" */
        var vias: MutableList<LatLng> = ArrayList()
        /** the vias' names ("Lolo Pass", or "" for a point on the map), one per via */
        val viaNames: MutableList<String> = ArrayList()
        var destName = ""
        /** a start chosen on the map for planning, instead of the GPS position */
        var planStart: LatLng? = null
        /** the chosen start's name, "" for a point on the map or the GPS position */
        var startName = ""
        /** the track being followed or edited, with its authored cues */
        var trackLats: DoubleArray? = null; var trackLons: DoubleArray? = null; var trackName = "Track"; var trackUri: Uri? = null
        val trackCues = ArrayList<Cue>()
        /** a file's own cues, carried through EDIT ROUTE and laid back onto whatever gets planned */
        val editCues = ArrayList<Cue>()
        /** the modes the compare screen is limited to (a file is re-planned in its own mode); null = the rider's chosen set */
        var compareModes: List<Profile>? = null
    }

    private lateinit var mapView: MapView
    private var map: MapLibreMap? = null
    private var style: Style? = null
    private lateinit var prefs: Prefs
    private lateinit var packs: PackStore
    private lateinit var marks: MarkStore
    private var srcMarks: GeoJsonSource? = null
    private var srcVia: GeoJsonSource? = null
    private var srcFog: GeoJsonSource? = null; private var srcCover: GeoJsonSource? = null; private var srcCoverLabel: GeoJsonSource? = null
    private val ui = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()

    // views
    private lateinit var hud: View; private lateinit var arrow: ArrowView
    private lateinit var hudDistance: TextView; private lateinit var hudStreet: TextView; private lateinit var offTrack: TextView; private lateinit var offPanel: View; private lateinit var btnReroute: Button
    private lateinit var panelIdle: View; private lateinit var panelPin: View; private lateinit var panelNav: View
    private lateinit var pinLabel: TextView; private lateinit var navRemaining: TextView; private lateinit var navSpeed: TextView
    private lateinit var navEta: TextView; private lateinit var navElev: TextView; private lateinit var navScale: ScaleView
    /** the last fix's height above sea level, metres (NaN until known) */
    private var lastAltitude = Double.NaN
    private var glowAt = 0L
    private lateinit var toastView: TextView
    private lateinit var panelCompare: View; private lateinit var compareRows: LinearLayout
    private var srcCompare: GeoJsonSource? = null
    private var srcLead: GeoJsonSource? = null
    private lateinit var turnChip: TextView
    private lateinit var hudCompass: CompassView
    /** candidate routes by mode, in the order they were computed */
    private val candidates = LinkedHashMap<Profile, Route>()
    private var compareGeneration = 0
    private var compareFrom: Fix? = null

    // state that must survive a night-mode recreation lives in NavSession
    private var mode: Mode
        get() = NavSession.mode; set(v) { NavSession.mode = v }
    private var follow = true
    /** Tap-to-cycle camera while navigating: whole route → next turn in view → close up. */
    private enum class ZoomMode { OVERVIEW, TURN, CLOSE }
    private var zoomMode = ZoomMode.TURN
    private var manualZoom = -1.0          // pinch override for the current mode, until the next tap
    private var overviewDone = false       // overview fits once, then holds still while the puck moves
    private var gestureFrom: LatLng? = null; private var gestureZoom = 0.0
    private var lastFix: Fix?
        get() = NavSession.lastFix; set(v) { NavSession.lastFix = v }
    private var heading = 0.0
    private var pin: LatLng?
        get() = NavSession.pin; set(v) { NavSession.pin = v }
    private var pinName = ""
    private var route: Route?
        get() = NavSession.route; set(v) { NavSession.route = v }
    /** the ride's follower, owned by [NavService]; the activity only reads views from it */
    private val follower: Follower? get() = NavService.controller?.follower
    private var isTrack: Boolean
        get() = NavSession.isTrack; set(v) { NavSession.isTrack = v }
    private var destination: LatLng?
        get() = NavSession.destination; set(v) { NavSession.destination = v }
    private val vias: MutableList<LatLng> get() = NavSession.vias
    private val viaNames: MutableList<String> get() = NavSession.viaNames
    private var planStart: LatLng?
        get() = NavSession.planStart; set(v) { NavSession.planStart = v }
    private var startName: String
        get() = NavSession.startName; set(v) { NavSession.startName = v }
    /** a plan is being set up on the map: the first GPS fix must not yank the camera away from it */
    private val planningCamera: Boolean get() = planStart != null || mode == Mode.COMPARE || mode == Mode.EDIT || mode == Mode.PIN
    private var srcStart: GeoJsonSource? = null
    private var destName: String
        get() = NavSession.destName; set(v) { NavSession.destName = v }
    private var profile = Profile.FAST
    private var rerouting = false
    private var lastRerouteMs = 0L
    private var offSince = 0L
    private var routeGeneration = 0

    // sources
    private var srcPuck: GeoJsonSource? = null
    private var srcRoute: GeoJsonSource? = null
    private var srcPin: GeoJsonSource? = null

    private val locationManager by lazy { getSystemService(LOCATION_SERVICE) as LocationManager }

    private var afterFolder: (() -> Unit)? = null
    private val pickFolder = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) { FilePicker.rememberFolder(this, uri); marks.syncMirror(); afterFolder?.invoke() }
        afterFolder = null
    }
    private fun chooseFolder(then: () -> Unit) { afterFolder = then; safe { pickFolder.launch(FilePicker.folder(this)) } }
    // system picker as a fallback for any file the in-app list can't see
    private val browsePack = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) importPacks(listOf(uri)) }
    private val browseGpx = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) loadGpx(uri) }
    private fun showTrackPicker() {
        FilePicker.show(this, "Follow a track", listOf(".gpx"), "GPX tracks", { chooseFolder { showTrackPicker() } }, { e -> loadGpx(e.uri) }, null, { safe { browseGpx.launch(arrayOf("*/*")) } },
            extra = "FETCH CODE FROM THE PLANNER" to { Library.fetchCode(this) { name, text -> onFetchedGpx(name, text) } })
    }
    private fun showPackPicker() {
        FilePicker.show(this, "Import map packs", listOf(".mappack", ".zip"), "map packs", { chooseFolder { showPackPicker() } }, { e -> importPacks(listOf(e.uri)) }, { all -> importPacks(all.map { it.uri }) }, { safe { browsePack.launch(arrayOf("*/*")) } })
    }
    private val askLocation = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { res -> if (res[Manifest.permission.ACCESS_FINE_LOCATION] == true) startLocation() else toast(getString(R.string.need_location)) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        Units.metric = prefs.metric
        profile = prefs.profile
        packs = PackStore(this)
        marks = MarkStore(this)
        Tone.enabled = prefs.sound
        // the notification's End button, when the screen is off and the phone is in a pocket
        NavService.onStopRequested = { if (mode == Mode.NAV) endNavigation() else NavService.stop(this) }
        NavService.onFailure = { why -> toast("Navigation cannot run with the screen off: $why", 8000) }
        NavService.onAttention = { on -> ui.post { onAttention(on) } }
        if (prefs.batterySaver) applyBatteryFlags(true)
        // under 15 % while navigating, the saver turns itself on rather than let the ride die on a long leg
        registerReceiver(batteryRx, android.content.IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        if (prefs.orientation >= 0) requestedOrientation = prefs.orientation
        { Speech.init(this); Speech.enabled = prefs.sound }
        setContentView(R.layout.activity_main)

        mapView = findViewById(R.id.map)
        hud = findViewById(R.id.hud); arrow = findViewById(R.id.arrow)
        // keep the HUD and the off-route banner clear of the status bar and the camera cutout
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.root)) { v, insets ->
            val top = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.statusBars() or androidx.core.view.WindowInsetsCompat.Type.displayCutout()).top
            (hud.layoutParams as android.widget.FrameLayout.LayoutParams).topMargin = top + dp(16)
            (offPanel.layoutParams as android.widget.FrameLayout.LayoutParams).topMargin = top + dp(16)
            hud.requestLayout(); offPanel.requestLayout()
            insets
        }
        hudDistance = findViewById(R.id.hudDistance); hudStreet = findViewById(R.id.hudStreet); offTrack = findViewById(R.id.offTrack)
        offPanel = findViewById(R.id.offPanel); btnReroute = findViewById(R.id.btnReroute)
        btnReroute.setOnClickListener { rerouteNow() }
        panelIdle = findViewById(R.id.panelIdle); panelPin = findViewById(R.id.panelPin); panelNav = findViewById(R.id.panelNav)
        pinLabel = findViewById(R.id.pinLabel); navRemaining = findViewById(R.id.navRemaining); navSpeed = findViewById(R.id.navSpeed)
        navEta = findViewById(R.id.navEta); navElev = findViewById(R.id.navElev); navScale = findViewById<ScaleView>(R.id.navScale).also { it.fixed = true }
        toastView = findViewById(R.id.toast)
        panelCompare = findViewById(R.id.panelCompare); compareRows = findViewById(R.id.compareRows)
        turnChip = findViewById(R.id.turnChip); hudCompass = findViewById(R.id.hudCompass)
        findViewById<Button>(R.id.btnStartNav).setOnClickListener { goWithSelected() }
        findViewById<Button>(R.id.btnCompareCancel).setOnClickListener { cancelCompare() }
        findViewById<Button>(R.id.btnStops).setOnClickListener { showStops() }
        findViewById<Button>(R.id.btnModes).setOnClickListener { pickModes() }

        findViewById<ImageButton>(R.id.btnGo).setOnClickListener { SearchDialog.show(this, packs, marks, { hit -> onSearchPick(hit) }, { m -> goToMark(m) }) }
        findViewById<ImageButton>(R.id.btnTrack).setOnClickListener { showTrackPicker() }
        findViewById<ImageButton>(R.id.btnMaps).setOnClickListener { MapsDialog.show(this, packs, marks, { showPackPicker() }, { p -> deletePack(p) }, { metric -> prefs.metric = metric; Units.metric = metric },
            onExportMarks = { val tree = FilePicker.folder(this); if (tree == null) chooseFolder { exportMarks() } else exportMarks() },
            onBurnMarks = { safe { marks.burn(); drawMarks(); toast("All marks deleted") } },
            onDownload = { Catalog.show(this, packs) { onPacksChanged() } },
            onBrowse = { showLibrary(true) },
            terrainOn = prefs.terrain, onTerrain = { on -> prefs.terrain = on; loadStyle() }, placesOn = prefs.places, onPlaces = { on -> prefs.places = on; drawPlaces() },
            onImportMarks = { safe { browseGpx.launch(arrayOf("*/*")) } }) }
        findViewById<Button>(R.id.btnRouteHere).setOnClickListener { pin?.let { routeTo(it, pinName) } }
        findViewById<Button>(R.id.btnPinCancel).setOnClickListener { clearPin() }
        findViewById<Button>(R.id.btnVia).setOnClickListener { pin?.let { addVia(it, pinName) } }
        findViewById<Button>(R.id.btnFrom).setOnClickListener { pin?.let { setStart(it) } }
        findViewById<Button>(R.id.btnSaveRoute).setOnClickListener { candidates[profile]?.let { saveOrShareRoute(it) } ?: toast("Pick a route first") }
        findViewById<View>(R.id.navCard).setOnClickListener { route?.let { r -> if (!isTrack) showTurnList(r) } }
        findViewById<ImageButton>(R.id.btnSavePin).setOnClickListener { pin?.let { ll -> MarkDialog.edit(this, null, if (pinName.isEmpty() || pinName.first().isDigit() || pinName.first() == '-') "" else pinName) { name, note -> safe { marks.add(name, note, ll.latitude, ll.longitude); drawMarks(); clearPin(); toast("Saved: $name") } } } }
        findViewById<Button>(R.id.btnEnd).setOnClickListener { endNavigation() }
        findViewById<ImageButton>(R.id.btnSound).setOnClickListener {
            val on = !prefs.sound; prefs.sound = on; Tone.enabled = on; Speech.enabled = on; updateSoundButton()
            if (on) { Tone.turn(); if (Speech.ready) Speech.say("Sound on") else toast("Pips and chimes on; the voice is still loading.", 4000) }
            else Speech.stop()
        }
        updateSoundButton()
        // orientation lock: holds the screen the way it is now, and remembers it
        val lock = View.OnClickListener { toggleOrientationLock() }
        findViewById<ImageButton>(R.id.btnLock).setOnClickListener(lock)
        findViewById<ImageButton>(R.id.btnLockNav).setOnClickListener(lock)
        updateLockButtons()
        // the glove menu
        findViewById<ImageButton>(R.id.btnMenu).setOnClickListener { haptic(1); openMenu() }
        findViewById<Button>(R.id.btnMenuClose).setOnClickListener { closeMenu() }
        findViewById<View>(R.id.menuScrim).setOnClickListener { closeMenu() }
        findViewById<View>(R.id.menuSheet).setOnClickListener { }   // taps inside the sheet stay inside
        // Roadbook: pre-run recording and cue authoring
        panelEdit = findViewById(R.id.panelEdit); recStrip = findViewById(R.id.recStrip); recStatus = findViewById(R.id.recStatus)
        run {
            findViewById<ImageButton>(R.id.btnRec).setOnClickListener { startRecording() }
            findViewById<Button>(R.id.btnRecCue).setOnClickListener { addCueHere() }
            findViewById<Button>(R.id.btnRecStop).setOnClickListener { stopRecording() }
            findViewById<ImageButton>(R.id.btnNavCue).setOnClickListener { addCueHere() }
            findViewById<Button>(R.id.btnEditSave).setOnClickListener { saveTrack() }
            findViewById<Button>(R.id.btnEditList).setOnClickListener { showCueList() }
            findViewById<Button>(R.id.btnEditFollow).setOnClickListener { followEditedTrack() }
            findViewById<Button>(R.id.btnEditCancel).setOnClickListener { leaveEdit() }
            Recorder.onChange = { ui.post { updateRecStrip(); Recorder.lastFailure?.let { why -> Recorder.lastFailure = null; toast("Not recording: $why", 7000) } } }
            val afterTerms: () -> Unit = {
                if (Recorder.recover(this)) ui.postDelayed({ offerRecovery() }, 1500)
                else if (NavService.leftover(this) != null) ui.postDelayed({ offerResume() }, 1500)
                Unit
            }
            if (prefs.accepted) afterTerms() else showTerms(afterTerms)
            updateRecStrip()
        }
        val locate = View.OnClickListener { follow = true; lastFix?.let { moveCamera(it.lat, it.lon, animate = true) } ?: toast("Waiting for GPS…") }
        findViewById<ImageButton>(R.id.btnLocate).setOnClickListener(locate)
        findViewById<ImageButton>(R.id.btnLocateNav).setOnClickListener(locate)
        val night = View.OnClickListener {
            val n = !prefs.night; prefs.night = n; prefs.sun = false
            applyUiTheme()
        }
        findViewById<ImageButton>(R.id.btnNight).setOnClickListener(night)
        findViewById<ImageButton>(R.id.btnNightNav).setOnClickListener(night)

        mapView.onCreate(savedInstanceState)
        mapView.getMapAsync { m ->
            map = m
            m.uiSettings.isLogoEnabled = false
            m.uiSettings.isAttributionEnabled = false
            m.uiSettings.isCompassEnabled = true
            m.uiSettings.setCompassMargins(0, dp(170), dp(16), 0)
            m.uiSettings.setCompassFadeFacingNorth(true)
            m.uiSettings.isRotateGesturesEnabled = true
            m.uiSettings.isTiltGesturesEnabled = false
            m.addOnCameraMoveStartedListener { reason ->
                if (reason != MapLibreMap.OnCameraMoveStartedListener.REASON_API_GESTURE) return@addOnCameraMoveStartedListener
                if (mode == Mode.NAV) { gestureFrom = m.cameraPosition.target; gestureZoom = m.cameraPosition.zoom } else follow = false
            }
            m.addOnMapLongClickListener { ll -> onLongPress(ll); true }
            m.addOnMapClickListener { ll ->
                val hit = try { val pt = m.projection.toScreenLocation(ll); m.queryRenderedFeatures(android.graphics.RectF(pt.x - dp(22), pt.y - dp(22), pt.x + dp(22), pt.y + dp(22)), "marks-layer").firstOrNull() } catch (_: Throwable) { null }
                val id = hit?.getNumberProperty("id")?.toLong()
                val mark = id?.let { v -> marks.marks.firstOrNull { it.id == v } }
                val cueHit = if (mode == Mode.EDIT || mode == Mode.COMPARE || (mode == Mode.NAV && isTrack)) try { val pt = m.projection.toScreenLocation(ll); m.queryRenderedFeatures(android.graphics.RectF(pt.x - dp(22), pt.y - dp(22), pt.x + dp(22), pt.y + dp(22)), "cues-layer").firstOrNull() } catch (_: Throwable) { null } else null
                val cue = cueHit?.getNumberProperty("idx")?.toInt()?.let { (if (mode == Mode.COMPARE) editCues else trackCues).getOrNull(it) }
                // plan mode: the router's own turns on the selected route can be reworded
                val turnHit = if (mode == Mode.COMPARE && cue == null) try { val pt = m.projection.toScreenLocation(ll); m.queryRenderedFeatures(android.graphics.RectF(pt.x - dp(18), pt.y - dp(18), pt.x + dp(18), pt.y + dp(18)), "turns-layer").firstOrNull() } catch (_: Throwable) { null } else null
                val turnIdx = turnHit?.getNumberProperty("idx")?.toInt()
                val line = if (mode == Mode.COMPARE) try { val pt = m.projection.toScreenLocation(ll); m.queryRenderedFeatures(android.graphics.RectF(pt.x - dp(14), pt.y - dp(14), pt.x + dp(14), pt.y + dp(14)), "cmp-line").firstOrNull() } catch (_: Throwable) { null } else null
                val pickedMode = line?.getStringProperty("mode")?.let { try { Profile.valueOf(it) } catch (_: Throwable) { null } }
                val viaHit = if (vias.isNotEmpty() && (mode == Mode.COMPARE || mode == Mode.NAV || mode == Mode.PIN)) try { val pt = m.projection.toScreenLocation(ll); m.queryRenderedFeatures(android.graphics.RectF(pt.x - dp(20), pt.y - dp(20), pt.x + dp(20), pt.y + dp(20)), "via-layer").firstOrNull() } catch (_: Throwable) { null } else null
                val viaIdx = viaHit?.getNumberProperty("idx")?.toInt()?.takeIf { it in vias.indices }
                val libHit = if (libraryOn && mode == Mode.IDLE) try { val pt = m.projection.toScreenLocation(ll); m.queryRenderedFeatures(android.graphics.RectF(pt.x - dp(16), pt.y - dp(16), pt.x + dp(16), pt.y + dp(16)), "lib-line").firstOrNull() } catch (_: Throwable) { null } else null
                val libItem = libHit?.getStringProperty("id")?.let { id -> libraryItems.firstOrNull { it.id == id } }
                // a place from the packs (fuel, camping…): a pin with its name, so ROUTE HERE / VIA / ★ work on it
                val placeHit = if (mode != Mode.NAV && mode != Mode.EDIT && prefs.places) try { val pt = m.projection.toScreenLocation(ll); m.queryRenderedFeatures(android.graphics.RectF(pt.x - dp(18), pt.y - dp(18), pt.x + dp(18), pt.y + dp(18)), "places-layer").firstOrNull() } catch (_: Throwable) { null } else null
                when {
                    cue != null && mode == Mode.COMPARE -> { editPlanCue(cue); true }
                    cue != null -> { editCue(cue); true }
                    turnIdx != null -> { rewordTurn(turnIdx); true }
                    viaIdx != null -> { offerRemoveVia(viaIdx); true }
                    libItem != null -> { openLibraryItem(libItem); true }
                    mark != null -> { openMark(mark); true }
                    placeHit != null -> { openPlace(placeHit); true }
                    pickedMode != null && candidates.containsKey(pickedMode) -> { profile = pickedMode; prefs.profile = pickedMode; rebuildCompareRows(prefs.modes.toList().sortedBy { it.ordinal }, false); drawCandidates(); true }
                    mode == Mode.PIN -> { clearPin(); true }
                    mode == Mode.NAV -> { cycleZoom(); true }
                    else -> false
                }
            }
            m.addOnCameraIdleListener {
                refreshVisiblePacks()
                if (libraryOn && mode == Mode.IDLE && System.currentTimeMillis() - libraryFetchedAt > 1500) fetchLibrary(false)
                // navigating: a pinch keeps following at the new zoom; a drag stops following
                val from = gestureFrom
                if (from != null && mode == Mode.NAV) {
                    gestureFrom = null
                    val t = m.cameraPosition.target
                    val moved = if (t != null) { val a = m.projection.toScreenLocation(from); val b = m.projection.toScreenLocation(t); Math.hypot((a.x - b.x).toDouble(), (a.y - b.y).toDouble()) } else 0.0
                    val zoomed = Math.abs(m.cameraPosition.zoom - gestureZoom) > 0.1
                    if (zoomed && moved < dp(60)) { manualZoom = m.cameraPosition.zoom; if (zoomMode == ZoomMode.OVERVIEW) { zoomMode = ZoomMode.TURN; overviewDone = false }; follow = true; lastFix?.let { moveCamera(it.lat, it.lon, true) } }
                    else if (moved >= dp(60)) follow = false
                }
            }
            val scale = findViewById<ScaleView>(R.id.scale)
            m.addOnCameraMoveListener { val mpp = m.projection.getMetersPerPixelAtLatitude(m.cameraPosition.target?.latitude ?: 45.0); scale.update(mpp); navScale.update(mpp) }
            scale.update(m.projection.getMetersPerPixelAtLatitude(m.cameraPosition.target?.latitude ?: 45.0))
            loadStyle()
        }

        worker.execute {
            packs.scan()
            ui.post {
                loadStyle()
                val p = packs.packs.firstOrNull()
                if (p != null && lastFix == null) {
                    val m = p.manifest
                    map?.moveCamera(CameraUpdateFactory.newLatLngBounds(LatLngBounds.from(m.maxLat, m.maxLon, m.minLat, m.minLon), dp(40)))
                } else if (p == null) toast("No maps yet — tap MAPS → DOWNLOAD")
            }
            // warm the routers in the background so the first route is instant
            try { packs.multiRouter() } catch (_: Throwable) {}
        }
        ensureLocation()
    }

    // ---------------------------------------------------------------- style / overlays

    private var styledPacks: List<Pack> = emptyList()

    /** Packs worth drawing for the current camera: those in view, nearest first, at most six (style size matters). */
    private fun visiblePacks(): List<Pack> {
        val all = packs.packs
        val roads = all.filter { !it.isTerrain }; val terrain = all.filter { it.isTerrain }
        val m = map ?: return roads.take(6) + terrain.take(6)
        if (all.size <= 6) return all
        val bounds = try { m.projection.visibleRegion.latLngBounds } catch (_: Throwable) { return roads.take(6) + terrain.take(6) }
        val c = m.cameraPosition.target ?: return roads.take(6) + terrain.take(6)
        fun pick(list: List<Pack>): List<Pack> {
            val inView = list.filter { p -> val mf = p.manifest; mf.maxLat >= bounds.latitudeSouth && mf.minLat <= bounds.latitudeNorth && mf.maxLon >= bounds.longitudeWest && mf.minLon <= bounds.longitudeEast }
            return inView.sortedBy { p -> val mf = p.manifest; Geo.fastDistance(c.latitude, c.longitude, (mf.minLat + mf.maxLat) / 2, (mf.minLon + mf.maxLon) / 2) }.take(6)
        }
        return pick(roads) + pick(terrain)
    }

    private fun refreshVisiblePacks() {
        if (packs.packs.size <= 6) return
        val v = visiblePacks().filter { prefs.terrain || !it.isTerrain }
        if (v.map { it.dir } != styledPacks.map { it.dir }) loadStyle()
    }

    private fun loadStyle() {
        val m = map ?: return
        val chosen = visiblePacks().filter { prefs.terrain || !it.isTerrain }
        val json = try { StyleBuilder.build(this, chosen, prefs.flavor) } catch (e: Throwable) { toast("Style error: ${e.message}"); return }
        styledPacks = chosen
        m.setStyle(Style.Builder().fromJson(json)) { st ->
            style = st
            try { addOverlays(st) } catch (e: Throwable) { toast("Overlay error: ${e.message}") }
            // re-draw anything we had (also restores a trip after a night-mode recreation)
            route?.let { drawRoute(it) }
            drawCues()
            if (mode == Mode.EDIT) findViewById<TextView>(R.id.editLabel).text = "$trackName · ${trackCues.size} cues\nLong-press on the line to add a cue. Tap a cue to edit it."
            pin?.let { showPinMarker(it); pinLabel.text = pinName.ifEmpty { "%.5f, %.5f".format(it.latitude, it.longitude) } }
            lastFix?.let { updatePuck(it.lat, it.lon, heading) }
            switchMode(mode)
            if (mode == Mode.NAV && follower != null) {
                window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                ui.removeCallbacks(hudTick); ui.post(hudTick)
            }
        }
    }

    private fun addOverlays(st: Style) {
        st.addImage("puck", puckBitmap())
        st.addImage("pin", pinBitmap())
        st.addImage("mark", markBitmap())
        srcMarks = GeoJsonSource("marks-src", FeatureCollection.fromFeatures(emptyArray())).also { st.addSource(it) }
        srcVia = GeoJsonSource("via-src", FeatureCollection.fromFeatures(emptyArray())).also { st.addSource(it) }
        srcCompare = GeoJsonSource("cmp-src", FeatureCollection.fromFeatures(emptyArray())).also { st.addSource(it) }
        srcStart = GeoJsonSource("start-src", FeatureCollection.fromFeatures(emptyArray())).also { st.addSource(it) }
        st.addImage("start", startBitmap())
        srcLead = GeoJsonSource("lead-src", FeatureCollection.fromFeatures(emptyArray())).also { st.addSource(it) }
        st.addImage("via", viaBitmap())
        srcRoute = GeoJsonSource("route-src", FeatureCollection.fromFeatures(emptyArray())).also { st.addSource(it) }
        srcCues = GeoJsonSource("cues-src", FeatureCollection.fromFeatures(emptyArray())).also { st.addSource(it) }
        st.addImage("cue", cueBitmap(false)); st.addImage("cue-danger", cueBitmap(true))
        srcTurns = GeoJsonSource("turns-src", FeatureCollection.fromFeatures(emptyArray())).also { st.addSource(it) }
        st.addImage("turn", turnBitmap())
        srcPin = GeoJsonSource("pin-src", FeatureCollection.fromFeatures(emptyArray())).also { st.addSource(it) }
        srcPuck = GeoJsonSource("puck-src", FeatureCollection.fromFeatures(emptyArray())).also { st.addSource(it) }
        addLibraryLayers(st)
        val casing = ContextCompat.getColor(this, R.color.route_casing)
        // Route lines go under the map's labels (road names, shields, towns), so a route never hides the name of the road it follows.
        val firstLabel = st.layers.firstOrNull { it is SymbolLayer }?.id
        fun addUnderLabels(l: org.maplibre.android.style.layers.Layer) { if (firstLabel != null) st.addLayerBelow(l, firstLabel) else st.addLayer(l) }
        addUnderLabels(LineLayer("route-casing", "route-src").withProperties(
            lineColor(casing), lineWidth(10f), lineCap(Property.LINE_CAP_ROUND), lineJoin(Property.LINE_JOIN_ROUND), lineOpacity(0.9f)))
        addUnderLabels(LineLayer("route-line", "route-src").withProperties(
            lineColor(ContextCompat.getColor(this, if (isTrack) R.color.track else R.color.route)), lineWidth(6f), lineCap(Property.LINE_CAP_ROUND), lineJoin(Property.LINE_JOIN_ROUND)))
        // coverage: fog over everything not in an installed pack, outlines and names of what is
        srcFog = GeoJsonSource("fog-src", Coverage.fog(packs.roadPacks)).also { st.addSource(it) }
        srcCover = GeoJsonSource("cover-src", Coverage.outlines(packs.roadPacks)).also { st.addSource(it) }
        srcCoverLabel = GeoJsonSource("cover-label-src", Coverage.labels(packs.roadPacks)).also { st.addSource(it) }
        st.addLayer(org.maplibre.android.style.layers.FillLayer("fog-layer", "fog-src").withProperties(
            fillColor(ContextCompat.getColor(this, R.color.ink)), fillAntialias(false),
            fillOpacity(org.maplibre.android.style.expressions.Expression.interpolate(org.maplibre.android.style.expressions.Expression.linear(), org.maplibre.android.style.expressions.Expression.zoom(),
                org.maplibre.android.style.expressions.Expression.stop(7, 0.22f), org.maplibre.android.style.expressions.Expression.stop(10, 0.0f)))).also { it.maxZoom = 10f })
        st.addLayer(LineLayer("cover-outline", "cover-src").withProperties(
            lineColor(ContextCompat.getColor(this, R.color.accent)), lineWidth(1.2f), lineDasharray(arrayOf(2f, 2f)),
            lineOpacity(org.maplibre.android.style.expressions.Expression.interpolate(org.maplibre.android.style.expressions.Expression.linear(), org.maplibre.android.style.expressions.Expression.zoom(),
                org.maplibre.android.style.expressions.Expression.stop(7, 0.9f), org.maplibre.android.style.expressions.Expression.stop(9, 0.0f)))).also { it.maxZoom = 9f })
        st.addLayer(SymbolLayer("cover-label", "cover-label-src").withProperties(
            textField(org.maplibre.android.style.expressions.Expression.get("name")), textFont(arrayOf("Noto Sans Medium")), textSize(13f), textTransform(Property.TEXT_TRANSFORM_UPPERCASE), textLetterSpacing(0.15f),
            textColor(ContextCompat.getColor(this, R.color.accent)), textHaloColor(ContextCompat.getColor(this, R.color.paper)), textHaloWidth(1.5f), textAllowOverlap(false)).also { it.maxZoom = 7f })
        // the packs' places: fuel, camping, water, beds, repair, food — icons from the map's own sprite, each from its own zoom
        srcPlaces = GeoJsonSource("places-src", FeatureCollection.fromFeatures(emptyArray())).also { st.addSource(it) }
        st.addLayer(SymbolLayer("places-layer", "places-src").withProperties(
            iconImage(E.get("icon")), iconAnchor(Property.ICON_ANCHOR_CENTER), iconAllowOverlap(false), iconIgnorePlacement(false), iconSize(1f),
            textField(E.get("name")), textFont(arrayOf("Noto Sans Regular")), textSize(11f), textAnchor(Property.TEXT_ANCHOR_TOP), textOffset(arrayOf(0f, 0.9f)),
            textColor(ContextCompat.getColor(this, R.color.ink_soft)), textHaloColor(ContextCompat.getColor(this, R.color.paper)), textHaloWidth(1.5f), textOptional(true))
            .withFilter(E.gte(E.zoom(), E.get("minzoom"))).also { it.minZoom = 11f })
        placesFor = emptyList(); drawPlaces()
        st.addLayer(SymbolLayer("marks-layer", "marks-src").withProperties(
            iconImage("mark"), iconAnchor(Property.ICON_ANCHOR_CENTER), iconAllowOverlap(true), iconIgnorePlacement(true), iconSize(1f),
            textField(org.maplibre.android.style.expressions.Expression.get("name")), textFont(arrayOf("Noto Sans Medium")), textSize(13f), textAnchor(Property.TEXT_ANCHOR_TOP), textOffset(arrayOf(0f, 0.9f)),
            textColor(ContextCompat.getColor(this, R.color.ink)), textHaloColor(ContextCompat.getColor(this, R.color.paper)), textHaloWidth(2f), textOptional(true)))
        drawMarks()
        st.addLayer(SymbolLayer("cues-layer", "cues-src").withProperties(
            iconImage(E.get("icon")), iconAnchor(Property.ICON_ANCHOR_CENTER), iconAllowOverlap(true), iconIgnorePlacement(true),
            textField(E.get("label")), textFont(arrayOf("Noto Sans Medium")), textSize(12f), textAnchor(Property.TEXT_ANCHOR_TOP), textOffset(arrayOf(0f, 1.0f)),
            textColor(ContextCompat.getColor(this, R.color.ink)), textHaloColor(ContextCompat.getColor(this, R.color.paper)), textHaloWidth(2f), textOptional(true)).also { it.minZoom = 9f })
        drawCues()
        run {
            addUnderLabels(LineLayer("cmp-casing", "cmp-src").withProperties(lineColor(ContextCompat.getColor(this, R.color.route_casing)), lineCap(Property.LINE_CAP_ROUND), lineJoin(Property.LINE_JOIN_ROUND),
                lineWidth(E.switchCase(E.toBool(E.get("sel")), E.literal(9f), E.literal(5f))), lineOpacity(E.switchCase(E.toBool(E.get("sel")), E.literal(0.9f), E.literal(0.5f)))))
            addUnderLabels(LineLayer("cmp-line", "cmp-src").withProperties(lineColor(E.toColor(E.get("color"))), lineCap(Property.LINE_CAP_ROUND), lineJoin(Property.LINE_JOIN_ROUND),
                lineWidth(E.switchCase(E.toBool(E.get("sel")), E.literal(6f), E.literal(3f))), lineOpacity(E.switchCase(E.toBool(E.get("sel")), E.literal(1f), E.literal(0.75f))),
                lineSortKey(E.switchCase(E.toBool(E.get("sel")), E.literal(2f), E.literal(1f)))))
        }
        // the "string to the next turn": straight, dashed, half transparent, on top of the route but under labels
        // the stretch of route between the rider and the next turn glows softly in the route's own colour
        addUnderLabels(LineLayer("lead-line", "lead-src").withProperties(lineColor(ContextCompat.getColor(this, if (isTrack) R.color.track else R.color.route)), lineWidth(18f), lineOpacity(0.32f), lineBlur(5f), lineCap(Property.LINE_CAP_ROUND), lineJoin(Property.LINE_JOIN_ROUND)))
        // plan mode: the router's turns on the selected route, small hollow rings, tap to reword
        st.addLayer(SymbolLayer("turns-layer", "turns-src").withProperties(iconImage("turn"), iconAllowOverlap(true), iconIgnorePlacement(true)).also { it.minZoom = 10f })
        if (mode == Mode.COMPARE) drawCandidates()
        st.addLayer(SymbolLayer("start-layer", "start-src").withProperties(iconImage("start"), iconAllowOverlap(true), iconIgnorePlacement(true)))
        drawStart()
        st.addLayer(SymbolLayer("via-layer", "via-src").withProperties(iconImage("via"), iconAllowOverlap(true), iconIgnorePlacement(true)))
        drawVias()
        st.addLayer(SymbolLayer("pin-layer", "pin-src").withProperties(iconImage("pin"), iconAnchor(Property.ICON_ANCHOR_BOTTOM), iconAllowOverlap(true), iconIgnorePlacement(true), iconSize(1f)))
        st.addLayer(SymbolLayer("puck-layer", "puck-src").withProperties(
            iconImage("puck"), iconAllowOverlap(true), iconIgnorePlacement(true), iconRotationAlignment(Property.ICON_ROTATION_ALIGNMENT_MAP), iconRotate(0f), iconSize(1f)))
    }

    private fun puckBitmap(): Bitmap {
        val s = dp(44); val b = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888); val c = Canvas(b)
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ContextCompat.getColor(this@MainActivity, R.color.accent) }
        val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; style = Paint.Style.STROKE; strokeWidth = dp(3).toFloat(); strokeJoin = Paint.Join.ROUND }
        val p = Path(); val cx = s / 2f; val cy = s / 2f
        p.moveTo(cx, cy - s * 0.42f); p.lineTo(cx + s * 0.3f, cy + s * 0.34f); p.lineTo(cx, cy + s * 0.16f); p.lineTo(cx - s * 0.3f, cy + s * 0.34f); p.close()
        c.drawPath(p, fill); c.drawPath(p, stroke)
        return b
    }

    private fun startBitmap(): Bitmap {
        val s = dp(24); val b = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888); val c = Canvas(b)
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#16A34A") }
        val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; style = Paint.Style.STROKE; strokeWidth = dp(3).toFloat() }
        c.drawCircle(s / 2f, s / 2f, s * 0.34f, fill); c.drawCircle(s / 2f, s / 2f, s * 0.34f, stroke)
        return b
    }
    private fun drawStart() { srcStart?.setGeoJson(planStart?.let { FeatureCollection.fromFeatures(arrayOf(Feature.fromGeometry(Point.fromLngLat(it.longitude, it.latitude)))) } ?: FeatureCollection.fromFeatures(emptyArray())) }

    /** Plan from a point on the map instead of where the phone is. Cleared by END. */
    private fun setStart(ll: LatLng) {
        planStart = ll; startName = placeName(pinName); drawStart()
        pin = null; clearPinMarker()
        toast("Start set — now pick a destination (GO TO, or long-press and ROUTE HERE)", 5000)
        val dest = destination
        if (dest != null) startCompare(startFix(), dest) else switchMode(Mode.IDLE)
    }

    private fun startFix(): Fix {
        val ps = planStart
        if (ps != null) return Fix(ps.latitude, ps.longitude, -1.0, -1.0, android.os.SystemClock.elapsedRealtime(), 50.0)
        return lastFix ?: Fix(map?.cameraPosition?.target?.latitude ?: 0.0, map?.cameraPosition?.target?.longitude ?: 0.0, -1.0, -1.0, android.os.SystemClock.elapsedRealtime(), 50.0)
    }

    private fun viaBitmap(): Bitmap {
        val s = dp(22); val b = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888); val c = Canvas(b)
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ContextCompat.getColor(this@MainActivity, R.color.route) }
        val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; style = Paint.Style.STROKE; strokeWidth = dp(3).toFloat() }
        c.drawCircle(s / 2f, s / 2f, s * 0.32f, fill); c.drawCircle(s / 2f, s / 2f, s * 0.32f, stroke)
        return b
    }

    /** A router's turn in plan mode: a small hollow ring in the route colour. */
    private fun turnBitmap(): Bitmap {
        val s = dp(16); val b = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888); val c = Canvas(b)
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
        val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ContextCompat.getColor(this@MainActivity, R.color.ink); style = Paint.Style.STROKE; strokeWidth = dp(2).toFloat() }
        c.drawCircle(s / 2f, s / 2f, s * 0.3f, fill); c.drawCircle(s / 2f, s / 2f, s * 0.3f, stroke)
        return b
    }

    /** Cue marker: a small round sign, orange for a danger, ink for everything else. */
    private fun cueBitmap(danger: Boolean): Bitmap {
        val s = dp(22); val b = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888); val c = Canvas(b)
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = if (danger) ContextCompat.getColor(this@MainActivity, R.color.accent) else ContextCompat.getColor(this@MainActivity, R.color.ink) }
        val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; style = Paint.Style.STROKE; strokeWidth = dp(2).toFloat() }
        c.drawCircle(s / 2f, s / 2f, s * 0.4f, fill); c.drawCircle(s / 2f, s / 2f, s * 0.4f, stroke)
        return b
    }

    private fun drawCues() {
        val shown = if (mode == Mode.COMPARE) editCues else trackCues
        val feats = shown.mapIndexed { i, c -> Feature.fromGeometry(Point.fromLngLat(c.lon, c.lat)).also { f ->
            f.addStringProperty("icon", if (c.turn == Turn.DANGER) "cue-danger" else "cue"); f.addNumberProperty("idx", i)
            f.addStringProperty("label", if (c.note.isNotEmpty()) c.note.take(24) else c.turn.spoken) } }
        srcCues?.setGeoJson(FeatureCollection.fromFeatures(feats))
    }

    private fun markBitmap(): Bitmap {
        val s = dp(26); val b = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888); val c = Canvas(b)
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ContextCompat.getColor(this@MainActivity, R.color.track) }
        val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; style = Paint.Style.STROKE; strokeWidth = dp(2).toFloat(); strokeJoin = Paint.Join.ROUND }
        val p = Path(); val cx = s / 2f; val cy = s / 2f; val r = s * 0.42f
        for (i in 0 until 10) { val ang = Math.PI / 2 + i * Math.PI / 5; val rr = if (i % 2 == 0) r else r * 0.45f; val x = (cx + rr * Math.cos(ang)).toFloat(); val y = (cy - rr * Math.sin(ang)).toFloat(); if (i == 0) p.moveTo(x, y) else p.lineTo(x, y) }
        p.close(); c.drawPath(p, fill); c.drawPath(p, stroke)
        return b
    }

    /** The visible packs' places onto the map (parsed on the worker the first time a pack is seen). */
    private fun drawPlaces() {
        val src = srcPlaces ?: return
        if (!prefs.places) { src.setGeoJson(FeatureCollection.fromFeatures(emptyArray())); placesFor = emptyList(); return }
        val packs = styledPacks.filter { !it.isTerrain }
        if (packs == placesFor) return
        placesFor = packs
        worker.execute {
            val feats = ArrayList<Feature>()
            for (p in packs) for (pl in p.loadPlaces()) feats.add(Feature.fromGeometry(Point.fromLngLat(pl.lon, pl.lat)).also { f ->
                f.addStringProperty("icon", pl.icon); f.addStringProperty("name", pl.name); f.addStringProperty("label", pl.label); f.addNumberProperty("minzoom", pl.minZoom) })
            val fc = FeatureCollection.fromFeatures(feats)
            ui.post { if (placesFor == packs) srcPlaces?.setGeoJson(fc) }
        }
    }

    /** Tap on a place: it becomes the pin, named, with the usual ROUTE HERE / VIA / FROM / ★. */
    private fun openPlace(f: Feature) {
        val pt = f.geometry() as? Point ?: return
        val ll = LatLng(pt.latitude(), pt.longitude())
        val label = f.getStringProperty("label") ?: ""
        pin = ll; pinName = (f.getStringProperty("name") ?: "").ifEmpty { label.substringBefore(" · ") }
        showPinMarker(ll); pinLabel.text = label
        follow = false
        switchMode(Mode.PIN)
    }

    private fun drawCoverage() {
        srcFog?.setGeoJson(Coverage.fog(packs.roadPacks)); srcCover?.setGeoJson(Coverage.outlines(packs.roadPacks)); srcCoverLabel?.setGeoJson(Coverage.labels(packs.roadPacks))
    }

    private fun drawMarks() {
        val feats = marks.marks.map { m -> Feature.fromGeometry(Point.fromLngLat(m.lon, m.lat)).also { f -> f.addStringProperty("name", m.name); f.addNumberProperty("id", m.id) } }
        srcMarks?.setGeoJson(FeatureCollection.fromFeatures(feats))
    }

    private fun openMark(m: Mark) {
        MarkDialog.show(this, m,
            onRoute = { routeTo(LatLng(m.lat, m.lon), m.name) },
            onEdit = { MarkDialog.edit(this, m, m.name) { name, note -> safe { marks.update(m, name, note); drawMarks() } } },
            onDelete = { safe { marks.remove(m); drawMarks(); toast("Deleted ${m.name}") } })
    }

    fun goToMark(m: Mark) {
        val ll = LatLng(m.lat, m.lon)
        pin = ll; pinName = m.name; showPinMarker(ll); pinLabel.text = m.name + if (m.note.isNotEmpty()) " — ${m.note}" else ""
        follow = false
        map?.easeCamera(CameraUpdateFactory.newLatLngZoom(ll, 15.0), 800)
        switchMode(Mode.PIN)
    }

    private fun pinBitmap(): Bitmap {
        val w = dp(36); val h = dp(48); val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888); val c = Canvas(b)
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ContextCompat.getColor(this@MainActivity, R.color.accent) }
        val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; style = Paint.Style.STROKE; strokeWidth = dp(3).toFloat() }
        val p = Path(); val cx = w / 2f; val r = w * 0.4f
        p.addCircle(cx, r + dp(2), r, Path.Direction.CW)
        p.moveTo(cx - r * 0.6f, r * 1.6f); p.lineTo(cx, h - dp(2).toFloat()); p.lineTo(cx + r * 0.6f, r * 1.6f); p.close()
        c.drawPath(p, fill); c.drawPath(p, stroke)
        val hole = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
        c.drawCircle(cx, r + dp(2), r * 0.35f, hole)
        return b
    }

    private fun updatePuck(lat: Double, lon: Double, hdg: Double) {
        srcPuck?.setGeoJson(Feature.fromGeometry(Point.fromLngLat(lon, lat)))
        style?.getLayer("puck-layer")?.setProperties(iconRotate(hdg.toFloat()))
    }

    private fun drawRoute(r: Route) {
        val pts = ArrayList<Point>(r.size)
        for (i in 0 until r.size) pts.add(Point.fromLngLat(r.lons[i], r.lats[i]))
        srcRoute?.setGeoJson(Feature.fromGeometry(LineString.fromLngLats(pts)))
        val color = if (r.isTrack) ContextCompat.getColor(this, R.color.track) else Color.parseColor(profile.color)
        style?.getLayer("route-line")?.setProperties(lineColor(color))
        style?.getLayer("lead-line")?.setProperties(lineColor(color))
    }

    private fun showPinMarker(ll: LatLng) { srcPin?.setGeoJson(Feature.fromGeometry(Point.fromLngLat(ll.longitude, ll.latitude))) }
    private fun clearPinMarker() { srcPin?.setGeoJson(FeatureCollection.fromFeatures(emptyArray())) }

    // ---------------------------------------------------------------- location

    private fun ensureLocation() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) startLocation()
        else askLocation.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
    }

    private val locationListener = object : LocationListener {
        override fun onLocationChanged(loc: Location) { onFix(loc) }
        override fun onProviderEnabled(provider: String) {}
        override fun onProviderDisabled(provider: String) { toast("GPS is off") }
        @Deprecated("Deprecated in Java") override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
    }

    @SuppressLint("MissingPermission")
    private fun startLocation() {
        try {
            locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, locationListener, Looper.getMainLooper())
        } catch (e: Throwable) { toast("No GPS provider: ${e.message}") }
    }

    private fun onFix(loc: Location) {
        // monotonic clock for everything time-based, so a wrong wall clock can never confuse dead reckoning
        val fix = Fix(loc.latitude, loc.longitude, if (loc.hasSpeed()) loc.speed.toDouble() else -1.0, if (loc.hasBearing()) loc.bearing.toDouble() else -1.0, loc.elapsedRealtimeNanos / 1_000_000L, if (loc.hasAccuracy()) loc.accuracy.toDouble() else 50.0)
        val first = lastFix == null
        lastFix = fix
        lastAltitude = when { android.os.Build.VERSION.SDK_INT >= 34 && loc.hasMslAltitude() -> loc.mslAltitudeMeters; loc.hasAltitude() -> loc.altitude; else -> lastAltitude }
        if (fix.speed > 1.5 && fix.bearing >= 0) heading = fix.bearing
        val f = follower
        if (f != null) {
            val v = f.view(android.os.SystemClock.elapsedRealtime())
            // the marker points where the GPS says the bike points, never along the road it is assumed to be on
            if (mode == Mode.NAV) { updatePuck(v.lat, v.lon, if (v.speed > 1.5) v.heading else heading); handleOffRoute(v, fix) }
            else updatePuck(fix.lat, fix.lon, heading)
        } else updatePuck(fix.lat, fix.lon, heading)
        if ((follow || (first && !planningCamera)) && uiVisible) moveCamera(fix.lat, fix.lon, animate = !first)
    }

    private fun haptic(times: Int, long: Boolean = false) {
        val kind = if (long) android.view.HapticFeedbackConstants.LONG_PRESS else if (android.os.Build.VERSION.SDK_INT >= 30) android.view.HapticFeedbackConstants.CONFIRM else android.view.HapticFeedbackConstants.VIRTUAL_KEY
        val flags = android.view.HapticFeedbackConstants.FLAG_IGNORE_VIEW_SETTING
        for (i in 0 until times) ui.postDelayed({ mapView.performHapticFeedback(kind, flags) }, i * 120L)
    }

    private fun cycleZoom() {
        zoomMode = when (zoomMode) { ZoomMode.OVERVIEW -> ZoomMode.TURN; ZoomMode.TURN -> ZoomMode.CLOSE; ZoomMode.CLOSE -> ZoomMode.OVERVIEW }
        // feel which view you landed in without looking: one tap = next turn, two = close up, long = whole route
        when (zoomMode) { ZoomMode.TURN -> haptic(1); ZoomMode.CLOSE -> haptic(2); ZoomMode.OVERVIEW -> haptic(1, long = true) }
        manualZoom = -1.0; overviewDone = false
        follow = true
        toast(when (zoomMode) { ZoomMode.OVERVIEW -> "Whole route"; ZoomMode.TURN -> "Next turn"; ZoomMode.CLOSE -> "Close up" })
        lastFix?.let { moveCamera(it.lat, it.lon, animate = true) }
    }

    /** Zoom that puts the next maneuver comfortably on screen, bounded to a useful range. */
    private fun turnZoom(): Double {
        val f = follower ?: return 15.5
        val v = f.view(android.os.SystemClock.elapsedRealtime())
        // straight-line distance to the turn point (the route can wander, but the turn itself must be on screen)
        val r = f.route; val m = v.next
        val d = (if (m != null) Geo.fastDistance(v.lat, v.lon, r.lats[m.vertex], r.lons[m.vertex]) else v.distToNext).coerceAtLeast(60.0)
        val lat = lastFix?.lat ?: 45.0
        // meters per pixel at zoom z ≈ 156543 * cos(lat) / 2^z. You sit 70% down the screen, so the turn
        // must fit in the smaller of the room above you and half the width, whatever direction it lies.
        val hudBottom = if (hud.visibility == View.VISIBLE) hud.bottom.toDouble() else 0.0
        val room = minOf((mapView.height * 0.70 - hudBottom) * 0.80, mapView.width * 0.42).coerceAtLeast(200.0)
        val wantMpp = d / room
        val z = Math.log(156543.03 * Math.cos(Math.toRadians(lat)) / wantMpp) / Math.log(2.0)
        return z.coerceIn(4.0, 16.5)
    }

    private fun moveCamera(lat: Double, lon: Double, animate: Boolean) {
        val m = map ?: return
        val nav = mode == Mode.NAV
        if (nav && zoomMode == ZoomMode.OVERVIEW) {
            if (overviewDone) return
            overviewDone = true
            val r = route ?: return
            val b = LatLngBounds.Builder(); for (i in 0 until r.size) b.include(LatLng(r.lats[i], r.lons[i])); b.include(LatLng(lat, lon))
            val pos = CameraPosition.Builder().bearing(0.0).tilt(0.0).padding(0.0, 0.0, 0.0, 0.0).build()
            m.easeCamera(CameraUpdateFactory.newCameraPosition(pos), 300)
            ui.postDelayed({ try { m.easeCamera(CameraUpdateFactory.newLatLngBounds(b.build(), dp(24), mapView.height / 5, dp(24), mapView.height / 4), if (animate) 800 else 0) } catch (_: Throwable) {} }, if (animate) 320 else 0)
            return
        }
        val zoom = when {
            !nav -> max(m.cameraPosition.zoom, 15.0)
            manualZoom > 0 -> manualZoom
            zoomMode == ZoomMode.CLOSE -> 17.3
            else -> turnZoom()
        }
        val bearing = if (nav) heading else 0.0
        // padding centers the target in the remaining area: top 0.5h, bottom 0.1h puts you at 70% down the screen
        val topPad = if (nav) mapView.height * 0.5 else 0.0
        val bottomPad = if (nav) mapView.height * 0.1 else 0.0
        val pos = CameraPosition.Builder().target(LatLng(lat, lon)).zoom(zoom).bearing(bearing).tilt(0.0).padding(0.0, topPad, 0.0, bottomPad).build()
        val update = CameraUpdateFactory.newCameraPosition(pos)
        if (animate) m.easeCamera(update, 1000) else m.moveCamera(update)
    }

    // ---------------------------------------------------------------- pin / routing

    private fun onLongPress(ll: LatLng) {
        if (mode == Mode.EDIT) { addCueAt(ll.latitude, ll.longitude, snapToTrack = true); return }
        if (mode == Mode.COMPARE && snapToPlan(ll.latitude, ll.longitude) != null) { addPlanCueAt(ll.latitude, ll.longitude); return }
        if (route != null) follow = false
        pin = ll; pinName = "%.5f, %.5f".format(ll.latitude, ll.longitude)
        showPinMarker(ll)
        pinLabel.text = pinName
        switchMode(Mode.PIN)
    }

    private fun onSearchPick(hit: Hit) {
        val ll = LatLng(hit.lat, hit.lon)
        pin = ll; pinName = hit.label
        showPinMarker(ll); pinLabel.text = pinName
        follow = false
        map?.easeCamera(CameraUpdateFactory.newLatLngZoom(ll, 15.0), 800)
        switchMode(Mode.PIN)
    }

    private fun exportMarks() {
        val tree = FilePicker.folder(this) ?: run { toast("No folder chosen"); return }
        try { toast("Wrote " + marks.exportTo(tree)) } catch (e: Throwable) { toast("Export failed: ${e.message}") }
    }

    private fun clearPin() {
        pin = null; clearPinMarker()
        when {
            candidates.isNotEmpty() -> { destination?.let { showPinMarker(it) }; switchMode(Mode.COMPARE); drawCandidates() }
            route != null -> { destination?.let { showPinMarker(it) }; switchMode(Mode.NAV) }
            else -> switchMode(Mode.IDLE)
        }
    }

    /**
     * Routes to a point. Starts from the GPS fix; without one (indoors, planning a trip at the kitchen
     * table) it starts from the last position the phone knew, or failing that the middle of the map, and says so.
     */
    private fun routeTo(dest: LatLng, label: String) {
        var fix = lastFix
        var fromWhere = ""
        planStart?.let { ps -> fix = Fix(ps.latitude, ps.longitude, -1.0, -1.0, android.os.SystemClock.elapsedRealtime(), 50.0); fromWhere = "Routed from your chosen start" }
        if (fix == null) {
            val last = try { locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER) } catch (_: Throwable) { null }
            if (last != null) { fix = Fix(last.latitude, last.longitude, -1.0, -1.0, android.os.SystemClock.elapsedRealtime(), 50.0); fromWhere = "No GPS fix yet — routed from your last known position" }
            else {
                val c = map?.cameraPosition?.target ?: run { toast("Waiting for a GPS fix…"); return }
                fix = Fix(c.latitude, c.longitude, -1.0, -1.0, android.os.SystemClock.elapsedRealtime(), 50.0); fromWhere = "No GPS fix yet — routed from the middle of the map"
            }
        }
        val f0 = fix!!
        if (packs.packFor(f0.lat, f0.lon) == null) { toast(if (lastFix == null) "No GPS fix, and the map center is outside your maps. Move the map over an installed pack or wait for GPS." else "You are outside your maps.", 6000); return }
        if (packs.packFor(dest.latitude, dest.longitude) == null) { toast(getString(R.string.outside_maps), 6000); return }
        destination = dest; destName = label; vias.clear(); viaNames.clear()
        if (fromWhere.isNotEmpty()) toast(fromWhere, 6000)
        pin = null; clearPinMarker(); showPinMarker(dest)
        startCompare(f0, dest)
    }

    /**
     * "Go by here": pulls the route through a point. The via is slotted into the list by where it
     * falls along the current route, so you can add them in any order.
     */
    private fun addVia(ll: LatLng, label: String) {
        val dest = destination ?: return
        val r = route
        val idx = if (r == null || vias.isEmpty()) vias.size else {
            fun along(p: LatLng): Double { var best = Double.MAX_VALUE; var at = 0.0; for (i in 0 until r.size) { val d = Geo.fastDistance(p.latitude, p.longitude, r.lats[i], r.lons[i]); if (d < best) { best = d; at = r.cum[i] } }; return at }
            val a = along(ll); vias.indexOfFirst { along(it) > a }.let { if (it < 0) vias.size else it }
        }
        vias.add(idx, ll); viaNames.add(idx, placeName(label))
        pin = null; clearPinMarker()
        val fix = startFix()
        toast("Via " + viaNames[idx].ifEmpty { "point" } + " — routing…")
        drawVias()
        if (mode == Mode.NAV) computeRoute(fix, dest, initial = true) else startCompare(compareFrom ?: fix, dest)
    }

    private fun drawVias() {
        srcVia?.setGeoJson(FeatureCollection.fromFeatures(vias.mapIndexed { i, v -> Feature.fromGeometry(Point.fromLngLat(v.longitude, v.latitude)).also { it.addNumberProperty("idx", i) } }))
    }

    /** A search label is a name; a long-press label is coordinates, which is no name at all. */
    private fun placeName(label: String): String = if (label.isNotEmpty() && !label.first().isDigit() && label.first() != '-') label else ""

    /** Tap on a via: take it out of the route (and route again without it). */
    private fun offerRemoveVia(idx: Int) {
        val v = vias[idx]
        androidx.appcompat.app.AlertDialog.Builder(this).setTitle("Stop ${'A' + idx}" + viaNames.getOrNull(idx)?.takeIf { it.isNotEmpty() }?.let { " · $it" }.orEmpty())
            .setMessage("%.5f, %.5f".format(v.latitude, v.longitude))
            .setPositiveButton("REMOVE VIA") { _, _ -> removeVia(idx) }
            .setNegativeButton(R.string.cancel, null).show()
    }

    private fun removeVia(idx: Int) {
        if (idx !in vias.indices) return
        vias.removeAt(idx); if (idx in viaNames.indices) viaNames.removeAt(idx); drawVias()
        val dest = destination ?: return
        toast("Via removed — routing…")
        if (mode == Mode.NAV) computeRoute(lastFix ?: startFix(), dest, initial = true) else startCompare(compareFrom ?: startFix(), dest)
    }

    /** Reroute (or single-mode routing): one mode, straight into navigation. */
    private fun computeRoute(from: Fix, dest: LatLng, initial: Boolean) {
        val gen = ++routeGeneration
        val p = profile
        if (initial && toastView.visibility != View.VISIBLE) toast("Routing…")
        rerouting = true
        val viasCopy = ArrayList(vias)
        worker.execute {
            val result = try { routeOne(from, dest, viasCopy, p) } catch (e: Throwable) { e } finally { try { packs.multiRouter().releaseMemory() } catch (_: Throwable) {} }
            ui.post {
                rerouting = false
                if (gen != routeGeneration) return@post
                when (result) {
                    is Route -> startNavigation(planned(result), false)
                    is Throwable -> { if (initial) toast(result.message ?: "Routing failed", 6000) }
                }
            }
        }
    }

    /** Worker-thread routing for one mode through the vias. Throws with a readable message. */
    private fun routeOne(from: Fix, dest: LatLng, viasCopy: List<LatLng>, p: Profile): Route {
        val router = packs.multiRouter()
        if (router.routers.isEmpty()) throw IllegalStateException("Could not open routing data")
        packs.routerErrors.firstOrNull()?.let { ui.post { toast(it, 6000) } }
        if (router.snap(from.lat, from.lon, p) == null) throw IllegalStateException("No road near you")
        if (router.snap(dest.latitude, dest.longitude, p, 6000.0) == null) throw IllegalStateException("No road within 6 km of the destination")
        val pts = ArrayList<DoubleArray>()
        pts.add(doubleArrayOf(from.lat, from.lon))
        for (v in viasCopy) { if (router.snap(v.latitude, v.longitude, p, 6000.0) == null) throw IllegalStateException("No road within 6 km of a via point"); pts.add(doubleArrayOf(v.latitude, v.longitude)) }
        pts.add(doubleArrayOf(dest.latitude, dest.longitude))
        val t0 = android.os.SystemClock.elapsedRealtime()
        val rr = router.routeVia(pts, p) ?: throw IllegalStateException(getString(R.string.no_route))
        android.util.Log.i("Fold", "route ${p.name}: ${android.os.SystemClock.elapsedRealtime() - t0} ms")
        return Route.fromGraphRoute(rr, router, p)
    }

    /**
     * The compare screen: every enabled mode is routed in turn and drawn as it finishes, whole-route
     * view, with a row per mode. Tap a row (or a line) to select; GO starts; VIA pulls all of them.
     */
    private fun startCompare(from: Fix, dest: LatLng) {
        val modes = (compareModes ?: prefs.modes.toList()).sortedBy { it.ordinal }
        candidates.clear()
        compareFrom = from
        if (!modes.contains(profile)) profile = modes.first()
        switchMode(Mode.COMPARE)
        rebuildCompareRows(modes, inProgress = true)
        val gen = ++compareGeneration
        routeGeneration++   // cancel any single-mode routing in flight
        val viasCopy = ArrayList(vias)
        follow = false
        worker.execute {
            var firstError: String? = null
            try {
            for (p in modes) {
                if (gen != compareGeneration) return@execute
                val r = try { routeOne(from, dest, viasCopy, p) } catch (e: Throwable) { if (firstError == null) firstError = e.message; null }
                ui.post {
                    if (gen != compareGeneration) return@post
                    if (r != null) candidates[p] = r
                    rebuildCompareRows(modes, inProgress = p != modes.last())
                    drawCandidates(); drawCues()
                    if (candidates.size == 1 || p == modes.last()) fitCandidates()
                }
            }
            } finally {
                // the search arrays stayed allocated across the modes; the compare is over (or cancelled), so let them go
                try { packs.multiRouter().releaseMemory() } catch (_: Throwable) {}
            }
            ui.post {
                if (gen != compareGeneration) return@post
                if (candidates.isEmpty()) { toast(firstError ?: "No route found", 6000); cancelCompare() }
                else if (!candidates.containsKey(profile)) { profile = candidates.keys.first(); rebuildCompareRows(modes, false); drawCandidates() }
            }
        }
    }

    private fun rebuildCompareRows(modes: List<Profile>, inProgress: Boolean) {
        compareRows.removeAllViews()
        if (planStart != null || vias.isNotEmpty() || startName.isNotEmpty()) compareRows.addView(TextView(this).apply {
            text = planTitle(); textSize = 13f; setTextColor(ContextCompat.getColor(this@MainActivity, R.color.ink_soft)); maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END
            setPadding(dp(14), dp(6), dp(14), dp(2)); setOnClickListener { showStops() } })
        for (p in modes) {
            val r = candidates[p]
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = android.view.Gravity.CENTER_VERTICAL; setPadding(dp(14), dp(8), dp(14), dp(8)) }
            val swatch = View(this).apply { setBackgroundColor(Color.parseColor(p.color)); layoutParams = LinearLayout.LayoutParams(dp(18), dp(6)).also { it.marginEnd = dp(12) } }
            val name = TextView(this).apply { text = p.label; textSize = 16f; setTextColor(ContextCompat.getColor(this@MainActivity, R.color.ink)); setTypeface(null, if (p == profile) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL) }
            val info = TextView(this).apply { textSize = 15f; setTextColor(ContextCompat.getColor(this@MainActivity, R.color.ink_soft)); gravity = android.view.Gravity.END
                text = when { r != null -> Units.distance(r.length) + " · " + Units.time(r.timeSeconds); inProgress && candidates.size < modes.indexOf(p) + 1 && candidates.keys.none { it.ordinal > p.ordinal } -> "…"; else -> "no route" } }
            row.addView(swatch); row.addView(name, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)); row.addView(info)
            row.alpha = if (r == null) 0.5f else 1f
            if (r != null) row.setOnClickListener { profile = p; prefs.profile = p; rebuildCompareRows(modes, inProgress); drawCandidates() }
            if (p == profile && r != null) row.setBackgroundColor(ContextCompat.getColor(this, R.color.bar_bg))
            compareRows.addView(row)
        }
    }

    private fun drawCandidates() {
        val feats = candidates.map { (p, r) ->
            val pts = ArrayList<Point>(r.size); for (i in 0 until r.size) pts.add(Point.fromLngLat(r.lons[i], r.lats[i]))
            Feature.fromGeometry(LineString.fromLngLats(pts)).also { f -> f.addStringProperty("color", p.color); f.addBooleanProperty("sel", p == profile); f.addStringProperty("mode", p.name) }
        }
        srcCompare?.setGeoJson(FeatureCollection.fromFeatures(feats))
        drawTurns()
    }

    /** Plan mode: the router's turns on the selected route (those not already reworded), so a tap can reword them. */
    private fun drawTurns() {
        val r = if (mode == Mode.COMPARE) candidates[profile] else null
        if (r == null) { srcTurns?.setGeoJson(FeatureCollection.fromFeatures(emptyArray())); return }
        val feats = ArrayList<Feature>()
        for ((i, m) in r.maneuvers.withIndex()) {
            if (m.turn == Turn.DEPART || m.turn == Turn.ARRIVE) continue
            if (editCues.any { c -> c.turn == m.turn && Geo.fastDistance(c.lat, c.lon, r.lats[m.vertex], r.lons[m.vertex]) < 40.0 }) continue
            feats.add(Feature.fromGeometry(Point.fromLngLat(r.lons[m.vertex], r.lats[m.vertex])).also { it.addNumberProperty("idx", i) })
        }
        srcTurns?.setGeoJson(FeatureCollection.fromFeatures(feats))
    }

    private fun fitCandidates() {
        val m = map ?: return
        val b = LatLngBounds.Builder(); var n = 0
        for (r in candidates.values) for (i in 0 until r.size step maxOf(1, r.size / 400)) { b.include(LatLng(r.lats[i], r.lons[i])); n++ }
        compareFrom?.let { b.include(LatLng(it.lat, it.lon)) }
        if (n < 2) return
        try { m.easeCamera(CameraUpdateFactory.newLatLngBounds(b.build(), dp(24), dp(40), dp(24), (compareRows.height + dp(120)).coerceAtLeast(mapView.height / 3)), 700) } catch (_: Throwable) {}
    }

    private fun goWithSelected() {
        val r = candidates[profile] ?: run { toast("Pick a route first"); return }
        compareGeneration++
        startNavigation(planned(r), false)
    }

    private fun cancelCompare() {
        compareGeneration++
        candidates.clear()
        srcCompare?.setGeoJson(FeatureCollection.fromFeatures(emptyArray()))
        if (route != null) { switchMode(Mode.NAV) } else { destination = null; destName = ""; vias.clear(); viaNames.clear(); drawVias(); clearPinMarker(); planStart = null; startName = ""; drawStart(); editCues.clear(); compareModes = null; drawCues(); switchMode(Mode.IDLE) }
    }

    // ---------------------------------------------------------------- plan mode: the planner's job, on the phone, offline
    //
    // PLAN asks for a start and an end by name; stops are added by search (VIA) or a long press; STOPS reorders them, closes a
    // loop or reverses the ride; a long press on the chosen route adds a note or a danger; a tap on one of the router's
    // turns rewords it. Everything lands in editCues, which `planned()` lays onto whichever route is chosen, and SAVE writes
    // the lot as a roadbook GPX that EDIT ROUTE opens again.

    /** PLAN from the menu: a plan in progress comes back up; otherwise the start is asked for, then the end. */
    private fun startPlan() {
        if (destination != null && candidates.isNotEmpty()) { switchMode(Mode.COMPARE); drawCandidates(); drawCues(); fitCandidates(); return }
        if (route != null) { toast("End the ride first (menu → END)"); return }
        follow = false
        val fromHere: Pair<String, () -> Unit>? = if (lastFix != null) Pair<String, () -> Unit>("FROM WHERE I AM", { planStart = null; startName = ""; drawStart(); askEnd() }) else null
        SearchDialog.show(this, packs, marks, { hit -> setStartNamed(LatLng(hit.lat, hit.lon), hit.label); askEnd() }, { m -> setStartNamed(LatLng(m.lat, m.lon), m.name); askEnd() },
            title = "Plan a ride · where does it start?", extra = fromHere)
    }

    private fun askEnd() {
        SearchDialog.show(this, packs, marks, { hit -> routeTo(LatLng(hit.lat, hit.lon), hit.label) }, { m -> routeTo(LatLng(m.lat, m.lon), m.name) },
            title = "…and where does it end?", extra = Pair<String, () -> Unit>("BACK TO THE START (LOOP)", { planLoopFromStart() }))
    }

    /** A loop needs somewhere to ride out to: the end is the start, and the first stop is asked for next. */
    private fun planLoopFromStart() {
        val ps = planStart ?: lastFix?.let { LatLng(it.lat, it.lon) } ?: run { toast("Waiting for a GPS fix…"); return }
        if (packs.packFor(ps.latitude, ps.longitude) == null) { toast("The start is outside your maps", 6000); return }
        if (planStart == null) { planStart = ps; drawStart() }
        destination = ps; destName = startName.ifEmpty { "Start" }; vias.clear(); viaNames.clear(); editCues.clear(); compareModes = null
        pin = null; clearPinMarker(); showPinMarker(ps)
        SearchDialog.show(this, packs, marks, { hit -> addVia(LatLng(hit.lat, hit.lon), hit.label) }, { m -> addVia(LatLng(m.lat, m.lon), m.name) },
            title = "A loop · where does it turn around?", extra = Pair<String, () -> Unit>("PICK ON THE MAP", { toast("Long-press the map where the ride turns around, then VIA", 6000) }))
    }

    private fun setStartNamed(ll: LatLng, label: String) {
        planStart = ll; startName = placeName(label); drawStart()
        follow = false
        map?.easeCamera(CameraUpdateFactory.newLatLngZoom(ll, 12.0), 600)
    }

    /** The plan's points in ride order with their names: start, stops, end. */
    private fun planPoints(): List<Pair<LatLng, String>> {
        val from = planStart ?: compareFrom?.let { LatLng(it.lat, it.lon) } ?: lastFix?.let { LatLng(it.lat, it.lon) } ?: return emptyList()
        val out = ArrayList<Pair<LatLng, String>>()
        out.add(from to startName)
        for (i in vias.indices) out.add(vias[i] to viaNames.getOrElse(i) { "" })
        destination?.let { out.add(it to destName) }
        return out
    }

    private fun planTitle(): String {
        val pts = planPoints(); if (pts.size < 2) return destName
        fun nm(p: Pair<LatLng, String>, fallback: String) = p.second.ifEmpty { fallback }
        val loop = isLoop()
        val mid = if (vias.isEmpty()) "" else " → " + vias.indices.joinToString(" → ") { viaNames.getOrElse(it) { "" }.ifEmpty { "stop ${'A' + it}" } }
        return nm(pts.first(), if (planStart == null) "Here" else "Start") + mid + " → " + (if (loop) "back to start" else nm(pts.last(), "End"))
    }

    private fun isLoop(): Boolean {
        val d = destination ?: return false
        val from = planStart ?: compareFrom?.let { LatLng(it.lat, it.lon) } ?: return false
        return Geo.fastDistance(d.latitude, d.longitude, from.latitude, from.longitude) < 30.0
    }

    /** LOOP: the end becomes the last stop and the ride ends where it started. OPEN LOOP undoes that. */
    private fun toggleLoop() {
        val d = destination ?: return
        val from = planStart ?: compareFrom?.let { LatLng(it.lat, it.lon) } ?: return
        if (isLoop()) {
            if (vias.isEmpty()) { toast("An out-and-back needs a stop to turn around at — add one with VIA"); return }
            destination = vias.removeAt(vias.size - 1); destName = viaNames.removeAt(viaNames.size - 1)
        } else {
            vias.add(d); viaNames.add(destName)
            if (planStart == null) { planStart = from; drawStart() }
            destination = from; destName = startName.ifEmpty { "Start" }
        }
        drawVias(); clearPinMarker(); destination?.let { showPinMarker(it) }
        startCompare(startFix(), destination!!)
    }

    /** REVERSE: ride it the other way round. Notes and dangers stay where they are; reworded turns are dropped (they would face the wrong way). */
    private fun reversePlan() {
        val d = destination ?: return
        val from = planStart ?: compareFrom?.let { LatLng(it.lat, it.lon) } ?: return
        val loop = isLoop()
        if (!loop) {
            planStart = d; val sn = startName; startName = destName
            destination = from; destName = sn.ifEmpty { "Start" }
        }
        vias.reverse(); viaNames.reverse()
        val dropped = editCues.count { it.turn != Turn.NOTE && it.turn != Turn.DANGER }
        editCues.removeAll { it.turn != Turn.NOTE && it.turn != Turn.DANGER }
        drawStart(); drawVias(); clearPinMarker(); destination?.let { showPinMarker(it) }
        if (dropped > 0) toast("Reversed — $dropped reworded turn${if (dropped > 1) "s" else ""} dropped (they faced the other way)", 5000)
        startCompare(startFix(), destination!!)
    }

    /** STOPS: the ride's points in order; move or remove a stop, add one by search, close the loop, reverse. */
    private fun showStops() {
        val ctx = this
        val root = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(8), dp(4), dp(8), 0) }
        val list = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        root.addView(android.widget.ScrollView(ctx).apply { addView(list) }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        var changed = false
        lateinit var dlg: androidx.appcompat.app.AlertDialog
        fun rebuild() {
            list.removeAllViews()
            val pts = planPoints()
            for ((i, p) in pts.withIndex()) {
                val isStart = i == 0; val isEnd = i == pts.size - 1; val viaIdx = i - 1
                val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = android.view.Gravity.CENTER_VERTICAL; setPadding(dp(8), dp(4), dp(0), dp(4)) }
                val tag = TextView(ctx).apply { text = if (isStart) "START" else if (isEnd) "END" else ('A' + viaIdx).toString(); textSize = 12f; setTypeface(null, android.graphics.Typeface.BOLD); setTextColor(ContextCompat.getColor(ctx, R.color.ink_soft)); minWidth = dp(52) }
                val name = TextView(ctx).apply { text = p.second.ifEmpty { if (isStart && planStart == null) "Where you are" else "%.4f, %.4f".format(p.first.latitude, p.first.longitude) }; textSize = 16f; setTextColor(ContextCompat.getColor(ctx, R.color.ink)); maxLines = 2 }
                row.addView(tag); row.addView(name, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                fun small(label: String, enabled: Boolean, act: () -> Unit) = Button(ctx).apply { text = label; textSize = 16f; minWidth = dp(44); minimumWidth = dp(44); isEnabled = enabled; alpha = if (enabled) 1f else 0.25f; setOnClickListener { act(); changed = true; rebuild() } }
                row.addView(small("▲", viaIdx in 1 until vias.size) { java.util.Collections.swap(vias, viaIdx, viaIdx - 1); java.util.Collections.swap(viaNames, viaIdx, viaIdx - 1) })
                row.addView(small("▼", viaIdx in 0 until vias.size - 1) { java.util.Collections.swap(vias, viaIdx, viaIdx + 1); java.util.Collections.swap(viaNames, viaIdx, viaIdx + 1) })
                row.addView(small("✕", viaIdx in vias.indices) { vias.removeAt(viaIdx); viaNames.removeAt(viaIdx) })
                list.addView(row)
            }
            dlg.setTitle(planTitle())
        }
        dlg = androidx.appcompat.app.AlertDialog.Builder(ctx).setTitle(planTitle()).setView(root)
            .setPositiveButton("DONE") { _, _ -> if (changed) { drawVias(); startCompare(startFix(), destination ?: return@setPositiveButton) } }
            .setNeutralButton("ADD STOP") { _, _ ->
                SearchDialog.show(ctx, packs, marks, { hit -> addVia(LatLng(hit.lat, hit.lon), hit.label) }, { m -> addVia(LatLng(m.lat, m.lon), m.name) }, title = "Add a stop")
            }
            .setNegativeButton(if (isLoop()) "OPEN LOOP" else "LOOP") { _, _ -> toggleLoop() }
            .create()
        // a fourth action: reverse, as a row at the foot of the list
        root.addView(Button(ctx).apply { text = "REVERSE THE RIDE"; setOnClickListener { dlg.dismiss(); reversePlan() } })
        rebuild()
        dlg.show()
    }

    // ---- cues on a planned route

    /** The chosen route's point nearest a map point, if it is within 150 m: lat, lon, distance. */
    private fun snapToPlan(lat: Double, lon: Double): DoubleArray? {
        val r = candidates[profile] ?: return null
        val out = DoubleArray(4); var best = Double.MAX_VALUE; var bLat = lat; var bLon = lon
        for (i in 0 until r.size - 1) {
            if (Math.abs(r.lats[i] - lat) > 0.02 || Math.abs(r.lons[i] - lon) > 0.02) continue
            Geo.projectOnSegment(lat, lon, r.lats[i], r.lons[i], r.lats[i + 1], r.lons[i + 1], out)
            if (out[1] < best) { best = out[1]; bLat = out[2]; bLon = out[3] }
        }
        return if (best <= 150.0) doubleArrayOf(bLat, bLon, best) else null
    }

    /** Long press on the chosen route in plan mode: a note or a danger there (an arrow is allowed too). */
    private fun addPlanCueAt(lat: Double, lon: Double) {
        val s = snapToPlan(lat, lon) ?: return
        CueDialog.show(this, s[0], s[1], Cue(s[0], s[1], Turn.NOTE, ""), { c -> editCues.add(c); drawCues(); drawTurns(); haptic(1); toast(c.turn.spoken + if (c.note.isNotEmpty()) ": " + c.note else "") })
    }

    private fun editPlanCue(c: Cue) {
        CueDialog.show(this, c.lat, c.lon, c, { n -> val i = editCues.indexOf(c); if (i >= 0) editCues[i] = n; drawCues(); drawTurns() },
            onDelete = { editCues.remove(c); drawCues(); drawTurns(); toast("Cue deleted") })
    }

    /** Tap on one of the router's turns: say it in your own words (the arrow can change too). */
    private fun rewordTurn(idx: Int) {
        val r = candidates[profile] ?: return
        val m = r.maneuvers.getOrNull(idx) ?: return
        val lat = r.lats[m.vertex]; val lon = r.lons[m.vertex]
        CueDialog.show(this, lat, lon, Cue(lat, lon, m.turn, ""), { c ->
            if (c.turn == m.turn && c.note.isBlank()) return@show
            editCues.add(c); drawCues(); drawTurns(); toast(c.turn.spoken + if (c.note.isNotEmpty()) ": " + c.note else "")
        })
    }

    // ---- saved routes: GPX with the points as a route and the line as a track; anyone can open it

    private fun routeGpx(r0: Route): String {
        val r = planned(r0)
        val from = compareFrom?.let { LatLng(it.lat, it.lon) } ?: LatLng(r.lats[0], r.lons[0])
        val pts = ArrayList<LatLng>(); pts.add(from); pts.addAll(vias); destination?.let { pts.add(it) }
        val names = ArrayList<String>(); names.add(startName.ifEmpty { "Start" }); for (i in vias.indices) names.add(viaNames.getOrElse(i) { "" }.ifEmpty { "Stop ${'A' + i}" }); names.add(destName.ifEmpty { "End" })
        val title = (destName.ifEmpty { "Route" }) + " (" + profile.label + ")"
        val text = Gpx.writeRoute(title, profile.name, pts.map { it.latitude }.toDoubleArray(), pts.map { it.longitude }.toDoubleArray(), names, r.lats, r.lons)
        // Roadbook: the router's turns go in as cues (reworded ones with the rider's words, notes and dangers as they are),
        // so the file is a roadbook you can annotate and follow as a track, and EDIT ROUTE brings it back as this plan
        val cues = r.maneuvers.filter { it.turn != Turn.DEPART && it.turn != Turn.ARRIVE }.map { m ->
            Cue(r.lats[m.vertex], r.lons[m.vertex], m.turn, m.note.ifEmpty { if (m.turn == Turn.ROUNDABOUT) "exit ${m.exit}" else m.name }, m.level) }
        return text.replace("  <rte>", cues.joinToString("") { Gpx.cueXml(it) } + "  <rte>")
    }

    private fun routeFileName(): String = "fold-route-" + (destName.ifEmpty { "route" }).lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').take(40) + "-" + profile.name.lowercase() + ".gpx"

    private fun saveOrShareRoute(r: Route) {
        androidx.appcompat.app.AlertDialog.Builder(this).setTitle("Route: " + destName.ifEmpty { "route" })
            .setMessage(Units.distance(r.length) + " · " + Units.time(r.timeSeconds) + " · " + profile.label + "\n\nSaved as a GPX file: any map app opens it; Fold re-routes it on the recipient's own maps.")
            .setPositiveButton("SAVE TO FOLDER") { _, _ -> val tree = FilePicker.folder(this); if (tree == null) chooseFolder { writeRouteToFolder(r) } else writeRouteToFolder(r) }
            .setNeutralButton("SHARE…") { _, _ -> shareRoute(r) }
            .setNegativeButton(R.string.cancel, null).show()
    }

    private fun writeRouteToFolder(r: Route) {
        val tree = FilePicker.folder(this) ?: run { toast("No folder chosen"); return }
        try {
            val parent = android.provider.DocumentsContract.buildChildDocumentsUriUsingTree(tree, android.provider.DocumentsContract.getTreeDocumentId(tree))
            val name = routeFileName()
            val doc = android.provider.DocumentsContract.createDocument(contentResolver, parent, "application/gpx+xml", name) ?: throw IllegalStateException("Could not create file")
            contentResolver.openOutputStream(doc)?.use { it.write(routeGpx(r).toByteArray()) } ?: throw IllegalStateException("Could not write file")
            toast("Saved $name — it will show under TRACK", 5000)
        } catch (e: Throwable) { toast("Save failed: ${e.message}", 6000) }
    }

    private fun shareRoute(r: Route) {
        try {
            val dir = java.io.File(cacheDir, "share").apply { mkdirs() }
            dir.listFiles()?.forEach { it.delete() }
            val f = java.io.File(dir, routeFileName()); f.writeText(routeGpx(r))
            val uri = androidx.core.content.FileProvider.getUriForFile(this, "$packageName.files", f)
            val send = android.content.Intent(android.content.Intent.ACTION_SEND).apply { type = "application/gpx+xml"; putExtra(android.content.Intent.EXTRA_STREAM, uri); addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            startActivity(android.content.Intent.createChooser(send, "Share route"))
        } catch (e: Throwable) { toast("Share failed: ${e.message}", 6000) }
    }

    /** The written directions, plus save/share, from the bottom card while navigating. */
    private fun showTurnList(r: Route) {
        val lines = r.maneuvers.filter { it.turn != Turn.DEPART }.joinToString("\n") { m ->
            val what = when (m.turn) { Turn.ARRIVE -> "Arrive"; Turn.ROUNDABOUT -> "Roundabout, exit ${m.exit}"; else -> m.turn.name.lowercase().replace('_', ' ').replaceFirstChar { it.uppercase() } }
            Units.distance(m.at).padEnd(9) + what + if (m.name.isNotEmpty() && m.turn != Turn.ARRIVE) " onto " + m.name else ""
        }
        val tv = TextView(this).apply { text = lines; textSize = 14f; typeface = android.graphics.Typeface.MONOSPACE; setPadding(dp(20), dp(8), dp(20), dp(8)); setTextIsSelectable(true) }
        val sv = android.widget.ScrollView(this).apply { addView(tv) }
        androidx.appcompat.app.AlertDialog.Builder(this).setTitle((destName.ifEmpty { "Route" }) + " · " + Units.distance(r.length) + " · " + Units.time(r.timeSeconds)).setView(sv)
            .setPositiveButton("SAVE / SHARE") { _, _ -> saveOrShareRoute(r) }
            .setNegativeButton("CLOSE", null).show()
    }

    private fun pickModes() {
        val all = Profile.values()
        val checked = BooleanArray(all.size) { prefs.modes.contains(all[it]) }
        androidx.appcompat.app.AlertDialog.Builder(this).setTitle("Modes to compare")
            .setMultiChoiceItems(all.map { it.label + " — " + it.blurb }.toTypedArray(), checked) { _, i, v -> checked[i] = v }
            .setPositiveButton("OK") { _, _ ->
                val sel = all.filterIndexed { i, _ -> checked[i] }.toSet().ifEmpty { setOf(Profile.FAST) }
                prefs.modes = sel; compareModes = null
                val from = compareFrom; val dest = destination
                if (from != null && dest != null) startCompare(from, dest)
            }
            .setNegativeButton(R.string.cancel, null).show()
    }

    private fun startNavigation(r: Route, track: Boolean, resumed: NavController? = null) {
        route = r; isTrack = track
        // the ride itself — route, follower, what has been said — belongs to the service, so it outlives this screen
        val c = resumed ?: NavController(r, track, if (track) trackName else destName, profile.name, NavService.sink).also { c -> lastFix?.let { c.update(it) } }
        c.batterySaver = prefs.batterySaver; c.worstSpeed = if (profile.bike) 10.0 else 30.0
        NavService.start(this, c)
        if (prefs.batterySaver) { applyBatteryFlags(true); batteryHint() }
        drawRoute(r)
        drawVias()
        clearPinMarker()
        if (!track) destination?.let { showPinMarker(it) }
        follow = true
        offSince = 0L
        zoomMode = ZoomMode.TURN; manualZoom = -1.0; overviewDone = false
        switchMode(Mode.NAV)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (android.os.Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) askNavNotify.launch(Manifest.permission.POST_NOTIFICATIONS)
        lastFix?.let { moveCamera(it.lat, it.lon, animate = true) }
        if (!track) { trackLats = null; trackLons = null; trackUri = null; trackCues.clear(); drawCues() }
        if (track) {
            // show the whole track first so the rider knows where it starts, then settle into the turn view
            zoomMode = ZoomMode.OVERVIEW; overviewDone = false
            lastFix?.let { moveCamera(it.lat, it.lon, true) }
            ui.postDelayed({ if (mode == Mode.NAV && isTrack && zoomMode == ZoomMode.OVERVIEW) { zoomMode = ZoomMode.TURN; lastFix?.let { moveCamera(it.lat, it.lon, true) } } }, 5000)
        }
        ui.removeCallbacks(hudTick); ui.post(hudTick)
    }

    /** the notification is a courtesy; navigation runs with or without permission to show it */
    private val askNavNotify = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    /** A ride a dead process left behind: offer to pick it up where it was. */
    private fun offerResume() {
        val text = NavService.leftover(this) ?: return
        val c = NavController.restore(text, NavService.sink) ?: run { NavService.checkpointFile(this).delete(); return }
        androidx.appcompat.app.AlertDialog.Builder(this).setTitle("Resume navigation?")
            .setMessage("${c.destName.ifEmpty { "A ride" }} was being followed when the app stopped. Pick it up where it was?")
            .setPositiveButton("RESUME") { _, _ ->
                val r = c.route; val n = r.size - 1
                destName = c.destName; destination = if (c.isTrack) null else LatLng(r.lats[n], r.lons[n])
                if (c.isTrack) { trackLats = r.lats; trackLons = r.lons; trackName = c.destName; trackUri = null; trackCues.clear(); trackCues.addAll(r.maneuvers.filter { it.turn != Turn.DEPART && it.turn != Turn.ARRIVE }.map { m -> Cue(r.lats[m.vertex], r.lons[m.vertex], m.turn, m.note, m.level) }); drawCues() }
                startNavigation(r, c.isTrack, c)
            }
            .setNegativeButton("DISCARD") { _, _ -> NavService.checkpointFile(this).delete() }
            .show()
    }

    // ---- battery saver: the screen wakes for a cue and sleeps after; between cues the GPS eases and the CPU rests
    private val batteryRx = object : android.content.BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            val level = intent?.getIntExtra(android.os.BatteryManager.EXTRA_LEVEL, -1) ?: -1; val scale = intent?.getIntExtra(android.os.BatteryManager.EXTRA_SCALE, 100) ?: 100
            val charging = (intent?.getIntExtra(android.os.BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0
            if (level in 0..(scale * 15 / 100) && !charging && mode == Mode.NAV && !prefs.batterySaver) { setBatterySaver(true); toast("Battery at ${level * 100 / scale} % — battery saver on", 6000) }
        }
    }
    private var dark: View? = null
    private var darkText: TextView? = null
    private var brightLock: PowerManager.WakeLock? = null
    private var hinted = false
    private fun setBatterySaver(on: Boolean) {
        prefs.batterySaver = on
        NavService.controller?.let { it.batterySaver = on; it.worstSpeed = if (profile.bike) 10.0 else 30.0 }
        applyBatteryFlags(on)
        if (on) { if (mode == Mode.NAV) { dim(true); batteryHint() } else toast("Battery saver: while navigating, the screen wakes for each cue and sleeps after; the GPS eases between cues", 6000) }
        else { dim(false); if (mode == Mode.NAV) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }
    private fun batteryHint() {
        if (hinted) return; hinted = true
        toast("Battery saver on. No signal out here? Airplane mode with Bluetooth on roughly doubles the battery — the maps and the voice do not need one.", 9000)
    }
    /** the screen may wake over the lock screen for a cue, without unlocking */
    private fun applyBatteryFlags(on: Boolean) {
        if (android.os.Build.VERSION.SDK_INT >= 27) { setShowWhenLocked(on); setTurnScreenOn(on) }
    }
    /** The dark HUD: the map black, the next cue in grey at minimum brightness; keep-screen-on dropped so the phone's own timeout can sleep the screen. */
    private fun dim(on: Boolean) {
        if (on) {
            if (dark == null) {
                val root = findViewById<android.widget.FrameLayout>(R.id.root)
                val d = android.widget.FrameLayout(this).apply { setBackgroundColor(0xFF000000.toInt()); isClickable = true }
                val t = TextView(this).apply { setTextColor(0xFF9A9A9A.toInt()); textSize = 28f; gravity = android.view.Gravity.CENTER; setPadding(dp(24), dp(24), dp(24), dp(24)) }
                d.addView(t, android.widget.FrameLayout.LayoutParams(android.widget.FrameLayout.LayoutParams.MATCH_PARENT, android.widget.FrameLayout.LayoutParams.WRAP_CONTENT, android.view.Gravity.CENTER))
                d.setOnClickListener { dim(false); window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON); ui.postDelayed({ if (prefs.batterySaver && mode == Mode.NAV && NavService.controller?.view(android.os.SystemClock.elapsedRealtime())?.progress == 0.0) dim(true) }, 20_000) }
                root.addView(d, root.indexOfChild(mapView) + 1, android.widget.FrameLayout.LayoutParams(android.widget.FrameLayout.LayoutParams.MATCH_PARENT, android.widget.FrameLayout.LayoutParams.MATCH_PARENT))
                dark = d; darkText = t
            }
            dark?.visibility = View.VISIBLE; hud.visibility = View.GONE
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            window.attributes = window.attributes.apply { screenBrightness = 0.01f }
            try { brightLock?.let { if (it.isHeld) it.release() } } catch (_: Throwable) {}
        } else {
            dark?.visibility = View.GONE
            window.attributes = window.attributes.apply { screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE }
        }
    }
    /** A cue is near (or the line is lost): light the screen, over the lock screen if need be, for half a minute. */
    @Suppress("DEPRECATION")
    private fun onAttention(on: Boolean) {
        if (!prefs.batterySaver || mode != Mode.NAV) return
        if (on) {
            dim(false); window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            try {
                if (brightLock == null) brightLock = (getSystemService(POWER_SERVICE) as PowerManager).newWakeLock(PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP or PowerManager.ON_AFTER_RELEASE, "roadbook:cue")
                brightLock?.acquire(45_000L)
            } catch (_: Throwable) {}
        } else dim(true)
    }

    private fun endNavigation() {
        Speech.stop()
        dim(false); try { brightLock?.let { if (it.isHeld) it.release() } } catch (_: Throwable) {}
        NavService.stop(this)
        routeGeneration++
        route = null; destination = null; pin = null; isTrack = false; vias.clear(); viaNames.clear(); destName = ""; candidates.clear(); compareGeneration++; planStart = null; startName = ""; drawStart(); editCues.clear(); compareModes = null
        srcRoute?.setGeoJson(FeatureCollection.fromFeatures(emptyArray())); drawVias()
        clearPinMarker()
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        ui.removeCallbacks(hudTick)
        offPanel.visibility = View.GONE
        trackLats = null; trackLons = null; trackUri = null; trackCues.clear(); drawCues()
        switchMode(Mode.IDLE)
        lastFix?.let { updatePuck(it.lat, it.lon, heading); if (follow) moveCamera(it.lat, it.lon, true) }
    }

    /** Off route: nothing happens by itself. The route is fixed until you tap REROUTE or END. */
    private fun handleOffRoute(v: NavView, fix: Fix) {
        if (v.onRoute) { offSince = 0L; return }
        if (offSince == 0L) offSince = fix.timeMs
    }

    private fun rerouteNow() {
        val fix = lastFix ?: run { toast("Waiting for a GPS fix…"); return }
        val dest = destination ?: run { toast("Tracks can't be rerouted — ride back to the line or END"); return }
        if (packs.packFor(fix.lat, fix.lon) == null) { toast("You are outside your maps."); return }
        if (rerouting) return
        // vias we have already passed stay behind
        val r = route; val f = follower
        if (r != null && f != null && vias.isNotEmpty()) {
            val here = f.view(android.os.SystemClock.elapsedRealtime()).along
            for (k in vias.indices.reversed()) {
                val v = vias[k]; var best = Double.MAX_VALUE; var at = 0.0
                for (i in 0 until r.size) { val d = Geo.fastDistance(v.latitude, v.longitude, r.lats[i], r.lons[i]); if (d < best) { best = d; at = r.cum[i] } }
                if (at < here - 500.0) { vias.removeAt(k); if (k in viaNames.indices) viaNames.removeAt(k) }
            }
        }
        computeRoute(fix, dest, initial = true)
    }

    // ---------------------------------------------------------------- HUD

    private val hudTick = object : Runnable {
        override fun run() {
            val c = NavService.controller ?: return
            val now = android.os.SystemClock.elapsedRealtime()
            // the service ticks the session (sounds, checkpoints); this screen only reads the view — unless the
            // service could not start, when the screen keeps the ride going itself for as long as it is open
            try { renderHud(if (NavService.running) c.view(now) else { c.sound = Tone.enabled; c.speechReady = Speech.enabled && Speech.ready; lastFix?.let { f -> if (f !== c.lastFix) c.update(f) }; c.tick(now) }) } catch (_: Throwable) {}
            ui.postDelayed(this, if (uiVisible) 50 else 250)
        }
    }

    /** Dashed line from you to the next turn with a time-to-turn chip, for turns still far out (next-turn view only). */
    /** The glow from the rider to the next cue: the route's own points between them, refreshed a few times a second. */
    private fun updateLead(v: NavView) {
        val next = v.next; val r = follower?.route
        val show = r != null && next != null && v.onRoute && !v.arrived
        if (!show) { if (glowAt != 0L) { glowAt = 0L; srcLead?.setGeoJson(FeatureCollection.fromFeatures(emptyArray())) }; return }
        val now = android.os.SystemClock.elapsedRealtime(); if (now - glowAt < 250) return; glowAt = now
        val pts = ArrayList<Point>()
        pts.add(Point.fromLngLat(v.lon, v.lat))
        val from = (v.segment + 1).coerceAtLeast(0); val to = next!!.vertex
        if (to >= from) { val step = maxOf(1, (to - from) / 600); var i = from; while (i <= to) { pts.add(Point.fromLngLat(r!!.lons[i], r.lats[i])); i += step }; if ((to - from) % step != 0) pts.add(Point.fromLngLat(r!!.lons[to], r.lats[to])) }
        else pts.add(Point.fromLngLat(r!!.lons[to], r.lats[to]))
        srcLead?.setGeoJson(Feature.fromGeometry(LineString.fromLngLats(pts)))
        turnChip.visibility = View.GONE
    }

    private fun renderHud(v: NavView) {
        darkText?.let { t -> if (dark?.visibility == View.VISIBLE) { val m = v.next; t.text = when { v.arrived -> getString(R.string.arrived); !v.onRoute -> getString(R.string.off_track); m == null -> Units.distance(v.remaining); else -> Units.cue(v.distToNext) + "\n" + (if (m.turn == Turn.ARRIVE) "Destination" else m.phrase()) } } }
        if (mode != Mode.NAV) { if (v.fixState == FixState.VALID || v.fixState == FixState.DEGRADED) updatePuck(v.lat, v.lon, if (v.speed > 1.5) v.heading else heading); return }
        if (v.arrived) {
            hud.visibility = View.VISIBLE; offPanel.visibility = View.GONE
            arrow.turn = Turn.ARRIVE; hudDistance.text = getString(R.string.arrived); hudStreet.text = ""; arrow.progress = 1f
            navRemaining.text = Units.distance(0.0); navEta.text = ""
            return
        }
        if (v.fixState == FixState.NONE || v.fixState == FixState.STALE) {
            // no position to trust: say so instead of showing frozen guidance as current
            hud.visibility = View.GONE
            offPanel.visibility = View.VISIBLE
            offTrack.text = if (v.fixState == FixState.NONE) "WAITING FOR GPS" else "NO GPS · " + Units.time(v.fixAge)
            btnReroute.visibility = View.GONE
            navRemaining.text = Units.distance(v.remaining); navEta.text = Units.time(v.etaSeconds)
            navSpeed.text = "—"
            return
        }
        if (!v.onRoute) {
            hud.visibility = View.GONE
            offPanel.visibility = View.VISIBLE
            val rel = Geo.turnAngle(heading, v.offBearing)
            val glyph = when {
                rel > 150 || rel < -150 -> "↓"; rel > 110 -> "↘"; rel > 60 -> "→"; rel > 20 -> "↗"
                rel < -110 -> "↙"; rel < -60 -> "←"; rel < -20 -> "↖"; else -> "↑"
            }
            offTrack.text = "${getString(R.string.off_track)}  $glyph ${Units.cue(v.offDist)}"
            btnReroute.visibility = if (isTrack) View.GONE else View.VISIBLE
            btnReroute.text = if (rerouting) "ROUTING…" else "REROUTE"
        } else {
            offPanel.visibility = View.GONE
            hud.visibility = View.VISIBLE
            val m = v.next
            if (m != null) {
                // a danger at the same place as a turn takes the arrow; the words carry the whole group
                val lead = v.hazard ?: m
                arrow.turn = lead.turn; arrow.exit = if (lead.turn == Turn.DANGER) lead.level else lead.exit
                hudDistance.text = Units.cue(v.distToNext) + if (v.fixState == FixState.DEGRADED) " ?" else ""
                hudStreet.text = when {
                    m.turn == Turn.ARRIVE -> "Destination"
                    v.group.size > 1 -> v.group.joinToString(" · ") { g -> g.note.ifEmpty { if (g.turn == Turn.DANGER || g.turn == Turn.NOTE) g.turn.spoken else g.name.ifEmpty { g.turn.spoken } } }
                    m.note.isNotEmpty() -> m.note
                    else -> m.name.ifEmpty { if (isTrack) "" else "unnamed road" }
                }
                arrow.progress = v.progress.toFloat()
            }
        }
        navRemaining.text = Units.distance(v.remaining); navEta.text = Units.clockFromNow(this, v.etaSeconds) + " · " + Units.time(v.etaSeconds)
        navSpeed.text = Units.speed(v.speed); navElev.text = if (lastAltitude.isNaN()) "" else Units.elevation(lastAltitude)
        map?.let { hudCompass.bearing = it.cameraPosition.bearing.toFloat() }
        updateLead(v)
        // smooth puck between fixes
        if (v.fixState == FixState.VALID || v.fixState == FixState.DEGRADED) updatePuck(v.lat, v.lon, if (v.speed > 1.5) v.heading else heading)
    }

    // ---------------------------------------------------------------- packs / gpx

    private fun importPacks(uris: List<Uri>) {
        val dlg = ProgressDialogLite(this, if (uris.size == 1) "Importing map pack" else "Importing ${uris.size} map packs")
        dlg.show()
        worker.execute {
            var ok = 0; val errors = ArrayList<String>(); var last: Pack? = null
            for ((i, uri) in uris.withIndex()) {
                val prefix = if (uris.size > 1) "${i + 1}/${uris.size}  " else ""
                try { last = packs.import(uri) { msg -> ui.post { dlg.message(prefix + msg) } }; ok++ } catch (e: Throwable) { errors.add(e.message ?: "failed") }
            }
            ui.post {
                dlg.dismiss()
                if (ok > 0) {
                    toast(if (uris.size == 1) "Imported ${last?.name}" else "Imported $ok packs" + if (errors.isEmpty()) "" else ", ${errors.size} failed")
                    loadStyle()
                    if (lastFix == null) last?.let { val m = it.manifest; map?.moveCamera(CameraUpdateFactory.newLatLngBounds(LatLngBounds.from(m.maxLat, m.maxLon, m.minLat, m.minLon), dp(40))) }
                }
                if (errors.isNotEmpty() && ok == 0) toast("Import failed: ${errors.first()}")
                else if (errors.isNotEmpty()) toast(errors.first())
            }
        }
    }

    private fun deletePack(p: Pack) {
        worker.execute { packs.delete(p); ui.post { toast("Removed ${p.name}"); loadStyle() } }
    }

    private fun loadGpx(uri: Uri) {
        worker.execute {
            val result = try {
                val cap = 64 shl 20
                val text = contentResolver.openInputStream(uri)?.use { ins ->
                    // stop reading at the cap instead of loading a huge file first
                    val out = java.io.ByteArrayOutputStream(); val buf = ByteArray(1 shl 16); var total = 0
                    while (true) { val n = ins.read(buf); if (n < 0) break; total += n; if (total > cap) throw IllegalStateException("GPX is larger than 64 MB"); out.write(buf, 0, n) }
                    String(out.toByteArray(), Charsets.UTF_8)
                } ?: throw IllegalStateException("Could not read file")
                val tr = Gpx.parse(text) ?: run {
                    // no line at all: a file of waypoints (someone's saved places, a marks backup) can join the marks
                    val n = MarkStore.parse(text).size
                    if (n > 0) { ui.post { offerMarks(text, n) }; return@execute }
                    throw IllegalStateException("No track or route points found")
                }
                if (tr.lats.size > 2_000_000) throw IllegalStateException("Track has too many points (${tr.lats.size})")
                if (tr.cues.size > 20_000) throw IllegalStateException("Track has too many cues (${tr.cues.size})")
                tr
            } catch (e: Throwable) { e }
            ui.post {
                when (result) {
                    is Gpx.Track -> onGpxLoaded(result, uri)
                    is Throwable -> toast("Track failed: ${result.message}")
                }
            }
        }
    }

    /** A GPX of waypoints and nothing else: add them to the marks. */
    private fun offerMarks(text: String, n: Int) {
        androidx.appcompat.app.AlertDialog.Builder(this).setTitle("$n saved point${if (n == 1) "" else "s"}")
            .setMessage("This file has places but no track. Add them to your marks? Points you already have are skipped.")
            .setPositiveButton("ADD TO MARKS") { _, _ -> safe { val added = marks.merge(text); drawMarks(); toast(if (added == 0) "Nothing new — all $n already in your marks" else "Added $added mark${if (added == 1) "" else "s"}") } }
            .setNegativeButton(R.string.cancel, null).show()
    }

    /** A GPX with route points is re-routed on this phone's maps; a bare track is followed as a line. */
    private fun onGpxLoaded(tr: Gpx.Track, uri: Uri? = null) {
        if (tr.hasTrack) {
            // Roadbook: a track is something you author as well as follow
            val n = tr.cues.size
            val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(24), dp(8), dp(24), 0) }
            body.addView(TextView(this).apply { textSize = 16f; text = Units.distance(trackLength(tr)) + (if (n > 0) " · $n cues" else " · no cues yet") + (if (tr.hasRoute) "\n\nThis file also carries its start, vias and end: EDIT ROUTE plans it again on your maps, where you can add or remove vias (tap a via to remove it), then GO or SAVE." else "") })
            var dlgRef: androidx.appcompat.app.AlertDialog? = null
            body.addView(Button(this).apply { text = "PUBLISH TO THE LIBRARY"; textSize = 13f; setOnClickListener { dlgRef?.dismiss(); Library.publish(this@MainActivity, tr.name, Gpx.writeTrack(tr.name, tr.lats, tr.lons, null, tr.cues), recorded = true) } })
            dlgRef = androidx.appcompat.app.AlertDialog.Builder(this).setTitle(tr.name).setView(body)
                .setPositiveButton("FOLLOW") { _, _ -> followTrack(tr, uri) }
                .setNeutralButton("EDIT CUES") { _, _ -> openEditor(tr, uri) }
                .apply { if (tr.hasRoute) setNegativeButton("EDIT ROUTE") { _, _ -> rerouteGpx(tr) } else setNegativeButton(R.string.cancel, null) }
                .show()
            return
        }
        if (tr.hasRoute) rerouteGpx(tr) else followTrack(tr, uri)
    }

    private fun trackLength(tr: Gpx.Track): Double { var d = 0.0; for (i in 1 until tr.lats.size) d += Geo.fastDistance(tr.lats[i - 1], tr.lons[i - 1], tr.lats[i], tr.lons[i]); return d }

    private fun followTrack(tr: Gpx.Track, uri: Uri?) {
        trackLats = tr.lats; trackLons = tr.lons; trackName = tr.name; trackUri = uri; trackCues.clear(); trackCues.addAll(tr.cues)
        val r = Route.fromTrack(tr.lats, tr.lons, tr.name, tr.cues); destination = null; startNavigation(r, true); drawCues()
        toast("Following track (${Units.distance(r.length)})" + if (tr.cues.isNotEmpty()) " · ${tr.cues.size} cues" else "")
    }

    /**
     * EDIT ROUTE: a file planned again on this phone's maps so its vias can be changed. A saved route brings its
     * own start, vias and end; a bare track (a library roadbook, anyone's GPX) gets vias placed where its line
     * bends, so the plan follows the same roads. The file's authored cues are laid back onto the plan. The compare
     * is limited to the file's own mode, so the line comes back the same; MODES opens it up again.
     */
    private fun rerouteGpx(tr: Gpx.Track) {
        val pts: List<DoubleArray> = if (tr.hasRoute) (0 until tr.rteLats.size).map { doubleArrayOf(tr.rteLats[it], tr.rteLons[it]) } else Route.viasForTrack(tr.lats, tr.lons)
        if (pts.size < 2) { followTrack(tr, null); return }
        val start = LatLng(pts.first()[0], pts.first()[1]); val dest = LatLng(pts.last()[0], pts.last()[1])
        if (packs.packFor(start.latitude, start.longitude) == null || packs.packFor(dest.latitude, dest.longitude) == null) {
            if (tr.hasTrack) { toast("Route is outside your maps — following its line instead", 5000); followTrack(tr, null) }
            else toast("Route is outside your maps", 6000)
            return
        }
        tr.mode?.let { m -> try { val p = Profile.valueOf(m); profile = p; prefs.profile = p } catch (_: Throwable) {} }
        compareModes = listOf(profile)
        destination = dest; destName = if (tr.hasRoute) tr.rteName(pts.size - 1).ifEmpty { tr.name } else tr.name
        vias.clear(); viaNames.clear(); for (i in 1 until pts.size - 1) { vias.add(LatLng(pts[i][0], pts[i][1])); viaNames.add(if (tr.hasRoute) tr.rteName(i).let { if (it.startsWith("Via ") || it.startsWith("Stop ")) "" else it } else "") }
        startName = if (tr.hasRoute) tr.rteName(0).let { if (it == "Start") "" else it } else ""
        drawVias()
        editCues.clear(); editCues.addAll(tr.cues)
        if (mode == Mode.NAV) endNavigationKeepingPlan()
        // start from the GPS fix if you are near the route's start, otherwise plan from the route's own start
        val fix = lastFix
        val nearStart = fix != null && Geo.fastDistance(fix.lat, fix.lon, start.latitude, start.longitude) < 2000.0
        if (!nearStart) { planStart = start; drawStart() } else planStart = null
        showPinMarker(dest)
        toast("Planning \"${tr.name}\" on your maps (${profile.label})… add a via with a long press, tap a via to remove it", 6000)
        startCompare(startFix(), dest)
    }

    /** Leaves a ride without clearing the plan being set up (EDIT ROUTE from a track that was being followed). */
    private fun endNavigationKeepingPlan() {
        Speech.stop(); dim(false); NavService.stop(this); routeGeneration++
        route = null; isTrack = false; srcRoute?.setGeoJson(FeatureCollection.fromFeatures(emptyArray()))
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON); ui.removeCallbacks(hudTick); offPanel.visibility = View.GONE
        trackLats = null; trackLons = null; trackUri = null; trackCues.clear()
    }

    // ---------------------------------------------------------------- misc

    /** Once, on first launch: what Roadbook is and is not. The full terms are on the site. */
    private fun showTerms(then: () -> Unit) {
        androidx.appcompat.app.AlertDialog.Builder(this).setTitle("Before you ride")
            .setMessage(TERMS_SHORT)
            .setPositiveButton("I UNDERSTAND") { _, _ -> prefs.accepted = true; then() }
            .setNeutralButton("READ THE TERMS") { _, _ -> openUrl("https://hondoroadbook.com/terms.html"); ui.postDelayed({ showTerms(then) }, 500) }
            .setCancelable(false).show()
    }
    fun openUrl(url: String) { try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) } catch (_: Throwable) { toast(url) } }

    companion object {
        const val TERMS_SHORT = "Roadbook is a riding aid, not a substitute for your eyes, your judgment or the law.\n\nThe map is made by volunteers and can be wrong: roads close, gates lock, a track that is public on the map may be private on the ground, and a legal road may be unsafe for you today. A route is a suggestion; a cue is a reminder of what the data says. If a cue and the road disagree, the road wins.\n\nYou are responsible for riding safely and legally, for watching the road rather than the screen, and for carrying what you need where there is no help. Roadbook is free and provided as is, with no warranty, and Hondo Garage is not liable for what happens on the road.\n\nFull terms: hondoroadbook.com/terms.html"
    }

    /** The interface follows the map: dark for the night and sun maps, light for the day map (a change recreates the activity). */
    private fun applyUiTheme() {
        val dark = prefs.night || prefs.sun
        val want = if (dark) AppCompatDelegate.MODE_NIGHT_YES else AppCompatDelegate.MODE_NIGHT_NO
        if (AppCompatDelegate.getDefaultNightMode() != want) AppCompatDelegate.setDefaultNightMode(want) else loadStyle()
    }

    /** SUN MAP: the noon-sun handlebar style, on or off (off goes back to whichever of day and night was chosen). */
    private fun toggleSun() {
        prefs.sun = !prefs.sun
        toast(if (prefs.sun) "Sun map: black ground, bright roads, big labels — least power, reads in glare" else "Sun map off", 3000)
        applyUiTheme()
    }

    private fun switchMode(m: Mode) {
        mode = m
        panelIdle.visibility = View.GONE          // the old small buttons stay hidden; the glove menu drives them
        panelPin.visibility = if (m == Mode.PIN) View.VISIBLE else View.GONE
        findViewById<View>(R.id.btnVia).visibility = if (m == Mode.PIN && destination != null && !isTrack) View.VISIBLE else View.GONE
        panelNav.visibility = if (m == Mode.NAV) View.VISIBLE else View.GONE
        findViewById<View>(R.id.scale).visibility = if (m == Mode.NAV) View.GONE else View.VISIBLE
        panelCompare.visibility = if (m == Mode.COMPARE) View.VISIBLE else View.GONE
        panelEdit.visibility = if (m == Mode.EDIT) View.VISIBLE else View.GONE
        findViewById<View>(R.id.btnMenu).visibility = if (m == Mode.IDLE || m == Mode.NAV) View.VISIBLE else View.GONE
        closeMenu(animate = false)
        updateRecStrip()
        if (m != Mode.NAV) { hud.visibility = View.GONE; offPanel.visibility = View.GONE; turnChip.visibility = View.GONE; srcLead?.setGeoJson(FeatureCollection.fromFeatures(emptyArray())) }
        map?.uiSettings?.isCompassEnabled = m != Mode.NAV
        if (m != Mode.COMPARE) { srcCompare?.setGeoJson(FeatureCollection.fromFeatures(emptyArray())); srcTurns?.setGeoJson(FeatureCollection.fromFeatures(emptyArray())) }
    }

    private fun updateSoundButton() { findViewById<ImageButton>(R.id.btnSound).setImageResource(if (prefs.sound) R.drawable.ic_sound_on else R.drawable.ic_sound_off) }

    // ---- audible cue state: pips track the bar, one chime at the turn
    // (the cue-delivery rules — pips, chimes, speech, missed hazards — live in fold.core.NavController, driven by NavService)

    // ---------------------------------------------------------------- Roadbook: cues, pre-run recording, editor, orientation

    private fun snapToTrack(lat: Double, lon: Double): DoubleArray? {
        val la = trackLats ?: return null; val lo = trackLons ?: return null
        val out = DoubleArray(4); var best = Double.MAX_VALUE; var bLat = lat; var bLon = lon
        for (i in 0 until la.size - 1) {
            if (Math.abs(la[i] - lat) > 0.02 || Math.abs(lo[i] - lon) > 0.02) continue
            Geo.projectOnSegment(lat, lon, la[i], lo[i], la[i + 1], lo[i + 1], out)
            if (out[1] < best) { best = out[1]; bLat = out[2]; bLon = out[3] }
        }
        return if (best <= 150.0) doubleArrayOf(bLat, bLon, best) else null
    }

    /** New cue at a point: on the loaded track (snapped) and/or in the recording. */
    private fun addCueAt(lat: Double, lon: Double, snapToTrack: Boolean) {
        var cLat = lat; var cLon = lon
        val onTrack = trackLats != null
        if (onTrack && snapToTrack) { val s = snapToTrack(lat, lon) ?: run { toast("That is more than 150 m from the track"); return }; cLat = s[0]; cLon = s[1] }
        CueDialog.show(this, cLat, cLon, null, { c ->
            if (onTrack) { trackCues.add(c); trackCues.sortBy { trackOffset(it) }; drawCues(); if (mode == Mode.NAV) rebuildTrackRoute() }
            if (Recorder.running) Recorder.addCue(c)
            haptic(1); toast(c.turn.spoken + if (c.note.isNotEmpty()) ": " + c.note else "")
        })
    }

    /** CUE button: where you are now. While following a track the cue snaps to it; while recording it goes on the recording. */
    private fun addCueHere() {
        val fix = lastFix
        val lat = if (fix != null) fix.lat else if (!Recorder.lastLat.isNaN()) Recorder.lastLat else { toast("Waiting for GPS…"); return }
        val lon = if (fix != null) fix.lon else Recorder.lastLon
        addCueAt(lat, lon, snapToTrack = trackLats != null)
    }

    private fun editCue(c: Cue) {
        CueDialog.show(this, c.lat, c.lon, c, { n ->
            val i = trackCues.indexOf(c); if (i >= 0) trackCues[i] = n; drawCues(); if (mode == Mode.NAV) rebuildTrackRoute()
        }, onDelete = { trackCues.remove(c); drawCues(); if (mode == Mode.NAV) rebuildTrackRoute(); toast("Cue deleted") })
    }

    private fun trackOffset(c: Cue): Double {
        val la = trackLats ?: return 0.0; val lo = trackLons ?: return 0.0
        var best = Double.MAX_VALUE; var bi = 0
        for (i in la.indices) { val d = Geo.fastDistance(la[i], lo[i], c.lat, c.lon); if (d < best) { best = d; bi = i } }
        return bi.toDouble()
    }

    /** Cues changed while following: rebuild the route in place, keeping position. */
    private fun rebuildTrackRoute() {
        val la = trackLats ?: return; val lo = trackLons ?: return
        val r = Route.fromTrack(la, lo, trackName, trackCues)
        route = r
        val old = NavService.controller
        val c = NavController(r, true, trackName, profile.name, NavService.sink)
        old?.lastFix?.let { f -> c.update(f); old.follower.view(android.os.SystemClock.elapsedRealtime()).let { v -> if (v.onRoute) c.follower.resumeAt(v.along) } }
        NavService.start(this, c)
    }

    private fun openEditor(tr: Gpx.Track, uri: Uri?) {
        if (mode == Mode.NAV) endNavigation()
        trackLats = tr.lats; trackLons = tr.lons; trackName = tr.name; trackUri = uri; trackCues.clear(); trackCues.addAll(tr.cues)
        val r = Route.fromTrack(tr.lats, tr.lons, tr.name, tr.cues)
        route = r; isTrack = true; drawRoute(r); drawCues()
        follow = false
        switchMode(Mode.EDIT)
        findViewById<TextView>(R.id.editLabel).text = "${tr.name} · ${Units.distance(r.length)} · ${trackCues.size} cues\nLong-press on the line to add a cue. Tap a cue to edit it."
        map?.let { m ->
            val b = org.maplibre.android.geometry.LatLngBounds.Builder(); for (i in tr.lats.indices step maxOf(1, tr.lats.size / 500)) b.include(LatLng(tr.lats[i], tr.lons[i])); b.include(LatLng(tr.lats.last(), tr.lons.last()))
            try { m.animateCamera(org.maplibre.android.camera.CameraUpdateFactory.newLatLngBounds(b.build(), dp(40), dp(120), dp(40), dp(220))) } catch (_: Throwable) {}
        }
    }

    private fun leaveEdit() {
        route = null; isTrack = false
        srcRoute?.setGeoJson(FeatureCollection.fromFeatures(emptyArray()))
        trackLats = null; trackLons = null; trackUri = null; trackCues.clear(); drawCues()
        switchMode(Mode.IDLE)
    }

    private fun followEditedTrack() {
        val la = trackLats ?: return; val lo = trackLons ?: return
        val tr = Gpx.Track(trackName, la, lo, DoubleArray(0), DoubleArray(0), null, ArrayList(trackCues))
        val uri = trackUri
        switchMode(Mode.IDLE)
        followTrack(tr, uri)
    }

    private fun showCueList() {
        val la = trackLats ?: return; val lo = trackLons ?: return
        val r = Route.fromTrack(la, lo, trackName, trackCues)
        val lines = r.maneuvers.filter { it.turn != Turn.DEPART }.joinToString("\n") { m -> Units.distance(m.at).padEnd(9) + m.phrase() }
        val tv = TextView(this).apply { text = lines.ifEmpty { "No cues yet." }; textSize = 14f; typeface = android.graphics.Typeface.MONOSPACE; setPadding(dp(20), dp(8), dp(20), dp(8)); setTextIsSelectable(true) }
        androidx.appcompat.app.AlertDialog.Builder(this).setTitle("$trackName · ${Units.distance(r.length)}").setView(android.widget.ScrollView(this).apply { addView(tv) }).setNegativeButton("CLOSE", null).show()
    }

    private fun trackGpx(): String { val la = trackLats!!; val lo = trackLons!!; return Gpx.writeTrack(trackName, la, lo, null, trackCues.sortedBy { trackOffset(it) }) }

    /** Writes the track with its cues back over the file it came from, or as a new file in your folder. */
    private fun saveTrack() {
        if (trackLats == null) return
        val uri = trackUri
        val b = androidx.appcompat.app.AlertDialog.Builder(this).setTitle("Save \"$trackName\"").setMessage("${trackCues.size} cues. The GPX keeps the line and adds the cues as waypoints, so any app still opens it.")
        if (uri != null) b.setPositiveButton("SAVE OVER FILE") { _, _ -> overwriteTrack(uri) }
        b.setNeutralButton(if (uri != null) "SAVE AS…" else "SAVE TO FOLDER") { _, _ -> askNameAndWrite(trackName, { trackGpx() }) }
        b.setNegativeButton(R.string.cancel, null).show()
    }

    /** Asks for a file name and writes into the granted folder; [onDone] gets true only after the bytes are on disk. */
    /** Overwrites the opened GPX. The old bytes are kept in the app's cache until the new ones are verified. */
    /**
     * Verifies a document by reading it back and comparing the bytes — not the provider's stat size, which is
     * −1 for a pipe-backed provider and says nothing about content. Returns null when it matches, else a reason.
     */
    private fun verifyWritten(uri: Uri, expected: ByteArray): String? {
        return try {
            val got = contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return "could not read it back"
            if (got.contentEquals(expected)) null else "read back ${got.size} of ${expected.size} bytes"
        } catch (e: Throwable) { "could not read it back (${e.javaClass.simpleName})" }
    }

    /**
     * Overwrites a roadbook in place. The original is kept in a backup named for this document before the
     * file is touched, and that backup is never replaced by anything that has not been verified: a failed or
     * torn write — including one that throws midway — restores the original, and a retry restores from the
     * same intact backup rather than copying the damaged file over it.
     */
    private fun overwriteTrack(uri: Uri) {
        val key = java.security.MessageDigest.getInstance("SHA-256").digest(uri.toString().toByteArray()).joinToString("") { "%02x".format(it) }.take(16)
        val backup = java.io.File(java.io.File(filesDir, "backup").apply { mkdirs() }, "$key.gpx")
        try {
            val bytes = trackGpx().toByteArray()
            val current = contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: throw IllegalStateException("Could not read the file")
            // a backup already there is a verified original from an earlier attempt; the current file is backed up only if it parses
            val currentValid = try { (fold.core.Gpx.parse(String(current))?.lats?.size ?: 0) >= 2 } catch (_: Throwable) { false }
            if (currentValid || !backup.exists()) { val tmp = java.io.File(backup.parentFile, "$key.tmp"); tmp.writeBytes(current); if (!tmp.renameTo(backup)) backup.writeBytes(current) }
            val old = backup.readBytes()
            var wrote = false
            try {
                contentResolver.openOutputStream(uri, "wt")?.use { it.write(bytes); it.flush() } ?: throw IllegalStateException("Could not open the file for writing")
                wrote = true
                verifyWritten(uri, bytes)?.let { throw IllegalStateException("File is incomplete ($it)") }
            } catch (e: Throwable) {
                // anything after the file was opened for writing may have torn it: put the original back, then say what happened
                var restored = false
                if (wrote || e !is IllegalStateException) { try { contentResolver.openOutputStream(uri, "wt")?.use { it.write(old); it.flush() }; restored = verifyWritten(uri, old) == null } catch (_: Throwable) {} }
                throw if (restored) IllegalStateException("${e.message}; the previous version was restored") else BackupKept("${e.message}; the previous version could not be put back")
            }
            backup.delete()
            toast("Saved")
        } catch (e: BackupKept) {
            // the file may be torn and the original is safe here: offer it as a new file right now
            androidx.appcompat.app.AlertDialog.Builder(this).setTitle("Save failed").setMessage("${e.message}. The original is kept safe; save it as a new file?")
                .setPositiveButton("SAVE ORIGINAL AS…") { _, _ -> askNameAndWrite((trackName.ifEmpty { "Roadbook" }) + " (original)", { String(backup.readBytes()) }) }
                .setNegativeButton("LATER", null).show()
        } catch (e: Throwable) { toast("Save failed: ${e.message}", 9000) }
    }
    private class BackupKept(msg: String) : RuntimeException(msg)

    private fun askNameAndWrite(suggest: String, body: () -> String, onDone: (Boolean) -> Unit = {}) {
        val input = android.widget.EditText(this).apply { setText(suggest); isSingleLine = true; textSize = 18f; setSelectAllOnFocus(true); setPadding(dp(20), dp(12), dp(20), dp(12)) }
        androidx.appcompat.app.AlertDialog.Builder(this).setTitle("File name").setView(input)
            .setPositiveButton("SAVE") { _, _ ->
                val name = input.text.toString().trim().ifEmpty { suggest }
                val go = { onDone(writeToFolder(name.removeSuffix(".gpx") + ".gpx", body())) }
                if (FilePicker.writableFolder(this) == null) chooseFolder(go) else go()
            }
            .setNegativeButton(R.string.cancel) { _, _ -> onDone(false) }
            .setOnCancelListener { onDone(false) }
            .show()
    }

    private var lastWritten: Uri? = null
    /** Writes a new file into the granted folder. Returns true only when the whole file was written. */
    private fun writeToFolder(fileName: String, text: String): Boolean {
        val tree = FilePicker.writableFolder(this) ?: run { toast(if (FilePicker.folder(this) != null) "The folder grant is read-only — pick the folder again" else "No folder chosen"); return false }
        var doc: Uri? = null
        try {
            val parent = android.provider.DocumentsContract.buildChildDocumentsUriUsingTree(tree, android.provider.DocumentsContract.getTreeDocumentId(tree))
            doc = android.provider.DocumentsContract.createDocument(contentResolver, parent, "application/gpx+xml", fileName) ?: throw IllegalStateException("Could not create file")
            val bytes = text.toByteArray()
            contentResolver.openOutputStream(doc, "wt")?.use { it.write(bytes); it.flush() } ?: throw IllegalStateException("Could not write file")
            // read it back and compare: a full disk or a flaky provider must not pass as saved, and a provider that
            // cannot report a size (a pipe) must not fail a save that is in fact whole
            verifyWritten(doc, bytes)?.let { throw IllegalStateException("File is incomplete ($it)") }
            lastWritten = doc
            toast("Saved $fileName — it is under TRACK", 5000)
            return true
        } catch (e: Throwable) {
            try { doc?.let { android.provider.DocumentsContract.deleteDocument(contentResolver, it) } } catch (_: Throwable) {}
            toast("Save failed: ${e.message}", 6000); return false
        }
    }

    // ---- pre-run recording
    private val askNotify = registerForActivityResult(ActivityResultContracts.RequestPermission()) { startRecordingNow() }

    private fun startRecording() {
        if (Recorder.running) return
        // an earlier recording that was never saved is offered again, not thrown away
        if (Recorder.hasData) { offerSaveRecording("Unsaved recording"); return }
        if (lastFix == null) toast("Recording starts with the first GPS fix")
        if (android.os.Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) askNotify.launch(Manifest.permission.POST_NOTIFICATIONS) else startRecordingNow()
    }
    private fun startRecordingNow() {
        try { Recorder.start(this); haptic(2); toast("Recording. Stop at a turn or a hazard and tap CUE.", 5000) } catch (e: Throwable) { toast(e.message ?: "Could not start recording", 6000) }
        updateRecStrip()
    }

    private fun stopRecording() {
        Recorder.stop(this)
        updateRecStrip()
        if (!Recorder.hasData) { Recorder.discard(this); toast("Nothing recorded"); return }
        offerSaveRecording("Recording stopped")
    }

    private fun offerRecovery() { offerSaveRecording("An unfinished recording was found") }

    private fun offerSaveRecording(title: String) {
        androidx.appcompat.app.AlertDialog.Builder(this).setTitle(title)
            .setMessage(Units.distance(Recorder.distance) + " · " + Recorder.cues.size + " cues")
            .setPositiveButton("SAVE") { _, _ ->
                // the recovery file lives until the save is on disk; cancel or a failed write keeps it
                askNameAndWrite(Recorder.defaultName(), { Recorder.gpx(Recorder.defaultName()) }) { ok -> if (ok) { Recorder.discard(this); updateRecStrip() } else toast("Recording kept — tap ● to save it later", 5000) }
            }
            .setNeutralButton("SHARE…") { _, _ -> shareText(Recorder.defaultName().replace(Regex("[^A-Za-z0-9 .-]"), "") + ".gpx", Recorder.gpx(Recorder.defaultName())); toast("Recording kept until you SAVE or DISCARD it", 5000) }
            .setNegativeButton("DISCARD") { _, _ -> androidx.appcompat.app.AlertDialog.Builder(this).setMessage("Discard this recording? There is no undo.").setPositiveButton("Discard") { _, _ -> Recorder.discard(this); updateRecStrip() }.setNegativeButton(R.string.cancel, null).show() }
            .setCancelable(false).show()
    }

    private fun shareText(fileName: String, text: String) {
        try {
            val dir = java.io.File(cacheDir, "share").apply { mkdirs() }
            val f = java.io.File(dir, fileName); f.writeText(text)
            val uri = androidx.core.content.FileProvider.getUriForFile(this, "$packageName.files", f)
            val send = android.content.Intent(android.content.Intent.ACTION_SEND).setType("application/gpx+xml").putExtra(android.content.Intent.EXTRA_STREAM, uri).addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
            startActivity(android.content.Intent.createChooser(send, "Share GPX"))
        } catch (e: Throwable) { toast("Share failed: ${e.message}", 6000) }
    }

    private val recTick = object : Runnable { override fun run() { if (Recorder.running) { updateRecStrip(); ui.postDelayed(this, 1000) } } }
    private fun updateRecStrip() {
                val on = Recorder.running
        recStrip.visibility = if (on && mode != Mode.COMPARE && mode != Mode.PIN) View.VISIBLE else View.GONE
        // keep the night/locate/lock stack clear of the strip
        findViewById<ImageButton>(R.id.btnRec).imageTintList = android.content.res.ColorStateList.valueOf(ContextCompat.getColor(this, if (on) R.color.accent else R.color.ink))
        if (on) {
            val s = Recorder.elapsedMs / 1000
            recStatus.text = "● " + (s / 3600) + ":" + "%02d".format(s / 60 % 60) + " · " + Units.distance(Recorder.distance) + " · " + Recorder.cues.size + " cues"
            ui.removeCallbacks(recTick); ui.postDelayed(recTick, 1000)
        }
    }

    // ---- orientation lock
    private fun toggleOrientationLock() {
        if (prefs.orientation >= 0) { prefs.orientation = -1; requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED; toast("Rotation follows the phone") }
        else {
            val rot = (getSystemService(WINDOW_SERVICE) as WindowManager).defaultDisplay.rotation
            val o = when (rot) {
                android.view.Surface.ROTATION_90 -> android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
                android.view.Surface.ROTATION_270 -> android.content.pm.ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE
                android.view.Surface.ROTATION_180 -> android.content.pm.ActivityInfo.SCREEN_ORIENTATION_REVERSE_PORTRAIT
                else -> android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            }
            prefs.orientation = o; requestedOrientation = o; toast("Screen locked this way")
        }
        haptic(1); updateLockButtons()
    }
    private fun updateLockButtons() {
        val locked = prefs.orientation >= 0
        for (id in intArrayOf(R.id.btnLock, R.id.btnLockNav)) findViewById<ImageButton>(id).apply {
            setImageResource(if (locked) R.drawable.ic_lock else R.drawable.ic_unlock)
            imageTintList = android.content.res.ColorStateList.valueOf(ContextCompat.getColor(this@MainActivity, if (locked) R.color.accent else R.color.ink))
        }
    }

    // ---------------------------------------------------------------- the library (Roadbook)

    /** A roadbook fetched by code: saved into the folder when there is one, then offered like any track. */
    private fun onFetchedGpx(name: String, text: String) {
        val tr = try { Gpx.parse(text) } catch (_: Throwable) { null }
        if (tr == null || tr.lats.size < 2) { toast("The fetched file has no track"); return }
        val file = name.replace(Regex("[^\\w .-]+"), "_").trim().ifEmpty { "Roadbook" } + ".gpx"
        val open = { val ok = writeToFolder(file, text); onGpxLoaded(tr, if (ok) lastWritten else null) }
        if (FilePicker.writableFolder(this) == null) chooseFolder(open) else open()
    }

    private var srcLib: GeoJsonSource? = null
    private var libraryOn = false
    private var libraryItems: List<LibraryItem> = emptyList()
    private var libraryFetchedAt = 0L

    private fun addLibraryLayers(st: Style) {
        srcLib = GeoJsonSource("lib-src", FeatureCollection.fromFeatures(emptyArray())).also { st.addSource(it) }
        val firstLabel = st.layers.firstOrNull { it is SymbolLayer }?.id
        val l1 = LineLayer("lib-casing", "lib-src").withProperties(lineColor(ContextCompat.getColor(this, R.color.route_casing)), lineWidth(7f), lineOpacity(0.8f), lineCap(Property.LINE_CAP_ROUND), lineJoin(Property.LINE_JOIN_ROUND))
        val l2 = LineLayer("lib-line", "lib-src").withProperties(lineColor(ContextCompat.getColor(this, R.color.track)), lineWidth(4f), lineOpacity(0.85f), lineCap(Property.LINE_CAP_ROUND), lineJoin(Property.LINE_JOIN_ROUND))
        if (firstLabel != null) { st.addLayerBelow(l1, firstLabel); st.addLayerBelow(l2, firstLabel) } else { st.addLayer(l1); st.addLayer(l2) }
        st.addLayer(SymbolLayer("lib-label", "lib-src").withProperties(textField(E.get("name")), textSize(12f), textFont(arrayOf("Noto Sans Medium")), textColor(ContextCompat.getColor(this, R.color.track)), textHaloColor(ContextCompat.getColor(this, R.color.route_casing)), textHaloWidth(1.5f), symbolPlacement(Property.SYMBOL_PLACEMENT_LINE), textAllowOverlap(false)))
        if (libraryOn) drawLibrary()
    }

    private fun showLibrary(on: Boolean) {
        libraryOn = on
        if (!on) { libraryItems = emptyList(); drawLibrary(); toast("Roadbooks hidden"); return }
        fetchLibrary(true)
    }

    private fun fetchLibrary(announce: Boolean) {
        val m = map ?: return
        val b = try { m.projection.visibleRegion.latLngBounds } catch (_: Throwable) { return }
        libraryFetchedAt = System.currentTimeMillis()
        Library.browse(b.longitudeWest, b.latitudeSouth, b.longitudeEast, b.latitudeNorth) { r -> ui.post {
            if (!libraryOn) return@post
            r.onSuccess { list -> libraryItems = list; drawLibrary(); if (announce) toast(if (list.isEmpty()) "No roadbooks published for this part of the map yet. Zoom out, or be the first: TRACK → PUBLISH." else "${list.size} roadbooks here — tap one. Hide them from the menu.", 5000) }
             .onFailure { e -> if (announce) toast("Library not reachable: ${e.message}", 6000) }
        } }
    }

    private fun drawLibrary() {
        val feats = libraryItems.map { it ->
            val f = Feature.fromGeometry(LineString.fromLngLats(it.line.map { p -> Point.fromLngLat(p[0], p[1]) }))
            f.addStringProperty("id", it.id); f.addStringProperty("name", it.name); f
        }
        srcLib?.setGeoJson(FeatureCollection.fromFeatures(feats))
    }

    private fun openLibraryItem(item: LibraryItem) {
        val surf = if (item.unpavedShare.isNaN()) "" else " · ${Math.round(item.unpavedShare * 100)}% unpaved"
        val modeLabel = try { if (item.mode.isNotEmpty()) " · " + Profile.valueOf(item.mode).label else "" } catch (_: Throwable) { "" }
        androidx.appcompat.app.AlertDialog.Builder(this).setTitle(item.name)
            .setMessage(Units.distance(item.lengthM) + " · ${item.cues} cues" + modeLabel + surf + (if (item.created.isNotEmpty()) "\nPublished ${item.created}" else ""))
            .setPositiveButton("OPEN") { _, _ -> withLibraryGpx(item) { tr, _ -> onGpxLoaded(tr, null) } }
            .setNeutralButton("SAVE") { _, _ -> withLibraryGpx(item) { _, text -> val file = item.name.replace(Regex("[^\\w .-]+"), "_").trim().ifEmpty { "Roadbook" } + ".gpx"; val go = { writeToFolder(file, text); Unit }; if (FilePicker.writableFolder(this) == null) chooseFolder(go) else go() } }
            .setNegativeButton("CLOSE", null).show()
    }

    private fun withLibraryGpx(item: LibraryItem, then: (Gpx.Track, String) -> Unit) {
        val dlg = ProgressDialogLite(this, "Fetching ${item.name}…"); dlg.show()
        Library.download(item) { r -> ui.post {
            dlg.dismiss()
            r.onSuccess { text -> val tr = try { Gpx.parse(text) } catch (_: Throwable) { null }; if (tr == null || tr.lats.size < 2) toast("That roadbook has no track") else then(tr, text) }
             .onFailure { e -> toast("Could not fetch it: ${e.message}", 6000) }
        } }
    }

    // ---------------------------------------------------------------- the glove menu

    private class MenuItem(val label: String, val icon: Int, val accent: Boolean = false, val on: Boolean = false, val action: () -> Unit)

    private fun menuItems(): List<MenuItem> {
        val nav = mode == Mode.NAV
        val items = ArrayList<MenuItem>()
        if (nav) {
            items.add(MenuItem("END", R.drawable.ic_end, accent = true) { endNavigation() })
            items.add(MenuItem(if (prefs.sound) "SOUND ON" else "SOUND OFF", if (prefs.sound) R.drawable.ic_sound_on else R.drawable.ic_sound_off, on = prefs.sound) { findViewById<ImageButton>(R.id.btnSound).performClick() })
            items.add(MenuItem(if (prefs.batterySaver) "BATTERY SAVER ON" else "BATTERY SAVER", R.drawable.ic_lock, on = prefs.batterySaver) { setBatterySaver(!prefs.batterySaver) })
            if (isTrack) items.add(MenuItem("ADD CUE HERE", R.drawable.ic_cue) { addCueHere() })
            if (!isTrack) items.add(MenuItem("TURN LIST", R.drawable.ic_list) { route?.let { showTurnList(it) } })
            items.add(MenuItem(if (Recorder.running) "RECORDING…" else "RECORD", R.drawable.ic_rec, on = Recorder.running) { if (Recorder.running) stopRecording() else startRecording() })
        } else {
            items.add(MenuItem("GO TO", R.drawable.ic_search) { findViewById<ImageButton>(R.id.btnGo).performClick() })
            items.add(MenuItem(if (destination != null && candidates.isNotEmpty()) "PLAN…" else "PLAN", R.drawable.ic_list, on = destination != null && candidates.isNotEmpty()) { startPlan() })
            items.add(MenuItem("TRACK", R.drawable.ic_track) { findViewById<ImageButton>(R.id.btnTrack).performClick() })
            items.add(MenuItem("MAPS", R.drawable.ic_maps) { findViewById<ImageButton>(R.id.btnMaps).performClick() })
            items.add(MenuItem(if (Recorder.running) "RECORDING…" else "RECORD", R.drawable.ic_rec, on = Recorder.running) { if (Recorder.running) stopRecording() else startRecording() })
            if (libraryOn) items.add(MenuItem("HIDE ROADBOOKS", R.drawable.ic_list, on = true) { showLibrary(false) })
            if (packs.packs.any { it.isTerrain }) items.add(MenuItem(if (prefs.terrain) "TERRAIN ON" else "TERRAIN OFF", R.drawable.ic_maps, on = prefs.terrain) { prefs.terrain = !prefs.terrain; loadStyle() })
            items.add(MenuItem(if (prefs.batterySaver) "BATTERY SAVER ON" else "BATTERY SAVER", R.drawable.ic_lock, on = prefs.batterySaver) { setBatterySaver(!prefs.batterySaver) })
        }
        items.add(MenuItem(if (prefs.night) "DAY MAP" else "NIGHT MAP", R.drawable.ic_night, on = prefs.night && !prefs.sun) { findViewById<ImageButton>(R.id.btnNight).performClick() })
        items.add(MenuItem(if (prefs.sun) "SUN MAP ON" else "SUN MAP", R.drawable.ic_night, on = prefs.sun) { toggleSun() })
        items.add(MenuItem("LOCATE", R.drawable.ic_locate) { findViewById<ImageButton>(R.id.btnLocate).performClick() })
        items.add(MenuItem(if (prefs.orientation >= 0) "UNLOCK SCREEN" else "LOCK SCREEN", if (prefs.orientation >= 0) R.drawable.ic_lock else R.drawable.ic_unlock, on = prefs.orientation >= 0) { toggleOrientationLock() })
        return items
    }

    private fun openMenu() {
        val scrim = findViewById<View>(R.id.menuScrim); val sheet = findViewById<View>(R.id.menuSheet)
        val grid = findViewById<android.widget.GridLayout>(R.id.menuGrid)
        grid.removeAllViews()
        findViewById<TextView>(R.id.menuTitle).text = if (mode == Mode.NAV) (if (isTrack) "FOLLOWING " + trackName.uppercase() else "NAVIGATING") else getString(R.string.app_name).uppercase()
        val items = menuItems()
        // the sheet reaches up to the HUD; giant buttons share the room: two columns upright, four on a phone held sideways
        val root = findViewById<View>(R.id.root)
        val landscape = root.width > root.height
        val cols = if (landscape) 4 else 2
        grid.columnCount = cols
        val top = (if (hud.visibility == View.VISIBLE) hud.bottom else hud.top) + dp(12)
        val avail = root.height - top - dp(16 + 10 + 24 + 76 + 18 + 12)
        val rows = (items.size + cols - 1) / cols
        val itemH = (avail / rows - dp(10)).coerceIn(if (landscape) dp(64) else dp(84), dp(132))
        for ((i, item) in items.withIndex()) {
            val it = item
            val b = Button(this).apply {
                text = it.label; textSize = 19f; setTypeface(typeface, android.graphics.Typeface.BOLD); letterSpacing = 0.04f; isAllCaps = false
                background = ContextCompat.getDrawable(this@MainActivity, if (it.accent) R.drawable.bg_accent else R.drawable.bg_menu_item)
                setTextColor(if (it.accent) Color.WHITE else ContextCompat.getColor(this@MainActivity, R.color.ink))
                stateListAnimator = null; gravity = android.view.Gravity.CENTER
                val icon = ContextCompat.getDrawable(this@MainActivity, it.icon)?.mutate()?.apply { setBounds(0, 0, dp(34), dp(34)); setTint(if (it.accent) Color.WHITE else ContextCompat.getColor(this@MainActivity, if (it.on) R.color.accent else R.color.ink)) }
                setCompoundDrawables(null, icon, null, null); compoundDrawablePadding = dp(6)
                setPadding(dp(8), dp(14), dp(8), dp(12))
                setOnClickListener { haptic(1); closeMenu(); item.action() }
            }
            val lp = android.widget.GridLayout.LayoutParams(android.widget.GridLayout.spec(i / cols, 1f), android.widget.GridLayout.spec(i % cols, 1f)).apply { width = 0; height = itemH; setMargins(dp(5), dp(5), dp(5), dp(5)) }
            grid.addView(b, lp)
        }
        // the grid fits by construction; when the minimum button height clamps (a short landscape screen) it scrolls instead
        val scroll = findViewById<android.widget.ScrollView>(R.id.menuScroll)
        val gridH = rows * (itemH + dp(10))
        scroll.layoutParams = scroll.layoutParams.apply { height = if (gridH > avail) avail.coerceAtLeast(dp(120)) else android.view.ViewGroup.LayoutParams.WRAP_CONTENT }
        scrim.visibility = View.VISIBLE
        sheet.translationY = sheet.height.toFloat().takeIf { it > 0 } ?: dp(600).toFloat()
        sheet.animate().translationY(0f).setDuration(180).setInterpolator(android.view.animation.DecelerateInterpolator()).start()
        findViewById<View>(R.id.btnMenu).visibility = View.INVISIBLE
    }

    private fun closeMenu(animate: Boolean = true) {
        val scrim = findViewById<View>(R.id.menuScrim); val sheet = findViewById<View>(R.id.menuSheet)
        if (scrim.visibility != View.VISIBLE) return
        val circle = findViewById<View>(R.id.btnMenu)
        if (mode == Mode.IDLE || mode == Mode.NAV) circle.visibility = View.VISIBLE
        if (!animate) { scrim.visibility = View.GONE; return }
        sheet.animate().translationY(sheet.height.toFloat()).setDuration(150).withEndAction { scrim.visibility = View.GONE; sheet.translationY = 0f }.start()
    }

    override fun onBackPressed() { if (findViewById<View>(R.id.menuScrim).visibility == View.VISIBLE) closeMenu() else super.onBackPressed() }

    private fun onPacksChanged() { loadStyle(); if (lastFix == null) packs.packs.lastOrNull()?.let { val m = it.manifest; map?.moveCamera(CameraUpdateFactory.newLatLngBounds(LatLngBounds.from(m.maxLat, m.maxLon, m.minLat, m.minLon), dp(40))) } }

    private fun toast(msg: String, ms: Long = 3500) {
        toastView.text = msg; toastView.visibility = View.VISIBLE
        ui.removeCallbacks(hideToast); ui.postDelayed(hideToast, ms)
    }
    private val hideToast = Runnable { toastView.visibility = View.GONE }

    private inline fun safe(block: () -> Unit) { try { block() } catch (e: Throwable) { toast(e.message ?: "Error") } }
    fun dp(v: Int): Int = (v * resources.displayMetrics.density + 0.5f).toInt()

    /** false while the screen is off or the app is behind another: no camera work, a slower HUD tick, the service keeps the fixes coming */
    private var uiVisible = true
    override fun onStart() {
        super.onStart(); mapView.onStart(); uiVisible = true
        // a pinch the map thought it saw while we were away must not freeze the auto zoom: back to the mode's own zoom
        manualZoom = -1.0; gestureFrom = null; overviewDone = false
        if (follow) lastFix?.let { moveCamera(it.lat, it.lon, animate = false) }
    }
    override fun onResume() { super.onResume(); mapView.onResume() }
    override fun onPause() { super.onPause(); mapView.onPause() }
    override fun onStop() { uiVisible = false; super.onStop(); mapView.onStop() }
    override fun onLowMemory() { super.onLowMemory(); mapView.onLowMemory() }
    override fun onSaveInstanceState(outState: Bundle) { super.onSaveInstanceState(outState); mapView.onSaveInstanceState(outState) }
    override fun onDestroy() {
        super.onDestroy()
        try { unregisterReceiver(batteryRx) } catch (_: Throwable) {}
        try { locationManager.removeUpdates(locationListener) } catch (_: Throwable) {}
        ui.removeCallbacks(hudTick)
        mapView.onDestroy()
        worker.shutdownNow()
    }
}
