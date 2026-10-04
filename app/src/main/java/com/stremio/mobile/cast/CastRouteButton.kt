package com.stremio.mobile.cast

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.mediarouter.app.MediaRouteButton
import com.google.android.gms.cast.framework.CastButtonFactory

@Composable
// Kept as a standalone Compose wrapper so Cast routing remains isolated from player chrome.
fun CastRouteButton(modifier: Modifier = Modifier) {
    AndroidView(modifier = modifier, factory = { context ->
        MediaRouteButton(context).apply {
            contentDescription = "Cast"
            runCatching { CastButtonFactory.setUpMediaRouteButton(context.applicationContext, this) }
                .onFailure { visibility = android.view.View.GONE }
        }
    })
}
