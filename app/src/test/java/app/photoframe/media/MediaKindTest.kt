package app.photoframe.media

import org.junit.Assert.assertEquals
import org.junit.Test

class MediaKindTest {
    @Test fun mimeWins() {
        assertEquals("video", mediaKind("http://x/a", "video/mp4", "music"))
        assertEquals("image", mediaKind("http://x/a", "image/jpeg", null))
        assertEquals("audio", mediaKind("http://x/a.mp4", "audio/mp4", null))
        assertEquals("video", mediaKind("/api/hls/tok/master_playlist.m3u8", "application/vnd.apple.mpegurl", null))
    }

    @Test fun haMediaTypeNext() {
        assertEquals("video", mediaKind("http://x/stream", null, "video"))
        assertEquals("video", mediaKind("http://x/stream", null, "movie"))
        assertEquals("image", mediaKind("http://x/pic", null, "image"))
        assertEquals("audio", mediaKind("http://x/song.mp4", null, "music")) // m4a-in-mp4 music stays audio
    }

    @Test fun urlLast() {
        assertEquals("video", mediaKind("http://x/clip.MP4?sig=1", null, "url"))
        assertEquals("image", mediaKind("http://x/doorbell.jpg", null, "url"))
        assertEquals("image", mediaKind("/api/camera_proxy/camera.door?authSig=x", null, null))
        assertEquals("audio", mediaKind("/api/tts_proxy/abc.mp3?authSig=x", null, "music"))
        assertEquals("audio", mediaKind("https://rr8.googlevideo.com/videoplayback?mime=audio%2Fmp4", null, null))
    }
}
