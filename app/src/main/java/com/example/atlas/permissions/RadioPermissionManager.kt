package com.example.atlas.permissions

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import com.example.atlas.model.PermissionState
import com.example.atlas.model.RadioType

/** Operation-specific policy; no launch-time or background permission requests. */
class RadioPermissionManager(private val context: Context) {
    fun missingForWifiSurvey(): List<String> = wifiSurveyPermissions.filter {
        ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
    }
    fun missingForBleScan(): List<String> = bleScanPermissions.filter {
        ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
    }
    fun requiredForState(type: RadioType): List<String> = statePermissions(type)

    fun missingForState(type: RadioType): List<String> = requiredForState(type).filter {
        ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
    }

    fun state(type: RadioType): PermissionState = permissionState(
        requiredForState(type), missingForState(type)
    )

    companion object {
        val wifiSurveyPermissions = listOf(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION)
        val bleScanPermissions = listOf(
            Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION
        )
        fun statePermissions(type: RadioType): List<String> = when (type) {
            RadioType.BLUETOOTH -> listOf(Manifest.permission.BLUETOOTH_CONNECT)
            else -> emptyList()
        }

        fun permissionState(required: List<String>, missing: List<String>): PermissionState = when {
            required.isEmpty() -> PermissionState.NOT_REQUIRED
            missing.isNotEmpty() -> PermissionState.REQUIRED
            else -> PermissionState.GRANTED
        }
    }
}
