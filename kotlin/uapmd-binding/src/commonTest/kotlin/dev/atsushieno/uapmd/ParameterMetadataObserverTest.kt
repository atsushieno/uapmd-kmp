package dev.atsushieno.uapmd

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ParameterMetadataObserverTest {
    private class FakeInstance : PluginInstance {
        var metadata = listOf(parameter("Blank"))
        private var captured = emptyList<ParameterMetadata>()
        var reads = 0
        override val parameterCount: UInt get() {
            reads++
            captured = metadata.toList()
            return captured.size.toUInt()
        }
        override fun getParameterMetadata(index: UInt) = captured.getOrNull(index.toInt())
        override val displayName = "Test"
        override val formatName = "Test"
        override val pluginId = "Test"
        override var bypassed = false
        override val latencyInSamples = 0u
        override val tailLengthInSeconds = 0.0
        override val requiresReplacingProcess = false
        override fun startProcessing() = 0
        override fun stopProcessing() = 0
        override fun getParameterValue(index: Int) = 0.0
        override fun setParameterValue(index: Int, value: Double) {}
        override fun getParameterValueString(index: Int, value: Double) = ""
        override fun setPerNoteControllerValue(note: UByte, index: UByte, value: Double) {}
        override fun getPerNoteControllerValueString(note: UByte, index: UByte, value: Double) = ""
        override val presetCount = 0u
        override fun getPresetMetadata(index: UInt): PresetMetadata? = null
        override fun loadPreset(presetIndex: Int) {}
        override fun saveStateSync() = byteArrayOf()
        override fun loadStateSync(data: ByteArray) {}
        override fun requestState(ctx: StateContextType, includeUiState: Boolean, callback: (ByteArray?, String?) -> Unit) {}
        override fun loadState(data: ByteArray, ctx: StateContextType, includeUiState: Boolean, callback: (String?) -> Unit) {}
        override val uiCapabilities = PluginUiCapabilities(false)
        override fun createUiPresentation(request: PluginUiPresentationRequest): PluginUiPresentation? = null
    }
    private class FakeNode(override val instance: FakeInstance = FakeInstance()) : PluginNode {
        override val instanceId = 1
        private var nextId = 0L
        val listeners = mutableMapOf<Long, () -> Unit>()
        var removed = 0
        override fun scheduleEvents(timestamp: Long, events: ByteArray) = true
        override fun sendAllNotesOff() {}
        override fun setEventOutputObserver(observer: ((UIntArray) -> Unit)?) {}
        override fun addParameterUpdateListener(listener: ParameterUpdateListener) = 0L
        override fun removeParameterUpdateListener(listenerId: Long) {}
        override fun addParameterMetadataRefreshListener(listener: () -> Unit): Long {
            val id = ++nextId
            listeners[id] = listener
            return id
        }
        override fun removeParameterMetadataRefreshListener(listenerId: Long) {
            listeners.remove(listenerId)
            removed++
        }
        fun changed() { listeners.values.toList().forEach { it() } }
    }
    companion object {
        private fun parameter(name: String) = ParameterMetadata(0u, "9", name, "", 0.5, 0.0, 1.0,
            true, false, false, listOf(ParameterNamedValue(0.0, "Off")))
    }
    @Test fun replayAndEventsCaptureBeforeUiDispatchAndCoalesce() {
        val node = FakeNode()
        val tasks = ArrayDeque<() -> Unit>()
        val received = mutableListOf<ParameterMetadataSnapshot>()
        val registration = observeParameterMetadata({ node }, { tasks.addLast(it) }, true, { received.add(it) })
        node.instance.metadata = listOf(parameter("Drive"))
        node.changed()
        node.instance.metadata = listOf(parameter("Tone"))
        node.changed()
        assertEquals(1, tasks.size)
        // No queued getter should see this unnotified mutation.
        node.instance.metadata = listOf(parameter("Unnotified"))
        tasks.removeFirst()()
        assertEquals("Tone", received.single().parameters.single().name)
        assertEquals(3, node.instance.reads)
        registration.close()
        registration.close()
        assertEquals(1, node.removed)
    }
    @Test fun closeSuppressesQueuedAndLateCallbacksEvenAfterNodeDisappears() {
        val node = FakeNode()
        var current: PluginNode? = node
        val tasks = ArrayDeque<() -> Unit>()
        var deliveries = 0
        val registration = observeParameterMetadata({ current }, { tasks.addLast(it) }, true, { deliveries++ })
        val lateCallback = node.listeners.values.single()
        current = null
        registration.close()
        tasks.removeFirst()()
        lateCallback()
        assertEquals(0, deliveries)
        assertEquals(1, node.instance.reads)
        assertEquals(0, node.removed)
    }
    @Test fun subscribersAreIndependentAndEmptyLayoutsAreDelivered() {
        val node = FakeNode()
        val first = mutableListOf<ParameterMetadataSnapshot>()
        val second = mutableListOf<ParameterMetadataSnapshot>()
        val one = observeParameterMetadata({ node }, { it() }, false, { first.add(it) })
        val two = observeParameterMetadata({ node }, { it() }, true, { second.add(it) })
        assertTrue(first.isEmpty())
        assertEquals("Blank", second.single().parameters.single().name)
        node.instance.metadata = emptyList()
        node.changed()
        assertTrue(first.single().parameters.isEmpty())
        assertTrue(second.last().parameters.isEmpty())
        one.close()
        two.close()
    }
    @Test fun aFailingSubscriberDoesNotPreventFutureDelivery() {
        val node = FakeNode()
        var errors = 0
        var calls = 0
        val registration = observeParameterMetadata({ node }, { it() }, false, {
            calls++
            if (calls == 1) error("Failure")
        }, { errors++ })
        node.changed()
        node.changed()
        assertEquals(1, errors)
        assertEquals(2, calls)
        registration.close()
    }
}
