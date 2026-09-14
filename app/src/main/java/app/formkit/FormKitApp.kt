package app.formkit

import android.app.Application
import android.os.StrictMode
import app.formkit.core.di.ApplicationScope
import app.formkit.core.di.IoDispatcher
import app.formkit.core.storage.CacheJanitor
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltAndroidApp
class FormKitApp : Application() {

    @Inject lateinit var cacheJanitor: CacheJanitor
    @Inject @ApplicationScope lateinit var appScope: CoroutineScope
    @Inject @IoDispatcher lateinit var ioDispatcher: CoroutineDispatcher

    override fun onCreate() {
        if (BuildConfig.DEBUG) enableStrictMode()
        super.onCreate()

        // Only stale leftovers go: anything recent may belong to a tool screen that is being
        // restored after process death.
        appScope.launch(ioDispatcher) { cacheJanitor.pruneOlderThan(CacheJanitor.STALE_AFTER_MILLIS) }
    }

    private fun enableStrictMode() {
        StrictMode.setThreadPolicy(
            StrictMode.ThreadPolicy.Builder().detectAll().penaltyLog().build(),
        )
        StrictMode.setVmPolicy(
            StrictMode.VmPolicy.Builder().detectAll().penaltyLog().build(),
        )
    }
}
