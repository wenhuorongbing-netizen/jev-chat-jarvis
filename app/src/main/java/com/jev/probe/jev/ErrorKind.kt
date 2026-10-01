package com.jev.probe.jev

/**
 * The seven ways a model call fails (contracts/jev/v1/retry_policy.json). The class alone decides
 * whether the call is tried again and how many attempts in total it may take.
 */
enum class ErrorKind(val id: String, val maxAttempts: Int) {
    AUTH("auth", 1),
    RATE_LIMITED("rate_limited", 3),
    TIMEOUT("timeout", 2),
    UNSUPPORTED("unsupported", 1),
    INVALID_RESPONSE("invalid_response", 1),
    CANCELLED("cancelled", 1),
    TRANSPORT("transport", 3);

    val retries: Boolean get() = maxAttempts > 1

    companion object {
        fun ofStatus(status: Int): ErrorKind = when (status) {
            401, 402, 403 -> AUTH
            408 -> TIMEOUT
            429, 529 -> RATE_LIMITED
            in 500..599 -> TRANSPORT
            in 400..499 -> UNSUPPORTED
            else -> INVALID_RESPONSE
        }

        fun of(t: Throwable): ErrorKind? = when (t) {
            is ApiException -> t.kind
            is InvalidResponseException -> INVALID_RESPONSE
            is InterruptedException, is java.util.concurrent.CancellationException -> CANCELLED
            else -> null  // not a model-call failure we know: never retried
        }
    }
}

/** The provider answered, but the answer is unusable (malformed, empty, a refusal). Text is ours, never the model's. */
class InvalidResponseException(message: String) : IllegalStateException(message)

/**
 * Bounded retry for one model call. [attempt] gets the 1-based attempt number. A failure is retried
 * only when its [ErrorKind] retries, its attempt cap is not used up, and [isLive] still says the
 * generation is wanted — asked once before the pause and once after it, so a generation that went
 * stale (newer message, cancelled, panel gone) never calls the provider again.
 */
object Retry {
    fun <T> run(
        isLive: () -> Boolean,
        pause: (attemptsSoFar: Int) -> Unit,
        attempt: (Int) -> T
    ): T {
        var calls = 0
        while (true) {
            calls++
            val failure = try {
                return attempt(calls)
            } catch (e: Exception) { e }
            val kind = ErrorKind.of(failure)
            if (kind == null || !kind.retries || calls >= kind.maxAttempts || !isLive()) throw failure
            try { pause(calls) } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                throw failure
            }
            if (!isLive()) throw failure
        }
    }
}
