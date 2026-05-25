package dev.spatialfin.baselineprofile

import android.content.Intent
import android.net.Uri
import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@LargeTest
class BaselineProfileGenerator {
    @get:Rule val rule = BaselineProfileRule()

    @Test
    fun generate() =
        rule.collect(
            packageName = TARGET_PACKAGE,
            includeInStartupProfile = true,
        ) {
            pressHome()
            startActivityAndWait()
            device.waitForIdle()
            device.swipe(
                device.displayWidth / 2,
                device.displayHeight * 3 / 4,
                device.displayWidth / 2,
                device.displayHeight / 4,
                10,
            )
            device.waitForIdle()

            if (canLaunchTvPlayerShell()) {
                startActivityAndWait(tvPlayerShellIntent())
                device.waitForIdle()
            }
        }
}

internal const val TARGET_PACKAGE = "dev.spatialfin"

internal fun tvPlayerShellIntent() =
    Intent(
            Intent.ACTION_VIEW,
            Uri.parse("spatialfin://play?id=00000000-0000-0000-0000-000000000001&kind=Movie"),
        )
        .setPackage(TARGET_PACKAGE)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

internal fun canLaunchTvPlayerShell(): Boolean =
    InstrumentationRegistry.getInstrumentation()
        .targetContext
        .packageManager
        .resolveActivity(tvPlayerShellIntent(), 0) != null
