package me.rerere.rikkahub.ui.components.avatar.animated

import android.app.Application
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.junit.Test
import java.io.File

@RunWith(RobolectricTestRunner::class)
@org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = Application::class)
class BaselineTraceTest {
    @Test
    fun frameMetricsForCompare() {
        ChannelScenarios.loadClips()
        val dir = System.getProperty("lastchat.renderDir")!!
        val sb = StringBuilder("scenario,seed," + ChannelScenarios.FRAME_KEYS.joinToString(",") + "\n")
        for (sc in ChannelScenarios.NAMES) for (seed in listOf(3, 11, 19, 5, 7, 23, 29, 31)) {
            val tr = ChannelScenarios.run(sc, seed)
            val m = ChannelMetrics.compute(tr)
            if (seed == 3) { File(dir).mkdirs(); File(dir, "trace_${sc}_$seed.csv").writeText(tr.csv()) }
            sb.append("$sc,$seed," + ChannelScenarios.FRAME_KEYS.joinToString(",") { "%.4f".format(m[it]) } + "\n")
        }
        File(dir).mkdirs(); File(dir, "frame_metrics.csv").writeText(sb.toString())
    }
}
