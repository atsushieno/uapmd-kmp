@file:Suppress("unused")

package dev.atsushieno.uapmd

import kotlin.js.Promise
import kotlinx.coroutines.await

// ── External declarations for the Emscripten module ──────────────────────────

/**
 * The Emscripten-compiled uapmd-c-api module.
 * Call [initUapmdWasm] to initialize before using any functions.
 */
external interface UapmdCApiModule : JsAny {
    // ── Emscripten runtime helpers ─────────────────────────────────────────
    @JsName("UTF8ToString")
    fun utf8ToString(ptr: Int, maxBytes: Int = definedExternally): String

    @JsName("stringToUTF8")
    fun stringToUTF8(str: String, outPtr: Int, maxBytesToWrite: Int): Int

    @JsName("lengthBytesUTF8")
    fun lengthBytesUTF8(str: String): Int

    @JsName("getValue")
    fun getValue(ptr: Int, type: String): Double

    @JsName("setValue")
    fun setValue(ptr: Int, value: Double, type: String)

    @JsName("addFunction")
    fun addFunction(fn: JsAny, signature: String): Int

    @JsName("removeFunction")
    fun removeFunction(ptr: Int)

    @JsName("_malloc")
    fun malloc(size: Int): Int

    @JsName("_free")
    fun free(ptr: Int)

    // ── Plugin host ────────────────────────────────────────────────────────
    @JsName("_uapmd_plugin_host_create")
    fun uapmdPluginHostCreate(): Int
    @JsName("_uapmd_plugin_host_destroy")
    fun uapmdPluginHostDestroy(handle: Int)
    @JsName("_uapmd_plugin_host_catalog_entry_count")
    fun uapmdPluginHostCatalogEntryCount(handle: Int): Int
    @JsName("_uapmd_plugin_host_instance_id_count")
    fun uapmdPluginHostInstanceIdCount(handle: Int): Int
    @JsName("_uapmd_plugin_host_get_instance")
    fun uapmdPluginHostGetInstance(handle: Int, instanceId: Int): Int
    @JsName("_uapmd_plugin_host_delete_instance")
    fun uapmdPluginHostDeleteInstance(handle: Int, instanceId: Int)
    @JsName("_uapmd_plugin_host_perform_scanning")
    fun uapmdPluginHostPerformScanning(handle: Int, rescan: Boolean)
    @JsName("_uapmd_plugin_host_reload_catalog_from_cache")
    fun uapmdPluginHostReloadCatalogFromCache(handle: Int)
    @JsName("_uapmd_plugin_host_save_catalog")
    fun uapmdPluginHostSaveCatalog(handle: Int, pathPtr: Int)
    /** Callback: void(int instanceId, const char* error) */
    @JsName("_uapmd_plugin_host_create_instance")
    fun uapmdPluginHostCreateInstance(
        handle: Int, sampleRate: Int, bufferSize: Int,
        mainInputChannels: Int, mainOutputChannels: Int,
        offlineMode: Boolean, formatPtr: Int, pluginIdPtr: Int,
        callback: Int, ctx: Int
    )
    @JsName("_uapmd_plugin_host_get_catalog_entry")
    fun uapmdPluginHostGetCatalogEntry(
        handle: Int, index: Int,
        fmtBuf: Int, fmtBufSize: Int,
        idBuf: Int, idBufSize: Int,
        nameBuf: Int, nameBufSize: Int
    ): Boolean
    @JsName("_uapmd_plugin_host_get_instance_ids")
    fun uapmdPluginHostGetInstanceIds(handle: Int, buf: Int, bufCount: Int): Int

    // ── Plugin instance ────────────────────────────────────────────────────
    @JsName("_uapmd_instance_display_name")
    fun uapmdInstanceDisplayName(inst: Int, buf: Int, bufSize: Int): Int
    @JsName("_uapmd_instance_format_name")
    fun uapmdInstanceFormatName(inst: Int, buf: Int, bufSize: Int): Int
    @JsName("_uapmd_instance_plugin_id")
    fun uapmdInstancePluginId(inst: Int, buf: Int, bufSize: Int): Int
    @JsName("_uapmd_instance_has_ui_support")
    fun uapmdInstanceHasUiSupport(inst: Int): Boolean
    @JsName("_uapmd_instance_get_ui_capabilities")
    fun uapmdInstanceGetUiCapabilities(inst: Int, outPtr: Int)
    @JsName("_uapmd_instance_can_ui_resize")
    fun uapmdInstanceCanUiResize(inst: Int): Boolean
    @JsName("_uapmd_instance_is_ui_visible")
    fun uapmdInstanceIsUiVisible(inst: Int): Boolean
    @JsName("_uapmd_instance_latency_in_samples")
    fun uapmdInstanceLatencyInSamples(inst: Int): Int
    @JsName("_uapmd_instance_tail_length_in_seconds")
    fun uapmdInstanceTailLengthInSeconds(inst: Int): Double
    @JsName("_uapmd_instance_requires_replacing_process")
    fun uapmdInstanceRequiresReplacingProcess(inst: Int): Boolean
    @JsName("_uapmd_instance_parameter_count")
    fun uapmdInstanceParameterCount(inst: Int): Int
    @JsName("_uapmd_instance_get_parameter_metadata")
    fun uapmdInstanceGetParameterMetadata(inst: Int, listIndex: Int, outPtr: Int): Boolean
    @JsName("_uapmd_instance_get_parameter_value")
    fun uapmdInstanceGetParameterValue(inst: Int, index: Int): Double
    @JsName("_uapmd_instance_set_parameter_value")
    fun uapmdInstanceSetParameterValue(inst: Int, index: Int, value: Double)
    @JsName("_uapmd_instance_get_parameter_value_string")
    fun uapmdInstanceGetParameterValueString(inst: Int, index: Int, value: Double, buf: Int, bufSize: Int): Int
    @JsName("_uapmd_instance_set_per_note_controller_value")
    fun uapmdInstanceSetPerNoteControllerValue(inst: Int, note: Int, index: Int, value: Double)
    @JsName("_uapmd_instance_get_per_note_controller_value_string")
    fun uapmdInstanceGetPerNoteControllerValueString(inst: Int, note: Int, index: Int, value: Double, buf: Int, bufSize: Int): Int
    @JsName("_uapmd_instance_preset_count")
    fun uapmdInstancePresetCount(inst: Int): Int
    @JsName("_uapmd_instance_get_preset_metadata")
    fun uapmdInstanceGetPresetMetadata(inst: Int, listIndex: Int, outPtr: Int): Boolean
    @JsName("_uapmd_instance_load_preset")
    fun uapmdInstanceLoadPreset(inst: Int, presetIndex: Int)
    @JsName("_uapmd_instance_get_bypassed")
    fun uapmdInstanceGetBypassed(inst: Int): Boolean
    @JsName("_uapmd_instance_set_bypassed")
    fun uapmdInstanceSetBypassed(inst: Int, bypassed: Boolean)
    @JsName("_uapmd_instance_start_processing")
    fun uapmdInstanceStartProcessing(inst: Int): Int
    @JsName("_uapmd_instance_stop_processing")
    fun uapmdInstanceStopProcessing(inst: Int): Int
    @JsName("_uapmd_instance_show_ui")
    fun uapmdInstanceShowUi(inst: Int): Boolean
    @JsName("_uapmd_instance_hide_ui")
    fun uapmdInstanceHideUi(inst: Int)
    @JsName("_uapmd_instance_get_ui_size")
    fun uapmdInstanceGetUiSize(inst: Int, widthPtr: Int, heightPtr: Int): Boolean
    @JsName("_uapmd_instance_set_ui_size")
    fun uapmdInstanceSetUiSize(inst: Int, width: Int, height: Int): Boolean
    @JsName("_uapmd_instance_create_ui_presentation")
    fun uapmdInstanceCreateUiPresentation(inst: Int, requestPtr: Int, resizeCb: Int, ctx: Int): Int
    @JsName("_uapmd_instance_create_ui")
    fun uapmdInstanceCreateUi(inst: Int, isFloating: Boolean, parentHandle: Int, resizeCb: Int, ctx: Int): Boolean
    @JsName("_uapmd_instance_destroy_ui")
    fun uapmdInstanceDestroyUi(inst: Int)
    @JsName("_uapmd_ui_presentation_destroy")
    fun uapmdUiPresentationDestroy(presentation: Int)
    @JsName("_uapmd_ui_presentation_show")
    fun uapmdUiPresentationShow(presentation: Int): Boolean
    @JsName("_uapmd_ui_presentation_hide")
    fun uapmdUiPresentationHide(presentation: Int)
    @JsName("_uapmd_ui_presentation_is_visible")
    fun uapmdUiPresentationIsVisible(presentation: Int): Boolean
    @JsName("_uapmd_ui_presentation_set_size")
    fun uapmdUiPresentationSetSize(presentation: Int, width: Int, height: Int): Boolean
    @JsName("_uapmd_ui_presentation_get_size")
    fun uapmdUiPresentationGetSize(presentation: Int, widthPtr: Int, heightPtr: Int): Boolean
    @JsName("_uapmd_ui_presentation_can_resize")
    fun uapmdUiPresentationCanResize(presentation: Int): Boolean
    @JsName("_uapmd_instance_save_state_sync")
    fun uapmdInstanceSaveStateSync(inst: Int, outBuf: Int, outBufSize: Int, outActual: Int): Boolean
    @JsName("_uapmd_instance_load_state_sync")
    fun uapmdInstanceLoadStateSync(inst: Int, data: Int, size: Int): Boolean
    /** Callback: void(const uint8_t* data, size_t size, const char* error) */
    @JsName("_uapmd_instance_request_state")
    fun uapmdInstanceRequestState(inst: Int, ctx: Int, includeUiState: Boolean, callback: Int, callbackCtx: Int)
    @JsName("_uapmd_instance_load_state")
    fun uapmdInstanceLoadState(inst: Int, data: Int, size: Int, ctx: Int, includeUiState: Boolean, callback: Int, callbackCtx: Int)

    // ── Plugin graph ───────────────────────────────────────────────────────
    @JsName("_uapmd_graph_create")
    fun uapmdGraphCreate(): Int
    @JsName("_uapmd_graph_destroy")
    fun uapmdGraphDestroy(handle: Int)
    @JsName("_uapmd_graph_plugin_count")
    fun uapmdGraphPluginCount(handle: Int): Int
    @JsName("_uapmd_graph_append_node")
    fun uapmdGraphAppendNode(handle: Int, instanceId: Int, inst: Int, onDeleteCb: Int, ctx: Int): Int
    @JsName("_uapmd_graph_remove_node")
    fun uapmdGraphRemoveNode(handle: Int, instanceId: Int): Boolean
    @JsName("_uapmd_graph_get_plugin_node")
    fun uapmdGraphGetPluginNode(handle: Int, instanceId: Int): Int
    @JsName("_uapmd_graph_output_bus_count")
    fun uapmdGraphOutputBusCount(handle: Int): Int
    @JsName("_uapmd_graph_output_latency_in_samples")
    fun uapmdGraphOutputLatencyInSamples(handle: Int, busIndex: Int): Int
    @JsName("_uapmd_graph_output_tail_length_in_seconds")
    fun uapmdGraphOutputTailLengthInSeconds(handle: Int, busIndex: Int): Double
    @JsName("_uapmd_graph_main_output_latency_in_samples")
    fun uapmdGraphMainOutputLatencyInSamples(handle: Int): Int
    @JsName("_uapmd_graph_main_output_tail_length_in_seconds")
    fun uapmdGraphMainOutputTailLengthInSeconds(handle: Int): Double
    @JsName("_uapmd_graph_render_lead_in_samples")
    fun uapmdGraphRenderLeadInSamples(handle: Int): Int
    /** Callback: void(int instanceId, const uint32_t* data, size_t size, void* ctx) */
    @JsName("_uapmd_graph_set_event_output_callback")
    fun uapmdGraphSetEventOutputCallback(handle: Int, callback: Int, ctx: Int)

    // ── Plugin node ────────────────────────────────────────────────────────
    @JsName("_uapmd_node_instance")
    fun uapmdNodeInstance(handle: Int): Int
    @JsName("_uapmd_node_instance_id")
    fun uapmdNodeInstanceId(handle: Int): Int
    @JsName("_uapmd_node_schedule_events")
    fun uapmdNodeScheduleEvents(handle: Int, timestamp: Int, eventsPtr: Int, eventsSize: Int): Boolean
    @JsName("_uapmd_node_send_all_notes_off")
    fun uapmdNodeSendAllNotesOff(handle: Int)

    // ── Sequencer engine ───────────────────────────────────────────────────
    @JsName("_uapmd_engine_create")
    fun uapmdEngineCreate(sampleRate: Int, audioBufferSize: Int, umpBufferSize: Int): Int
    @JsName("_uapmd_engine_destroy")
    fun uapmdEngineDestroy(handle: Int)
    @JsName("_uapmd_engine_set_sample_rate")
    fun uapmdEngineSetSampleRate(handle: Int, sampleRate: Int)
    @JsName("_uapmd_engine_plugin_host")
    fun uapmdEnginePluginHost(handle: Int): Int
    @JsName("_uapmd_engine_function_block_manager")
    fun uapmdEngineFunctionBlockManager(handle: Int): Int
    @JsName("_uapmd_engine_timeline")
    fun uapmdEngineTimeline(handle: Int): Int
    @JsName("_uapmd_engine_track_count")
    fun uapmdEngineTrackCount(handle: Int): Int
    @JsName("_uapmd_engine_get_track")
    fun uapmdEngineGetTrack(handle: Int, index: Int): Int
    @JsName("_uapmd_engine_master_track")
    fun uapmdEngineMasterTrack(handle: Int): Int
    @JsName("_uapmd_engine_add_empty_track")
    fun uapmdEngineAddEmptyTrack(handle: Int): Int
    @JsName("_uapmd_engine_remove_track")
    fun uapmdEngineRemoveTrack(handle: Int, trackIndex: Int): Boolean
    @JsName("_uapmd_engine_cleanup_empty_tracks")
    fun uapmdEngineCleanupEmptyTracks(handle: Int)
    @JsName("_uapmd_engine_remove_plugin_instance")
    fun uapmdEngineRemovePluginInstance(handle: Int, instanceId: Int): Boolean
    @JsName("_uapmd_engine_find_track_for_instance")
    fun uapmdEngineFindTrackForInstance(handle: Int, instanceId: Int): Int
    @JsName("_uapmd_engine_get_plugin_instance")
    fun uapmdEngineGetPluginInstance(handle: Int, instanceId: Int): Int
    @JsName("_uapmd_engine_get_instance_group")
    fun uapmdEngineGetInstanceGroup(handle: Int, instanceId: Int): Int
    @JsName("_uapmd_engine_set_instance_group")
    fun uapmdEngineSetInstanceGroup(handle: Int, instanceId: Int, group: Int): Boolean
    @JsName("_uapmd_engine_set_active")
    fun uapmdEngineSetActive(handle: Int, active: Boolean)
    @JsName("_uapmd_engine_set_external_pump")
    fun uapmdEngineSetExternalPump(handle: Int, enabled: Boolean)
    @JsName("_uapmd_engine_set_default_channels")
    fun uapmdEngineSetDefaultChannels(handle: Int, inputChannels: Int, outputChannels: Int)
    @JsName("_uapmd_engine_set_offline_rendering")
    fun uapmdEngineSetOfflineRendering(handle: Int, offline: Boolean)
    @JsName("_uapmd_engine_get_offline_rendering")
    fun uapmdEngineGetOfflineRendering(handle: Int): Boolean
    @JsName("_uapmd_engine_enqueue_ump")
    fun uapmdEngineEnqueueUmp(handle: Int, instanceId: Int, umpPtr: Int, count: Int, timestamp: Double)
    @JsName("_uapmd_engine_send_note_on")
    fun uapmdEngineSendNoteOn(handle: Int, instanceId: Int, note: Int)
    @JsName("_uapmd_engine_send_note_off")
    fun uapmdEngineSendNoteOff(handle: Int, instanceId: Int, note: Int)
    @JsName("_uapmd_engine_send_pitch_bend")
    fun uapmdEngineSendPitchBend(handle: Int, instanceId: Int, value: Double)
    @JsName("_uapmd_engine_send_channel_pressure")
    fun uapmdEngineSendChannelPressure(handle: Int, instanceId: Int, pressure: Double)
    @JsName("_uapmd_engine_set_parameter_value")
    fun uapmdEngineSetParameterValue(handle: Int, instanceId: Int, index: Int, value: Double)
    @JsName("_uapmd_engine_start_playback")
    fun uapmdEngineStartPlayback(handle: Int)
    @JsName("_uapmd_engine_pause_playback")
    fun uapmdEnginePausePlayback(handle: Int)
    @JsName("_uapmd_engine_resume_playback")
    fun uapmdEngineResumePlayback(handle: Int)
    @JsName("_uapmd_engine_stop_playback")
    fun uapmdEngineStopPlayback(handle: Int)
    @JsName("_uapmd_engine_is_playback_active")
    fun uapmdEngineIsPlaybackActive(handle: Int): Boolean
    @JsName("_uapmd_engine_get_playback_position")
    fun uapmdEngineGetPlaybackPosition(handle: Int): Long
    @JsName("_uapmd_engine_set_playback_position")
    fun uapmdEngineSetPlaybackPosition(handle: Int, position: Long)
    @JsName("_uapmd_engine_render_playback_position")
    fun uapmdEngineRenderPlaybackPosition(handle: Int): Long
    @JsName("_uapmd_engine_master_track_latency")
    fun uapmdEngineMasterTrackLatency(handle: Int): Int
    @JsName("_uapmd_engine_master_track_render_lead")
    fun uapmdEngineMasterTrackRenderLead(handle: Int): Int
    @JsName("_uapmd_engine_track_latency")
    fun uapmdEngineTrackLatency(handle: Int, trackIndex: Int): Int
    @JsName("_uapmd_engine_track_render_lead")
    fun uapmdEngineTrackRenderLead(handle: Int, trackIndex: Int): Int
    /** Callback for addPluginToTrack: void(int instanceId, int trackIndex, const char* error) */
    @JsName("_uapmd_engine_add_plugin_to_track")
    fun uapmdEngineAddPluginToTrack(handle: Int, trackIndex: Int, formatPtr: Int, pluginIdPtr: Int, callback: Int, ctx: Int)

    // ── Realtime sequencer ─────────────────────────────────────────────────
    @JsName("_uapmd_rt_sequencer_create")
    fun uapmdRtSequencerCreate(bufferSize: Int, umpBufferSize: Int, sampleRate: Int, dispatcher: Int): Int
    @JsName("_uapmd_rt_sequencer_destroy")
    fun uapmdRtSequencerDestroy(handle: Int)
    @JsName("_uapmd_rt_sequencer_engine")
    fun uapmdRtSequencerEngine(handle: Int): Int
    @JsName("_uapmd_rt_sequencer_sample_rate")
    fun uapmdRtSequencerSampleRate(handle: Int): Int
    @JsName("_uapmd_rt_sequencer_set_sample_rate")
    fun uapmdRtSequencerSetSampleRate(handle: Int, sampleRate: Int)
    @JsName("_uapmd_rt_sequencer_start_audio")
    fun uapmdRtSequencerStartAudio(handle: Int): Int
    @JsName("_uapmd_rt_sequencer_stop_audio")
    fun uapmdRtSequencerStopAudio(handle: Int): Int
    @JsName("_uapmd_rt_sequencer_is_audio_playing")
    fun uapmdRtSequencerIsAudioPlaying(handle: Int): Int
    @JsName("_uapmd_rt_sequencer_clear_output_buffers")
    fun uapmdRtSequencerClearOutputBuffers(handle: Int)
    @JsName("_uapmd_rt_sequencer_reconfigure_audio_device")
    fun uapmdRtSequencerReconfigureAudioDevice(handle: Int, inDev: Int, outDev: Int, sampleRate: Int, bufferSize: Int): Boolean

    // ── Device I/O dispatcher ──────────────────────────────────────────────
    @JsName("_uapmd_default_device_io_dispatcher")
    fun uapmdDefaultDeviceIoDispatcher(): Int
    @JsName("_uapmd_dispatcher_start")
    fun uapmdDispatcherStart(handle: Int): Int
    @JsName("_uapmd_dispatcher_stop")
    fun uapmdDispatcherStop(handle: Int): Int
    @JsName("_uapmd_dispatcher_is_playing")
    fun uapmdDispatcherIsPlaying(handle: Int): Boolean
    @JsName("_uapmd_dispatcher_clear_output_buffers")
    fun uapmdDispatcherClearOutputBuffers(handle: Int)

    // ── Timeline facade ────────────────────────────────────────────────────
    @JsName("_uapmd_tl_track_count")
    fun uapmdTlTrackCount(handle: Int): Int
    @JsName("_uapmd_tl_get_track")
    fun uapmdTlGetTrack(handle: Int, index: Int): Int
    @JsName("_uapmd_tl_master_timeline_track")
    fun uapmdTlMasterTimelineTrack(handle: Int): Int
    @JsName("_uapmd_tl_get_state")
    fun uapmdTlGetState(handle: Int, outPtr: Int): Boolean
    @JsName("_uapmd_tl_set_tempo")
    fun uapmdTlSetTempo(handle: Int, tempo: Double)
    @JsName("_uapmd_tl_set_time_signature")
    fun uapmdTlSetTimeSignature(handle: Int, numerator: Int, denominator: Int)
    @JsName("_uapmd_tl_set_loop")
    fun uapmdTlSetLoop(handle: Int, enabled: Boolean, startSamples: Double, startBeats: Double, endSamples: Double, endBeats: Double)
    @JsName("_uapmd_tl_remove_clip")
    fun uapmdTlRemoveClip(handle: Int, trackIndex: Int, clipId: Int): Boolean
    @JsName("_uapmd_tl_load_project")
    fun uapmdTlLoadProject(handle: Int, filePathPtr: Int, successOut: Int, errorBuf: Int, errorBufSize: Int): Boolean
    @JsName("_uapmd_tl_calculate_content_bounds")
    fun uapmdTlCalculateContentBounds(handle: Int, outPtr: Int)

    // clip add functions return uapmd_clip_add_result_t (handled via out pointer)
    @JsName("_uapmd_tl_add_audio_clip")
    fun uapmdTlAddAudioClip(handle: Int, trackIndex: Int, positionSamples: Double, positionBeats: Double, reader: Int, filePathPtr: Int, outPtr: Int)
    @JsName("_uapmd_tl_add_midi_clip_from_file")
    fun uapmdTlAddMidiClipFromFile(handle: Int, trackIndex: Int, positionSamples: Double, positionBeats: Double, filePathPtr: Int, nrpnMapping: Boolean, outPtr: Int)
    @JsName("_uapmd_tl_add_midi_clip_from_data")
    fun uapmdTlAddMidiClipFromData(handle: Int, trackIndex: Int, positionSamples: Double, positionBeats: Double, dataPtr: Int, dataSize: Int, outPtr: Int)

    // ── Timeline track ─────────────────────────────────────────────────────
    @JsName("_uapmd_tt_reference_id")
    fun uapmdTtReferenceId(handle: Int): Int
    @JsName("_uapmd_tt_channel_count")
    fun uapmdTtChannelCount(handle: Int): Int
    @JsName("_uapmd_tt_sample_rate")
    fun uapmdTtSampleRate(handle: Int): Int
    @JsName("_uapmd_tt_clip_manager")
    fun uapmdTtClipManager(handle: Int): Int
    @JsName("_uapmd_tt_has_device_input_source")
    fun uapmdTtHasDeviceInputSource(handle: Int): Boolean
    @JsName("_uapmd_tt_remove_clip")
    fun uapmdTtRemoveClip(handle: Int, clipId: Int): Boolean

    // ── Audio file reader ──────────────────────────────────────────────────
    @JsName("_uapmd_audio_file_reader_create")
    fun uapmdAudioFileReaderCreate(filePathPtr: Int): Int
    @JsName("_uapmd_audio_file_reader_destroy")
    fun uapmdAudioFileReaderDestroy(handle: Int)
    @JsName("_uapmd_audio_file_reader_get_properties")
    fun uapmdAudioFileReaderGetProperties(handle: Int, outPtr: Int): Boolean
    @JsName("_uapmd_audio_file_reader_read_frames")
    fun uapmdAudioFileReaderReadFrames(handle: Int, startFrame: Double, framesToRead: Double, channelsPtr: Int, channelCount: Int)

    // ── Audio devices ──────────────────────────────────────────────────────
    @JsName("_uapmd_audio_device_mgr_instance")
    fun uapmdAudioDeviceMgrInstance(driverNamePtr: Int): Int
    @JsName("_uapmd_audio_device_mgr_device_count")
    fun uapmdAudioDeviceMgrDeviceCount(handle: Int): Int
    @JsName("_uapmd_audio_device_mgr_get_device_info")
    fun uapmdAudioDeviceMgrGetDeviceInfo(handle: Int, index: Int, outPtr: Int): Boolean
    @JsName("_uapmd_audio_device_mgr_open")
    fun uapmdAudioDeviceMgrOpen(handle: Int, inputDev: Int, outputDev: Int, sampleRate: Int, bufferSize: Int): Int
    @JsName("_uapmd_audio_device_channels")
    fun uapmdAudioDeviceChannels(handle: Int): Int
    @JsName("_uapmd_audio_device_input_channels")
    fun uapmdAudioDeviceInputChannels(handle: Int): Int
    @JsName("_uapmd_audio_device_output_channels")
    fun uapmdAudioDeviceOutputChannels(handle: Int): Int
    @JsName("_uapmd_audio_device_sample_rate")
    fun uapmdAudioDeviceSampleRate(handle: Int): Double
    @JsName("_uapmd_audio_device_is_playing")
    fun uapmdAudioDeviceIsPlaying(handle: Int): Boolean
    @JsName("_uapmd_audio_device_start")
    fun uapmdAudioDeviceStart(handle: Int): Int
    @JsName("_uapmd_audio_device_stop")
    fun uapmdAudioDeviceStop(handle: Int): Int

    // ── MIDI I/O ───────────────────────────────────────────────────────────
    @JsName("_uapmd_midi_device_instance")
    fun uapmdMidiDeviceInstance(driverNamePtr: Int): Int
    /** Callback: void(const uint32_t* messages, size_t count, int64_t timestamp) */
    @JsName("_uapmd_midi_io_add_input_handler")
    fun uapmdMidiIoAddInputHandler(handle: Int, callback: Int, ctx: Int): Int
    @JsName("_uapmd_midi_io_remove_input_handler")
    fun uapmdMidiIoRemoveInputHandler(handle: Int, token: Int)
    @JsName("_uapmd_midi_io_send")
    fun uapmdMidiIoSend(handle: Int, messagesPtr: Int, count: Int, timestamp: Double)

    // ── Function block manager ─────────────────────────────────────────────
    @JsName("_uapmd_fbm_count")
    fun uapmdFbmCount(handle: Int): Int
    @JsName("_uapmd_fbm_get_device_by_index")
    fun uapmdFbmGetDeviceByIndex(handle: Int, index: Int): Int
    @JsName("_uapmd_fbm_get_device_for_instance")
    fun uapmdFbmGetDeviceForInstance(handle: Int, instanceId: Int): Int
    @JsName("_uapmd_fbm_create_device")
    fun uapmdFbmCreateDevice(handle: Int): Int
    @JsName("_uapmd_fbm_delete_empty_devices")
    fun uapmdFbmDeleteEmptyDevices(handle: Int)
    @JsName("_uapmd_fbm_clear_all_devices")
    fun uapmdFbmClearAllDevices(handle: Int)
    @JsName("_uapmd_fbm_detach_all_output_mappers")
    fun uapmdFbmDetachAllOutputMappers(handle: Int)

    // ── Function block / device ────────────────────────────────────────────
    @JsName("_uapmd_fb_get_group")
    fun uapmdFbGetGroup(handle: Int): Int
    @JsName("_uapmd_fb_set_group")
    fun uapmdFbSetGroup(handle: Int, group: Int)
    @JsName("_uapmd_fb_initialize")
    fun uapmdFbInitialize(handle: Int)
    @JsName("_uapmd_fb_midi_io")
    fun uapmdFbMidiIo(handle: Int): Int
    @JsName("_uapmd_fb_detach_output_mapper")
    fun uapmdFbDetachOutputMapper(handle: Int)

    // ── Track ──────────────────────────────────────────────────────────────
    @JsName("_uapmd_track_graph")
    fun uapmdTrackGraph(handle: Int): Int
    @JsName("_uapmd_track_get_bypassed")
    fun uapmdTrackGetBypassed(handle: Int): Boolean
    @JsName("_uapmd_track_set_bypassed")
    fun uapmdTrackSetBypassed(handle: Int, bypassed: Boolean)
    @JsName("_uapmd_track_get_frozen")
    fun uapmdTrackGetFrozen(handle: Int): Boolean
    @JsName("_uapmd_track_set_frozen")
    fun uapmdTrackSetFrozen(handle: Int, frozen: Boolean)
    @JsName("_uapmd_track_get_instance_group")
    fun uapmdTrackGetInstanceGroup(handle: Int, instanceId: Int): Int
    @JsName("_uapmd_track_set_instance_group")
    fun uapmdTrackSetInstanceGroup(handle: Int, instanceId: Int, group: Int)
    @JsName("_uapmd_track_find_available_group")
    fun uapmdTrackFindAvailableGroup(handle: Int): Int
    @JsName("_uapmd_track_ordered_instance_id_count")
    fun uapmdTrackOrderedInstanceIdCount(handle: Int): Int
    @JsName("_uapmd_track_get_ordered_instance_ids")
    fun uapmdTrackGetOrderedInstanceIds(handle: Int, buf: Int, bufCount: Int): Int
    @JsName("_uapmd_track_remove_instance")
    fun uapmdTrackRemoveInstance(handle: Int, instanceId: Int)
    @JsName("_uapmd_track_latency_in_samples")
    fun uapmdTrackLatencyInSamples(handle: Int): Int
    @JsName("_uapmd_track_tail_length_in_seconds")
    fun uapmdTrackTailLengthInSeconds(handle: Int): Double
    @JsName("_uapmd_track_render_lead_in_samples")
    fun uapmdTrackRenderLeadInSamples(handle: Int): Int

    // ── Scan tool ──────────────────────────────────────────────────────────
    @JsName("_uapmd_scan_tool_create")
    fun uapmdScanToolCreate(): Int
    @JsName("_uapmd_scan_tool_destroy")
    fun uapmdScanToolDestroy(handle: Int)
    @JsName("_uapmd_scan_tool_catalog_entry_count")
    fun uapmdScanToolCatalogEntryCount(handle: Int): Int
    @JsName("_uapmd_scan_tool_format_count")
    fun uapmdScanToolFormatCount(handle: Int): Int
    @JsName("_uapmd_scan_tool_get_format_name")
    fun uapmdScanToolGetFormatName(handle: Int, index: Int, buf: Int, bufSize: Int): Int
    @JsName("_uapmd_scan_tool_get_cache_file")
    fun uapmdScanToolGetCacheFile(handle: Int, buf: Int, bufSize: Int): Int
    @JsName("_uapmd_scan_tool_set_cache_file")
    fun uapmdScanToolSetCacheFile(handle: Int, pathPtr: Int)
    @JsName("_uapmd_scan_tool_save_cache")
    fun uapmdScanToolSaveCache(handle: Int)
    @JsName("_uapmd_scan_tool_save_cache_to")
    fun uapmdScanToolSaveCacheTo(handle: Int, pathPtr: Int)
    @JsName("_uapmd_scan_tool_perform_scanning")
    fun uapmdScanToolPerformScanning(handle: Int, requireFast: Boolean, slowStartCb: Int, bundleStartCb: Int, bundleCompleteCb: Int, slowCompleteCb: Int, errorCb: Int, cancelCb: Int, ctx: Int)
    @JsName("_uapmd_scan_tool_blocklist_count")
    fun uapmdScanToolBlocklistCount(handle: Int): Int
    @JsName("_uapmd_scan_tool_get_blocklist_entry")
    fun uapmdScanToolGetBlocklistEntry(handle: Int, index: Int, outPtr: Int): Boolean
    @JsName("_uapmd_scan_tool_flush_blocklist")
    fun uapmdScanToolFlushBlocklist(handle: Int)
    @JsName("_uapmd_scan_tool_unblock_bundle")
    fun uapmdScanToolUnblockBundle(handle: Int, entryIdPtr: Int): Boolean
    @JsName("_uapmd_scan_tool_clear_blocklist")
    fun uapmdScanToolClearBlocklist(handle: Int)
    @JsName("_uapmd_scan_tool_add_to_blocklist")
    fun uapmdScanToolAddToBlocklist(handle: Int, formatNamePtr: Int, pluginIdPtr: Int, reasonPtr: Int)
    @JsName("_uapmd_scan_tool_last_scan_error")
    fun uapmdScanToolLastScanError(handle: Int, buf: Int, bufSize: Int): Int

    // ── Format manager ─────────────────────────────────────────────────────
    @JsName("_uapmd_format_manager_create")
    fun uapmdFormatManagerCreate(): Int
    @JsName("_uapmd_format_manager_destroy")
    fun uapmdFormatManagerDestroy(handle: Int)
    @JsName("_uapmd_format_manager_format_count")
    fun uapmdFormatManagerFormatCount(handle: Int): Int
    @JsName("_uapmd_format_manager_get_format_name")
    fun uapmdFormatManagerGetFormatName(handle: Int, index: Int, buf: Int, bufSize: Int): Int

    // ── Plugin instancing ──────────────────────────────────────────────────
    @JsName("_uapmd_instancing_create")
    fun uapmdInstancingCreate(scanTool: Int, formatPtr: Int, pluginIdPtr: Int): Int
    @JsName("_uapmd_instancing_destroy")
    fun uapmdInstancingDestroy(handle: Int)
    @JsName("_uapmd_instancing_state")
    fun uapmdInstancingState(handle: Int): Int
    /** Callback: void(const char* error) */
    @JsName("_uapmd_instancing_make_alive")
    fun uapmdInstancingMakeAlive(handle: Int, callback: Int, ctx: Int)
}

// ── External adapter declarations ────────────────────────────────────────────

@JsModule("uapmd-wasm-adapter")
@JsFun("(factory, wasmUrl) => factory({ locateFile: () => wasmUrl })")
private external fun invokeFactory(factory: JsAny, wasmUrl: String): Promise<UapmdCApiModule>

@JsFun("(mod) => globalThis.__uapmdWasmAdapter.setUapmdModule(mod)")
private external fun setUapmdModule(mod: JsAny)

@JsModule("uapmd-wasm-adapter")
private external fun getUapmdModule(): JsAny?

@JsModule("uapmd-wasm-adapter")
internal external fun readCStringFromHandle(fn: JsAny, handle: Int): String

@JsModule("uapmd-wasm-adapter")
internal external fun readCStringFromHandleIndex(fn: JsAny, handle: Int, index: Int): String

@JsModule("uapmd-wasm-adapter")
internal external fun withCString(str: String?, callback: JsAny): JsAny?

@JsModule("uapmd-wasm-adapter")
internal external fun withTwoCStrings(s1: String, s2: String, callback: JsAny): JsAny?

@JsModule("uapmd-wasm-adapter")
internal external fun withThreeCStrings(s1: String, s2: String, s3: String, callback: JsAny): JsAny?

@JsModule("uapmd-wasm-adapter")
internal external fun readTimelineState(ptr: Int): JsAny?

@JsModule("uapmd-wasm-adapter")
internal external fun readAudioFileProperties(ptr: Int): JsAny?

@JsModule("uapmd-wasm-adapter")
internal external fun readAudioDeviceInfo(ptr: Int): JsAny?

@JsModule("uapmd-wasm-adapter")
internal external fun readParameterMetadata(ptr: Int): JsAny?

@JsModule("uapmd-wasm-adapter")
internal external fun readPresetMetadata(ptr: Int): JsAny?

@JsModule("uapmd-wasm-adapter")
internal external fun readBlocklistEntry(ptr: Int): JsAny?

@JsModule("uapmd-wasm-adapter")
internal external fun withStruct(size: Int, cb: JsAny): JsAny?

@JsModule("uapmd-wasm-adapter")
internal external fun registerCallback(obj: JsAny): Int

@JsModule("uapmd-wasm-adapter")
internal external fun unregisterCallback(id: Int)

@JsFun("(cbId, dispatchName, sig) => globalThis.__uapmdWasmAdapter.makeCFunctionPtr(cbId, dispatchName, sig)")
internal external fun makeCFunctionPtr(cbId: Int, dispatchName: String, sig: String): Int

@JsFun("(ptr) => globalThis.__uapmdWasmAdapter.removeCFunctionPtr(ptr)")
internal external fun removeCFunctionPtr(ptr: Int)

@JsFun("(cbId, dispatchName) => globalThis.__uapmdWasmAdapter.makeStateCallbackPtr(cbId, dispatchName)")
internal external fun makeStateCallbackPtr(cbId: Int, dispatchName: String): Int

// ── Module singleton ──────────────────────────────────────────────────────────

private var _uapmdModule: UapmdCApiModule? = null

/** The initialized Emscripten module. Throws if not initialized. */
internal val wasmMod: UapmdCApiModule
    get() = _uapmdModule
        ?: error("uapmd Wasm module not initialized. Call initUapmdWasm(factory) first.")

/**
 * Initialize the uapmd Wasm module from an Emscripten factory Promise.
 * Typically called once at app startup:
 *
 *   import UapmdCApi from './uapmd-c-api.js'
 *   initUapmdWasm(UapmdCApi)
 *
 * @param factory The default export of `uapmd-c-api.js` (an async factory function).
 */
suspend fun initUapmdWasm(factory: JsAny, wasmUrl: String = "uapmd-c-api.wasm") {
    if (_uapmdModule != null) return
    val mod: UapmdCApiModule = invokeFactory(factory, wasmUrl).await()
    _uapmdModule = mod
    setUapmdModule(mod)
}

// ── String helper extensions ───────────────────────────────────────────────────

/**
 * Read a C output string produced by a two-parameter pattern:
 *   size_t fn(handle, char* buf, size_t buf_size)
 * Returns the string without the null terminator.
 */
internal fun readString(handle: Int, fn: UapmdCApiModule.(Int, Int, Int) -> Int): String {
    val mod = wasmMod
    val size = mod.fn(handle, 0, 0)
    if (size <= 0) return ""
    val ptr = mod.malloc(size)
    return try {
        mod.fn(handle, ptr, size)
        mod.utf8ToString(ptr, size - 1)
    } finally {
        mod.free(ptr)
    }
}

/**
 * Read a C string with an extra index parameter:
 *   size_t fn(handle, index, char* buf, size_t buf_size)
 */
internal fun readStringIndexed(handle: Int, index: Int, fn: UapmdCApiModule.(Int, Int, Int, Int) -> Int): String {
    val mod = wasmMod
    val size = mod.fn(handle, index, 0, 0)
    if (size <= 0) return ""
    val ptr = mod.malloc(size)
    return try {
        mod.fn(handle, index, ptr, size)
        mod.utf8ToString(ptr, size - 1)
    } finally {
        mod.free(ptr)
    }
}

/**
 * Allocate a temporary C string, call [block] with the pointer, then free.
 * If [str] is null or empty, passes pointer 0.
 */
internal fun <T> withCStringKt(str: String?, block: (Int) -> T): T {
    val mod = wasmMod
    if (str.isNullOrEmpty()) return block(0)
    val len = mod.lengthBytesUTF8(str) + 1
    val ptr = mod.malloc(len)
    return try {
        mod.stringToUTF8(str, ptr, len)
        block(ptr)
    } finally {
        mod.free(ptr)
    }
}

internal fun <T> withTwoCStringsKt(s1: String, s2: String, block: (Int, Int) -> T): T =
    withCStringKt(s1) { p1 -> withCStringKt(s2) { p2 -> block(p1, p2) } }

internal fun <T> withThreeCStringsKt(s1: String, s2: String, s3: String, block: (Int, Int, Int) -> T): T =
    withCStringKt(s1) { p1 -> withCStringKt(s2) { p2 -> withCStringKt(s3) { p3 -> block(p1, p2, p3) } } }

// ── Global callback dispatch (called from JS adapter) ────────────────────────
// Each @JsExport function receives cbId (from the registry) + C callback args.

internal val pendingCreateInstanceCallbacks = mutableMapOf<Int, (Int, String?) -> Unit>()
internal val pendingAddPluginCallbacks      = mutableMapOf<Int, (Int, Int, String?) -> Unit>()
internal val pendingMakeAliveCallbacks      = mutableMapOf<Int, (String?) -> Unit>()
internal val eventOutputCallbacks           = mutableMapOf<Int, (Int, IntArray, Int) -> Unit>()
internal val midiInputHandlers              = mutableMapOf<Int, (UIntArray, Long) -> Unit>()
internal val scanObservers                  = mutableMapOf<Int, ScanObserver>()
internal val pendingRenderCallbacks         = mutableMapOf<Int, ((OfflineRenderProgress) -> Unit)?>()
internal val pendingRenderCancelCallbacks   = mutableMapOf<Int, (() -> Boolean)?>()
internal val pendingRequestStateCallbacks   = mutableMapOf<Int, (ByteArray?, String?) -> Unit>()
internal val pendingLoadStateCallbacks      = mutableMapOf<Int, (String?) -> Unit>()

@JsExport
fun uapmdDispatchCreateInstance(cbId: Int, instanceId: Int, errorPtr: Int) {
    val error = if (errorPtr != 0) wasmMod.utf8ToString(errorPtr) else null
    pendingCreateInstanceCallbacks.remove(cbId)?.invoke(instanceId, error)
}

@JsExport
fun uapmdDispatchAddPlugin(cbId: Int, instanceId: Int, trackIndex: Int, errorPtr: Int) {
    val error = if (errorPtr != 0) wasmMod.utf8ToString(errorPtr) else null
    pendingAddPluginCallbacks.remove(cbId)?.invoke(instanceId, trackIndex, error)
}

@JsExport
fun uapmdDispatchMakeAlive(cbId: Int, errorPtr: Int) {
    val error = if (errorPtr != 0) wasmMod.utf8ToString(errorPtr) else null
    pendingMakeAliveCallbacks.remove(cbId)?.invoke(error)
}

@JsExport
fun uapmdDispatchEventOutput(cbId: Int, instanceId: Int, dataPtr: Int, size: Int) {
    val cb = eventOutputCallbacks[cbId] ?: return
    val data = IntArray(size / 4) { i ->
        wasmMod.getValue(dataPtr + i * 4, "i32").toInt()
    }
    cb(instanceId, data, size)
}

@JsExport
fun uapmdDispatchMidiInput(cbId: Int, messagesPtr: Int, count: Int, timestamp: Double) {
    val handler = midiInputHandlers[cbId] ?: return
    val data = UIntArray(count) { i ->
        wasmMod.getValue(messagesPtr + i * 4, "i32").toUInt()
    }
    handler(data, timestamp.toLong())
}

@JsExport fun uapmdDispatchScanSlowStart(cbId: Int, total: Int)             { scanObservers[cbId]?.onSlowScanStarted(total.toUInt()) }
@JsExport fun uapmdDispatchScanBundleStart(cbId: Int, pathPtr: Int)         { scanObservers[cbId]?.onBundleScanStarted(if (pathPtr != 0) wasmMod.utf8ToString(pathPtr) else "") }
@JsExport fun uapmdDispatchScanBundleComplete(cbId: Int, pathPtr: Int)      { scanObservers[cbId]?.onBundleScanCompleted(if (pathPtr != 0) wasmMod.utf8ToString(pathPtr) else "") }
@JsExport fun uapmdDispatchScanSlowComplete(cbId: Int)                      { scanObservers[cbId]?.onSlowScanCompleted() }
@JsExport fun uapmdDispatchScanError(cbId: Int, msgPtr: Int)                { scanObservers[cbId]?.onErrorOccurred(if (msgPtr != 0) wasmMod.utf8ToString(msgPtr) else "") }
@JsExport fun uapmdDispatchScanCancel(cbId: Int): Boolean                   = scanObservers[cbId]?.shouldCancel() ?: false

@JsExport
fun uapmdDispatchRenderProgress(cbId: Int, progress: Double, renderedSec: Double, totalSec: Double, renderedFrames: Double, totalFrames: Double) {
    pendingRenderCallbacks[cbId]?.invoke(OfflineRenderProgress(progress, renderedSec, totalSec, renderedFrames.toLong(), totalFrames.toLong()))
}

@JsExport
fun uapmdDispatchRenderCancel(cbId: Int): Boolean =
    pendingRenderCancelCallbacks[cbId]?.invoke() ?: false

@JsExport
fun uapmdDispatchRequestState(cbId: Int, dataPtr: Int, size: Int, errorPtr: Int) {
    val data = if (dataPtr != 0 && size > 0) {
        ByteArray(size) { i -> wasmMod.getValue(dataPtr + i, "i8").toInt().toByte() }
    } else null
    val error = if (errorPtr != 0) wasmMod.utf8ToString(errorPtr) else null
    pendingRequestStateCallbacks.remove(cbId)?.invoke(data, error)
}

@JsExport
fun uapmdDispatchLoadState(cbId: Int, errorPtr: Int) {
    val error = if (errorPtr != 0) wasmMod.utf8ToString(errorPtr) else null
    pendingLoadStateCallbacks.remove(cbId)?.invoke(error)
}
