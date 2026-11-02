package com.example.atlas.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.example.atlas.R

@Composable
fun PermissionGuidance() {
    Explanation(stringResource(R.string.permission_settings_help))
}
