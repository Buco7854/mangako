package com.mangako.app.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage
import com.mangako.app.R
import java.io.File

/**
 * Full-screen viewer for a cover thumbnail. Tap-anywhere-to-dismiss
 * (matching the Mihon-style ergonomics) plus a small close button in
 * the corner for users who reach the close icon by habit. Pinch to
 * zoom, drag to pan when zoomed in.
 *
 * Renders inside a [Dialog] so it overlays the system bars and the
 * Activity's content. The dialog's own dismiss handling routes back
 * presses + scrim taps through [onDismiss], so callers don't have to
 * wire BackHandlers themselves.
 */
@Composable
fun FullscreenImageDialog(
    file: File?,
    onDismiss: () -> Unit,
) {
    if (file == null || !file.exists()) return
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            // Keep the system back button working as a dismiss action.
            dismissOnBackPress = true,
            dismissOnClickOutside = true,
        ),
    ) {
        // Pinch to zoom, drag to pan. Double-tap-to-zoom would also be
        // nice but is enough complexity to skip until users ask. The
        // gesture detector only consumes events; tap-to-dismiss lives
        // on the outer clickable so a quick tap (no drag) still closes.
        var scale by remember { mutableFloatStateOf(1f) }
        var offsetX by remember { mutableFloatStateOf(0f) }
        var offsetY by remember { mutableFloatStateOf(0f) }
        var moved by remember { mutableStateOf(false) }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.95f))
                .clickable(
                    // Only dismiss on a clean tap — if the user just
                    // finished a pinch / drag gesture we'd otherwise
                    // close mid-zoom which feels broken.
                    enabled = !moved,
                    onClick = onDismiss,
                )
                .pointerInput(Unit) {
                    detectTransformGestures { _, pan, zoom, _ ->
                        moved = true
                        scale = (scale * zoom).coerceIn(1f, 6f)
                        if (scale > 1f) {
                            offsetX += pan.x
                            offsetY += pan.y
                        } else {
                            offsetX = 0f
                            offsetY = 0f
                        }
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            AsyncImage(
                model = file,
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer(
                        scaleX = scale,
                        scaleY = scale,
                        translationX = offsetX,
                        translationY = offsetY,
                    ),
            )
            IconButton(
                onClick = onDismiss,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(8.dp),
            ) {
                Icon(
                    Icons.Outlined.Close,
                    contentDescription = stringResource(R.string.fullscreen_image_close_cd),
                    tint = MaterialTheme.colorScheme.onSurface,
                )
            }
        }
        // Reset moved-flag when scale settles back to 1, so the next
        // single-tap can dismiss again.
        if (scale == 1f && offsetX == 0f && offsetY == 0f) moved = false
    }
}
