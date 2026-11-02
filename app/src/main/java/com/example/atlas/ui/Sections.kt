package com.example.atlas.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.OutlinedCard
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * Layout rhythm for the single atlas-content LazyColumn: items are 16 dp apart; siblings inside one item are 8 dp
 * apart (never 0 dp); label/value pairs use [Pairs] at 4 dp.
 */
@Composable
fun ItemColumn(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp), content = content)
}

/** Tight 4 dp grouping for label/value lines such as "Hardware: … / State: …". */
@Composable
fun Pairs(content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp), content = content)
}

/** A bordered section for blocks that used to be bare text on the background (access, export, retention). */
@Composable
fun SectionCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    OutlinedCard(modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp), content = content)
    }
}
