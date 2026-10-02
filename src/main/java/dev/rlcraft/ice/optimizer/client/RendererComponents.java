package dev.rlcraft.ice.optimizer.client;

import dev.rlcraft.ice.optimizer.OptimizationModule;
import dev.rlcraft.ice.optimizer.render.backend.AdaptiveBackendController;
import dev.rlcraft.ice.optimizer.render.backend.CapabilityReport;
import dev.rlcraft.ice.optimizer.render.entity.CertifiedDrawSites;
import dev.rlcraft.ice.optimizer.render.entity.LwjglModelMeshCache;
import dev.rlcraft.ice.optimizer.render.frame.FrameCoordinator;
import dev.rlcraft.ice.optimizer.render.hud.FontLayoutCache;
import dev.rlcraft.ice.optimizer.render.hud.HudVertexStream;
import dev.rlcraft.ice.optimizer.render.hud.LwjglHudRenderer;
import dev.rlcraft.ice.optimizer.render.legacy.GlStateMirror;
import dev.rlcraft.ice.optimizer.render.legacy.LegacyGlIsland;
import dev.rlcraft.ice.optimizer.render.optifine.LwjglShaderProgramInstaller;
import dev.rlcraft.ice.optimizer.render.optifine.LwjglShaderImageCertification;
import dev.rlcraft.ice.optimizer.render.optifine.LwjglOptifineShaderActivation;
import dev.rlcraft.ice.optimizer.render.optifine.OptifineShaderBackendSelector;
import dev.rlcraft.ice.optimizer.render.optifine.ShaderCertificationRegistry;
import dev.rlcraft.ice.optimizer.render.optifine.ShaderCertificationPipeline;
import dev.rlcraft.ice.optimizer.render.optifine.LwjglShaderCompilationDriver;
import dev.rlcraft.ice.optimizer.render.particle.ParticleInstanceStream;
import dev.rlcraft.ice.optimizer.render.particle.LwjglFbpPacketRenderer;
import dev.rlcraft.ice.optimizer.render.particle.LwjglParticleRenderer;
import dev.rlcraft.ice.optimizer.render.resource.RenderThreadGuard;
import dev.rlcraft.ice.optimizer.render.resource.ResourceLedger;
import dev.rlcraft.ice.optimizer.render.telemetry.CorrelatedRenderProfiler;
import dev.rlcraft.ice.optimizer.render.terrain.LwjglTerrainArena;
import dev.rlcraft.ice.optimizer.render.texture.SpriteVisibilityTracker;
import dev.rlcraft.ice.optimizer.render.texture.TextureUploadStream;
import dev.rlcraft.ice.optimizer.render.texture.LwjglAnimatedTextureUploadStream;
import dev.rlcraft.ice.optimizer.render.visibility.ConservativeOcclusionHistory;
import dev.rlcraft.ice.optimizer.render.visibility.LwjglDepthHistory;
import java.util.EnumMap;
import org.lwjgl.opengl.ContextCapabilities;

/** Owns one renderer generation; unpublished graphs may be partially constructed. */
final class RendererComponents {
    ContextCapabilities contextCapabilities;
    long contextGeneration;
    RenderThreadGuard threadGuard;
    GlStateMirror stateMirror;
    FrameCoordinator coordinator;
    LegacyGlIsland legacyIsland;
    CorrelatedRenderProfiler profiler;
    ResourceLedger resources;
    LwjglTerrainArena terrainArena;
    LwjglModelMeshCache modelMeshes;
    ParticleInstanceStream particles;
    LwjglParticleRenderer particleRenderer;
    LwjglFbpPacketRenderer fbpPacketRenderer;
    TextureUploadStream textureUploads;
    SpriteVisibilityTracker spriteVisibility;
    LwjglAnimatedTextureUploadStream animatedTextures;
    HudVertexStream hud;
    LwjglHudRenderer hudRenderer;
    FontLayoutCache fonts;
    CertifiedDrawSites certifiedDrawSites;
    ShaderCertificationRegistry shaders;
    ShaderCertificationPipeline shaderPipeline;
    LwjglShaderCompilationDriver shaderCompiler;
    LwjglShaderProgramInstaller shaderInstaller;
    LwjglShaderImageCertification shaderImageCertification;
    LwjglOptifineShaderActivation shaderActivation;
    OptifineShaderBackendSelector shaderBackendSelector;
    ConservativeOcclusionHistory hzbHistory;
    LwjglDepthHistory depthHistory;
    GlStateQueryWorkspace glStateQueryWorkspace;
    CapabilityReport capabilities;
    final EnumMap<OptimizationModule, AdaptiveBackendController> backends =
        new EnumMap<OptimizationModule, AdaptiveBackendController>(
            OptimizationModule.class);
}
