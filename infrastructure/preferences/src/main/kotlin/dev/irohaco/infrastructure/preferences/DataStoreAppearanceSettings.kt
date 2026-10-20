package dev.irohaco.infrastructure.preferences

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import dev.irohaco.core.contracts.AppearanceSettings
import dev.irohaco.core.domain.ThemeColor
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

class DataStoreAppearanceSettings(private val store: DataStore<Preferences>) : AppearanceSettings {
    override val themeColor: Flow<ThemeColor> =
        store.data.map { decode(it[THEME_COLOR]) }.distinctUntilChanged()

    override suspend fun setThemeColor(color: ThemeColor) {
        store.edit { it[THEME_COLOR] = encode(color) }
    }

    internal companion object {
        val THEME_COLOR = stringPreferencesKey("theme_color")
        private const val DEVICE = "device"

        fun encode(color: ThemeColor): String = when (color) {
            ThemeColor.Device -> DEVICE
            is ThemeColor.Seed -> "#%06X".format(color.rgb)
        }

        /** Anything unreadable falls back to the default rather than failing the theme. */
        fun decode(value: String?): ThemeColor =
            value?.removePrefix("#")?.takeIf { it.length == 6 }?.toIntOrNull(16)?.let { ThemeColor.Seed(it) } ?: ThemeColor.Device
    }
}
