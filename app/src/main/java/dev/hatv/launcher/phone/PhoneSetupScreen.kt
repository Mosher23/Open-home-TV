package dev.hatv.launcher.phone

import android.graphics.Bitmap
import android.os.SystemClock
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.MultiFormatWriter
import dev.hatv.launcher.LauncherViewModel
import dev.hatv.launcher.R
import dev.hatv.launcher.ui.*
import kotlinx.coroutines.delay

@Composable
fun PhoneSetupScreen(model: LauncherViewModel, back: () -> Unit) {
    val state by model.phoneSetup.state.collectAsStateWithLifecycle()
    DisposableEffect(Unit) {
        model.phoneSetup.start()
        onDispose { model.phoneSetup.stop() }
    }
    val bitmap =
        remember(state.url) {
            state.url
                .takeIf { it.isNotBlank() }
                ?.let { url ->
                    val matrix =
                        MultiFormatWriter()
                            .encode(
                                url,
                                BarcodeFormat.QR_CODE,
                                512,
                                512,
                                mapOf(EncodeHintType.MARGIN to 4),
                            )
                    Bitmap.createBitmap(512, 512, Bitmap.Config.RGB_565).apply {
                        setPixels(
                            IntArray(512 * 512) { index ->
                                if (matrix[index % 512, index / 512]) android.graphics.Color.BLACK
                                else android.graphics.Color.WHITE
                            },
                            0,
                            512,
                            0,
                            0,
                            512,
                            512,
                        )
                    }
                }
        }
    val allowFocus = remember { FocusRequester() }
    var focused by remember(state.phase) { mutableStateOf(false) }
    var now by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    LaunchedEffect(state.expiresAt) {
        while (state.expiresAt > 0) {
            now = SystemClock.elapsedRealtime()
            delay(1000)
        }
    }
    Column(
        Modifier.fillMaxSize().padding(6.dp),
        verticalArrangement = Arrangement.spacedBy(15.dp),
    ) {
        Label(stringResource(R.string.phone_setup), size = 25, bold = true)
        Row(
            Modifier.fillMaxWidth()
                .weight(1f)
                .background(Panel, RoundedCornerShape(16.dp))
                .padding(20.dp),
            horizontalArrangement = Arrangement.spacedBy(22.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            bitmap
                ?.takeIf { state.phase in listOf("waiting", "approval") }
                ?.let {
                    Image(
                        it.asImageBitmap(),
                        stringResource(R.string.phone_qr),
                        Modifier.size(220.dp),
                        filterQuality = FilterQuality.None,
                    )
                }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(13.dp)) {
                when (state.phase) {
                    "waiting" -> {
                        Label(stringResource(R.string.phone_scan), size = 21, bold = true)
                        Label(stringResource(R.string.phone_same_network), size = 14, color = Muted)
                        Label(state.url.substringBefore('#'), size = 13, color = Accent)
                        Label(stringResource(R.string.phone_private), size = 12, color = Muted)
                    }
                    "approval" -> {
                        Label(stringResource(R.string.phone_verify), size = 19, bold = true)
                        Label(state.code, size = 36, color = Accent, bold = true)
                        Action(
                            stringResource(R.string.phone_allow),
                            { model.phoneSetup.approve() },
                            Modifier.focusRequester(allowFocus).onGloballyPositioned {
                                if (!focused) {
                                    focused = true
                                    allowFocus.requestFocus()
                                }
                            },
                            selected = true,
                        )
                    }
                    "paired",
                    "applying" -> {
                        Label(
                            stringResource(R.string.phone_paired),
                            size = 22,
                            bold = true,
                            color = Green,
                        )
                        Label(stringResource(R.string.phone_continue), size = 14)
                        Label(stringResource(R.string.phone_apply_hint), size = 13, color = Muted)
                    }
                    "closed" -> {
                        Label(stringResource(R.string.phone_closed), size = 20)
                        Action(stringResource(R.string.phone_restart), { model.phoneSetup.start() })
                    }
                    "expired" -> {
                        Label(stringResource(R.string.phone_expired), size = 20)
                        Action(stringResource(R.string.phone_restart), { model.phoneSetup.start() })
                    }
                    "error" -> {
                        Label(stringResource(R.string.phone_network_error), size = 17)
                        Action(stringResource(R.string.phone_restart), { model.phoneSetup.start() })
                    }
                    "done" -> Label(stringResource(R.string.phone_done), size = 22, color = Green)
                    else -> Label(stringResource(R.string.phone_starting), size = 17)
                }
                if (state.expiresAt > 0)
                    Label(
                        stringResource(
                            R.string.phone_remaining,
                            ((state.expiresAt - now).coerceAtLeast(0) / 1000).toInt(),
                        ),
                        size = 11,
                        color = Muted,
                    )
            }
        }
        Action(stringResource(R.string.phone_cancel), back)
    }
}
