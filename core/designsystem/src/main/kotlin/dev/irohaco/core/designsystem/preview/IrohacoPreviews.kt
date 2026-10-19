package dev.irohaco.core.designsystem.preview

import android.content.res.Configuration
import androidx.compose.ui.tooling.preview.Preview

/** DESIGN.md §8: the multipreview every designsystem component and screen must carry. */
@Preview(name = "Light", uiMode = Configuration.UI_MODE_NIGHT_NO)
@Preview(name = "Dark", uiMode = Configuration.UI_MODE_NIGHT_YES)
@Preview(name = "Large font", uiMode = Configuration.UI_MODE_NIGHT_NO, fontScale = 2f)
annotation class IrohacoPreviews
