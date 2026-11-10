package app.blindspot.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.viewmodel.compose.viewModel
import android.content.Intent
import kotlinx.coroutines.flow.MutableStateFlow

object StatusColors {
    val inside = Color(0xFFD32F2F)
    val outside = Color(0xFF2E7D32)
    val warning = Color(0xFFF9A825)
    val off = Color(0xFF78909C)
}

@Composable
fun BlindspotTheme(content: @Composable () -> Unit) {
    val scheme = if (isSystemInDarkTheme()) {
        darkColorScheme(primary = Color(0xFF90CAF9), secondary = Color(0xFFFFB74D))
    } else {
        lightColorScheme(primary = Color(0xFF1F5FBF), secondary = Color(0xFFE65100))
    }
    MaterialTheme(colorScheme = scheme, content = content)
}

private enum class Screen { MAIN, IMPORT, SETTINGS }

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        handleIntent(intent)
        setContent { BlindspotTheme { BlindspotRoot() } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        if (intent?.action == ACTION_START_MONITORING) {
            startRequests.value = true
            intent.action = null
        }
        if (intent?.action == Intent.ACTION_VIEW && intent.data?.scheme == "geo") {
            // geo:lat,lon or geo:0,0?q=lat,lon(label)
            val ssp = intent.data?.schemeSpecificPart.orEmpty()
            val coords = (ssp.substringAfter("q=", "").ifEmpty { ssp.substringBefore('?') })
            val parts = coords.substringBefore('(').split(',')
            val lat = parts.getOrNull(0)?.trim()?.toDoubleOrNull()
            val lon = parts.getOrNull(1)?.trim()?.toDoubleOrNull()
            if (lat != null && lon != null && lat in -90.0..90.0 && lon in -180.0..180.0 && !(lat == 0.0 && lon == 0.0)) {
                centerRequests.value = app.blindspot.geo.LatLon(lat, lon)
            }
            intent.action = null
        }
    }

    companion object {
        const val ACTION_START_MONITORING = "app.blindspot.action.START_MONITORING"
        /** Set when the Quick Settings tile asks the app to start monitoring. */
        val startRequests = MutableStateFlow(false)
        /** Set when a geo: link asks the map to show a place. */
        val centerRequests = MutableStateFlow<app.blindspot.geo.LatLon?>(null)
    }
}

@Composable
private fun BlindspotRoot(vm: AppViewModel = viewModel()) {
    var screen by rememberSaveable { mutableStateOf(Screen.MAIN) }
    BackHandler(enabled = screen != Screen.MAIN) { screen = Screen.MAIN }
    when (screen) {
        Screen.MAIN -> MainScreen(vm, onImport = { screen = Screen.IMPORT }, onSettings = { screen = Screen.SETTINGS })
        Screen.IMPORT -> ImportScreen(vm, onBack = { screen = Screen.MAIN })
        Screen.SETTINGS -> SettingsScreen(vm, onBack = { screen = Screen.MAIN })
    }
}
