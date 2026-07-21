package uk.co.riera.talkbalance.ml

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Single-owner mutual-exclusion lease over the shared native engines (the sherpa-onnx `Vad` /
 * `SpeakerEmbeddingExtractor`, which are single-instance / single-owner). A live session and an
 * enrollment must never drive them at the same time; whoever holds the lease owns them until it is
 * released. Pure (no Android types) so the busy/free semantics are fully unit-testable.
 */
class EngineLeaseHolder {
    private val holder = AtomicReference<String?>(null)

    /** Current owner name, or null when free. */
    val owner: String? get() = holder.get()

    /** True while some owner holds the lease. */
    val isHeld: Boolean get() = holder.get() != null

    /**
     * Non-blocking, exclusive acquire. Returns a [Lease] on success, or null if another owner
     * already holds it (the caller then backs off — e.g. enrollment surfaces a typed Busy failure).
     */
    fun tryAcquire(owner: String): Lease? =
        if (holder.compareAndSet(null, owner)) Lease(owner) else null

    /** A held lease. [release] is idempotent and only frees the holder if this lease still owns it. */
    inner class Lease internal constructor(val owner: String) {
        private val released = AtomicBoolean(false)

        fun release() {
            if (released.compareAndSet(false, true)) {
                holder.compareAndSet(owner, null)
            }
        }
    }
}
