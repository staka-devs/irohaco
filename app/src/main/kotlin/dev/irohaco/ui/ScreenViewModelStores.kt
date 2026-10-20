package dev.irohaco.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner

/**
 * A ViewModelStore per full-screen route (viewer, settings). It lives in the activity's store, so it
 * survives rotation, and a route's store is cleared when that route closes; otherwise every opened
 * viewer would keep its pages and video player until the activity finished.
 */
class ScreenViewModelStores : ViewModel() {
    private val stores = mutableMapOf<String, ViewModelStore>()

    fun owner(route: String): ViewModelStoreOwner {
        val store = stores.getOrPut(route) { ViewModelStore() }
        return object : ViewModelStoreOwner {
            override val viewModelStore = store
        }
    }

    fun clear(route: String) {
        stores.remove(route)?.clear()
    }

    override fun onCleared() {
        stores.values.forEach { it.clear() }
        stores.clear()
    }
}
