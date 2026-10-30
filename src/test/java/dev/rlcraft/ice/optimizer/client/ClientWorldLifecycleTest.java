package dev.rlcraft.ice.optimizer.client;

import dev.rlcraft.ice.early.EarlyRendererOwnership;
import dev.rlcraft.ice.optimizer.runtime.ClientEpochs;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.WorldClient;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import sun.misc.Unsafe;
import static org.junit.Assert.*;

/** Real controller/runtime event order, without starting Minecraft or OpenGL. */
public class ClientWorldLifecycleTest {
    private final ClientOptimizerRuntime runtime = ClientOptimizerRuntime.INSTANCE;
    private final Map<Field, Object> saved = new LinkedHashMap<>();
    private final List<String> terrain = new ArrayList<>();
    private ClientEpochs epochs;
    private Minecraft minecraft;
    private ClientOptimizerController controller;
    private boolean external, nativeRenderer;

    @Before public void setUp() throws Exception {
        external = EarlyRendererOwnership.hasExternalTerrainRenderer();
        nativeRenderer = EarlyRendererOwnership.hasNativeTerrainRenderer();
        EarlyRendererOwnership.initialize(false, true);
        epochs = new ClientEpochs();
        replace("epochs", epochs);
        replace("initialized", true);
        replace("modernRenderer", null);
        replace("workers", null);
        replace("renderQueue", null);
        replace("nativeTerrainPause", new NativeTerrainPauseLifecycle() {
            @Override void beforeChange() { terrain.add("drain@" + epochs.currentFrameId()); }
            @Override void afterChange() { terrain.add("rebuild@" + epochs.currentFrameId()); }
        });
        minecraft = (Minecraft) unsafe().allocateInstance(Minecraft.class);
        Constructor<ClientOptimizerController> constructor = ClientOptimizerController.class.getDeclaredConstructor();
        constructor.setAccessible(true);
        controller = constructor.newInstance();
        Field client = ClientOptimizerController.class.getDeclaredField("minecraft");
        client.setAccessible(true);
        client.set(controller, minecraft);
    }

    @After public void tearDown() throws Exception {
        for (Map.Entry<Field, Object> entry : saved.entrySet()) entry.getKey().set(runtime, entry.getValue());
        EarlyRendererOwnership.initialize(external, nativeRenderer);
    }

    @Test public void networkJoinBeforeAZeroTickFrameDoesNotRetireThatFramesMeshesOnTheNextTick() throws Exception {
        // Minecraft drains scheduled network packets even when elapsedTicks is zero.
        minecraft.world = newWorld();
        startFrame();
        assertEquals("World ownership must be established before its first render", 2, epochs.currentWorldGeneration());
        assertEquals(Arrays.asList("drain@0", "rebuild@0"), terrain);
        // Terrain uploaded during this frame must survive the following client tick.
        controller.onClientTick(new TickEvent.ClientTickEvent(TickEvent.Phase.START));
        startFrame();
        assertEquals(2, epochs.currentWorldGeneration());
        assertEquals(Arrays.asList("drain@0", "rebuild@0"), terrain);
    }

    @Test public void dimensionChangeAndDisconnectAreObservedBeforeRenderingWithoutWaitingForATick() throws Exception {
        minecraft.world = newWorld();
        startFrame();
        minecraft.world = newWorld();
        startFrame();
        minecraft.world = null;
        startFrame();
        startFrame();
        assertEquals(4, epochs.currentWorldGeneration());
        assertEquals(Arrays.asList("drain@0", "rebuild@0", "drain@1", "rebuild@1",
            "drain@2", "rebuild@2"), terrain);
    }

    @Test public void tickFirstJoinDrainsAndRebuildsBeforeAnyNewFrame() throws Exception {
        minecraft.world = newWorld();
        controller.onClientTick(new TickEvent.ClientTickEvent(TickEvent.Phase.START));
        assertEquals(Arrays.asList("drain@0", "rebuild@0"), terrain);
        startFrame();
        assertEquals(2, terrain.size());
    }

    @Test public void worldInvalidationMustRetireWorkersAndRebuildCompiledChunksTogether() {
        runtime.worldChanged();
        assertEquals(Arrays.asList("drain@0", "rebuild@0"), terrain);
        assertEquals(2, epochs.currentWorldGeneration());
    }

    private void startFrame() {
        controller.onRenderTick(new TickEvent.RenderTickEvent(TickEvent.Phase.START, 0.5F));
    }

    private static WorldClient newWorld() throws Exception {
        return (WorldClient) unsafe().allocateInstance(WorldClient.class);
    }

    private void replace(String name, Object value) throws Exception {
        Field field = ClientOptimizerRuntime.class.getDeclaredField(name);
        field.setAccessible(true);
        saved.put(field, field.get(runtime));
        field.set(runtime, value);
    }

    private static Unsafe unsafe() throws Exception {
        Field field = Unsafe.class.getDeclaredField("theUnsafe");
        field.setAccessible(true);
        return (Unsafe) field.get(null);
    }
}
