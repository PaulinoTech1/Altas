package com.example.atlas

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.ViewModelProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.example.atlas.acquisition.bluetooth.AndroidBleScanner
import com.example.atlas.acquisition.bluetooth.AndroidGattClient
import com.example.atlas.acquisition.bluetooth.BluetoothViewModel
import com.example.atlas.acquisition.wifi.AndroidWifiSurveyRepository
import com.example.atlas.acquisition.wifi.WifiViewModel
import com.example.atlas.acquisition.cellular.AndroidCellularRepository
import com.example.atlas.acquisition.cellular.CellularViewModel
import com.example.atlas.acquisition.nfc.AndroidNfcReader
import com.example.atlas.acquisition.nfc.NfcViewModel
import com.example.atlas.data.AndroidCapabilityRepository
import com.example.atlas.permissions.RadioPermissionManager
import com.example.atlas.ui.AtlasApp
import com.example.atlas.ui.DashboardViewModel
import com.example.atlas.ui.theme.AtlasTheme
import com.example.atlas.telemetry.TelemetryRepository
import com.example.atlas.telemetry.TelemetryViewModel

class MainActivity : ComponentActivity() {
    private lateinit var permissions: RadioPermissionManager
    private lateinit var dashboard: DashboardViewModel
    private lateinit var bluetooth: BluetoothViewModel
    private lateinit var wifiSurvey: WifiViewModel
    private lateinit var cellular: CellularViewModel
    private lateinit var nfc: NfcViewModel
    private lateinit var telemetry: TelemetryViewModel
    private var exportRedacted = true
    private val exportDocument = registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        telemetry.export(uri, exportRedacted)
    }
    private var blePermissionsMissing by mutableStateOf(emptyList<String>())
    private var wifiPermissionsMissing by mutableStateOf(emptyList<String>())
    private var cellularPermissionsMissing by mutableStateOf(emptyList<String>())
    private val permissionRequest = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        blePermissionsMissing = permissions.missingForBleScan()
        wifiPermissionsMissing = permissions.missingForWifiSurvey()
        cellularPermissionsMissing = permissions.missingForCellular()
        dashboard.refresh()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        exportRedacted = savedInstanceState?.getBoolean("exportRedacted", true) ?: true
        permissions = RadioPermissionManager(applicationContext)
        dashboard = ViewModelProvider(this, DashboardViewModel.Factory(
            AndroidCapabilityRepository(applicationContext, permissions)
        ))[DashboardViewModel::class.java]
        bluetooth = ViewModelProvider(this, BluetoothViewModel.Factory(
            AndroidBleScanner(applicationContext, permissions), AndroidGattClient(applicationContext)
        ))[BluetoothViewModel::class.java]
        wifiSurvey = ViewModelProvider(this, WifiViewModel.Factory(
            AndroidWifiSurveyRepository(applicationContext, permissions)
        ))[WifiViewModel::class.java]
        cellular = ViewModelProvider(this, CellularViewModel.Factory(
            AndroidCellularRepository(applicationContext, permissions)
        ))[CellularViewModel::class.java]
        nfc = ViewModelProvider(this, NfcViewModel.Factory(AndroidNfcReader(applicationContext)))[NfcViewModel::class.java]
        telemetry = ViewModelProvider(this, TelemetryViewModel.Factory(TelemetryRepository.get(applicationContext)))[TelemetryViewModel::class.java]
        enableEdgeToEdge()
        setContent {
            AtlasTheme {
                AtlasApp(dashboard, bluetooth, wifiSurvey, cellular, nfc, telemetry, blePermissionsMissing, wifiPermissionsMissing, cellularPermissionsMissing,
                    onExport = { redact ->
                        exportRedacted = redact
                        try { exportDocument.launch("atlas-rf-${System.currentTimeMillis()}.json") }
                        catch (_: android.content.ActivityNotFoundException) { telemetry.exportUnavailable() }
                    },
                    onCellularPermissions = {
                        val missing = permissions.missingForCellular()
                        if (missing.isNotEmpty()) permissionRequest.launch(missing.toTypedArray())
                        cellularPermissionsMissing = permissions.missingForCellular()
                    },
                    onWifiPermissions = {
                        val missing = permissions.missingForWifiSurvey()
                        if (missing.isNotEmpty()) permissionRequest.launch(missing.toTypedArray())
                        wifiPermissionsMissing = permissions.missingForWifiSurvey()
                    },
                    onBlePermissions = {
                        val missing = permissions.missingForBleScan()
                        if (missing.isNotEmpty()) permissionRequest.launch(missing.toTypedArray())
                        blePermissionsMissing = permissions.missingForBleScan()
                    }, onRequestPermission = { type ->
                    val missing = permissions.missingForState(type)
                    if (missing.isNotEmpty()) permissionRequest.launch(missing.toTypedArray())
                    else dashboard.refresh()
                }, onAppSettings = {
                    startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.fromParts("package", packageName, null)))
                })
            }
        }
    }
    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("exportRedacted", exportRedacted)
        super.onSaveInstanceState(outState)
    }
    override fun onResume() {
        super.onResume()
        if (::permissions.isInitialized) blePermissionsMissing = permissions.missingForBleScan()
        if (::permissions.isInitialized) wifiPermissionsMissing = permissions.missingForWifiSurvey()
        if (::permissions.isInitialized) cellularPermissionsMissing = permissions.missingForCellular()
        if (::dashboard.isInitialized) dashboard.refresh()
    }

    override fun onStop() {
        if (::bluetooth.isInitialized) bluetooth.stopAll()
        if (::wifiSurvey.isInitialized) wifiSurvey.stop("Stopped — app inactive")
        if (::cellular.isInitialized) cellular.stop("Stopped — app inactive")
        if (::nfc.isInitialized) nfc.stop("Stopped — app inactive")
        if (::dashboard.isInitialized) dashboard.pause()
        super.onStop()
    }
}
