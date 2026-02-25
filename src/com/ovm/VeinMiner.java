package com.ovm;

import cpw.mods.fml.common.network.Player;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.PriorityQueue;
import java.util.Set;

/**
 * VeinMiner v0.3.0 — server-side chain-mine with priority flood fill.
 *
 * Called from OvmPacketHandler when the client sends a veinmine request.
 * All Minecraft types accessed via reflection to avoid NoClassDefFoundError.
 *
 * Obfuscated names sourced from forge/conf/packaged.srg (1.4.7-6.6.2.534).
 * Each helper tries the MCP name first, then the obfuscated name.
 */
public class VeinMiner {

    // 26-neighbor offsets (full 3x3x3 minus center)
    private static final int[][] NEIGHBORS = new int[26][3];

    static {
        int idx = 0;
        for (int dx = -1; dx <= 1; dx++) for (int dy = -1; dy <= 1; dy++) for (
            int dz = -1;
            dz <= 1;
            dz++
        ) if (dx != 0 || dy != 0 || dz != 0) NEIGHBORS[idx++] = new int[] {
            dx,
            dy,
            dz,
        };
    }

    // -----------------------------------------------------------------------
    // Entry point called by OvmPacketHandler
    // -----------------------------------------------------------------------

    public static void veinmine(
        Player playerArg,
        int ox,
        int oy,
        int oz,
        int hintBlockId
    ) {
        System.out.println(
            "[OVM] veinmine called ox=" +
                ox +
                " oy=" +
                oy +
                " oz=" +
                oz +
                " hintBlockId=" +
                hintBlockId +
                " player=" +
                (playerArg == null ? "null" : playerArg.getClass().getName())
        );
        try {
            Object player = playerArg;

            // worldObj field: MCP="worldObj", obf="p"  (Entity.field_70170_p)
            Object world = getFieldByNames(player, "worldObj", "p");
            if (world == null) {
                System.out.println("[OVM] veinmine: world is null");
                return;
            }

            // Must be server-side
            boolean isRemote = getBooleanByNames(world, "isRemote", "I");
            System.out.println(
                "[OVM] veinmine: world=" +
                    world.getClass().getSimpleName() +
                    " isRemote=" +
                    isRemote
            );
            if (isRemote) {
                System.out.println("[OVM] veinmine: skipped (isRemote=true)");
                return;
            }

            int originId = invokeGetBlockId(world, ox, oy, oz);
            // Client already broke the origin block — use the hint ID sent in the packet
            if (originId == 0 && hintBlockId != 0) {
                System.out.println(
                    "[OVM] veinmine: origin is air, using hintBlockId=" +
                        hintBlockId
                );
                originId = hintBlockId;
            }
            System.out.println(
                "[OVM] veinmine: originId=" +
                    originId +
                    " at (" +
                    ox +
                    "," +
                    oy +
                    "," +
                    oz +
                    ")"
            );
            if (originId == 0) {
                System.out.println(
                    "[OVM] veinmine: origin is air and no hint, abort"
                );
                return;
            }

            // Log held item at start of veinmine
            Object heldAtStart = getHeldItem(player);
            if (heldAtStart == null) {
                System.out.println("[OVM] veinmine: heldItem=null (bare hands)");
            } else {
                int heldId  = getStackItemId(heldAtStart);
                int heldDmg = getStackDamage(heldAtStart);
                int heldMax = getStackMaxDamage(heldAtStart);
                System.out.println("[OVM] veinmine: heldItem id=" + heldId + " dmg=" + heldDmg + "/" + heldMax);
            }

            // Hunger check
            Object foodStats = invokeByNames(player, "getFoodStats", "cc");
            System.out.println(
                "[OVM] veinmine: foodStats=" +
                    (foodStats == null
                        ? "null"
                        : foodStats.getClass().getSimpleName())
            );
            if (foodStats == null) {
                System.out.println("[OVM] veinmine: foodStats null, abort");
                return;
            }
            int foodLevel = invokeIntByNames(foodStats, "getFoodLevel", "a");
            System.out.println("[OVM] veinmine: foodLevel=" + foodLevel);
            if (foodLevel < 1) {
                invokeWithStringArg(
                    player,
                    "addChatMessage",
                    "[OVM] Not enough food to veinmine."
                );
                return;
            }

            // Require correct tool for the origin block before proceeding
            Object originBlock = getBlockFromArray(originId);
            if (originBlock != null && !invokeCanHarvest(player, originBlock)) {
                System.out.println("[OVM] veinmine: wrong tool for origin block, abort");
                return;
            }

            List<int[]> vein = buildVein(
                world,
                ox,
                oy,
                oz,
                originId,
                OvmConfig.maxBlocks
            );
            System.out.println("[OVM] veinmine: vein size=" + vein.size());
            if (vein.isEmpty()) {
                System.out.println("[OVM] veinmine: vein empty, abort");
                return;
            }

            // Collect drops manually: key=(itemId<<16|damage) -> [itemId, damage, totalCount]
            java.util.LinkedHashMap<Integer, int[]> mergedDrops =
                new java.util.LinkedHashMap<Integer, int[]>();

            int minedCount = 0;
            for (int[] pos : vein) {
                int bx = pos[0],
                    by = pos[1],
                    bz = pos[2];
                int blockId = invokeGetBlockId(world, bx, by, bz);
                if (blockId != originId) continue;

                Object block = getBlockFromArray(blockId);
                if (block == null) {
                    System.out.println(
                        "[OVM] veinmine: block null for id=" + blockId + " skip"
                    );
                    continue;
                }
                boolean canHarvest = invokeCanHarvest(player, block);
                System.out.println(
                    "[OVM] veinmine: (" +
                        bx +
                        "," +
                        by +
                        "," +
                        bz +
                        ") id=" +
                        blockId +
                        " block=" +
                        block.getClass().getSimpleName() +
                        " canHarvest=" +
                        canHarvest
                );
                if (!canHarvest) continue;

                int meta = invokeGetBlockMeta(world, bx, by, bz);
                // Collect drops from this block before removing it
                collectBlockDrops(
                    block,
                    world,
                    player,
                    bx,
                    by,
                    bz,
                    meta,
                    mergedDrops
                );
                invokeSetBlock(world, bx, by, bz, 0);
                minedCount++;

                // Damage tool (1 durability per block, same as vanilla)
                Object held = getHeldItem(player);
                if (held == null) {
                    System.out.println("[OVM] durability: held=null at block " + minedCount);
                } else if (!isItemStackDamageable(held)) {
                    System.out.println("[OVM] durability: item not damageable (id=" + getStackItemId(held) + ")");
                } else {
                    int dmgBefore = getStackDamage(held);
                    int sizeBefore = getStackSize(held);
                    damageItemReflect(held, 1, player);
                    int dmgAfter = getStackDamage(held);
                    int sizeAfter = getStackSize(held);
                    System.out.println("[OVM] durability: block=" + minedCount
                        + " dmg " + dmgBefore + "->" + dmgAfter
                        + " stackSize " + sizeBefore + "->" + sizeAfter);
                    if (sizeAfter <= 0) {
                        System.out.println("[OVM] durability: tool broke, stopping");
                        break;
                    }
                }
            }

            deliverMergedDrops(world, player, mergedDrops);

            if (OvmConfig.hungerPerBlocks > 0 && minedCount > 0) {
                int pts = minedCount / OvmConfig.hungerPerBlocks;
                if (pts > 0) {
                    invokeFloatByNames(
                        foodStats,
                        "addExhaustion",
                        "a",
                        4.0f * pts
                    );
                }
            }

            System.out.println(
                "[OVM] Veinmined " +
                    minedCount +
                    " blocks at (" +
                    ox +
                    "," +
                    oy +
                    "," +
                    oz +
                    ")"
            );
        } catch (Exception e) {
            System.out.println("[OVM] VeinMiner error: " + e);
            e.printStackTrace();
        }
    }

    // -----------------------------------------------------------------------
    // Priority flood fill
    // -----------------------------------------------------------------------

    private static List<int[]> buildVein(
        Object world,
        int ox,
        int oy,
        int oz,
        int targetId,
        int maxCount
    ) {
        List<int[]> result = new ArrayList<int[]>();
        PriorityQueue<long[]> pq = new PriorityQueue<long[]>(
            16,
            new Comparator<long[]>() {
                public int compare(long[] a, long[] b) {
                    return Long.compare(a[0], b[0]);
                }
            }
        );
        Set<Long> visited = new HashSet<Long>();

        visited.add(coordKey(ox, oy, oz));
        pq.add(new long[] { 0L, ox, oy, oz });

        while (!pq.isEmpty() && result.size() < maxCount) {
            long[] e = pq.poll();
            int bx = (int) e[1],
                by = (int) e[2],
                bz = (int) e[3];
            int bid = invokeGetBlockId(world, bx, by, bz);
            // Accept the block if it matches targetId, OR if it's the origin (already air — client mined it)
            boolean isOrigin = (bx == ox && by == oy && bz == oz);
            if (bid != targetId && !isOrigin) continue;
            if (bid == targetId) result.add(new int[] { bx, by, bz }); // don't add air origin to result
            for (int[] d : NEIGHBORS) {
                int nx = bx + d[0],
                    ny = by + d[1],
                    nz = bz + d[2];
                long nk = coordKey(nx, ny, nz);
                if (
                    !visited.contains(nk) &&
                    invokeGetBlockId(world, nx, ny, nz) == targetId
                ) {
                    visited.add(nk);
                    long ddx = nx - ox,
                        ddy = ny - oy,
                        ddz = nz - oz;
                    pq.add(
                        new long[] {
                            ddx * ddx + ddy * ddy + ddz * ddz,
                            nx,
                            ny,
                            nz,
                        }
                    );
                }
            }
        }
        return result;
    }

    // -----------------------------------------------------------------------
    // Drop collection and delivery
    // -----------------------------------------------------------------------

    // Per-class method caches for block drop methods (keyed by block class name)
    // [0]=idDropped, [1]=quantityDropped, [2]=damageDropped
    private static final java.util.HashMap<String, Method[]> blockDropMethodCache =
        new java.util.HashMap<String, Method[]>();

    private static final java.util.Random RAND = new java.util.Random();

    private static void collectBlockDrops(
        Object block,
        Object world,
        Object player,
        int x,
        int y,
        int z,
        int meta,
        java.util.LinkedHashMap<Integer, int[]> merged
    ) {
        try {
            String blockClassName = block.getClass().getName();
            System.out.println("[OVM] collectBlockDrops: block=" + blockClassName + " meta=" + meta);

            // Resolve block drop methods per class
            Method[] dropMethods = blockDropMethodCache.get(blockClassName);
            if (dropMethods == null) {
                dropMethods = new Method[3]; // [0]=idDropped, [1]=quantityDropped, [2]=damageDropped
                // idDropped: MCP="idDropped", obf="a" — signature (int,Random,int)->int (unique)
                // quantityDropped: MCP="quantityDropped", obf="a" — signature (Random)->int
                // damageDropped: MCP="damageDropped", obf="b" — signature (int)->int
                for (Method m : block.getClass().getMethods()) {
                    if (m.getReturnType() != int.class) continue;
                    Class<?>[] p = m.getParameterTypes();
                    if (p.length == 3 && p[0] == int.class && p[1] == java.util.Random.class && p[2] == int.class) {
                        dropMethods[0] = m;
                    } else if (p.length == 1 && p[0] == java.util.Random.class) {
                        dropMethods[1] = m;
                    } else if (p.length == 1 && p[0] == int.class
                            && (m.getName().equals("damageDropped") || m.getName().equals("b"))) {
                        dropMethods[2] = m;
                    }
                }
                blockDropMethodCache.put(blockClassName, dropMethods);
                System.out.println("[OVM] resolved for " + blockClassName
                    + " idDropped=" + (dropMethods[0] != null ? dropMethods[0].getName() : "null")
                    + " qty=" + (dropMethods[1] != null ? dropMethods[1].getName() : "null")
                    + " dmg=" + (dropMethods[2] != null ? dropMethods[2].getName() : "null"));
            }

            int dropId =
                dropMethods[0] != null
                    ? (Integer) dropMethods[0].invoke(block, meta, RAND, 0)
                    : 0;
            System.out.println(
                "[OVM] collectBlockDrops: dropId=" + dropId + " meta=" + meta
            );
            if (dropId == 0) return; // block drops nothing

            int dropCount =
                dropMethods[1] != null
                    ? (Integer) dropMethods[1].invoke(block, RAND)
                    : 1;
            if (dropCount <= 0) return;

            int dropDamage =
                dropMethods[2] != null
                    ? (Integer) dropMethods[2].invoke(block, meta)
                    : 0;
            System.out.println(
                "[OVM] collectBlockDrops: dropCount=" +
                    dropCount +
                    " dropDamage=" +
                    dropDamage
            );

            int key = dropId * 65536 + dropDamage;
            int[] acc = merged.get(key);
            if (acc == null) {
                merged.put(key, new int[] { dropId, dropDamage, dropCount });
            } else {
                acc[2] += dropCount;
            }
        } catch (Exception e) {
            System.out.println("[OVM] collectBlockDrops error: " + e);
            e.printStackTrace();
        }
    }

    private static void deliverMergedDrops(
        Object world,
        Object player,
        java.util.LinkedHashMap<Integer, int[]> merged
    ) {
        if (merged.isEmpty()) {
            System.out.println("[OVM] deliverMergedDrops: no drops");
            return;
        }

        double[] pos = getPlayerPos(player);
        double px = pos[0],
            py = pos[1],
            pz = pos[2];
        System.out.println(
            "[OVM] deliverMergedDrops: " +
                merged.size() +
                " item types at (" +
                px +
                "," +
                py +
                "," +
                pz +
                ")"
        );

        int spawnCount = 0;
        for (java.util.Map.Entry<Integer, int[]> entry : merged.entrySet()) {
            int[] acc = entry.getValue();
            int remaining = acc[2];
            while (remaining > 0) {
                int batch = Math.min(remaining, 64);
                remaining -= batch;
                Object stack = makeItemStack(acc[0], acc[1], batch);
                if (stack == null) continue;
                spawnItemReflect(world, px, py, pz, stack);
                spawnCount++;
            }
        }
        System.out.println(
            "[OVM] deliverMergedDrops: spawned " + spawnCount + " stacks"
        );
    }

    // ItemStack field accessors — keyed per class name to handle yz (client) vs ur (server).
    // yz(Class, itemId, count, damage): kv.a=itemId, yz.c=stackSize, yz.d=itemDamage
    // ur(itemId, count, damage):        ur.c=itemId, ur.a=stackSize, ur.e=itemDamage
    private static final java.util.Map<String, Field[]> stackFieldCache =
        new java.util.HashMap<String, Field[]>(); // className -> [idField, sizeField, dmgField]

    /** Returns [idField, sizeField, dmgField] for this stack's class, or null entries on failure.
     *  All returned fields are guaranteed to be int type. */
    private static Field[] resolveStackFields(Object stack) {
        String cls = stack.getClass().getName();
        if (stackFieldCache.containsKey(cls)) return stackFieldCache.get(cls);
        Field[] result = new Field[3]; // [itemId, stackSize, itemDamage]
        // MCP names first
        try { result[0] = findIntField(stack.getClass(), "itemID");    } catch (Exception ignored) {}
        try { result[1] = findIntField(stack.getClass(), "stackSize"); } catch (Exception ignored) {}
        try { result[2] = findIntField(stack.getClass(), "itemDamage");} catch (Exception ignored) {}
        if (result[0] != null && result[1] != null && result[2] != null) {
            System.out.println("[OVM] stackFields[" + cls + "]: MCP names");
            stackFieldCache.put(cls, result);
            return result;
        }
        // yz (client jar): kv.a=itemId, yz.c=stackSize, yz.d=itemDamage (all int)
        try { result[0] = findIntField(stack.getClass(), "a"); } catch (Exception ignored) {}
        try { result[1] = findIntField(stack.getClass(), "c"); } catch (Exception ignored) {}
        try { result[2] = findIntField(stack.getClass(), "d"); } catch (Exception ignored) {}
        if (result[0] != null && result[1] != null && result[2] != null) {
            System.out.println("[OVM] stackFields[" + cls + "]: yz a/c/d");
            stackFieldCache.put(cls, result);
            return result;
        }
        // ur (server jar) from ctor bytecode: ur(itemId,count,damage) -> c=itemId, a=stackSize, e=itemDamage
        result = new Field[3];
        try { result[0] = findIntField(stack.getClass(), "c"); } catch (Exception ignored) {}
        try { result[1] = findIntField(stack.getClass(), "a"); } catch (Exception ignored) {}
        try { result[2] = findIntField(stack.getClass(), "e"); } catch (Exception ignored) {}
        // Dump all int fields on this class to help verify the mapping
        StringBuilder dump = new StringBuilder("[OVM] stackFields[" + cls + "] int fields: ");
        Class<?> c = stack.getClass();
        while (c != null && !c.getName().equals("java.lang.Object")) {
            for (Field f : c.getDeclaredFields()) {
                if (f.getType() == int.class) {
                    f.setAccessible(true);
                    try { dump.append(f.getName()).append("=").append(f.getInt(stack)).append(" "); }
                    catch (Exception ignored) { dump.append(f.getName()).append("=? "); }
                }
            }
            c = c.getSuperclass();
        }
        System.out.println(dump);
        System.out.println("[OVM] stackFields[" + cls + "]: ur c/a/e => id=" +
            (result[0] != null ? result[0].getName() : "null") + " size=" +
            (result[1] != null ? result[1].getName() : "null") + " dmg=" +
            (result[2] != null ? result[2].getName() : "null"));
        stackFieldCache.put(cls, result);
        return result;
    }

    private static Field findField(Class<?> cls, String name)
        throws NoSuchFieldException {
        while (cls != null && !cls.getName().equals("java.lang.Object")) {
            try {
                Field f = cls.getDeclaredField(name);
                f.setAccessible(true);
                return f;
            } catch (NoSuchFieldException ignored) {}
            cls = cls.getSuperclass();
        }
        throw new NoSuchFieldException(name);
    }

    /** Like findField but only matches fields of type int. */
    private static Field findIntField(Class<?> cls, String name)
        throws NoSuchFieldException {
        while (cls != null && !cls.getName().equals("java.lang.Object")) {
            try {
                Field f = cls.getDeclaredField(name);
                if (f.getType() == int.class) {
                    f.setAccessible(true);
                    return f;
                }
            } catch (NoSuchFieldException ignored) {}
            cls = cls.getSuperclass();
        }
        throw new NoSuchFieldException(name + " (int)");
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

    // ItemStack.getMaxDamage(): MCP="getMaxDamage", obf="k" (ur.k()I per packaged.srg)
    private static int getStackMaxDamage(Object stack) {
        for (String n : new String[] { "getMaxDamage", "k" }) {
            try { return (Integer) stack.getClass().getMethod(n).invoke(stack); }
            catch (Exception ignored) {}
        }
        return 0;
    }

    // -----------------------------------------------------------------------
    // Reflection helpers
    // -----------------------------------------------------------------------

    private static Object getFieldByNames(Object obj, String... names) {
        for (String name : names) {
            try {
                Field f = obj.getClass().getField(name);
                return f.get(obj);
            } catch (Exception ignored) {}
        }
        return null;
    }

    private static boolean getBooleanByNames(Object obj, String... names) {
        for (String name : names) {
            try {
                return obj.getClass().getField(name).getBoolean(obj);
            } catch (Exception ignored) {}
        }
        return false;
    }

    private static Object invokeByNames(Object obj, String... names) {
        for (String name : names) {
            try {
                return obj.getClass().getMethod(name).invoke(obj);
            } catch (Exception ignored) {}
        }
        return null;
    }

    private static int invokeIntByNames(Object obj, String... names) {
        for (String name : names) {
            try {
                return (Integer) obj.getClass().getMethod(name).invoke(obj);
            } catch (Exception ignored) {}
        }
        return 0;
    }

    private static void invokeFloatByNames(
        Object obj,
        String mcpName,
        String obfName,
        float arg
    ) {
        for (String name : new String[] { mcpName, obfName }) {
            try {
                obj.getClass().getMethod(name, float.class).invoke(obj, arg);
                return;
            } catch (Exception ignored) {}
        }
    }

    private static void invokeWithStringArg(
        Object obj,
        String method,
        String arg
    ) {
        try {
            obj.getClass().getMethod(method, String.class).invoke(obj, arg);
        } catch (Exception ignored) {}
    }

    // World.getBlockId(III): MCP="getBlockId", obf="a"
    private static Method getBlockIdMethod = null;

    private static int invokeGetBlockId(Object world, int x, int y, int z) {
        try {
            if (getBlockIdMethod == null) {
                for (String n : new String[] { "getBlockId", "a" }) {
                    try {
                        getBlockIdMethod = world
                            .getClass()
                            .getMethod(n, int.class, int.class, int.class);
                        break;
                    } catch (Exception ignored) {}
                }
            }
            if (getBlockIdMethod == null) return 0;
            return (Integer) getBlockIdMethod.invoke(world, x, y, z);
        } catch (Exception e) {
            return 0;
        }
    }

    // World.getBlockMetadata(III): MCP="getBlockMetadata", obf="h"
    private static Method getBlockMetaMethod = null;

    private static int invokeGetBlockMeta(Object world, int x, int y, int z) {
        try {
            if (getBlockMetaMethod == null) {
                for (String n : new String[] { "getBlockMetadata", "h" }) {
                    try {
                        getBlockMetaMethod = world
                            .getClass()
                            .getMethod(n, int.class, int.class, int.class);
                        break;
                    } catch (Exception ignored) {}
                }
            }
            if (getBlockMetaMethod == null) return 0;
            return (Integer) getBlockMetaMethod.invoke(world, x, y, z);
        } catch (Exception e) {
            return 0;
        }
    }

    // setBlock(IIII)Z — direct chunk write, MCP="setBlock", obf="b"
    private static Method setBlockDirectMethod = null;
    // markBlockForUpdate(III)V — sends visual update to client, MCP="markBlockForUpdate"
    private static Method markBlockForUpdateMethod = null;
    // notifyBlockChange(IIII)V — triggers neighbor updates (torches pop, etc), MCP="notifyBlockChange"
    private static Method notifyBlockChangeMethod = null;

    private static void invokeSetBlock(
        Object world,
        int x,
        int y,
        int z,
        int id
    ) {
        try {
            if (setBlockDirectMethod == null) {
                for (String n : new String[] { "setBlock", "b" }) {
                    try {
                        Method m = world
                            .getClass()
                            .getMethod(
                                n,
                                int.class,
                                int.class,
                                int.class,
                                int.class
                            );
                        if (
                            m.getReturnType() == boolean.class ||
                            m.getReturnType() == Boolean.class
                        ) {
                            setBlockDirectMethod = m;
                            System.out.println(
                                "[OVM] invokeSetBlock: setBlock='" + n + "'"
                            );
                            break;
                        }
                    } catch (Exception ignored) {}
                }
            }
            if (markBlockForUpdateMethod == null) {
                for (String n : new String[] { "markBlockForUpdate", "h" }) {
                    try {
                        Method m = world
                            .getClass()
                            .getMethod(n, int.class, int.class, int.class);
                        if (m.getReturnType() == void.class) {
                            markBlockForUpdateMethod = m;
                            System.out.println(
                                "[OVM] invokeSetBlock: markBlockForUpdate='" +
                                    n +
                                    "'"
                            );
                            break;
                        }
                    } catch (Exception ignored) {}
                }
            }
            if (notifyBlockChangeMethod == null) {
                for (String n : new String[] { "notifyBlockChange", "f" }) {
                    try {
                        Method m = world
                            .getClass()
                            .getMethod(
                                n,
                                int.class,
                                int.class,
                                int.class,
                                int.class
                            );
                        if (m.getReturnType() == void.class) {
                            notifyBlockChangeMethod = m;
                            System.out.println(
                                "[OVM] invokeSetBlock: notifyBlockChange='" +
                                    n +
                                    "'"
                            );
                            break;
                        }
                    } catch (Exception ignored) {}
                }
            }
            if (setBlockDirectMethod == null) {
                // Last resort: setBlockWithNotify does setBlock+notifyBlockChange in one call
                for (String n : new String[] { "setBlockWithNotify", "c" }) {
                    try {
                        setBlockDirectMethod = world
                            .getClass()
                            .getMethod(
                                n,
                                int.class,
                                int.class,
                                int.class,
                                int.class
                            );
                        System.out.println(
                            "[OVM] invokeSetBlock: fallback setBlockWithNotify='" +
                                n +
                                "'"
                        );
                        break;
                    } catch (Exception ignored) {}
                }
            }
            if (setBlockDirectMethod == null) {
                System.out.println("[OVM] invokeSetBlock: NO METHOD FOUND");
                return;
            }
            Object result = setBlockDirectMethod.invoke(world, x, y, z, id);
            if (markBlockForUpdateMethod != null) {
                markBlockForUpdateMethod.invoke(world, x, y, z);
                // Also mark face-neighbors so attached blocks (torches, etc) update visually
                markBlockForUpdateMethod.invoke(world, x + 1, y, z);
                markBlockForUpdateMethod.invoke(world, x - 1, y, z);
                markBlockForUpdateMethod.invoke(world, x, y + 1, z);
                markBlockForUpdateMethod.invoke(world, x, y - 1, z);
                markBlockForUpdateMethod.invoke(world, x, y, z + 1);
                markBlockForUpdateMethod.invoke(world, x, y, z - 1);
            }
            if (notifyBlockChangeMethod != null) notifyBlockChangeMethod.invoke(
                world,
                x,
                y,
                z,
                id
            );
            System.out.println(
                "[OVM] invokeSetBlock (" +
                    x +
                    "," +
                    y +
                    "," +
                    z +
                    ") id=" +
                    id +
                    " result=" +
                    result
            );
        } catch (Exception e) {
            System.out.println("[OVM] invokeSetBlock error: " + e);
            e.printStackTrace();
        }
    }

    private static Object[] blocksListCache = null;

    private static Object getBlockFromArray(int id) {
        try {
            if (blocksListCache == null) {
                for (String cname : new String[] {
                    "net.minecraft.block.Block",
                    "amq",
                }) {
                    try {
                        Class<?> blockClass = Class.forName(cname);
                        for (String fname : new String[] {
                            "blocksList",
                            "p",
                        }) {
                            try {
                                Field f = blockClass.getDeclaredField(fname);
                                f.setAccessible(true);
                                Object[] arr = (Object[]) f.get(null);
                                if (arr != null) {
                                    blocksListCache = arr;
                                    break;
                                }
                            } catch (Exception ignored) {}
                        }
                        if (blocksListCache != null) break;
                    } catch (Exception ignored) {}
                }
            }
            if (blocksListCache == null) {
                System.out.println(
                    "[OVM] getBlockFromArray: blocksList not found"
                );
                return null;
            }
            return (id >= 0 && id < blocksListCache.length)
                ? blocksListCache[id]
                : null;
        } catch (Exception e) {
            System.out.println("[OVM] getBlockFromArray error: " + e);
            return null;
        }
    }

    // EntityPlayer.canHarvestBlock(Block)
    private static Method canHarvestMethod = null;

    private static boolean invokeCanHarvest(Object player, Object block) {
        try {
            if (canHarvestMethod == null) {
                for (Method m : player.getClass().getMethods()) {
                    if (
                        m.getParameterTypes().length == 1 &&
                        (m.getName().equals("canHarvestBlock") ||
                            m.getName().length() == 1) &&
                        (m.getReturnType() == boolean.class ||
                            m.getReturnType() == Boolean.class)
                    ) {
                        try {
                            m.invoke(player, block);
                            canHarvestMethod = m;
                            break;
                        } catch (Exception ignored) {}
                    }
                }
            }
            if (
                canHarvestMethod != null
            ) return (Boolean) canHarvestMethod.invoke(player, block);
        } catch (Exception ignored) {}
        return true;
    }

    // ItemStack.damageItem(int, EntityLiving): 2 params, first is int
    private static Method damageItemMethod = null;

    private static void damageItemReflect(
        Object stack,
        int amount,
        Object player
    ) {
        try {
            if (damageItemMethod == null) {
                for (Method m : stack.getClass().getMethods()) {
                    Class<?>[] p = m.getParameterTypes();
                    if (
                        p.length == 2 &&
                        p[0] == int.class &&
                        !p[1].isPrimitive() &&
                        (m.getName().equals("damageItem") ||
                            m.getName().equals("a")) &&
                        (m.getReturnType() == void.class) &&
                        p[1].isAssignableFrom(player.getClass())
                    ) {
                        damageItemMethod = m;
                        System.out.println("[OVM] damageItem: resolved via strict match '" + m.getName() + "'");
                        break;
                    }
                }
                if (damageItemMethod == null) {
                    for (Method m : stack.getClass().getMethods()) {
                        Class<?>[] p = m.getParameterTypes();
                        if (
                            p.length == 2 &&
                            p[0] == int.class &&
                            !p[1].isPrimitive()
                        ) {
                            damageItemMethod = m;
                            System.out.println("[OVM] damageItem: resolved via loose match '" + m.getName() + "' params=(" + p[0].getSimpleName() + "," + p[1].getName() + ")");
                            break;
                        }
                    }
                }
                if (damageItemMethod == null) {
                    System.out.println("[OVM] damageItem: NO METHOD FOUND on " + stack.getClass().getName());
                }
            }
            if (damageItemMethod != null) damageItemMethod.invoke(stack, amount, player);
        } catch (Exception e) {
            System.out.println("[OVM] damageItemReflect error: " + e);
        }
    }

    private static boolean isItemStackDamageable(Object stack) {
        // yz (client): q()  — ur (server): f()   [SRG: func_77984_f]
        for (String n : new String[] { "isItemStackDamageable", "f", "q" }) {
            try {
                return (Boolean) stack.getClass().getMethod(n).invoke(stack);
            } catch (Exception ignored) {}
        }
        return false;
    }

    // EntityPlayer.getHeldItem():
    //   Server path: player.bJ (InventoryPlayer qw), then qw.g() = getCurrentItem() -> ur (ItemStack)
    //   Client path: try MCP getHeldItem() / bD() on player directly
    private static Method getHeldItemMethod = null;   // on player directly (client fallback)
    private static Field  inventoryField    = null;   // player.bJ / player.inventory -> qw
    private static Method inventoryGetCurrent = null; // qw.g() / qw.getCurrentItem() -> ur

    private static Object getHeldItem(Object player) {
        // Server path: player.bJ.g()
        if (inventoryField == null) {
            for (String n : new String[] { "inventory", "bJ" }) {
                try {
                    Field f = findField(player.getClass(), n);
                    inventoryField = f;
                    System.out.println("[OVM] getHeldItem: inventory field='" + n + "' type=" + f.getType().getName());
                    break;
                } catch (Exception ignored) {}
            }
        }
        if (inventoryField != null) {
            try {
                Object inv = inventoryField.get(player);
                if (inv != null) {
                    if (inventoryGetCurrent == null) {
                        for (String n : new String[] { "getCurrentItem", "g" }) {
                            try {
                                Method m = inv.getClass().getMethod(n);
                                Class<?> r = m.getReturnType();
                                if (r == void.class || r.isPrimitive()) continue;
                                inventoryGetCurrent = m;
                                System.out.println("[OVM] getHeldItem: inventory.getCurrentItem='" + n + "' returns=" + r.getName());
                                break;
                            } catch (Exception ignored) {}
                        }
                    }
                    if (inventoryGetCurrent != null) {
                        Object held = inventoryGetCurrent.invoke(inv);
                        return held;
                    }
                }
            } catch (Exception e) {
                System.out.println("[OVM] getHeldItem inventory path error: " + e);
            }
        }
        // Client fallback: player.getHeldItem() / bD()
        if (getHeldItemMethod == null) {
            for (String n : new String[] { "getHeldItem", "bD", "bE", "bF", "bC" }) {
                try {
                    Method m = player.getClass().getMethod(n);
                    Class<?> r = m.getReturnType();
                    if (r == void.class || r.isPrimitive()) continue;
                    getHeldItemMethod = m;
                    System.out.println("[OVM] getHeldItem: fallback method='" + n + "' returns=" + r.getName());
                    break;
                } catch (Exception ignored) {}
            }
            if (getHeldItemMethod == null) {
                System.out.println("[OVM] getHeldItem: NO METHOD FOUND on " + player.getClass().getName());
            }
        }
        try {
            return getHeldItemMethod != null ? getHeldItemMethod.invoke(player) : null;
        } catch (Exception e) {
            System.out.println("[OVM] getHeldItem invoke error: " + e);
            return null;
        }
    }

    // EntityPlayer.destroyCurrentEquippedItem() — destroys held item when durability hits 0
    private static Method destroyEquippedMethod = null;

    private static void destroyHeldItem(Object player) {
        if (destroyEquippedMethod == null) {
            for (String n : new String[] {
                "destroyCurrentEquippedItem",
                "bT",
                "bU",
                "bS",
            }) {
                try {
                    destroyEquippedMethod = player.getClass().getMethod(n);
                    System.out.println(
                        "[OVM] destroyHeldItem: method='" + n + "'"
                    );
                    break;
                } catch (Exception ignored) {}
            }
        }
        try {
            if (destroyEquippedMethod != null) destroyEquippedMethod.invoke(
                player
            );
        } catch (Exception ignored) {}
    }

    // Construct a new ItemStack(int itemId, int count, int damage)
    // Runtime yz(Class, int, int, int) — first Class arg is Forge coremods token, pass null
    private static Constructor<?> itemStackCtor = null;
    private static boolean itemStackCtorHasClass = false;

    private static Object makeItemStack(int itemId, int damage, int count) {
        try {
            if (itemStackCtor == null) {
                for (String cname : new String[] {
                    "net.minecraft.item.ItemStack",
                    "ur",   // server jar obf (try first — we run server-side)
                    "yz",   // client jar obf
                }) {
                    try {
                        Class<?> cls = Class.forName(cname);
                        // Try (int,int,int) first, then (Class,int,int,int)
                        try {
                            itemStackCtor = cls.getConstructor(
                                int.class,
                                int.class,
                                int.class
                            );
                            itemStackCtorHasClass = false;
                            System.out.println(
                                "[OVM] makeItemStack: class='" +
                                    cname +
                                    "' ctor=(int,int,int)"
                            );
                        } catch (Exception ignored) {
                            itemStackCtor = cls.getConstructor(
                                Class.class,
                                int.class,
                                int.class,
                                int.class
                            );
                            itemStackCtorHasClass = true;
                            System.out.println(
                                "[OVM] makeItemStack: class='" +
                                    cname +
                                    "' ctor=(Class,int,int,int)"
                            );
                        }
                        break;
                    } catch (Exception ignored) {}
                }
            }
            if (itemStackCtor == null) {
                System.out.println("[OVM] makeItemStack: no ctor found");
                return null;
            }
            Object stack;
            if (itemStackCtorHasClass) {
                // (Class, itemId, count, damage) — note arg order: id, count, damage
                stack = itemStackCtor.newInstance((Object) null, itemId, count, damage);
            } else {
                // (itemId, count, damage)
                stack = itemStackCtor.newInstance(itemId, count, damage);
            }
            // Verify the stack has the right values — this catches arg-order bugs
            resolveStackFields(stack);
            int gotId    = getStackItemId(stack);
            int gotSize  = getStackSize(stack);
            int gotDmg   = getStackDamage(stack);
            System.out.println("[OVM] makeItemStack: want id=" + itemId + " count=" + count + " dmg=" + damage
                + " => got id=" + gotId + " count=" + gotSize + " dmg=" + gotDmg);
            return stack;
        } catch (Exception e) {
            System.out.println("[OVM] makeItemStack error: " + e);
            return null;
        }
    }

    // EntityItem.getEntityItem(): no-arg, returns ItemStack
    private static Method getEntityItemMethod = null;

    private static Object getEntityItemStack(Object entityItem) {
        try {
            if (getEntityItemMethod == null) {
                for (Method m : entityItem.getClass().getMethods()) {
                    if (
                        m.getParameterTypes().length == 0 &&
                        (m.getName().equals("getEntityItem") ||
                            m.getName().equals("c"))
                    ) {
                        getEntityItemMethod = m;
                        break;
                    }
                }
            }
            return getEntityItemMethod != null
                ? getEntityItemMethod.invoke(entityItem)
                : null;
        } catch (Exception e) {
            return null;
        }
    }

    // World.spawnEntityInWorld(Entity)
    private static Constructor<?> entityItemCtor = null;
    private static Method spawnEntityMethod = null;

    private static void spawnItemReflect(
        Object world,
        double x,
        double y,
        double z,
        Object stack
    ) {
        try {
            if (entityItemCtor == null) {
                Class<?> eiClass = null;
                for (String cname : new String[] {
                    "net.minecraft.entity.item.EntityItem",
                    "px",
                }) {
                    try {
                        eiClass = Class.forName(cname);
                        break;
                    } catch (Exception ignored) {}
                }
                if (eiClass == null) { System.out.println("[OVM] spawnItem: EntityItem class not found"); return; }
                Class<?> worldClass = world.getClass();
                while (worldClass != null) {
                    try {
                        entityItemCtor = eiClass.getConstructor(
                            worldClass,
                            double.class,
                            double.class,
                            double.class,
                            stack.getClass()
                        );
                        System.out.println("[OVM] spawnItem: ctor found worldClass=" + worldClass.getName() + " stackClass=" + stack.getClass().getName());
                        break;
                    } catch (Exception ignored) {
                        worldClass = worldClass.getSuperclass();
                    }
                }
                if (entityItemCtor == null) System.out.println("[OVM] spawnItem: ctor NOT FOUND for stackClass=" + stack.getClass().getName());
            }
            if (entityItemCtor == null) return;
            Object ei = entityItemCtor.newInstance(world, x, y, z, stack);
            if (spawnEntityMethod == null) {
                // MCP: spawnEntityInWorld, obf: d (yc.d(lq)Z per packaged.srg func_72838_d)
                // Must accept ei (EntityItem/px which extends Entity/lq)
                for (String n : new String[] { "spawnEntityInWorld", "d" }) {
                    for (Method m : world.getClass().getMethods()) {
                        if (m.getName().equals(n) && m.getParameterTypes().length == 1
                                && m.getParameterTypes()[0].isAssignableFrom(ei.getClass())) {
                            spawnEntityMethod = m;
                            System.out.println("[OVM] spawnItem: spawnEntity='" + n + "' param=" + m.getParameterTypes()[0].getName());
                            break;
                        }
                    }
                    if (spawnEntityMethod != null) break;
                }
            }
            if (spawnEntityMethod == null) { System.out.println("[OVM] spawnItem: no spawn method found"); return; }
            spawnEntityMethod.invoke(world, ei);
        } catch (Exception e) {
            System.out.println("[OVM] spawnItemReflect error: " + e);
            e.printStackTrace();
        }
    }

    private static int getIntField(Object obj, String name) {
        // Try public field first, then declared (obf) up the hierarchy
        try {
            return obj.getClass().getField(name).getInt(obj);
        } catch (Exception ignored) {}
        Class<?> c = obj.getClass();
        while (c != null) {
            try {
                Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                return f.getInt(obj);
            } catch (Exception ignored) {}
            c = c.getSuperclass();
        }
        return 0;
    }

    private static double getDoubleField(Object obj, String name) {
        // Try public field first, then declared (obf) up the hierarchy
        try {
            return obj.getClass().getField(name).getDouble(obj);
        } catch (Exception ignored) {}
        Class<?> c = obj.getClass();
        while (c != null) {
            try {
                Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                return f.getDouble(obj);
            } catch (Exception ignored) {}
            c = c.getSuperclass();
        }
        return 0.0;
    }

    /** Get player position. posX/Y/Z are on EntityPlayer (qx): obf bT, bU, bV. */
    private static double[] getPlayerPos(Object player) {
        // Try MCP names first (work in dev), then known obf names for 1.4.7
        for (String[] names : new String[][] {
            { "posX", "posY", "posZ" },
            { "bT", "bU", "bV" },
        }) {
            try {
                double x = getDoubleField(player, names[0]);
                double y = getDoubleField(player, names[1]);
                double z = getDoubleField(player, names[2]);
                if (x != 0.0 || y != 0.0 || z != 0.0) {
                    System.out.println(
                        "[OVM] getPlayerPos: (" +
                            x +
                            "," +
                            y +
                            "," +
                            z +
                            ") via " +
                            names[0]
                    );
                    return new double[] { x, y, z };
                }
            } catch (Exception ignored) {}
        }
        System.out.println("[OVM] getPlayerPos: failed, defaulting");
        return new double[] { 0, 64, 0 };
    }

    private static long coordKey(int x, int y, int z) {
        return (
            ((long) (x + 30000)) * 60001L * 512L +
            ((long) (y + 256)) * 60001L +
            (z + 30000)
        );
    }
}
