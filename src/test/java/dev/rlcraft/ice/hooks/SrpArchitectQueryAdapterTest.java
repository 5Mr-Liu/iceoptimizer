package dev.rlcraft.ice.hooks;

import static org.junit.Assert.*;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import org.junit.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.BasicVerifier;

public class SrpArchitectQueryAdapterTest {
    @Test public void originalChunkQueryKeepsPredicateAndOrderedListWrites() throws Exception {
        byte[] result = adapt(SrpArchitectQueryAdapter.TARGET, new SrpArchitectQueryAdapter());
        ClassNode node = new ClassNode(Opcodes.ASM5); new ClassReader(result).accept(node, 0);
        MethodNode fast = ServerModAdapterSupport.require(node, "ice$fast$architectQuery", SrpArchitectQueryAdapter.DESC);
        MethodNode original = ServerModAdapterSupport.require(node, "ice$original$architectQuery", SrpArchitectQueryAdapter.DESC);
        assertEquals(1, calls(fast, SrpArchitectQueryAdapter.BRIDGE, "iterator"));
        assertEquals(calls(original, "com/google/common/base/Predicate", "apply"), calls(fast, "com/google/common/base/Predicate", "apply"));
        assertEquals(calls(original, "java/util/List", "add"), calls(fast, "java/util/List", "add"));
        new Analyzer(new BasicVerifier()).analyze(node.name, fast);
        new Analyzer(new BasicVerifier()).analyze(node.name, ServerModAdapterSupport.require(node,
            "getEntitiesOfTypeWithinAABB", SrpArchitectQueryAdapter.DESC));
    }
    @Test public void realBucketTracksInsertionRemovalAndEmptyToPopulatedTransition() throws Exception {
        final byte[] base = adapt(SrpArchitectBucketAdapter.TARGET, new SrpArchitectBucketAdapter());
        List<URL> urls = new ArrayList<URL>();
        for (String path : System.getProperty("java.class.path").split(File.pathSeparator)) urls.add(new File(path).toURI().toURL());
        URLClassLoader loader = new URLClassLoader(urls.toArray(new URL[urls.size()]), null) {
            @Override protected Class<?> findClass(String name) throws ClassNotFoundException {
                if (SrpArchitectBucketAdapter.TARGET.replace('/', '.').equals(name)) return defineClass(name, base, 0, base.length);
                return super.findClass(name);
            }
        };
        try {
            Class<?> type = loader.loadClass(SrpArchitectBucketAdapter.TARGET.replace('/', '.'));
            Object section = type.getConstructor(Class.class).newInstance(Object.class);
            Class<?> bridge = loader.loadClass("dev.rlcraft.ice.optimizer.compat.srp.SrpArchitectQueryBridge");
            Method iterator = bridge.getMethod("iterator", type, Class.class);
            assertEquals(Collections.emptyList(), values((Iterator<?>) iterator.invoke(null, section, String.class)));
            type.getMethod("add", Object.class).invoke(section, "one");
            type.getMethod("add", Object.class).invoke(section, Integer.valueOf(2));
            type.getMethod("add", Object.class).invoke(section, "three");
            assertEquals(java.util.Arrays.asList("one", "three"), values((Iterator<?>) iterator.invoke(null, section, String.class)));
            type.getMethod("remove", Object.class).invoke(section, "one");
            assertEquals(Collections.singletonList("three"), values((Iterator<?>) iterator.invoke(null, section, String.class)));
            type.getMethod("remove", Object.class).invoke(section, "three");
            assertEquals(Collections.emptyList(), values((Iterator<?>) iterator.invoke(null, section, String.class)));
            type.getMethod("add", Object.class).invoke(section, "later");
            assertEquals(Collections.singletonList("later"), values((Iterator<?>) iterator.invoke(null, section, String.class)));
        } finally { loader.close(); }
    }
    private static List<Object> values(Iterator<?> iterator) { List<Object> values = new ArrayList<Object>(); while (iterator.hasNext()) values.add(iterator.next()); return values; }
    private static byte[] adapt(String target, OptimizerBytecodeAdapter adapter) throws Exception {
        InputStream in = SrpArchitectQueryAdapterTest.class.getClassLoader().getResourceAsStream(target + ".class");
        assertNotNull(in); ByteArrayOutputStream out = new ByteArrayOutputStream(); byte[] buffer = new byte[8192]; int n;
        while ((n = in.read(buffer)) >= 0) out.write(buffer, 0, n); in.close();
        return adapter.transform(target.replace('/', '.'), out.toByteArray(), new TargetSpec("test", "test", "test", Collections.<String>emptySet()));
    }
    private static int calls(MethodNode method, String owner, String name) {
        int count = 0; for (AbstractInsnNode instruction : method.instructions.toArray()) if (instruction instanceof MethodInsnNode) {
            MethodInsnNode call = (MethodInsnNode) instruction; if (owner.equals(call.owner) && name.equals(call.name)) count++;
        } return count;
    }
}
