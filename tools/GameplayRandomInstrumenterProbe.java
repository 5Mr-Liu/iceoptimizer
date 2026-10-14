package dev.rlcraft.ice.benchmark;

import java.io.*;
import java.util.jar.*;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;

/** Verify actual input-hook sites without launching or timing a game. */
public final class GameplayRandomInstrumenterProbe {
    public static void main(String[] args)throws Exception {
        try(JarFile jar=new JarFile(args[0])) {
            verify("net/silentchaos512/scalinghealth/event/DifficultyHandler",
                read(jar.getInputStream(jar.getJarEntry("net/silentchaos512/scalinghealth/event/DifficultyHandler.class"))),"healthRandom");
        }
        try(JarFile jar=new JarFile(args[1])) {
            String kill="com/lycanitesmobs/core/spawner/trigger/KillSpawnTrigger";
            verify(kill,read(jar.getInputStream(jar.getJarEntry(kill+".class"))),"killRandom");
        }
        try(JarFile jar=new JarFile(args[2])) {
            verifyChampions(jar);
        }
        String zombie="net/minecraft/entity/monster/EntityZombie";
        verify(zombie,read(GameplayRandomInstrumenterProbe.class.getClassLoader().getResourceAsStream(zombie+".class")),"beforeAttributes");
        if(GameplayRandomInstrumenter.transform("unrelated/Class",new byte[0])!=null)throw new AssertionError("Unrelated class changed");
        System.out.println("gameplay_random_transform=true actual_health_zombie_kill_champions=true unrelated_unchanged=true");
    }
    static void verifyChampions(JarFile jar)throws Exception {
        String helper="c4/champions/common/util/ChampionHelper";
        byte[] original=read(jar.getInputStream(jar.getJarEntry(helper+".class")));
        ClassNode before=node(original),after=node(GameplayRandomInstrumenter.transform(helper,original));
        int hooks=0;
        if(before.methods.size()!=after.methods.size())throw new AssertionError("Champion helper method set changed");
        for(int i=0;i<before.methods.size();i++) {
            MethodNode a=before.methods.get(i),b=after.methods.get(i);int added=0;
            for(AbstractInsnNode instruction:b.instructions.toArray())if(instruction instanceof MethodInsnNode) {
                MethodInsnNode call=(MethodInsnNode)instruction;
                if(call.owner.equals("dev/rlcraft/ice/benchmark/LiveCombatRandom")) {
                    if(!call.name.equals("championRandom"))throw new AssertionError("Unexpected champion hook");
                    AbstractInsnNode previous=call.getPrevious();
                    if(!(previous instanceof FieldInsnNode)||!((FieldInsnNode)previous).name.equals("rand"))throw new AssertionError("Random source changed");
                    added++;
                }
            }
            if(b.instructions.size()!=a.instructions.size()+added)throw new AssertionError("Champion formulas changed");
            hooks+=added;
        }
        if(hooks!=6)throw new AssertionError("Champion rank/name/affix input count");
        String events="c4/champions/common/capability/CapabilityChampionship$EventHandler";
        original=read(jar.getInputStream(jar.getJarEntry(events+".class")));
        before=node(original);after=node(GameplayRandomInstrumenter.transform(events,original));
        if(after.methods.size()!=before.methods.size()+2)throw new AssertionError("Event wrapper count");
        int wrappers=0;
        for(MethodNode method:after.methods)if(method.name.equals("entityJoin")||method.name.equals("entitySpawn")) {
            new org.objectweb.asm.tree.analysis.Analyzer<org.objectweb.asm.tree.analysis.BasicValue>(new org.objectweb.asm.tree.analysis.BasicVerifier()).analyze(events,method);
            if(method.tryCatchBlocks.size()!=1||method.tryCatchBlocks.get(0).type!=null)throw new AssertionError("Exception-safe scope missing");
            if(method.visibleAnnotations==null||method.visibleAnnotations.isEmpty())throw new AssertionError("Forge event registration lost");
            int begin=0,end=0,delegates=0;
            for(AbstractInsnNode instruction:method.instructions.toArray())if(instruction instanceof MethodInsnNode) {
                MethodInsnNode call=(MethodInsnNode)instruction;
                if(call.name.equals("beginChampion"))begin++;
                if(call.name.equals("endChampion"))end++;
                if(call.name.equals("ice$original$"+method.name))delegates++;
            }
            if(begin!=1||end!=2||delegates!=1)throw new AssertionError("Scope/delegate multiplicity");
            wrappers++;
        }
        for(MethodNode a:before.methods) {
            String expected=a.name.equals("entityJoin")||a.name.equals("entitySpawn")?"ice$original$"+a.name:a.name;
            MethodNode b=null;for(MethodNode candidate:after.methods)if(candidate.name.equals(expected)&&candidate.desc.equals(a.desc))b=candidate;
            if(b==null||a.instructions.size()!=b.instructions.size())throw new AssertionError("Original event body changed");
            for(int i=0;i<a.instructions.size();i++)if(a.instructions.get(i).getOpcode()!=b.instructions.get(i).getOpcode())throw new AssertionError("Original event opcode changed");
        }
        if(wrappers!=2)throw new AssertionError("Missing event scope");
        verifyScopeRuntime();
        verifyEventWrappers(after);
        System.out.println("champions_inputs=6 event_scopes=2 wrapper_runtime_normal_exception_cases=4 original_formulas_retained=true");
    }
    public static boolean throwInside;
    private static final java.util.Random ORDINARY=new java.util.Random(4);
    public static void scopeBody() {
        if(LiveCombatRandom.championRandom(ORDINARY)!=ORDINARY)throw new AssertionError("Ordinary event inherited fixture stream");
        if(throwInside)throw new IllegalStateException("injected-original-event-failure");
    }
    @SuppressWarnings("unchecked")
    static void verifyEventWrappers(ClassNode transformed)throws Exception {
        // Execute the actual generated wrappers, replacing only the delegated
        // mod body with a deterministic throwing/nonthrowing oracle.
        ClassNode harness=new ClassNode();transformed.accept(harness);
        for(java.util.Iterator<MethodNode> it=harness.methods.iterator();it.hasNext();) {
            MethodNode method=it.next();
            if(method.name.startsWith("ice$original$")) {
                method.instructions.clear();method.tryCatchBlocks.clear();
                if(method.localVariables!=null)method.localVariables.clear();
                method.instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC,"dev/rlcraft/ice/benchmark/GameplayRandomInstrumenterProbe","scopeBody","()V",false));
                method.instructions.add(new InsnNode(Opcodes.RETURN));
            } else if(!method.name.equals("entityJoin")&&!method.name.equals("entitySpawn"))it.remove();
        }
        ClassWriter writer=new ClassWriter(ClassWriter.COMPUTE_FRAMES|ClassWriter.COMPUTE_MAXS);harness.accept(writer);
        final byte[] bytes=writer.toByteArray();final String name=harness.name.replace('/','.');
        Class<?> loaded=new ClassLoader(GameplayRandomInstrumenterProbe.class.getClassLoader()) {
            Class<?> create(){return defineClass(name,bytes,0,bytes.length);}
        }.create();
        java.lang.reflect.Field field=LiveCombatRandom.class.getDeclaredField("CHAMPION_SCOPE");field.setAccessible(true);
        ThreadLocal<java.util.Random> scope=(ThreadLocal<java.util.Random>)field.get(null);
        Object[] events={new net.minecraftforge.event.entity.EntityJoinWorldEvent(null,null),
            new net.minecraftforge.event.entity.living.LivingSpawnEvent.SpecialSpawn(null,null,0,0,0)};
        String[] names={"entityJoin","entitySpawn"};
        for(int i=0;i<events.length;i++)for(boolean failure:new boolean[]{false,true}) {
            java.util.Random outer=new java.util.Random(19);scope.set(outer);throwInside=failure;
            try {
                loaded.getMethod(names[i],events[i].getClass()).invoke(null,events[i]);
                if(failure)throw new AssertionError("Original exception swallowed");
            } catch(java.lang.reflect.InvocationTargetException error) {
                if(!failure||!(error.getCause() instanceof IllegalStateException))throw error;
            }
            if(scope.get()!=outer)throw new AssertionError("Actual wrapper leaked nested scope");
            scope.remove();
        }
    }
    @SuppressWarnings("unchecked")
    static void verifyScopeRuntime()throws Exception {
        java.lang.reflect.Field field=LiveCombatRandom.class.getDeclaredField("CHAMPION_SCOPE");field.setAccessible(true);
        ThreadLocal<java.util.Random> scope=(ThreadLocal<java.util.Random>)field.get(null);
        java.util.Random outer=new java.util.Random(3),original=new java.util.Random(4);scope.set(outer);
        java.util.Random previous=LiveCombatRandom.beginChampion(null);
        if(previous!=outer||LiveCombatRandom.championRandom(original)!=original)throw new AssertionError("Ordinary entity inherited fixture RNG");
        try { throw new IllegalStateException("scope-test"); }
        catch(IllegalStateException expected) { }
        finally { LiveCombatRandom.endChampion(previous); }
        if(LiveCombatRandom.championRandom(original)!=outer)throw new AssertionError("Nested scope was not restored");
        LiveCombatRandom.endChampion(null);
        if(LiveCombatRandom.championRandom(original)!=original)throw new AssertionError("RNG scope leaked");
    }
    static void verify(String name,byte[] source,String expected)throws Exception {
        byte[] result=GameplayRandomInstrumenter.transform(name,source);ClassNode before=node(source),after=node(result);int additions=0;
        if(before.methods.size()!=after.methods.size())throw new AssertionError("Method set changed");
        for(int i=0;i<before.methods.size();i++) {
            MethodNode a=before.methods.get(i),b=after.methods.get(i);
            int hooks=0;
            for(AbstractInsnNode instruction:b.instructions.toArray())if(instruction instanceof MethodInsnNode) {
                MethodInsnNode call=(MethodInsnNode)instruction;
                if(call.owner.equals("dev/rlcraft/ice/benchmark/LiveCombatRandom")) {
                    if(!call.name.equals(expected))throw new AssertionError("Unexpected hook");hooks++;
                }
            }
            if(hooks>0) {
                if(b.instructions.size()!=a.instructions.size()+2)throw new AssertionError("Unexpected instructions replaced");
                additions+=hooks;
            } else if(b.instructions.size()!=a.instructions.size())throw new AssertionError("Other method changed");
        }
        if(additions!=1)throw new AssertionError("Expected exactly one hook");
        System.out.println("verified_hook="+name+" input_bytes="+source.length+" output_bytes="+result.length);
    }
    static ClassNode node(byte[] bytes){ClassNode n=new ClassNode();new ClassReader(bytes).accept(n,0);return n;}
    static byte[] read(InputStream input)throws Exception {
        if(input==null)throw new FileNotFoundException("Missing probe input");
        try(InputStream in=input;ByteArrayOutputStream out=new ByteArrayOutputStream()) {
            byte[] buffer=new byte[8192];int size;while((size=in.read(buffer))!=-1)out.write(buffer,0,size);return out.toByteArray();
        }
    }
}
