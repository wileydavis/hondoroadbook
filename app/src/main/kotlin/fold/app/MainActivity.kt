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
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.ContextCompat
import fold.core.Cue
import fold.core.Fix
import fold.core.Follower
import fold.core.Geo
import fold.core.Gpx
import fold.core.Hit
import fold.core.Maneuver
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
    private var trackLats: DoubleArray? = null
    private var trackLons: DoubleArray? = null
    private var trackName = "Track"
    private var trackUri: Uri? = null
    private val trackCues = ArrayList<Cue>()
    private var srcCues: GeoJsonSource? = null
    private lateinit var panelEdit: View
    private lateinit var recStrip: View
    private lateinit var recStatus: TextView

    /** Process-wide holder so toggling night mode (which recreates the activity) never loses a route. */
    object NavSession {
        var mode = Mode.IDLE; var lastFix: Fix? = null; var pin: LatLng? = null
        var route: Route? = null; var follower: Follower? = null; var isTrack = false; var destination: LatLng? = null
        /** points the route must pass through, in order; "go by Spokane" */
        var vias: MutableList<LatLng> = ArrayList()
        var destName = ""
        /** a start chosen on the map for planning, instead of the GPS position */
        var planStart: LatLng? = null
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
    private var follower: Follower?
        get() = NavSession.follower; set(v) { NavSession.follower = v }
    private var isTrack: Boolean
        get() = NavSession.isTrack; set(v) { NavSession.isTrack = v }
    private var destination: LatLng?
        get() = NavSession.destination; set(v) { NavSession.destination = v }
    private val vias: MutableList<LatLng> get() = NavSession.vias
    private var planStart: LatLng?
        get() = NavSession.planStart; set(v) { NavSession.planStart = v }
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
        if (uri != null) { FilePicker.rememberFolder(this, uri); afterFolder?.invoke() }
        afterFolder = null
    }
    private fun chooseFolder(then: () -> Unit) { afterFolder = then; safe { pickFolder.launch(FilePicker.folder(this)) } }
    // system picker as a fallback for any file the in-app list can't see
    private val browsePack = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) importPacks(listOf(uri)) }
    private val browseGpx = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) loadGpx(uri) }
    private fun showTrackPicker() {
        FilePicker.show(this, "Follow a track", listOf(".gpx"), "GPX tracks", { chooseFolder { showTrackPicker() } }, { e -> loadGpx(e.uri) }, null, { safe { browseGpx.launch(arrayOf("*/*")) } })
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
        if (prefs.orientation >= 0) requestedOrientation = prefs.orientation
        if (BuildConfig.ROADBOOK) { Speech.init(this); Speech.enabled = prefs.sound }
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
        toastView = findViewById(R.id.toast)
        panelCompare = findViewById(R.id.panelCompare); compareRows = findViewById(R.id.compareRows)
        turnChip = findViewById(R.id.turnChip); hudCompass = findViewById(R.id.hudCompass)
        findViewById<Button>(R.id.btnStartNav).setOnClickListener { goWithSelected() }
        findViewById<Button>(R.id.btnCompareCancel).setOnClickListener { cancelCompare() }
        findViewById<Button>(R.id.btnModes).setOnClickListener { pickModes() }

        findViewById<ImageButton>(R.id.btnGo).setOnClickListener { SearchDialog.show(this, packs, marks, { hit -> onSearchPick(hit) }, { m -> goToMark(m) }) }
        findViewById<ImageButton>(R.id.btnTrack).setOnClickListener { showTrackPicker() }
        findViewById<ImageButton>(R.id.btnMaps).setOnClickListener { MapsDialog.show(this, packs, marks, { showPackPicker() }, { p -> deletePack(p) }, { metric -> prefs.metric = metric; Units.metric = metric },
            onExportMarks = { val tree = FilePicker.folder(this); if (tree == null) chooseFolder { exportMarks() } else exportMarks() },
            onBurnMarks = { marks.burn(); drawMarks(); toast("All marks deleted") },
            onDownload = if (BuildConfig.ROADBOOK) ({ Catalog.show(this, packs) { onPacksChanged() } }) else null) }
        findViewById<Button>(R.id.btnRouteHere).setOnClickListener { pin?.let { routeTo(it, pinName) } }
        findViewById<Button>(R.id.btnPinCancel).setOnClickListener { clearPin() }
        findViewById<Button>(R.id.btnVia).setOnClickListener { pin?.let { addVia(it, pinName) } }
        findViewById<Button>(R.id.btnFrom).setOnClickListener { pin?.let { setStart(it) } }
        findViewById<Button>(R.id.btnSaveRoute).setOnClickListener { candidates[profile]?.let { saveOrShareRoute(it) } ?: toast("Pick a route first") }
        findViewById<View>(R.id.navCard).setOnClickListener { route?.let { r -> if (!isTrack) showTurnList(r) } }
        findViewById<ImageButton>(R.id.btnSavePin).setOnClickListener { pin?.let { ll -> MarkDialog.edit(this, null, if (pinName.isEmpty() || pinName.first().isDigit() || pinName.first() == '-') "" else pinName) { name, note -> marks.add(name, note, ll.latitude, ll.longitude); drawMarks(); clearPin(); toast("Saved: $name") } } }
        findViewById<Button>(R.id.btnEnd).setOnClickListener { endNavigation() }
        findViewById<ImageButton>(R.id.btnSound).setOnClickListener {
            val on = !prefs.sound; prefs.sound = on; Tone.enabled = on; Speech.enabled = on && BuildConfig.ROADBOOK; updateSoundButton()
            if (on) { Tone.turn(); if (BuildConfig.ROADBOOK) { if (Speech.ready) Speech.say("Sound on") else toast("Pips and chimes on; the voice is still loading.", 4000) } }
            else Speech.stop()
        }
        updateSoundButton()
        // orientation lock: holds the screen the way it is now, and remembers it
        val lock = View.OnClickListener { toggleOrientationLock() }
        findViewById<ImageButton>(R.id.btnLock).setOnClickListener(lock)
        findViewById<ImageButton>(R.id.btnLockNav).setOnClickListener(lock)
        updateLockButtons()
        // Roadbook: pre-run recording and cue authoring
        panelEdit = findViewById(R.id.panelEdit); recStrip = findViewById(R.id.recStrip); recStatus = findViewById(R.id.recStatus)
        if (BuildConfig.ROADBOOK) {
            findViewById<ImageButton>(R.id.btnRec).visibility = View.VISIBLE
            findViewById<ImageButton>(R.id.btnRec).setOnClickListener { startRecording() }
            findViewById<Button>(R.id.btnRecCue).setOnClickListener { addCueHere() }
            findViewById<Button>(R.id.btnRecStop).setOnClickListener { stopRecording() }
            findViewById<ImageButton>(R.id.btnNavCue).setOnClickListener { addCueHere() }
            findViewById<Button>(R.id.btnEditSave).setOnClickListener { saveTrack() }
            findViewById<Button>(R.id.btnEditList).setOnClickListener { showCueList() }
            findViewById<Button>(R.id.btnEditFollow).setOnClickListener { followEditedTrack() }
            findViewById<Button>(R.id.btnEditCancel).setOnClickListener { leaveEdit() }
            Recorder.onChange = { ui.post { updateRecStrip() } }
            if (Recorder.recover(this)) ui.postDelayed({ offerRecovery() }, 1500)
            updateRecStrip()
        }
        val locate = View.OnClickListener { follow = true; lastFix?.let { moveCamera(it.lat, it.lon, animate = true) } ?: toast("Waiting for GPS…") }
        findViewById<ImageButton>(R.id.btnLocate).setOnClickListener(locate)
        findViewById<ImageButton>(R.id.btnLocateNav).setOnClickListener(locate)
        val night = View.OnClickListener {
            val n = !prefs.night; prefs.night = n
            AppCompatDelegate.setDefaultNightMode(if (n) AppCompatDelegate.MODE_NIGHT_YES else AppCompatDelegate.MODE_NIGHT_NO)
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
                val cueHit = if (mode == Mode.EDIT || (mode == Mode.NAV && isTrack)) try { val pt = m.projection.toScreenLocation(ll); m.queryRenderedFeatures(android.graphics.RectF(pt.x - dp(22), pt.y - dp(22), pt.x + dp(22), pt.y + dp(22)), "cues-layer").firstOrNull() } catch (_: Throwable) { null } else null
                val cue = cueHit?.getNumberProperty("idx")?.toInt()?.let { trackCues.getOrNull(it) }
                val line = if (mode == Mode.COMPARE) try { val pt = m.projection.toScreenLocation(ll); m.queryRenderedFeatures(android.graphics.RectF(pt.x - dp(14), pt.y - dp(14), pt.x + dp(14), pt.y + dp(14)), "cmp-line").firstOrNull() } catch (_: Throwable) { null } else null
                val pickedMode = line?.getStringProperty("mode")?.let { try { Profile.valueOf(it) } catch (_: Throwable) { null } }
                when {
                    cue != null -> { editCue(cue); true }
                    mark != null -> { openMark(mark); true }
                    pickedMode != null && candidates.containsKey(pickedMode) -> { profile = pickedMode; prefs.profile = pickedMode; rebuildCompareRows(prefs.modes.toList().sortedBy { it.ordinal }, false); drawCandidates(); true }
                    mode == Mode.PIN -> { clearPin(); true }
                    mode == Mode.NAV -> { cycleZoom(); true }
                    else -> false
                }
            }
            m.addOnCameraIdleListener {
                refreshVisiblePacks()
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
            m.addOnCameraMoveListener { scale.update(m.projection.getMetersPerPixelAtLatitude(m.cameraPosition.target?.latitude ?: 45.0)) }
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
                } else if (p == null) toast(if (BuildConfig.ROADBOOK) "No maps yet — tap MAPS → DOWNLOAD" else "No maps yet — tap MAPS to import a .mappack")
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
        val v = visiblePacks()
        if (v.map { it.dir } != styledPacks.map { it.dir }) loadStyle()
    }

    private fun loadStyle() {
        val m = map ?: return
        val chosen = visiblePacks()
        val json = try { StyleBuilder.build(this, chosen, prefs.night) } catch (e: Throwable) { toast("Style error: ${e.message}"); return }
        styledPacks = chosen
        m.setStyle(Style.Builder().fromJson(json)) { st ->
            style = st
            try { addOverlays(st) } catch (e: Throwable) { toast("Overlay error: ${e.message}") }
            // re-draw anything we had (also restores a trip after a night-mode recreation)
            route?.let { drawRoute(it) }
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
        srcPin = GeoJsonSource("pin-src", FeatureCollection.fromFeatures(emptyArray())).also { st.addSource(it) }
        srcPuck = GeoJsonSource("puck-src", FeatureCollection.fromFeatures(emptyArray())).also { st.addSource(it) }
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
        addUnderLabels(LineLayer("lead-line", "lead-src").withProperties(lineColor(ContextCompat.getColor(this, R.color.ink)), lineWidth(2.5f), lineOpacity(0.45f), lineDasharray(arrayOf(2f, 2f)), lineCap(Property.LINE_CAP_ROUND)))
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
        planStart = ll; drawStart()
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

    /** Cue marker: a small round sign, orange for a danger, ink for everything else. */
    private fun cueBitmap(danger: Boolean): Bitmap {
        val s = dp(22); val b = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888); val c = Canvas(b)
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = if (danger) ContextCompat.getColor(this@MainActivity, R.color.accent) else ContextCompat.getColor(this@MainActivity, R.color.ink) }
        val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; style = Paint.Style.STROKE; strokeWidth = dp(2).toFloat() }
        c.drawCircle(s / 2f, s / 2f, s * 0.4f, fill); c.drawCircle(s / 2f, s / 2f, s * 0.4f, stroke)
        return b
    }

    private fun drawCues() {
        val feats = trackCues.mapIndexed { i, c -> Feature.fromGeometry(Point.fromLngLat(c.lon, c.lat)).also { f ->
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
            onEdit = { MarkDialog.edit(this, m, m.name) { name, note -> marks.update(m, name, note); drawMarks() } },
            onDelete = { marks.remove(m); drawMarks(); toast("Deleted ${m.name}") })
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
        style?.getLayer("route-line")?.setProperties(lineColor(if (r.isTrack) ContextCompat.getColor(this, R.color.track) else Color.parseColor(profile.color)))
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
        if (fix.speed > 1.5 && fix.bearing >= 0) heading = fix.bearing
        follower?.update(fix)
        val f = follower
        if (f != null) {
            val v = f.view(android.os.SystemClock.elapsedRealtime())
            if (mode == Mode.NAV) { updatePuck(v.lat, v.lon, if (v.onRoute && v.speed > 1.5) f.route.segmentBearing(v.segment) else heading); handleOffRoute(v, fix) }
            else updatePuck(fix.lat, fix.lon, heading)
        } else updatePuck(fix.lat, fix.lon, heading)
        if (follow || first) moveCamera(fix.lat, fix.lon, animate = !first)
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
        destination = dest; destName = label; vias.clear()
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
        vias.add(idx, ll)
        pin = null; clearPinMarker()
        val fix = startFix()
        toast("Via " + (if (label.isNotEmpty() && !label.first().isDigit() && label.first() != '-') label else "point") + " — routing…")
        drawVias()
        if (mode == Mode.NAV) computeRoute(fix, dest, initial = true) else startCompare(compareFrom ?: fix, dest)
    }

    private fun drawVias() {
        srcVia?.setGeoJson(FeatureCollection.fromFeatures(vias.map { Feature.fromGeometry(Point.fromLngLat(it.longitude, it.latitude)) }))
    }

    /** Reroute (or single-mode routing): one mode, straight into navigation. */
    private fun computeRoute(from: Fix, dest: LatLng, initial: Boolean) {
        val gen = ++routeGeneration
        val p = profile
        if (initial && toastView.visibility != View.VISIBLE) toast("Routing…")
        rerouting = true
        val viasCopy = ArrayList(vias)
        worker.execute {
            val result = try { routeOne(from, dest, viasCopy, p) } catch (e: Throwable) { e }
            ui.post {
                rerouting = false
                if (gen != routeGeneration) return@post
                when (result) {
                    is Route -> startNavigation(result, false)
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
        val rr = router.routeVia(pts, p) ?: throw IllegalStateException(getString(R.string.no_route))
        return Route.fromGraphRoute(rr, router, p)
    }

    /**
     * The compare screen: every enabled mode is routed in turn and drawn as it finishes, whole-route
     * view, with a row per mode. Tap a row (or a line) to select; GO starts; VIA pulls all of them.
     */
    private fun startCompare(from: Fix, dest: LatLng) {
        val modes = prefs.modes.toList().sortedBy { it.ordinal }
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
            for (p in modes) {
                if (gen != compareGeneration) return@execute
                val r = try { routeOne(from, dest, viasCopy, p) } catch (e: Throwable) { if (firstError == null) firstError = e.message; null }
                ui.post {
                    if (gen != compareGeneration) return@post
                    if (r != null) candidates[p] = r
                    rebuildCompareRows(modes, inProgress = p != modes.last())
                    drawCandidates()
                    if (candidates.size == 1 || p == modes.last()) fitCandidates()
                }
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
        startNavigation(r, false)
    }

    private fun cancelCompare() {
        compareGeneration++
        candidates.clear()
        srcCompare?.setGeoJson(FeatureCollection.fromFeatures(emptyArray()))
        if (route != null) { switchMode(Mode.NAV) } else { destination = null; vias.clear(); drawVias(); clearPinMarker(); planStart = null; drawStart(); switchMode(Mode.IDLE) }
    }

    // ---- saved routes: GPX with the points as a route and the line as a track; anyone can open it

    private fun routeGpx(r: Route): String {
        val from = compareFrom?.let { LatLng(it.lat, it.lon) } ?: LatLng(r.lats[0], r.lons[0])
        val pts = ArrayList<LatLng>(); pts.add(from); pts.addAll(vias); destination?.let { pts.add(it) }
        val names = ArrayList<String>(); names.add("Start"); for (i in vias.indices) names.add("Via ${i + 1}"); names.add(destName.ifEmpty { "End" })
        val title = (destName.ifEmpty { "Route" }) + " (" + profile.label + ")"
        val text = Gpx.writeRoute(title, profile.name, pts.map { it.latitude }.toDoubleArray(), pts.map { it.longitude }.toDoubleArray(), names, r.lats, r.lons)
        if (!BuildConfig.ROADBOOK) return text
        // Roadbook: the router's turns go in as cues, so the file is a roadbook you can annotate and follow as a track
        val cues = r.maneuvers.filter { it.turn != Turn.DEPART && it.turn != Turn.ARRIVE }.map { m -> Cue(r.lats[m.vertex], r.lons[m.vertex], m.turn, if (m.turn == Turn.ROUNDABOUT) "exit ${m.exit}" else m.name) }
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
                prefs.modes = sel
                val from = compareFrom; val dest = destination
                if (from != null && dest != null) startCompare(from, dest)
            }
            .setNegativeButton(R.string.cancel, null).show()
    }

    private fun startNavigation(r: Route, track: Boolean) {
        route = r; isTrack = track
        follower = Follower(r).also { f -> lastFix?.let { f.update(it) } }
        drawRoute(r)
        drawVias()
        clearPinMarker()
        if (!track) destination?.let { showPinMarker(it) }
        follow = true
        offSince = 0L
        cuedManeuver = null; turnChimed = false; wasOffRoute = false; arrivedChimed = false; nextPipAt = 0L
        zoomMode = ZoomMode.TURN; manualZoom = -1.0; overviewDone = false
        switchMode(Mode.NAV)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
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

    private fun endNavigation() {
        Speech.stop()
        routeGeneration++
        route = null; follower = null; destination = null; pin = null; isTrack = false; vias.clear(); destName = ""; candidates.clear(); compareGeneration++; planStart = null; drawStart()
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
            vias.removeAll { v -> var best = Double.MAX_VALUE; var at = 0.0; for (i in 0 until r.size) { val d = Geo.fastDistance(v.latitude, v.longitude, r.lats[i], r.lons[i]); if (d < best) { best = d; at = r.cum[i] } }; at < here - 500.0 }
        }
        computeRoute(fix, dest, initial = true)
    }

    // ---------------------------------------------------------------- HUD

    private val hudTick = object : Runnable {
        override fun run() {
            val f = follower ?: return
            try { renderHud(f.view(android.os.SystemClock.elapsedRealtime())) } catch (_: Throwable) {}
            ui.postDelayed(this, 50)
        }
    }

    /** Dashed line from you to the next turn with a time-to-turn chip, for turns still far out (next-turn view only). */
    private fun updateLead(v: NavView) {
        val m = map; val next = v.next; val r = follower?.route
        val show = m != null && r != null && next != null && v.onRoute && !v.arrived && zoomMode == ZoomMode.TURN && next.turn != Turn.ARRIVE && v.progress <= 0.0 && v.distToNext > 120.0
        if (!show) { if (turnChip.visibility != View.GONE) { turnChip.visibility = View.GONE; srcLead?.setGeoJson(FeatureCollection.fromFeatures(emptyArray())) }; return }
        val tLat = r!!.lats[next!!.vertex]; val tLon = r.lons[next.vertex]
        srcLead?.setGeoJson(Feature.fromGeometry(LineString.fromLngLats(listOf(Point.fromLngLat(v.lon, v.lat), Point.fromLngLat(tLon, tLat)))))
        // (the time-to-turn chip was retired: the HUD already shows the distance; the line alone marks the view)
        turnChip.visibility = View.GONE
    }

    private fun renderHud(v: NavView) {
        soundTick(v, android.os.SystemClock.elapsedRealtime())
        if (mode != Mode.NAV) { if (v.onRoute) updatePuck(v.lat, v.lon, follower?.route?.segmentBearing(v.segment) ?: heading); return }
        if (v.arrived) {
            hud.visibility = View.VISIBLE; offPanel.visibility = View.GONE
            arrow.turn = Turn.ARRIVE; hudDistance.text = getString(R.string.arrived); hudStreet.text = ""; arrow.progress = 1f
            navRemaining.text = Units.distance(0.0)
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
                arrow.turn = m.turn; arrow.exit = if (m.turn == Turn.DANGER) m.level else m.exit
                hudDistance.text = Units.cue(v.distToNext)
                hudStreet.text = when { m.turn == Turn.ARRIVE -> "Destination"; m.note.isNotEmpty() -> m.note; else -> m.name.ifEmpty { if (isTrack) "" else "unnamed road" } }
                arrow.progress = v.progress.toFloat()
            }
        }
        navRemaining.text = Units.distance(v.remaining) + " · " + Units.time(v.etaSeconds)
        navSpeed.text = Units.speed(v.speed)
        map?.let { hudCompass.bearing = it.cameraPosition.bearing.toFloat() }
        updateLead(v)
        // smooth puck between fixes
        if (v.onRoute) updatePuck(v.lat, v.lon, follower?.route?.segmentBearing(v.segment) ?: heading)
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
                val text = contentResolver.openInputStream(uri)?.use { it.readBytes() }?.let { bytes ->
                    if (bytes.size > 64 shl 20) throw IllegalStateException("GPX is too large") else String(bytes, Charsets.UTF_8)
                } ?: throw IllegalStateException("Could not read file")
                Gpx.parse(text) ?: throw IllegalStateException("No track or route points found")
            } catch (e: Throwable) { e }
            ui.post {
                when (result) {
                    is Gpx.Track -> onGpxLoaded(result, uri)
                    is Throwable -> toast("Track failed: ${result.message}")
                }
            }
        }
    }

    /** A GPX with route points is re-routed on this phone's maps; a bare track is followed as a line. */
    private fun onGpxLoaded(tr: Gpx.Track, uri: Uri? = null) {
        if (BuildConfig.ROADBOOK && tr.hasTrack) {
            // Roadbook: a track is something you author as well as follow
            val n = tr.cues.size
            androidx.appcompat.app.AlertDialog.Builder(this).setTitle(tr.name)
                .setMessage(Units.distance(Route.fromTrack(tr.lats, tr.lons, tr.name, tr.cues).length) + (if (n > 0) " · $n cues" else " · no cues yet") + (if (tr.hasRoute && n == 0) "\n\nThis file also carries route points; RE-ROUTE plans it on your maps." else ""))
                .setPositiveButton("FOLLOW") { _, _ -> followTrack(tr, uri) }
                .setNeutralButton("EDIT CUES") { _, _ -> openEditor(tr, uri) }
                .apply { if (tr.hasRoute && n == 0) setNegativeButton("RE-ROUTE") { _, _ -> rerouteGpx(tr) } else setNegativeButton(R.string.cancel, null) }
                .show()
            return
        }
        if (tr.hasRoute) rerouteGpx(tr) else followTrack(tr, uri)
    }

    private fun followTrack(tr: Gpx.Track, uri: Uri?) {
        trackLats = tr.lats; trackLons = tr.lons; trackName = tr.name; trackUri = uri; trackCues.clear(); trackCues.addAll(tr.cues)
        val r = Route.fromTrack(tr.lats, tr.lons, tr.name, tr.cues); destination = null; startNavigation(r, true); drawCues()
        toast("Following track (${Units.distance(r.length)})" + if (tr.cues.isNotEmpty()) " · ${tr.cues.size} cues" else "")
    }

    private fun rerouteGpx(tr: Gpx.Track) {
        if (tr.hasRoute) {
            val n = tr.rteLats.size
            val start = LatLng(tr.rteLats[0], tr.rteLons[0]); val dest = LatLng(tr.rteLats[n - 1], tr.rteLons[n - 1])
            if (packs.packFor(start.latitude, start.longitude) == null || packs.packFor(dest.latitude, dest.longitude) == null) {
                if (tr.hasTrack) { toast("Route is outside your maps — following its line instead", 5000); followTrack(tr, null) }
                else toast("Route is outside your maps", 6000)
                return
            }
            tr.mode?.let { m -> try { val p = Profile.valueOf(m); profile = p; prefs.profile = p; if (!prefs.modes.contains(p)) prefs.modes = prefs.modes + p } catch (_: Throwable) {} }
            destination = dest; destName = tr.name; vias.clear(); for (i in 1 until n - 1) vias.add(LatLng(tr.rteLats[i], tr.rteLons[i])); drawVias()
            // start from the GPS fix if you are near the route's start, otherwise plan from the route's own start
            val fix = lastFix
            val nearStart = fix != null && Geo.fastDistance(fix.lat, fix.lon, start.latitude, start.longitude) < 2000.0
            if (!nearStart) { planStart = start; drawStart() } else planStart = null
            showPinMarker(dest)
            toast("Routing \"${tr.name}\" on your maps…", 4000)
            startCompare(startFix(), dest)
        } else followTrack(tr, null)
    }

    // ---------------------------------------------------------------- misc

    private fun switchMode(m: Mode) {
        mode = m
        panelIdle.visibility = if (m == Mode.IDLE) View.VISIBLE else View.GONE
        panelPin.visibility = if (m == Mode.PIN) View.VISIBLE else View.GONE
        findViewById<View>(R.id.btnVia).visibility = if (m == Mode.PIN && destination != null && !isTrack) View.VISIBLE else View.GONE
        panelNav.visibility = if (m == Mode.NAV) View.VISIBLE else View.GONE
        panelCompare.visibility = if (m == Mode.COMPARE) View.VISIBLE else View.GONE
        panelEdit.visibility = if (m == Mode.EDIT) View.VISIBLE else View.GONE
        findViewById<View>(R.id.navSide).visibility = if (m == Mode.NAV || m == Mode.EDIT) View.VISIBLE else View.GONE
        findViewById<View>(R.id.btnNavCue).visibility = if (m == Mode.NAV && isTrack && BuildConfig.ROADBOOK) View.VISIBLE else View.GONE
        updateRecStrip()
        if (m != Mode.NAV) { hud.visibility = View.GONE; offPanel.visibility = View.GONE; turnChip.visibility = View.GONE; srcLead?.setGeoJson(FeatureCollection.fromFeatures(emptyArray())) }
        map?.uiSettings?.isCompassEnabled = m != Mode.NAV
        if (m != Mode.COMPARE) { srcCompare?.setGeoJson(FeatureCollection.fromFeatures(emptyArray())) }
    }

    private fun updateSoundButton() { findViewById<ImageButton>(R.id.btnSound).setImageResource(if (prefs.sound) R.drawable.ic_sound_on else R.drawable.ic_sound_off) }

    // ---- audible cue state: pips track the bar, one chime at the turn
    private var nextPipAt = 0L
    private var cuedManeuver: Maneuver? = null
    private var turnChimed = false
    private var wasOffRoute = false
    private var arrivedChimed = false
    private var spoken = false

    private fun soundTick(v: NavView, now: Long) {
        if (!Tone.enabled) return
        if (v.arrived) { if (!arrivedChimed) { arrivedChimed = true; Tone.arrive() }; return }
        if (!v.onRoute) { if (!wasOffRoute) { wasOffRoute = true; Tone.offRoute() }; return }
        if (wasOffRoute) { wasOffRoute = false; Tone.back() }
        val m = v.next ?: return
        if (m !== cuedManeuver) { cuedManeuver = m; turnChimed = false; spoken = false; nextPipAt = 0L; if (m.turn != Turn.ARRIVE) Speech.prepare(m.phrase()) }
        if (m.turn == Turn.ARRIVE) return
        if (v.progress <= 0.0) return
        // the window just opened (~15 s out): say what is coming, then the pips take over
        if (!spoken) { spoken = true; if (Speech.enabled && Speech.ready) { Speech.say(m.phrase()); nextPipAt = now + 1500 + 60L * m.phrase().length } }
        if (v.progress >= 0.97 || v.distToNext < 12.0) {
            if (!turnChimed) { turnChimed = true; when (m.turn) { Turn.DANGER -> Tone.danger(m.level); Turn.NOTE -> Tone.note(); else -> Tone.turn() } }
            return
        }
        if (now >= nextPipAt) { Tone.pip(v.progress); nextPipAt = now + Tone.pipIntervalMs(v.progress) }
    }



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
        route = r; follower = Follower(r).also { f -> lastFix?.let { f.update(it) } }
        cuedManeuver = null
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
        if (uri != null) b.setPositiveButton("SAVE OVER FILE") { _, _ -> try { contentResolver.openOutputStream(uri, "wt")?.use { it.write(trackGpx().toByteArray()) } ?: throw IllegalStateException("Could not open the file"); toast("Saved") } catch (e: Throwable) { toast("Save failed: ${e.message}", 6000) } }
        b.setNeutralButton(if (uri != null) "SAVE AS…" else "SAVE TO FOLDER") { _, _ -> askNameAndWrite(trackName) { trackGpx() } }
        b.setNegativeButton(R.string.cancel, null).show()
    }

    private fun askNameAndWrite(suggest: String, body: () -> String) {
        val input = android.widget.EditText(this).apply { setText(suggest); isSingleLine = true; textSize = 18f; setSelectAllOnFocus(true); setPadding(dp(20), dp(12), dp(20), dp(12)) }
        androidx.appcompat.app.AlertDialog.Builder(this).setTitle("File name").setView(input)
            .setPositiveButton("SAVE") { _, _ ->
                val name = input.text.toString().trim().ifEmpty { suggest }
                val go = { writeToFolder(name.removeSuffix(".gpx") + ".gpx", body()) }
                if (FilePicker.folder(this) == null) chooseFolder(go) else go()
            }.setNegativeButton(R.string.cancel, null).show()
    }

    private fun writeToFolder(fileName: String, text: String) {
        val tree = FilePicker.folder(this) ?: run { toast("No folder chosen"); return }
        try {
            val parent = android.provider.DocumentsContract.buildChildDocumentsUriUsingTree(tree, android.provider.DocumentsContract.getTreeDocumentId(tree))
            val doc = android.provider.DocumentsContract.createDocument(contentResolver, parent, "application/gpx+xml", fileName) ?: throw IllegalStateException("Could not create file")
            contentResolver.openOutputStream(doc)?.use { it.write(text.toByteArray()) } ?: throw IllegalStateException("Could not write file")
            toast("Saved $fileName — it is under TRACK", 5000)
        } catch (e: Throwable) { toast("Save failed: ${e.message}", 6000) }
    }

    // ---- pre-run recording
    private val askNotify = registerForActivityResult(ActivityResultContracts.RequestPermission()) { startRecordingNow() }

    private fun startRecording() {
        if (Recorder.running) return
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
            .setPositiveButton("SAVE") { _, _ -> askNameAndWrite(Recorder.defaultName()) { Recorder.gpx(Recorder.defaultName()) }; Recorder.recoveryFile(this).delete() }
            .setNeutralButton("SHARE…") { _, _ -> shareText(Recorder.defaultName().replace(Regex("[^A-Za-z0-9 .-]"), "") + ".gpx", Recorder.gpx(Recorder.defaultName())) }
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
        if (!BuildConfig.ROADBOOK) return
        val on = Recorder.running
        recStrip.visibility = if (on && mode != Mode.COMPARE && mode != Mode.PIN) View.VISIBLE else View.GONE
        // keep the night/locate/lock stack clear of the strip
        findViewById<View>(R.id.navSide).let { v -> (v.layoutParams as android.widget.FrameLayout.LayoutParams).bottomMargin = dp(if (recStrip.visibility == View.VISIBLE) 152 else 104); v.requestLayout() }
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

    private fun onPacksChanged() { loadStyle(); if (lastFix == null) packs.packs.lastOrNull()?.let { val m = it.manifest; map?.moveCamera(CameraUpdateFactory.newLatLngBounds(LatLngBounds.from(m.maxLat, m.maxLon, m.minLat, m.minLon), dp(40))) } }

    private fun toast(msg: String, ms: Long = 3500) {
        toastView.text = msg; toastView.visibility = View.VISIBLE
        ui.removeCallbacks(hideToast); ui.postDelayed(hideToast, ms)
    }
    private val hideToast = Runnable { toastView.visibility = View.GONE }

    private inline fun safe(block: () -> Unit) { try { block() } catch (e: Throwable) { toast(e.message ?: "Error") } }
    fun dp(v: Int): Int = (v * resources.displayMetrics.density + 0.5f).toInt()

    override fun onStart() { super.onStart(); mapView.onStart() }
    override fun onResume() { super.onResume(); mapView.onResume() }
    override fun onPause() { super.onPause(); mapView.onPause() }
    override fun onStop() { super.onStop(); mapView.onStop() }
    override fun onLowMemory() { super.onLowMemory(); mapView.onLowMemory() }
    override fun onSaveInstanceState(outState: Bundle) { super.onSaveInstanceState(outState); mapView.onSaveInstanceState(outState) }
    override fun onDestroy() {
        super.onDestroy()
        try { locationManager.removeUpdates(locationListener) } catch (_: Throwable) {}
        ui.removeCallbacks(hudTick)
        mapView.onDestroy()
        worker.shutdownNow()
    }
}
