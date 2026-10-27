package fold.core

import kotlin.math.*

/** Small, allocation-free geodesy helpers. Coordinates are degrees unless stated. */
object Geo {
    const val EARTH_RADIUS = 6371008.8
    private const val DEG = PI / 180.0

    /** Great-circle distance in meters. */
    fun distance(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val dLat = (lat2 - lat1) * DEG
        val dLon = (lon2 - lon1) * DEG
        val a = sin(dLat / 2).pow(2) + cos(lat1 * DEG) * cos(lat2 * DEG) * sin(dLon / 2).pow(2)
        return 2 * EARTH_RADIUS * asin(min(1.0, sqrt(a)))
    }

    /** Fast equirectangular distance, accurate to <0.5% below ~100 km. */
    fun fastDistance(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val x = (lon2 - lon1) * DEG * cos((lat1 + lat2) * 0.5 * DEG)
        val y = (lat2 - lat1) * DEG
        return sqrt(x * x + y * y) * EARTH_RADIUS
    }

    /** Initial bearing from point 1 to point 2, degrees in [0, 360). */
    fun bearing(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val p1 = lat1 * DEG; val p2 = lat2 * DEG; val dl = (lon2 - lon1) * DEG
        val y = sin(dl) * cos(p2)
        val x = cos(p1) * sin(p2) - sin(p1) * cos(p2) * cos(dl)
        val b = atan2(y, x) / DEG
        return (b + 360.0) % 360.0
    }

    /** Signed smallest difference b - a in degrees, in (-180, 180]. Positive = clockwise (right). */
    fun turnAngle(a: Double, b: Double): Double {
        var d = (b - a) % 360.0
        if (d > 180.0) d -= 360.0
        if (d <= -180.0) d += 360.0
        return d
    }

    /** Point at [distance] meters from (lat,lon) along [bearing]. Returns lat,lon pair. */
    fun destination(lat: Double, lon: Double, bearing: Double, distance: Double): DoubleArray {
        val d = distance / EARTH_RADIUS
        val b = bearing * DEG
        val p1 = lat * DEG; val l1 = lon * DEG
        val p2 = asin(sin(p1) * cos(d) + cos(p1) * sin(d) * cos(b))
        val l2 = l1 + atan2(sin(b) * sin(d) * cos(p1), cos(d) - sin(p1) * sin(p2))
        return doubleArrayOf(p2 / DEG, ((l2 / DEG + 540.0) % 360.0) - 180.0)
    }

    /**
     * Projects point P onto segment AB in a local equirectangular frame.
     * Returns [t in 0..1, distance meters, projLat, projLon].
     */
    fun projectOnSegment(
        pLat: Double, pLon: Double,
        aLat: Double, aLon: Double,
        bLat: Double, bLon: Double,
        out: DoubleArray
    ) {
        val k = cos(pLat * DEG)
        val ax = aLon * k; val ay = aLat
        val bx = bLon * k; val by = bLat
        val px = pLon * k; val py = pLat
        val dx = bx - ax; val dy = by - ay
        val len2 = dx * dx + dy * dy
        var t = if (len2 == 0.0) 0.0 else ((px - ax) * dx + (py - ay) * dy) / len2
        if (t < 0.0) t = 0.0 else if (t > 1.0) t = 1.0
        val qx = ax + t * dx; val qy = ay + t * dy
        val dist = sqrt((px - qx) * (px - qx) + (py - qy) * (py - qy)) * DEG * EARTH_RADIUS
        out[0] = t; out[1] = dist; out[2] = qy; out[3] = qx / k
    }
}
