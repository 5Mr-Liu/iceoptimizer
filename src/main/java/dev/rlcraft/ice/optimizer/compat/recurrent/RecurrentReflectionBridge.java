package dev.rlcraft.ice.optimizer.compat.recurrent;

import dev.rlcraft.ice.optimizer.bridge.OptimizerBridge;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import net.minecraftforge.fml.relauncher.ReflectionHelper;

/** Caches class metadata, never event values or the result of a structure call. */
public final class RecurrentReflectionBridge {
    private static final ClassValue<EventMethods> EVENTS = new ClassValue<EventMethods>() {
        @Override protected EventMethods computeValue(Class<?> type) { return new EventMethods(type); }
    };
    private static final ClassValue<Bindings> BINDINGS = new ClassValue<Bindings>() {
        @Override protected Bindings computeValue(Class<?> type) { return new Bindings(); }
    };

    private RecurrentReflectionBridge() { }

    public static boolean methodsAvailable(Object event, int operation) {
        if (!OptimizerBridge.isEnabled("recurrent-reflection")) return true;
        EventMethods methods = event == null ? EventMethods.NONE : EVENTS.get(event.getClass());
        return operation == 1 ? methods.get != null : operation == 2 ? methods.set != null
            : methods.has != null && methods.get != null && methods.set != null;
    }

    private static final class EventMethods {
        static final EventMethods NONE = new EventMethods(null);
        final Method has, get, set;
        EventMethods(Class<?> type) {
            has = resolve(type, "hasAmountData");
            get = resolve(type, "getModifiedAmount");
            set = resolve(type, "setModifiedAmount", Integer.TYPE);
        }
        private static Method resolve(Class<?> type, String name, Class<?>... arguments) {
            if (type == null) return null;
            try { return type.getDeclaredMethod(name, arguments); }
            catch (Exception absent) { return null; }
        }
    }

    public static Method declaredMethod(Class<?> owner, String name, Class<?>[] arguments)
            throws NoSuchMethodException {
        if (!OptimizerBridge.isEnabled("recurrent-reflection")) return owner.getDeclaredMethod(name, arguments);
        EventMethods methods = EVENTS.get(owner);
        Method result = "hasAmountData".equals(name) ? methods.has : "getModifiedAmount".equals(name) ? methods.get : methods.set;
        if (result == null) throw new NoSuchMethodException(owner.getName() + "." + name);
        return result;
    }

    public static Method structureMethod(Class<?> owner, String mapped, String srg, Class<?>[] arguments) {
        if (!OptimizerBridge.isEnabled("recurrent-reflection"))
            return ReflectionHelper.findMethod(owner, mapped, srg, arguments);
        return BINDINGS.get(owner).structure(owner, mapped, srg, arguments);
    }

    private static final class Bindings {
        private final Map<Key, Method> structures = new HashMap<Key, Method>();

        synchronized Method structure(Class<?> owner, String mapped, String srg, Class<?>[] arguments) {
            Key key = new Key(mapped, srg, arguments);
            Method method = structures.get(key);
            if (method == null) {
                method = ReflectionHelper.findMethod(owner, mapped, srg, arguments);
                structures.put(key, method);
            }
            return method;
        }
    }

    private static final class Key {
        final String mapped, srg;
        final Class<?>[] arguments;
        Key(String mapped, String srg, Class<?>[] arguments) {
            this.mapped = mapped;
            this.srg = srg;
            this.arguments = arguments;
        }
        @Override public int hashCode() { return mapped.hashCode() * 31 + Arrays.hashCode(arguments); }
        @Override public boolean equals(Object other) {
            if (!(other instanceof Key)) return false;
            Key key = (Key) other;
            return mapped.equals(key.mapped) && (srg == null ? key.srg == null : srg.equals(key.srg))
                && Arrays.equals(arguments, key.arguments);
        }
    }
}
