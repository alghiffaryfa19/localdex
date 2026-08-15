package com.localdex.scrcpy

import android.media.MediaCodec
import android.media.MediaFormat
import android.util.Log
import android.view.Surface
import java.io.DataInputStream
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.CountDownLatch

/**
 * Reads the scrcpy v4.1 video stream and decodes it onto a [Surface].
 *
 * Stream layout (send_dummy_byte=false, send_device_meta=false, defaults otherwise):
 *   [4B codec id ("h264")]
 *   then packets, each starting with a 12-byte header:
 *     - session packet (MSB of first byte set): [4B flags][4B width][4B height], no payload
 *     - frame packet: [8B pts+flags][4B size][size bytes of H.264]
 *       flags: bit62 = codec config (SPS/PPS), bit61 = key frame
 */
class VideoDecoder(
    private val input: InputStream,
    private val onVideoSize: (Int, Int) -> Unit,
    private val onError: (String) -> Unit,
) {
    companion object {
        private const val TAG = "VideoDecoder"

        private const val CODEC_ID_H264 = 0x68323634
        private const val FLAG_CONFIG = 1L shl 62

        private const val PTS_MASK = (1L shl 61) - 1
    }

    @Volatile
    private var surface: Surface? = null
    private val surfaceLatch = CountDownLatch(1)

    @Volatile
    private var renderEnabled = false

    @Volatile
    private var running = true

    private var codec: MediaCodec? = null
    private var outputThread: Thread? = null

    private var videoWidth = 0
    private var videoHeight = 0
    private var needsReconfigure = false

    private val readerThread = Thread({ runReader() }, "localdex-video")

    fun start() {
        readerThread.start()
    }

    /** Must be called (once the viewer's surface exists) before decoding can begin. */
    fun setSurface(surface: Surface) {
        this.surface = surface
        val currentCodec = codec
        if (currentCodec != null) {
            // Viewer came back after being backgrounded: point the codec at the new surface.
            try {
                currentCodec.setOutputSurface(surface)
            } catch (e: IllegalStateException) {
                Log.w(TAG, "Could not switch output surface", e)
            }
        }
        renderEnabled = true
        surfaceLatch.countDown()
    }

    /** The viewer's surface is going away; keep decoding but stop rendering. */
    fun clearSurface() {
        renderEnabled = false
    }

    fun stop() {
        running = false
        readerThread.interrupt()
        try {
            input.close()
        } catch (e: IOException) {
            // Closing is what unblocks the reader; nothing to do.
        }
    }

    private fun runReader() {
        try {
            val dis = DataInputStream(input)

            val codecId = dis.readInt()
            if (codecId != CODEC_ID_H264) {
                throw IOException("Unexpected codec id 0x${Integer.toHexString(codecId)}")
            }

            val header = ByteArray(12)
            while (running) {
                dis.readFully(header)

                if ((header[0].toInt() and 0x80) != 0) {
                    // Session packet: capture (re)started, possibly with a new size.
                    val width = readInt(header, 4)
                    val height = readInt(header, 8)
                    Log.i(TAG, "Video session: ${width}x$height")
                    if (videoWidth != 0 && (width != videoWidth || height != videoHeight)) {
                        needsReconfigure = true
                    }
                    videoWidth = width
                    videoHeight = height
                    onVideoSize(width, height)
                    continue
                }

                val ptsAndFlags = readLong(header, 0)
                val size = readInt(header, 8)
                if (size <= 0 || size > 16 * 1024 * 1024) {
                    throw IOException("Implausible packet size $size — stream out of sync")
                }
                val payload = ByteArray(size)
                dis.readFully(payload)

                val isConfig = (ptsAndFlags and FLAG_CONFIG) != 0L
                if (isConfig) {
                    // Config packets (SPS/PPS) open every capture session; this is the
                    // safe moment to (re)create the codec.
                    surfaceLatch.await()
                    if (codec == null || needsReconfigure) {
                        recreateCodec()
                        needsReconfigure = false
                    }
                    submit(payload, 0, MediaCodec.BUFFER_FLAG_CODEC_CONFIG)
                } else if (codec != null) {
                    submit(payload, ptsAndFlags and PTS_MASK, 0)
                }
            }
        } catch (e: Exception) {
            if (running) {
                Log.e(TAG, "Video stream ended", e)
                onError("Video stream ended: ${e.message}")
            }
        } finally {
            releaseCodec()
        }
    }

    private fun recreateCodec() {
        releaseCodec()

        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, videoWidth, videoHeight)
        val newCodec = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        newCodec.configure(format, surface, null, 0)
        newCodec.start()
        codec = newCodec

        outputThread = Thread({ drainOutput(newCodec) }, "localdex-video-out").also { it.start() }
    }

    private fun submit(data: ByteArray, ptsUs: Long, flags: Int) {
        val currentCodec = codec ?: return
        while (running) {
            val index = currentCodec.dequeueInputBuffer(10_000)
            if (index >= 0) {
                val buffer = currentCodec.getInputBuffer(index) ?: continue
                buffer.clear()
                buffer.put(data)
                currentCodec.queueInputBuffer(index, 0, data.size, ptsUs, flags)
                return
            }
        }
    }

    private fun drainOutput(codec: MediaCodec) {
        val info = MediaCodec.BufferInfo()
        try {
            while (running) {
                val index = codec.dequeueOutputBuffer(info, 50_000)
                if (index >= 0) {
                    // Render immediately: the source display is live, latency beats pacing.
                    codec.releaseOutputBuffer(index, renderEnabled)
                }
            }
        } catch (e: IllegalStateException) {
            // Codec was released under us during stop/reconfigure; expected.
        }
    }

    private fun releaseCodec() {
        val oldCodec = codec
        codec = null
        outputThread?.interrupt()
        outputThread = null
        if (oldCodec != null) {
            try {
                oldCodec.stop()
            } catch (e: IllegalStateException) {
                // Already stopped.
            }
            oldCodec.release()
        }
    }

    private fun readInt(data: ByteArray, offset: Int): Int {
        return ((data[offset].toInt() and 0xff) shl 24) or
            ((data[offset + 1].toInt() and 0xff) shl 16) or
            ((data[offset + 2].toInt() and 0xff) shl 8) or
            (data[offset + 3].toInt() and 0xff)
    }

    private fun readLong(data: ByteArray, offset: Int): Long {
        return (readInt(data, offset).toLong() shl 32) or
            (readInt(data, offset + 4).toLong() and 0xffffffffL)
    }
}
