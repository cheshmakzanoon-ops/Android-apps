package com.agency.stickerjet.platform

import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.os.Build
import androidx.annotation.RequiresApi

/**
 * What this device reports about H.264 (AVC) decoding, the codec in most phone MP4 files.
 *
 * It is a capability report, not a guess: each field comes from MediaCodecList. The probe never
 * opens a codec and never touches the network. A device with no decoder is NotSupported. A device
 * whose probe threw is ProbeFailed, which is unknown and must never be read as supported.
 */
sealed interface H264DecodeCapability {
    data class Supported(
        /** Names of the AVC decoders the platform lists to apps, in platform order. */
        val decoderNames: List<String>,
        /** True when a listed AVC decoder is hardware accelerated. Null before API 29, which cannot tell. */
        val hardwareDecoderPresent: Boolean?,
        /** True when some AVC decoder advertises 1920x1080 in either orientation. */
        val accepts1080p: Boolean,
    ) : H264DecodeCapability

    /** The platform lists no AVC decoder. Import of H.264 clips will fail on this device. */
    data object NotSupported : H264DecodeCapability

    /** The codec list could not be read. Show it as unknown, not as supported. */
    data class ProbeFailed(val reason: String) : H264DecodeCapability
}

/** Reads the device's H.264 decode capability from MediaCodecList. Pure query, no side effects. */
object H264Capability {
    private const val MIME_AVC = "video/avc"

    fun probe(): H264DecodeCapability = try {
        val avcDecoders = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.filter { info ->
            !info.isEncoder && info.supportedTypes.any { type -> type.equals(MIME_AVC, ignoreCase = true) }
        }
        if (avcDecoders.isEmpty()) {
            H264DecodeCapability.NotSupported
        } else {
            H264DecodeCapability.Supported(
                decoderNames = avcDecoders.map { it.name },
                hardwareDecoderPresent = hardwareFlag(avcDecoders),
                accepts1080p = avcDecoders.any { accepts1080p(it) },
            )
        }
    } catch (e: RuntimeException) {
        H264DecodeCapability.ProbeFailed(e.javaClass.simpleName)
    }

    private fun hardwareFlag(decoders: List<MediaCodecInfo>): Boolean? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) anyHardwareAccelerated(decoders) else null

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun anyHardwareAccelerated(decoders: List<MediaCodecInfo>): Boolean =
        decoders.any { it.isHardwareAccelerated }

    private fun accepts1080p(info: MediaCodecInfo): Boolean {
        val video = info.getCapabilitiesForType(MIME_AVC).videoCapabilities ?: return false
        return video.isSizeSupported(1920, 1080) || video.isSizeSupported(1080, 1920)
    }
}
