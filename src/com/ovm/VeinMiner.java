package com.ovm;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.PriorityQueue;
import java.util.Set;

import net.minecraft.block.Block;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;
import net.minecraft.world.World;
import net.minecraftforge.event.ForgeSubscribe;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;

/**
 * VeinMiner v0.3.0 — server-side chain-mine with priority flood fill.
 *
 * Activation: hold veinmine key (grave ` by default) while breaking a veinminable block.
 * Key state is synced from client via OvmPacketHandler.
 *
 * Algorithm: priority flood fill (PriorityQueue ordered by squared Euclidean distance
 * from origin), producing a sphere-shaped cavity.
 *
 * Supported block types:
 *   Ores: 14,15,16,21,56,73,74,129
 *   Wood (logs): 17
 *   Leaves: 18
 *
 * Obfuscation note: EntityItem and EntityLiving are obfuscated in the runtime jar.
 * All access to these classes uses reflection to avoid NoClassDefFoundError at class-load time.
 */
public class VeinMiner {

    // Veinminable block IDs (Minecraft 1.4.7 vanilla)
    private static final Set<Integer> VEINMINE_IDS = new HashSet<Integer>();
    static {
        VEINMINE_IDS.add(14);   // gold ore
        VEINMINE_IDS.add(15);   // iron ore
        VEINMINE_IDS.add(16);   // coal ore
        VEINMINE_IDS.add(21);   // lapis ore
        VEINMINE_IDS.add(56);   // diamond ore
        VEINMINE_IDS.add(73);   // redstone ore
        VEINMINE_IDS.add(74);   // lit redstone ore
        VEINMINE_IDS.add(129);  // emerald ore
        VEINMINE_IDS.add(17);   // wood (logs)
        VEINMINE_IDS.add(18);   // leaves
    }

    // 26-neighbor offsets (full 3x3x3 minus center)
    private static final int[][] NEIGHBORS = new int[26][3];
    static {
        int idx = 0;
        for (int dx = -1; dx <= 1; dx++)
            for (int dy = -1; dy <= 1; dy++)
                for (int dz = -1; dz <= 1; dz++)
                    if (dx != 0 || dy != 0 || dz != 0)
                        NEIGHBORS[idx++] = new int[]{dx, dy, dz};
    }

    @ForgeSubscribe
    public void onPlayerInteract(PlayerInteractEvent event) {
        if (event.action != PlayerInteractEvent.Action.LEFT_CLICK_BLOCK) return;

        EntityPlayer player = event.entityPlayer;
        World world = player.worldObj;
        if (world.isRemote) return;  // server-side only

        // Check veinmine key is held (state sent from client via packet)
        if (!OvmPacketHandler.isVeinKeyActive(player)) return;

        int ox = event.x, oy = event.y, oz = event.z;
        int originId = world.getBlockId(ox, oy, oz);
        if (!VEINMINE_IDS.contains(originId)) return;

        // Check hunger before starting: need at least 1 hunger point
        int foodLevel = player.getFoodStats().getFoodLevel();
        if (foodLevel < 1) {
            player.addChatMessage("[OVM] Not enough hunger to veinmine.");
            return;
        }

        // Build vein list via priority flood fill (sphere-shaped, closest-first)
        List<int[]> vein = buildVein(world, ox, oy, oz, originId, OvmConfig.maxBlocks);
        if (vein.isEmpty()) return;

        // Enable drop capture via reflection (avoids direct EntityItem reference)
        setCaptureDrops(player, true);
        clearCapturedDrops(player);

        int minedCount = 0;
        try {
            for (int[] pos : vein) {
                int bx = pos[0], by = pos[1], bz = pos[2];
                int blockId = world.getBlockId(bx, by, bz);
                if (blockId != originId) continue;

                Block block = Block.blocksList[blockId];
                if (block == null) continue;
                if (!player.canHarvestBlock(block)) continue;

                int meta = world.getBlockMetadata(bx, by, bz);

                // Mine through full harvest pipeline (applies Fortune, Silk Touch, etc.)
                block.harvestBlock(world, player, bx, by, bz, meta);
                world.setBlockWithNotify(bx, by, bz, 0);
                minedCount++;

                // Damage tool once per extra block (via reflection — avoids EntityLiving ref)
                ItemStack held = player.getHeldItem();
                if (held != null && held.getItem() != null && held.isItemStackDamageable()) {
                    damageItemReflect(held, 1, player);
                    if (held.stackSize <= 0) {
                        player.destroyCurrentEquippedItem();
                        break;
                    }
                }
            }
        } finally {
            setCaptureDrops(player, false);
        }

        // Deliver collected drops to player inventory or drop at feet (all via reflection)
        deliverCapturedDrops(world, player);
        clearCapturedDrops(player);

        // Deduct hunger: 1 point per hungerPerBlocks blocks mined
        if (OvmConfig.hungerPerBlocks > 0 && minedCount > 0) {
            int hungerPoints = minedCount / OvmConfig.hungerPerBlocks;
            if (hungerPoints > 0) {
                player.addExhaustion(4.0f * hungerPoints);
            }
        }

        System.out.println("[OVM] VeinMiner: mined " + minedCount + " blocks at ("
                + ox + "," + oy + "," + oz + ")");
    }

    // -----------------------------------------------------------------------
    // Priority flood fill
    // -----------------------------------------------------------------------

    private List<int[]> buildVein(World world, int ox, int oy, int oz, int targetId, int maxCount) {
        List<int[]> result = new ArrayList<int[]>();

        PriorityQueue<long[]> pq = new PriorityQueue<long[]>(16, new Comparator<long[]>() {
            public int compare(long[] a, long[] b) {
                return Long.compare(a[0], b[0]);
            }
        });
        Set<Long> visited = new HashSet<Long>();

        visited.add(coordKey(ox, oy, oz));
        pq.add(new long[]{0L, ox, oy, oz});

        while (!pq.isEmpty() && result.size() < maxCount) {
            long[] entry = pq.poll();
            int bx = (int) entry[1];
            int by = (int) entry[2];
            int bz = (int) entry[3];

            if (world.getBlockId(bx, by, bz) != targetId) continue;
            result.add(new int[]{bx, by, bz});

            for (int[] d : NEIGHBORS) {
                int nx = bx + d[0];
                int ny = by + d[1];
                int nz = bz + d[2];
                long nKey = coordKey(nx, ny, nz);
                if (!visited.contains(nKey) && world.getBlockId(nx, ny, nz) == targetId) {
                    visited.add(nKey);
                    long ddx = nx - ox, ddy = ny - oy, ddz = nz - oz;
                    pq.add(new long[]{ddx*ddx + ddy*ddy + ddz*ddz, nx, ny, nz});
                }
            }
        }

        return result;
    }

    // -----------------------------------------------------------------------
    // Reflection helpers — all EntityItem / EntityLiving access goes here
    // to avoid NoClassDefFoundError from obfuscated runtime class names
    // -----------------------------------------------------------------------

    private static Field captureDropsField = null;
    private static Field capturedDropsField = null;

    private static void setCaptureDrops(EntityPlayer player, boolean value) {
        try {
            if (captureDropsField == null) {
                captureDropsField = player.getClass().getField("captureDrops");
            }
            captureDropsField.set(player, value);
        } catch (Exception e) { /* field may not exist */ }
    }

    @SuppressWarnings("unchecked")
    private static ArrayList<Object> getCapturedDrops(EntityPlayer player) {
        try {
            if (capturedDropsField == null) {
                capturedDropsField = player.getClass().getField("capturedDrops");
            }
            return (ArrayList<Object>) capturedDropsField.get(player);
        } catch (Exception e) {
            return new ArrayList<Object>();
        }
    }

    private static void clearCapturedDrops(EntityPlayer player) {
        ArrayList<Object> drops = getCapturedDrops(player);
        if (drops != null) drops.clear();
    }

    /**
     * Delivers all captured EntityItem drops to player inventory.
     * EntityItem is accessed entirely via reflection.
     */
    private void deliverCapturedDrops(World world, EntityPlayer player) {
        ArrayList<Object> drops = getCapturedDrops(player);
        if (drops == null || drops.isEmpty()) return;

        for (Object ei : drops) {
            if (ei == null) continue;
            ItemStack stack = getEntityItem(ei);
            if (stack == null || stack.stackSize <= 0) continue;

            if (OvmConfig.dropsToInventory) {
                boolean fitted = player.inventory.addItemStackToInventory(stack);
                if (!fitted || stack.stackSize > 0) {
                    spawnItemReflect(world, player.posX, player.posY, player.posZ, stack);
                }
            } else {
                double px = getPosField(ei, "posX");
                double py = getPosField(ei, "posY");
                double pz = getPosField(ei, "posZ");
                spawnItemReflect(world, px, py, pz, stack);
            }
        }
    }

    /** Gets the ItemStack from an EntityItem via reflection (getEntityItem method). */
    private static Method getEntityItemMethod = null;
    private static ItemStack getEntityItem(Object entityItem) {
        try {
            if (getEntityItemMethod == null) {
                for (Method m : entityItem.getClass().getMethods()) {
                    if (m.getParameterTypes().length == 0
                            && ItemStack.class.isAssignableFrom(m.getReturnType())) {
                        // getEntityItem() returns ItemStack, no args
                        // method name is "getEntityItem" or obfuscated
                        getEntityItemMethod = m;
                        break;
                    }
                }
            }
            if (getEntityItemMethod != null) {
                return (ItemStack) getEntityItemMethod.invoke(entityItem);
            }
        } catch (Exception e) { /* skip */ }
        return null;
    }

    /** Gets a double position field (posX/posY/posZ) from an entity via reflection. */
    private static double getPosField(Object entity, String name) {
        try {
            Field f = entity.getClass().getField(name);
            return f.getDouble(entity);
        } catch (Exception e) {
            return 0.0;
        }
    }

    /**
     * Spawns an EntityItem in the world via reflection.
     * Avoids referencing EntityItem class directly in bytecode.
     */
    private static Class<?> entityItemClass = null;
    private static Constructor<?> entityItemCtor = null;
    private static Method spawnEntityMethod = null;

    private static void spawnItemReflect(World world, double x, double y, double z, ItemStack stack) {
        try {
            if (entityItemClass == null) {
                entityItemClass = Class.forName("net.minecraft.entity.item.EntityItem");
            }
            if (entityItemCtor == null) {
                entityItemCtor = entityItemClass.getConstructor(
                        World.class, double.class, double.class, double.class, ItemStack.class);
            }
            Object ei = entityItemCtor.newInstance(world, x, y, z, stack);
            if (spawnEntityMethod == null) {
                spawnEntityMethod = World.class.getMethod("spawnEntityInWorld",
                        Class.forName("net.minecraft.entity.Entity"));
            }
            spawnEntityMethod.invoke(world, ei);
        } catch (Exception e) {
            // fallback: use player.dropPlayerItem which doesn't need EntityItem directly
        }
    }

    /**
     * Calls ItemStack.damageItem(int, EntityLiving) via reflection.
     * EntityLiving is obfuscated in runtime jar — never reference it directly.
     */
    private static Method damageItemMethod = null;
    private static void damageItemReflect(ItemStack stack, int amount, EntityPlayer player) {
        try {
            if (damageItemMethod == null) {
                for (Method m : ItemStack.class.getMethods()) {
                    if (m.getName().equals("damageItem") && m.getParameterTypes().length == 2
                            && m.getParameterTypes()[0] == int.class) {
                        damageItemMethod = m;
                        break;
                    }
                }
            }
            if (damageItemMethod != null) {
                damageItemMethod.invoke(stack, amount, player);
            }
        } catch (Exception e) {
            // reflection failed — skip tool damage for this block
        }
    }

    private static long coordKey(int x, int y, int z) {
        return ((long)(x + 30000)) * 60001L * 512L
             + ((long)(y + 256))  * 60001L
             + (z + 30000);
    }
}
