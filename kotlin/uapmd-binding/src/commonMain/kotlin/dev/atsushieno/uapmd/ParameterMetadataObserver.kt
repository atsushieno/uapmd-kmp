package dev.atsushieno.uapmd

import kotlin.concurrent.atomics.AtomicLong
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi

/** Complete owned metadata; revision is monotonic within this subscription. */
data class ParameterMetadataSnapshot(val revision: Long, val parameters: List<ParameterMetadata>)

/**
 * Snapshot/replay convenience over the existing native graph metadata event.
 * Metadata is copied on the callback thread, before dispatching to the UI. No periodic reads.
 * The dispatcher defaults to the native control thread; UI callers should supply their dispatcher.
 * Close subscriptions before destroying the engine.
 */
fun SequencerEngine.addParameterMetadataChangedListener(
    instanceId: Int,
    dispatch: ((() -> Unit) -> Unit) = { it() },
    replayCurrent: Boolean = true,
    onError: (Throwable) -> Unit = { println("Parameter metadata listener failed: $it") },
    listener: (ParameterMetadataSnapshot) -> Unit
): AutoCloseable = observeParameterMetadata(
    findNode = {
        var found: PluginNode? = null
        for (index in 0u until trackCount) {
            found = getTrack(index).graph.getPluginNode(instanceId)
            if (found != null) break
        }
        found ?: masterTrack.graph.getPluginNode(instanceId)
    }, dispatch, replayCurrent, listener, onError)

internal fun observeParameterMetadata(
    findNode: () -> PluginNode?,
    dispatch: ((() -> Unit) -> Unit),
    replayCurrent: Boolean,
    listener: (ParameterMetadataSnapshot) -> Unit,
    onError: (Throwable) -> Unit = { println("Parameter metadata listener failed: $it") }
): AutoCloseable {
    val observer = MetadataDelivery(dispatch, listener, onError)
    val node = findNode()
    val id = node?.addParameterMetadataRefreshListener { observer.refresh(node.instance) } ?: 0L
    if (replayCurrent && node != null) observer.refresh(node.instance)
    return AutoCloseable {
        // Nodes can disappear before the UI's DisposableEffect is disposed.
        if (observer.close() && id != 0L) findNode()?.removeParameterMetadataRefreshListener(id)
    }
}

@OptIn(ExperimentalAtomicApi::class)
private class MetadataDelivery(
    private val dispatch: ((() -> Unit) -> Unit),
    private val listener: (ParameterMetadataSnapshot) -> Unit,
    private val onError: (Throwable) -> Unit
) {
    private data class State(
        val closed: Boolean = false,
        val revision: Long = 0L,
        val pending: ParameterMetadataSnapshot? = null,
        val scheduled: Boolean = false
    )
    private val state = AtomicReference(State())
    private val revisions = AtomicLong(0L)

    fun refresh(instance: PluginInstance) {
        if (state.load().closed) return
        val revision = revisions.fetchAndAdd(1L) + 1L
        // The C API count captures a thread-local vector. Keep all getters on this thread,
        // with no suspension, so records and named values come from the same capture.
        val count = instance.parameterCount.toInt()
        val parameters = ArrayList<ParameterMetadata>(count)
        for (index in 0 until count) {
            val parameter = instance.getParameterMetadata(index.toUInt()) ?: return
            parameters.add(parameter.copy(namedValues = parameter.namedValues.map { it.copy() }))
        }
        val snapshot = ParameterMetadataSnapshot(revision, parameters.toList())
        while (true) {
            val before = state.load()
            if (before.closed || revision <= before.revision) return
            val after = before.copy(revision = revision, pending = snapshot, scheduled = true)
            if (state.compareAndSet(before, after)) {
                if (!before.scheduled) {
                    try {
                        dispatch(::deliver)
                    } catch (error: Exception) {
                        resetScheduled()
                        onError(error)
                    }
                }
                return
            }
        }
    }

    private fun deliver() {
        while (true) {
            val before = state.load()
            if (before.closed) return
            val snapshot = before.pending
            val after = before.copy(pending = null, scheduled = snapshot != null)
            if (!state.compareAndSet(before, after)) continue
            if (snapshot == null) return
            try {
                listener(snapshot)
            } catch (error: Exception) {
                onError(error)
            }
        }
    }

    private fun resetScheduled() {
        while (true) {
            val before = state.load()
            if (state.compareAndSet(before, before.copy(scheduled = false))) return
        }
    }

    fun close(): Boolean {
        while (true) {
            val before = state.load()
            if (before.closed) return false
            if (state.compareAndSet(before, before.copy(closed = true, pending = null))) return true
        }
    }
}
