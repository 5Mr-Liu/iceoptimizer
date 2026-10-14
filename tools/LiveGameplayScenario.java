package dev.rlcraft.ice.benchmark;

import java.io.PrintWriter;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.monster.EntityZombieVillager;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.init.Blocks;
import net.minecraft.init.Enchantments;
import net.minecraft.init.Items;
import net.minecraft.inventory.EntityEquipmentSlot;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.EnumHand;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.GameType;

/** Test-only arena in the disposable copy; never shipped in ICE. */
final class LiveGameplayScenario {
    final String kind = System.getProperty("ice.bench.scenario", "none");
    final boolean enabled = !"none".equals(kind);
    final boolean combat = "combat".equals(kind);
    volatile boolean ready;
    private volatile boolean measuring;
    private int serverTicks, spawned, attacks, hurtFrames;
    private int zombieHits, zombieDeaths, playerHits;
    private int externalPlayerHits, relocatedEntities;
    private final java.util.Map<String, Integer> damageSources = new java.util.TreeMap<String, Integer>();
    private float zombieDamage, playerDamage;
    private float minimumHealth = Float.MAX_VALUE;
    private double distance, lastX, lastZ;
    private int waypoint;
    private String startGear = "not-started", startEffects = "not-started";
    private final java.util.Map<String, Integer> measuredEffects = new java.util.TreeMap<String, Integer>();
    private final java.util.List<EntityZombieVillager> opponents = new java.util.ArrayList<EntityZombieVillager>();
    private final java.util.List<String> opponentProfiles = new java.util.ArrayList<String>();
    private static final long SPAWN_SEED = 0x1CE2900L;
    private static final String[] ATTRIBUTES = {"generic.movementSpeed", "generic.attackDamage",
        "generic.attackSpeed", "generic.armor", "generic.armorToughness", "generic.maxHealth"};
    private final double[] initialAttributes = new double[ATTRIBUTES.length];
    private final double[] minimumAttributes = new double[ATTRIBUTES.length];
    private final double[] maximumAttributes = new double[ATTRIBUTES.length];
    private static final double X = 513.5, Y = 80, Z = 411.5;
    private static final double[][] ROUTE = {{5, 5}, {-5, 5}, {-5, -5}, {5, -5}};

    LiveGameplayScenario() {
        if (enabled && !"survival".equals(kind) && !combat)
            throw new IllegalArgumentException("Unknown gameplay scenario: " + kind);
    }

    void server(EntityPlayerMP player) {
        if (!enabled) return;
        if (!ready) {
            // Identical deterministic setup in each fresh clone, before warmup.
            // Ordinary AI, damage, physics, difficulty and spawn rules stay enabled.
            for (int dx = -15; dx <= 15; dx++) for (int dz = -15; dz <= 15; dz++) {
                BlockPos base = new BlockPos(X + dx, Y - 1, Z + dz);
                player.world.setBlockState(base, Blocks.STONEBRICK.getDefaultState(), 2);
                for (int dy = 1; dy <= 6; dy++)
                    player.world.setBlockState(base.up(dy), Blocks.AIR.getDefaultState(), 2);
                if (Math.abs(dx) == 15 || Math.abs(dz) == 15)
                    player.world.setBlockState(base.up(), Blocks.COBBLESTONE_WALL.getDefaultState(), 2);
                if (combat) {
                    // A normal enclosed glass arena excludes unrelated entrants;
                    // wave AI, collisions, infection, attacks and damage stay live.
                    player.world.setBlockState(base.up(6), Blocks.GLASS.getDefaultState(), 2);
                    if (Math.abs(dx) == 15 || Math.abs(dz) == 15)
                        for (int dy = 1; dy < 6; dy++)
                            player.world.setBlockState(base.up(dy), Blocks.GLASS.getDefaultState(), 2);
                }
            }
            if (combat) for (EntityLivingBase occupant : player.world.getEntitiesWithinAABB(EntityLivingBase.class,
                new net.minecraft.util.math.AxisAlignedBB(X-15,Y,Z-15,X+16,Y+6,Z+16))) {
                if (occupant instanceof net.minecraft.entity.player.EntityPlayer) continue;
                // One setup relocation only, retaining all naturally present mobs.
                occupant.setPositionAndUpdate(X+18+(relocatedEntities%4),Y,Z+18+(relocatedEntities/4));
                relocatedEntities++;
            }
            player.world.setWorldTime(6000L);
            // Randomness is an explicit input of this disposable scenario.
            // Keep all ordinary mod spawn events, with the same world RNG input.
            player.world.rand.setSeed(SPAWN_SEED);
            player.world.getWorldInfo().setRaining(false);
            player.world.getWorldInfo().setThundering(false);
            player.world.getWorldInfo().setCleanWeatherTime(12000);
            player.clearActivePotions();
            player.setGameType(GameType.SURVIVAL);
            player.capabilities.isFlying = false;
            player.capabilities.disableDamage = false;
            player.sendPlayerAbilities();
            player.inventory.clear();
            ItemStack sword = normalQuality(new ItemStack(Items.DIAMOND_SWORD));
            sword.addEnchantment(Enchantments.SHARPNESS, 5);
            sword.addEnchantment(Enchantments.UNBREAKING, 3);
            player.inventory.mainInventory.set(0, sword);
            player.inventory.mainInventory.set(1, new ItemStack(Items.COOKED_BEEF, 64));
            if (combat) player.setItemStackToSlot(EntityEquipmentSlot.OFFHAND, normalQuality(new ItemStack(Items.SHIELD)));
            player.inventory.currentItem = 0;
            player.connection.sendPacket(new net.minecraft.network.play.server.SPacketHeldItemChange(0));
            equip(player, EntityEquipmentSlot.HEAD, new ItemStack(Items.DIAMOND_HELMET));
            equip(player, EntityEquipmentSlot.CHEST, new ItemStack(Items.DIAMOND_CHESTPLATE));
            equip(player, EntityEquipmentSlot.LEGS, new ItemStack(Items.DIAMOND_LEGGINGS));
            equip(player, EntityEquipmentSlot.FEET, new ItemStack(Items.DIAMOND_BOOTS));
            player.setHealth(player.getMaxHealth());
            player.getFoodStats().setFoodLevel(20);
            player.getFoodStats().setFoodSaturationLevel(5);
            if (combat) {
                // A fixed, ordinary preparation potion for both arms. Invoke
                // vanilla consumption once before warmup; never heal or apply
                // effects during the measured fight.
                net.minecraft.potion.PotionUtils.addPotionToItemStack(
                    new ItemStack(Items.POTIONITEM), net.minecraft.init.PotionTypes.LONG_FIRE_RESISTANCE)
                    .onItemUseFinish(player.world, player);
            }
            player.motionX = player.motionY = player.motionZ = 0;
            player.fallDistance = 0;
            player.connection.setPlayerLocation(X, Y, Z, 180, 8);
            player.inventoryContainer.detectAndSendChanges();
            System.out.println("ICE_BENCH scenario_setup=" + kind + " origin=" + X + "," + Y + "," + Z
                + " gear=diamond_protection4_sharpness5 time=6000 clear_weather=true normal_ai_damage=true");
            ready = true;
        }
        if (!measuring) return;
        if (combat && serverTicks < 1200 && serverTicks % 200 == 0) {
            int wave = serverTicks / 200;
            int size = 2;
            for (int i = 0; i < size; i++) {
                double angle = wave * 1.7 + (i - .5) * .55;
                player.world.rand.setSeed(SPAWN_SEED + spawned);
                EntityZombieVillager opponent;
                LiveCombatRandom.begin(SPAWN_SEED + spawned);
                try { opponent = new EntityZombieVillager(player.world); }
                finally { LiveCombatRandom.end(); }
                opponent.getRNG().setSeed(SPAWN_SEED + spawned);
                opponent.setUniqueId(new java.util.UUID(0x1CE2900L, spawned + 1));
                opponent.setPosition(X + Math.sin(angle) * 9, Y, Z + Math.cos(angle) * 9);
                opponent.setItemStackToSlot(EntityEquipmentSlot.HEAD, normalQuality(new ItemStack(Items.IRON_HELMET)));
                opponent.setAttackTarget(player);
                opponent.enablePersistence();
                opponent.addTag("ice_benchmark_combat");
                if (player.world.spawnEntity(opponent)) {
                    opponents.add(opponent); opponentProfiles.add(opponentProfile(opponent)); spawned++;
                }
            }
        }
        if (combat && serverTicks % 200 == 20) {
            for (int i=Math.max(0,opponents.size()-2);i<opponents.size();i++)
                opponentProfiles.set(i,opponentProfile(opponents.get(i)));
        }
        serverTicks++;
    }

    private static String opponentProfile(EntityLivingBase opponent) {
        StringBuilder value = new StringBuilder();
        java.util.Map<String,Double> attributes = new java.util.TreeMap<String,Double>();
        for (net.minecraft.entity.ai.attributes.IAttributeInstance attribute : opponent.getAttributeMap().getAllAttributes())
            attributes.put(attribute.getAttribute().getName(),attribute.getAttributeValue());
        value.append(attributes).append(";effects=").append(effects(opponent));
        value.append(";infernal=").append(opponent.getEntityData().getString("InfernalMobsMod"));
        NBTTagCompound snapshot = new NBTTagCompound(); opponent.writeToNBT(snapshot);
        value.append(";capabilities=").append(snapshot.getCompoundTag("ForgeCaps"));
        return value.toString();
    }

    private static void equip(EntityPlayerMP player, EntityEquipmentSlot slot, ItemStack armor) {
        normalQuality(armor);
        armor.addEnchantment(Enchantments.PROTECTION, 4);
        armor.addEnchantment(Enchantments.UNBREAKING, 3);
        player.setItemStackToSlot(slot, armor);
    }

    private static ItemStack normalQuality(ItemStack item) {
        // Quality Tools represents its ordinary, modifier-free roll as an empty
        // Quality compound. Choose that initial equipment for both arms instead
        // of rolling unrelated movement/attack/armor modifiers during warmup.
        if (!item.hasTagCompound()) item.setTagCompound(new NBTTagCompound());
        item.getTagCompound().setTag("Quality", new NBTTagCompound());
        return item;
    }

    private static String effects(EntityLivingBase player) {
        java.util.List<String> values = new java.util.ArrayList<String>();
        for (net.minecraft.potion.PotionEffect effect : player.getActivePotionEffects())
            values.add(effect.getPotion().getRegistryName() + ":" + effect.getAmplifier());
        java.util.Collections.sort(values);
        return values.toString();
    }

    void begin(Minecraft mc) {
        if (mc.player.getHeldItemMainhand().getItem() != Items.DIAMOND_SWORD)
            throw new IllegalStateException("Scenario did not synchronize the equipped sword");
        StringBuilder gear = new StringBuilder();
        for (EntityEquipmentSlot slot : EntityEquipmentSlot.values()) {
            ItemStack item = mc.player.getItemStackFromSlot(slot);
            gear.append(slot.getName()).append(':').append(item.getItem().getRegistryName())
                .append(':').append(item.getEnchantmentTagList()).append(':')
                .append(item.hasTagCompound() ? item.getTagCompound().getCompoundTag("Quality") : "{}").append(';');
        }
        startGear = gear.toString();
        startEffects = effects(mc.player);
        for (int i=0;i<ATTRIBUTES.length;i++) initialAttributes[i] = minimumAttributes[i] = maximumAttributes[i] =
            mc.player.getAttributeMap().getAttributeInstanceByName(ATTRIBUTES[i]).getAttributeValue();
        measuring = true;
        lastX = mc.player.posX;
        lastZ = mc.player.posZ;
    }

    void client(Minecraft mc) {
        if (!ready || mc.player == null || !measuring) return;
        for (int i=0;i<ATTRIBUTES.length;i++) {
            double value = mc.player.getAttributeMap().getAttributeInstanceByName(ATTRIBUTES[i]).getAttributeValue();
            minimumAttributes[i] = Math.min(minimumAttributes[i], value);
            maximumAttributes[i] = Math.max(maximumAttributes[i], value);
        }
        for (net.minecraft.potion.PotionEffect effect : mc.player.getActivePotionEffects()) {
            String key = effect.getPotion().getRegistryName() + ":" + effect.getAmplifier();
            Integer count = measuredEffects.get(key); measuredEffects.put(key, count == null ? 1 : count + 1);
        }
        double dx = mc.player.posX - lastX, dz = mc.player.posZ - lastZ;
        distance += Math.sqrt(dx * dx + dz * dz);
        lastX = mc.player.posX; lastZ = mc.player.posZ;
        minimumHealth = Math.min(minimumHealth, mc.player.getHealth());
        if (mc.player.hurtTime > 0) hurtFrames++;
        if (!combat) {
            double[] target = ROUTE[waypoint];
            dx = X + target[0] - mc.player.posX;
            dz = Z + target[1] - mc.player.posZ;
            if (dx * dx + dz * dz < 1) waypoint = (waypoint + 1) % ROUTE.length;
            look(mc, dx, 0, dz);
            mc.player.rotationPitch = 8;
            return;
        }
        EntityLivingBase closest = null;
        double best = 400;
        for (Entity entity : mc.world.loadedEntityList) {
            if (!(entity instanceof EntityLivingBase) || !combatOpponent(entity) || entity.isDead
                || ((EntityLivingBase) entity).getHealth() <= 0) continue;
            double d = entity.getDistanceSq(mc.player);
            if (d < best) { best = d; closest = (EntityLivingBase) entity; }
        }
        if (closest == null) return;
        dx = closest.posX - mc.player.posX; dz = closest.posZ - mc.player.posZ;
        look(mc, dx, closest.posY + closest.getEyeHeight() - mc.player.posY - mc.player.getEyeHeight(), dz);
        // Normal controller attack packet, reach, cooldown, line of sight and damage.
        if (best <= 8 && mc.player.canEntityBeSeen(closest)
            && mc.player.getCooledAttackStrength(0) >= .99F) {
            if (mc.player.isHandActive()) mc.playerController.onStoppedUsingItem(mc.player);
            mc.playerController.attackEntity(mc.player, closest);
            mc.player.swingArm(EnumHand.MAIN_HAND);
            attacks++;
        } else if (!mc.player.isHandActive()) {
            mc.playerController.processRightClick(mc.player, mc.world, EnumHand.OFF_HAND);
        }
    }

    private static boolean combatOpponent(Entity entity) {
        // Ordinary mod death-spawn callbacks may create a Geist. The test
        // player must fight it through the same controller, not ignore it or
        // suppress the callback/damage to manufacture a completed run.
        return entity instanceof net.minecraft.entity.monster.IMob
            || entity.getClass().getName().equals("com.lycanitesmobs.core.entity.creature.EntityGeist");
    }

    private static void look(Minecraft mc, double dx, double dy, double dz) {
        mc.player.prevRotationYaw = mc.player.rotationYaw;
        mc.player.prevRotationPitch = mc.player.rotationPitch;
        mc.player.rotationYaw = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90);
        mc.player.rotationPitch = (float) -Math.toDegrees(Math.atan2(dy, Math.sqrt(dx * dx + dz * dz)));
    }

    float forward() { return measuring && !combat ? .6F : 0; }
    String name() { return combat ? "survival_combat" : "survival_local_walk"; }
    void hurt(net.minecraftforge.event.entity.living.LivingHurtEvent event) {
        if (!measuring || event.getEntityLiving().world.isRemote) return;
        if (event.getEntityLiving().getTags().contains("ice_benchmark_combat")) {
            zombieHits++; zombieDamage += event.getAmount();
        } else if (event.getEntityLiving() instanceof EntityPlayerMP) {
            playerHits++; playerDamage += event.getAmount();
            Entity source = event.getSource().getTrueSource();
            String key = source == null ? event.getSource().getDamageType() : source.getClass().getName();
            Integer old = damageSources.get(key); damageSources.put(key, old == null ? 1 : old + 1);
            if (source == null || !source.getTags().contains("ice_benchmark_combat")) externalPlayerHits++;
        }
    }
    void died(net.minecraftforge.event.entity.living.LivingDeathEvent event) {
        if (measuring && !event.getEntityLiving().world.isRemote
            && event.getEntityLiving().getTags().contains("ice_benchmark_combat")) zombieDeaths++;
    }
    boolean validActivity() {
        return combat ? attacks >= 10 && spawned >= 6 && zombieHits >= 8 && zombieDeaths >= 1 : distance >= 20;
    }
    void summary(PrintWriter out) {
        out.println("gameplay_scenario=" + kind);
        out.println("gameplay_fixture_version=12");
        out.println("gameplay_deterministic_combat="+LiveCombatRandom.ENABLED);
        out.println("gameplay_preparation_potion=" + (combat ? "minecraft:long_fire_resistance" : "none"));
        out.println("gameplay_combat_target_policy=hostile_mobs_and_geist");
        out.println("gameplay_equipment_quality=normal");
        out.println("gameplay_spawn_random_seed_base="+SPAWN_SEED);
        for (int i=0;i<opponentProfiles.size();i++) out.println("gameplay_opponent_profile."+i+"="+opponentProfiles.get(i));
        out.println("gameplay_start_gear=" + startGear);
        out.println("gameplay_start_effects=" + startEffects);
        for (int i=0;i<ATTRIBUTES.length;i++) {
            out.println("gameplay_initial_attribute."+ATTRIBUTES[i]+"="+initialAttributes[i]);
            out.println("gameplay_min_attribute."+ATTRIBUTES[i]+"="+minimumAttributes[i]);
            out.println("gameplay_max_attribute."+ATTRIBUTES[i]+"="+maximumAttributes[i]);
        }
        for (java.util.Map.Entry<String,Integer> effect : measuredEffects.entrySet())
            out.println("gameplay_effect_ticks."+effect.getKey()+"="+effect.getValue());
        out.println("gameplay_combat_shield=" + combat);
        out.println("gameplay_opponent_type=" + (combat ? "minecraft:zombie_villager" : "none"));
        out.println("gameplay_enclosed_combat=" + combat);
        out.println("gameplay_setup_relocated_entities=" + relocatedEntities);
        out.println("gameplay_server_ticks=" + serverTicks);
        out.println("gameplay_spawned=" + spawned);
        out.println("gameplay_attacks=" + attacks);
        out.println("gameplay_hurt_ticks=" + hurtFrames);
        out.println("gameplay_zombie_hits=" + zombieHits);
        out.println("gameplay_zombie_deaths=" + zombieDeaths);
        out.println("gameplay_zombie_damage=" + zombieDamage);
        out.println("gameplay_player_hits=" + playerHits);
        out.println("gameplay_player_damage=" + playerDamage);
        out.println("gameplay_external_player_hits=" + externalPlayerHits);
        for (java.util.Map.Entry<String, Integer> source : damageSources.entrySet())
            out.println("gameplay_damage_source." + source.getKey() + "=" + source.getValue());
        out.println("gameplay_min_health=" + minimumHealth);
        out.println("gameplay_walk_distance=" + distance);
        out.println("gameplay_valid_activity=" + validActivity());
    }
}
