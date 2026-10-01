package com.jev.probe.jev

import com.jev.probe.core.EphemeralImage
import com.jev.probe.core.ImageUse

/**
 * Whether one reply request carries its picture, and what happens when the provider objects.
 *
 * Order of the questions: no picture (or one already released) → plain text request; the owner
 * switch is off → plain text request and the provider is not asked anything about images; else the
 * model's declared capability goes through [ModelCapabilities.decideImage]. Only an UNKNOWN
 * capability falls back to text, and only when the provider's structured error code says it takes no
 * images ([ImageRejection]; a bare 4xx, auth, rate-limit, timeout or transport failure is not a statement
 * about images) and the generation is still wanted. Once refused, the generation stays text-only: the
 * retry policy lives inside each HTTP call, so a transient failure of the text request never re-uploads
 * the picture. A model that declared it takes images is never second-guessed.
 */
internal object ImageReply {

    class Outcome<T>(val value: T, val use: ImageUse)

    fun <T> run(
        image: EphemeralImage?,
        ownerEnabled: Boolean,
        capability: () -> CapState,
        isLive: () -> Boolean,
        call: (EphemeralImage?) -> T
    ): Outcome<T> {
        if (image == null || image.released) return Outcome(call(null), ImageUse.NONE)
        if (!ownerEnabled) return Outcome(call(null), ImageUse.TEXT_DISABLED)

        val decision = ModelCapabilities.decideImage(capability(), ownerEnabled = true, clientCanAttach = true)
        if (!decision.attach) return Outcome(call(null), ImageUse.TEXT_UNSUPPORTED)
        try {
            return Outcome(call(image), ImageUse.ATTACHED)
        } catch (e: Exception) {
            val refused = (e as? ApiException)?.imageRejected == true
            if (!(decision.fallBackToText && refused && isLive())) throw e
        }
        return Outcome(call(null), ImageUse.FELL_BACK)
    }
}
