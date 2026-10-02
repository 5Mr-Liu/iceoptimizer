package dev.rlcraft.ice.profiler.analysis;

import dev.rlcraft.ice.profiler.sampling.StackSample;
import dev.rlcraft.ice.profiler.sampling.ThreadRole;
import java.util.Locale;

/** Trace-only work classification, cached once per stack ID by the analyzer. */
final class StackWorkClassifier {
    private StackWorkClassifier() { }
    static String joinedMethods(StackTraceElement[] trace) {
        StringBuilder joined = new StringBuilder();
        for (StackTraceElement frame : trace) {
            joined.append(frame.getClassName()).append('.').append(frame.getMethodName()).append(' ');
        }
        return joined.toString().toLowerCase(Locale.ROOT);
    }

    static RootCause classifyTrace(StackTraceElement[] trace) {
        return classifyTrace(trace, joinedMethods(trace));
    }

    static RootCause classifyTrace(StackTraceElement[] trace, String value) {
        if (iceIsExecuting(trace)) return RootCause.ICE_RUNTIME;
        if (containsAny(value,
            "smoothsync", "display.sync", "framelimiter", "limitframerate",
            "syncframerate", "waitfornextframe")) {
            return RootCause.FRAME_LIMITER_WAIT;
        }
        if (containsAny(value,
            "glclientwaitsync", "nglclientwaitsync", "glfencesync", "nglfencesync",
            "swapbuffers", "wglswap", "glfinish", "nglfinish",
            "nvoglv", "atio6axx", "ig7icd", "opengl32")) {
            return RootCause.GPU_DRIVER;
        }
        if (containsAny(value, "java.net.socketinputstream", "java.net.socketoutputstream",
            "sun.net.www.protocol", "com.mojang.authlib.httpauthenticationservice")) {
            return RootCause.NETWORK;
        }
        if (containsAny(value,
            "integratedserver.func_71260_j", "minecraftserver.func_71260_j",
            "minecraftserver.stopserver", "handleserverstopped",
            "fmlclienthandler.serverstopped", "unloadworld", "loadworld",
            "freeworld", "shuttingdown")) {
            return RootCause.GAME_LIFECYCLE;
        }
        if (containsAny(value, "anvilchunkloader.func_75820_a", "anvilchunkloader.func_75816_a", "customstructurefilemanager.savestructure", "customstructurefilemanager.savechunksmap", "mapstorage.func_75747_a", "savehandler.func_75755_a", "savechunk", "saveallchunks", "writechunk", "anvilchunkloader.save", "regionfile.write", "compressedstreamtools.write")) return RootCause.CHUNK_SAVING;
        if (containsAny(value, "chunkgenerator", "providechunk", "generatechunk", "worldgen", "mapgen", "genlayer", ".populate")) return RootCause.WORLD_GENERATION;
        if (containsAny(value, "chunkioprovider", "loadchunk", "anvilchunkloader.load", "regionfile.read", "randomaccessfile.read", "inflaterinputstream")) return RootCause.CHUNK_LOADING;
        if (containsAny(value, "checklight", "relight", "lightengine", "generateheightmap")) return RootCause.CHUNK_LIGHTING;
        if (containsAny(value, "chunkrenderdispatcher", "renderchunk.rebuild", "renderchunk.resort", "uploadchunk", "compiledchunk")) return RootCause.CHUNK_RENDERING;
        if (containsAny(value, "pathnavigate", "pathfinder", "pathfind", "walknodeprocessor", "entityai")) return RootCause.AI_PATHFINDING;
        if (containsAny(value, "collision", "getcollisionboxes", "axisalignedbb", "raytraceblocks")) return RootCause.COLLISION;
        if (containsAny(value, "tileentity", "itickable.update")) return RootCause.TILE_ENTITY_TICK;
        if (containsAny(value, "updateentity", "entitylivingbase.onupdate", "onlivingupdate", "entity.onupdate")) return RootCause.ENTITY_TICK;
        if (containsAny(value, "asmeventhandler", "eventbus.post", "listenerlist")) return RootCause.EVENT_HANDLER;
        if (containsAny(value, "networkmanager", "netty", "packetbuffer", "processpacket", "decoder", "encoder")) return RootCause.NETWORK;
        if (containsAny(value, "reloadresources", "resourcemanager", "texturemap", "modelbakery", "soundmanager")) return RootCause.RESOURCE_LOADING;
        if (containsAny(value, "entityrenderer", "renderglobal", "rendermanager", "tessellator", "glstatemanager", "display.update")) return RootCause.CLIENT_RENDER;
        if (containsAny(value, "unsafe.park", "object.wait", "locksupport.park", "reentrantlock")) {
            return RootCause.THREAD_CONTENTION;
        }
        return RootCause.UNKNOWN;
    }

    static boolean mainSleepMayOverride(RootCause traceCause, String methods) {
        return traceCause != RootCause.ICE_RUNTIME
            && traceCause != RootCause.FRAME_LIMITER_WAIT
            && containsAny(methods, "java.lang.thread.sleep", "thread.sleep");
    }

    static RootCause classifySample(StackSample sample, RootCause traceCause,
                                            boolean mainSleepMayOverride) {
        if (mainSleepMayOverride && sample != null
            && sample.getState() == Thread.State.TIMED_WAITING
            && (sample.getRole() == ThreadRole.CLIENT_MAIN
                || sample.getRole() == ThreadRole.SERVER_MAIN)) {
            return RootCause.FRAME_LIMITER_WAIT;
        }
        return traceCause;
    }

    static StackTraceElement firstActionable(StackTraceElement[] trace, ModResolver modResolver, RootCause cause) {
        StackTraceElement fallback = null;
        for (StackTraceElement frame : trace) {
            String name = frame.getClassName();
            if (cause != RootCause.ICE_RUNTIME && name.startsWith("dev.rlcraft.ice.")) continue;
            if (name.equals("ice.profiler")) continue;
            if (name.startsWith("java.lang.Thread") || name.startsWith("sun.reflect.") || name.startsWith("java.lang.reflect.")) continue;
            if (name.startsWith("java.") || name.startsWith("javax.") || name.startsWith("sun.") || name.startsWith("com.sun.")) continue;
            if (name.equals("net.minecraft.client.Minecraft") || name.equals("net.minecraft.server.MinecraftServer")) continue;
            if (fallback == null) fallback = frame;
            ModIdentity owner = modResolver.resolve(name);
            if (owner != ModIdentity.MINECRAFT && owner != ModIdentity.FORGE && owner != ModIdentity.JVM && owner != ModIdentity.LWJGL) return frame;
        }
        return fallback == null && trace.length > 0 ? trace[0] : fallback;
    }


    private static boolean iceIsExecuting(StackTraceElement[] trace) {
        for (StackTraceElement frame : trace) {
            String name = frame.getClassName();
            if (name.equals("java.lang.Thread") || name.startsWith("sun.reflect.")
                || name.startsWith("java.lang.reflect.")) continue;
            return name.startsWith("dev.rlcraft.ice.");
        }
        return false;
    }

    private static boolean containsAny(String value, String... needles) {
        for (String needle : needles) if (value.contains(needle)) return true;
        return false;
    }
}
