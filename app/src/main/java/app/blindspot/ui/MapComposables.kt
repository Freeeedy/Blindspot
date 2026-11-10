package app.blindspot.ui

import android.graphics.Color
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import app.blindspot.data.TileMode
import app.blindspot.geo.CameraIndex
import org.osmdroid.mapsforge.MapsForgeTileProvider
import org.osmdroid.mapsforge.MapsForgeTileSource
import org.osmdroid.tileprovider.MapTileProviderBasic
import org.osmdroid.tileprovider.util.SimpleRegisterReceiver
import java.io.File
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.ScaleBarOverlay

/**
 * osmdroid map hosted in Compose. Camera monitoring never depends on this map: if tiles are
 * unavailable (offline, or tiles turned off) the overlays still draw on a plain background.
 */
@Composable
fun OsmMap(
    modifier: Modifier,
    tileMode: TileMode,
    vectorMaps: List<File>,
    initialIndex: CameraIndex,
    initialCenter: GeoPoint?,
    setup: (MapView) -> Unit,
    update: (MapView) -> Unit,
) {
    val ctx = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val mapView = remember {
        MapView(ctx).apply {
            setTileSource(TileSourceFactory.MAPNIK)
            setMultiTouchControls(true)
            zoomController.setVisibility(CustomZoomButtonsController.Visibility.NEVER)
            isTilesScaledToDpi = true
            minZoomLevel = 3.0
            maxZoomLevel = 19.5
            isVerticalMapRepetitionEnabled = false
            setScrollableAreaLimitLatitude(MapView.getTileSystem().maxLatitude, MapView.getTileSystem().minLatitude, 0)
            overlayManager.tilesOverlay.loadingBackgroundColor = Color.rgb(232, 234, 237)
            overlayManager.tilesOverlay.loadingLineColor = Color.rgb(214, 217, 222)
            overlays.add(ScaleBarOverlay(this).apply {
                setAlignBottom(true)
                setScaleBarOffset((resources.displayMetrics.density * 12).toInt(), (resources.displayMetrics.density * 150).toInt())
            })
            if (initialCenter != null) {
                controller.setZoom(17.0)
                controller.setCenter(initialCenter)
            } else {
                controller.setZoom(5.0)
                controller.setCenter(GeoPoint(50.0, 15.0))
            }
            setup(this)
        }
    }

    // Frame the cameras once they are available (the database loads asynchronously).
    val framed = remember { booleanArrayOf(initialCenter != null) }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            mapView.onDetach()
        }
    }

    AndroidView(
        modifier = modifier,
        factory = { mapView },
        update = { m ->
            applyTileMode(m, tileMode, vectorMaps)
            if (!framed[0]) initialIndex.bounds()?.let { bb ->
                framed[0] = true
                fitBounds(m, bb)
            }
            update(m)
            m.invalidate()
        },
    )
}

private fun styleTiles(m: MapView) {
    m.overlayManager.tilesOverlay.loadingBackgroundColor = Color.rgb(232, 234, 237)
    m.overlayManager.tilesOverlay.loadingLineColor = Color.rgb(214, 217, 222)
}

/** Switches between the online/cached raster tile provider and the offline Mapsforge vector provider. */
private fun applyProvider(m: MapView, vector: List<File>?) {
    val key = vector?.joinToString("|") { it.name + ":" + it.length() } ?: "raster"
    if (m.getTag(R_TAG) == key) return
    if (m.getTag(R_TAG) == null && vector == null) {
        m.setTag(R_TAG, key)   // the MapView's own provider is already the raster one
        return
    }
    val old = m.tileProvider
    if (vector != null) {
        val source = MapsForgeTileSource.createFromFiles(vector.toTypedArray())
        m.tileProvider = MapsForgeTileProvider(SimpleRegisterReceiver(m.context), source, null)
    } else {
        m.tileProvider = MapTileProviderBasic(m.context, TileSourceFactory.MAPNIK)
    }
    if (m.getTag(R_TAG) != null) old.detach()
    m.setTag(R_TAG, key)
    // Mapsforge already renders for the screen density; scaling its tiles again doubles everything.
    m.isTilesScaledToDpi = vector == null
    styleTiles(m)
}

private val R_TAG = app.blindspot.R.id.map_provider_tag

private fun applyTileMode(m: MapView, mode: TileMode, vectorMaps: List<File>) {
    applyProvider(m, if (mode == TileMode.OFFLINE_VECTOR && vectorMaps.isNotEmpty()) vectorMaps else null)
    when (mode) {
        TileMode.OFFLINE_VECTOR -> {
            m.setUseDataConnection(false)
            m.overlayManager.tilesOverlay.isEnabled = vectorMaps.isNotEmpty()
            if (vectorMaps.isEmpty()) m.setBackgroundColor(Color.rgb(236, 238, 241))
        }
        TileMode.ONLINE -> {
            m.setUseDataConnection(true)
            m.overlayManager.tilesOverlay.isEnabled = true
        }
        TileMode.CACHED_ONLY -> {
            m.setUseDataConnection(false)
            m.overlayManager.tilesOverlay.isEnabled = true
        }
        TileMode.OFF -> {
            m.setUseDataConnection(false)
            m.overlayManager.tilesOverlay.isEnabled = false
            m.setBackgroundColor(Color.rgb(236, 238, 241))
        }
    }
}

private fun fitBounds(m: MapView, bb: DoubleArray) {
    val fit = {
        if (bb[2] - bb[0] > 0.0005 || bb[3] - bb[1] > 0.0005) {
            m.zoomToBoundingBox(BoundingBox(bb[2], bb[3], bb[0], bb[1]).increaseByScale(1.15f), false)
        } else {
            m.controller.setZoom(17.0)
            m.controller.setCenter(GeoPoint((bb[0] + bb[2]) / 2, (bb[1] + bb[3]) / 2))
        }
    }
    if (m.width > 0 && m.height > 0) fit() else m.addOnFirstLayoutListener { _, _, _, _, _ -> fit() }
}
