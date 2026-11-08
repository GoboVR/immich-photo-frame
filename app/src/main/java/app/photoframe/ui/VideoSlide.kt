package app.photoframe.ui

import android.util.Log
import android.view.TextureView
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import app.photoframe.AppGraph
import app.photoframe.core.Slide

/**
 * An Immich video in the slideshow. Shows the poster immediately, fades the video in on
 * its first frame, plays muted unless [muted] is false, and calls [onEnded] when done
 * (or if it can't be played, so the slideshow never gets stuck).
 */
@Composable
fun VideoSlide(g: AppGraph, slide: Slide, muted: Boolean, paused: Boolean, onEnded: () -> Unit) {
    val context = LocalContext.current
    val poster = remember(slide.seq) { slide.bitmap.asImageBitmap() }
    var firstFrame by remember(slide.seq) { mutableStateOf(false) }
    val ended by rememberUpdatedState(onEnded)

    val player = remember(slide.seq) {
        val http = DefaultHttpDataSource.Factory()
            .setDefaultRequestProperties(mapOf("x-api-key" to g.immich.apiKey))
            .setAllowCrossProtocolRedirects(true)
        ExoPlayer.Builder(context).setMediaSourceFactory(DefaultMediaSourceFactory(http)).build().apply {
            // Don't take audio focus: music playing on the frame keeps going.
            setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(), false)
            volume = 0f
            setMediaItem(MediaItem.fromUri(g.immich.videoUrl(slide.asset.id)))
            prepare()
        }
    }
    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onRenderedFirstFrame() { firstFrame = true; Log.i("VideoSlide", "first frame ${slide.asset.id}") }
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_ENDED) { Log.i("VideoSlide", "ended ${slide.asset.id}"); ended() }
            }
            override fun onPlayerError(error: PlaybackException) {
                Log.w("VideoSlide", "can't play ${slide.asset.id}: ${error.errorCodeName}")
                ended()
            }
        }
        player.addListener(listener)
        onDispose { player.removeListener(listener); player.release() }
    }
    LaunchedEffect(player, muted) {
        player.volume = if (muted) 0f else 1f
        Log.i("VideoSlide", "sound ${if (muted) "off" else "on"} ${slide.asset.id}")
    }
    LaunchedEffect(player, paused) { player.playWhenReady = !paused }

    BoxWithConstraints(Modifier.fillMaxSize().clipToBounds()) {
        val screenAspect = maxWidth.value / maxHeight.value
        // Immich's poster is already correctly oriented, so lay out by its shape.
        val aspect = poster.width.toFloat() / poster.height
        val fit = aspect / screenAspect < 0.75f || aspect / screenAspect > 1.6f

        if (fit) {
            Image(poster, null, Modifier.fillMaxSize().blur(48.dp).drawWithContent { drawContent(); drawRect(Color.Black.copy(alpha = 0.35f)) },
                contentScale = ContentScale.Crop)
        }
        Image(poster, null, Modifier.fillMaxSize(), contentScale = if (fit) ContentScale.Fit else ContentScale.Crop)

        // Fit inside the screen, or cover it (cropping the overflow), keeping proportions.
        val wider = aspect > screenAspect
        val (w, h) = if (fit == wider) maxWidth to maxWidth / aspect else maxHeight * aspect to maxHeight
        AndroidView(
            factory = { ctx -> TextureView(ctx).also { player.setVideoTextureView(it) } },
            modifier = Modifier.align(Alignment.Center).requiredSize(w, h)
                .graphicsLayer { alpha = if (firstFrame) 1f else 0f },
        )
    }
}

