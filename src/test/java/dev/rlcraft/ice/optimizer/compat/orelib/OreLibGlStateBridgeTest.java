package dev.rlcraft.ice.optimizer.compat.orelib;

import static org.junit.Assert.assertEquals;

import dev.rlcraft.ice.optimizer.ClientOptimizerConfig;
import dev.rlcraft.ice.optimizer.ModuleState;
import dev.rlcraft.ice.optimizer.OptimizationModule;
import dev.rlcraft.ice.optimizer.OptimizerConfig;
import dev.rlcraft.ice.optimizer.OptimizerRegistry;
import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.Map;
import net.minecraft.client.renderer.GlStateManager;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

public class OreLibGlStateBridgeTest {
    private boolean previousEnabled;
    private boolean previousModuleEnabled;

    @Before
    public void enableModule() {
        previousEnabled = OptimizerConfig.settings.enabled;
        previousModuleEnabled = OptimizerConfig.settings.oreLibGlState;
        OptimizerConfig.settings.enabled = true;
        OptimizerConfig.settings.oreLibGlState = true;
        OptimizerRegistry.targetObserved("orelib-gl-state", "test.OpenGlState", repeat('a', 64), true);
        OptimizerRegistry.configure(ClientOptimizerConfig.capture());
        OreLibGlStateBridge.resetForTests();
    }

    @After
    public void restoreConfiguration() {
        OreLibGlStateBridge.resetForTests();
        OptimizerConfig.settings.enabled = previousEnabled;
        OptimizerConfig.settings.oreLibGlState = previousModuleEnabled;
        OptimizerRegistry.configure(ClientOptimizerConfig.capture());
    }

    @Test
    public void validatesOnceThenUsesCachedStateWithOnlyThreeNativeSentinels() throws Exception {
        StateFixture fixture = new StateFixture();
        try {
            fixture.installReviewedValues();
            FakeQueries nativeQueries = matchingQueries();
            OreLibGlStateBridge.installNativeQueriesForTests(nativeQueries);

            assertSnapshot(32774);
            assertEquals("the first snapshot must validate every original query", 16, nativeQueries.totalCalls());

            nativeQueries.resetCounts();
            nativeQueries.defaultInteger = -777;
            nativeQueries.defaultFloat = -777.0F;
            nativeQueries.integers.clear();
            nativeQueries.integers.put(3042, 1);
            nativeQueries.integers.put(32777, 32774);
            nativeQueries.integers.put(3553, 1);

            assertSnapshot(32774);
            assertEquals("steady snapshots must retain only blend, equation and texture sentinels",
                3, nativeQueries.totalCalls());
            assertEquals(ModuleState.ACTIVE,
                OptimizerRegistry.breaker(OptimizationModule.ORELIB_GL_STATE).snapshot().getState());
        } finally {
            fixture.close();
        }
    }

    @Test
    public void cacheMismatchReturnsNativeValuesAndPermanentlyFallsBack() throws Exception {
        StateFixture fixture = new StateFixture();
        try {
            fixture.installReviewedValues();
            FakeQueries nativeQueries = matchingQueries();
            nativeQueries.integers.put(3009, 519);
            OreLibGlStateBridge.installNativeQueriesForTests(nativeQueries);

            assertEquals(1, OreLibGlStateBridge.getInteger(3042));
            assertEquals(770, OreLibGlStateBridge.getInteger(3041));
            assertEquals(771, OreLibGlStateBridge.getInteger(3040));
            assertEquals(32774, OreLibGlStateBridge.getInteger(32777));
            assertEquals(0, OreLibGlStateBridge.getInteger(3008));
            assertEquals("the mismatching full-validation value must remain the native truth",
                519, OreLibGlStateBridge.getInteger(3009));
            finishSnapshot();

            assertEquals(ModuleState.INCOMPATIBLE,
                OptimizerRegistry.breaker(OptimizationModule.ORELIB_GL_STATE).snapshot().getState());
            nativeQueries.resetCounts();
            assertSnapshotWithAlphaFunc(32774, 519);
            assertEquals("a rejected cache must use all sixteen original native queries",
                16, nativeQueries.totalCalls());
        } finally {
            fixture.close();
        }
    }

    @Test
    public void disabledModuleDelegatesDirectlyToNativeQueries() {
        OptimizerConfig.settings.oreLibGlState = false;
        OptimizerRegistry.configure(ClientOptimizerConfig.capture());
        FakeQueries nativeQueries = new FakeQueries();
        nativeQueries.defaultInteger = 73;
        nativeQueries.defaultFloat = 0.625F;
        OreLibGlStateBridge.installNativeQueriesForTests(nativeQueries);

        assertEquals(73, OreLibGlStateBridge.getInteger(3041));
        assertEquals(0.625F, OreLibGlStateBridge.getFloat(3010), 0.0F);
        assertEquals(2, nativeQueries.totalCalls());
    }

    @Test
    public void reflectionResolutionFailureReturnsNativeTruthAndRejectsOnlyThisModule() throws Exception {
        Object[] textures = (Object[]) field(GlStateManager.class, "textureState").get(null);
        Field textureState = field(textures[0].getClass(), "texture2DState");
        Object previous = textureState.get(textures[0]);
        ModuleState renderLibBefore =
            OptimizerRegistry.breaker(OptimizationModule.RENDERLIB_VISIBILITY).snapshot().getState();
        FakeQueries nativeQueries = matchingQueries();
        OreLibGlStateBridge.installNativeQueriesForTests(nativeQueries);
        try {
            textureState.set(textures[0], null);
            assertEquals(1, OreLibGlStateBridge.getInteger(3042));
            assertEquals(ModuleState.INCOMPATIBLE,
                OptimizerRegistry.breaker(OptimizationModule.ORELIB_GL_STATE).snapshot().getState());
            assertEquals("other modules must retain their previous state", renderLibBefore,
                OptimizerRegistry.breaker(OptimizationModule.RENDERLIB_VISIBILITY).snapshot().getState());
        } finally {
            textureState.set(textures[0], previous);
        }
    }

    private static void assertSnapshot(int equation) {
        assertSnapshotWithAlphaFunc(equation, 516);
    }

    private static void assertSnapshotWithAlphaFunc(int equation, int alphaFunc) {
        assertEquals(1, OreLibGlStateBridge.getInteger(3042));
        assertEquals(770, OreLibGlStateBridge.getInteger(3041));
        assertEquals(771, OreLibGlStateBridge.getInteger(3040));
        assertEquals(equation, OreLibGlStateBridge.getInteger(32777));
        assertEquals(0, OreLibGlStateBridge.getInteger(3008));
        assertEquals(alphaFunc, OreLibGlStateBridge.getInteger(3009));
        assertEquals(0.375F, OreLibGlStateBridge.getFloat(3010), 0.0F);
        assertEquals(1, OreLibGlStateBridge.getInteger(2929));
        assertEquals(515, OreLibGlStateBridge.getInteger(2932));
        assertEquals(0, OreLibGlStateBridge.getInteger(2884));
        assertEquals(1032, OreLibGlStateBridge.getInteger(2885));
        assertEquals(1, OreLibGlStateBridge.getInteger(2896));
        assertEquals(0, OreLibGlStateBridge.getInteger(2930));
        assertEquals(1, OreLibGlStateBridge.getInteger(2977));
        assertEquals(0, OreLibGlStateBridge.getInteger(32826));
        assertEquals(1, OreLibGlStateBridge.getInteger(3553));
    }

    private static void finishSnapshot() {
        OreLibGlStateBridge.getFloat(3010);
        OreLibGlStateBridge.getInteger(2929);
        OreLibGlStateBridge.getInteger(2932);
        OreLibGlStateBridge.getInteger(2884);
        OreLibGlStateBridge.getInteger(2885);
        OreLibGlStateBridge.getInteger(2896);
        OreLibGlStateBridge.getInteger(2930);
        OreLibGlStateBridge.getInteger(2977);
        OreLibGlStateBridge.getInteger(32826);
        OreLibGlStateBridge.getInteger(3553);
    }

    private static FakeQueries matchingQueries() {
        FakeQueries result = new FakeQueries();
        result.integers.put(3042, 1);
        result.integers.put(3041, 770);
        result.integers.put(3040, 771);
        result.integers.put(32777, 32774);
        result.integers.put(3008, 0);
        result.integers.put(3009, 516);
        result.integers.put(2929, 1);
        result.integers.put(2932, 515);
        result.integers.put(2884, 0);
        result.integers.put(2885, 1032);
        result.integers.put(2896, 1);
        result.integers.put(2930, 0);
        result.integers.put(2977, 1);
        result.integers.put(32826, 0);
        result.integers.put(3553, 1);
        result.floats.put(3010, 0.375F);
        return result;
    }

    private static Field field(Class<?> owner, String name) throws Exception {
        Field result = owner.getDeclaredField(name);
        result.setAccessible(true);
        return result;
    }

    private static String repeat(char value, int count) {
        StringBuilder result = new StringBuilder(count);
        for (int i = 0; i < count; i++) result.append(value);
        return result.toString();
    }

    private static final class FakeQueries implements OreLibGlStateBridge.NativeQueries {
        private final Map<Integer, Integer> integers = new HashMap<Integer, Integer>();
        private final Map<Integer, Float> floats = new HashMap<Integer, Float>();
        private int integerCalls;
        private int floatCalls;
        private int defaultInteger;
        private float defaultFloat;

        @Override
        public int getInteger(int pname) {
            integerCalls++;
            Integer value = integers.get(pname);
            return value == null ? defaultInteger : value.intValue();
        }

        @Override
        public float getFloat(int pname) {
            floatCalls++;
            Float value = floats.get(pname);
            return value == null ? defaultFloat : value.floatValue();
        }

        private int totalCalls() {
            return integerCalls + floatCalls;
        }

        private void resetCounts() {
            integerCalls = 0;
            floatCalls = 0;
        }
    }

    private static final class StateFixture implements AutoCloseable {
        private final Object alphaState = staticValue("alphaState");
        private final Object blendState = staticValue("blendState");
        private final Object depthState = staticValue("depthState");
        private final Object cullState = staticValue("cullState");
        private final Object lighting = staticValue("lightingState");
        private final Object normal = staticValue("normalizeState");
        private final Object rescale = staticValue("rescaleNormalState");
        private final Object[] textures = (Object[]) staticValue("textureState");
        private final Field activeTexture = requiredField(GlStateManager.class, "activeTextureUnit");
        private final Field currentState;
        private final Object alphaTest;
        private final Object blend;
        private final Object depthTest;
        private final Object cullFace;
        private final Object texture2D;
        private final Snapshot previous = new Snapshot();

        private StateFixture() throws Exception {
            alphaTest = object(alphaState, "alphaTest");
            blend = object(blendState, "blend");
            depthTest = object(depthState, "depthTest");
            cullFace = object(cullState, "cullFace");
            texture2D = object(textures[1], "texture2DState");
            currentState = requiredField(alphaTest.getClass(), "currentState");
            previous.capture(this);
        }

        private void installReviewedValues() throws Exception {
            setBoolean(alphaTest, false);
            setBoolean(blend, true);
            setBoolean(depthTest, true);
            setBoolean(cullFace, false);
            setBoolean(lighting, true);
            setBoolean(normal, true);
            setBoolean(rescale, false);
            setBoolean(texture2D, true);
            requiredField(alphaState.getClass(), "func").setInt(alphaState, 516);
            requiredField(alphaState.getClass(), "ref").setFloat(alphaState, 0.375F);
            requiredField(blendState.getClass(), "srcFactor").setInt(blendState, 770);
            requiredField(blendState.getClass(), "dstFactor").setInt(blendState, 771);
            requiredField(depthState.getClass(), "maskEnabled").setBoolean(depthState, false);
            requiredField(depthState.getClass(), "depthFunc").setInt(depthState, 515);
            requiredField(cullState.getClass(), "mode").setInt(cullState, 1032);
            activeTexture.setInt(null, 1);
        }

        private void setBoolean(Object target, boolean value) throws Exception {
            currentState.setBoolean(target, value);
        }

        @Override
        public void close() throws Exception {
            previous.restore(this);
        }

        private static Object staticValue(String name) {
            try {
                return requiredField(GlStateManager.class, name).get(null);
            } catch (Exception error) {
                throw new IllegalStateException(error);
            }
        }

        private static Object object(Object owner, String name) throws Exception {
            return requiredField(owner.getClass(), name).get(owner);
        }

        private static Field requiredField(Class<?> owner, String name) throws Exception {
            Field result = owner.getDeclaredField(name);
            result.setAccessible(true);
            return result;
        }
    }

    private static final class Snapshot {
        private boolean alpha;
        private boolean blend;
        private boolean depth;
        private boolean cull;
        private boolean lighting;
        private boolean normal;
        private boolean rescale;
        private boolean texture;
        private int alphaFunc;
        private float alphaRef;
        private int blendSource;
        private int blendDest;
        private boolean depthMask;
        private int depthFunc;
        private int cullMode;
        private int activeTexture;

        private void capture(StateFixture state) throws Exception {
            alpha = state.currentState.getBoolean(state.alphaTest);
            blend = state.currentState.getBoolean(state.blend);
            depth = state.currentState.getBoolean(state.depthTest);
            cull = state.currentState.getBoolean(state.cullFace);
            lighting = state.currentState.getBoolean(state.lighting);
            normal = state.currentState.getBoolean(state.normal);
            rescale = state.currentState.getBoolean(state.rescale);
            texture = state.currentState.getBoolean(state.texture2D);
            alphaFunc = StateFixture.requiredField(state.alphaState.getClass(), "func").getInt(state.alphaState);
            alphaRef = StateFixture.requiredField(state.alphaState.getClass(), "ref").getFloat(state.alphaState);
            blendSource = StateFixture.requiredField(state.blendState.getClass(), "srcFactor").getInt(state.blendState);
            blendDest = StateFixture.requiredField(state.blendState.getClass(), "dstFactor").getInt(state.blendState);
            depthMask = StateFixture.requiredField(state.depthState.getClass(), "maskEnabled").getBoolean(state.depthState);
            depthFunc = StateFixture.requiredField(state.depthState.getClass(), "depthFunc").getInt(state.depthState);
            cullMode = StateFixture.requiredField(state.cullState.getClass(), "mode").getInt(state.cullState);
            activeTexture = state.activeTexture.getInt(null);
        }

        private void restore(StateFixture state) throws Exception {
            state.currentState.setBoolean(state.alphaTest, alpha);
            state.currentState.setBoolean(state.blend, blend);
            state.currentState.setBoolean(state.depthTest, depth);
            state.currentState.setBoolean(state.cullFace, cull);
            state.currentState.setBoolean(state.lighting, lighting);
            state.currentState.setBoolean(state.normal, normal);
            state.currentState.setBoolean(state.rescale, rescale);
            state.currentState.setBoolean(state.texture2D, texture);
            StateFixture.requiredField(state.alphaState.getClass(), "func").setInt(state.alphaState, alphaFunc);
            StateFixture.requiredField(state.alphaState.getClass(), "ref").setFloat(state.alphaState, alphaRef);
            StateFixture.requiredField(state.blendState.getClass(), "srcFactor").setInt(state.blendState, blendSource);
            StateFixture.requiredField(state.blendState.getClass(), "dstFactor").setInt(state.blendState, blendDest);
            StateFixture.requiredField(state.depthState.getClass(), "maskEnabled").setBoolean(state.depthState, depthMask);
            StateFixture.requiredField(state.depthState.getClass(), "depthFunc").setInt(state.depthState, depthFunc);
            StateFixture.requiredField(state.cullState.getClass(), "mode").setInt(state.cullState, cullMode);
            state.activeTexture.setInt(null, activeTexture);
        }
    }
}
