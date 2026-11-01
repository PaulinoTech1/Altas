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
import com.example.atlas.data.AndroidCapabilityRepository
import com.example.atlas.permissions.RadioPermissionManager
import com.example.atlas.ui.AtlasApp
import com.example.atlas.ui.DashboardViewModel
import com.example.atlas.ui.theme.AtlasTheme

class MainActivity : ComponentActivity() {
    private lateinit var permissions: RadioPermissionManager
    private lateinit var dashboard: DashboardViewModel
    private val permissionRequest = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { dashboard.refresh() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        permissions = RadioPermissionManager(applicationContext)
        dashboard = ViewModelProvider(this, DashboardViewModel.Factory(
            AndroidCapabilityRepository(applicationContext, permissions)
        ))[DashboardViewModel::class.java]
        enableEdgeToEdge()
        setContent {
            AtlasTheme {
                AtlasApp(dashboard, onRequestPermission = { type ->
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
    override fun onResume() {
        super.onResume()
        if (::dashboard.isInitialized) dashboard.refresh()
    }

    override fun onStop() {
        if (::dashboard.isInitialized) dashboard.pause()
        super.onStop()
    }
}
