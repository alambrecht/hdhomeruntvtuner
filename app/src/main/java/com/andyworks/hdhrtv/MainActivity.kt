package com.andyworks.hdhrtv

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Button
import androidx.tv.material3.ListItem
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import androidx.tv.material3.darkColorScheme

private const val PREFS = "hdhr"
private const val KEY_BASE_URL = "baseUrl"

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { App() }
    }
}

@Composable
fun App() {
    val prefs = LocalContext.current.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    var reload by remember { mutableIntStateOf(0) }
    var channels by remember { mutableStateOf<List<Channel>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var showSetup by remember { mutableStateOf(false) }
    var playing by rememberSaveable { mutableStateOf<Int?>(null) }
    var lastIndex by rememberSaveable { mutableIntStateOf(0) }

    LaunchedEffect(reload) {
        channels = null
        error = null
        var baseUrl = prefs.getString(KEY_BASE_URL, null)
        if (baseUrl == null) {
            baseUrl = HdhrClient.discoverBaseUrl()
            if (baseUrl != null) prefs.edit().putString(KEY_BASE_URL, baseUrl).apply()
        }
        if (baseUrl == null) {
            error = "Couldn't find an HDHomeRun automatically. Enter its IP address."
            showSetup = true
            return@LaunchedEffect
        }
        try {
            channels = HdhrClient.lineup(baseUrl)
        } catch (e: Exception) {
            error = "Couldn't load channels from $baseUrl (${e.message})"
            showSetup = true
        }
    }

    MaterialTheme(colorScheme = darkColorScheme()) {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            val list = channels
            when {
                showSetup -> SetupScreen(
                    initial = prefs.getString(KEY_BASE_URL, "")?.removePrefix("http://") ?: "",
                    error = error,
                    onSave = { ip ->
                        prefs.edit().putString(KEY_BASE_URL, HdhrClient.normalize(ip)).apply()
                        showSetup = false
                        reload++
                    },
                )

                list == null -> Text("Loading channels…", Modifier.align(Alignment.Center), color = Color.White)

                playing != null -> PlayerScreen(
                    channels = list,
                    index = playing!!,
                    onIndexChange = { playing = it; lastIndex = it },
                    onExit = { playing = null },
                )

                else -> ChannelList(
                    channels = list,
                    focusIndex = lastIndex,
                    onPick = { playing = it; lastIndex = it },
                    onSettings = { error = null; showSetup = true },
                )
            }
        }
    }
}

@Composable
private fun ChannelList(
    channels: List<Channel>,
    focusIndex: Int,
    onPick: (Int) -> Unit,
    onSettings: () -> Unit,
) {
    val listState = rememberLazyListState()
    val focusRequester = remember { FocusRequester() }
    val target = focusIndex.coerceIn(0, (channels.size - 1).coerceAtLeast(0))

    LaunchedEffect(Unit) {
        if (channels.isNotEmpty()) {
            listState.scrollToItem(target)
            runCatching { focusRequester.requestFocus() }
        }
    }

    Column(Modifier.fillMaxSize().padding(horizontal = 48.dp, vertical = 27.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Live TV", style = MaterialTheme.typography.headlineMedium, color = Color.White)
            Spacer(Modifier.weight(1f))
            Button(onClick = onSettings) { Text("Tuner address") }
        }
        Spacer(Modifier.height(12.dp))

        if (channels.isEmpty()) {
            Text(
                "No ATSC 1.0 channels found. Run a channel scan on the HDHomeRun, then reopen the app.",
                color = Color.White,
            )
            return@Column
        }

        LazyColumn(state = listState, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            itemsIndexed(channels) { i, ch ->
                ListItem(
                    selected = false,
                    onClick = { onPick(i) },
                    modifier = if (i == target) Modifier.focusRequester(focusRequester) else Modifier,
                    leadingContent = { Text(ch.number, Modifier.width(72.dp)) },
                    headlineContent = { Text(ch.name) },
                    trailingContent = { if (ch.hd) Text("HD") },
                )
            }
        }
    }
}

@Composable
private fun SetupScreen(initial: String, error: String?, onSave: (String) -> Unit) {
    var text by remember { mutableStateOf(initial) }
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focusRequester.requestFocus() } }

    Column(
        Modifier.fillMaxSize().padding(48.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("HDHomeRun IP address", style = MaterialTheme.typography.headlineSmall, color = Color.White)
        if (error != null) {
            Text(error, color = Color(0xFFFFB4AB), modifier = Modifier.padding(top = 8.dp))
        }
        BasicTextField(
            value = text,
            onValueChange = { text = it },
            singleLine = true,
            textStyle = TextStyle(color = Color.White, fontSize = 24.sp),
            cursorBrush = SolidColor(Color.White),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { if (text.isNotBlank()) onSave(text) }),
            modifier = Modifier
                .padding(top = 24.dp)
                .width(420.dp)
                .focusRequester(focusRequester)
                .border(2.dp, Color.White, MaterialTheme.shapes.small)
                .padding(16.dp),
        )
        Button(
            onClick = { if (text.isNotBlank()) onSave(text) },
            modifier = Modifier.padding(top = 24.dp),
        ) { Text("Save") }
    }
}
