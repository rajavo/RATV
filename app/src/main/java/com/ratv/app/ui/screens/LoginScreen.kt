package com.ratv.app.ui.screens

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ratv.app.data.Playlist
import com.ratv.app.data.PlaylistType
import com.ratv.app.data.XtreamClient
import com.ratv.app.ui.LocalRepo
import com.ratv.app.ui.components.CircleIconButton
import com.ratv.app.ui.components.OutlineButton
import com.ratv.app.ui.components.RIcons
import com.ratv.app.ui.components.RatvLogo
import com.ratv.app.ui.components.TvInitialFocus
import com.ratv.app.ui.components.focusRing
import com.ratv.app.ui.components.rememberIsTv
import com.ratv.app.ui.components.rememberIsWide
import com.ratv.app.ui.components.tvClickable
import com.ratv.app.ui.theme.RColors
import com.ratv.app.ui.theme.T
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun LoginScreen(onDone: () -> Unit) {
    val repo = LocalRepo.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val tv = rememberIsTv()
    val wide = rememberIsWide()

    var xtream by rememberSaveable { mutableStateOf(true) }
    var name by rememberSaveable { mutableStateOf("") }
    var server by rememberSaveable { mutableStateOf("") }
    var username by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }
    var showPassword by rememberSaveable { mutableStateOf(false) }
    var m3uUrl by rememberSaveable { mutableStateOf("") }
    var epgUrl by rememberSaveable { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var showHelp by remember { mutableStateOf(false) }
    val firstField = remember { FocusRequester() }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) {
            try {
                context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (e: Exception) {
                // Some providers do not offer persistable permissions; we copy the file on first load anyway.
            }
            m3uUrl = uri.toString()
            if (name.isBlank()) name = "قائمة من الجهاز"
        }
    }

    fun submit() {
        if (loading) return
        error = null
        val playlist = if (xtream) {
            XtreamClient.fromUserInput(name.trim().ifBlank { "اشتراكي" }, server, username, password)
        } else {
            Playlist(
                type = PlaylistType.M3U,
                name = name.trim().ifBlank { "قائمتي" },
                m3uUrl = m3uUrl.trim(),
                epgUrl = epgUrl.trim()
            )
        }
        loading = true
        scope.launch {
            val result = repo.addPlaylist(playlist)
            loading = false
            if (result == null) {
                onDone()
            } else {
                error = result
            }
        }
    }

    fun pickFile() {
        try {
            picker.launch(arrayOf("*/*"))
        } catch (e: ActivityNotFoundException) {
            error = "لا يوجد على هذا الجهاز تطبيق لاختيار الملفات. استخدم رابط القائمة بدلاً من ذلك."
        }
    }

    TvInitialFocus(firstField, key = xtream)

    val intro: @Composable () -> Unit = {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            RatvLogo(22.sp)
            Spacer(Modifier.weight(1f))
            CircleIconButton(RIcons.Help, "مساعدة", { showHelp = true }, tint = RColors.Text2)
        }
        Spacer(Modifier.height(if (wide) 24.dp else 32.dp))
        Text("أضف اشتراكك", style = T.display(28.sp))
        Spacer(Modifier.height(8.dp))
        Text("أدخل البيانات كما وصلتك من مزوّد الخدمة.", style = T.body(15.sp, color = RColors.Text2))
    }

    val modeSwitch: @Composable () -> Unit = {
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(RColors.Surface)
                .border(1.dp, RColors.Line, RoundedCornerShape(16.dp))
                .padding(4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Segment("Xtream Codes", xtream, Modifier.weight(1f)) { xtream = true; error = null }
            Segment("رابط M3U", !xtream, Modifier.weight(1f)) { xtream = false; error = null }
        }
    }

    val fields: @Composable () -> Unit = {
        if (xtream) {
            Field(
                "رابط الخادم", server, { server = it }, "http://server.example:8080",
                ltr = true, keyboard = KeyboardType.Uri, tv = tv, focusRequester = firstField
            )
            Spacer(Modifier.height(14.dp))
            val userField: @Composable (Modifier) -> Unit = { m ->
                Field("اسم المستخدم", username, { username = it }, "username", ltr = true, tv = tv, modifier = m)
            }
            val passwordField: @Composable (Modifier) -> Unit = { m ->
                Field(
                    label = "كلمة المرور",
                    value = password,
                    onChange = { password = it },
                    placeholder = "••••••••",
                    ltr = true,
                    keyboard = KeyboardType.Password,
                    secret = !showPassword,
                    tv = tv,
                    modifier = m,
                    // On TV the remote moves straight down the fields, so the eye button is left out there.
                    trailing = if (tv) null else {
                        {
                            Box(
                                Modifier
                                    .size(40.dp)
                                    .clip(RoundedCornerShape(20.dp))
                                    .tvClickable(RoundedCornerShape(20.dp)) { showPassword = !showPassword },
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    if (showPassword) RIcons.EyeOff else RIcons.Eye,
                                    contentDescription = if (showPassword) "إخفاء كلمة المرور" else "إظهار كلمة المرور",
                                    tint = RColors.Text3,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                    }
                )
            }
            if (tv) {
                // One field per row so the down arrow visits every field in order.
                userField(Modifier.fillMaxWidth())
                Spacer(Modifier.height(14.dp))
                passwordField(Modifier.fillMaxWidth())
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    userField(Modifier.weight(1f))
                    passwordField(Modifier.weight(1f))
                }
            }
            Spacer(Modifier.height(14.dp))
            Field(
                "اسم القائمة (اختياري)", name, { name = it }, "مثال: اشتراك المنزل",
                tv = tv, last = true, onDone = { submit() }
            )
        } else {
            Field(
                "رابط قائمة M3U", m3uUrl, { m3uUrl = it }, "http://server.example/playlist.m3u",
                ltr = true, keyboard = KeyboardType.Uri, tv = tv, focusRequester = firstField
            )
            Spacer(Modifier.height(14.dp))
            Field(
                "رابط دليل البرامج EPG (اختياري)", epgUrl, { epgUrl = it }, "http://server.example/epg.xml",
                ltr = true, keyboard = KeyboardType.Uri, tv = tv
            )
            Spacer(Modifier.height(14.dp))
            Field("اسم القائمة (اختياري)", name, { name = it }, "مثال: قائمة السفر", tv = tv, last = true, onDone = { submit() })
            if (!tv) {
                Spacer(Modifier.height(10.dp))
                OutlineButton(
                    "اختيار ملف M3U من الجهاز",
                    onClick = { pickFile() },
                    icon = RIcons.FileIcon,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }

    val errorBox: @Composable () -> Unit = {
        val currentError = error
        if (currentError != null) {
            Spacer(Modifier.height(14.dp))
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(RColors.DangerBg)
                    .padding(12.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Icon(RIcons.Alert, contentDescription = null, tint = RColors.Danger, modifier = Modifier.size(20.dp))
                Text(currentError, style = T.body(13.sp, color = RColors.Danger))
            }
        }
    }

    val submitButton: @Composable () -> Unit = {
        Row(
            Modifier
                .fillMaxWidth()
                .height(54.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(RColors.Saffron)
                .tvClickable(RoundedCornerShape(16.dp), enabled = !loading, ringColor = Color.White) { submit() },
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (loading) {
                CircularProgressIndicator(color = RColors.OnSaffron, strokeWidth = 2.5.dp, modifier = Modifier.size(22.dp))
                Spacer(Modifier.size(10.dp))
                Text("جارٍ التحقق والتحميل…", style = T.body(16.sp, FontWeight.Bold, RColors.OnSaffron))
            } else {
                Text(if (xtream) "دخول" else "إضافة القائمة", style = T.body(16.sp, FontWeight.Bold, RColors.OnSaffron))
                Spacer(Modifier.size(10.dp))
                Icon(RIcons.Forward, contentDescription = null, tint = RColors.OnSaffron, modifier = Modifier.size(20.dp))
            }
        }
    }

    val disclaimer: @Composable () -> Unit = {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Icon(RIcons.Info, contentDescription = null, tint = RColors.Text3, modifier = Modifier.size(18.dp))
            Text(
                "RATV مشغّل وسائط فقط ولا يوفّر أي قنوات أو محتوى. استخدم اشتراكاً من مزوّد مرخّص.",
                style = T.body(12.sp, color = RColors.Text3).copy(lineHeight = 20.sp)
            )
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(RColors.Ink)
    ) {
        if (wide) {
            // TV and landscape: introduction on one side, the form on the other, no scrolling needed.
            Row(
                Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .navigationBarsPadding()
                    .imePadding()
                    .padding(horizontal = 48.dp, vertical = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(48.dp)
            ) {
                val introPane: @Composable RowScope.() -> Unit = {
                    CenteredScroll(Modifier.weight(0.42f).fillMaxHeight()) {
                        intro()
                        Spacer(Modifier.height(24.dp))
                        modeSwitch()
                        Spacer(Modifier.height(24.dp))
                        disclaimer()
                    }
                }
                // On TV the on-screen keyboard floats over the middle of the screen, so the
                // fields go on the right where what you type stays visible.
                if (!tv) introPane()
                CenteredScroll(Modifier.weight(0.58f).fillMaxHeight()) {
                    fields()
                    errorBox()
                    Spacer(Modifier.height(20.dp))
                    submitButton()
                    if (tv) {
                        Spacer(Modifier.height(12.dp))
                        Text(
                            "استخدم الأسهم للتنقل بين الخانات، واضغط OK لفتح لوحة المفاتيح.",
                            style = T.caption,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
                if (tv) introPane()
            }
        } else {
            Column(
                Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .navigationBarsPadding()
                    .imePadding()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp)
            ) {
                Spacer(Modifier.height(12.dp))
                intro()
                Spacer(Modifier.height(24.dp))
                modeSwitch()
                Spacer(Modifier.height(20.dp))
                fields()
                errorBox()
                Spacer(Modifier.height(24.dp))
                submitButton()
                Spacer(Modifier.height(28.dp))
                disclaimer()
                Spacer(Modifier.height(24.dp))
            }
        }
    }

    if (showHelp) {
        AlertDialog(
            onDismissRequest = { showHelp = false },
            confirmButton = { TextButton(onClick = { showHelp = false }) { Text("حسناً", color = RColors.Saffron) } },
            title = { Text("كيف أضيف اشتراكي؟", style = T.h2) },
            text = {
                Text(
                    "• Xtream Codes: أدخل رابط الخادم مع المنفذ، واسم المستخدم وكلمة المرور كما أرسلها المزوّد. " +
                        "يمكنك أيضاً لصق رابط get.php كاملاً في خانة الخادم.\n\n" +
                        "• M3U: الصق رابط القائمة" + (if (tv) "" else " أو اختر ملف ‎.m3u من جهازك") +
                        ". رابط دليل البرامج اختياري." +
                        (if (tv) "\n\n• بالريموت: الأسهم للتنقل، وOK لفتح لوحة المفاتيح، وزر «التالي» في لوحة المفاتيح للانتقال إلى الخانة التالية." else ""),
                    style = T.body(14.sp, color = RColors.Text2)
                )
            },
            containerColor = RColors.Surface
        )
    }
}

/** A scrollable column whose content is centred vertically while it fits. */
@Composable
private fun CenteredScroll(modifier: Modifier, content: @Composable ColumnScope.() -> Unit) {
    BoxWithConstraints(modifier) {
        val minHeight = maxHeight
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .heightIn(min = minHeight)
                .padding(vertical = 8.dp),
            verticalArrangement = Arrangement.Center,
            content = content
        )
    }
}

@Composable
private fun Segment(label: String, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(
        modifier
            .height(44.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(if (selected) RColors.Surface3 else RColors.Surface)
            .tvClickable(RoundedCornerShape(12.dp), role = Role.Tab, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(label, style = T.body(15.sp, FontWeight.Bold, if (selected) RColors.Text else RColors.Text2))
    }
}

/** After the keyboard's "Next" key on TV, the next field starts typing as soon as it is reached. */
private object TvEditChain {
    var until = 0L
}

@Composable
private fun Field(
    label: String,
    value: String,
    onChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    ltr: Boolean = false,
    keyboard: KeyboardType = KeyboardType.Text,
    secret: Boolean = false,
    trailing: (@Composable () -> Unit)? = null,
    last: Boolean = false,
    onDone: () -> Unit = {},
    tv: Boolean = false,
    focusRequester: FocusRequester? = null
) {
    val keyboardController = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    // On TV the remote first selects the field and OK starts typing, so the keyboard never
    // opens by itself and the arrows keep moving between fields.
    var editing by remember { mutableStateOf(false) }
    val inputFocus = remember { FocusRequester() }
    val boxSource = remember { MutableInteractionSource() }
    val boxFocused by boxSource.collectIsFocusedAsState()
    LaunchedEffect(editing) {
        if (editing) {
            delay(40)
            runCatching { inputFocus.requestFocus() }
        }
    }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, style = if (boxFocused || editing) T.label.copy(color = RColors.Saffron) else T.label)
        val textStyle = if (ltr) {
            T.body(15.sp).merge(TextStyle(textDirection = TextDirection.Ltr, textAlign = TextAlign.Right))
        } else {
            T.body(15.sp)
        }
        var fieldModifier = Modifier.fillMaxWidth()
        if (tv) {
            fieldModifier = fieldModifier
                .focusRequester(inputFocus)
                .focusProperties { canFocus = editing }
                .onFocusChanged { if (!it.isFocused && editing) editing = false }
                .onPreviewKeyEvent { e ->
                    when (e.key) {
                        Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> {
                            if (e.type == KeyEventType.KeyUp) keyboardController?.show()
                            true
                        }
                        Key.DirectionDown -> {
                            if (e.type == KeyEventType.KeyDown) focusManager.moveFocus(FocusDirection.Down)
                            true
                        }
                        Key.DirectionUp -> {
                            if (e.type == KeyEventType.KeyDown) focusManager.moveFocus(FocusDirection.Up)
                            true
                        }
                        else -> false
                    }
                }
        } else if (focusRequester != null) {
            fieldModifier = fieldModifier.focusRequester(focusRequester)
        }
        val field: @Composable () -> Unit = {
            OutlinedTextField(
                value = value,
                onValueChange = onChange,
                modifier = fieldModifier,
                textStyle = textStyle,
                placeholder = {
                    Text(
                        placeholder,
                        style = textStyle.copy(color = RColors.Text3),
                        modifier = Modifier.fillMaxWidth()
                    )
                },
                trailingIcon = trailing,
                singleLine = true,
                visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
                keyboardOptions = KeyboardOptions(
                    keyboardType = keyboard,
                    imeAction = if (last) ImeAction.Done else ImeAction.Next
                ),
                keyboardActions = KeyboardActions(
                    onNext = {
                        if (tv) TvEditChain.until = System.currentTimeMillis() + 800L
                        focusManager.moveFocus(if (tv) FocusDirection.Down else FocusDirection.Next)
                    },
                    onDone = {
                        keyboardController?.hide()
                        onDone()
                    }
                ),
                shape = RoundedCornerShape(14.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = RColors.Saffron,
                    unfocusedBorderColor = if (boxFocused) RColors.Saffron else RColors.Surface3,
                    focusedContainerColor = RColors.Surface,
                    unfocusedContainerColor = RColors.Surface,
                    cursorColor = RColors.Saffron,
                    focusedTextColor = RColors.Text,
                    unfocusedTextColor = RColors.Text
                )
            )
        }
        if (tv) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
                    .onFocusChanged {
                        if (it.isFocused && System.currentTimeMillis() < TvEditChain.until) {
                            TvEditChain.until = 0L
                            editing = true
                        }
                    }
                    .focusProperties { canFocus = !editing }
                    .focusRing(boxFocused, RoundedCornerShape(14.dp))
                    .clickable(interactionSource = boxSource, indication = null, role = Role.Button) { editing = true }
            ) {
                field()
            }
        } else {
            field()
        }
    }
}
