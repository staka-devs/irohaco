package dev.irohaco.core.designsystem.component

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.runtime.Composable

/** The modal bottom sheet (DESIGN §3: details on Compact). M3's ModalBottomSheet is still experimental, so the opt-in stays here. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun IrohacoBottomSheet(onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, content = content)
}
