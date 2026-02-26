package com.ovm;

import cpw.mods.fml.common.network.Player;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Random;
import java.util.Set;

/**
 * Server-side chain-mine with priority flood fill.
 * Called from OvmPacketHandler when the client sends a veinmine request.
 * All Minecraft types accessed via reflection (FML does not remap mod code).
 */
public class VeinMiner {

    // 26-neighbor offsets (full 3x3x3 minus center)
    private static final int[][] NEIGHBORS = new int[26][3];
    static {
        int idx = 0;
        for (int dx = -1; dx <= 1; dx++)
            for (int dy = -1; dy <= 1; dy++)
                for (int dz = -1; dz <= 1; dz++)
                    if (dx != 0 || dy != 0 || dz != 0)
                        NEIGHBORS[idx++] = new int[]{ dx, dy, dz };
    }

    private static final Random RAND = new Random();

    // Block id pairs treated as the same vein (e.g. redstone ore 73 ↔ lit 74).
    private static final int[][] EQUIVALENT_IDS = { { 73, 74 } };

    private static int canonicalId(int id) {
        for (int[] pair : EQUIVALENT_IDS)
            if (id == pair[1]) return pair[0];
        return id;
    }

    private static boolean matchesTarget(int blockId, int targetId) {
        if (blockId == targetId) return true;
        for (int[] pair : EQUIVALENT_IDS)
            if (pair[0] == targetId && blockId == pair[1]) return true;
        return false;
    }

    // -----------------------------------------------------------------------
    // Entry point
    // -----------------------------------------------------------------------

    public static void veinmine(Player playerArg, int ox, int oy, int oz, int hintBlockId) {
        try {
            Object player = playerArg;
            Object world = Reflect.getField(player, Object.class, "worldObj", "p");
            if (world == null || Reflect.getField(world, boolean.class, "isRemote", "I")) return;

            int originId = canonicalId(McAccessor.getBlockId(world, ox, oy, oz));
            if (originId == 0) originId = canonicalId(hintBlockId);
            if (originId == 0) return;

            Object foodStats = Reflect.invokeNoArg(player, Object.class, "getFoodStats", "cc");
            if (foodStats == null) return;
            if (Reflect.invokeNoArg(foodStats, int.class, "getFoodLevel", "a") < 1) {
                Reflect.invokeWithString(player, "[OVM] Not enough food to veinmine.", "addChatMessage");
                return;
            }

            Object originBlock = McAccessor.getBlock(originId);
            if (originBlock != null && !invokeCanHarvest(player, originBlock)) {
                System.out.println("[OVM] veinmine: canHarvest=false for originId=" + originId + ", abort");
                return;
            }

            List<int[]> vein = buildVein(world, ox, oy, oz, originId, OvmConfig.maxBlocks);
            System.out.println("[OVM] veinmine: originId=" + originId + " vein=" + vein.size());
            if (vein.isEmpty()) return;

            LinkedHashMap<Integer, int[]> drops = new LinkedHashMap<Integer, int[]>();
            int minedCount = 0;

            // Account for the origin block (already broken by vanilla).
            // Collect its drops and remove the vanilla EntityItem spawned at the break site.
            if (originBlock != null) {
                int originMeta = McAccessor.getBlockMeta(world, ox, oy, oz);
                collectBlockDrops(originBlock, originMeta, drops);
                removeDroppedItems(world, ox, oy, oz);
                minedCount++;
            }

            for (int[] pos : vein) {
                int bx = pos[0], by = pos[1], bz = pos[2];
                int actualId = McAccessor.getBlockId(world, bx, by, bz);
                if (!matchesTarget(actualId, originId)) continue;

                Object block = McAccessor.getBlock(actualId);
                if (block == null || !invokeCanHarvest(player, block)) continue;

                int meta = McAccessor.getBlockMeta(world, bx, by, bz);
                boolean harvested = invokeHarvestBlock(block, world, player, bx, by, bz, meta);
                if (!harvested) collectBlockDrops(block, meta, drops);
                McAccessor.setBlock(world, bx, by, bz, 0);
                minedCount++;

                Object held = McAccessor.getHeldItem(player);
                if (held != null && isItemStackDamageable(held)) {
                    damageItem(held, 1, player);
                    if (McAccessor.getStackSize(held) <= 0) {
                        System.out.println("[OVM] tool broke at block " + minedCount);
                        break;
                    }
                }
            }

            deliverDrops(world, player, drops);

            if (OvmConfig.hungerPerBlocks > 0 && minedCount > 0) {
                int pts = minedCount / OvmConfig.hungerPerBlocks;
                if (pts > 0) Reflect.invokeWithFloat(foodStats, 4.0f * pts, "addExhaustion", "a");
            }

            System.out.println("[OVM] Veinmined " + minedCount + " blocks at (" + ox + "," + oy + "," + oz + ")");
        } catch (Exception e) {
            System.out.println("[OVM] VeinMiner error: " + e);
            e.printStackTrace();
        }
    }

    // -----------------------------------------------------------------------
    // Priority flood fill
    // -----------------------------------------------------------------------

    private static final class PQEntry {
        final long dist;
        final int x, y, z;
        PQEntry(long dist, int x, int y, int z) { this.dist = dist; this.x = x; this.y = y; this.z = z; }
    }

    private static List<int[]> buildVein(Object world, int ox, int oy, int oz, int targetId, int maxCount) {
        List<int[]> result = new ArrayList<int[]>();
        PriorityQueue<PQEntry> pq = new PriorityQueue<PQEntry>(16, new Comparator<PQEntry>() {
            public int compare(PQEntry a, PQEntry b) { return Long.compare(a.dist, b.dist); }
        });
        Set<Long> visited = new HashSet<Long>();

        visited.add(coordKey(ox, oy, oz));
        pq.add(new PQEntry(0L, ox, oy, oz));

        while (!pq.isEmpty() && result.size() < maxCount) {
            PQEntry e = pq.poll();
            int bx = e.x, by = e.y, bz = e.z;
            int bid = McAccessor.getBlockId(world, bx, by, bz);
            boolean isOrigin = (bx == ox && by == oy && bz == oz);
            if (!matchesTarget(bid, targetId) && !isOrigin) continue;
            if (matchesTarget(bid, targetId)) result.add(new int[]{ bx, by, bz });
            for (int[] d : NEIGHBORS) {
                int nx = bx + d[0], ny = by + d[1], nz = bz + d[2];
                long nk = coordKey(nx, ny, nz);
                if (!visited.contains(nk)) {
                    visited.add(nk);
                    if (matchesTarget(McAccessor.getBlockId(world, nx, ny, nz), targetId)) {
                        long ddx = nx - ox, ddy = ny - oy, ddz = nz - oz;
                        pq.add(new PQEntry(ddx * ddx + ddy * ddy + ddz * ddz, nx, ny, nz));
                    }
                }
            }
        }
        return result;
    }

    // -----------------------------------------------------------------------
    // Block-specific method resolution (specialized signatures, stays here)
    // -----------------------------------------------------------------------

    // harvestBlock(World, EntityPlayer, int, int, int, int) → void
    private static final HashMap<String, Method> harvestBlockCache = new HashMap<String, Method>();

    private static boolean invokeHarvestBlock(Object block, Object world, Object player, int x, int y, int z, int meta) {
        String cls = block.getClass().getName();
        Method m = harvestBlockCache.get(cls);
        if (m == null) {
            for (Method mtd : block.getClass().getMethods()) {
                Class<?>[] p = mtd.getParameterTypes();
                if (p.length == 6 && !p[0].isPrimitive() && !p[1].isPrimitive()
                        && p[2] == int.class && p[3] == int.class && p[4] == int.class && p[5] == int.class
                        && mtd.getReturnType() == void.class
                        && (mtd.getName().equals("harvestBlock") || mtd.getName().equals("b"))) {
                    m = mtd;
                    break;
                }
            }
            harvestBlockCache.put(cls, m);
        }
        if (m == null) return false;
        try {
            m.invoke(block, world, player, x, y, z, meta);
            return true;
        } catch (Exception e) {
            System.out.println("[OVM] invokeHarvestBlock error: " + e);
            return false;
        }
    }

    // Per-class cache: [0]=idDropped(int,Random,int), [1]=quantityDropped(Random), [2]=damageDropped(int)
    private static final HashMap<String, Method[]> blockDropCache = new HashMap<String, Method[]>();

    private static void collectBlockDrops(Object block, int meta, LinkedHashMap<Integer, int[]> merged) {
        try {
            String cls = block.getClass().getName();
            Method[] m = blockDropCache.get(cls);
            if (m == null) {
                m = new Method[3];
                for (Method mtd : block.getClass().getMethods()) {
                    if (mtd.getReturnType() != int.class) continue;
                    Class<?>[] p = mtd.getParameterTypes();
                    if (p.length == 3 && p[0] == int.class && p[1] == Random.class && p[2] == int.class)
                        m[0] = mtd;
                    else if (p.length == 1 && p[0] == Random.class)
                        m[1] = mtd;
                    else if (p.length == 1 && p[0] == int.class
                            && (mtd.getName().equals("damageDropped") || mtd.getName().equals("b")))
                        m[2] = mtd;
                }
                blockDropCache.put(cls, m);
            }

            int dropId    = m[0] != null ? (Integer) m[0].invoke(block, meta, RAND, 0) : 0;
            if (dropId == 0) return;
            int dropCount = m[1] != null ? (Integer) m[1].invoke(block, RAND) : 1;
            if (dropCount <= 0) return;
            int dropDamage = m[2] != null ? (Integer) m[2].invoke(block, meta) : 0;

            int key = dropId * 65536 + dropDamage;
            int[] acc = merged.get(key);
            if (acc == null) merged.put(key, new int[]{ dropId, dropDamage, dropCount });
            else             acc[2] += dropCount;
        } catch (Exception e) {
            System.out.println("[OVM] collectBlockDrops error: " + e);
        }
    }

    private static Method canHarvestMethod;

    private static boolean invokeCanHarvest(Object player, Object block) {
        try {
            if (canHarvestMethod == null) {
                for (Method m : player.getClass().getMethods()) {
                    if (m.getParameterTypes().length == 1
                            && (m.getName().equals("canHarvestBlock") || m.getName().length() == 1)
                            && m.getReturnType() == boolean.class) {
                        try { m.invoke(player, block); canHarvestMethod = m; break; }
                        catch (Exception ignored) {}
                    }
                }
            }
            return canHarvestMethod != null ? (Boolean) canHarvestMethod.invoke(player, block) : true;
        } catch (Exception ignored) { return true; }
    }

    private static Method damageItemMethod;

    private static void damageItem(Object stack, int amount, Object player) {
        try {
            if (damageItemMethod == null) {
                for (Method m : stack.getClass().getMethods()) {
                    Class<?>[] p = m.getParameterTypes();
                    if (p.length == 2 && p[0] == int.class && !p[1].isPrimitive()
                            && (m.getName().equals("damageItem") || m.getName().equals("a"))
                            && m.getReturnType() == void.class
                            && p[1].isAssignableFrom(player.getClass())) {
                        damageItemMethod = m; break;
                    }
                }
                if (damageItemMethod == null) {
                    for (Method m : stack.getClass().getMethods()) {
                        Class<?>[] p = m.getParameterTypes();
                        if (p.length == 2 && p[0] == int.class && !p[1].isPrimitive()) {
                            damageItemMethod = m; break;
                        }
                    }
                }
            }
            if (damageItemMethod != null) damageItemMethod.invoke(stack, amount, player);
        } catch (Exception e) {
            System.out.println("[OVM] damageItem error: " + e);
        }
    }

    private static boolean isItemStackDamageable(Object stack) {
        return Reflect.invokeNoArg(stack, boolean.class, "isItemStackDamageable", "f", "q");
    }

    // -----------------------------------------------------------------------
    // Drop delivery
    // -----------------------------------------------------------------------

    private static void deliverDrops(Object world, Object player, LinkedHashMap<Integer, int[]> drops) {
        if (drops.isEmpty()) return;
        for (Map.Entry<Integer, int[]> entry : drops.entrySet()) {
            int[] acc = entry.getValue();
            int remaining = acc[2];
            while (remaining > 0) {
                int batch = Math.min(remaining, 64);
                remaining -= batch;
                Object stack = McAccessor.makeItemStack(acc[0], acc[1], batch);
                if (stack == null) continue;
                if (OvmConfig.dropsToInventory) {
                    int leftover = McAccessor.addToInventory(player, stack);
                    if (leftover > 0) {
                        Object overflow = McAccessor.makeItemStack(acc[0], acc[1], leftover);
                        if (overflow != null) McAccessor.spawnItem(world, player, overflow);
                    }
                } else {
                    McAccessor.spawnItem(world, player, stack);
                }
            }
        }
    }

    // -----------------------------------------------------------------------
    // Vanilla drop suppression
    // -----------------------------------------------------------------------

    private static Method getEntitiesMethod;

    /** Remove EntityItems within 2 blocks of the given position (vanilla drops from origin block). */
    @SuppressWarnings("unchecked")
    private static void removeDroppedItems(Object world, int x, int y, int z) {
        try {
            // EntityItem class
            Class<?> eiClass = null;
            for (String cname : new String[]{ "net.minecraft.entity.item.EntityItem", "px" }) {
                try { eiClass = Class.forName(cname); break; }
                catch (Exception ignored) {}
            }
            if (eiClass == null) return;

            // AxisAlignedBB.getBoundingBox(minX, minY, minZ, maxX, maxY, maxZ)
            Class<?> aabbClass = null;
            for (String cname : new String[]{ "net.minecraft.util.AxisAlignedBB", "aoe" }) {
                try { aabbClass = Class.forName(cname); break; }
                catch (Exception ignored) {}
            }
            if (aabbClass == null) return;

            // Static factory: AxisAlignedBB.getBoundingBox / obf "a" (6 doubles → AABB)
            Object aabb = null;
            for (String n : new String[]{ "getBoundingBox", "a" }) {
                try {
                    Method m = aabbClass.getMethod(n, double.class, double.class, double.class,
                                                      double.class, double.class, double.class);
                    aabb = m.invoke(null, x - 1.0, y - 1.0, z - 1.0, x + 2.0, y + 2.0, z + 2.0);
                    if (aabb != null) break;
                } catch (Exception ignored) {}
            }
            if (aabb == null) return;

            // World.getEntitiesWithinAABB(Class, AABB) / obf "a"
            if (getEntitiesMethod == null) {
                for (String n : new String[]{ "getEntitiesWithinAABB", "a" }) {
                    try {
                        getEntitiesMethod = world.getClass().getMethod(n, Class.class, aabbClass);
                        break;
                    } catch (Exception ignored) {}
                }
            }
            if (getEntitiesMethod == null) return;

            List<?> entities = (List<?>) getEntitiesMethod.invoke(world, eiClass, aabb);
            if (entities == null || entities.isEmpty()) return;

            for (Object entity : entities) {
                // entity.setDead() / obf "x"
                Reflect.invokeNoArg(entity, void.class, "setDead", "x");
            }
            System.out.println("[OVM] removed " + entities.size() + " vanilla drops near origin");
        } catch (Exception e) {
            System.out.println("[OVM] removeDroppedItems error: " + e);
        }
    }

    private static long coordKey(int x, int y, int z) {
        return ((long)(x + 30000)) * 60001L * 512L + ((long)(y + 256)) * 60001L + (z + 30000);
    }
}
