package dev.atsushieno.uapmd.cmp.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.atsushieno.uapmd.GraphBusType
import dev.atsushieno.uapmd.GraphConnection
import dev.atsushieno.uapmd.GraphEndpoint
import dev.atsushieno.uapmd.GraphEndpointType
import dev.atsushieno.uapmd.cmp.UapmdHost
import dev.atsushieno.uapmd.cmp.backgroundDispatcher
import dev.atsushieno.uapmd.cmp.nodegraph.BusType
import dev.atsushieno.uapmd.cmp.nodegraph.GraphLink
import dev.atsushieno.uapmd.cmp.nodegraph.GraphNode
import dev.atsushieno.uapmd.cmp.nodegraph.GraphPin
import dev.atsushieno.uapmd.cmp.nodegraph.NodeGraphEditor
import kotlinx.coroutines.withContext

/**
 * uapmd-app's per-track Plugin Graph Editor, on the ported NodeGraph canvas.
 *
 * A track starts on the simple linear chain; editing requires switching it to
 * the editable graph first, which is what "Use Editor Graph" does.
 *
 * Pins are keyed exactly as `PluginGraphEditor::pinKeyForDescriptor` keys them
 * (:172) — by node id, bus type, direction and bus index. Each part is load-bearing:
 *
 *  - **node id**, not instance id, because `instance_id` is -1 for the graph's own
 *    two endpoints *and* for every built-in node, so it cannot tell them apart;
 *  - **bus type separately from bus index**, because a connection carries its bus
 *    type on the connection while both bus types number their buses from 0, so an
 *    event connection on bus 0 and an audio connection on bus 0 are different pins.
 *
 * Getting either wrong mints a pin id no node owns, and every link then points at
 * nothing — which is exactly how this window used to render: all nodes, no edges.
 *
 * The C API reports the graph as the C++ API models it — every bus, with its own
 * `enabled` flag — so which buses earn a pin is decided below rather than upstream.
 */
@Composable
fun TrackGraphEditor(host: UapmdHost, trackIndex: Int) {
    var revision by remember { mutableStateOf(0) }
    var status by remember { mutableStateOf<String?>(null) }

    // Opening the editor converts the track to the editable DAG, as uapmd-app does
    // before it ever shows the window (`PluginGraphEditor::showTrack`, :131). Without
    // this the window opens on the simple linear chain, which reports no connections
    // at all, so every node renders unconnected until the user thinks to press the
    // button. Off the UI thread: this migrates the track's graph.
    LaunchedEffect(trackIndex) {
        val ok = withContext(backgroundDispatcher()) {
            host.model.ensureTrackUsesEditorGraph(trackIndex)
        }
        if (!ok) status = "Could not switch this track to the editor graph."
        revision++
    }

    val connections = remember(trackIndex, revision) { host.model.getTrackGraphConnections(trackIndex) }
    val graph = remember(trackIndex, revision) { host.model.getTrackGraphNodes(trackIndex) }

    /**
     * A pin's full identity. The canvas wants flat ints, so these are interned into
     * ids; the descriptor is kept so a new link can be turned back into endpoints.
     */
    data class PinDescriptor(
        val nodeId: String,
        val endpointType: GraphEndpointType,
        val instanceId: Int,
        val busType: GraphBusType,
        val isInput: Boolean,
        val busIndex: UInt
    ) {
        fun toEndpoint() = GraphEndpoint(endpointType, nodeId, instanceId, busIndex)
    }

    val pins = remember(connections, graph) { mutableMapOf<PinDescriptor, Int>() }
    val descriptors = remember(connections, graph) { mutableMapOf<Int, PinDescriptor>() }

    fun pinId(d: PinDescriptor): Int = pins.getOrPut(d) {
        (pins.size + 1).also { descriptors[it] = d }
    }

    // Node ids for the canvas are likewise interned, since it keys nodes by Int.
    val nodeIds = remember(connections, graph) { mutableMapOf<String, Int>() }
    fun nodeId(key: String): Int = nodeIds.getOrPut(key) { nodeIds.size + 1 }

    val nodes = remember(connections, graph) {
        val list = mutableListOf<GraphNode>()

        fun pinsFor(
            nodeKey: String,
            endpointType: GraphEndpointType,
            instanceId: Int,
            audioCount: UInt,
            eventCount: UInt,
            isInput: Boolean
        ): List<GraphPin> {
            val out = mutableListOf<GraphPin>()
            // Event pins first on the input side, last on the output side: the
            // order uapmd-app draws them in (PluginGraphEditor.cpp:353-415).
            if (isInput) repeat(eventCount.toInt()) { bus ->
                out += GraphPin(
                    pinId(PinDescriptor(nodeKey, endpointType, instanceId, GraphBusType.Event, true, bus.toUInt())),
                    "Event In $bus", true, BusType.Event
                )
            }
            repeat(audioCount.toInt()) { bus ->
                out += GraphPin(
                    pinId(PinDescriptor(nodeKey, endpointType, instanceId, GraphBusType.Audio, isInput, bus.toUInt())),
                    if (isInput) "Audio In $bus" else "Audio Out $bus", isInput
                )
            }
            if (!isInput) repeat(eventCount.toInt()) { bus ->
                out += GraphPin(
                    pinId(PinDescriptor(nodeKey, endpointType, instanceId, GraphBusType.Event, false, bus.toUInt())),
                    "Event Out $bus", false, BusType.Event
                )
            }
            return out
        }

        // The graph's own two endpoints. Their direction is inverted: what the graph
        // takes in is what the nodes inside it read from, so Graph Input has outputs.
        list += GraphNode(
            id = nodeId("graph:input"), label = "Graph Input",
            inputs = emptyList(),
            outputs = pinsFor(
                "graph:input", GraphEndpointType.GraphInput, -1,
                graph.graphAudioInputBusCount, graph.graphEventInputBusCount, isInput = false
            )
        )
        graph.nodes.forEach { n ->
            val key = n.nodeId.ifEmpty { if (n.instanceId >= 0) "plugin:${n.instanceId}" else "" }
            // uapmd-app's pin rules, applied here because they are presentation and
            // not something the C API should impose (PluginGraphEditor.cpp:353-450):
            // a hosted plugin shows a pin per *enabled* audio bus and at most one
            // event pin per direction, while a node with no buses of its own — a
            // built-in such as the track's gain — takes the graph's own layout.
            val audioIn: UInt
            val audioOut: UInt
            val eventIn: UInt
            val eventOut: UInt
            if (n.hasAudioBuses) {
                audioIn = n.audioInputBuses.count { it.enabled }.toUInt()
                audioOut = n.audioOutputBuses.count { it.enabled }.toUInt()
                eventIn = if (n.hasEventInputs) 1u else 0u
                eventOut = if (n.hasEventOutputs) 1u else 0u
            } else {
                audioIn = graph.graphAudioInputBusCount
                audioOut = graph.graphAudioOutputBusCount
                eventIn = graph.graphEventInputBusCount
                eventOut = graph.graphEventOutputBusCount
            }
            list += GraphNode(
                id = nodeId(key), label = n.displayName.ifEmpty { key },
                inputs = pinsFor(key, GraphEndpointType.Plugin, n.instanceId, audioIn, eventIn, isInput = true),
                outputs = pinsFor(key, GraphEndpointType.Plugin, n.instanceId, audioOut, eventOut, isInput = false)
            )
        }
        list += GraphNode(
            id = nodeId("graph:output"), label = "Graph Output",
            inputs = pinsFor(
                "graph:output", GraphEndpointType.GraphOutput, -1,
                graph.graphAudioOutputBusCount, graph.graphEventOutputBusCount, isInput = true
            ),
            outputs = emptyList()
        )
        list
    }

    // Links are built after the nodes so that every pin a connection names has
    // already been interned; a connection that still misses one refers to a bus the
    // graph no longer reports, and is dropped rather than drawn to a phantom pin.
    val links = remember(connections, nodes) {
        connections.connections.mapIndexedNotNull { i, c ->
            val source = PinDescriptor(
                c.source.resolvedNodeId, c.source.type, c.source.instanceId,
                c.busType, false, c.source.busIndex
            )
            val target = PinDescriptor(
                c.target.resolvedNodeId, c.target.type, c.target.instanceId,
                c.busType, true, c.target.busIndex
            )
            val s = pins[source] ?: return@mapIndexedNotNull null
            val t = pins[target] ?: return@mapIndexedNotNull null
            GraphLink(i + 1, s, t)
        }
    }

    Column(Modifier.fillMaxSize()) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Button(onClick = {
                status = if (host.model.ensureTrackUsesEditorGraph(trackIndex)) null
                else "Could not switch this track to the editor graph."
                revision++
            }) { Text("Use Editor Graph") }
            Button(onClick = {
                status = if (host.model.revertTrackToSimpleGraph(trackIndex)) null
                else "Could not revert to the simple graph."
                revision++
            }) { Text("Revert to Simple Graph") }
        }
        Text(
            "track $trackIndex · ${graph.nodes.size} node(s) · ${connections.connections.size} connection(s)" +
                (connections.error?.let { " · $it" } ?: "") + (graph.error?.let { " · $it" } ?: ""),
            style = MaterialTheme.typography.bodySmall
        )
        status?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }

        NodeGraphEditor(
            nodes = nodes,
            links = links,
            modifier = Modifier.fillMaxSize(),
            onLinkCreated = { sourcePinId, targetPinId ->
                var source = descriptors[sourcePinId]
                var target = descriptors[targetPinId]
                // Dragged output-to-input or input-to-output; uapmd-app swaps
                // rather than refuse (PluginGraphEditor.cpp:494).
                if (source != null && target != null && source.isInput && !target.isInput) {
                    val t = source; source = target; target = t
                }
                when {
                    source == null || target == null -> Unit
                    source.isInput || !target.isInput -> Unit
                    source.busType != target.busType ->
                        status = "An audio pin cannot connect to an event pin."
                    else -> {
                        val r = host.model.connectTrackGraph(
                            trackIndex,
                            GraphConnection(0L, source.busType, source.toEndpoint(), target.toEndpoint())
                        )
                        status = r.error
                        revision++
                    }
                }
            },
            onLinkDeleted = { linkId ->
                connections.connections.getOrNull(linkId - 1)?.let { c ->
                    val r = host.model.disconnectTrackGraphConnection(trackIndex, c.id)
                    status = r.error
                    revision++
                }
            }
        )
    }
}
