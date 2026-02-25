package com.ovm;

import cpw.mods.fml.common.network.Player;
import java.lang.reflect.Constructor;
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
 * Obfuscated names from forge/conf/packaged.srg (1.4.7-6.6.2.534).
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

    // Block id pairs that should be treated as the same vein target.
    // e.g. redstone ore (73) activates to lit redstone ore (74) on touch.
    private static final int[][] EQUIVALENT_IDS = { { 73, 74 } };

    /** Normalize a block id to its canonical vein id (e.g. 74 → 73). */
    private static int canonicalId(int id) {
        for (int[] pair : EQUIVALENT_IDS)
            if (id == pair[1]) return pair[0];
        return id;
    }

    /** Return true if blockId belongs to the same vein as targetId. */
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
            Object world = getField(player, Object.class, "worldObj", "p");
            if (world == null || getField(world, boolean.class, "isRemote", "I")) return;

            int originId = canonicalId(invokeGetBlockId(world, ox, oy, oz));
            if (originId == 0) originId = canonicalId(hintBlockId); // client already broke it
            if (originId == 0) return;

            Object foodStats = invokeNoArg(player, Object.class, "getFoodStats", "cc");
            if (foodStats == null) return;
            if (invokeNoArg(foodStats, int.class, "getFoodLevel", "a") < 1) {
                invokeWithStringArg(player, "addChatMessage", "[OVM] Not enough food to veinmine.");
                return;
            }

            Object originBlock = getBlockFromArray(originId);
            if (originBlock != null && !invokeCanHarvest(player, originBlock)) {
                System.out.println("[OVM] veinmine: canHarvest=false for originId=" + originId + ", abort");
                return;
            }

            List<int[]> vein = buildVein(world, ox, oy, oz, originId, OvmConfig.maxBlocks);
            System.out.println("[OVM] veinmine: originId=" + originId + " vein=" + vein.size());
            if (vein.isEmpty()) return;

            LinkedHashMap<Integer, int[]> drops = new LinkedHashMap<Integer, int[]>();
            int minedCount = 0;

            for (int[] pos : vein) {
                int bx = pos[0], by = pos[1], bz = pos[2];
                int actualId = invokeGetBlockId(world, bx, by, bz);
                if (!matchesTarget(actualId, originId)) continue;

                Object block = getBlockFromArray(actualId);
                if (block == null || !invokeCanHarvest(player, block)) continue;

                int meta = invokeGetBlockMeta(world, bx, by, bz);
                boolean harvested = invokeHarvestBlock(block, world, player, bx, by, bz, meta);
                if (!harvested) collectBlockDrops(block, meta, drops);
                invokeSetBlock(world, bx, by, bz, 0);
                minedCount++;

                Object held = getHeldItem(player);
                if (held != null && isItemStackDamageable(held)) {
                    damageItemReflect(held, 1, player);
                    if (getStackSize(held) <= 0) {
                        System.out.println("[OVM] tool broke at block " + minedCount);
                        break;
                    }
                }
            }

            deliverDrops(world, player, drops);

            if (OvmConfig.hungerPerBlocks > 0 && minedCount > 0) {
                int pts = minedCount / OvmConfig.hungerPerBlocks;
                if (pts > 0) invokeWithFloatArg(foodStats, "addExhaustion", "a", 4.0f * pts);
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
            int bid = invokeGetBlockId(world, bx, by, bz);
            boolean isOrigin = (bx == ox && by == oy && bz == oz);
            if (!matchesTarget(bid, targetId) && !isOrigin) continue;
            if (matchesTarget(bid, targetId)) result.add(new int[]{ bx, by, bz });
            for (int[] d : NEIGHBORS) {
                int nx = bx + d[0], ny = by + d[1], nz = bz + d[2];
                long nk = coordKey(nx, ny, nz);
                if (!visited.contains(nk)) {
                    visited.add(nk);
                    if (matchesTarget(invokeGetBlockId(world, nx, ny, nz), targetId)) {
                        long ddx = nx - ox, ddy = ny - oy, ddz = nz - oz;
                        pq.add(new PQEntry(ddx * ddx + ddy * ddy + ddz * ddz, nx, ny, nz));
                    }
                }
            }
        }
        return result;
    }

    // -----------------------------------------------------------------------
    // Drop collection
    // -----------------------------------------------------------------------

    // harvestBlock(World, EntityPlayer, int, int, int, int) — vanilla handles special drops (shears, etc.)
    // Returns true if called successfully (vanilla spawned drops), false if not found (use collectBlockDrops).
    private static final HashMap<String, Method> harvestBlockMethodCache = new HashMap<String, Method>();

    private static boolean invokeHarvestBlock(Object block, Object world, Object player, int x, int y, int z, int meta) {
        String cls = block.getClass().getName();
        Method m = harvestBlockMethodCache.get(cls);
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
            harvestBlockMethodCache.put(cls, m != null ? m : null);
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
    private static final HashMap<String, Method[]> blockDropMethodCache = new HashMap<String, Method[]>();

    private static void collectBlockDrops(Object block, int meta, LinkedHashMap<Integer, int[]> merged) {
        try {
            String cls = block.getClass().getName();
            Method[] m = blockDropMethodCache.get(cls);
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
                blockDropMethodCache.put(cls, m);
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
                Object stack = makeItemStack(acc[0], acc[1], batch);
                if (stack == null) continue;
                if (OvmConfig.dropsToInventory) {
                    int leftover = addToInventory(player, stack);
                    if (leftover > 0) {
                        Object overflow = makeItemStack(acc[0], acc[1], leftover);
                        if (overflow != null) spawnItemReflect(world, player, overflow);
                    }
                } else {
                    spawnItemReflect(world, player, stack);
                }
            }
        }
    }

    // -----------------------------------------------------------------------
    // ItemStack field resolution
    // -----------------------------------------------------------------------

    // Per-class cache: [itemId, stackSize, itemDamage]
    // yz (client): kv.a=itemId, yz.c=stackSize, yz.d=itemDamage
    // ur (server): ur.c=itemId, ur.a=stackSize, ur.e=itemDamage
    private static final Map<String, Field[]> stackFieldCache = new HashMap<String, Field[]>();

    private static Field[] resolveStackFields(Object stack) {
        String cls = stack.getClass().getName();
        if (stackFieldCache.containsKey(cls)) return stackFieldCache.get(cls);

        Field[] result;

        // MCP names
        result = tryStackSchema(stack.getClass(), "itemID", "stackSize", "itemDamage");
        if (result != null) { stackFieldCache.put(cls, result); return result; }

        // yz (client): kv.a=itemId, yz.c=stackSize, yz.d=itemDamage
        result = tryStackSchema(stack.getClass(), "a", "c", "d");
        if (result != null) { stackFieldCache.put(cls, result); return result; }

        // ur (server): ur.c=itemId, ur.a=stackSize, ur.e=itemDamage
        result = tryStackSchema(stack.getClass(), "c", "a", "e");
        if (result != null) { stackFieldCache.put(cls, result); return result; }

        result = new Field[3]; // all null — will return 0 for everything
        stackFieldCache.put(cls, result);
        return result;
    }

    /** Returns [itemId, stackSize, itemDamage] fields if all three are int fields, else null. */
    private static Field[] tryStackSchema(Class<?> cls, String idName, String sizeName, String damageName) {
        Field[] result = new Field[3];
        try { result[0] = findIntField(cls, idName);    } catch (Exception ignored) {}
        try { result[1] = findIntField(cls, sizeName);  } catch (Exception ignored) {}
        try { result[2] = findIntField(cls, damageName);} catch (Exception ignored) {}
        return (result[0] != null && result[1] != null && result[2] != null) ? result : null;
    }

    private static int getStackSize(Object stack) {
        Field[] f = resolveStackFields(stack);
        try { return f[1] != null ? f[1].getInt(stack) : 0; } catch (Exception e) { return 0; }
    }

    private static int getStackItemId(Object stack) {
        Field[] f = resolveStackFields(stack);
        try { return f[0] != null ? f[0].getInt(stack) : 0; } catch (Exception e) { return 0; }
    }

    private static int getStackDamage(Object stack) {
        Field[] f = resolveStackFields(stack);
        try { return f[2] != null ? f[2].getInt(stack) : 0; } catch (Exception e) { return 0; }
    }

    private static int getStackMaxDamage(Object stack) {
        return invokeNoArg(stack, int.class, "getMaxDamage", "k");
    }

    // -----------------------------------------------------------------------
    // Reflection primitives
    // -----------------------------------------------------------------------

    /** Walk class hierarchy to find a declared field by name. */
    private static Field findField(Class<?> cls, String name) throws NoSuchFieldException {
        for (Class<?> c = cls; c != null && !c.getName().equals("java.lang.Object"); c = c.getSuperclass()) {
            try { Field f = c.getDeclaredField(name); f.setAccessible(true); return f; }
            catch (NoSuchFieldException ignored) {}
        }
        throw new NoSuchFieldException(name);
    }

    /** Walk class hierarchy to find a declared int field by name. */
    private static Field findIntField(Class<?> cls, String name) throws NoSuchFieldException {
        for (Class<?> c = cls; c != null && !c.getName().equals("java.lang.Object"); c = c.getSuperclass()) {
            try {
                Field f = c.getDeclaredField(name);
                if (f.getType() == int.class) { f.setAccessible(true); return f; }
            } catch (NoSuchFieldException ignored) {}
        }
        throw new NoSuchFieldException(name + " (int)");
    }

    /**
     * Get a typed field value from obj, trying each name in order.
     * Uses public getField first, then hierarchy walk via findField.
     * Returns type default (null/false/0) if not found.
     */
    @SuppressWarnings("unchecked")
    private static <T> T getField(Object obj, Class<T> type, String... names) {
        for (String name : names) {
            // Try public field first
            try {
                Field f = obj.getClass().getField(name);
                return (T) fieldGet(f, obj, type);
            } catch (Exception ignored) {}
            // Then hierarchy
            try {
                Field f = findField(obj.getClass(), name);
                return (T) fieldGet(f, obj, type);
            } catch (Exception ignored) {}
        }
        return defaultValue(type);
    }

    private static Object fieldGet(Field f, Object obj, Class<?> type) throws Exception {
        if (type == boolean.class) return f.getBoolean(obj);
        if (type == int.class)     return f.getInt(obj);
        if (type == double.class)  return f.getDouble(obj);
        return f.get(obj);
    }

    @SuppressWarnings("unchecked")
    private static <T> T defaultValue(Class<T> type) {
        if (type == boolean.class) return (T) Boolean.FALSE;
        if (type == int.class)     return (T) Integer.valueOf(0);
        if (type == double.class)  return (T) Double.valueOf(0.0);
        return null;
    }

    /**
     * Invoke a no-arg method by name, returning typed result.
     * Returns type default if not found or on error.
     */
    @SuppressWarnings("unchecked")
    private static <T> T invokeNoArg(Object obj, Class<T> type, String... names) {
        for (String name : names) {
            try {
                Method m = obj.getClass().getMethod(name);
                Object result = m.invoke(obj);
                return result != null ? (T) result : defaultValue(type);
            } catch (Exception ignored) {}
        }
        return defaultValue(type);
    }

    private static void invokeWithStringArg(Object obj, String method, String arg) {
        try { obj.getClass().getMethod(method, String.class).invoke(obj, arg); }
        catch (Exception ignored) {}
    }

    private static void invokeWithFloatArg(Object obj, String... names) {
        // names: mcpName, obfName — last element is the float value encoded as a string? No —
        // caller passes (obj, mcpName, obfName, floatVal). Handle via varargs workaround below.
        // This overload is not used; see the 4-arg version.
    }

    private static void invokeWithFloatArg(Object obj, String mcpName, String obfName, float arg) {
        for (String name : new String[]{ mcpName, obfName }) {
            try { obj.getClass().getMethod(name, float.class).invoke(obj, arg); return; }
            catch (Exception ignored) {}
        }
    }

    // -----------------------------------------------------------------------
    // World accessors (cached method lookup)
    // -----------------------------------------------------------------------

    // World.getBlockId(III)
    private static Method getBlockIdMethod;
    private static int invokeGetBlockId(Object world, int x, int y, int z) {
        try {
            if (getBlockIdMethod == null) {
                for (String n : new String[]{ "getBlockId", "a" }) {
                    try { getBlockIdMethod = world.getClass().getMethod(n, int.class, int.class, int.class); break; }
                    catch (Exception ignored) {}
                }
            }
            return getBlockIdMethod != null ? (Integer) getBlockIdMethod.invoke(world, x, y, z) : 0;
        } catch (Exception e) { return 0; }
    }

    // World.getBlockMetadata(III)
    private static Method getBlockMetaMethod;
    private static int invokeGetBlockMeta(Object world, int x, int y, int z) {
        try {
            if (getBlockMetaMethod == null) {
                for (String n : new String[]{ "getBlockMetadata", "h" }) {
                    try { getBlockMetaMethod = world.getClass().getMethod(n, int.class, int.class, int.class); break; }
                    catch (Exception ignored) {}
                }
            }
            return getBlockMetaMethod != null ? (Integer) getBlockMetaMethod.invoke(world, x, y, z) : 0;
        } catch (Exception e) { return 0; }
    }

    // World.setBlock(IIII)Z + markBlockForUpdate(III)V + notifyBlockChange(IIII)V
    private static Method setBlockMethod;
    private static Method markBlockForUpdateMethod;
    private static Method notifyBlockChangeMethod;

    private static void invokeSetBlock(Object world, int x, int y, int z, int id) {
        try {
            if (setBlockMethod == null) {
                // setBlock(IIII)Z — direct write
                for (String n : new String[]{ "setBlock", "b" }) {
                    try {
                        Method m = world.getClass().getMethod(n, int.class, int.class, int.class, int.class);
                        if (m.getReturnType() == boolean.class) { setBlockMethod = m; break; }
                    } catch (Exception ignored) {}
                }
                // fallback: setBlockWithNotify does setBlock+notify in one call
                if (setBlockMethod == null) {
                    for (String n : new String[]{ "setBlockWithNotify", "c" }) {
                        try { setBlockMethod = world.getClass().getMethod(n, int.class, int.class, int.class, int.class); break; }
                        catch (Exception ignored) {}
                    }
                }
            }
            if (markBlockForUpdateMethod == null) {
                for (String n : new String[]{ "markBlockForUpdate", "h" }) {
                    try {
                        Method m = world.getClass().getMethod(n, int.class, int.class, int.class);
                        if (m.getReturnType() == void.class) { markBlockForUpdateMethod = m; break; }
                    } catch (Exception ignored) {}
                }
            }
            if (notifyBlockChangeMethod == null) {
                for (String n : new String[]{ "notifyBlockChange", "f" }) {
                    try {
                        Method m = world.getClass().getMethod(n, int.class, int.class, int.class, int.class);
                        if (m.getReturnType() == void.class) { notifyBlockChangeMethod = m; break; }
                    } catch (Exception ignored) {}
                }
            }

            if (setBlockMethod == null) return;
            setBlockMethod.invoke(world, x, y, z, id);
            if (markBlockForUpdateMethod != null) {
                markBlockForUpdateMethod.invoke(world, x,     y,     z);
                markBlockForUpdateMethod.invoke(world, x + 1, y,     z);
                markBlockForUpdateMethod.invoke(world, x - 1, y,     z);
                markBlockForUpdateMethod.invoke(world, x,     y + 1, z);
                markBlockForUpdateMethod.invoke(world, x,     y - 1, z);
                markBlockForUpdateMethod.invoke(world, x,     y,     z + 1);
                markBlockForUpdateMethod.invoke(world, x,     y,     z - 1);
            }
            if (notifyBlockChangeMethod != null) notifyBlockChangeMethod.invoke(world, x, y, z, id);
        } catch (Exception e) {
            System.out.println("[OVM] invokeSetBlock error: " + e);
        }
    }

    // -----------------------------------------------------------------------
    // Block and player helpers
    // -----------------------------------------------------------------------

    private static Object[] blocksListCache;
    private static Object getBlockFromArray(int id) {
        try {
            if (blocksListCache == null) {
                for (String cname : new String[]{ "net.minecraft.block.Block", "amq" }) {
                    try {
                        Class<?> cls = Class.forName(cname);
                        for (String fname : new String[]{ "blocksList", "p" }) {
                            try {
                                Field f = cls.getDeclaredField(fname);
                                f.setAccessible(true);
                                Object[] arr = (Object[]) f.get(null);
                                if (arr != null) { blocksListCache = arr; break; }
                            } catch (Exception ignored) {}
                        }
                        if (blocksListCache != null) break;
                    } catch (Exception ignored) {}
                }
            }
            return (blocksListCache != null && id >= 0 && id < blocksListCache.length)
                ? blocksListCache[id] : null;
        } catch (Exception e) { return null; }
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
    private static void damageItemReflect(Object stack, int amount, Object player) {
        try {
            if (damageItemMethod == null) {
                // Prefer exact match: damageItem(int, EntityLiving) void
                for (Method m : stack.getClass().getMethods()) {
                    Class<?>[] p = m.getParameterTypes();
                    if (p.length == 2 && p[0] == int.class && !p[1].isPrimitive()
                            && (m.getName().equals("damageItem") || m.getName().equals("a"))
                            && m.getReturnType() == void.class
                            && p[1].isAssignableFrom(player.getClass())) {
                        damageItemMethod = m; break;
                    }
                }
                // Loose fallback
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
            System.out.println("[OVM] damageItemReflect error: " + e);
        }
    }

    private static boolean isItemStackDamageable(Object stack) {
        return invokeNoArg(stack, boolean.class, "isItemStackDamageable", "f", "q");
    }

    // EntityPlayer inventory path: player.inventory (bJ) -> getCurrentItem() (g)
    private static Field  inventoryField;
    private static Method inventoryGetCurrent;
    // Fallback: player.getHeldItem() / bD()
    private static Method getHeldItemMethod;

    private static Object getHeldItem(Object player) {
        if (inventoryField == null) {
            for (String n : new String[]{ "inventory", "bJ" }) {
                try { inventoryField = findField(player.getClass(), n); break; }
                catch (Exception ignored) {}
            }
        }
        if (inventoryField != null) {
            try {
                Object inv = inventoryField.get(player);
                if (inv != null) {
                    if (inventoryGetCurrent == null) {
                        for (String n : new String[]{ "getCurrentItem", "g" }) {
                            try {
                                Method m = inv.getClass().getMethod(n);
                                if (m.getReturnType() != void.class && !m.getReturnType().isPrimitive()) {
                                    inventoryGetCurrent = m; break;
                                }
                            } catch (Exception ignored) {}
                        }
                    }
                    if (inventoryGetCurrent != null) return inventoryGetCurrent.invoke(inv);
                }
            } catch (Exception ignored) {}
        }
        if (getHeldItemMethod == null) {
            for (String n : new String[]{ "getHeldItem", "bD", "bE", "bF", "bC" }) {
                try {
                    Method m = player.getClass().getMethod(n);
                    if (m.getReturnType() != void.class && !m.getReturnType().isPrimitive()) {
                        getHeldItemMethod = m; break;
                    }
                } catch (Exception ignored) {}
            }
        }
        try { return getHeldItemMethod != null ? getHeldItemMethod.invoke(player) : null; }
        catch (Exception e) { return null; }
    }

    // -----------------------------------------------------------------------
    // Item stack construction and delivery
    // -----------------------------------------------------------------------

    private static Constructor<?> itemStackCtor;
    private static boolean itemStackCtorHasClass;

    private static Object makeItemStack(int itemId, int damage, int count) {
        try {
            if (itemStackCtor == null) {
                for (String cname : new String[]{ "net.minecraft.item.ItemStack", "ur", "yz" }) {
                    try {
                        Class<?> cls = Class.forName(cname);
                        try {
                            itemStackCtor = cls.getConstructor(int.class, int.class, int.class);
                            itemStackCtorHasClass = false;
                        } catch (Exception ignored) {
                            itemStackCtor = cls.getConstructor(Class.class, int.class, int.class, int.class);
                            itemStackCtorHasClass = true;
                        }
                        break;
                    } catch (Exception ignored) {}
                }
            }
            if (itemStackCtor == null) return null;
            return itemStackCtorHasClass
                ? itemStackCtor.newInstance((Object) null, itemId, count, damage)
                : itemStackCtor.newInstance(itemId, count, damage);
        } catch (Exception e) {
            System.out.println("[OVM] makeItemStack error: " + e);
            return null;
        }
    }

    // InventoryPlayer.addItemStackToInventory(ItemStack)Z — MCP name, obf "c" (or "b")
    private static Method addToInventoryMethod;

    /** Add stack to player inventory. Returns leftover count (0 if fully added). */
    private static int addToInventory(Object player, Object stack) {
        try {
            Object inv = inventoryField != null ? inventoryField.get(player) : null;
            if (inv == null) return getStackSize(stack);
            if (addToInventoryMethod == null) {
                for (String n : new String[]{ "addItemStackToInventory", "c", "b" }) {
                    try {
                        Method m = inv.getClass().getMethod(n, stack.getClass());
                        if (m.getReturnType() == boolean.class) { addToInventoryMethod = m; break; }
                    } catch (Exception ignored) {}
                }
                if (addToInventoryMethod == null) {
                    for (Method m : inv.getClass().getMethods()) {
                        Class<?>[] p = m.getParameterTypes();
                        if (p.length == 1 && !p[0].isPrimitive()
                                && p[0].isAssignableFrom(stack.getClass())
                                && m.getReturnType() == boolean.class
                                && (m.getName().equals("addItemStackToInventory")
                                    || m.getName().equals("c") || m.getName().equals("b"))) {
                            addToInventoryMethod = m; break;
                        }
                    }
                }
            }
            if (addToInventoryMethod == null) return getStackSize(stack);
            boolean added = (Boolean) addToInventoryMethod.invoke(inv, stack);
            int remaining = getStackSize(stack);
            return (added && remaining <= 0) ? 0 : remaining;
        } catch (Exception e) {
            System.out.println("[OVM] addToInventory error: " + e);
            return getStackSize(stack);
        }
    }

    private static Constructor<?> entityItemCtor;
    private static Method spawnEntityMethod;

    private static void spawnItemReflect(Object world, Object player, Object stack) {
        try {
            double px = getField(player, double.class, "posX", "bT");
            double py = getField(player, double.class, "posY", "bU");
            double pz = getField(player, double.class, "posZ", "bV");

            if (entityItemCtor == null) {
                Class<?> eiClass = null;
                for (String cname : new String[]{ "net.minecraft.entity.item.EntityItem", "px" }) {
                    try { eiClass = Class.forName(cname); break; }
                    catch (Exception ignored) {}
                }
                if (eiClass == null) return;
                Class<?> worldClass = world.getClass();
                while (worldClass != null) {
                    try {
                        entityItemCtor = eiClass.getConstructor(worldClass, double.class, double.class, double.class, stack.getClass());
                        break;
                    } catch (Exception ignored) { worldClass = worldClass.getSuperclass(); }
                }
            }
            if (entityItemCtor == null) return;
            Object ei = entityItemCtor.newInstance(world, px, py, pz, stack);
            if (spawnEntityMethod == null) {
                for (String n : new String[]{ "spawnEntityInWorld", "d" }) {
                    for (Method m : world.getClass().getMethods()) {
                        if (m.getName().equals(n) && m.getParameterTypes().length == 1
                                && m.getParameterTypes()[0].isAssignableFrom(ei.getClass())) {
                            spawnEntityMethod = m; break;
                        }
                    }
                    if (spawnEntityMethod != null) break;
                }
            }
            if (spawnEntityMethod != null) spawnEntityMethod.invoke(world, ei);
        } catch (Exception e) {
            System.out.println("[OVM] spawnItemReflect error: " + e);
        }
    }

    private static long coordKey(int x, int y, int z) {
        return ((long)(x + 30000)) * 60001L * 512L + ((long)(y + 256)) * 60001L + (z + 30000);
    }
}
