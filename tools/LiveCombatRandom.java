package dev.rlcraft.ice.benchmark;

import java.util.Random;
import java.util.Map;
import java.util.WeakHashMap;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.Entity;

/** Disposable benchmark input control. Ordinary mobs keep their original RNG. */
public final class LiveCombatRandom {
    public static final boolean ENABLED=Boolean.getBoolean("ice.bench.deterministicCombat");
    private static final ThreadLocal<Long> CONSTRUCTION=new ThreadLocal<Long>();
    private static final Map<EntityLivingBase,Random> HEALTH=new WeakHashMap<EntityLivingBase,Random>();
    private static final Map<EntityLivingBase,Random> DEATH=new WeakHashMap<EntityLivingBase,Random>();
    private static final Map<EntityLivingBase,Random> CHAMPIONS=new WeakHashMap<EntityLivingBase,Random>();
    private static final ThreadLocal<Random> CHAMPION_SCOPE=new ThreadLocal<Random>();
    private LiveCombatRandom() { }
    static void begin(long seed) {
        if(!ENABLED)return;
        try {
            Class.forName("net.minecraft.entity.monster.EntityZombie",true,LiveCombatRandom.class.getClassLoader());
            Class.forName("net.silentchaos512.scalinghealth.event.DifficultyHandler",true,LiveCombatRandom.class.getClassLoader());
            Class.forName("com.lycanitesmobs.core.spawner.trigger.KillSpawnTrigger",true,LiveCombatRandom.class.getClassLoader());
            Class.forName("c4.champions.common.util.ChampionHelper",true,LiveCombatRandom.class.getClassLoader());
            Class.forName("c4.champions.common.capability.CapabilityChampionship$EventHandler",true,LiveCombatRandom.class.getClassLoader());
        } catch(ClassNotFoundException missing){throw new IllegalStateException("Combat fixture dependency missing",missing);}
        if(Boolean.getBoolean("ice.bench.randomHook.failed")||!Boolean.getBoolean("ice.bench.randomHook.zombie")||!Boolean.getBoolean("ice.bench.randomHook.health")||!Boolean.getBoolean("ice.bench.randomHook.kill")
            ||!Boolean.getBoolean("ice.bench.randomHook.championHelper")||!Boolean.getBoolean("ice.bench.randomHook.championEvents"))
            throw new IllegalStateException("Deterministic combat hooks not installed");
        if(CONSTRUCTION.get()!=null)throw new IllegalStateException("Nested opponent construction");
        CONSTRUCTION.set(seed);
    }
    static void end(){CONSTRUCTION.remove();}
    public static void beforeAttributes(EntityLivingBase entity) {
        Long seed=CONSTRUCTION.get();
        if(seed!=null)entity.getRNG().setSeed(seed);
    }
    public static Random healthRandom(Random original,EntityLivingBase entity) {
        if(!ENABLED||entity.world.isRemote||!entity.getTags().contains("ice_benchmark_combat"))return original;
        Random random=HEALTH.get(entity);
        if(random==null){random=new Random(0x1CE2900L+entity.getUniqueID().getLeastSignificantBits()-1);HEALTH.put(entity,random);}
        return random;
    }
    public static Random killRandom(Random original,EntityLivingBase victim) {
        if(!ENABLED||victim.world.isRemote||!victim.getTags().contains("ice_benchmark_combat"))return original;
        Random random=DEATH.get(victim);
        if(random==null) {
            long seed=victim.getUniqueID().getLeastSignificantBits()+0x1CE2900L+0x9E3779B97F4A7C15L;
            seed=(seed^(seed>>>30))*0xBF58476D1CE4E5B9L;
            seed=(seed^(seed>>>27))*0x94D049BB133111EBL;
            random=new Random(seed^(seed>>>31));DEATH.put(victim,random);
        }
        return random;
    }
    public static Random beginChampion(Entity entity) {
        Random previous=CHAMPION_SCOPE.get();
        if(!ENABLED||!(entity instanceof EntityLivingBase)||entity.world.isRemote
            ||!entity.getTags().contains("ice_benchmark_combat")) {
            CHAMPION_SCOPE.remove();return previous;
        }
        EntityLivingBase living=(EntityLivingBase)entity;
        Random random=CHAMPIONS.get(living);
        if(random==null) {
            long seed=living.getUniqueID().getLeastSignificantBits()+0x4348414D50494F4EL;
            seed=(seed^(seed>>>30))*0xBF58476D1CE4E5B9L;
            seed=(seed^(seed>>>27))*0x94D049BB133111EBL;
            random=new Random(seed^(seed>>>31));CHAMPIONS.put(living,random);
        }
        CHAMPION_SCOPE.set(random);return previous;
    }
    public static void endChampion(Random previous) {
        if(previous==null)CHAMPION_SCOPE.remove();else CHAMPION_SCOPE.set(previous);
    }
    public static Random championRandom(Random original) {
        Random selected=CHAMPION_SCOPE.get();return selected==null?original:selected;
    }
}
