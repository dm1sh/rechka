package ru.dm1sh.rechka

import android.app.Application
import com.google.android.material.color.DynamicColors
import ru.dm1sh.rechka.model.GigaAmModel
import ru.dm1sh.rechka.util.AppLog
import ru.dm1sh.rechka.util.Prefs

class LiteApp : Application() {
    override fun onCreate() {
        super.onCreate()
        DynamicColors.applyToActivitiesIfAvailable(this)
        Prefs.migrateTo108PauseDefault(this)

        // Only private model files are accessed after the optional SAF copy.
        // Full APKs install/replace their bundled model here, asynchronously;
        // light APKs validate and retain the existing working copy.
        GigaAmModel.initialize(this) { message -> AppLog.log(this, message) }
    }
}
