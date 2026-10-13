package com.andyworks.hdhrtv

import android.content.Context
import android.view.KeyEvent
import androidx.activity.compose.BackHandler
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.ts.DefaultTsPayloadReaderFactory
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import androidx.tv.material3.Text
import kotlinx.coroutines.delay

/**
 * Full-screen live player.
 * D-pad up / CH+ = next channel, D-pad down / CH- = previous, number keys = direct tune,
 * center/OK = show channel info, Back = channel list.
 */
@OptIn(UnstableApi::class)
@Composable
fun PlayerScreen(
    channels: List<Channel>,
    index: Int,
    onIndexChange: (Int) -> Unit,
    onExit: () -> Unit,
) {
    val context = LocalContext.current
    val player = remember { buildPlayer(context) }
    val focusRequester = remember { FocusRequester() }
    val channel = channels[index]

    var error by remember { mutableStateOf<String?>(null) }
    var overlayVisible by remember { mutableStateOf(true) }
    var digits by remember { mutableStateOf("") }

    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onPlayerError(e: PlaybackException) {
                error = "Playback error: ${e.errorCodeName}" +
                    if (e.errorCode == PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS) " (all tuners busy?)" else ""
                overlayVisible = true
            }
        }
        player.addListener(listener)
        onDispose {
            player.removeListener(listener)
            player.release() // closes the HTTP stream, which frees the tuner
        }
    }

    // Tune. The short delay lets rapid CH+/CH- presses skip past channels without opening each one.
    LaunchedEffect(index) {
        error = null
        overlayVisible = true
        delay(250)
        player.setMediaItem(MediaItem.fromUri(channel.url))
        player.prepare()
        player.playWhenReady = true
        delay(3000)
        if (error == null) overlayVisible = false
    }

    // Direct tune after 2 s of no further digits.
    LaunchedEffect(digits) {
        if (digits.isEmpty()) return@LaunchedEffect
        overlayVisible = true
        delay(2000)
        findChannel(channels, digits)?.let(onIndexChange)
        digits = ""
    }

    LaunchedEffect(Unit) { focusRequester.requestFocus() }
    BackHandler { onExit() }

    fun step(delta: Int) = onIndexChange((index + delta + channels.size) % channels.size)

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .focusRequester(focusRequester)
            .focusable()
            .onKeyEvent { e ->
                if (e.type != KeyEventType.KeyDown) return@onKeyEvent false
                when (val code = e.nativeKeyEvent.keyCode) {
                    KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_CHANNEL_UP, KeyEvent.KEYCODE_PAGE_UP -> { step(1); true }
                    KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_CHANNEL_DOWN, KeyEvent.KEYCODE_PAGE_DOWN -> { step(-1); true }
                    KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_INFO -> {
                        overlayVisible = !overlayVisible; true
                    }
                    in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9 -> {
                        if (digits.length < 5) digits += (code - KeyEvent.KEYCODE_0).toString(); true
                    }
                    else -> false
                }
            },
    ) {
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    useController = false
                    isFocusable = false
                    keepScreenOn = true
                    resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                    setShowBuffering(PlayerView.SHOW_BUFFERING_ALWAYS)
                    this.player = player
                }
            },
            modifier = Modifier.fillMaxSize(),
        )

        if (overlayVisible || digits.isNotEmpty()) {
            Column(
                Modifier
                    .align(Alignment.BottomStart)
                    .padding(48.dp)
                    .background(Color(0xCC000000), androidx.compose.foundation.shape.RoundedCornerShape(8.dp))
                    .padding(horizontal = 24.dp, vertical = 16.dp),
            ) {
                if (digits.isNotEmpty()) {
                    Text(digits, color = Color.White, fontSize = 40.sp)
                } else {
                    Text("${channel.number}  ${channel.name}", color = Color.White, fontSize = 32.sp)
                }
                error?.let { Text(it, color = Color(0xFFFFB4AB), fontSize = 18.sp) }
            }
        }
    }
}

/** "61" matches 6.1; "6" matches 6.1 if there's no exact 6. */
private fun findChannel(channels: List<Channel>, digits: String): Int? {
    channels.indexOfFirst { it.number == digits }.takeIf { it >= 0 }?.let { return it }
    channels.indexOfFirst { it.number.replace(".", "") == digits }.takeIf { it >= 0 }?.let { return it }
    return channels.indexOfFirst { it.number.substringBefore('.') == digits }.takeIf { it >= 0 }
}

@OptIn(UnstableApi::class)
private fun buildPlayer(context: Context): ExoPlayer {
    // These TS flags let MPEG-2/H.264 broadcast streams start without waiting for an IDR frame.
    val extractors = DefaultExtractorsFactory().setTsExtractorFlags(
        DefaultTsPayloadReaderFactory.FLAG_ALLOW_NON_IDR_KEYFRAMES or
            DefaultTsPayloadReaderFactory.FLAG_DETECT_ACCESS_UNITS,
    )
    val http = DefaultHttpDataSource.Factory()
        .setConnectTimeoutMs(5000)
        .setReadTimeoutMs(15000)

    // Small buffers so channel changes start quickly.
    val loadControl = DefaultLoadControl.Builder()
        .setBufferDurationsMs(3000, 20000, 1000, 2000)
        .build()

    // AC-3 is passed through automatically when the HDMI sink reports AC-3 support.
    return ExoPlayer.Builder(context)
        .setMediaSourceFactory(ProgressiveMediaSource.Factory(http, extractors))
        .setLoadControl(loadControl)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(C.USAGE_MEDIA)
                .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                .build(),
            true,
        )
        .build()
}
