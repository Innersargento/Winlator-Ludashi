package com.winlator.cmod.ui.container

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.winlator.cmod.R
import com.winlator.cmod.core.CursorTintStore
import com.winlator.cmod.core.MsstylesStore
import com.winlator.cmod.core.WineThemeManager
import com.winlator.cmod.ui.uiStr
import com.winlator.cmod.widget.CursorPreviewView
import java.util.Locale

// Theme choices in the container editors are "Dark", "Light" or "msstyles:<name>" for an imported
// visual style, which is listed by its bare name (without the .msstyles extension).

internal fun themeStateFromValue(value: String?): String {
    val first = value.orEmpty().substringBefore(',')
    return when {
        first.startsWith(WineThemeManager.MSSTYLES_PREFIX) -> first
        first.equals("LIGHT", true) -> "Light"
        else -> "Dark"
    }
}

internal fun themeIdFromState(state: String): String = when {
    state.startsWith(WineThemeManager.MSSTYLES_PREFIX) -> state
    state.equals("Light", true) -> WineThemeManager.Theme.LIGHT.name
    else -> WineThemeManager.Theme.DARK.name
}

private fun builtinThemeLabel(name: String) = "$name ${uiStr(R.string.ui_theme_builtin)}"

internal fun themeEntries(imported: List<String>): List<String> =
    listOf(builtinThemeLabel("Dark"), builtinThemeLabel("Light")) + imported

internal fun themeEntryLabel(state: String): String = when {
    state.startsWith(WineThemeManager.MSSTYLES_PREFIX) -> state.removePrefix(WineThemeManager.MSSTYLES_PREFIX)
    else -> builtinThemeLabel(state)
}

internal fun themeStateFromEntry(entry: String): String = when (entry) {
    builtinThemeLabel("Dark") -> "Dark"
    builtinThemeLabel("Light") -> "Light"
    else -> WineThemeManager.MSSTYLES_PREFIX + entry.removeSuffix(MsstylesStore.EXTENSION)
}

/** Falls back to Dark when the selected imported style was deleted. */
internal fun themeStateOrDefault(context: Context, state: String): String =
    if (state.startsWith(WineThemeManager.MSSTYLES_PREFIX) &&
        !MsstylesStore.exists(context, state.removePrefix(WineThemeManager.MSSTYLES_PREFIX))) "Dark"
    else state

private fun importMsstylesTheme(context: Context, uri: Uri): String? {
    var displayName: String? = null
    runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) displayName = it.getString(0)
        }
    }
    return MsstylesStore.importFrom(context, uri, displayName)
}

/** Import / remove buttons shown under the theme choice. */
@Composable
internal fun ThemeImportRow(
    selectedState: String,
    onImported: (String) -> Unit,
    onRemoved: (String) -> Unit
) {
    val context = LocalContext.current
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            val name = importMsstylesTheme(context, uri)
            if (name == null) {
                Toast.makeText(context, uiStr(R.string.ui_theme_import_failed), Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(context, uiStr(R.string.ui_theme_imported_1_s, name), Toast.LENGTH_SHORT).show()
                onImported(name)
            }
        }
    }
    val importedName = selectedState.takeIf { it.startsWith(WineThemeManager.MSSTYLES_PREFIX) }
        ?.removePrefix(WineThemeManager.MSSTYLES_PREFIX)
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 8.dp, end = 8.dp, bottom = 4.dp),
        horizontalArrangement = Arrangement.End,
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (importedName != null) {
            IconButton(onClick = {
                MsstylesStore.delete(context, importedName)
                onRemoved(importedName)
            }) {
                Icon(Icons.Outlined.DeleteOutline, uiStr(R.string.ui_remove_theme), tint = MaterialTheme.colorScheme.error)
            }
        }
        IconButton(onClick = { picker.launch(arrayOf("*/*")) }) {
            Icon(Icons.Outlined.Add, uiStr(R.string.ui_import_theme))
        }
    }
}

private val cursorPalette = intArrayOf(
    0xFFFFFF, 0x000000, 0xD32F2F, 0xFF8F00, 0xFDD835, 0x2E7D32, 0x00838F, 0x0277BD, 0x9575CD,
    0xE91E63, 0x607D8B, 0x795548, 0xB0BEC5, 0x00E676, 0x40C4FF, 0xFF5252, 0xFFAB40, 0x7C4DFF
)

private const val PALETTE_COLUMNS = 6
private val PALETTE_DOT_SIZE = 36.dp

private fun rgbColor(rgb: Int) = Color(0xFF000000.toInt() or (rgb and 0xFFFFFF))

/** Inside and outline colors of Wine's mouse cursor, with a live arrow preview. */
@Composable
internal fun CursorColorsEditor(
    inside: Int,
    outline: Int,
    modifier: Modifier = Modifier,
    onChange: (inside: Int, outline: Int) -> Unit
) {
    var picking by remember { mutableStateOf(0) } // 0 = closed, 1 = inside, 2 = outline
    Column(modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp)) {
        Text(
            uiStr(R.string.ui_mouse_cursor_colors),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Row(
            Modifier.fillMaxWidth().padding(top = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = MaterialTheme.colorScheme.surfaceVariant,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
            ) {
                AndroidView(
                    modifier = Modifier.size(64.dp),
                    factory = { CursorPreviewView(it) },
                    update = { it.setColors(inside and 0xFFFFFF, outline and 0xFFFFFF) }
                )
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                CursorColorRow(uiStr(R.string.ui_cursor_inside_color), inside) { picking = 1 }
                CursorColorRow(uiStr(R.string.ui_cursor_outline_color), outline) { picking = 2 }
            }
        }
        if (inside != CursorTintStore.DEFAULT_INSIDE || outline != CursorTintStore.DEFAULT_OUTLINE) {
            TextButton(
                onClick = { onChange(CursorTintStore.DEFAULT_INSIDE, CursorTintStore.DEFAULT_OUTLINE) },
                modifier = Modifier.align(Alignment.End)
            ) { Text(uiStr(R.string.ui_cursor_colors_reset)) }
        }
    }
    if (picking != 0) {
        val editingInside = picking == 1
        ColorPaletteDialog(
            title = uiStr(if (editingInside) R.string.ui_cursor_inside_color else R.string.ui_cursor_outline_color),
            current = if (editingInside) inside else outline,
            onDismiss = { picking = 0 },
            onPicked = {
                if (editingInside) onChange(it, outline) else onChange(inside, it)
                picking = 0
            }
        )
    }
}

@Composable
private fun CursorColorRow(label: String, rgb: Int, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            Box(Modifier.size(24.dp).clip(CircleShape).background(rgbColor(rgb)))
        }
    }
}

@Composable
private fun ColorPaletteDialog(title: String, current: Int, onDismiss: () -> Unit, onPicked: (Int) -> Unit) {
    var hex by remember { mutableStateOf(String.format(Locale.US, "#%06X", current and 0xFFFFFF)) }
    val parsed = remember(hex) {
        runCatching { android.graphics.Color.parseColor(hex.trim().let { if (it.startsWith("#")) it else "#$it" }) and 0xFFFFFF }.getOrNull()
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                cursorPalette.toList().chunked(PALETTE_COLUMNS).forEach { row ->
                    Row(Modifier.fillMaxWidth()) {
                        row.forEach { rgb ->
                            val selected = rgb == (current and 0xFFFFFF)
                            Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                                Box(
                                    Modifier.size(PALETTE_DOT_SIZE).clip(CircleShape).background(rgbColor(rgb))
                                        .border(
                                            if (selected) 3.dp else 1.dp,
                                            if (selected) MaterialTheme.colorScheme.primary
                                            else MaterialTheme.colorScheme.outlineVariant,
                                            CircleShape
                                        )
                                        .clickable { onPicked(rgb) }
                                )
                            }
                        }
                        // Keep the last row's dots the same size as the full rows.
                        repeat(PALETTE_COLUMNS - row.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
                OutlinedTextField(
                    value = hex,
                    onValueChange = { hex = it.take(7) },
                    label = { Text(uiStr(R.string.ui_hex_color)) },
                    singleLine = true,
                    isError = parsed == null,
                    shape = RoundedCornerShape(10.dp)
                )
            }
        },
        confirmButton = {
            TextButton(enabled = parsed != null, onClick = { parsed?.let(onPicked) }) {
                Text(uiStr(R.string.ok))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(uiStr(R.string.cancel)) } }
    )
}

internal const val DEFAULT_DESKTOP_COLOR = 0x0277BD

/** Reads the solid color out of a container desktopTheme value ("THEME,COLOR,#rrggbb[,stamp]"). */
internal fun desktopColorFromValue(value: String?): Int =
    runCatching {
        android.graphics.Color.parseColor(value.orEmpty().split(',')[2].trim()) and 0xFFFFFF
    }.getOrDefault(DEFAULT_DESKTOP_COLOR)

internal fun desktopColorHex(rgb: Int): String = String.format(Locale.US, "#%06x", rgb and 0xFFFFFF)

/** Solid desktop background color, picked from the same palette as the cursor colors. */
@Composable
internal fun DesktopColorEditor(
    rgb: Int,
    modifier: Modifier = Modifier,
    onChange: (Int) -> Unit
) {
    var picking by remember { mutableStateOf(false) }
    Column(modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp)) {
        CursorColorRow(uiStr(R.string.ui_background_color), rgb) { picking = true }
    }
    if (picking) {
        ColorPaletteDialog(
            title = uiStr(R.string.ui_background_color),
            current = rgb,
            onDismiss = { picking = false },
            onPicked = { onChange(it); picking = false }
        )
    }
}
