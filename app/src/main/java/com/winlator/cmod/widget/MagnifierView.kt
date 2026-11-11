package com.winlator.cmod.widget

import android.content.Context
import android.content.SharedPreferences
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.DragIndicator
import androidx.compose.material.icons.outlined.Remove
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.input.pointer.pointerInteropFilter
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.unit.dp
import androidx.preference.PreferenceManager
import com.winlator.cmod.R
import com.winlator.cmod.core.Callback
import com.winlator.cmod.core.UnitUtils
import com.winlator.cmod.math.Mathf
import com.winlator.cmod.ui.theme.WinZOverlayTheme
import com.winlator.cmod.ui.uiStr

/** Floating zoom panel of the in-game magnifier, drawn with Compose. */
class MagnifierView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {
    private val preferences: SharedPreferences = PreferenceManager.getDefaultSharedPreferences(context)
    private var restoreSavedPosition = true
    private var zoom by mutableFloatStateOf(1f)
    private var dragTouchX = 0f
    private var dragTouchY = 0f
    private var dragViewX = 0f
    private var dragViewY = 0f

    var zoomButtonCallback: Callback<Float>? = null
    var hideButtonCallback: Runnable? = null

    init {
        layoutParams = LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        addView(ComposeView(context).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
            setContent {
                WinZOverlayTheme {
                    MagnifierPanel(
                        zoom = zoom,
                        onZoom = { zoomButtonCallback?.call(it) },
                        onHide = { hideButtonCallback?.run() },
                        onDragStart = { rawX, rawY -> dragTouchX = rawX; dragTouchY = rawY; dragViewX = this@MagnifierView.x; dragViewY = this@MagnifierView.y },
                        onDrag = { rawX, rawY -> movePanel(dragViewX + rawX - dragTouchX, dragViewY + rawY - dragTouchY) },
                        onDragEnd = { savePosition() }
                    )
                }
            }
        })
    }

    fun setZoomValue(value: Float) {
        zoom = value
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        if (restoreSavedPosition && width > 0 && height > 0) {
            var px = 1e6f
            var py = 1e6f
            preferences.getString("magnifier_view", null)?.let { config ->
                runCatching {
                    val parts = config.split("|")
                    px = parts[0].toShort().toFloat()
                    py = parts[1].toShort().toFloat()
                }
            }
            movePanel(px, py)
            restoreSavedPosition = false
        }
    }

    private fun movePanel(newX: Float, newY: Float) {
        val parent = parent as? ViewGroup ?: return
        val padding = UnitUtils.dpToPx(8f)
        x = Mathf.clamp(newX, padding, parent.width - padding - width)
        y = Mathf.clamp(newY, padding, parent.height - padding - height)
    }

    private fun savePosition() {
        if (x > 0 && y > 0) {
            preferences.edit().putString("magnifier_view", "${x.toInt().toShort()}|${y.toInt().toShort()}").apply()
        }
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun MagnifierPanel(
    zoom: Float,
    onZoom: (Float) -> Unit,
    onHide: () -> Unit,
    onDragStart: (Float, Float) -> Unit,
    onDrag: (Float, Float) -> Unit,
    onDragEnd: () -> Unit
) {
    val currentDragStart by rememberUpdatedState(onDragStart)
    val currentDrag by rememberUpdatedState(onDrag)
    val currentDragEnd by rememberUpdatedState(onDragEnd)
    Surface(
        modifier = Modifier.width(60.dp),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.94f),
        contentColor = MaterialTheme.colorScheme.onSurface,
        tonalElevation = 6.dp,
        shadowElevation = 8.dp,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column(
            Modifier.padding(vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            // Screen coordinates: the panel moves under the finger, so local ones would stall the drag.
            Icon(
                Icons.Outlined.DragIndicator,
                uiStr(R.string.ui_magnifier_move),
                Modifier.size(44.dp).padding(10.dp).pointerInteropFilter { event ->
                    when (event.actionMasked) {
                        MotionEvent.ACTION_DOWN -> { currentDragStart(event.rawX, event.rawY); true }
                        MotionEvent.ACTION_MOVE -> { currentDrag(event.rawX, event.rawY); true }
                        MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { currentDragEnd(); true }
                        else -> false
                    }
                },
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
            IconButton(onClick = onHide, Modifier.size(44.dp)) {
                Icon(Icons.Outlined.Close, uiStr(R.string.ui_magnifier_hide))
            }
            HorizontalDivider(Modifier.padding(horizontal = 10.dp, vertical = 4.dp), color = MaterialTheme.colorScheme.outlineVariant)
            IconButton(onClick = { onZoom(0.25f) }, Modifier.size(44.dp)) {
                Icon(Icons.Outlined.Add, uiStr(R.string.ui_magnifier_zoom_in), tint = MaterialTheme.colorScheme.primary)
            }
            Text("${(zoom * 100).toInt()}%", style = MaterialTheme.typography.labelLarge)
            IconButton(onClick = { onZoom(-0.25f) }, Modifier.size(44.dp)) {
                Icon(Icons.Outlined.Remove, uiStr(R.string.ui_magnifier_zoom_out), tint = MaterialTheme.colorScheme.primary)
            }
        }
    }
}
