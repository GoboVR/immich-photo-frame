package app.photoframe.server

import android.util.Base64
import android.util.Log
import app.photoframe.AppGraph
import app.photoframe.device.CameraStreamer
import app.photoframe.device.H264Encoder
import kotlinx.coroutines.runBlocking
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.security.MessageDigest
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.random.Random

/**
 * Minimal RTSP server for the live camera: H.264 over RTP (RFC 6184), UDP or TCP-interleaved.
 * URL: rtsp://<frame>:8554/camera?key=<camera key>  (or an HA token as ?token=, or the key as
 * the Basic-auth password). Works with VLC, ffmpeg/Frigate and Home Assistant's stream component.
 */
class RtspServer(private val g: AppGraph) {
    private var serverSocket: ServerSocket? = null
    private val sessions = CopyOnWriteArraySet<Session>()

    @Synchronized
    fun restart(port: Int) {
        stop()
        val ss = try { ServerSocket(port) } catch (e: IOException) { Log.e(TAG, "RTSP can't listen on $port", e); return }
        serverSocket = ss
        thread(name = "rtsp-accept", isDaemon = true) {
            while (!ss.isClosed) {
                val socket = try { ss.accept() } catch (e: IOException) { break }
                socket.tcpNoDelay = true
                val s = Session(socket)
                sessions += s
                thread(name = "rtsp-client", isDaemon = true) { s.run() }
            }
        }
        Log.i(TAG, "RTSP listening on $port")
    }

    @Synchronized
    fun stop() {
        runCatching { serverSocket?.close() }
        serverSocket = null
        sessions.forEach { it.close() }
    }

    private class Request(val method: String, val url: String, val headers: Map<String, String>)
    private class AccessUnit(val data: ByteArray, val ptsUs: Long, val key: Boolean)

    private inner class Session(private val socket: Socket) : CameraStreamer.H264Sink {
        private val input = BufferedInputStream(socket.getInputStream())
        private val output = BufferedOutputStream(socket.getOutputStream(), 64 * 1024)
        private val writeLock = Any()
        private val sessionId = Random.nextLong(0x1000000000L, 0xFFFFFFFFFFL).toString(16)
        private val ssrc = Random.nextInt()
        private var seq = Random.nextInt(0, 0xFFFF)
        private val tsOffset = Random.nextLong(0, 1L shl 31)
        private var authorized = false
        private var lease: CameraStreamer.Lease? = null
        private var tcp = false
        private var channel = 0
        private var udp: DatagramSocket? = null
        private var udpDest: InetSocketAddress? = null
        @Volatile private var playing = false
        @Volatile private var closed = false
        @Volatile private var needKey = true
        private val queue = ArrayBlockingQueue<AccessUnit>(90)

        fun run() {
            try {
                socket.soTimeout = 0
                while (!closed) {
                    input.mark(1)
                    val b = input.read()
                    if (b < 0) break
                    if (b == '$'.code) { // interleaved RTCP from the client: skip it
                        input.read(); val hi = input.read(); val lo = input.read()
                        if (lo < 0) break
                        skip(input, (hi shl 8) or lo); continue
                    }
                    input.reset()
                    val req = readRequest() ?: break
                    handle(req)
                }
            } catch (_: IOException) {
            } catch (e: Exception) {
                Log.w(TAG, "session error", e)
            } finally {
                close()
            }
        }

        private fun handle(req: Request) {
            val cseq = req.headers["cseq"] ?: "0"
            when (req.method) {
                "OPTIONS" -> reply(cseq, 200, "OK", mapOf("Public" to "OPTIONS, DESCRIBE, SETUP, PLAY, PAUSE, TEARDOWN, GET_PARAMETER"))
                "DESCRIBE" -> {
                    if (!checkAuth(req)) return unauthorized(cseq)
                    if (!g.camera.enabled) return reply(cseq, 503, "Camera Off")
                    if (lease == null) {
                        lease = runCatching { runBlocking { g.camera.acquire(CameraStreamer.Kind.H264) } }
                            .getOrElse { return reply(cseq, 503, "Service Unavailable") }
                    }
                    val cfg = runBlocking { g.camera.awaitH264Config() } ?: return reply(cseq, 503, "Service Unavailable")
                    val sdp = buildSdp(cfg.sps, cfg.pps)
                    val base = req.url.substringBefore('?').trimEnd('/') + "/"
                    reply(cseq, 200, "OK", mapOf("Content-Base" to base, "Content-Type" to "application/sdp"), sdp)
                }
                "SETUP" -> {
                    if (!authorized) return unauthorized(cseq)
                    val t = req.headers["transport"].orEmpty()
                    val transport = if (t.contains("TCP", ignoreCase = true)) {
                        tcp = true
                        channel = Regex("interleaved=(\\d+)").find(t)?.groupValues?.get(1)?.toInt() ?: 0
                        "RTP/AVP/TCP;unicast;interleaved=$channel-${channel + 1};ssrc=${ssrcHex()}"
                    } else {
                        val m = Regex("client_port=(\\d+)(?:-(\\d+))?").find(t)
                            ?: return reply(cseq, 461, "Unsupported Transport")
                        val port = m.groupValues[1].toInt()
                        val sock = DatagramSocket().also { udp?.close(); udp = it }
                        udpDest = InetSocketAddress(socket.inetAddress, port)
                        "RTP/AVP;unicast;client_port=$port-${port + 1};server_port=${sock.localPort}-${sock.localPort + 1};ssrc=${ssrcHex()}"
                    }
                    reply(cseq, 200, "OK", mapOf("Transport" to transport, "Session" to "$sessionId;timeout=60"))
                }
                "PLAY" -> {
                    if (!authorized) return unauthorized(cseq)
                    if (!tcp && udp == null) return reply(cseq, 455, "Method Not Valid In This State")
                    val trackUrl = req.url.substringBefore('?').trimEnd('/') + "/trackID=0"
                    reply(cseq, 200, "OK", mapOf("Session" to sessionId, "Range" to "npt=0.000-", "RTP-Info" to "url=$trackUrl;seq=$seq"))
                    startPlaying()
                }
                "PAUSE" -> { playing = false; reply(cseq, 200, "OK", mapOf("Session" to sessionId)) }
                "TEARDOWN" -> { reply(cseq, 200, "OK", mapOf("Session" to sessionId)); close() }
                "GET_PARAMETER", "SET_PARAMETER" -> reply(cseq, 200, "OK", mapOf("Session" to sessionId))
                else -> reply(cseq, 405, "Method Not Allowed")
            }
        }

        private fun checkAuth(req: Request): Boolean {
            if (authorized) return true
            val query = req.url.substringAfter('?', "").split('&').mapNotNull {
                val kv = it.split('=', limit = 2)
                if (kv.size == 2) kv[0] to URLDecoder.decode(kv[1], "UTF-8") else null
            }.toMap()
            val basicPassword = req.headers["authorization"]?.takeIf { it.startsWith("Basic ", ignoreCase = true) }?.let {
                runCatching { String(Base64.decode(it.substring(6).trim(), Base64.DEFAULT)).substringAfter(':') }.getOrNull()
            }
            val cfg = g.config.value
            val candidates = listOfNotNull(query["key"], query["token"], basicPassword)
            authorized = candidates.any { c ->
                MessageDigest.isEqual(c.toByteArray(), cfg.cameraKey.toByteArray()) || cfg.serverTokens.containsKey(c)
            }
            return authorized
        }

        private fun startPlaying() {
            if (playing) return
            playing = true
            needKey = true
            g.camera.addSink(this)
            g.camera.requestKeyFrame()
            thread(name = "rtsp-writer", isDaemon = true) { writerLoop() }
        }

        /** Called on the encoder thread: never block it. */
        override fun onAccessUnit(data: ByteArray, ptsUs: Long, keyFrame: Boolean) {
            if (!playing || closed) return
            if (needKey) { if (!keyFrame) return; needKey = false }
            if (!queue.offer(AccessUnit(data, ptsUs, keyFrame))) {
                // This viewer can't keep up: drop the backlog and resync on the next keyframe.
                queue.clear(); needKey = true; g.camera.requestKeyFrame()
            }
        }

        private fun writerLoop() {
            try {
                while (!closed) {
                    if (!g.camera.enabled) { close(); break }
                    val au = queue.poll(500, TimeUnit.MILLISECONDS) ?: continue
                    sendAccessUnit(au)
                }
            } catch (_: IOException) {
                close()
            } catch (e: Exception) {
                Log.w(TAG, "writer error", e); close()
            }
        }

        private fun sendAccessUnit(au: AccessUnit) {
            var nals = H264Encoder.splitNals(au.data).filter { it.isNotEmpty() && (it[0].toInt() and 0x1F) != 9 } // drop AUDs
            // Make every keyframe self-contained (SPS/PPS first) so decoders can start/resync there.
            if (au.key && nals.none { (it[0].toInt() and 0x1F) == 7 }) {
                g.camera.h264Config?.let { nals = listOf(it.sps, it.pps) + nals }
            }
            val ts = ((au.ptsUs * 90 / 1000) + tsOffset) and 0xFFFFFFFFL
            for ((i, nal) in nals.withIndex()) {
                val lastNal = i == nals.lastIndex
                if (nal.size <= MAX_PAYLOAD) {
                    sendRtp(nal, 0, nal.size, ts, marker = lastNal, prefix = null)
                } else {
                    // FU-A fragmentation
                    val nalHeader = nal[0].toInt()
                    val fuIndicator = ((nalHeader and 0xE0) or 28).toByte()
                    var off = 1
                    while (off < nal.size) {
                        val len = minOf(MAX_PAYLOAD - 2, nal.size - off)
                        val start = off == 1
                        val end = off + len >= nal.size
                        val fuHeader = ((if (start) 0x80 else 0) or (if (end) 0x40 else 0) or (nalHeader and 0x1F)).toByte()
                        sendRtp(nal, off, len, ts, marker = lastNal && end, prefix = byteArrayOf(fuIndicator, fuHeader))
                        off += len
                    }
                }
            }
            if (tcp) synchronized(writeLock) { output.flush() }
        }

        private fun sendRtp(src: ByteArray, off: Int, len: Int, ts: Long, marker: Boolean, prefix: ByteArray?) {
            val pre = prefix?.size ?: 0
            val pkt = ByteArray(12 + pre + len)
            pkt[0] = 0x80.toByte()
            pkt[1] = ((if (marker) 0x80 else 0) or PAYLOAD_TYPE).toByte()
            pkt[2] = (seq shr 8).toByte(); pkt[3] = seq.toByte()
            seq = (seq + 1) and 0xFFFF
            pkt[4] = (ts shr 24).toByte(); pkt[5] = (ts shr 16).toByte(); pkt[6] = (ts shr 8).toByte(); pkt[7] = ts.toByte()
            pkt[8] = (ssrc shr 24).toByte(); pkt[9] = (ssrc shr 16).toByte(); pkt[10] = (ssrc shr 8).toByte(); pkt[11] = ssrc.toByte()
            prefix?.copyInto(pkt, 12)
            System.arraycopy(src, off, pkt, 12 + pre, len)
            if (tcp) {
                synchronized(writeLock) {
                    output.write('$'.code); output.write(channel)
                    output.write(pkt.size shr 8); output.write(pkt.size and 0xFF)
                    output.write(pkt)
                }
            } else {
                udp?.send(DatagramPacket(pkt, pkt.size, udpDest))
            }
        }

        private fun buildSdp(sps: ByteArray, pps: ByteArray): String {
            val profile = if (sps.size >= 4) "%02X%02X%02X".format(sps[1], sps[2], sps[3]) else "42E01F"
            val b64 = { b: ByteArray -> Base64.encodeToString(b, Base64.NO_WRAP) }
            val host = socket.localAddress.hostAddress ?: "0.0.0.0"
            return listOf(
                "v=0",
                "o=- ${System.currentTimeMillis()} 1 IN IP4 $host",
                "s=${g.config.value.frameName.replace(Regex("[\\r\\n]"), " ")} camera",
                "c=IN IP4 0.0.0.0",
                "t=0 0",
                "a=control:*",
                "m=video 0 RTP/AVP $PAYLOAD_TYPE",
                "a=rtpmap:$PAYLOAD_TYPE H264/90000",
                "a=fmtp:$PAYLOAD_TYPE packetization-mode=1;profile-level-id=$profile;sprop-parameter-sets=${b64(sps)},${b64(pps)}",
                "a=control:trackID=0",
            ).joinToString("\r\n", postfix = "\r\n")
        }

        private fun ssrcHex() = "%08X".format(ssrc)

        private fun unauthorized(cseq: String) =
            reply(cseq, 401, "Unauthorized", mapOf("WWW-Authenticate" to "Basic realm=\"Photo Frame camera\""))

        private fun reply(cseq: String, code: Int, reason: String, headers: Map<String, String> = emptyMap(), body: String = "") {
            val bytes = body.toByteArray()
            val sb = StringBuilder("RTSP/1.0 $code $reason\r\nCSeq: $cseq\r\nServer: PhotoFrame\r\n")
            headers.forEach { (k, v) -> sb.append(k).append(": ").append(v).append("\r\n") }
            if (bytes.isNotEmpty()) sb.append("Content-Length: ${bytes.size}\r\n")
            sb.append("\r\n")
            synchronized(writeLock) {
                output.write(sb.toString().toByteArray())
                if (bytes.isNotEmpty()) output.write(bytes)
                output.flush()
            }
        }

        private fun readRequest(): Request? {
            val first = readLine() ?: return null
            val parts = first.split(' ')
            if (parts.size < 3) return null
            val headers = HashMap<String, String>()
            while (true) {
                val line = readLine() ?: return null
                if (line.isEmpty()) break
                val i = line.indexOf(':')
                if (i > 0) headers[line.substring(0, i).trim().lowercase()] = line.substring(i + 1).trim()
            }
            headers["content-length"]?.toIntOrNull()?.let { if (it > 0) skip(input, it) }
            return Request(parts[0].uppercase(), parts[1], headers)
        }

        private fun readLine(): String? {
            val sb = StringBuilder()
            while (true) {
                val c = input.read()
                if (c < 0) return if (sb.isEmpty()) null else sb.toString()
                if (c == '\n'.code) return sb.toString().trimEnd('\r')
                if (sb.length > 8192) throw IOException("line too long")
                sb.append(c.toChar())
            }
        }

        fun close() {
            if (closed) return
            closed = true
            playing = false
            g.camera.removeSink(this)
            lease?.close(); lease = null
            runCatching { udp?.close() }
            runCatching { socket.close() }
            sessions.remove(this)
        }
    }

    companion object {
        private const val TAG = "RtspServer"
        private const val PAYLOAD_TYPE = 96
        private const val MAX_PAYLOAD = 1400

        private fun skip(input: InputStream, n: Int) {
            var left = n.toLong()
            while (left > 0) {
                val s = input.skip(left)
                if (s <= 0) { if (input.read() < 0) throw IOException("eof"); left-- } else left -= s
            }
        }
    }
}
