package dev.atsushieno.uapmd.cmp.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import dev.atsushieno.uapmd.cmp.UapmdHost
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt
import kotlin.time.TimeSource

internal const val MixerWindowKey = "mixer"
internal val MixerWindowSize = DpSize(860.dp, 600.dp)

private const val MeterFloor = -60.0
private const val GainCeiling = 18.06179973983887
private const val UnityPosition = 0.7

private data class MixerTrack(val id: String, val index: Int) {
    val master get() = index == MasterTrackIndex
    val name get() = if (master) "Master" else "Track ${index + 1}"
}

private data class Peak(val db: Double = MeterFloor, val held: Double = Double.NEGATIVE_INFINITY)
private data class Bus(val channels: List<Peak>, val balance: Double?)
private data class Strip(val gain: Double, val buses: List<Bus>)

private fun sampleDb(linear: Double) =
    if (linear.isFinite() && linear > 0.0) 20 * log10(linear) else Double.NEGATIVE_INFINITY

private fun decibels(db: Double): String =
    if (!db.isFinite()) "−∞" else (if (db < 0) "−" else "") + fixed(abs(db), 1)

private fun gainAt(position: Float): Double {
    if (position <= 0f) return 0.0
    val db = if (position <= UnityPosition)
        MeterFloor * (1 - (position / UnityPosition).pow(2))
    else GainCeiling * (position - UnityPosition) / (1 - UnityPosition)
    return 10.0.pow(db / 20)
}

private fun faderPosition(gain: Double): Float {
    val db = sampleDb(gain).coerceIn(MeterFloor, GainCeiling)
    return (if (db <= 0) UnityPosition * sqrt(1 - db / MeterFloor)
        else UnityPosition + (1 - UnityPosition) * db / GainCeiling).toFloat()
}

private fun channelName(index: Int, count: Int): String =
    if (count == 2) (if (index == 0) "L" else "R") else "${index + 1}"

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun Mixer(host: UapmdHost, windows: FloatingWindowManager) {
    if (host.isShuttingDown) return
    val engine = remember(host) { host.model.sequencer.engine }
    val master = MixerTrack("master_track", MasterTrackIndex)
    val tracks = host.sortedTrackIndices.map { index ->
        MixerTrack(engine.timeline.addresses.trackReferenceId(index) ?: "track_$index", index)
    }
    val currentTracks by rememberUpdatedState(tracks + master)
    val loading by rememberUpdatedState(host.isLoadingProject)
    var strips by remember(host.projectRevision) { mutableStateOf(emptyMap<String, Strip>()) }
    var selectedTrackId by remember(host.projectRevision) { mutableStateOf(master.id) }
    val selectedBuses = remember(host.projectRevision) { mutableStateMapOf<String, Int>() }
    val outputList = rememberLazyListState()
    val stripList = rememberLazyListState()
    val scope = rememberCoroutineScope()

    DisposableEffect(engine) {
        engine.captureMixerPeaks(true)
        onDispose {
            if (!host.isShuttingDown) {
                engine.captureMixerPeaks(false)
                host.endMixerGesture()
            }
        }
    }
    LaunchedEffect(engine, host.projectRevision) {
        var previousTime = TimeSource.Monotonic.markNow()
        while (!host.isShuttingDown) {
            if (!loading) {
                val decay = 48 * previousTime.elapsedNow().inWholeMilliseconds.coerceAtMost(250) / 1000.0
                previousTime = TimeSource.Monotonic.markNow()
                val previous = strips
                val count = engine.trackCount.toInt()
                strips = currentTracks.filter { it.master || it.index < count }.associate { item ->
                    val track = if (item.master) engine.masterTrack else engine.getTrack(item.index.toUInt())
                    val oldBuses = previous[item.id]?.buses.orEmpty()
                    item.id to Strip(track.gain, List(track.outputBusCount) { bus ->
                        val busIndex = bus.toUInt()
                        val oldPeaks = oldBuses.getOrNull(bus)?.channels.orEmpty()
                        Bus(List(track.outputChannelCount(busIndex)) { channel ->
                            val measured = sampleDb(track.consumeOutputPeak(busIndex, channel.toUInt()))
                            val prior = oldPeaks.getOrNull(channel) ?: Peak()
                            Peak(maxOf(MeterFloor, measured, prior.db - decay), maxOf(prior.held, measured))
                        }, if (item.master) null else track.stereoBalance(busIndex))
                    })
                }
            }
            delay(33)
        }
    }
    LaunchedEffect(host.mixerFocusTrack, tracks) {
        val focus = host.mixerFocusTrack ?: return@LaunchedEffect
        val item = currentTracks.firstOrNull { it.index == focus }
        if (item != null) {
            selectedTrackId = item.id
            if (!item.master)
                stripList.scrollToItem(tracks.indexOf(item))
            outputList.scrollToItem(0)
        }
        host.mixerFocusTrack = null
    }

    val selectedTrack = currentTracks.firstOrNull { it.id == selectedTrackId } ?: master
    fun selectOutput(track: MixerTrack, bus: Int) {
        selectedTrackId = track.id
        selectedBuses[track.id] = bus
        val precedingRows = strips[track.id]?.buses.orEmpty().take(bus).sumOf { output ->
            if (output.channels.isEmpty()) 0 else output.channels.size + 1
        }
        scope.launch { outputList.scrollToItem(precedingRows) }
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val stripHeight = (maxHeight - 216.dp).coerceIn(320.dp, 362.dp)
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                val anyMuted = host.trackMutedFlags.any { it }
                val anySolo = host.trackSoloFlags.any { it }
                TextButton(onClick = { host.setAllTracksMuted(!anyMuted) }, enabled = tracks.isNotEmpty()) {
                    Text(if (anyMuted) "Unmute all" else "Mute all")
                }
                TextButton(onClick = { host.setAllTracksSolo(!anySolo) }, enabled = tracks.isNotEmpty()) {
                    Text(if (anySolo) "Unsolo all" else "Solo all")
                }
                TextButton(onClick = {
                    strips = strips.mapValues { (_, strip) ->
                        strip.copy(buses = strip.buses.map { bus -> bus.copy(channels = bus.channels.map { Peak(it.db) }) })
                    }
                }) { Text("Clear peaks") }
                TextButton(onClick = {
                    windows.open("latencyMonitor", "Latency Monitor", DpSize(520.dp, 360.dp)) { MixerMonitor(host) }
                }) { Text("Latency Monitor") }
            }

            Row(Modifier.height(stripHeight).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                LazyRow(Modifier.weight(1f).fillMaxHeight(), state = stripList, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    items(tracks, key = { it.id }) { item ->
                        MixerStrip(host, item, strips[item.id], selectedBuses[item.id], selectedTrack.id == item.id) {
                            selectOutput(item, it)
                        }
                    }
                }
                MixerStrip(host, master, strips[master.id], selectedBuses[master.id], selectedTrack.master) {
                    selectOutput(master, it)
                }
            }

            HorizontalDivider()
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("${selectedTrack.name} outputs", style = MaterialTheme.typography.titleSmall)
                Text("Peak hold (dBFS)", style = MaterialTheme.typography.labelSmall)
            }
            val buses = strips[selectedTrack.id]?.buses.orEmpty()
            val selectedBus = selectedBuses[selectedTrack.id] ?: 0
            LazyColumn(Modifier.fillMaxWidth().height(130.dp), state = outputList) {
                buses.forEachIndexed { index, bus ->
                    if (bus.channels.isNotEmpty()) {
                        item {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                FilterChip(selected = selectedBus == index,
                                    onClick = { selectedBuses[selectedTrack.id] = index },
                                    label = { Text("${index + 1}") })
                                val route = remember(selectedTrack.index, index, host.history, host.projectRevision, buses.size) {
                                    engine.trackOutputBusRoute(selectedTrack.index, index.toUInt())
                                }
                                Text(route, Modifier.padding(start = 8.dp), style = MaterialTheme.typography.labelMedium)
                            }
                        }
                        items(bus.channels.size) { channel ->
                            val peak = bus.channels[channel]
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                                Text(channelName(channel, bus.channels.size), Modifier.width(20.dp),
                                    style = MaterialTheme.typography.labelSmall)
                                PeakMeter(peak, false, Modifier.weight(1f).height(8.dp))
                                Text(decibels(peak.held),
                                    Modifier.width(45.dp), style = MaterialTheme.typography.labelSmall,
                                    color = if (peak.held >= 0) MaterialTheme.colorScheme.error else Color.Unspecified)
                            }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun MixerStrip(host: UapmdHost, track: MixerTrack, strip: Strip?, preferredBus: Int?, selected: Boolean,
                       select: (Int) -> Unit) {
    val buses = strip?.buses.orEmpty()
    val available = buses.indices.filter { buses[it].channels.isNotEmpty() }
    val busIndex = preferredBus?.takeIf { it in available } ?: available.firstOrNull() ?: 0
    val bus = buses.getOrNull(busIndex)
    val gain = strip?.gain ?: 1.0
    val gainDb = sampleDb(gain)
    val balance = bus?.balance
    val palette = editorPalette
    val colors = MaterialTheme.colorScheme
    val fader = remember(track.id) { SliderState(faderPosition(gain)) }
    fader.value = faderPosition(gain)
    fader.onValueChange = {
        fader.value = it
        host.setTrackGain(track.index, gainAt(it))
    }
    fader.onValueChangeFinished = host::endMixerGesture
    val plugin = host.trackInstances.getOrNull(track.index)?.firstOrNull()?.displayName ?: ""
    val overload = available.firstNotNullOfOrNull { index ->
        val channels = buses[index].channels
        channels.indexOfFirst { it.held >= 0 }.takeIf { it >= 0 }?.let { index to channelName(it, channels.size) }
    }

    Column(
        Modifier.width(132.dp).fillMaxHeight()
            .background(if (track.master) colors.surfaceContainerHigh else colors.surfaceContainer)
            .trackColorStrip(host.trackProperties(track.index)).padding(horizontal = 5.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        FilledTonalButton(onClick = { select(busIndex) }, modifier = Modifier.fillMaxWidth(),
            colors = if (selected) ButtonDefaults.filledTonalButtonColors(containerColor = colors.primaryContainer)
                else ButtonDefaults.filledTonalButtonColors(), contentPadding = PaddingValues(horizontal = 4.dp)) {
            Text(track.name, maxLines = 1)
        }
        Text(if (track.master) "Device output" else plugin.ifEmpty { " " }, maxLines = 1,
            overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelSmall)

        Box(Modifier.height(16.dp), contentAlignment = Alignment.Center) {
            if (balance != null)
                Text("Balance", style = MaterialTheme.typography.labelSmall)
        }
        Row(Modifier.height(48.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            if (balance != null) {
                Slider(balance.toFloat(), { host.setTrackStereoBalance(track.index, busIndex, it.toDouble(), dragging = true) },
                    modifier = Modifier.weight(1f).semantics { contentDescription = "Stereo balance, bus ${busIndex + 1}" },
                    onValueChangeFinished = host::endMixerGesture, valueRange = -1f..1f)
                val percent = (balance * 100).roundToInt()
                val label = if (percent == 0) "0" else "${abs(percent)}${if (percent < 0) "L" else "R"}"
                TextButton(onClick = { host.setTrackStereoBalance(track.index, busIndex, 0.0) },
                    modifier = Modifier.widthIn(min = 44.dp).semantics { contentDescription = "Center balance" },
                    contentPadding = PaddingValues(horizontal = 3.dp)) { Text(label) }
            }
        }

        Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
            VerticalSlider(state = fader, reverseDirection = true,
                modifier = Modifier.fillMaxHeight().width(48.dp).semantics { contentDescription = "${track.name} gain" })
            val channels = bus?.channels.orEmpty()
            Row(Modifier.fillMaxHeight().weight(1f), horizontalArrangement = Arrangement.Center) {
                if (channels.size > 8) {
                    Column(Modifier.width(12.dp).fillMaxHeight(), horizontalAlignment = Alignment.CenterHorizontally) {
                        PeakMeter(Peak(channels.maxOf { it.db }, channels.maxOf { it.held }), true,
                            Modifier.fillMaxWidth().weight(1f))
                        Text("${channels.size}", style = MaterialTheme.typography.labelSmall)
                    }
                } else channels.forEachIndexed { channel, peak ->
                    Column(Modifier.width(if (channels.size > 4) 8.dp else 13.dp).fillMaxHeight().padding(horizontal = 1.dp),
                        horizontalAlignment = Alignment.CenterHorizontally) {
                        PeakMeter(peak, true, Modifier.fillMaxWidth().weight(1f))
                        Text(channelName(channel, channels.size), style = MaterialTheme.typography.labelSmall, maxLines = 1)
                    }
                }
            }
        }
        Text("${decibels(gainDb)} dB", style = MaterialTheme.typography.labelSmall)
        Row(Modifier.height(48.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            if (!track.master) {
                val muted = host.trackMuted(track.index)
                val solo = host.trackSolo(track.index)
                MixToggle("M", if (muted) "Unmute track" else "Mute track", muted, palette.muted, Modifier.weight(1f)) {
                    host.setTrackMuted(track.index, !muted)
                }
                MixToggle("S", if (solo) "Unsolo track" else "Solo track", solo, palette.solo, Modifier.weight(1f)) {
                    host.setTrackSolo(track.index, !solo)
                }
            }
        }
        Row(Modifier.height(48.dp).fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
            if (available.size > 1) {
                TextButton(onClick = { select(available[(available.indexOf(busIndex) + 1) % available.size]) },
                    contentPadding = PaddingValues(horizontal = 4.dp)) {
                    Text("${busIndex + 1}/${buses.size}")
                }
            }
            if (overload != null) {
                TextButton(onClick = { select(overload.first) }, contentPadding = PaddingValues(horizontal = 4.dp),
                    colors = ButtonDefaults.textButtonColors(contentColor = colors.error)) {
                    Text("! ${overload.second}")
                }
            }
        }
    }
}

@Composable
private fun MixToggle(label: String, description: String, active: Boolean, tint: Color, modifier: Modifier, onClick: () -> Unit) {
    FilledTonalButton(onClick = onClick, modifier = modifier.semantics { contentDescription = description },
        contentPadding = PaddingValues(horizontal = 6.dp),
        colors = if (active) ButtonDefaults.filledTonalButtonColors(containerColor = tint, contentColor = Color.White)
            else ButtonDefaults.filledTonalButtonColors()) { Text(label) }
}

@Composable
private fun PeakMeter(peak: Peak, vertical: Boolean, modifier: Modifier) {
    val rail = MaterialTheme.colorScheme.surfaceVariant
    val marker = MaterialTheme.colorScheme.onSurface
    val overload = MaterialTheme.colorScheme.error
    Canvas(modifier) {
        drawRect(rail)
        fun fraction(db: Double) = ((db - MeterFloor) / -MeterFloor).coerceIn(0.0, 1.0).toFloat()
        fun segment(from: Float, to: Float, color: Color) {
            if (to <= from) return
            if (vertical)
                drawRect(color, Offset(0f, size.height * (1 - to)), Size(size.width, size.height * (to - from)))
            else
                drawRect(color, Offset(size.width * from, 0f), Size(size.width * (to - from), size.height))
        }
        val level = fraction(peak.db)
        segment(0f, minOf(level, 0.8f), Color(0xff51bf8d))
        segment(0.8f, minOf(level, 0.95f), Color(0xffe1b54a))
        segment(0.95f, level, overload)
        if (peak.held > MeterFloor) {
            val hold = fraction(peak.held)
            segment((hold - 0.012f).coerceAtLeast(0f), hold, if (peak.held >= 0) overload else marker)
        }
    }
}
