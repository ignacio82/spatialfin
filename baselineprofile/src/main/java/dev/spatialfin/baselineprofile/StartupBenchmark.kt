package dev.spatialfin.baselineprofile

import androidx.benchmark.macro.BaselineProfileMode
import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.FrameTimingMetric
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.StartupTimingMetric
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@LargeTest
class StartupBenchmark {
    @get:Rule val rule = MacrobenchmarkRule()

    @Test
    fun startupWithoutCompilation() = measureStartup(CompilationMode.None())

    @Test
    fun startupWithBaselineProfile() =
        measureStartup(CompilationMode.Partial(BaselineProfileMode.Require))

    @Test
    fun shellFirstScrollWithBaselineProfile() =
        rule.measureRepeated(
            packageName = TARGET_PACKAGE,
            metrics = listOf(FrameTimingMetric()),
            compilationMode = CompilationMode.Partial(BaselineProfileMode.Require),
            iterations = 5,
            startupMode = StartupMode.COLD,
            setupBlock = { pressHome() },
        ) {
            startActivityAndWait()
            device.swipe(
                device.displayWidth / 2,
                device.displayHeight * 3 / 4,
                device.displayWidth / 2,
                device.displayHeight / 4,
                10,
            )
            device.waitForIdle()
        }

    @Test
    fun tvPlayerShellWithBaselineProfile() {
        assumeTrue("TV player deep link is present only in the tv flavor.", canLaunchTvPlayerShell())
        rule.measureRepeated(
            packageName = TARGET_PACKAGE,
            metrics = listOf(StartupTimingMetric(), FrameTimingMetric()),
            compilationMode = CompilationMode.Partial(BaselineProfileMode.Require),
            iterations = 5,
            startupMode = StartupMode.COLD,
            setupBlock = { pressHome() },
        ) {
            startActivityAndWait(tvPlayerShellIntent())
            device.waitForIdle()
        }
    }

    private fun measureStartup(compilationMode: CompilationMode) =
        rule.measureRepeated(
            packageName = TARGET_PACKAGE,
            metrics = listOf(StartupTimingMetric(), FrameTimingMetric()),
            compilationMode = compilationMode,
            iterations = 5,
            startupMode = StartupMode.COLD,
            setupBlock = { pressHome() },
        ) {
            startActivityAndWait()
            device.waitForIdle()
        }
}
