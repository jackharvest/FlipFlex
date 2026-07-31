package com.github.jackharvest.flipflex.dl

import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile

/**
 * Write the seek index that Plex's transcoder cannot.
 *
 * ## Why a downloaded episode could not be seeked
 *
 * `start.mkv` with `protocol=http` is muxed **live**, straight into the socket.
 * A live Matroska muxer cannot go back and fill anything in, so what lands has
 * a Segment of unknown size and, crucially, **no Cues element** -- Cues is the
 * cluster index, and a muxer can only write it once it knows where every
 * cluster ended up.
 *
 * ExoPlayer's `MatroskaExtractor` builds its seek map from Cues and from
 * nothing else. With none present it publishes `SeekMap.Unseekable`, and every
 * `seekTo` then collapses to the only seek point there is: zero. Measured on
 * the handset -- six presses of the forward key on a downloaded episode left
 * the clock reading 0:01 and restarted the picture each time. That is not a
 * seek that does nothing, it is a seek that goes backwards.
 *
 * ## Why the file is rewritten rather than appended to
 *
 * Cues has to be encountered *before* the clusters it indexes. The extractor
 * parses forwards and never follows the SeekHead, so an index bolted onto the
 * end of the file is an index it reads after it no longer needs it. There is a
 * 98-byte Void in the header, which is four cue points' worth. So the file is
 * rewritten with the index spliced in ahead of the first cluster.
 *
 * That is cheap in the only way that matters here: the clusters are copied
 * byte for byte, so nothing is re-encoded, nothing is re-timed, and a failure
 * leaves the original untouched.
 *
 * ## The one arithmetic trap
 *
 * `CueClusterPosition` is an offset from the start of the Segment's data, and
 * inserting the index *moves every cluster*. So the positions have to be
 * written with the length of the index already added -- and the length depends
 * on the positions. The circle is broken by writing both `CueTime` and
 * `CueClusterPosition` as **fixed eight-byte** integers, which makes the
 * encoded size independent of the values: build once to measure, build again
 * with the shift applied, and assert the two agree.
 */
object MatroskaIndex {

    private const val TAG = "FlipFlex/mkv"

    private const val ID_CLUSTER = 0x1F43B675L
    private const val ID_TRACKS = 0x1654AE6BL
    private const val ID_TRACK_ENTRY = 0xAEL
    private const val ID_TRACK_NUMBER = 0xD7L
    private const val ID_TRACK_TYPE = 0x83L
    private const val ID_TIMECODE = 0xE7L
    private const val ID_CRC32 = 0xBFL
    private const val ID_VOID = 0xECL

    private const val TRACK_TYPE_VIDEO = 1L

    /**
     * One cue point per five seconds of runtime, not per cluster.
     *
     * The transcoder emits a cluster roughly every 0.8 s, so indexing all of
     * them costs about 47 kB on a 23-minute episode to buy a seek precision
     * finer than the 15 s the D-pad steps in. Five seconds is well under one
     * press and an order of magnitude smaller.
     */
    private const val CUE_STEP_MS = 5_000L

    /** A cluster worth indexing: when it starts, and where it is. */
    private data class Cue(val timeMs: Long, val position: Long)

    /**
     * Rewrite [src] into [dst] with a Cues element. Returns false and leaves
     * [dst] absent if anything about the file is not what is expected -- the
     * caller keeps the original, which plays perfectly well and merely cannot
     * be seeked.
     */
    fun addCues(src: File, dst: File): Boolean {
        return try {
            RandomAccessFile(src, "r").use { f ->
                val scan = scan(f) ?: return false
                if (scan.cues.size < 2) {
                    Log.w(TAG, "only ${scan.cues.size} cue points, not worth an index")
                    return false
                }
                // Twice: once to learn how long the index is, once with every
                // position moved by exactly that much. Fixed-width fields are
                // what make the second pass the same size as the first.
                val measured = buildCues(scan.cues, scan.videoTrack, 0L)
                val blob = buildCues(scan.cues, scan.videoTrack, measured.size.toLong())
                check(blob.size == measured.size) { "cue index changed size between passes" }

                FileOutputStream(dst).use { out ->
                    f.seek(0)
                    copy(f, out, scan.firstCluster)
                    out.write(blob)
                    copy(f, out, f.length() - scan.firstCluster)
                }
                Log.i(
                    TAG,
                    "indexed ${src.name}: ${scan.cues.size} cue points, " +
                        "${blob.size} bytes spliced in at ${scan.firstCluster}",
                )
                true
            }
        } catch (e: Exception) {
            Log.w(TAG, "could not index ${src.name}: ${e.javaClass.simpleName}: ${e.message}")
            dst.delete()
            false
        }
    }

    // ---- walking the source ------------------------------------------------

    private class Scan(
        val firstCluster: Long,
        val videoTrack: Int,
        val cues: List<Cue>,
    )

    /**
     * One linear pass over the top level of the Segment.
     *
     * Everything before the first cluster is header -- SeekHead, Info, Tracks
     * and Tags all sit there in what the transcoder produces -- and everything
     * from the first cluster to the end of the file is clusters. That is what
     * makes the splice a single insertion rather than a re-mux.
     */
    private fun scan(f: RandomAccessFile): Scan? {
        readId(f) ?: return null                       // EBML header
        val headerSize = readSize(f) ?: return null
        f.seek(f.filePointer + headerSize.value)

        val segment = readId(f) ?: return null
        readSize(f) ?: return null                     // unknown, and that is the point
        if (segment != 0x18538067L) {
            Log.w(TAG, "not a Matroska segment: ${segment.toString(16)}")
            return null
        }
        val segmentData = f.filePointer

        var firstCluster = -1L
        var videoTrack = 1
        val cues = ArrayList<Cue>()
        var lastCueMs = Long.MIN_VALUE

        while (f.filePointer < f.length()) {
            val at = f.filePointer
            val id = readId(f) ?: break
            val size = readSize(f) ?: break
            // An unknown-size element at the top level would mean the walk
            // cannot find where the next one begins. The transcoder only does
            // that for the Segment itself, which is already behind us.
            if (size.unknown) {
                Log.w(TAG, "unknown-size element ${id.toString(16)} at $at")
                return null
            }
            val body = f.filePointer

            when (id) {
                ID_CLUSTER -> {
                    if (firstCluster < 0) firstCluster = at
                    val tc = clusterTime(f, body + size.value)
                    if (tc != null && (cues.isEmpty() || tc - lastCueMs >= CUE_STEP_MS)) {
                        cues += Cue(tc, at - segmentData)
                        lastCueMs = tc
                    }
                }
                ID_TRACKS -> videoTrack = videoTrackNumber(f, body + size.value) ?: videoTrack
                0x1C53BB6BL -> {
                    // Already indexed. Nothing to do, and re-indexing would
                    // leave two Cues elements in one file.
                    Log.i(TAG, "file already carries a Cues element")
                    return null
                }
            }
            f.seek(body + size.value)
        }

        if (firstCluster < 0) return null
        return Scan(firstCluster, videoTrack, cues)
    }

    /**
     * The cluster's Timecode, in the Segment's own ticks.
     *
     * Timecode is normally the first child but is not required to be, and the
     * transcoder puts a CRC-32 in front of it. Both of those are skipped; a
     * SimpleBlock means the timecode was not there and the cluster is not
     * indexable.
     */
    private fun clusterTime(f: RandomAccessFile, end: Long): Long? {
        while (f.filePointer < end) {
            val id = readId(f) ?: return null
            val size = readSize(f) ?: return null
            if (id == ID_TIMECODE) return readUInt(f, size.value.toInt())
            if (id != ID_CRC32 && id != ID_VOID) return null
            f.seek(f.filePointer + size.value)
        }
        return null
    }

    /**
     * The track number Cues should point at.
     *
     * It is nearly always 1, but "nearly always" is how a file with the audio
     * declared first ends up with an index against the wrong track -- which a
     * player is entitled to ignore entirely.
     */
    private fun videoTrackNumber(f: RandomAccessFile, end: Long): Int? {
        var found: Int? = null
        while (f.filePointer < end) {
            val id = readId(f) ?: return found
            val size = readSize(f) ?: return found
            val body = f.filePointer
            if (id == ID_TRACK_ENTRY) {
                var number: Int? = null
                var type: Long? = null
                while (f.filePointer < body + size.value) {
                    val cid = readId(f) ?: break
                    val csize = readSize(f) ?: break
                    when (cid) {
                        ID_TRACK_NUMBER -> number = readUInt(f, csize.value.toInt()).toInt()
                        ID_TRACK_TYPE -> type = readUInt(f, csize.value.toInt())
                        else -> f.seek(f.filePointer + csize.value)
                    }
                }
                if (type == TRACK_TYPE_VIDEO && number != null) found = number
            }
            f.seek(body + size.value)
        }
        return found
    }

    // ---- writing the index -------------------------------------------------

    /**
     * A Cues element, with every position moved on by [shift].
     *
     * CueTime and CueClusterPosition are written as eight-byte integers even
     * when the value would fit in one, so that the encoded length depends only
     * on the number of cue points. That is what lets the caller measure the
     * index before it knows the positions, and the positions before it writes
     * them -- see the class comment.
     */
    private fun buildCues(cues: List<Cue>, videoTrack: Int, shift: Long): ByteArray {
        val body = java.io.ByteArrayOutputStream(cues.size * 32)
        cues.forEach { cue ->
            // CueTrackPositions: CueTrack + CueClusterPosition
            val positions = java.io.ByteArrayOutputStream(16)
            positions.write(0xF7)                       // CueTrack
            positions.write(0x81)
            positions.write(videoTrack)
            positions.write(0xF1)                       // CueClusterPosition
            positions.write(0x88)
            positions.write(eightBytes(cue.position + shift))
            val p = positions.toByteArray()

            val point = java.io.ByteArrayOutputStream(32)
            point.write(0xB3)                           // CueTime
            point.write(0x88)
            point.write(eightBytes(cue.timeMs))
            point.write(0xB7)                           // CueTrackPositions
            point.write(0x80 or p.size)
            point.write(p)
            val pt = point.toByteArray()

            body.write(0xBB)                            // CuePoint
            body.write(0x80 or pt.size)
            body.write(pt)
        }
        val content = body.toByteArray()

        val out = java.io.ByteArrayOutputStream(content.size + 16)
        out.write(byteArrayOf(0x1C, 0x53, 0xBB.toByte(), 0x6B))   // Cues
        // An eight-byte length marker, again so the header is a constant size.
        out.write(0x01)
        out.write(eightBytes(content.size.toLong()), 1, 7)
        out.write(content)
        return out.toByteArray()
    }

    private fun eightBytes(v: Long): ByteArray = ByteArray(8) { i ->
        ((v ushr (8 * (7 - i))) and 0xFF).toByte()
    }

    // ---- EBML primitives ---------------------------------------------------

    /** An element id, marker bits and all -- that is how ids are compared. */
    private fun readId(f: RandomAccessFile): Long? {
        val first = f.read()
        if (first < 0) return null
        val length = (0 until 4).firstOrNull { first and (0x80 shr it) != 0 }?.plus(1) ?: return null
        var value = first.toLong()
        repeat(length - 1) {
            val b = f.read()
            if (b < 0) return null
            value = (value shl 8) or b.toLong()
        }
        return value
    }

    private class Size(val value: Long, val unknown: Boolean)

    private fun readSize(f: RandomAccessFile): Size? {
        val first = f.read()
        if (first < 0) return null
        val length = (0 until 8).firstOrNull { first and (0x80 shr it) != 0 }?.plus(1) ?: return null
        var value = (first and (0xFF shr length)).toLong()
        repeat(length - 1) {
            val b = f.read()
            if (b < 0) return null
            value = (value shl 8) or b.toLong()
        }
        return Size(value, value == (1L shl (7 * length)) - 1)
    }

    private fun readUInt(f: RandomAccessFile, bytes: Int): Long {
        var value = 0L
        repeat(bytes) {
            val b = f.read()
            if (b < 0) return value
            value = (value shl 8) or b.toLong()
        }
        return value
    }

    private fun copy(f: RandomAccessFile, out: FileOutputStream, bytes: Long) {
        val buf = ByteArray(256 * 1024)
        var left = bytes
        while (left > 0) {
            val n = f.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
            if (n <= 0) break
            out.write(buf, 0, n)
            left -= n
        }
    }
}
