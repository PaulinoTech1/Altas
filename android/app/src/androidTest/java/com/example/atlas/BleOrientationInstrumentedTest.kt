package com.example.atlas

import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.LocationManager
import android.util.Log
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.atlas.acquisition.bluetooth.AndroidBleScanner
import com.example.atlas.acquisition.bluetooth.BleScanConfig
import com.example.atlas.acquisition.bluetooth.BleScanMode
import com.example.atlas.permissions.RadioPermissionManager
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.first
import org.junit.Assert.assertNotNull
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Opt-in physical scan: requires a nearby advertiser and does not connect or change phone settings. */
@RunWith(AndroidJUnit4::class)
class BleOrientationInstrumentedTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun gameRotationSensorDeliversEventsAndCanStop() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        val manager = app.getSystemService(SensorManager::class.java)
        val sensor = manager.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)
        assumeTrue(sensor != null)
        val latch = CountDownLatch(1)
        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) { if (event.values.size >= 3) latch.countDown() }
            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
        }
        try {
            assumeTrue(manager.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_UI))
            org.junit.Assert.assertTrue("No rotation-vector callback within 3 seconds", latch.await(3, TimeUnit.SECONDS))
        } finally { manager.unregisterListener(listener) }
    }

    @Test fun liveBleEventHasFreshPhoneOrientation() { runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("atlas.orientationHardware") == "true")
        compose.waitForIdle() // Keep Atlas visible while the unfiltered BLE scan runs.
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        assumeTrue(RadioPermissionManager(app).missingForBleScan().isEmpty())
        assumeTrue(app.getSystemService(LocationManager::class.java).isLocationEnabled)
        assumeTrue(app.getSystemService(SensorManager::class.java).getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR) != null)
        var observations = 0
        val paired = withTimeoutOrNull(23_000) {
            AndroidBleScanner(app, RadioPermissionManager(app)).scan(BleScanConfig(BleScanMode.LOW_LATENCY, 20)).first {
                observations++
                it.phoneOrientation != null && it.rssiDbm != null
            }
        }
        assertNotNull("No paired sample among $observations BLE advertisements; feature NOT VERIFIED", paired)
        Log.i("AtlasBleOrientation", "paired_ble_sample: observations=$observations, rssi_dbm=${paired!!.rssiDbm}, " +
            "orientation_age_ms=${(paired.sourceElapsedNanos ?: paired.receivedElapsedNanos).minus(paired.phoneOrientation!!.elapsedNanos) / 1_000_000}; no device identifiers logged")
    } }
}
