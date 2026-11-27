package ru.dm1sh.rechka.ui

import android.os.Bundle
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.color.DynamicColors
import ru.dm1sh.rechka.R
import ru.dm1sh.rechka.ui.onboarding.ModelFragment

/** Reopens just the model step without resetting a completed onboarding. */
class ModelSetupActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        DynamicColors.applyToActivityIfAvailable(this)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_model_setup)

        findViewById<MaterialToolbar>(R.id.modelSetupToolbar).setNavigationOnClickListener { finish() }
        val content = findViewById<android.view.View>(R.id.modelSetupContent)
        ViewCompat.setOnApplyWindowInsetsListener(content) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.navigationBars() or WindowInsetsCompat.Type.displayCutout())
            v.updatePadding(bottom = bars.bottom)
            insets
        }
        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .replace(R.id.modelSetupContent, ModelFragment())
                .commit()
        }
    }
}
