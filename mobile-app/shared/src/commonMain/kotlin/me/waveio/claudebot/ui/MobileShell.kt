package me.waveio.claudebot.ui

import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.layout.ContentScale
import me.waveio.claudebot.state.*

@Composable
fun MobileShell(state: AppState, actions: AppActions, reducedMotion: Boolean) {
    val palette = LocalPalette.current
    NativeBackHandler(state.sendModeOpen || state.attachmentPickerOpen || state.dictationOpen || state.menuOpen || state.openFile != null || state.screen != Screen.Chat) {
        when {
            state.sendModeOpen -> actions.sendModes(false)
            state.attachmentPickerOpen -> actions.attachments(false)
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
    CompositionLocalProvider(LocalReducedMotion provides reducedMotion, LocalIndication provides QuietIndication) {
    Box(Modifier.fillMaxSize().background(palette.background)) {
        if (!state.connected) {
            Wallpaper(state.preferences, state.customWallpaper, state.screen, state.connected)
            ConnectionScreen(state, actions)
        } else RevealDrawer(state.menuOpen, actions::menu, state.openFile == null && !state.dictationOpen,
            menu = { ConversationDrawer(state, actions) }) {
            Box(Modifier.fillMaxSize()) {
                Wallpaper(state.preferences, state.customWallpaper, state.screen, state.connected)
                Column(Modifier.fillMaxSize().windowInsetsPadding(
                    if (state.screen == Screen.Chat) WindowInsets.ime else WindowInsets.safeDrawing
                ).imePadding()) {
                    if (state.screen != Screen.Chat) TopBar(state, actions)
                    Box(Modifier.weight(1f)) {
                        AnimatedContent(state.screen, transitionSpec = {
                            fadeIn(tween(if (reducedMotion) 0 else 160)) togetherWith fadeOut(tween(if (reducedMotion) 0 else 90))
                        }, label = "screen") { screen ->
                        EnterMotion(true) { transition -> Box(transition.fillMaxSize()) {
                        when (screen) {
                            Screen.Chat -> ChatSurface(state, actions, reducedMotion, topBarHeight()) { TopBar(state, actions) }
                            Screen.Search -> SearchScreen(state, actions)
                            Screen.Files -> FilesScreen(state, actions)
                            Screen.Skills -> SkillsScreen(state, actions)
                            Screen.Agents -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Column(horizontalAlignment = Alignment.CenterHorizontally) { BotMark(); Spacer(Modifier.height(12.dp)); Text(tr("agents.empty"), color = palette.muted) } }
                            else -> SettingsScreen(state, actions)
                        }
                        } }
                        }
                    }
                }
            }
        }
        val notice = state.error ?: state.notice
        LaunchedEffect(notice) {
            val shown = notice ?: return@LaunchedEffect
            kotlinx.coroutines.delay(2600)
            if (state.error == shown || state.notice == shown) actions.dismissNotice()
        }
        AnimatedVisibility(
            visible = notice != null,
            modifier = Modifier.align(Alignment.TopCenter),
            enter = fadeIn(tween(180)) + scaleIn(tween(220), initialScale = .92f),
            exit = fadeOut(tween(180)) + scaleOut(tween(180), targetScale = .94f),
        ) {
            notice?.let { shown ->
                val isError = state.error != null
                val shape = RoundedCornerShape(30.dp)
                Row(Modifier.windowInsetsPadding(WindowInsets.statusBars).padding(top = 62.dp, start = 16.dp, end = 16.dp)
                    .clip(shape).background(palette.surface.copy(alpha = .96f)).border(1.dp, palette.line, shape)
                    .padding(start = 8.dp, end = 4.dp, top = 7.dp, bottom = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(32.dp).clip(CircleShape).background(if (isError) MaterialTheme.colorScheme.error.copy(alpha = .12f) else palette.accent.copy(alpha = .12f)), contentAlignment = Alignment.Center) {
                        Glyph(if (isError) "close" else "check", modifier = Modifier.size(16.dp), tint = if (isError) MaterialTheme.colorScheme.error else palette.accent)
                    }
                    Text(tr(shown, "model" to state.noticeDetail.orEmpty()), fontSize = 12.sp, color = palette.ink,
                        modifier = Modifier.weight(1f).padding(horizontal = 10.dp), maxLines = 2, overflow = TextOverflow.Ellipsis)
                    IconAction("close", tr("action.close"), actions::dismissNotice, Modifier.size(40.dp))
                }
            }
        }
        ModelPicker(state, actions)
        AttachmentMenu(state, actions)
        if (state.scheduling) SchedulePopup(actions)
        if (state.previewTitle != null) MediaPreview(state, actions)
        if (state.offlineQuestion) BotDialog({ actions.offlineDelivery(false) }) {
            Text(tr("queue.offlineTitle"), fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = palette.ink)
            Text(tr("queue.offlineBody"), modifier = Modifier.padding(vertical = 16.dp), color = palette.muted)
            ActionButton(tr("queue.allow"), { actions.offlineDelivery(true) }, Modifier.fillMaxWidth(), primary = true)
            QuietAction(tr("queue.keepDraft"), { actions.offlineDelivery(false) }, Modifier.fillMaxWidth())
        }
        state.update?.let { update -> BotDialog({ if (!update.mandatory) actions.dismissUpdate() }) {
            Text(tr("update.title"), fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = palette.ink)
            Text(tr("update.available", "version" to update.versionName), color = palette.muted, modifier = Modifier.padding(top = 6.dp, bottom = 14.dp))
            Text(tr("update.changelog"), color = palette.ink, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
            Column(Modifier.padding(vertical = 10.dp).verticalScroll(rememberScrollState())) {
                update.changelog.forEach { item -> Text("• $item", color = palette.muted, fontSize = 13.sp, modifier = Modifier.padding(vertical = 3.dp)) }
                if (update.changelog.isEmpty()) Text(tr("update.noChanges"), color = palette.muted, fontSize = 13.sp)
            }
            if (state.updateError != null) Text(tr(state.updateError), color = MaterialTheme.colorScheme.error, fontSize = 12.sp, modifier = Modifier.padding(bottom = 8.dp))
            ActionButton(
                tr(if (state.updateInstalling) "update.downloading" else "update.install"),
                actions::installUpdate,
                Modifier.fillMaxWidth(),
                primary = true,
                enabled = !state.updateInstalling,
                icon = if (state.updateInstalling) "time" else "download",
            )
            if (!update.mandatory) QuietAction(tr("update.close"), actions::dismissUpdate, Modifier.fillMaxWidth())
        } }
    }
    }
}

@Composable
private fun ConnectionScreen(state: AppState, actions: AppActions) {
    val p = LocalPalette.current
    val keyboard = LocalSoftwareKeyboardController.current
    val focus = LocalFocusManager.current
    NativeBackHandler(state.codePairingOpen) { actions.codePairing(false) }
    val submit = {
        focus.clearFocus()
        keyboard?.hide()
        actions.connectWithCode()
    }
    BoxWithConstraints(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).imePadding()) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).heightIn(min = maxHeight).padding(28.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
            BotMark(Modifier.size(76.dp))
            Spacer(Modifier.height(22.dp))
            Text(tr("connect.title"), fontSize = 30.sp, lineHeight = 35.sp, fontWeight = FontWeight.Medium, color = p.ink)
            Spacer(Modifier.height(16.dp))
            Text(tr(if (state.codePairingOpen) "connect.codeHelp" else "connect.body"), color = p.muted, lineHeight = 23.sp)
            Spacer(Modifier.height(30.dp))
            if (state.codePairingOpen) {
                BotField(state.pairingCode, actions::pairingCode, Modifier.fillMaxWidth(),
                    label = tr("connect.codeLabel"), placeholder = tr("connect.codePlaceholder"),
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters,
                        autoCorrectEnabled = false, keyboardType = KeyboardType.Ascii, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { submit() }))
                Spacer(Modifier.height(14.dp))
                BotField(state.pairingServer, actions::pairingServer, Modifier.fillMaxWidth(),
                    label = tr("connect.server"),
                    keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { submit() }))
                state.pairingError?.let { key ->
                    Text(tr(key), color = MaterialTheme.colorScheme.error, fontSize = 13.sp,
                        modifier = Modifier.fillMaxWidth().padding(top = 12.dp).semantics { liveRegion = LiveRegionMode.Polite })
                }
                Spacer(Modifier.height(20.dp))
                ActionButton(tr(if (state.connecting) "connect.connecting" else "connect.submit"), submit,
                    Modifier.fillMaxWidth().heightIn(min = 54.dp), primary = true,
                    enabled = !state.connecting && state.pairingCode.isNotBlank() && state.pairingServer.isNotBlank())
                QuietAction(tr("connect.cancel"), { actions.codePairing(false) }, Modifier.fillMaxWidth().padding(top = 6.dp))
            } else {
                ActionButton(tr(if (state.connecting) "connect.connecting" else "connect.scan"), actions::connect, Modifier.fillMaxWidth().heightIn(min = 54.dp), primary = true, enabled = !state.connecting, icon = "phone")
                QuietAction(tr("connect.enterCode"), { actions.codePairing(true) }, Modifier.fillMaxWidth().padding(top = 6.dp), enabled = !state.connecting)
            }
            Spacer(Modifier.height(16.dp))
            Text(state.baseUrl.removePrefix("https://"), color = p.muted, fontSize = 12.sp)
        }
    }
}

@Composable
private fun topBarHeight(): androidx.compose.ui.unit.Dp {
    val window = LocalWindowInfo.current.containerSize
    return if (window.width > window.height) 48.dp else 58.dp
}

@Composable
private fun TopBar(state: AppState, actions: AppActions) {
    val p = LocalPalette.current
    Row(Modifier.fillMaxWidth().height(topBarHeight()).padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        if (state.openFile != null) IconAction("back", tr("nav.back"), actions::closeFile)
        else if (state.screen !in listOf(Screen.Chat, Screen.Files, Screen.Agents, Screen.Search, Screen.Profile)) IconAction("back", tr("nav.back"), { actions.navigate(if (state.screen == Screen.Skills) Screen.Chat else Screen.Profile) })
        else IconAction("menu", tr("nav.menu"), { actions.menu(true) })
        Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
            if (state.screen == Screen.Chat) {
                val model = state.models.firstOrNull { it.id == state.selectedModel }
                Row(Modifier.clip(RoundedCornerShape(14.dp)).clickable { actions.modelPicker(true) }.heightIn(min = 44.dp).padding(horizontal = 9.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    BrandMark(model?.brand ?: "", Modifier.size(18.dp))
                    Text(model?.label ?: state.selectedModel.takeIf { it.isNotBlank() }?.substringAfterLast('/') ?: tr(if (state.modelsLoading) "model.loading" else "model.choose"), color = p.ink, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, false))
                    Glyph("down", modifier = Modifier.size(13.dp).graphicsLayer {
                        rotationZ = if (state.modelPickerOpen) 180f else 0f
                    }, tint = p.muted)
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
