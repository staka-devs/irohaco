package dev.irohaco.infrastructure.ai

import android.content.Context
import android.os.BatteryManager
import android.os.PowerManager
import dev.irohaco.core.contracts.PauseReason
import dev.irohaco.core.contracts.ResourcePolicy

/**
 * Pauses indexing when the device runs hot or the battery is low and not charging (AC-012,
 * ARCHITECTURE §10.1). Asked between items, so the check must stay cheap.
 */
class AndroidResourcePolicy(context: Context) : ResourcePolicy {
    private val power = context.getSystemService(PowerManager::class.java)
    private val battery = context.getSystemService(BatteryManager::class.java)

    override fun hold(): PauseReason? = when {
        power.currentThermalStatus >= PowerManager.THERMAL_STATUS_SEVERE -> PauseReason.THERMAL
        !battery.isCharging && (power.isPowerSaveMode || battery.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) <= LOW_PERCENT) ->
            PauseReason.BATTERY
        else -> null
    }

    private companion object {
        const val LOW_PERCENT = 15
    }
}
