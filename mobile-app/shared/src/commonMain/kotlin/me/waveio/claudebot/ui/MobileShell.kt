package me.waveio.claudebot.ui

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.layout.ContentScale
import me.waveio.claudebot.state.*

@Composable
fun MobileShell(state: AppState, actions: AppActions, reducedMotion: Boolean) {
    val palette = LocalPalette.current
    NativeBackHandler(state.menuOpen || state.openFile != null || state.screen != Screen.Chat) {
        when {
            state.menuOpen -> actions.menu(false)
            state.openFile != null -> actions.closeFile()
            state.screen in listOf(Screen.Appearance, Screen.Models, Screen.Notifications, Screen.Personalization, Screen.Queue) -> actions.navigate(Screen.Profile)
            else -> actions.navigate(Screen.Chat)
        }
    }
    val drawer = rememberDrawerState(DrawerValue.Closed)
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(state.menuOpen) {
        if (state.menuOpen) { focus.clearFocus(); keyboard?.hide(); drawer.open() }
        else drawer.close()
    }
    val currentOpen by rememberUpdatedState(state.menuOpen)
    LaunchedEffect(drawer) {
        snapshotFlow { drawer.targetValue }.collect { value ->
            val open = value == DrawerValue.Open
            if (open != currentOpen) actions.menu(open)
        }
    }
    Box(Modifier.fillMaxSize().background(palette.background)) {
        Wallpaper(state)
        if (!state.connected) ConnectionScreen(state, actions)
        else ModalNavigationDrawer(
            drawerState = drawer,
            gesturesEnabled = state.openFile == null && !state.dictationOpen,
            scrimColor = androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.38f),
            drawerContent = {
                ModalDrawerSheet(Modifier.fillMaxWidth(0.65f), drawerContainerColor = palette.surface, drawerShape = RoundedCornerShape(topEnd = 22.dp, bottomEnd = 22.dp)) {
                    DrawerContent(state, actions)
                }
            },
        ) {
            Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).imePadding()) {
                TopBar(state, actions)
                Box(Modifier.weight(1f)) {
                    when (state.screen) {
                        Screen.Chat -> ChatSurface(state, actions, reducedMotion)
                        Screen.Search -> SearchScreen(state, actions)
                        Screen.Files -> FilesScreen(state, actions)
                        Screen.Agents -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Column(horizontalAlignment = Alignment.CenterHorizontally) { BotMark(); Spacer(Modifier.height(12.dp)); Text(tr("agents.empty"), color = palette.muted) } }
                        else -> SettingsScreen(state, actions)
                    }
                }
            }
        }
        val notice = state.error ?: state.notice
        if (notice != null) {
            Snackbar(modifier = Modifier.align(Alignment.BottomCenter).windowInsetsPadding(WindowInsets.safeDrawing).padding(12.dp), action = { TextButton(onClick = actions::dismissNotice) { Text(tr("action.close")) } }) { Text(tr(notice, "model" to state.noticeDetail.orEmpty())) }
        }
        if (state.modelPickerOpen) ModelPicker(state, actions)
        if (state.dictationOpen) DictationPopup(state, actions)
        if (state.scheduling) SchedulePopup(actions)
        if (state.previewTitle != null) Dialog(onDismissRequest = actions::closePreview) {
            GlassCard {
                Column(Modifier.padding(18.dp).heightIn(max = 600.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(state.previewTitle, modifier = Modifier.weight(1f), maxLines = 2)
                        IconAction("close", tr("action.close"), actions::closePreview)
                    }
                    val bitmap = remember(state.previewBytes) { state.previewBytes?.let(::decodeImage) }
                    if (bitmap != null) Image(bitmap, state.previewTitle, Modifier.fillMaxWidth().weight(1f, false), contentScale = ContentScale.Fit)
                    else if (state.loading) CircularProgressIndicator(Modifier.padding(20.dp).size(24.dp))
                    else Text(state.previewText.ifBlank { tr("files.previewUnavailable") }, modifier = Modifier.verticalScroll(rememberScrollState()))
                }
            }
        }
        if (state.offlineQuestion) AlertDialog(
            onDismissRequest = { actions.offlineDelivery(false) },
            title = { Text(tr("queue.offlineTitle")) }, text = { Text(tr("queue.offlineBody")) },
            confirmButton = { TextButton(onClick = { actions.offlineDelivery(true) }) { Text(tr("queue.allow")) } },
            dismissButton = { TextButton(onClick = { actions.offlineDelivery(false) }) { Text(tr("queue.keepDraft")) } },
        )
    }
}

@Composable
private fun ConnectionScreen(state: AppState, actions: AppActions) {
    val p = LocalPalette.current
    Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).padding(28.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        BotMark(Modifier.size(76.dp))
        Spacer(Modifier.height(22.dp))
        Text(tr("connect.title"), fontSize = 30.sp, lineHeight = 35.sp, fontWeight = FontWeight.Medium, color = p.ink)
        Spacer(Modifier.height(16.dp))
        Text(tr("connect.body"), color = p.muted, lineHeight = 23.sp)
        Spacer(Modifier.height(30.dp))
        Button(onClick = actions::connect, enabled = !state.connecting, modifier = Modifier.fillMaxWidth().height(54.dp), shape = RoundedCornerShape(18.dp)) {
            if (state.connecting) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
            else Glyph("phone", tint = MaterialTheme.colorScheme.onPrimary)
            Spacer(Modifier.width(10.dp)); Text(tr(if (state.connecting) "connect.connecting" else "connect.scan"))
        }
        Spacer(Modifier.height(16.dp))
        Text(state.baseUrl.removePrefix("https://"), color = p.muted, fontSize = 12.sp)
    }
}

@Composable
private fun TopBar(state: AppState, actions: AppActions) {
    val p = LocalPalette.current
    Row(Modifier.fillMaxWidth().height(58.dp).padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        if (state.openFile != null) IconAction("back", tr("nav.back"), actions::closeFile)
        else if (state.screen !in listOf(Screen.Chat, Screen.Files, Screen.Agents, Screen.Search, Screen.Profile)) IconAction("back", tr("nav.back"), { actions.navigate(Screen.Profile) })
        else IconAction("menu", tr("nav.menu"), { actions.menu(true) })
        Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
            if (state.screen == Screen.Chat) {
                val model = state.models.firstOrNull { it.id == state.selectedModel }
                Row(Modifier.clip(RoundedCornerShape(14.dp)).clickable { actions.modelPicker(true) }.heightIn(min = 44.dp).padding(horizontal = 9.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    BrandMark(model?.brand ?: "", Modifier.size(18.dp))
                    Text(model?.label ?: tr("model.choose"), color = p.ink, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, false))
                    Glyph("down", modifier = Modifier.size(13.dp), tint = p.muted)
                }
            } else Text(state.openFile?.substringAfterLast('/') ?: screenTitle(state.screen), fontSize = 16.sp, fontWeight = FontWeight.Medium, color = p.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        IconAction("new", tr("nav.new"), actions::newChat)
    }
}

@Composable
fun screenTitle(screen: Screen): String = tr(when (screen) {
    Screen.Chat -> "nav.chats"
    Screen.Search -> "nav.search"
    Screen.Files -> "nav.files"
    Screen.Agents -> "nav.agents"
    Screen.Profile -> "nav.profile"
    Screen.Appearance -> "profile.appearance"
    Screen.Models -> "profile.models"
    Screen.Notifications -> "profile.notifications"
    Screen.Personalization -> "profile.personalization"
    Screen.Queue -> "queue.title"
})

@Composable
private fun DrawerContent(state: AppState, actions: AppActions) {
    val p = LocalPalette.current
    Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).padding(horizontal = 14.dp)) {
        Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) { BotMark(Modifier.size(34.dp)); Spacer(Modifier.width(7.dp)); Text(tr("app.name"), fontSize = 17.sp, fontWeight = FontWeight.Medium) }
        DrawerLink("new", tr("nav.new"), actions::newChat)
        DrawerLink("search", tr("nav.search")) { actions.navigate(Screen.Search) }
        DrawerLink("bot", tr("nav.agents")) { actions.navigate(Screen.Agents) }
        DrawerLink("folder", tr("nav.files")) { actions.navigate(Screen.Files) }
        Text(tr("nav.chats"), fontSize = 12.sp, color = p.muted, modifier = Modifier.padding(start = 10.dp, top = 23.dp, bottom = 8.dp))
        LazyColumn(Modifier.weight(1f)) {
            items(state.conversations, key = { it.id }) { chat ->
                Text(chat.title, modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(if (chat.id == state.sessionId) p.line.copy(alpha = 0.6f) else androidx.compose.ui.graphics.Color.Transparent).clickable { actions.openChat(chat.id) }.padding(10.dp, 13.dp), color = p.ink, maxLines = 2, overflow = TextOverflow.Ellipsis, fontSize = 14.sp)
            }
            if (state.conversations.isEmpty()) item { Text(tr("chat.historyEmpty"), fontSize = 12.sp, color = p.muted, modifier = Modifier.padding(10.dp)) }
        }
        HorizontalDivider(color = p.line)
        DrawerLink("settings", tr("nav.profile")) { actions.navigate(Screen.Profile) }
    }
}

@Composable
private fun DrawerLink(icon: String, text: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable(onClick = onClick).heightIn(min = 48.dp).padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Glyph(icon, modifier = Modifier.size(20.dp)); Spacer(Modifier.width(11.dp)); Text(text, fontSize = 14.sp)
    }
}

@Composable
private fun ModelPicker(state: AppState, actions: AppActions) {
    val p = LocalPalette.current
    var query by remember { mutableStateOf("") }
    val density = LocalDensity.current
    val top = WindowInsets.safeDrawing.getTop(density) + with(density) { 57.dp.roundToPx() }
    Popup(alignment = Alignment.TopCenter, offset = IntOffset(0, top), onDismissRequest = { actions.modelPicker(false) }, properties = PopupProperties(focusable = true)) {
        GlassCard(Modifier.padding(horizontal = 16.dp).widthIn(max = 400.dp).fillMaxWidth()) {
            Column(Modifier.padding(14.dp).heightIn(max = 460.dp)) {
                OutlinedTextField(query, { query = it }, modifier = Modifier.fillMaxWidth(), placeholder = { Text(tr("model.search"), fontSize = 13.sp) }, leadingIcon = { Glyph("search", modifier = Modifier.size(17.dp)) }, singleLine = true, shape = RoundedCornerShape(14.dp))
                Spacer(Modifier.height(10.dp))
                LazyColumn(Modifier.weight(1f, false)) {
                    items(state.models.filter { it.label.contains(query, true) || it.provider.contains(query, true) }, key = { it.id }) { model ->
                        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable(enabled = model.available) { actions.selectModel(model.id) }.padding(vertical = 11.dp, horizontal = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                            BrandMark(model.brand)
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f)) {
                                Text(model.label, color = if (model.available) p.ink else p.muted, fontSize = 14.sp)
                                Text(if (model.available) model.provider else tr("model.unavailable"), color = p.muted, fontSize = 10.sp)
                            }
                            if (model.id == state.selectedModel) Glyph("check", modifier = Modifier.size(16.dp), tint = p.accent)
                        }
                    }
                    if (state.models.isEmpty()) item { Text(tr("model.empty"), color = p.muted, modifier = Modifier.padding(16.dp)) }
                }
                HorizontalDivider(color = p.line)
                Text(tr("model.effort"), fontSize = 12.sp, color = p.muted, modifier = Modifier.padding(top = 12.dp, bottom = 5.dp))
                val efforts = state.models.firstOrNull { it.id == state.selectedModel }?.efforts.orEmpty().ifEmpty { listOf("none") }
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    efforts.forEach { effort -> FilterChip(selected = state.effort == effort, onClick = { actions.selectEffort(effort) }, label = { Text(tr("model.$effort"), fontSize = 12.sp) }) }
                }
            }
        }
    }
}

@Composable
private fun SearchScreen(state: AppState, actions: AppActions) {
    val p = LocalPalette.current
    Column(Modifier.fillMaxSize().padding(18.dp)) {
        OutlinedTextField(state.search, actions::search, modifier = Modifier.fillMaxWidth(), singleLine = true, placeholder = { Text(tr("chat.searchPlaceholder")) }, leadingIcon = { Glyph("search") }, shape = RoundedCornerShape(16.dp))
        Spacer(Modifier.height(12.dp))
        val results = state.conversations.filter { it.title.contains(state.search, true) }
        LazyColumn {
            items(results, key = { it.id }) { chat -> Text(chat.title, color = p.ink, modifier = Modifier.fillMaxWidth().clickable { actions.openChat(chat.id) }.padding(vertical = 18.dp), maxLines = 2) }
            if (results.isEmpty()) item { Text(tr("chat.noResults"), color = p.muted, modifier = Modifier.padding(vertical = 20.dp)) }
        }
    }
}
