package dev.fogo.dokkantranslate.bubble

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner

/**
 * A ComposeView can only run inside a tree that provides lifecycle,
 * ViewModelStore and SavedStateRegistry owners. A Service has none of
 * those, so an overlay window has to bring its own — otherwise Compose
 * crashes on attach. This supplies the minimum viable set so the bubble's
 * result panel can reuse the same composables as the main screen instead
 * of a parallel View-based renderer.
 */
class OverlayComposeHost(context: Context) :
    LifecycleOwner, ViewModelStoreOwner, SavedStateRegistryOwner {

    private val lifecycleRegistry = LifecycleRegistry(this)
    private val savedStateController = SavedStateRegistryController.create(this)

    override val lifecycle: Lifecycle get() = lifecycleRegistry
    override val viewModelStore = ViewModelStore()
    override val savedStateRegistry: SavedStateRegistry
        get() = savedStateController.savedStateRegistry

    val view: ComposeView = ComposeView(context).also { composeView ->
        savedStateController.performRestore(null)
        lifecycleRegistry.currentState = Lifecycle.State.CREATED
        composeView.setViewTreeLifecycleOwner(this)
        composeView.setViewTreeViewModelStoreOwner(this)
        composeView.setViewTreeSavedStateRegistryOwner(this)
    }

    fun setContent(content: @Composable () -> Unit) {
        view.setContent(content)
    }

    /** Call once the view is attached to the WindowManager. */
    fun onShown() {
        lifecycleRegistry.currentState = Lifecycle.State.RESUMED
    }

    /** Call before removing the view; Compose tears down its composition. */
    fun onRemoved() {
        lifecycleRegistry.currentState = Lifecycle.State.DESTROYED
        viewModelStore.clear()
    }
}
