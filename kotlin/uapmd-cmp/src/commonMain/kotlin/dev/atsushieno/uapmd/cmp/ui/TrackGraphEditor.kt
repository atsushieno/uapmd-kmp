package dev.atsushieno.uapmd.cmp.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import dev.atsushieno.uapmd.cmp.nodegraph.BusType
import dev.atsushieno.uapmd.cmp.nodegraph.GraphLink
import dev.atsushieno.uapmd.cmp.nodegraph.GraphNode
import dev.atsushieno.uapmd.cmp.nodegraph.GraphPin
import dev.atsushieno.uapmd.cmp.nodegraph.NodeGraphEditor

/**
 * uapmd-app's per-track Plugin Graph Editor, on the ported NodeGraph canvas.
 *
 * A track starts on the simple linear chain; editing requires switching it to
 * the editable graph first, which is what "Use Editor Graph" does.
 */
@Composable
fun TrackGraphEditor(host: UapmdHost, trackIndex: Int) {
    var revision by remember { mutableStateOf(0) }
    var status by remember { mutableStateOf<String?>(null) }

    val result = remember(trackIndex, revision) { host.model.getTrackGraphConnections(trackIndex) }
    val instances = host.trackInstances.getOrNull(trackIndex).orEmpty()

    // Pin ids are synthesised: endpoints are (type, instanceId, busIndex) triples,
    // while the canvas wants flat ints.
    val pinId = remember(result, instances) { mutableMapOf<Triple<Int, Int, Int>, Int>() }
    var nextPin = 1

    fun pinFor(e: GraphEndpoint, isInput: Boolean): Int {
        val key = Triple(e.type.nativeValue * 2 + if (isInput) 1 else 0, e.instanceId, e.busIndex.toInt())
        return pinId.getOrPut(key) { nextPin++ }
    }

    val nodes = remember(result, instances) {
        val list = mutableListOf<GraphNode>()
        list += GraphNode(
            id = -1, label = "Graph Input",
            inputs = emptyList(),
            outputs = listOf(
                GraphPin(pinFor(GraphEndpoint(GraphEndpointType.GraphInput, -1, 0u), false), "Audio Out 0", false),
                GraphPin(pinFor(GraphEndpoint(GraphEndpointType.GraphInput, -1, 1u), false), "Event Out 0", false, BusType.Event)
            )
        )
        instances.forEach { inst ->
            list += GraphNode(
                id = inst.instanceId, label = inst.displayName,
                inputs = listOf(
                    GraphPin(pinFor(GraphEndpoint(GraphEndpointType.Plugin, inst.instanceId, 0u), true), "Audio In 0", true),
                    GraphPin(pinFor(GraphEndpoint(GraphEndpointType.Plugin, inst.instanceId, 1u), true), "Event In 0", true, BusType.Event)
                ),
                outputs = listOf(
                    GraphPin(pinFor(GraphEndpoint(GraphEndpointType.Plugin, inst.instanceId, 0u), false), "Audio Out 0", false),
                    GraphPin(pinFor(GraphEndpoint(GraphEndpointType.Plugin, inst.instanceId, 1u), false), "Event Out 0", false, BusType.Event)
                )
            )
        }
        list += GraphNode(
            id = -2, label = "Graph Output",
            inputs = listOf(
                GraphPin(pinFor(GraphEndpoint(GraphEndpointType.GraphOutput, -2, 0u), true), "Audio In 0", true)
            ),
            outputs = emptyList()
        )
        list
    }

    val links = remember(result, nodes) {
        result.connections.mapIndexed { i, c ->
            GraphLink(i + 1, pinFor(c.source, false), pinFor(c.target, true))
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
            "track $trackIndex · ${result.connections.size} connection(s)" +
                (result.error?.let { " · $it" } ?: ""),
            style = MaterialTheme.typography.bodySmall
        )
        status?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }

        NodeGraphEditor(
            nodes = nodes,
            links = links,
            modifier = Modifier.fillMaxSize(),
            onLinkDeleted = { linkId ->
                result.connections.getOrNull(linkId - 1)?.let { c ->
                    val r = host.model.disconnectTrackGraphConnection(trackIndex, c.id)
                    status = r.error
                    revision++
                }
            }
        )
    }
}
