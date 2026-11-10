package app.blindspot.ui

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LifecycleStartEffect

object Perms {
    fun hasFine(ctx: Context) = granted(ctx, Manifest.permission.ACCESS_FINE_LOCATION)
    fun hasCoarse(ctx: Context) = granted(ctx, Manifest.permission.ACCESS_COARSE_LOCATION)
    fun hasNotifications(ctx: Context) =
        Build.VERSION.SDK_INT < 33 || granted(ctx, Manifest.permission.POST_NOTIFICATIONS)

    fun gpsEnabled(ctx: Context): Boolean = try {
        ctx.getSystemService(LocationManager::class.java).isProviderEnabled(LocationManager.GPS_PROVIDER)
    } catch (_: Exception) { false }

    fun hasGpsHardware(ctx: Context) = ctx.packageManager.hasSystemFeature(PackageManager.FEATURE_LOCATION_GPS)

    fun ignoringBatteryOptimizations(ctx: Context): Boolean =
        ctx.getSystemService(PowerManager::class.java)?.isIgnoringBatteryOptimizations(ctx.packageName) ?: true

    fun startPermissions(): Array<String> = buildList {
        add(Manifest.permission.ACCESS_FINE_LOCATION)
        add(Manifest.permission.ACCESS_COARSE_LOCATION)
        if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
    }.toTypedArray()

    fun openAppSettings(ctx: Context) = ctx.startActivity(
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${ctx.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    )

    fun openLocationSettings(ctx: Context) = ctx.startActivity(
        Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    )

    @SuppressLint("BatteryLife")
    fun requestIgnoreBatteryOptimizations(ctx: Context) {
        try {
            ctx.startActivity(
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${ctx.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        } catch (_: Exception) {
            ctx.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    private fun granted(ctx: Context, p: String) =
        ContextCompat.checkSelfPermission(ctx, p) == PackageManager.PERMISSION_GRANTED
}

data class UserFix(val lat: Double, val lon: Double, val accuracyM: Float?)

/**
 * GPS position for the map while monitoring is off and the app is visible. Uses the same
 * on-device GPS provider; nothing is stored or transmitted.
 */
@SuppressLint("MissingPermission")
@Composable
fun rememberPreviewLocation(ctx: Context, enabled: Boolean, hasPermission: Boolean): MutableState<UserFix?> {
    val state = remember { mutableStateOf<UserFix?>(null) }
    LifecycleStartEffect(enabled, hasPermission) {
        val lm = ctx.getSystemService(LocationManager::class.java)
        val listener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                state.value = UserFix(location.latitude, location.longitude, if (location.hasAccuracy()) location.accuracy else null)
            }
            override fun onProviderEnabled(provider: String) = Unit
            override fun onProviderDisabled(provider: String) = Unit
            @Deprecated("Deprecated in Java")
            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit
        }
        var registered = false
        if (enabled && hasPermission) {
            try {
                if (state.value == null) {
                    lm.getLastKnownLocation(LocationManager.GPS_PROVIDER)?.let {
                        if (System.currentTimeMillis() - it.time < 10 * 60_000L) {
                            state.value = UserFix(it.latitude, it.longitude, if (it.hasAccuracy()) it.accuracy else null)
                        }
                    }
                }
                lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 3000L, 0f, listener, Looper.getMainLooper())
                registered = true
            } catch (_: Exception) {
            }
        }
        onStopOrDispose { if (registered) lm.removeUpdates(listener) }
    }
    return state
}
