package app.blindspot.ui

import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Point
import android.view.MotionEvent
import app.blindspot.data.Camera
import app.blindspot.geo.CameraIndex
import app.blindspot.geo.GeoMath
import app.blindspot.geo.LatLon
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.Projection
import org.osmdroid.views.overlay.Overlay
import kotlin.math.ceil
import kotlin.math.hypot
import kotlin.math.max

private fun Projection.px(lat: Double, lon: Double, out: Point): Point = toPixels(GeoPoint(lat, lon), out)

/** Radius in pixels of [meters] around a point, measured through the projection. */
private fun Projection.metersPx(lat: Double, lon: Double, meters: Double, tmpA: Point, tmpB: Point): Float {
    px(lat, lon, tmpA)
    val e = GeoMath.destination(lat, lon, 90.0, meters)
    px(e.lat, e.lon, tmpB)
    return hypot((tmpB.x - tmpA.x).toFloat(), (tmpB.y - tmpA.y).toFloat())
}

/**
 * Draws every camera as a dot plus its estimated visibility area: a viewing cone (sector) for
 * cameras with known directions and a dashed circle for cameras with unknown direction.
 * Cameras whose estimated coverage currently includes the user are drawn in red.
 */
class CameraOverlay(private val density: Float) : Overlay() {
    var onTap: ((Camera) -> Unit)? = null
    var index = CameraIndex(emptyList())
    var fovDeg = 110.0
    var maxDistanceM = 40.0
    var unknownRadiusM = 40.0
    var covering: Set<Long> = emptySet()
    var selectedId: Long? = null
    var showCoverage = true

    private val fillNormal = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; color = 0x40FFD600 }
    private val strokeNormal = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 1.5f * density; color = 0xE0B89B00.toInt()
    }
    private val strokeNormalDashed = Paint(strokeNormal).apply {
        pathEffect = DashPathEffect(floatArrayOf(6f * density, 4f * density), 0f)
    }
    private val fillActive = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; color = 0x55E53935 }
    private val strokeActive = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 2f * density; color = 0xE0B71C1C.toInt()
    }
    private val strokeActiveDashed = Paint(strokeActive).apply {
        pathEffect = DashPathEffect(floatArrayOf(6f * density, 4f * density), 0f)
    }
    private val dotFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; color = 0xFFFFD600.toInt() }
    private val dotStrokeNormal = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 1.5f * density; color = 0xFF5C4D00.toInt()
    }
    private val dotActive = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; color = 0xFFC62828.toInt() }
    private val dotStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 1.5f * density; color = 0xFFFFFFFF.toInt()
    }
    private val selectRing = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 3f * density; color = 0xFF1565C0.toInt()
    }
    private val a = Point()
    private val b = Point()
    private val path = Path()

    private fun visible(pj: Projection): List<Camera> {
        val bb = pj.boundingBox
        val padLat = (bb.latNorth - bb.latSouth) * 0.15 + 0.001
        val padLon = (bb.lonEast - bb.lonWest) * 0.15 + 0.001
        return index.inBounds(bb.latSouth - padLat, bb.lonWest - padLon, bb.latNorth + padLat, bb.lonEast + padLon)
    }

    override fun draw(c: Canvas, mapView: MapView, shadow: Boolean) {
        if (shadow || !isEnabled) return
        val pj = mapView.projection
        val zoom = pj.zoomLevel
        val cams = visible(pj)
        if (showCoverage && zoom >= 13.5 && cams.size <= 5000) {
            for (cam in cams) drawCoverage(c, pj, cam)
        }
        val r = when {
            zoom >= 15 -> 5.5f
            zoom >= 12 -> 4f
            else -> 2.5f
        } * density
        for (cam in cams) {
            pj.px(cam.lat, cam.lon, a)
            val active = cam.id in covering
            c.drawCircle(a.x.toFloat(), a.y.toFloat(), r, if (active) dotActive else dotFill)
            if (zoom >= 12) c.drawCircle(a.x.toFloat(), a.y.toFloat(), r, if (active) dotStroke else dotStrokeNormal)
            if (cam.id == selectedId) c.drawCircle(a.x.toFloat(), a.y.toFloat(), r + 5f * density, selectRing)
        }
    }

    private fun drawCoverage(c: Canvas, pj: Projection, cam: Camera) {
        val active = cam.id in covering
        drawCameraShape(
            c, pj, cam, fovDeg, maxDistanceM, unknownRadiusM,
            if (active) fillActive else fillNormal,
            if (active) strokeActive else strokeNormal,
            if (active) strokeActiveDashed else strokeNormalDashed,
            a, b, path,
        )
    }

    override fun onSingleTapConfirmed(e: MotionEvent, mapView: MapView): Boolean {
        val cb = onTap ?: return false
        val pj = mapView.projection
        var best: Camera? = null
        var bestD = (26f * density).let { it * it }
        for (cam in visible(pj)) {
            pj.px(cam.lat, cam.lon, a)
            val dx = a.x - e.x
            val dy = a.y - e.y
            val d2 = dx * dx + dy * dy
            if (d2 < bestD) { bestD = d2; best = cam }
        }
        best?.let { cb(it); return true }
        return false
    }
}

/** Draws a camera's estimated area: cone(s) for known directions, dashed circle otherwise. */
internal fun drawCameraShape(
    c: Canvas, pj: Projection, cam: Camera, fovDeg: Double, maxDistanceM: Double, unknownRadiusM: Double,
    fill: Paint, stroke: Paint, strokeDashed: Paint, a: Point, b: Point, path: Path,
) {
    if (!cam.hasDirection) {
        val rpx = pj.metersPx(cam.lat, cam.lon, unknownRadiusM, a, b)
        if (rpx < 2f) return
        pj.px(cam.lat, cam.lon, a)
        c.drawCircle(a.x.toFloat(), a.y.toFloat(), rpx, fill)
        c.drawCircle(a.x.toFloat(), a.y.toFloat(), rpx, strokeDashed)
        return
    }
    val steps = max(4, ceil(fovDeg / 8.0).toInt())
    for (dir in cam.directions) {
        path.reset()
        pj.px(cam.lat, cam.lon, a)
        path.moveTo(a.x.toFloat(), a.y.toFloat())
        for (s in 0..steps) {
            val bearing = dir - fovDeg / 2 + fovDeg * s / steps
            val p = GeoMath.destination(cam.lat, cam.lon, bearing, maxDistanceM)
            pj.px(p.lat, p.lon, b)
            path.lineTo(b.x.toFloat(), b.y.toFloat())
        }
        path.close()
        c.drawPath(path, fill)
        c.drawPath(path, stroke)
    }
}

/**
 * Long-press anywhere to start adding a camera. While a draft camera exists it is drawn in blue,
 * a long-press moves it and a tap points a viewing direction towards the tapped spot.
 */
class EditCameraOverlay(private val density: Float) : Overlay() {
    var draft: Camera? = null
    var fovDeg = 110.0
    var maxDistanceM = 40.0
    var unknownRadiusM = 40.0
    var onLongPress: ((LatLon) -> Unit)? = null
    var onDraftTap: ((LatLon) -> Unit)? = null

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; color = 0x401E88E5 }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 2f * density; color = 0xFF1565C0.toInt()
    }
    private val dashed = Paint(stroke).apply { pathEffect = DashPathEffect(floatArrayOf(6f * density, 4f * density), 0f) }
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; color = 0xFF1565C0.toInt() }
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 3f * density; color = 0xFFFFFFFF.toInt()
    }
    private val a = Point()
    private val b = Point()
    private val path = Path()

    override fun draw(c: Canvas, mapView: MapView, shadow: Boolean) {
        if (shadow) return
        val d = draft ?: return
        val pj = mapView.projection
        drawCameraShape(c, pj, d, fovDeg, maxDistanceM, unknownRadiusM, fill, stroke, dashed, a, b, path)
        pj.px(d.lat, d.lon, a)
        c.drawCircle(a.x.toFloat(), a.y.toFloat(), 9f * density, dot)
        c.drawCircle(a.x.toFloat(), a.y.toFloat(), 9f * density, ring)
    }

    private fun geo(e: MotionEvent, mapView: MapView): LatLon {
        val gp = mapView.projection.fromPixels(e.x.toInt(), e.y.toInt())
        return LatLon(gp.latitude, gp.longitude)
    }

    override fun onLongPress(e: MotionEvent, mapView: MapView): Boolean {
        val cb = onLongPress ?: return false
        cb(geo(e, mapView))
        return true
    }

    override fun onSingleTapConfirmed(e: MotionEvent, mapView: MapView): Boolean {
        if (draft == null) return false
        val cb = onDraftTap ?: return false
        cb(geo(e, mapView))
        return true
    }
}

/** The user's position with an accuracy circle. Data comes from the app's own GPS readings. */
class UserLocationOverlay(private val density: Float) : Overlay() {
    var lat: Double? = null
    var lon: Double? = null
    var accuracyM: Float? = null
    var stale = false

    private val accFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; color = 0x301E88E5 }
    private val accStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 1f * density; color = 0x801E88E5.toInt()
    }
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; color = 0xFF1E88E5.toInt() }
    private val dotStale = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; color = 0xFF90A4AE.toInt() }
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 2.5f * density; color = 0xFFFFFFFF.toInt()
    }
    private val a = Point()
    private val b = Point()

    override fun draw(c: Canvas, mapView: MapView, shadow: Boolean) {
        if (shadow) return
        val la = lat ?: return
        val lo = lon ?: return
        val pj = mapView.projection
        accuracyM?.let { acc ->
            val r = pj.metersPx(la, lo, acc.toDouble(), a, b)
            pj.px(la, lo, a)
            if (r > 8f * density) {
                c.drawCircle(a.x.toFloat(), a.y.toFloat(), r, accFill)
                c.drawCircle(a.x.toFloat(), a.y.toFloat(), r, accStroke)
            }
        }
        pj.px(la, lo, a)
        c.drawCircle(a.x.toFloat(), a.y.toFloat(), 8f * density, if (stale) dotStale else dot)
        c.drawCircle(a.x.toFloat(), a.y.toFloat(), 8f * density, ring)
    }
}

/**
 * Import-area selection: either a frame showing the visible area that will be used, or a
 * user-drawn polygon (tap to add corners).
 */
class SelectionOverlay(private val density: Float) : Overlay() {
    var points: List<LatLon> = emptyList()
    var showFrame = false
    var tapToAdd: ((LatLon) -> Unit)? = null
    val frameInsetPx: Float get() = 28f * density

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; color = 0x301565C0 }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 2.5f * density; color = 0xFF1565C0.toInt()
    }
    private val frame = Paint(stroke).apply {
        pathEffect = DashPathEffect(floatArrayOf(10f * density, 6f * density), 0f)
    }
    private val vertex = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; color = 0xFF1565C0.toInt() }
    private val a = Point()
    private val path = Path()

    override fun draw(c: Canvas, mapView: MapView, shadow: Boolean) {
        if (shadow) return
        if (showFrame) {
            val i = frameInsetPx
            c.drawRect(i, i, mapView.width - i, mapView.height - i, frame)
        }
        if (points.isEmpty()) return
        val pj = mapView.projection
        path.reset()
        points.forEachIndexed { idx, p ->
            pj.px(p.lat, p.lon, a)
            if (idx == 0) path.moveTo(a.x.toFloat(), a.y.toFloat()) else path.lineTo(a.x.toFloat(), a.y.toFloat())
        }
        if (points.size >= 3) {
            path.close()
            c.drawPath(path, fill)
        }
        c.drawPath(path, stroke)
        for (p in points) {
            pj.px(p.lat, p.lon, a)
            c.drawCircle(a.x.toFloat(), a.y.toFloat(), 5f * density, vertex)
        }
    }

    override fun onSingleTapConfirmed(e: MotionEvent, mapView: MapView): Boolean {
        val cb = tapToAdd ?: return false
        val gp = mapView.projection.fromPixels(e.x.toInt(), e.y.toInt())
        cb(LatLon(gp.latitude, gp.longitude))
        return true
    }
}
