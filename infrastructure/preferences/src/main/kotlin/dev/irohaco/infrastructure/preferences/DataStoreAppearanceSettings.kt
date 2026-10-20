package dev.irohaco.infrastructure.preferences

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import dev.irohaco.core.contracts.AppearanceSettings
import dev.irohaco.core.domain.ThemeColor
import dev.irohaco.core.domain.ThemeContrast
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

class DataStoreAppearanceSettings(private val store: DataStore<Preferences>) : AppearanceSettings {
    override val themeColor: Flow<ThemeColor> =
        store.data.map { decode(it[THEME_COLOR]) }.distinctUntilChanged()

    override val themeContrast: Flow<ThemeContrast> =
        store.data.map { decodeContrast(it[THEME_CONTRAST]) }.distinctUntilChanged()

    override suspend fun setThemeColor(color: ThemeColor) {
        store.edit { it[THEME_COLOR] = encode(color) }
    }

    override suspend fun setThemeContrast(contrast: ThemeContrast) {
        store.edit { it[THEME_CONTRAST] = contrast.name }
    }

    internal companion object {
        val THEME_COLOR = stringPreferencesKey("theme_color")
        val THEME_CONTRAST = stringPreferencesKey("theme_contrast")
        private const val DEVICE = "device"

        fun encode(color: ThemeColor): String = when (color) {
            ThemeColor.Device -> DEVICE
            is ThemeColor.Seed -> color.toHex()
        }

        /** Anything unreadable falls back to the default rather than failing the theme. */
        fun decode(value: String?): ThemeColor = value?.let { ThemeColor.Seed.parseHex(it) } ?: ThemeColor.Device

        fun decodeContrast(value: String?): ThemeContrast =
            ThemeContrast.entries.firstOrNull { it.name == value } ?: ThemeContrast.DEVICE
    }
}
