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
import androidx.compose.ui.platform.LocalWindowInfo
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
    NativeBackHandler(state.dictationOpen || state.menuOpen || state.openFile != null || state.screen != Screen.Chat) {
        when {
            state.dictationOpen -> actions.cancelDictation()
            state.menuOpen -> actions.menu(false)
            state.openFile != null -> actions.closeFile()
            state.screen in listOf(Screen.Appearance, Screen.Models, Screen.Notifications, Screen.Personalization, Screen.Queue) -> actions.navigate(Screen.Profile)
            else -> actions.navigate(Screen.Chat)
        }
    }
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(state.menuOpen) { if (state.menuOpen) { focus.clearFocus(); keyboard?.hide() } }
    CompositionLocalProvider(LocalReducedMotion provides reducedMotion) {
    Box(Modifier.fillMaxSize().background(palette.background)) {
        if (!state.connected) {
            Wallpaper(state.preferences, state.customWallpaper, state.screen, state.connected)
            ConnectionScreen(state, actions)
        } else RevealDrawer(state.menuOpen, actions::menu, state.openFile == null && !state.dictationOpen,
            menu = { DrawerContent(state, actions) }) {
            Box(Modifier.fillMaxSize()) {
                Wallpaper(state.preferences, state.customWallpaper, state.screen, state.connected)
                Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).imePadding()) {
                    TopBar(state, actions)
                    Box(Modifier.weight(1f)) {
                        when (state.screen) {
                            Screen.Chat -> ChatSurface(state, actions, reducedMotion)
                            Screen.Search -> SearchScreen(state, actions)
                            Screen.Files -> FilesScreen(state, actions)
                            Screen.Skills -> SkillsScreen(state, actions)
                            Screen.Agents -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Column(horizontalAlignment = Alignment.CenterHorizontally) { BotMark(); Spacer(Modifier.height(12.dp)); Text(tr("agents.empty"), color = palette.muted) } }
                            else -> SettingsScreen(state, actions)
                        }
                    }
                }
            }
        }
        val notice = state.error ?: state.notice
        if (notice != null) {
            LaunchedEffect(notice) { if (state.error == null) { kotlinx.coroutines.delay(2300); actions.dismissNotice() } }
            Row(Modifier.align(Alignment.TopCenter).windowInsetsPadding(WindowInsets.statusBars).padding(top = 62.dp, start = 16.dp, end = 16.dp).clip(RoundedCornerShape(18.dp)).background(palette.surface).border(1.dp, palette.line, RoundedCornerShape(18.dp)).padding(start = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                Glyph(if (state.error != null) "close" else "check", modifier = Modifier.size(17.dp), tint = if (state.error != null) MaterialTheme.colorScheme.error else palette.accent)
                Text(tr(notice, "model" to state.noticeDetail.orEmpty()), fontSize = 12.sp, color = palette.ink, modifier = Modifier.weight(1f).padding(horizontal = 10.dp))
                IconAction("close", tr("action.close"), actions::dismissNotice)
            }
        }
        ModelPicker(state, actions)
        if (state.scheduling) SchedulePopup(actions)
        if (state.previewTitle != null) BotDialog(actions::closePreview) {
                Column(Modifier.padding(18.dp).heightIn(max = 600.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(state.previewTitle, modifier = Modifier.weight(1f), maxLines = 2)
                        IconAction("close", tr("action.close"), actions::closePreview)
                    }
                    val bitmap = remember(state.previewBytes) { state.previewBytes?.let(::decodeImage) }
                    if (bitmap != null) Image(bitmap, state.previewTitle, Modifier.fillMaxWidth().weight(1f, false), contentScale = ContentScale.Fit)
                    else if (state.loading) LoadingDots(Modifier.padding(20.dp))
                    else Text(state.previewText.ifBlank { tr("files.previewUnavailable") }, modifier = Modifier.verticalScroll(rememberScrollState()))
                }
        }
        if (state.offlineQuestion) BotDialog({ actions.offlineDelivery(false) }) {
            Text(tr("queue.offlineTitle"), fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = palette.ink)
            Text(tr("queue.offlineBody"), modifier = Modifier.padding(vertical = 16.dp), color = palette.muted)
            ActionButton(tr("queue.allow"), { actions.offlineDelivery(true) }, Modifier.fillMaxWidth(), primary = true)
            QuietAction(tr("queue.keepDraft"), { actions.offlineDelivery(false) }, Modifier.fillMaxWidth())
        }
    }
    }
}

@Composable
private fun ConnectionScreen(state: AppState, actions: AppActions) {
    val p = LocalPalette.current
    BoxWithConstraints(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).heightIn(min = maxHeight).padding(28.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
            BotMark(Modifier.size(76.dp))
            Spacer(Modifier.height(22.dp))
            Text(tr("connect.title"), fontSize = 30.sp, lineHeight = 35.sp, fontWeight = FontWeight.Medium, color = p.ink)
            Spacer(Modifier.height(16.dp))
            Text(tr("connect.body"), color = p.muted, lineHeight = 23.sp)
            Spacer(Modifier.height(30.dp))
            ActionButton(tr(if (state.connecting) "connect.connecting" else "connect.scan"), actions::connect, Modifier.fillMaxWidth().heightIn(min = 54.dp), primary = true, enabled = !state.connecting, icon = "phone")
            Spacer(Modifier.height(16.dp))
            Text(state.baseUrl.removePrefix("https://"), color = p.muted, fontSize = 12.sp)
        }
    }
}

@Composable
private fun TopBar(state: AppState, actions: AppActions) {
    val p = LocalPalette.current
    val window = LocalWindowInfo.current.containerSize
    Row(Modifier.fillMaxWidth().height(if (window.width > window.height) 48.dp else 58.dp).padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        if (state.openFile != null) IconAction("back", tr("nav.back"), actions::closeFile)
        else if (state.screen !in listOf(Screen.Chat, Screen.Files, Screen.Agents, Screen.Search, Screen.Profile)) IconAction("back", tr("nav.back"), { actions.navigate(if (state.screen == Screen.Skills) Screen.Chat else Screen.Profile) })
        else IconAction("menu", tr("nav.menu"), { actions.menu(true) })
        Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
            if (state.screen == Screen.Chat) {
                val model = state.models.firstOrNull { it.id == state.selectedModel }
                Row(Modifier.clip(RoundedCornerShape(14.dp)).clickable { actions.modelPicker(true) }.heightIn(min = 44.dp).padding(horizontal = 9.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    BrandMark(model?.brand ?: "", Modifier.size(18.dp))
                    Text(model?.label ?: state.selectedModel.takeIf { it.isNotBlank() }?.substringAfterLast('/') ?: tr(if (state.modelsLoading) "model.loading" else "model.choose"), color = p.ink, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, false))
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
    Screen.Skills -> "input.skills"
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
private fun SearchScreen(state: AppState, actions: AppActions) {
    val p = LocalPalette.current
    Column(Modifier.fillMaxSize().padding(18.dp)) {
        BotField(state.search, actions::search, modifier = Modifier.fillMaxWidth(), placeholder = tr("chat.searchPlaceholder"), icon = "search")
        Spacer(Modifier.height(12.dp))
        val results = state.conversations.filter { it.title.contains(state.search, true) }
        LazyColumn {
            items(results, key = { it.id }) { chat -> Text(chat.title, color = p.ink, modifier = Modifier.fillMaxWidth().clickable { actions.openChat(chat.id) }.padding(vertical = 18.dp), maxLines = 2) }
            if (results.isEmpty()) item { Text(tr("chat.noResults"), color = p.muted, modifier = Modifier.padding(vertical = 20.dp)) }
        }
    }
}
