package pl.stapik.common.sample

import android.app.Activity
import android.os.Bundle
import android.widget.ScrollView
import android.widget.TextView
import kotlin.concurrent.thread

/** Runs [SelfTest] on the device and prints the result. No UI beyond that. */
class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val view = TextView(this).apply {
            setPadding(48, 96, 48, 48)
            textSize = 14f
            typeface = android.graphics.Typeface.MONOSPACE
            text = "Running self-test..."
        }
        setContentView(ScrollView(this).apply { addView(view) })
        thread {
            val report = try {
                SelfTest(filesDir.resolve("selftest")).run()
            } catch (e: Throwable) {
                "CRASH: $e"
            }
            runOnUiThread { view.text = report }
        }
    }
}
