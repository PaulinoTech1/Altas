package com.example.atlas

import android.content.pm.PackageManager
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.atlas.data.AndroidCapabilityRepository
import com.example.atlas.model.Availability
import com.example.atlas.model.CollectionState
import com.example.atlas.model.PermissionState
import com.example.atlas.model.RadioState
import com.example.atlas.model.RadioType
import com.example.atlas.permissions.RadioPermissionManager
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CapabilityInstrumentedTest {
    @Test fun snapshotMatchesPhysicalPlatformAndDoesNotProduceMeasurements() = runBlocking {
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        val snapshot = AndroidCapabilityRepository(app, RadioPermissionManager(app)).snapshot()
        assertEquals(RadioType.entries.toSet(), snapshot.map { it.type }.toSet())
        val features = mapOf(
            RadioType.BLUETOOTH to PackageManager.FEATURE_BLUETOOTH,
            RadioType.NFC to PackageManager.FEATURE_NFC,
            RadioType.WIFI to PackageManager.FEATURE_WIFI,
            RadioType.CELLULAR to PackageManager.FEATURE_TELEPHONY
        )
        snapshot.forEach { radio ->
            val present = app.packageManager.hasSystemFeature(features.getValue(radio.type)) ||
                (radio.type == RadioType.BLUETOOTH && app.packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE))
            assertEquals(if (present) Availability.AVAILABLE else Availability.UNAVAILABLE, radio.availability)
            assertNotNull(radio.checkedAtEpochMillis)
            assertNull(radio.lastMeasurementEpochMillis)
            assertNull("${radio.type}: ${radio.error}", radio.error)
            assertEquals(CollectionState.NOT_IMPLEMENTED, radio.collection)
            if (radio.type == RadioType.BLUETOOTH && radio.permission == PermissionState.REQUIRED) {
                assertEquals(RadioState.PERMISSION_REQUIRED, radio.state)
            }
            if (radio.type == RadioType.CELLULAR && present) assertEquals(RadioState.NOT_QUERIED, radio.state)
            Log.i("AtlasValidation", "${radio.type}: hardware=${radio.availability}, permission=${radio.permission}, state=${radio.state}; ${radio.detail}")
        }
    }

    @Test fun manifestOnlyAuthorizesImplementedOperations() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        @Suppress("DEPRECATION")
        val info = app.packageManager.getPackageInfo(app.packageName, PackageManager.GET_PERMISSIONS)
        assertEquals(setOf(
            "android.permission.BLUETOOTH_CONNECT", "android.permission.NFC",
            "android.permission.BLUETOOTH_SCAN", "android.permission.ACCESS_COARSE_LOCATION",
            "android.permission.ACCESS_FINE_LOCATION",
            "android.permission.ACCESS_WIFI_STATE", "android.permission.ACCESS_NETWORK_STATE",
            "android.permission.CHANGE_WIFI_STATE",
            "android.permission.READ_PHONE_STATE",
            "com.example.atlas.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION"
        ), info.requestedPermissions.orEmpty().toSet())
        assertEquals(0, app.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_ALLOW_BACKUP)
    }
}
