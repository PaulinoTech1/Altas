package com.example.atlas.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.example.atlas.R

@Composable
fun PermissionGuidance() {
    Text(stringResource(R.string.permission_settings_help), style = MaterialTheme.typography.bodySmall)
}
