package com.example.atlas.permissions

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import com.example.atlas.model.PermissionState
import com.example.atlas.model.RadioType

/** Phase 1 policy only. Add scan/location permissions beside their future call sites. */
class RadioPermissionManager(private val context: Context) {
    fun requiredForState(type: RadioType): List<String> = statePermissions(type)

    fun missingForState(type: RadioType): List<String> = requiredForState(type).filter {
        ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
    }

    fun state(type: RadioType): PermissionState = permissionState(
        requiredForState(type), missingForState(type)
    )

    companion object {
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
