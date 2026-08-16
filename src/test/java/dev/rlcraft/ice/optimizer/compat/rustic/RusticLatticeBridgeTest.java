package dev.rlcraft.ice.optimizer.compat.rustic;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;

import dev.rlcraft.ice.optimizer.OptimizationModule;
import dev.rlcraft.ice.optimizer.OptimizerRegistry;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraftforge.common.property.IExtendedBlockState;
import net.minecraftforge.common.property.IUnlistedProperty;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

public final class RusticLatticeBridgeTest {
    @Before
    public void enable() {
        OptimizerRegistry.breaker(OptimizationModule.RUSTIC_LATTICE_STATE).configure(true, 3);
        OptimizerRegistry.breaker(OptimizationModule.RUSTIC_LATTICE_STATE)
            .patchInstalled("BlockLattice", "test");
    }

    @After
    public void disable() {
        OptimizerRegistry.breaker(OptimizationModule.RUSTIC_LATTICE_STATE).configure(false, 3);
    }

    @Test
    public void canonicalizesRepeatedImmutableStateTransitions() {
        AtomicInteger creations = new AtomicInteger();
        IExtendedBlockState base = state(new IdentityHashMap<IUnlistedProperty<?>, Object>(), creations);
        Property property = new Property("north");
        IExtendedBlockState first = RusticLatticeBridge.withProperty(base, property, Boolean.TRUE);
        IExtendedBlockState second = RusticLatticeBridge.withProperty(base, property, Boolean.TRUE);
        assertSame(first, second);
        assertEquals(1, creations.get());
    }

    @Test
    public void returnsOneOfTheExactSixtyFourBoundingBoxes() {
        Object[] properties = new Object[6];
        Map<IUnlistedProperty<?>, Object> values = new IdentityHashMap<IUnlistedProperty<?>, Object>();
        for (int i = 0; i < properties.length; i++) {
            Property property = new Property("p" + i);
            properties[i] = property;
            values.put(property, Boolean.FALSE);
        }
        values.put((IUnlistedProperty<?>) properties[0], Boolean.TRUE);
        values.put((IUnlistedProperty<?>) properties[3], Boolean.TRUE);
        AxisAlignedBB box = RusticLatticeBridge.boundingBox(state(values, new AtomicInteger()), properties);
        assertEquals(0.375D, box.minX, 0.0D);
        assertEquals(0.0D, box.minY, 0.0D);
        assertEquals(0.375D, box.minZ, 0.0D);
        assertEquals(0.625D, box.maxX, 0.0D);
        assertEquals(0.625D, box.maxY, 0.0D);
        assertEquals(1.0D, box.maxZ, 0.0D);
        assertSame(box, RusticLatticeBridge.boundingBox(state(values, new AtomicInteger()), properties));
    }

    private static IExtendedBlockState state(final Map<IUnlistedProperty<?>, Object> values,
                                             final AtomicInteger creations) {
        InvocationHandler handler = new InvocationHandler() {
            @Override
            public Object invoke(Object proxy, Method method, Object[] args) {
                if ("withProperty".equals(method.getName())) {
                    Map<IUnlistedProperty<?>, Object> replacement =
                        new IdentityHashMap<IUnlistedProperty<?>, Object>(values);
                    replacement.put((IUnlistedProperty<?>) args[0], args[1]);
                    creations.incrementAndGet();
                    return state(replacement, creations);
                }
                if ("getValue".equals(method.getName())) return values.get(args[0]);
                if ("toString".equals(method.getName())) return "FakeExtendedState" + values;
                if ("hashCode".equals(method.getName())) return System.identityHashCode(proxy);
                if ("equals".equals(method.getName())) return proxy == args[0];
                Class<?> type = method.getReturnType();
                if (type == boolean.class) return Boolean.FALSE;
                if (type == int.class) return Integer.valueOf(0);
                return null;
            }
        };
        return (IExtendedBlockState) Proxy.newProxyInstance(
            RusticLatticeBridgeTest.class.getClassLoader(),
            new Class<?>[] { IExtendedBlockState.class }, handler);
    }

    private static final class Property implements IUnlistedProperty<Boolean> {
        private final String name;
        private Property(String name) { this.name = name; }
        @Override public String getName() { return name; }
        @Override public boolean isValid(Boolean value) { return value != null; }
        @Override public Class<Boolean> getType() { return Boolean.class; }
        @Override public String valueToString(Boolean value) { return String.valueOf(value); }
    }
}
