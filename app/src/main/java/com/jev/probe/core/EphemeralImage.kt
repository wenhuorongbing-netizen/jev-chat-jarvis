package com.jev.probe.core

/**
 * The cropped picture for one reply session, as JPEG bytes in memory. It is never written
 * anywhere, never logged, and never part of a cache or a history: [release] wipes it and it is
 * unusable afterwards. A released image is simply "no image" to everything downstream.
 */
class EphemeralImage(jpeg: ByteArray) {
    private var bytes: ByteArray? = jpeg  // guarded by this: a worker may be encoding while the session ends

    val released: Boolean get() = synchronized(this) { bytes == null }

    /** Encoded size in bytes (0 once released), for logging geometry without logging content. */
    val size: Int get() = synchronized(this) { bytes?.size ?: 0 }

    /** The `data:` URL body; throws once released, so a stale reference cannot send anything. */
    fun base64(): String = synchronized(this) {
        val b = bytes ?: throw IllegalStateException("image released")
        java.util.Base64.getEncoder().encodeToString(b)
    }

    fun release() = synchronized(this) {
        bytes?.fill(0)
        bytes = null
    }
}

/**
 * The image that belongs to one reply session: the conversation it was taken in and what the
 * screen showed then ([ConversationRef.key] + [ChatSnapshot.signature]). A reroll asks the same
 * state again and gets the same picture; any other state (a newer message, another chat) gets
 * nothing and ends the session, so the picture cannot reach a request it was not taken for.
 */
class ImageSession(val image: EphemeralImage, private val convKey: String, private val signature: String) {
    fun take(convKey: String, signature: String): EphemeralImage? {
        if (image.released) return null
        if (convKey == this.convKey && signature == this.signature) return image
        image.release()
        return null
    }

    fun release() = image.release()
}

/** What a reply request did with the picture (see [com.jev.probe.jev.ModelCapabilities.decideImage]). */
enum class ImageUse(val note: String) {
    /** There was no picture for this request. */
    NONE(""),
    ATTACHED(""),
    TEXT_DISABLED("图片识别已在设置里关闭，这次只按文字回复"),
    TEXT_UNSUPPORTED("回复模型不支持看图，这次只按文字回复"),
    FELL_BACK("回复模型拒绝了图片，这次只按文字回复")
}
