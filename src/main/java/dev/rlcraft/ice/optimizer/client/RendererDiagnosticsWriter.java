package dev.rlcraft.ice.optimizer.client;

import dev.rlcraft.ice.optimizer.FatalErrors;
import dev.rlcraft.ice.optimizer.OptimizationModule;
import dev.rlcraft.ice.optimizer.memory.CacheBudgetStatus;
import dev.rlcraft.ice.optimizer.compat.chunk.ChunkAnimatorRenderBridge;
import dev.rlcraft.ice.optimizer.compat.model.ModelMeshCaptureBridge;
import dev.rlcraft.ice.optimizer.client.ModernRendererRuntime.HzbCaptureReason;
import dev.rlcraft.ice.optimizer.client.ModernRendererRuntime.ModelDrawReason;
import dev.rlcraft.ice.optimizer.client.ModernRendererRuntime.TerrainFallbackReason;
import dev.rlcraft.ice.optimizer.render.backend.AdaptiveBackendController;
import dev.rlcraft.ice.optimizer.render.backend.BackendStatus;
import dev.rlcraft.ice.optimizer.render.backend.CapabilityReport;
import dev.rlcraft.ice.optimizer.render.backend.ModernCapability;
import dev.rlcraft.ice.optimizer.render.backend.RenderBackendId;
import dev.rlcraft.ice.optimizer.render.arena.ArenaStatus;
import dev.rlcraft.ice.optimizer.render.resource.ResourceLedgerStatus;
import dev.rlcraft.ice.optimizer.render.telemetry.PassProfile;
import dev.rlcraft.ice.optimizer.render.telemetry.RenderProfilerSnapshot;
import dev.rlcraft.ice.optimizer.render.telemetry.RenderProfileKey;
import dev.rlcraft.ice.optimizer.render.terrain.TerrainIndirectReason;
import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;

/**
 * Render-thread report formatter. It reads live runtime/components only at
 * publication time, then exports immutable strings through the existing
 * cross-thread ModernRendererDiagnostics ABI. It never copies live state.
 */
final class RendererDiagnosticsWriter {
    private static final long PUBLICATION_INTERVAL = 120L;

    private final ModernRendererRuntime runtime;
    private long lastPublishedFrame = Long.MIN_VALUE;

    RendererDiagnosticsWriter(ModernRendererRuntime runtime) {
        this.runtime = runtime;
    }

    void publish(boolean force) {
        long frame = runtime.currentStamp == null ? runtime.lastRuntimeFrame
            : runtime.currentStamp.getFrameId();
        if (!force && frame < 0L) return;
        if (!force && lastPublishedFrame != Long.MIN_VALUE
            && frame >= lastPublishedFrame
            && frame - lastPublishedFrame < PUBLICATION_INTERVAL) return;
        lastPublishedFrame = frame;
        try {
            StringBuilder report = new StringBuilder(8192);
            appendRuntimeDiagnostics(report, frame);
            appendRenderWorkDiagnostics(report);
            appendTerrainDiagnostics(report);
            appendVisibilityDiagnostics(report);
            appendParticleDiagnostics(report);
            appendModelDiagnostics(report);
            appendCapabilitiesDiagnostics(report);
            StringBuilder summary = appendTerrainReasonsAndSummary(report);
            appendBackendDiagnostics(report);
            ModernRendererDiagnostics.publish(report.toString(),
                summary.toString());
        } catch (Throwable error) {
            FatalErrors.rethrowIfFatal(error);
            // An observational publication failure must not invalidate a frame.
        }
    }

    private void appendRuntimeDiagnostics(StringBuilder report, long frame) {
        report.append("ICE modern renderer diagnostics\n");
        report.append("format=1\n");
        report.append("published_frame=").append(frame).append('\n');
        report.append("renderer_generation=").append(runtime.combinedGeneration())
            .append('\n');
        report.append("initialized=").append(runtime.initialized).append('\n');
        report.append("quiescent=").append(runtime.quiescent).append('\n');
        report.append("frame_infrastructure_active=")
            .append(runtime.frameInfrastructureActive).append('\n');
        report.append("detail=").append(diagnosticText(runtime.detail, 1024))
            .append('\n');
        report.append("shader_pack_state_known=")
            .append(runtime.shaderRuntime.shaderPackStateKnown).append('\n');
        report.append("shader_pack_active=").append(runtime.shaderRuntime.shaderPackActive)
            .append('\n');
        boolean diagnosticShaderActive = runtime.shaderRuntime.hasNativeBinding();
        report.append("shader_native_binding_active=")
            .append(diagnosticShaderActive).append('\n');
        report.append("shader_terrain_layout_certified=")
            .append(diagnosticShaderActive
                && runtime.shaderRuntime.nativeTerrainLayoutCertified()).append('\n');
        report.append("shader_terrain_layout_detail=")
            .append(diagnosticText(
                runtime.shaderRuntime.nativeTerrainLayoutDetail(), 1024))
            .append('\n');
        report.append("shader_terrain_gate=")
            .append(runtime.isShaderPackSafeForTerrainArena()).append('\n');
        CacheBudgetStatus budgetStatus = runtime.budget.snapshot();
        report.append("optimizer_budget_heap_used_bytes=")
            .append(budgetStatus.getHeapUsed()).append('\n');
        report.append("optimizer_budget_heap_limit_bytes=")
            .append(budgetStatus.getHeapLimit()).append('\n');
        report.append("optimizer_budget_direct_used_bytes=")
            .append(budgetStatus.getDirectUsed()).append('\n');
        report.append("optimizer_budget_direct_limit_bytes=")
            .append(budgetStatus.getDirectLimit()).append('\n');
        report.append("optimizer_budget_gpu_used_bytes=")
            .append(budgetStatus.getGpuUsed()).append('\n');
        report.append("optimizer_budget_gpu_limit_bytes=")
            .append(budgetStatus.getGpuLimit()).append('\n');
        report.append("optimizer_budget_rejected_reservations=")
            .append(budgetStatus.getRejectedReservations()).append('\n');
        if (runtime.components.resources != null) {
            ResourceLedgerStatus ledgerStatus = runtime.components.resources.snapshot();
            report.append("resource_ledger_live=")
                .append(ledgerStatus.getLive()).append('\n');
            report.append("resource_ledger_retired=")
                .append(ledgerStatus.getRetired()).append('\n');
            report.append("resource_ledger_live_bytes=")
                .append(ledgerStatus.getLiveBytes()).append('\n');
            report.append("resource_ledger_created=")
                .append(ledgerStatus.getCreated()).append('\n');
            report.append("resource_ledger_destroyed=")
                .append(ledgerStatus.getDestroyed()).append('\n');
            report.append("resource_ledger_abandoned=")
                .append(ledgerStatus.getAbandoned()).append('\n');
            report.append("resource_ledger_rejected=")
                .append(ledgerStatus.getRejected()).append('\n');
            report.append("resource_ledger_fence_timeouts=")
                .append(ledgerStatus.getTimedOut()).append('\n');
        }
    }

    private void appendRenderWorkDiagnostics(StringBuilder report) {
        if (report == null || runtime.components.profiler == null) return;
        RenderProfilerSnapshot snapshot = runtime.components.profiler.snapshot();
        if (snapshot == null) return;
        report.append("render_profile_dropped_frames=")
            .append(snapshot.getDroppedFrames()).append('\n');
        report.append("render_profile_dropped_gpu_queries=")
            .append(snapshot.getDroppedGpuQueries()).append('\n');
        report.append("render_profile_scope_errors=")
            .append(snapshot.getScopeErrors()).append('\n');
        EnumMap<RenderBackendId, long[]> totals =
            new EnumMap<RenderBackendId, long[]>(RenderBackendId.class);
        for (RenderBackendId backend : RenderBackendId.values()) {
            totals.put(backend, new long[5]);
        }
        for (Map.Entry<RenderProfileKey, PassProfile> entry
            : snapshot.getProfiles().entrySet()) {
            RenderProfileKey key = entry.getKey();
            PassProfile profile = entry.getValue();
            if (key == null || profile == null) continue;
            long[] values = totals.get(key.getBackend());
            if (values == null) continue;
            values[0] = safeAdd(values[0], 1L);
            values[1] = safeAdd(values[1], profile.getCpuInclusiveNanos());
            values[2] = safeAdd(values[2], profile.getCpuExclusiveNanos());
            values[3] = safeAdd(values[3], profile.getGpuNanos());
            if (profile.getGpuNanos() > 0L) {
                values[4] = safeAdd(values[4], 1L);
            }
        }
        for (Map.Entry<RenderBackendId, long[]> entry : totals.entrySet()) {
            String prefix = "render_profile." + entry.getKey().name() + ".";
            long[] values = entry.getValue();
            report.append(prefix).append("profile_keys=")
                .append(values[0]).append('\n');
            report.append(prefix).append("cpu_inclusive_nanos=")
                .append(values[1]).append('\n');
            report.append(prefix).append("cpu_exclusive_nanos=")
                .append(values[2]).append('\n');
            report.append(prefix).append("gpu_nanos=")
                .append(values[3]).append('\n');
            report.append(prefix).append("gpu_profile_keys=")
                .append(values[4]).append('\n');
        }
    }

    private void appendTerrainDiagnostics(StringBuilder report) {
        report.append("terrain_upload_arena=").append(runtime.terrainArenaUploads)
            .append('\n');
        report.append("terrain_upload_legacy=")
            .append(runtime.terrainLegacyUploads.get())
            .append('\n');
        report.append("terrain_shadow_upload_attempts=")
            .append(runtime.terrainShadowUploadAttempts).append('\n');
        report.append("terrain_shadow_uploads=")
            .append(runtime.terrainShadowUploads).append('\n');
        report.append("terrain_shadow_uploaded_bytes=")
            .append(runtime.terrainShadowUploadedBytes).append('\n');
        report.append("terrain_shadow_budget_rejects=")
            .append(runtime.terrainShadowBudgetRejects).append('\n');
        report.append("terrain_shadow_qualification_skips=")
            .append(runtime.terrainShadowQualificationSkips).append('\n');
        report.append("terrain_measurement_coverage_rejects=")
            .append(runtime.terrainMeasurementCoverageRejects).append('\n');
        report.append("terrain_measurement_profile_warmups=")
            .append(runtime.terrainMeasurementProfileWarmups).append('\n');
        report.append("terrain_last_visible_meshes=")
            .append(runtime.terrainLastVisibleMeshes).append('\n');
        report.append("terrain_last_owned_meshes=")
            .append(runtime.terrainLastOwnedMeshes).append('\n');
        report.append("terrain_last_ownership_percent=")
            .append(runtime.terrainLastVisibleMeshes <= 0 ? 0.0D
                : 100.0D * runtime.terrainLastOwnedMeshes
                    / runtime.terrainLastVisibleMeshes)
            .append('\n');
        report.append("terrain_last_region_runs=")
            .append(runtime.terrainLastRegionRuns).append('\n');
        report.append("terrain_draw_arena_total=").append(runtime.terrainArenaDraws)
            .append('\n');
        report.append("terrain_draw_legacy=").append(runtime.terrainLegacyDraws)
            .append('\n');
        report.append("terrain_draw_arena_unbatched=")
            .append(runtime.terrainArenaUnbatchedDraws).append('\n');
        report.append("terrain_draw_arena_multi=")
            .append(runtime.terrainArenaMultiDraws).append('\n');
        report.append("terrain_draw_arena_uncertain=")
            .append(runtime.terrainArenaUncertainDraws).append('\n');
        report.append("terrain_mdi_submissions=")
            .append(runtime.terrainMdiSubmissions).append('\n');
        report.append("terrain_indirect_commands=")
            .append(runtime.terrainIndirectCommands).append('\n');
        if (runtime.components.terrainArena != null) {
            ArenaStatus arena = runtime.components.terrainArena.allocatorStatus();
            report.append("terrain_arena_owned_meshes=")
                .append(runtime.components.terrainArena.getOwnedMeshes()).append('\n');
            report.append("terrain_arena_shadow_owned_meshes=")
                .append(runtime.components.terrainArena.getShadowOwnedMeshes()).append('\n');
            report.append("terrain_arena_shadow_owned_bytes=")
                .append(runtime.components.terrainArena.getShadowOwnedBytes()).append('\n');
            report.append("terrain_arena_used_bytes=")
                .append(arena.getUsedBytes()).append('\n');
            report.append("terrain_arena_committed_bytes=")
                .append(arena.getCommittedBytes()).append('\n');
            report.append("terrain_arena_live_allocations=")
                .append(arena.getAllocations()).append('\n');
            report.append("terrain_arena_free_segments=")
                .append(arena.getFreeSegments()).append('\n');
            report.append("terrain_arena_rejected_allocations=")
                .append(arena.getRejected()).append('\n');
            report.append("terrain_arena_invalid_frees=")
                .append(arena.getInvalidFrees()).append('\n');
            report.append("terrain_arena_rejected_uploads=")
                .append(runtime.components.terrainArena.getRejectedUploads()).append('\n');
            report.append("terrain_arena_invalid_payloads=")
                .append(runtime.components.terrainArena.getInvalidPayloads()).append('\n');
            report.append("terrain_arena_uploaded_bytes=")
                .append(runtime.components.terrainArena.getUploadedBytes()).append('\n');
            report.append("terrain_arena_persistent_uploads=")
                .append(runtime.components.terrainArena.getPersistentUploads()).append('\n');
            report.append("terrain_arena_subdata_uploads=")
                .append(runtime.components.terrainArena.getSubDataUploads()).append('\n');
            report.append("terrain_arena_raw_draw_calls=")
                .append(runtime.components.terrainArena.getDrawCalls()).append('\n');
            report.append("terrain_arena_raw_multi_draw_calls=")
                .append(runtime.components.terrainArena.getMultiDrawCalls()).append('\n');
            report.append("terrain_arena_raw_indirect_draw_calls=")
                .append(runtime.components.terrainArena.getIndirectDrawCalls()).append('\n');
            report.append("terrain_arena_indirect_fallbacks=")
                .append(runtime.components.terrainArena.getIndirectFallbacks()).append('\n');
            report.append("terrain_arena_multi_draw_capacity_fallbacks=")
                .append(runtime.components.terrainArena.getMultiDrawCapacityFallbacks())
                .append('\n');
            report.append("terrain_arena_indirect_unknown_bindings=")
                .append(runtime.components.terrainArena.getIndirectUnknownBindings())
                .append('\n');
            report.append("terrain_arena_indirect_binding_reauthentications=")
                .append(runtime.components.terrainArena.getIndirectBindingReauthentications())
                .append('\n');
            report.append("terrain_arena_indirect_binding_query_failures=")
                .append(runtime.components.terrainArena.getIndirectBindingQueryFailures())
                .append('\n');
            report.append("terrain_arena_indirect_binding_query_suppressions=")
                .append(runtime.components.terrainArena.getIndirectBindingQuerySuppressions())
                .append('\n');
            for (TerrainIndirectReason reason
                : TerrainIndirectReason.values()) {
                report.append("terrain_arena_indirect_reason.")
                    .append(reason.name()).append('=')
                    .append(runtime.components.terrainArena.getIndirectReason(reason))
                    .append('\n');
            }
            report.append("terrain_arena_busy_fences=")
                .append(runtime.components.terrainArena.getBusyFences()).append('\n');
            report.append("terrain_chunk_animator_compatibility_draws=")
                .append(runtime.components.terrainArena.getChunkAnimatorCompatibilityDraws())
                .append('\n');
        }
        report.append("chunk_animator_probe_status=")
            .append(ChunkAnimatorRenderBridge.status()).append('\n');
        report.append("chunk_animator_probe_runtime_failures=")
            .append(ChunkAnimatorRenderBridge.runtimeFailures()).append('\n');
        report.append("chunk_animator_probe_failure_exception=")
            .append(diagnosticText(
                ChunkAnimatorRenderBridge.failureType(), 256)).append('\n');
        report.append("chunk_animator_probe_failure_message=")
            .append(diagnosticText(
                ChunkAnimatorRenderBridge.failureMessage(), 1024))
            .append('\n');
    }

    private void appendVisibilityDiagnostics(StringBuilder report) {
        if (runtime.components.depthHistory != null) {
            report.append("hzb_capture_attempts=")
                .append(runtime.hzbCaptureAttempts).append('\n');
            report.append("hzb_state_reauthentication_attempts=")
                .append(runtime.hzbStateReauthenticationAttempts).append('\n');
            report.append("hzb_state_reauthentications=")
                .append(runtime.hzbStateReauthentications).append('\n');
            report.append("hzb_state_reauthentication_failures=")
                .append(runtime.hzbStateReauthenticationFailures).append('\n');
            report.append("hzb_state_reauthentication_suppressions=")
                .append(runtime.hzbStateReauthenticationSuppressions).append('\n');
            report.append("hzb_state_last_failure_exception=")
                .append(diagnosticText(runtime.lastHzbStateFailureType, 256))
                .append('\n');
            report.append("hzb_state_last_failure_message=")
                .append(diagnosticText(runtime.lastHzbStateFailureMessage, 1024))
                .append('\n');
            report.append("hzb_state_failed_invalidation=")
                .append(runtime.failedHzbStateInvalidation).append('\n');
            report.append("hzb_state_failed_frame=")
                .append(runtime.failedHzbStateFrame).append('\n');
            for (HzbCaptureReason reason : HzbCaptureReason.values()) {
                report.append("hzb_capture_reason.")
                    .append(reason.name()).append('=')
                    .append(runtime.hzbCaptureReasons[reason.ordinal()])
                    .append('\n');
            }
            report.append("hzb_captures=").append(runtime.components.depthHistory.getCaptures())
                .append('\n');
            report.append("hzb_published=").append(runtime.components.depthHistory.getPublished())
                .append('\n');
            report.append("hzb_oracle_validated_publications=")
                .append(runtime.components.depthHistory.getOracleValidatedPublications())
                .append('\n');
            report.append("hzb_view_gate.INVALID_INPUT=")
                .append(runtime.components.depthHistory.getViewGateInvalidInput())
                .append('\n');
            report.append("hzb_view_gate.FIRST_OBSERVATION=")
                .append(runtime.components.depthHistory.getViewGateFirstObservation())
                .append('\n');
            report.append("hzb_view_gate.VIEW_CHANGED=")
                .append(runtime.components.depthHistory.getViewGateViewChanged())
                .append('\n');
            report.append("hzb_view_gate.DUPLICATE_FRAME=")
                .append(runtime.components.depthHistory.getViewGateDuplicateFrame())
                .append('\n');
            report.append("hzb_view_gate.FRAME_GAP=")
                .append(runtime.components.depthHistory.getViewGateFrameGap())
                .append('\n');
            report.append("hzb_view_gate.CAPTURE_ALLOWED=")
                .append(runtime.components.depthHistory.getViewGateCaptureAllowed())
                .append('\n');
            report.append("hzb_deferred_polls=")
                .append(runtime.components.depthHistory.getDeferredPolls()).append('\n');
            report.append("hzb_tested=").append(runtime.components.depthHistory.getTested())
                .append('\n');
            report.append("hzb_occluded=").append(runtime.components.depthHistory.getOccluded())
                .append('\n');
            report.append("hzb_raw_occluded=")
                .append(runtime.components.depthHistory.getRawOccluded()).append('\n');
            report.append("hzb_confirmation_deferrals=")
                .append(runtime.components.depthHistory.getConfirmationDeferrals()).append('\n');
            report.append("hzb_chunk_animation_bypasses=")
                .append(runtime.components.depthHistory.getAnimationBypasses()).append('\n');
            report.append("hzb_confirmation_capacity_resets=")
                .append(runtime.components.depthHistory.getOcclusionGateCapacityResets())
                .append('\n');
            report.append("hzb_confirmation_capacity=")
                .append(runtime.components.depthHistory.getOcclusionGateCapacity())
                .append('\n');
            report.append("hzb_confirmation_budget_reductions=")
                .append(runtime.components.depthHistory
                    .getOcclusionGateBudgetCapacityReductions())
                .append('\n');
            report.append("hzb_filter_rollbacks=")
                .append(runtime.components.depthHistory.getFilterRollbacks()).append('\n');
            report.append("hzb_filter_transaction_deferrals=")
                .append(runtime.components.depthHistory.getFilterTransactionDeferrals())
                .append('\n');
            report.append("hzb_filter_transaction_failures=")
                .append(runtime.components.depthHistory.getFilterTransactionFailures())
                .append('\n');
            report.append("hzb_busy=").append(runtime.components.depthHistory.getBusy())
                .append('\n');
            report.append("hzb_rejected=").append(runtime.components.depthHistory.getRejected())
                .append('\n');
            report.append("hzb_scene_invalidations=")
                .append(runtime.components.depthHistory.getSceneInvalidations()).append('\n');
            report.append("hzb_stale_completions=")
                .append(runtime.components.depthHistory.getStaleCompletions()).append('\n');
            report.append("hzb_geometry_changes=")
                .append(runtime.components.depthHistory.getGeometryChanges()).append('\n');
            report.append("hzb_geometry_changes_coalesced=")
                .append(runtime.components.depthHistory.getCoalescedGeometryChanges())
                .append('\n');
        }
    }

    private void appendParticleDiagnostics(StringBuilder report) {
        report.append("particle_backend_failures=")
            .append(runtime.particleBackendFailures).append('\n');
        report.append("particle_last_failure_exception=")
            .append(diagnosticText(runtime.lastParticleFailureType, 256))
            .append('\n');
        report.append("particle_last_failure_message=")
            .append(diagnosticText(runtime.lastParticleFailureMessage, 1024))
            .append('\n');
        report.append("particle_last_root_failure_exception=")
            .append(diagnosticText(runtime.lastParticleRootFailureType, 256))
            .append('\n');
        report.append("particle_last_root_failure_message=")
            .append(diagnosticText(runtime.lastParticleRootFailureMessage, 1024))
            .append('\n');
    }

    private void appendModelDiagnostics(StringBuilder report) {
        report.append("model_mesh_cache_entries=")
            .append(runtime.components.modelMeshes == null ? 0 : runtime.components.modelMeshes.size()).append('\n');
        report.append("model_mesh_shared_pages=")
            .append(runtime.components.modelMeshes == null ? 0 : runtime.components.modelMeshes.getSharedPageCount())
            .append('\n');
        report.append("model_state_batch_begins=")
            .append(runtime.components.modelMeshes == null ? 0 : runtime.components.modelMeshes.getStateBatchBegins())
            .append('\n');
        report.append("model_state_batch_draws=")
            .append(runtime.components.modelMeshes == null ? 0 : runtime.components.modelMeshes.getStateBatchDraws())
            .append('\n');
        report.append("model_state_batch_buffer_binds=")
            .append(runtime.components.modelMeshes == null ? 0 : runtime.components.modelMeshes.getStateBatchBufferBinds())
            .append('\n');
        report.append("model_state_batch_pointer_sets=")
            .append(runtime.components.modelMeshes == null ? 0 : runtime.components.modelMeshes.getStateBatchPointerSets())
            .append('\n');
        report.append("model_state_batch_restores=")
            .append(runtime.components.modelMeshes == null ? 0 : runtime.components.modelMeshes.getStateBatchRestores())
            .append('\n');
        report.append("model_state_batch_rejections=")
            .append(runtime.components.modelMeshes == null ? 0 : runtime.components.modelMeshes.getStateBatchRejections())
            .append('\n');
        report.append("model_mesh_generic_validated=")
            .append(runtime.genericModelVboValidated).append('\n');
        ModelMeshCaptureBridge.Diagnostics modelCapture =
            ModelMeshCaptureBridge.diagnostics();
        report.append("model_mesh_capture_begins=")
            .append(modelCapture.getCaptureBegins()).append('\n');
        report.append("model_mesh_capture_enabled=")
            .append(modelCapture.getEnabledCaptures()).append('\n');
        report.append("model_mesh_capture_early=")
            .append(modelCapture.getEarlyCaptures()).append('\n');
        report.append("model_mesh_capture_disabled=")
            .append(modelCapture.getDisabledCaptures()).append('\n');
        report.append("model_mesh_capture_quads=")
            .append(modelCapture.getCapturedQuads()).append('\n');
        report.append("model_mesh_capture_rejected=")
            .append(modelCapture.getRejectedCaptures()).append('\n');
        report.append("model_mesh_capture_completed=")
            .append(modelCapture.getCompletedCaptures()).append('\n');
        report.append("model_mesh_capture_immediate_publications=")
            .append(modelCapture.getImmediatePublications()).append('\n');
        report.append("model_mesh_capture_deferred_publications=")
            .append(modelCapture.getDeferredPublications()).append('\n');
        report.append("model_mesh_capture_late_qualifications=")
            .append(modelCapture.getLateQualifications()).append('\n');
        report.append("model_mesh_capture_stale_drops=")
            .append(modelCapture.getStalePendingDrops()).append('\n');
        report.append("model_mesh_capture_disabled_drops=")
            .append(modelCapture.getDisabledPendingDrops()).append('\n');
        report.append("model_mesh_pending_purge_scans=")
            .append(modelCapture.getPendingPurgeScans()).append('\n');
        report.append("model_mesh_publication_attempts=")
            .append(modelCapture.getPublicationAttempts()).append('\n');
        report.append("model_mesh_publication_failures=")
            .append(modelCapture.getPublicationFailures()).append('\n');
        report.append("model_mesh_publication_retry_suppressions=")
            .append(modelCapture.getPublicationRetrySuppressions())
            .append('\n');
        report.append("model_mesh_publication_drain_cycles=")
            .append(modelCapture.getPublicationDrainCycles()).append('\n');
        report.append("model_mesh_publication_drain_examined=")
            .append(modelCapture.getPublicationDrainExamined()).append('\n');
        report.append("model_mesh_publication_drained=")
            .append(modelCapture.getDrainedPublications()).append('\n');
        report.append("model_mesh_capture_pending=")
            .append(modelCapture.getPendingMeshes()).append('\n');
        report.append("model_mesh_capture_pending_bytes=")
            .append(modelCapture.getPendingBytes()).append('\n');
        report.append("model_mesh_draw_outside_traversal=")
            .append(runtime.modelDrawOutsideTraversal).append('\n');
        report.append("model_mesh_state_reauthentication_attempts=")
            .append(runtime.modelStateReauthenticationAttempts).append('\n');
        report.append("model_mesh_state_reauthentications=")
            .append(runtime.modelStateReauthentications).append('\n');
        report.append("model_mesh_state_reauthentication_failures=")
            .append(runtime.modelStateReauthenticationFailures).append('\n');
        report.append("model_mesh_state_reauthentication_suppressions=")
            .append(runtime.modelStateReauthenticationSuppressions).append('\n');
        report.append("model_mesh_upload_binding_attempts=")
            .append(runtime.modelUploadBindingAttempts).append('\n');
        report.append("model_mesh_upload_binding_recoveries=")
            .append(runtime.modelUploadBindingRecoveries).append('\n');
        report.append("model_mesh_upload_binding_failures=")
            .append(runtime.modelUploadBindingFailures).append('\n');
        report.append("model_mesh_state_last_failure_stage=")
            .append(diagnosticText(runtime.lastModelStateFailureStage, 256))
            .append('\n');
        report.append("model_mesh_state_last_failure_exception=")
            .append(diagnosticText(runtime.lastModelStateFailureType, 256))
            .append('\n');
        report.append("model_mesh_state_last_failure_message=")
            .append(diagnosticText(runtime.lastModelStateFailureMessage, 1024))
            .append('\n');
        report.append("model_mesh_state_failed_gl_invalidation=")
            .append(runtime.failedModelGlInvalidation).append('\n');
        report.append("model_mesh_state_failed_matrix_invalidation=")
            .append(runtime.failedModelMatrixInvalidation).append('\n');
        report.append("model_mesh_state_failed_frame=")
            .append(runtime.failedModelReauthenticationFrame).append('\n');
        for (int backendIndex = 0; backendIndex < 2; backendIndex++) {
            String backendName = backendIndex == 0 ? "entity" : "tesr";
            for (ModelDrawReason reason : ModelDrawReason.values()) {
                report.append("model_mesh_draw_reason.")
                    .append(backendName).append('.')
                    .append(reason.name()).append('=')
                    .append(runtime.modelDrawReasons[backendIndex][reason.ordinal()])
                    .append('\n');
            }
        }
    }

    private void appendCapabilitiesDiagnostics(StringBuilder report) {
        ModernCapability[] capabilityValues = ModernCapability.values();
        for (int i = 0; i < capabilityValues.length; i++) {
            ModernCapability capability = capabilityValues[i];
            CapabilityReport.FailureDetail failure = runtime.components.capabilities == null
                ? null : runtime.components.capabilities.getFailureDetail(capability);
            String prefix = "capability." + capability.name() + ".";
            String status = runtime.components.capabilities != null && runtime.components.capabilities.passed(capability)
                ? "PASS" : failure == null ? "NOT_TESTED" : "FAIL";
            report.append(prefix).append("status=").append(status).append('\n');
            report.append(prefix).append("detail=")
                .append(diagnosticText(failure == null ? ""
                    : failure.summary(), 4096)).append('\n');
            report.append(prefix).append("stage=")
                .append(diagnosticText(failure == null ? ""
                    : failure.getStage(), 256)).append('\n');
            report.append(prefix).append("exception=")
                .append(diagnosticText(failure == null ? ""
                    : failure.getExceptionType(), 256)).append('\n');
            report.append(prefix).append("gl_state=")
                .append(diagnosticText(failure == null ? ""
                    : failure.getGlState(), 4096)).append('\n');
            report.append(prefix).append("gl_errors=")
                .append(diagnosticText(failure == null ? ""
                    : failure.getGlErrors(), 512)).append('\n');
        }
    }

    private StringBuilder appendTerrainReasonsAndSummary(StringBuilder report) {
        long fallbackTotal = 0L;
        TerrainFallbackReason topReason = null;
        long topReasonCount = 0L;
        TerrainFallbackReason[] reasons = TerrainFallbackReason.values();
        for (int i = 0; i < reasons.length; i++) {
            long count = runtime.terrainFallbackReasons[i];
            fallbackTotal = safeAdd(fallbackTotal, count);
            if (count > topReasonCount) {
                topReason = reasons[i];
                topReasonCount = count;
            }
            report.append("terrain_reason.").append(reasons[i].name())
                .append('=').append(count).append('\n');
        }
        report.append("terrain_reason_total=").append(fallbackTotal)
            .append('\n');

        StringBuilder summary = new StringBuilder(256);
        summary.append("Terrain U A/L ").append(runtime.terrainArenaUploads)
            .append('/').append(runtime.terrainLegacyUploads.get())
            .append(" | D A/L ").append(runtime.terrainArenaDraws)
            .append('/').append(runtime.terrainLegacyDraws)
            .append(" | MDI ").append(runtime.terrainMdiSubmissions)
            .append('/').append(runtime.terrainIndirectCommands)
            .append(" | FB ");
        if (topReason == null) summary.append("none");
        else summary.append(topReason.name()).append(' ')
            .append(topReasonCount);
        return summary;
    }

    private void appendBackendDiagnostics(StringBuilder report) {
        for (Map.Entry<OptimizationModule, AdaptiveBackendController> entry
            : runtime.components.backends.entrySet()) {
            BackendStatus status = entry.getValue().snapshot();
            String prefix = "backend." + entry.getKey().getId() + ".";
            report.append(prefix).append("state=")
                .append(status.getState().name()).append('\n');
            report.append(prefix).append("active=")
                .append(status.isActive()).append('\n');
            report.append(prefix).append("generation=")
                .append(status.getGeneration()).append('\n');
            report.append(prefix).append("detail=")
                .append(diagnosticText(status.getDetail(), 512)).append('\n');
            report.append(prefix).append("median_improvement_percent=")
                .append(diagnosticPercent(status.getMedianImprovement()))
                .append('\n');
            report.append(prefix).append("p95_regression_percent=")
                .append(diagnosticPercent(status.getP95Regression()))
                .append('\n');
            report.append(prefix).append("measurement_samples=")
                .append(status.getMeasurementSamples()).append('\n');
            report.append(prefix).append("ignored_unstable_samples=")
                .append(status.getIgnoredUnstableSamples()).append('\n');
            report.append(prefix).append("workload_bucket=")
                .append(status.getWorkloadBucket()).append('\n');
            report.append(prefix).append("workload_profiles_evaluated=")
                .append(status.getEvaluatedWorkloadProfiles()).append('\n');
            report.append(prefix).append("workload_retests=")
                .append(status.getWorkloadRetests()).append('\n');
            report.append(prefix).append("workload_next_retest_frame=")
                .append(status.getNextRetestFrame()).append('\n');
            report.append(prefix).append("workload_retests_disabled=")
                .append(status.isWorkloadRetestsDisabled()).append('\n');
            report.append(prefix).append("timed_out_transitions=")
                .append(status.getTimedOutTransitions()).append('\n');
        }
    }

    private static String diagnosticPercent(double ratio) {
        if (Double.isNaN(ratio) || Double.isInfinite(ratio)) return "n/a";
        return String.format(Locale.ROOT, "%.3f", ratio * 100.0D);
    }

    private static String diagnosticText(String value, int maximum) {
        if (value == null || value.isEmpty() || maximum <= 0) return "";
        int length = Math.min(value.length(), maximum);
        StringBuilder result = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            char character = value.charAt(i);
            result.append(character < 32 || character == 127 ? ' ' : character);
        }
        return result.toString();
    }

    private static long safeAdd(long left, long right) {
        return right > Long.MAX_VALUE - left ? Long.MAX_VALUE : left + right;
    }
}
