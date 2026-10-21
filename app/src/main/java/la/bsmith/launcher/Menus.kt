package la.bsmith.launcher

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import kotlinx.coroutines.delay

/**
 * How a theme's popups look. The long-press menu, the hidden apps list and
 * the theme picker are the same in every theme but for this.
 */
class MenuStyle(
    val panel: Color,
    val text: Color,
    val dim: Color,
    val focusBackground: Color,
    val focusText: Color,
    val border: Color? = null,
    val shape: Shape = RectangleShape,
    val rowShape: Shape = RectangleShape,
    val font: FontFamily = FontFamily.Default,
    val fontSize: TextUnit = 16.sp,
    val uppercase: Boolean = false,
    val rowHeight: androidx.compose.ui.unit.Dp = 48.dp,
) {
    fun label(text: String) = if (uppercase) text.uppercase() else text
}

/** The long-press menu: favorite on or off, hide, cancel. Back closes it. */
@Composable
fun StyledAppMenu(
    style: MenuStyle,
    app: AppEntry,
    isFavorite: Boolean,
    onToggleFavorite: () -> Unit,
    onHide: () -> Unit,
    onUninstall: () -> Unit,
    onDismiss: () -> Unit,
) {
    StyledDialog(style, title = app.label, onDismiss = onDismiss, guardHeldSelect = true, tag = Tags.APP_MENU) { first ->
        MenuRow(style, if (isFavorite) "Remove from favorites" else "Add to favorites", { onToggleFavorite(); onDismiss() }, Modifier.focusRequester(first))
        MenuRow(style, "Hide app", { onHide(); onDismiss() })
        // A built-in package cannot go; hiding is the way to remove it from view.
        if (!app.isSystem) MenuRow(style, "Uninstall", { onUninstall(); onDismiss() })
        MenuRow(style, "Cancel", onDismiss)
    }
}

/** The hidden apps, one per row; OK on a row restores it. */
@Composable
fun StyledHiddenList(
    style: MenuStyle,
    hidden: List<AppEntry>,
    onUnhide: (AppEntry) -> Unit,
    onDismiss: () -> Unit,
) {
    Dialog(onDismissRequest = onDismiss) {
        var focusId by remember { mutableStateOf(hidden.firstOrNull()?.id) }
        val requester = remember { FocusRequester() }
        LaunchedEffect(focusId, hidden) {
            repeat(10) {
                delay(50)
                if (runCatching { requester.requestFocus() }.isSuccess) return@LaunchedEffect
            }
        }
        Column(modifier = Modifier.exposeTestTags().testTag(Tags.HIDDEN).then(panelModifier(style)).heightIn(max = 600.dp)) {
            Title(style, "Hidden apps")
            Caption(style, if (hidden.isEmpty()) "None. Hold OK on an app to hide it." else "OK restores. Back closes.")
            LazyColumn(modifier = Modifier.weight(1f, fill = false), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                items(hidden, key = { it.id }) { app ->
                    MenuRow(
                        style, app.label,
                        onClick = {
                            val index = hidden.indexOfFirst { it.id == app.id }
                            focusId = (hidden.getOrNull(index + 1) ?: hidden.getOrNull(index - 1))?.id
                            onUnhide(app)
                        },
                        modifier = if (app.id == focusId) Modifier.focusRequester(requester) else Modifier,
                    )
                }
            }
            MenuRow(style, "Close", onDismiss, Modifier.padding(top = 10.dp).then(if (focusId == null) Modifier.focusRequester(requester) else Modifier))
        }
    }
}

/** Every registered theme, the current one marked and focused; OK applies, Back closes. */
@Composable
fun StyledThemePicker(style: MenuStyle, current: Theme, onPick: (Theme) -> Unit, onDismiss: () -> Unit) {
    StyledDialog(style, title = "Theme", onDismiss = onDismiss, guardHeldSelect = true) { first ->
        for (theme in Theme.entries) {
            MenuRow(
                style,
                if (theme == current) "${theme.label} (current)" else theme.label,
                { onPick(theme); onDismiss() },
                if (theme == current) Modifier.focusRequester(first) else Modifier,
            )
        }
    }
}

/** A two-way choice, such as Light or Dark; the current one marked and focused. */
@Composable
fun StyledChoice(style: MenuStyle, title: String, options: List<String>, current: String, onPick: (String) -> Unit, onDismiss: () -> Unit) {
    StyledDialog(style, title = title, onDismiss = onDismiss, guardHeldSelect = true) { first ->
        for (option in options) {
            MenuRow(
                style,
                if (option == current) "$option (current)" else option,
                { onPick(option); onDismiss() },
                if (option == current) Modifier.focusRequester(first) else Modifier,
            )
        }
    }
}

@Composable
private fun StyledDialog(
    style: MenuStyle,
    title: String,
    onDismiss: () -> Unit,
    guardHeldSelect: Boolean,
    tag: String? = null,
    content: @Composable (first: FocusRequester) -> Unit,
) {
    Dialog(onDismissRequest = onDismiss) {
        val first = remember { FocusRequester() }
        LaunchedEffect(Unit) {
            delay(50)
            runCatching { first.requestFocus() }
        }
        Column(
            modifier = Modifier
                .exposeTestTags()
                .then(if (tag != null) Modifier.testTag(tag) else Modifier)
                .then(if (guardHeldSelect) Modifier.ignoreHeldSelect() else Modifier)
                .then(panelModifier(style)),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Title(style, title)
            content(first)
        }
    }
}

private fun panelModifier(style: MenuStyle): Modifier =
    Modifier
        .width(460.dp)
        .background(style.panel, style.shape)
        .then(if (style.border != null) Modifier.border(2.dp, style.border, style.shape) else Modifier)
        .padding(22.dp)

@Composable
private fun Title(style: MenuStyle, text: String) {
    Text(
        text = style.label(text),
        color = style.text,
        fontFamily = style.font,
        fontSize = style.fontSize * 1.2f,
        fontWeight = FontWeight.SemiBold,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.padding(bottom = 8.dp),
    )
}

@Composable
private fun Caption(style: MenuStyle, text: String) {
    Text(
        text = style.label(text),
        color = style.dim,
        fontFamily = style.font,
        fontSize = style.fontSize * 0.85f,
        maxLines = 2,
        modifier = Modifier.padding(bottom = 10.dp),
    )
}

@Composable
fun MenuRow(style: MenuStyle, label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        onClick = onClick,
        modifier = modifier.fillMaxWidth().height(style.rowHeight),
        shape = ClickableSurfaceDefaults.shape(shape = style.rowShape),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Color.Transparent,
            focusedContainerColor = style.focusBackground,
            pressedContainerColor = style.focusBackground,
            contentColor = style.text,
            focusedContentColor = style.focusText,
            pressedContentColor = style.focusText,
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f, pressedScale = 1f),
        border = ClickableSurfaceDefaults.border(
            focusedBorder = Border(BorderStroke(2.dp, style.focusText), shape = style.rowShape),
        ),
    ) {
        Box(modifier = Modifier.fillMaxSize().padding(horizontal = 14.dp), contentAlignment = Alignment.CenterStart) {
            Text(
                text = style.label(label),
                fontFamily = style.font,
                fontSize = style.fontSize,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
