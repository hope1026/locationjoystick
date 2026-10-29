package com.locationjoystick.core.location

import android.location.Location
import android.util.Log
import com.google.android.gms.location.FusedLocationProviderClient
import kotlinx.coroutines.tasks.await

/** Official Play services adapter. Availability is checked by the owning service. */
internal class GoogleFusedLocationPort(
    private val client: FusedLocationProviderClient,
) : FusedLocationPort {
    override suspend fun setMockMode(enabled: Boolean) {
        try {
            client.setMockMode(enabled).await()
        } catch (error: SecurityException) {
            Log.e(TAG, "Permission denied changing Fused mock mode", error)
            throw error
        }
    }

    override suspend fun setMockLocation(location: Location) {
        try {
            client.setMockLocation(location).await()
        } catch (error: SecurityException) {
            Log.e(TAG, "Permission denied sending Fused mock location", error)
            throw error
        }
    }

    private companion object {
        const val TAG = "GoogleFusedLocationPort"
    }
}
