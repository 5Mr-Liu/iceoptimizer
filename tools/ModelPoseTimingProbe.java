import java.io.*;
import java.lang.reflect.*;
import java.util.*;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
/** Offline attribution of native entry time; not part of the shipped mod. */
public final class ModelPoseTimingProbe {
    private static final Map<String,Integer> ids = new LinkedHashMap<String,Integer>();
    private static final long[] nanos = new long[256], counts = new long[256], starts = new long[256];
    public static void before(int id) { starts[id] = System.nanoTime(); }
    public static void after(int id) { nanos[id] += System.nanoTime() - starts[id]; counts[id]++; }
    public static void main(String[] args) throws Exception {
        ClassLoader loader = new ClassLoader(ModelPoseTimingProbe.class.getClassLoader()) {
            @Override protected synchronized Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
                if (!(name.startsWith("dev.rlcraft.ice.") || name.equals("ModelPoseBatchProbe") || name.startsWith("ModelPoseBatchProbe$"))) return super.loadClass(name, resolve);
                Class<?> value = findLoadedClass(name);
                if (value == null) {
                    byte[] bytes;
                    String resource = name.replace('.', '/') + ".class";
                    String directory = System.getProperty("ice.model.baselineClasses");
                    File baseline = directory == null ? null : new File(directory, resource);
                    try (InputStream in = baseline != null && baseline.isFile()
                        ? new FileInputStream(baseline) : getParent().getResourceAsStream(resource)) {
                        if (in == null) throw new ClassNotFoundException(name);
                        ByteArrayOutputStream out = new ByteArrayOutputStream(); byte[] data = new byte[8192];
                        for (int n; (n = in.read(data)) >= 0;) out.write(data, 0, n);
                        bytes = out.toByteArray();
                    } catch (IOException e) { throw new ClassNotFoundException(name, e); }
                    if (name.startsWith("dev.rlcraft.ice.optimizer.render.entity.LwjglModel")) {
                        ClassNode node = new ClassNode(Opcodes.ASM5); new ClassReader(bytes).accept(node, 0);
                        for (MethodNode method : node.methods) for (AbstractInsnNode insn : method.instructions.toArray()) {
                            if (!(insn instanceof MethodInsnNode)) continue;
                            MethodInsnNode call = (MethodInsnNode)insn;
                            if (!call.owner.startsWith("org/lwjgl/opengl/")) continue;
                            String key = node.name.substring(node.name.lastIndexOf('/') + 1) + ":" + method.name + ":" + call.name;
                            Integer id = ids.get(key); if (id == null) { id = ids.size(); ids.put(key, id); }
                            InsnList before = new InsnList(); before.add(new LdcInsnNode(id)); before.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "ModelPoseTimingProbe", "before", "(I)V", false));
                            method.instructions.insertBefore(call, before);
                            InsnList after = new InsnList(); after.add(new LdcInsnNode(id)); after.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "ModelPoseTimingProbe", "after", "(I)V", false));
                            method.instructions.insert(call, after);
                        }
                        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS); node.accept(writer); bytes = writer.toByteArray();
                    }
                    value = defineClass(name, bytes, 0, bytes.length);
                }
                if (resolve) resolveClass(value); return value;
            }
        };
        try { Class.forName("ModelPoseBatchProbe", true, loader).getMethod("main", String[].class).invoke(null, (Object)args); }
        catch (InvocationTargetException e) { throw new RuntimeException(e.getCause()); }
        List<Map.Entry<String,Integer>> order = new ArrayList<Map.Entry<String,Integer>>(ids.entrySet());
        Collections.sort(order, new Comparator<Map.Entry<String,Integer>>() { public int compare(Map.Entry<String,Integer> a, Map.Entry<String,Integer> b) {return Long.compare(nanos[b.getValue()], nanos[a.getValue()]);} });
        for (Map.Entry<String,Integer> item : order) { int id = item.getValue(); if (counts[id] > 0) System.out.println("native_time " + item.getKey() + " count=" + counts[id] + " total_ms=" + nanos[id]/1e6 + " mean_us=" + nanos[id]/(counts[id]*1000.0)); }
    }
}
