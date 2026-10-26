package com.example.thermohammer.ui.components

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.thermohammer.engine.ExportJob
import com.example.thermohammer.engine.ExportStage
import com.example.thermohammer.ui.theme.Forge
import com.example.thermohammer.ThermoHammerTheme
import com.example.thermohammer.ui.theme.uxColor
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream

/**
 * UX Test 6's export dialog, drawn at a landscape phone's stage size with typical numbers — an emulator has no 4K
 * encoder, so a run there never shows it mid-export. The picture lands in the app's external files dir
 * (export_dialog.png).
 */
@RunWith(AndroidJUnit4::class)
class ExportDialogRenderTest {

    @get:Rule val rule = createComposeRule()

    @Test
    fun exportDialogMidExport() {
        val queue = listOf(
            ExportStage.Item(ExportJob.AVC_4K, ExportStage.State.DONE, 152.4, 41.3),
            ExportStage.Item(ExportJob.HEVC_4K, ExportStage.State.ACTIVE),
            ExportStage.Item(ExportJob.AV1_4K, ExportStage.State.PENDING),
            ExportStage.Item(ExportJob.HEVC_8K, ExportStage.State.PENDING))
        val encoding = ExportStage(ExportJob.HEVC_4K, "c2.qti.hevc.encoder", true, 302, 2.31, 139.6, "ENCODING", queue)
        val checking = encoding.copy(framesDone = 480, elapsedS = 3.52, step = "CHECKING QUALITY")
        rule.setContent {
            ThermoHammerTheme {
                Column(Modifier.background(Forge.bg)) {
                    for (st in listOf(encoding, checking)) {
                        Box(Modifier.size(860.dp, 400.dp).background(Forge.bg)) {
                            ExportDialog(st, uxColor(com.example.thermohammer.engine.UxTest.EXPORT),
                                Modifier.align(Alignment.Center).padding(horizontal = 24.dp, vertical = 64.dp))
                        }
                    }
                }
            }
        }
        val bmp: Bitmap = rule.onRoot().captureToImage().asAndroidBitmap()
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        FileOutputStream(File(app.getExternalFilesDir(null), "export_dialog.png")).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
