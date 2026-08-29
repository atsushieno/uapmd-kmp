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
    /* ── Document provider (uapmd-c-file.h) ──────────────────────────────── */
    @JsName("_uapmd_app_document_provider")
    fun uapmdAppDocumentProvider(app: Int): Int
    /** Callback: void(uapmd_document_pick_result_t result, void* user_data) */
    @JsName("_uapmd_document_provider_pick_open")
    fun uapmdDocumentProviderPickOpen(
        provider: Int, filters: Int, filterCount: Int, allowMultiple: Boolean,
        userData: Int, callback: Int
    )
    /** Callback: void(uapmd_document_pick_result_t result, void* user_data) */
    @JsName("_uapmd_document_provider_pick_save")
    fun uapmdDocumentProviderPickSave(
        provider: Int, defaultName: Int, filters: Int, filterCount: Int,
        userData: Int, callback: Int
    )
    /** Callback: void(uapmd_document_io_result_t result, const char* path, void* user_data) */
    @JsName("_uapmd_document_provider_resolve_to_path")
    fun uapmdDocumentProviderResolveToPath(provider: Int, handle: Int, userData: Int, callback: Int)
    /** Callback: void(uapmd_document_io_result_t result, void* user_data) */
    @JsName("_uapmd_app_save_project_to_document")
    fun uapmdAppSaveProjectToDocument(app: Int, handle: Int, userData: Int, callback: Int)
    @JsName("_uapmd_document_provider_tick")
    fun uapmdDocumentProviderTick(provider: Int)
    @JsName("_uapmd_document_pick_result_free")
    fun uapmdDocumentPickResultFree(result: Int)

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
        nameBuf: Int, nameBufSize: Int,
        vendorBuf: Int, vendorBufSize: Int
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
    fun uapmdNodeScheduleEvents(handle: Int, timestamp: Long, eventsPtr: Int, eventsSize: Int): Boolean
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
    fun uapmdEngineEnqueueUmp(handle: Int, instanceId: Int, umpPtr: Int, count: Int, timestamp: Long)
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
    fun uapmdTlSetLoop(handle: Int, enabled: Boolean, startPtr: Int, endPtr: Int)
    @JsName("_uapmd_tl_remove_clip")
    fun uapmdTlRemoveClip(handle: Int, trackIndex: Int, clipId: Int): Boolean
    @JsName("_uapmd_tl_load_project")
    fun uapmdTlLoadProject(outPtr: Int, handle: Int, filePathPtr: Int)
    @JsName("_uapmd_tl_calculate_content_bounds")
    fun uapmdTlCalculateContentBounds(outPtr: Int, handle: Int)

    // Struct-returning functions use sret: first arg is a pointer where the
    // uapmd_clip_add_result_t return value will be written. Position args are
    // also passed by pointer (uapmd_timeline_position_t is 16 bytes > ABI limit).
    @JsName("_uapmd_tl_add_audio_clip")
    fun uapmdTlAddAudioClip(outPtr: Int, handle: Int, trackIndex: Int, posPtr: Int, reader: Int, filePathPtr: Int)
    @JsName("_uapmd_tl_add_midi_clip_from_file")
    fun uapmdTlAddMidiClipFromFile(outPtr: Int, handle: Int, trackIndex: Int, posPtr: Int, filePathPtr: Int, nrpnMapping: Boolean)
    @JsName("_uapmd_tl_add_midi_clip_from_data")
    fun uapmdTlAddMidiClipFromData(outPtr: Int, handle: Int, trackIndex: Int, posPtr: Int, dataPtr: Int, dataSize: Int, tickTimestampsPtr: Int, tickCount: Int, tickResolution: Int, clipTempo: Double, tempoChangesPtr: Int, tempoChangeCount: Int, timeSigChangesPtr: Int, timeSigChangeCount: Int, clipNamePtr: Int, nrpnMapping: Boolean, needsFileSave: Boolean)

    // ── Timeline track ─────────────────────────────────────────────────────
    @JsName("_uapmd_tt_reference_id")
    fun uapmdTtReferenceId(handle: Int): Int
    @JsName("_uapmd_engine_track_freeze_policy")
    fun uapmdEngineTrackFreezePolicy(engine: Int, trackIndex: Int): Int
    @JsName("_uapmd_engine_track_freeze_state")
    fun uapmdEngineTrackFreezeState(engine: Int, trackIndex: Int): Int
    /** out is uapmd_offline_render_progress_t: sizeof=40 align=8 (emscripten clang). */
    @JsName("_uapmd_engine_track_freeze_render_progress")
    fun uapmdEngineTrackFreezeRenderProgress(engine: Int, trackIndex: Int, out: Int): Boolean
    @JsName("_uapmd_engine_is_track_busy")
    fun uapmdEngineIsTrackBusy(engine: Int, trackIndex: Int): Boolean
    @JsName("_uapmd_tt_channel_count")
    fun uapmdTtChannelCount(handle: Int): Int
    @JsName("_uapmd_tt_sample_rate")
    fun uapmdTtSampleRate(handle: Int): Int
    @JsName("_uapmd_tt_clip_manager")
    fun uapmdTtClipManager(handle: Int): Int

    // ── Clip manager ────────────────────────────────────────────────────────
    @JsName("_uapmd_cm_clip_count")
    fun uapmdCmClipCount(handle: Int): Int
    @JsName("_uapmd_cm_get_all_clips")
    fun uapmdCmGetAllClips(handle: Int, outPtr: Int, maxCount: Int): Int

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
    fun uapmdAudioFileReaderReadFrames(handle: Int, startFrame: Long, framesToRead: Long, channelsPtr: Int, channelCount: Int)

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
    fun uapmdMidiIoSend(handle: Int, messagesPtr: Int, count: Int, timestamp: Long)

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
    @JsName("_uapmd_engine_midi_recorder")
    fun uapmdEngineMidiRecorder(engine: Int): Int
    @JsName("_uapmd_midi_recorder_stop")
    fun uapmdMidiRecorderStop(rec: Int)
    @JsName("_uapmd_midi_recorder_cancel")
    fun uapmdMidiRecorderCancel(rec: Int)
    @JsName("_uapmd_midi_recorder_is_recording")
    fun uapmdMidiRecorderIsRecording(rec: Int): Boolean
    @JsName("_uapmd_track_get_gain")
    fun uapmdTrackGetGain(track: Int): Double
    @JsName("_uapmd_track_get_muted")
    fun uapmdTrackGetMuted(track: Int): Boolean
    @JsName("_uapmd_track_get_solo")
    fun uapmdTrackGetSolo(track: Int): Boolean
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
    fun uapmdScanToolPerformScanning(handle: Int, requireFast: Boolean, observerPtr: Int)
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

    // ── Project archive ────────────────────────────────────────────────────
    @JsName("_uapmd_project_archive_is_archive")
    fun uapmdProjectArchiveIsArchive(pathPtr: Int): Boolean
    // Returns a heap-allocated uapmd_project_archive_extract_result_t* pointer
    @JsName("_uapmd_project_archive_extract")
    fun uapmdProjectArchiveExtract(archivePathPtr: Int, destDirPtr: Int): Int
    @JsName("_uapmd_project_archive_extract_result_free")
    fun uapmdProjectArchiveExtractResultFree(resultPtr: Int)

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

    // ── Project history: ProjectUndoEngine (uapmd 0.5.6) ───────────────────
    //
    // Functions returning a C struct take a hidden result pointer as their
    // FIRST argument (Emscripten sret), and a struct passed by value is passed
    // as a pointer in its declared position.

    @JsName("_uapmd_undo_engine_get_state")
    fun uapmdUndoEngineGetState(eng: Int, outPtr: Int): Boolean
    @JsName("_uapmd_undo_engine_undo")
    fun uapmdUndoEngineUndo(eng: Int, userData: Int, callback: Int)
    @JsName("_uapmd_undo_engine_redo")
    fun uapmdUndoEngineRedo(eng: Int, userData: Int, callback: Int)
    @JsName("_uapmd_undo_engine_begin_compound")
    fun uapmdUndoEngineBeginCompound(outPtr: Int, eng: Int, descPtr: Int, origin: Int)
    @JsName("_uapmd_undo_engine_end_compound")
    fun uapmdUndoEngineEndCompound(eng: Int, userData: Int, callback: Int)
    @JsName("_uapmd_undo_engine_cancel_compound")
    fun uapmdUndoEngineCancelCompound(eng: Int, userData: Int, callback: Int)
    @JsName("_uapmd_undo_engine_begin_gesture")
    fun uapmdUndoEngineBeginGesture(outPtr: Int, eng: Int, descPtr: Int, origin: Int)
    @JsName("_uapmd_undo_engine_end_gesture")
    fun uapmdUndoEngineEndGesture(eng: Int, userData: Int, callback: Int)
    @JsName("_uapmd_undo_engine_cancel_gesture")
    fun uapmdUndoEngineCancelGesture(eng: Int, userData: Int, callback: Int)
    @JsName("_uapmd_undo_engine_clear")
    fun uapmdUndoEngineClear(eng: Int, markCurrentStateSaved: Boolean): Boolean
    @JsName("_uapmd_undo_engine_mark_saved")
    fun uapmdUndoEngineMarkSaved(eng: Int): Boolean
    @JsName("_uapmd_undo_engine_shutdown")
    fun uapmdUndoEngineShutdown(eng: Int)

    // ── Project history: ProjectCommandManager ─────────────────────────────

    @JsName("_uapmd_command_manager_get_state")
    fun uapmdCommandManagerGetState(cm: Int, outPtr: Int): Boolean
    @JsName("_uapmd_command_manager_history")
    fun uapmdCommandManagerHistory(cm: Int): Int
    @JsName("_uapmd_command_manager_undo")
    fun uapmdCommandManagerUndo(cm: Int, userData: Int, callback: Int)
    @JsName("_uapmd_command_manager_redo")
    fun uapmdCommandManagerRedo(cm: Int, userData: Int, callback: Int)
    @JsName("_uapmd_command_manager_begin_step")
    fun uapmdCommandManagerBeginStep(outPtr: Int, cm: Int, descPtr: Int, origin: Int)
    @JsName("_uapmd_command_manager_end_step")
    fun uapmdCommandManagerEndStep(cm: Int, userData: Int, callback: Int)
    @JsName("_uapmd_command_manager_cancel_step")
    fun uapmdCommandManagerCancelStep(cm: Int, userData: Int, callback: Int)
    @JsName("_uapmd_command_manager_begin_gesture")
    fun uapmdCommandManagerBeginGesture(outPtr: Int, cm: Int, descPtr: Int, origin: Int)
    @JsName("_uapmd_command_manager_end_gesture")
    fun uapmdCommandManagerEndGesture(cm: Int, userData: Int, callback: Int)
    @JsName("_uapmd_command_manager_cancel_gesture")
    fun uapmdCommandManagerCancelGesture(cm: Int, userData: Int, callback: Int)
    @JsName("_uapmd_command_manager_shutdown")
    fun uapmdCommandManagerShutdown(cm: Int)

    // ── Project history: ProjectCommands ───────────────────────────────────

    @JsName("_uapmd_commands_history")
    fun uapmdCommandsHistory(cmd: Int): Int
    @JsName("_uapmd_commands_set_clip_enabled")
    fun uapmdCommandsSetClipEnabled(cmd: Int, t: Int, c: Int, v: Boolean, o: Int): Boolean
    @JsName("_uapmd_commands_set_clip_anchor")
    fun uapmdCommandsSetClipAnchor(cmd: Int, t: Int, c: Int, anchorPtr: Int, o: Int): Boolean
    @JsName("_uapmd_commands_set_clip_gain")
    fun uapmdCommandsSetClipGain(cmd: Int, t: Int, c: Int, v: Double, o: Int): Boolean
    @JsName("_uapmd_commands_set_clip_muted")
    fun uapmdCommandsSetClipMuted(cmd: Int, t: Int, c: Int, v: Boolean, o: Int): Boolean
    @JsName("_uapmd_commands_set_clip_name")
    fun uapmdCommandsSetClipName(cmd: Int, t: Int, c: Int, namePtr: Int, o: Int): Boolean
    @JsName("_uapmd_commands_set_clip_filepath")
    fun uapmdCommandsSetClipFilepath(cmd: Int, t: Int, c: Int, pathPtr: Int, o: Int): Boolean
    @JsName("_uapmd_commands_set_clip_needs_file_save")
    fun uapmdCommandsSetClipNeedsFileSave(cmd: Int, t: Int, c: Int, v: Boolean, o: Int): Boolean
    @JsName("_uapmd_commands_set_clip_markers")
    fun uapmdCommandsSetClipMarkers(cmd: Int, t: Int, c: Int, markersPtr: Int, count: Int, o: Int): Boolean
    @JsName("_uapmd_commands_set_clip_audio_warps")
    fun uapmdCommandsSetClipAudioWarps(cmd: Int, t: Int, c: Int, warpsPtr: Int, count: Int, o: Int): Boolean
    @JsName("_uapmd_commands_set_track_gain")
    fun uapmdCommandsSetTrackGain(cmd: Int, t: Int, v: Double, o: Int): Boolean
    @JsName("_uapmd_commands_set_track_muted")
    fun uapmdCommandsSetTrackMuted(cmd: Int, t: Int, v: Boolean, o: Int): Boolean
    @JsName("_uapmd_commands_set_track_solo")
    fun uapmdCommandsSetTrackSolo(cmd: Int, t: Int, v: Boolean, o: Int): Boolean
    @JsName("_uapmd_commands_set_track_bypassed")
    fun uapmdCommandsSetTrackBypassed(cmd: Int, t: Int, v: Boolean, o: Int): Boolean
    @JsName("_uapmd_commands_set_track_freeze_policy_enabled")
    fun uapmdCommandsSetTrackFreezePolicyEnabled(cmd: Int, t: Int, v: Boolean, o: Int): Boolean
    @JsName("_uapmd_commands_set_plugin_bypassed")
    fun uapmdCommandsSetPluginBypassed(cmd: Int, id: Int, v: Boolean, o: Int): Boolean
    @JsName("_uapmd_commands_set_plugin_parameter_value")
    fun uapmdCommandsSetPluginParameterValue(cmd: Int, id: Int, idx: Int, v: Double, o: Int): Boolean
    @JsName("_uapmd_commands_set_plugin_per_note_controller_value")
    fun uapmdCommandsSetPluginPerNoteControllerValue(
        cmd: Int, id: Int, contextType: Int, note: Int, channel: Int, group: Int, extra: Int,
        idx: Int, v: Double, o: Int
    ): Boolean
    @JsName("_uapmd_commands_set_plugin_group")
    fun uapmdCommandsSetPluginGroup(cmd: Int, id: Int, group: Int, o: Int): Boolean
    @JsName("_uapmd_commands_set_master_track_markers")
    fun uapmdCommandsSetMasterTrackMarkers(cmd: Int, markersPtr: Int, count: Int, o: Int): Boolean

    // ── Project history: ProjectAddressBook ────────────────────────────────

    @JsName("_uapmd_addresses_timeline_track")
    fun uapmdAddressesTimelineTrack(ab: Int, refIdPtr: Int): Int
    @JsName("_uapmd_addresses_sequencer_track")
    fun uapmdAddressesSequencerTrack(ab: Int, refIdPtr: Int): Int
    @JsName("_uapmd_addresses_track_index")
    fun uapmdAddressesTrackIndex(ab: Int, refIdPtr: Int): Int
    @JsName("_uapmd_addresses_clip_id")
    fun uapmdAddressesClipId(ab: Int, addressPtr: Int): Int
    @JsName("_uapmd_addresses_plugin_instance_id")
    fun uapmdAddressesPluginInstanceId(ab: Int, addressPtr: Int): Int
    @JsName("_uapmd_addresses_track_reference_id")
    fun uapmdAddressesTrackReferenceId(ab: Int, trackIndex: Int): Int
    @JsName("_uapmd_addresses_clip_address")
    fun uapmdAddressesClipAddress(ab: Int, trackIndex: Int, clipId: Int, outPtr: Int): Boolean
    @JsName("_uapmd_addresses_plugin_address")
    fun uapmdAddressesPluginAddress(ab: Int, instanceId: Int, outPtr: Int): Boolean

    // ── Fragments ──────────────────────────────────────────────────────────

    @JsName("_uapmd_clip_fragment_destroy")
    fun uapmdClipFragmentDestroy(fragment: Int)
    @JsName("_uapmd_clip_fragment_is_midi")
    fun uapmdClipFragmentIsMidi(fragment: Int): Boolean
    @JsName("_uapmd_clip_fragment_get_clip")
    fun uapmdClipFragmentGetClip(fragment: Int, outPtr: Int): Boolean
    @JsName("_uapmd_clip_fragment_get_ump_events")
    fun uapmdClipFragmentGetUmpEvents(fragment: Int, outPtr: Int, outCount: Int): Int
    @JsName("_uapmd_clip_fragment_get_ump_tick_timestamps")
    fun uapmdClipFragmentGetUmpTickTimestamps(fragment: Int, outPtr: Int, outCount: Int): Int
    @JsName("_uapmd_clip_fragment_extension_state_count")
    fun uapmdClipFragmentExtensionStateCount(fragment: Int): Int
    @JsName("_uapmd_clip_fragment_extension_state_key")
    fun uapmdClipFragmentExtensionStateKey(fragment: Int, index: Int, buf: Int, bufSize: Int): Int
    @JsName("_uapmd_clip_fragment_extension_state_data")
    fun uapmdClipFragmentExtensionStateData(fragment: Int, index: Int, buf: Int, bufSize: Int): Int

    @JsName("_uapmd_track_fragment_destroy")
    fun uapmdTrackFragmentDestroy(fragment: Int)
    @JsName("_uapmd_track_fragment_reference_id")
    fun uapmdTrackFragmentReferenceId(fragment: Int, buf: Int, bufSize: Int): Int
    @JsName("_uapmd_track_fragment_volume")
    fun uapmdTrackFragmentVolume(fragment: Int): Double
    @JsName("_uapmd_track_fragment_muted")
    fun uapmdTrackFragmentMuted(fragment: Int): Boolean
    @JsName("_uapmd_track_fragment_solo")
    fun uapmdTrackFragmentSolo(fragment: Int): Boolean
    @JsName("_uapmd_track_fragment_graph_type")
    fun uapmdTrackFragmentGraphType(fragment: Int, buf: Int, bufSize: Int): Int
    @JsName("_uapmd_track_fragment_graph_bytes")
    fun uapmdTrackFragmentGraphBytes(fragment: Int, buf: Int, bufSize: Int): Int
    @JsName("_uapmd_track_fragment_clip_count")
    fun uapmdTrackFragmentClipCount(fragment: Int): Int
    @JsName("_uapmd_track_fragment_get_clip")
    fun uapmdTrackFragmentGetClip(fragment: Int, index: Int): Int
    @JsName("_uapmd_track_fragment_plugin_count")
    fun uapmdTrackFragmentPluginCount(fragment: Int): Int
    @JsName("_uapmd_track_fragment_get_plugin")
    fun uapmdTrackFragmentGetPlugin(fragment: Int, index: Int, outPtr: Int): Boolean

    // ── TimelineFacade history accessors and undoable mutations ────────────

    @JsName("_uapmd_tl_undo_engine")
    fun uapmdTlUndoEngine(tl: Int): Int
    @JsName("_uapmd_tl_commands")
    fun uapmdTlCommands(tl: Int): Int
    @JsName("_uapmd_tl_addresses")
    fun uapmdTlAddresses(tl: Int): Int
    @JsName("_uapmd_tl_begin_document_transaction")
    fun uapmdTlBeginDocumentTransaction(tl: Int)
    @JsName("_uapmd_tl_end_document_transaction")
    fun uapmdTlEndDocumentTransaction(tl: Int)
    @JsName("_uapmd_tl_remove_clip_with_origin")
    fun uapmdTlRemoveClipWithOrigin(tl: Int, t: Int, c: Int, o: Int): Boolean
    @JsName("_uapmd_tl_clear_clips_from_track")
    fun uapmdTlClearClipsFromTrack(tl: Int, t: Int, o: Int): Boolean
    @JsName("_uapmd_tl_clip_enabled")
    fun uapmdTlClipEnabled(tl: Int, t: Int, c: Int): Boolean
    @JsName("_uapmd_tl_replace_midi_clip_content")
    fun uapmdTlReplaceMidiClipContent(
        tl: Int, t: Int, c: Int, eventsPtr: Int, eventCount: Int, ticksPtr: Int, tickCount: Int, o: Int
    ): Boolean
    @JsName("_uapmd_tl_replace_audio_clip_content")
    fun uapmdTlReplaceAudioClipContent(
        tl: Int, t: Int, c: Int, filepathPtr: Int,
        markersPtr: Int, markerCount: Int, warpsPtr: Int, warpCount: Int,
        masterMarkersPtr: Int, masterMarkerCount: Int, o: Int
    ): Boolean
    @JsName("_uapmd_tl_capture_clip_fragment")
    fun uapmdTlCaptureClipFragment(tl: Int, t: Int, c: Int): Int
    @JsName("_uapmd_tl_attach_clip_fragment")
    fun uapmdTlAttachClipFragment(outPtr: Int, tl: Int, t: Int, fragment: Int, idPolicy: Int)
    @JsName("_uapmd_tl_capture_track_fragment")
    fun uapmdTlCaptureTrackFragment(tl: Int, t: Int, userData: Int, callback: Int)
    @JsName("_uapmd_tl_attach_track_fragment")
    fun uapmdTlAttachTrackFragment(tl: Int, fragment: Int, optionsPtr: Int, userData: Int, callback: Int)
    @JsName("_uapmd_tl_add_empty_track")
    fun uapmdTlAddEmptyTrackUndoable(tl: Int, o: Int, userData: Int, callback: Int)
    @JsName("_uapmd_tl_remove_track")
    fun uapmdTlRemoveTrackUndoable(tl: Int, t: Int, o: Int, userData: Int, callback: Int)
    @JsName("_uapmd_tl_record_track_addition")
    fun uapmdTlRecordTrackAddition(tl: Int, t: Int, o: Int, userData: Int, callback: Int)
    @JsName("_uapmd_tl_set_plugin_state")
    fun uapmdTlSetPluginState(tl: Int, id: Int, statePtr: Int, stateSize: Int, o: Int, userData: Int, callback: Int)
    @JsName("_uapmd_tl_load_plugin_preset")
    fun uapmdTlLoadPluginPreset(tl: Int, id: Int, presetIndex: Int, o: Int, userData: Int, callback: Int)
    @JsName("_uapmd_tl_record_plugin_instance_addition")
    fun uapmdTlRecordPluginInstanceAddition(tl: Int, id: Int, o: Int, userData: Int, callback: Int)
    @JsName("_uapmd_tl_remove_plugin_instance")
    fun uapmdTlRemovePluginInstanceUndoable(tl: Int, id: Int, o: Int, userData: Int, callback: Int)
    @JsName("_uapmd_tl_has_pending_plugin_mutations")
    fun uapmdTlHasPendingPluginMutations(tl: Int): Boolean

    // ── Engine dirty state, master markers, addin extension points ─────────

    @JsName("_uapmd_engine_is_project_dirty")
    fun uapmdEngineIsProjectDirty(engine: Int): Boolean
    @JsName("_uapmd_engine_is_track_dirty")
    fun uapmdEngineIsTrackDirty(engine: Int, t: Int): Boolean
    @JsName("_uapmd_engine_mark_track_dirty")
    fun uapmdEngineMarkTrackDirty(engine: Int, t: Int, dirty: Boolean)
    @JsName("_uapmd_engine_clear_track_dirty_state")
    fun uapmdEngineClearTrackDirtyState(engine: Int)
    @JsName("_uapmd_engine_master_marker_count")
    fun uapmdEngineMasterMarkerCount(engine: Int): Int
    @JsName("_uapmd_engine_get_master_marker")
    fun uapmdEngineGetMasterMarker(engine: Int, index: Int, outPtr: Int): Boolean
    @JsName("_uapmd_engine_set_master_markers")
    fun uapmdEngineSetMasterMarkers(engine: Int, markersPtr: Int, count: Int)
    @JsName("_uapmd_engine_register_addin_extension_points")
    fun uapmdEngineRegisterAddinExtensionPoints(engine: Int, mgr: Int)

    // ── AddinManager ───────────────────────────────────────────────────────

    @JsName("_uapmd_addin_manager_create")
    fun uapmdAddinManagerCreate(): Int
    @JsName("_uapmd_addin_manager_destroy")
    fun uapmdAddinManagerDestroy(mgr: Int)
    @JsName("_uapmd_addin_manager_initialize")
    fun uapmdAddinManagerInitialize(mgr: Int)
    @JsName("_uapmd_addin_manager_set_enabled")
    fun uapmdAddinManagerSetEnabled(mgr: Int, packageIdPtr: Int, addinIdPtr: Int, enabled: Boolean): Boolean
    @JsName("_uapmd_addin_manager_shutdown")
    fun uapmdAddinManagerShutdown(mgr: Int)
    @JsName("_uapmd_addin_manager_directory_count")
    fun uapmdAddinManagerDirectoryCount(mgr: Int): Int
    @JsName("_uapmd_addin_manager_get_directory")
    fun uapmdAddinManagerGetDirectory(mgr: Int, index: Int, buf: Int, bufSize: Int): Int
    @JsName("_uapmd_addin_manager_addin_count")
    fun uapmdAddinManagerAddinCount(mgr: Int): Int
    @JsName("_uapmd_addin_manager_get_addin")
    fun uapmdAddinManagerGetAddin(mgr: Int, index: Int, outPtr: Int): Boolean
    @JsName("_uapmd_addin_manager_last_error")
    fun uapmdAddinManagerLastError(mgr: Int, buf: Int, bufSize: Int): Int
    @JsName("_uapmd_addin_supports_dynamic_loading")
    fun uapmdAddinSupportsDynamicLoading(): Boolean
    // ── AppModel / TransportController ─────────────────────────────────────

    @JsName("_uapmd_app_instantiate")
    fun uapmdAppInstantiate()
    @JsName("_uapmd_app_instance")
    fun uapmdAppInstance(): Int
    @JsName("_uapmd_app_cleanup")
    fun uapmdAppCleanup()

    @JsName("_uapmd_app_sequencer")
    fun uapmdAppSequencer(app: Int): Int
    @JsName("_uapmd_app_transport")
    fun uapmdAppTransport(app: Int): Int
    @JsName("_uapmd_app_sample_rate")
    fun uapmdAppSampleRate(app: Int): Int
    @JsName("_uapmd_app_track_count")
    fun uapmdAppTrackCount(app: Int): Int

    @JsName("_uapmd_app_is_scanning")
    fun uapmdAppIsScanning(app: Int): Boolean
    @JsName("_uapmd_app_is_audio_engine_enabled")
    fun uapmdAppIsAudioEngineEnabled(app: Int): Boolean
    @JsName("_uapmd_app_set_audio_engine_enabled")
    fun uapmdAppSetAudioEngineEnabled(app: Int, enabled: Boolean)
    @JsName("_uapmd_app_toggle_audio_engine")
    fun uapmdAppToggleAudioEngine(app: Int)
    @JsName("_uapmd_app_update_audio_device_settings")
    fun uapmdAppUpdateAudioDeviceSettings(app: Int, sampleRate: Int, bufferSize: Int)
    @JsName("_uapmd_app_set_auto_buffer_size_enabled")
    fun uapmdAppSetAutoBufferSizeEnabled(app: Int, enabled: Boolean)
    @JsName("_uapmd_app_auto_buffer_size_enabled")
    fun uapmdAppAutoBufferSizeEnabled(app: Int): Boolean

    @JsName("_uapmd_app_notify_ui_ready")
    fun uapmdAppNotifyUiReady(app: Int)
    @JsName("_uapmd_app_notify_persistent_storage_ready")
    fun uapmdAppNotifyPersistentStorageReady(app: Int)

    @JsName("_uapmd_transport_is_playing")
    fun uapmdTransportIsPlaying(tc: Int): Boolean
    @JsName("_uapmd_transport_is_paused")
    fun uapmdTransportIsPaused(tc: Int): Boolean
    @JsName("_uapmd_transport_is_recording")
    fun uapmdTransportIsRecording(tc: Int): Boolean
    @JsName("_uapmd_transport_get_volume")
    fun uapmdTransportGetVolume(tc: Int): Float
    @JsName("_uapmd_transport_set_volume")
    fun uapmdTransportSetVolume(tc: Int, volume: Float)
    @JsName("_uapmd_transport_play")
    fun uapmdTransportPlay(tc: Int)
    @JsName("_uapmd_transport_stop")
    fun uapmdTransportStop(tc: Int)
    @JsName("_uapmd_transport_pause")
    fun uapmdTransportPause(tc: Int)
    @JsName("_uapmd_transport_resume")
    fun uapmdTransportResume(tc: Int)
    @JsName("_uapmd_transport_record")
    fun uapmdTransportRecord(tc: Int)

    @JsName("_uapmd_app_perform_plugin_scanning")
    fun uapmdAppPerformPluginScanning(app: Int, forceRescan: Boolean, request: Int, remoteTimeoutSeconds: Double, requireFastScanning: Boolean)
    @JsName("_uapmd_app_slow_scan_progress")
    fun uapmdAppSlowScanProgress(out: Int, app: Int)
    @JsName("_uapmd_app_last_plugin_scan_error")
    fun uapmdAppLastPluginScanError(app: Int, buf: Int, bufSize: Int): Int
    @JsName("_uapmd_app_cancel_plugin_scanning")
    fun uapmdAppCancelPluginScanning(app: Int)
    @JsName("_uapmd_app_generate_scan_report")
    fun uapmdAppGenerateScanReport(app: Int, buf: Int, bufSize: Int): Int
    @JsName("_uapmd_app_refresh_master_tempo_map")
    fun uapmdAppRefreshMasterTempoMap(app: Int): Double
    @JsName("_uapmd_app_master_tempo_point_count")
    fun uapmdAppMasterTempoPointCount(app: Int): Int
    @JsName("_uapmd_app_get_master_tempo_point")
    fun uapmdAppGetMasterTempoPoint(app: Int, index: Int, out: Int): Boolean
    @JsName("_uapmd_app_master_time_signature_count")
    fun uapmdAppMasterTimeSignatureCount(app: Int): Int
    @JsName("_uapmd_app_get_master_time_signature")
    fun uapmdAppGetMasterTimeSignature(app: Int, index: Int, out: Int): Boolean
    @JsName("_uapmd_app_blocklist_count")
    fun uapmdAppBlocklistCount(app: Int): Int
    @JsName("_uapmd_app_get_blocklist_entry")
    fun uapmdAppGetBlocklistEntry(app: Int, index: Int, out: Int): Boolean
    @JsName("_uapmd_app_unblock_plugin")
    fun uapmdAppUnblockPlugin(app: Int, entryIdPtr: Int): Boolean
    @JsName("_uapmd_app_clear_plugin_blocklist")
    fun uapmdAppClearPluginBlocklist(app: Int)

    @JsName("_uapmd_app_add_track")
    fun uapmdAppAddTrack(app: Int, userData: Int, callback: Int)
    @JsName("_uapmd_app_remove_track")
    fun uapmdAppRemoveTrack(app: Int, trackIndex: Int, userData: Int, callback: Int)
    @JsName("_uapmd_app_remove_all_tracks")
    fun uapmdAppRemoveAllTracks(app: Int, userData: Int, callback: Int)

    @JsName("_uapmd_app_timeline_track_count")
    fun uapmdAppTimelineTrackCount(app: Int): Int
    @JsName("_uapmd_app_get_timeline_track")
    fun uapmdAppGetTimelineTrack(app: Int, index: Int): Int
    @JsName("_uapmd_app_master_timeline_track")
    fun uapmdAppMasterTimelineTrack(app: Int): Int
    @JsName("_uapmd_app_get_timeline_state")
    fun uapmdAppGetTimelineState(app: Int, out: Int): Boolean

    @JsName("_uapmd_app_get_history_state")
    fun uapmdAppGetHistoryState(app: Int, out: Int): Boolean
    @JsName("_uapmd_app_undo")
    fun uapmdAppUndo(app: Int, userData: Int, callback: Int)
    @JsName("_uapmd_app_redo")
    fun uapmdAppRedo(app: Int, userData: Int, callback: Int)

    @JsName("_uapmd_app_create_plugin_instance")
    fun uapmdAppCreatePluginInstance(app: Int, format: Int, pluginId: Int, trackIndex: Int, config: Int, userData: Int, callback: Int)
    @JsName("_uapmd_app_remove_plugin_instance")
    fun uapmdAppRemovePluginInstance(app: Int, instanceId: Int)
    @JsName("_uapmd_app_get_instance_group")
    fun uapmdAppGetInstanceGroup(app: Int, instanceId: Int): Int
    @JsName("_uapmd_app_set_instance_group")
    fun uapmdAppSetInstanceGroup(app: Int, instanceId: Int, group: Int): Boolean
    @JsName("_uapmd_app_enable_ump_device")
    fun uapmdAppEnableUmpDevice(app: Int, instanceId: Int, deviceName: Int)
    @JsName("_uapmd_app_disable_ump_device")
    fun uapmdAppDisableUmpDevice(app: Int, instanceId: Int)
    @JsName("_uapmd_app_request_show_instance_details")
    fun uapmdAppRequestShowInstanceDetails(app: Int, instanceId: Int)
    @JsName("_uapmd_app_request_show_plugin_ui")
    fun uapmdAppRequestShowPluginUi(app: Int, instanceId: Int)
    @JsName("_uapmd_app_hide_plugin_ui")
    fun uapmdAppHidePluginUi(app: Int, instanceId: Int)

    // Struct-returning functions take the result pointer as their FIRST argument (sret).
    @JsName("_uapmd_app_load_project")
    fun uapmdAppLoadProject(out: Int, app: Int, filePath: Int)
    @JsName("_uapmd_app_save_project_sync")
    fun uapmdAppSaveProjectSync(out: Int, app: Int, filePath: Int)
    @JsName("_uapmd_app_save_project")
    fun uapmdAppSaveProject(app: Int, filePath: Int, userData: Int, callback: Int)
    @JsName("_uapmd_app_load_project_from_handle_token")
    fun uapmdAppLoadProjectFromHandleToken(out: Int, app: Int, token: Int)

    @JsName("_uapmd_app_get_midi_clip_ump_events")
    fun uapmdAppGetMidiClipUmpEvents(out: Int, app: Int, trackIndex: Int, clipId: Int)
    @JsName("_uapmd_app_remove_ump_event_from_clip")
    fun uapmdAppRemoveUmpEventFromClip(app: Int, trackIndex: Int, clipId: Int, eventIndex: Int): Boolean
    @JsName("_uapmd_app_remove_clip_from_track")
    fun uapmdAppRemoveClipFromTrack(app: Int, trackIndex: Int, clipId: Int): Boolean
    /** Callback: void(bool success, const char* error, uint32_t importedTrackCount, void* userData) */
    /**
     * Count-then-fill: call with out=0/max=0 for the count, then again with a
     * buffer. uapmd_midi_note_t is 24 bytes (verified against emscripten clang):
     * start_seconds f64 @0, duration_seconds f64 @8, velocity f32 @16, note u8 @20.
     */
    @JsName("_uapmd_tl_get_clip_midi_notes")
    fun uapmdTlGetClipMidiNotes(tl: Int, trackIndex: Int, clipId: Int,
                                outNotes: Int, maxNotes: Int,
                                outMinNote: Int, outMaxNote: Int): Int
    @JsName("_uapmd_app_import_midi_tracks_from_file")
    fun uapmdAppImportMidiTracksFromFile(app: Int, filepath: Int, userData: Int, callback: Int)

    @JsName("_uapmd_app_ensure_track_uses_editor_graph")
    fun uapmdAppEnsureTrackUsesEditorGraph(app: Int, trackIndex: Int): Boolean
    @JsName("_uapmd_app_revert_track_to_simple_graph")
    fun uapmdAppRevertTrackToSimpleGraph(app: Int, trackIndex: Int): Boolean
    @JsName("_uapmd_app_get_clip_audio_events")
    fun uapmdAppGetClipAudioEvents(out: Int, app: Int, trackIndex: Int, clipId: Int)
    @JsName("_uapmd_app_set_clip_audio_events")
    fun uapmdAppSetClipAudioEvents(out: Int, app: Int, trackIndex: Int, clipId: Int, markers: Int, markerCount: Int, warps: Int, warpCount: Int)
    @JsName("_uapmd_app_get_track_graph_connections")
    fun uapmdAppGetTrackGraphConnections(out: Int, app: Int, trackIndex: Int)
    @JsName("_uapmd_app_get_track_graph_nodes")
    fun uapmdAppGetTrackGraphNodes(out: Int, app: Int, trackIndex: Int)
    @JsName("_uapmd_app_connect_track_graph")
    fun uapmdAppConnectTrackGraph(out: Int, app: Int, trackIndex: Int, connection: Int)

}


// ── External adapter declarations ────────────────────────────────────────────

@JsModule("uapmd-wasm-adapter")
@JsFun("(factory, wasmUrl) => factory({ locateFile: () => wasmUrl })")
private external fun invokeFactory(factory: JsAny, wasmUrl: String): Promise<UapmdCApiModule>

@JsFun("(mod) => { globalThis.__uapmdWasmAdapter.setUapmdModule(mod); globalThis.__uapmdWasmAdapter.setKotlinDispatchers(wasmExports); }")
private external fun setUapmdModule(mod: JsAny)

@JsFun("() => globalThis.__uapmdWasmAdapter.initBrowserFileSystem()")
private external fun initBrowserFileSystem(): Promise<JsAny?>

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
    // uapmd stores the plugin list cache under /browser/remidy-tooling; that path has to
    // exist (and be restored from IndexedDB) before anything reads or writes the catalog.
    initBrowserFileSystem().await<JsAny?>()
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
internal val pendingImportMidiTracksCallbacks = mutableMapOf<Int, (Boolean, String?, Int) -> Unit>()

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
fun uapmdDispatchImportMidiTracks(cbId: Int, success: Int, errorPtr: Int, importedTrackCount: Int) {
    val error = if (errorPtr != 0) wasmMod.utf8ToString(errorPtr) else null
    pendingImportMidiTracksCallbacks.remove(cbId)?.invoke(success != 0, error, importedTrackCount)
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


// ── Project history: BigInt-valued parameters ────────────────────────────────
//
// The Wasm module is linked with -sWASM_BIGINT=1, so scalar i64 parameters must
// arrive as BigInt. Passing the value as a decimal string keeps it exact for
// magnitudes beyond 2^53.

@JsFun("(mod, out, app, t, id) => mod._uapmd_app_disconnect_track_graph_connection(out, app, t, BigInt(id))")
internal external fun wasmAppDisconnectTrackGraph(
    mod: UapmdCApiModule, out: Int, app: Int, trackIndex: Int, connectionId: String
)

@JsFun("(mod, ptr, v) => { new DataView(mod.HEAPU8.buffer).setBigInt64(ptr, BigInt(v), true); }")
internal external fun wasmWriteI64(mod: UapmdCApiModule, ptr: Int, value: String)

@JsFun("(mod, ptr) => new DataView(mod.HEAPU8.buffer).getBigInt64(ptr, true).toString()")
internal external fun wasmReadI64(mod: UapmdCApiModule, ptr: Int): String

// num_frames is uint64_t, so it crosses as BigInt; pass it as a decimal string.
@JsFun("(mod, n, ch, sr) => mod._uapmd_audio_file_reader_create_silent(BigInt(n), ch, sr)")
internal external fun wasmAudioFileReaderCreateSilent(
    mod: UapmdCApiModule, numFrames: String, numChannels: Int, sampleRate: Int
): Int

@JsFun("(mod, rec, t, c, s) => mod._uapmd_midi_recorder_start(rec, t, c, BigInt(s))")
internal external fun wasmMidiRecorderStart(
    mod: UapmdCApiModule, rec: Int, trackReferenceId: Int, clipId: Int, startSample: String
): Boolean

@JsFun("(mod, out, app, t, pos, res, bpm) => mod._uapmd_app_create_empty_midi_clip(out, app, t, BigInt(pos), res, bpm)")
internal external fun wasmAppCreateEmptyMidiClip(
    mod: UapmdCApiModule, out: Int, app: Int, trackIndex: Int, positionSamples: String, tickResolution: Int, bpm: Double
)

@JsFun("(mod, app, t, c, tick, words, n) => mod._uapmd_app_add_ump_event_to_clip(app, t, c, BigInt(tick), words, n)")
internal external fun wasmAppAddUmpEventToClip(
    mod: UapmdCApiModule, app: Int, trackIndex: Int, clipId: Int, tick: String, words: Int, wordCount: Int
): Boolean

@JsFun("(mod, eng, v) => mod._uapmd_undo_engine_mark_state_saved(eng, BigInt(v))")
internal external fun wasmUndoEngineMarkStateSaved(mod: UapmdCApiModule, eng: Int, v: String): Boolean

@JsFun("(mod, eng, v) => mod._uapmd_undo_engine_set_maximum_history_size(eng, BigInt(v))")
internal external fun wasmUndoEngineSetMaximumHistorySize(mod: UapmdCApiModule, eng: Int, v: String): Boolean

@JsFun("(mod, cmd, t, c, v, o) => mod._uapmd_commands_resize_clip(cmd, t, c, BigInt(v), o)")
internal external fun wasmCommandsResizeClip(mod: UapmdCApiModule, cmd: Int, t: Int, c: Int, v: String, o: Int): Boolean

// ── Project history callback dispatch ───────────────────────────────────────

internal val pendingUndoCompletions   = mutableMapOf<Int, (UndoResult) -> Unit>()
internal val pendingTrackMutations    = mutableMapOf<Int, (Int, String?) -> Unit>()
internal val pendingTrackFragments    = mutableMapOf<Int, (TrackFragment?, String?) -> Unit>()
/** For C callbacks shaped (const char* error, void* user_data). */
internal val pendingErrorOnlyCallbacks = mutableMapOf<Int, (String?) -> Unit>()
internal val pendingInstanceCreations = mutableMapOf<Int, (PluginInstanceResult) -> Unit>()
internal val pendingProjectSaves = mutableMapOf<Int, (AppProjectResult) -> Unit>()

/** The C callback takes uapmd_undo_result_t by value, i.e. as a pointer. */
@JsExport
fun uapmdDispatchUndoCompletion(cbId: Int, resultPtr: Int) {
    val mod = wasmMod
    val status = mod.getValue(resultPtr, "i32").toInt()
    val errorPtr = mod.getValue(resultPtr + 4, "i32").toInt()
    val error = if (errorPtr != 0) mod.utf8ToString(errorPtr) else null
    pendingUndoCompletions.remove(cbId)?.invoke(UndoResult(UndoStatus.fromNative(status), error))
}

@JsExport
fun uapmdDispatchTrackMutation(cbId: Int, trackIndex: Int, errorPtr: Int) {
    val error = if (errorPtr != 0) wasmMod.utf8ToString(errorPtr) else null
    pendingTrackMutations.remove(cbId)?.invoke(trackIndex, error)
}

/**
 * The C callback takes uapmd_plugin_instance_result_t by value, which Emscripten
 * passes as a pointer. Layout on wasm32: int32 id @0, char* name @4, char* err @8.
 */
@JsExport
fun uapmdDispatchInstanceCreated(cbId: Int, resultPtr: Int) {
    val mod = wasmMod
    val id = mod.getValue(resultPtr, "i32").toInt()
    val namePtr = mod.getValue(resultPtr + 4, "i32").toInt()
    val errPtr = mod.getValue(resultPtr + 8, "i32").toInt()
    pendingInstanceCreations.remove(cbId)?.invoke(
        PluginInstanceResult(
            instanceId = id,
            pluginName = if (namePtr != 0) mod.utf8ToString(namePtr) else "",
            error = if (errPtr != 0) mod.utf8ToString(errPtr) else null
        )
    )
}

/*
 * Document picking, in two hops. `pick_open` reports handles; a handle is not a
 * path, so `resolve_to_path` turns the chosen one into something the engine can
 * open — which for the Emscripten provider is the file it has already copied into
 * MEMFS. Both are async, so each hop is its own C callback.
 *
 * Layouts, verified with emcc -fdump-record-layouts-complete on wasm32:
 *   uapmd_document_pick_result_t  success@0 count@4 handles@8 error@12,  size 16
 *   uapmd_document_handle_t       id@0 displayName@4 mimeType@8,         size 12
 *   uapmd_document_io_result_t    success@0 error@4,                     size 8
 */
private val pendingDocumentPicks = mutableMapOf<Int, (String?) -> Unit>()

/** Kept alive until the pick completes; the provider calls back long after the call returns. */
internal fun registerDocumentPick(callback: (String?) -> Unit): Pair<Int, Int> {
    val cbId = nextCallbackId()
    pendingDocumentPicks[cbId] = callback
    return cbId to makeCFunctionPtr(cbId, "uapmdDispatchDocumentPick", "vii")
}

@JsExport
fun uapmdDispatchDocumentPick(cbId: Int, resultPtr: Int) {
    val mod = wasmMod
    val callback = pendingDocumentPicks.remove(cbId) ?: return
    val success = mod.getValue(resultPtr, "i8").toInt() != 0
    val count = mod.getValue(resultPtr + 4, "i32").toInt()
    val handles = mod.getValue(resultPtr + 8, "i32").toInt()
    if (!success || count == 0 || handles == 0) {
        // A cancelled pick is a success with no handles, and is not an error.
        mod.uapmdDocumentPickResultFree(resultPtr)
        callback(null)
        return
    }
    // Resolve the first handle to a real path, then free the result: the handle's
    // strings belong to it, and the provider has copied them by the time it returns.
    val provider = documentProviderHandle
    if (provider == 0) {
        mod.uapmdDocumentPickResultFree(resultPtr)
        callback(null)
        return
    }
    val pathCbId = nextCallbackId()
    pendingDocumentPaths[pathCbId] = callback
    val ptr = makeCFunctionPtr(pathCbId, "uapmdDispatchDocumentPath", "viii")
    mod.uapmdDocumentProviderResolveToPath(provider, handles, pathCbId, ptr)
    mod.uapmdDocumentPickResultFree(resultPtr)
}

private val pendingDocumentPaths = mutableMapOf<Int, (String?) -> Unit>()

@JsExport
fun uapmdDispatchDocumentPath(cbId: Int, resultPtr: Int, pathPtr: Int) {
    val mod = wasmMod
    val callback = pendingDocumentPaths.remove(cbId) ?: return
    val success = mod.getValue(resultPtr, "i8").toInt() != 0
    callback(if (success && pathPtr != 0) mod.utf8ToString(pathPtr).takeIf { it.isNotEmpty() } else null)
}

/** The app's own provider, looked up once. */
internal var documentProviderHandle: Int = 0

/**
 * Opens the browser's file picker through uapmd's Emscripten document provider,
 * which creates a hidden `<input type=file>`, copies the chosen file into MEMFS and
 * hands back a path the engine can open (`DocumentProviderEmscripten.cpp:93`).
 *
 * [extensions] are matched the way the provider builds its `accept` string, e.g.
 * ".uapmd". Returns null when the user cancels.
 */
fun pickDocumentToOpen(
    app: AppModel,
    label: String,
    extensions: List<String>,
    callback: (String?) -> Unit
) {
    val mod = wasmMod
    if (documentProviderHandle == 0)
        documentProviderHandle = mod.uapmdAppDocumentProvider((app as WasmJsAppModel).handle)
    if (documentProviderHandle == 0) {
        callback(null)
        return
    }

    // uapmd_document_filter_t: label@0 mimeTypes@4 mimeCount@8 extensions@12 extCount@16.
    val labelPtr = allocCString(label)
    val extPtrs = extensions.map { allocCString(it) }
    val extArray = mod.malloc(extPtrs.size * 4)
    extPtrs.forEachIndexed { i, p -> mod.setValue(extArray + i * 4, p.toDouble(), "i32") }
    val filter = mod.malloc(20)
    mod.setValue(filter, labelPtr.toDouble(), "i32")
    mod.setValue(filter + 4, 0.0, "i32")
    mod.setValue(filter + 8, 0.0, "i32")
    mod.setValue(filter + 12, extArray.toDouble(), "i32")
    mod.setValue(filter + 16, extPtrs.size.toDouble(), "i32")

    val (_, cbPtr) = registerDocumentPick { path ->
        // Freed here rather than after the call: the provider reads the filters
        // while the picker is open, which outlives pick_open returning.
        mod.free(filter)
        mod.free(extArray)
        extPtrs.forEach { mod.free(it) }
        mod.free(labelPtr)
        callback(path)
    }
    mod.uapmdDocumentProviderPickOpen(documentProviderHandle, filter, 1, false, 0, cbPtr)
}

/** Drives pending picks; the provider completes them from its own tick. */
fun tickDocumentProvider() {
    if (documentProviderHandle != 0) wasmMod.uapmdDocumentProviderTick(documentProviderHandle)
}

private val pendingDocumentWrites = mutableMapOf<Int, (String?) -> Unit>()

/** Reports null on success, or the engine's message on failure. */
@JsExport
fun uapmdDispatchDocumentWrite(cbId: Int, resultPtr: Int) {
    val mod = wasmMod
    val callback = pendingDocumentWrites.remove(cbId) ?: return
    val success = mod.getValue(resultPtr, "i8").toInt() != 0
    val errPtr = mod.getValue(resultPtr + 4, "i32").toInt()
    val error = if (errPtr != 0) mod.utf8ToString(errPtr) else null
    callback(if (success) null else (error ?: "The project could not be saved."))
}

/**
 * Saves the project the way uapmd-app does (`MainWindow::handleSaveProject`): ask
 * the provider for a save handle, then hand it to the app model, which packs the
 * project tree into a `.uapmdz` and writes it through the provider — a download, in
 * a browser. Writing to a path cannot replace this: a saved project is a directory,
 * so a single-file write would deliver only its manifest.
 *
 * [onDone] receives null on success, or a message. A cancelled pick reports null and
 * saves nothing.
 */
fun saveProjectAsDocument(app: AppModel, defaultName: String, onDone: (String?) -> Unit) {
    val mod = wasmMod
    if (documentProviderHandle == 0)
        documentProviderHandle = mod.uapmdAppDocumentProvider((app as WasmJsAppModel).handle)
    if (documentProviderHandle == 0) {
        onDone("No document provider is available.")
        return
    }

    val labelPtr = allocCString("uapmd project archive")
    val extPtr = allocCString(".uapmdz")
    val extArray = mod.malloc(4)
    mod.setValue(extArray, extPtr.toDouble(), "i32")
    val filter = mod.malloc(20)
    mod.setValue(filter, labelPtr.toDouble(), "i32")
    mod.setValue(filter + 4, 0.0, "i32")
    mod.setValue(filter + 8, 0.0, "i32")
    mod.setValue(filter + 12, extArray.toDouble(), "i32")
    mod.setValue(filter + 16, 1.0, "i32")
    val namePtr = allocCString(defaultName)

    fun release() {
        mod.free(filter); mod.free(extArray); mod.free(extPtr)
        mod.free(labelPtr); mod.free(namePtr)
    }

    val cbId = nextCallbackId()
    pendingSavePicks[cbId] = { handlePtr ->
        if (handlePtr == 0) {
            release()
            onDone(null)                      // cancelled
        } else {
            val writeId = nextCallbackId()
            pendingDocumentWrites[writeId] = { error ->
                release()
                onDone(error)
            }
            val writeCb = makeCFunctionPtr(writeId, "uapmdDispatchDocumentWrite", "vii")
            mod.uapmdAppSaveProjectToDocument((app as WasmJsAppModel).handle, handlePtr, writeId, writeCb)
        }
    }
    val pickCb = makeCFunctionPtr(cbId, "uapmdDispatchSavePick", "vii")
    mod.uapmdDocumentProviderPickSave(documentProviderHandle, namePtr, filter, 1, 0, pickCb)
}

private val pendingSavePicks = mutableMapOf<Int, (Int) -> Unit>()

/** Hands the first handle's address on, so the save can use it without copying. */
@JsExport
fun uapmdDispatchSavePick(cbId: Int, resultPtr: Int) {
    val mod = wasmMod
    val callback = pendingSavePicks.remove(cbId) ?: return
    val success = mod.getValue(resultPtr, "i8").toInt() != 0
    val count = mod.getValue(resultPtr + 4, "i32").toInt()
    val handles = mod.getValue(resultPtr + 8, "i32").toInt()
    callback(if (success && count > 0) handles else 0)
    mod.uapmdDocumentPickResultFree(resultPtr)
}

private fun allocCString(s: String): Int {
    val mod = wasmMod
    val size = mod.lengthBytesUTF8(s) + 1
    val ptr = mod.malloc(size)
    mod.stringToUTF8(s, ptr, size)
    return ptr
}

/** uapmd_app_project_result_t by value = a pointer. wasm32: bool @0, char* @4. */
@JsExport
fun uapmdDispatchProjectSave(cbId: Int, resultPtr: Int) {
    val mod = wasmMod
    val ok = mod.getValue(resultPtr, "i8").toInt() != 0
    val errPtr = mod.getValue(resultPtr + 4, "i32").toInt()
    pendingProjectSaves.remove(cbId)?.invoke(
        AppProjectResult(ok, if (errPtr != 0) mod.utf8ToString(errPtr) else null)
    )
}

@JsExport
fun uapmdDispatchErrorOnly(cbId: Int, errorPtr: Int) {
    val error = if (errorPtr != 0) wasmMod.utf8ToString(errorPtr) else null
    pendingErrorOnlyCallbacks.remove(cbId)?.invoke(error)
}

@JsExport
fun uapmdDispatchTrackFragment(cbId: Int, fragmentPtr: Int, errorPtr: Int) {
    val error = if (errorPtr != 0) wasmMod.utf8ToString(errorPtr) else null
    val fragment = if (fragmentPtr != 0) WasmJsTrackFragment(fragmentPtr) else null
    pendingTrackFragments.remove(cbId)?.invoke(fragment, error)
}
