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

import net.minecraftforge.event.ForgeSubscribe;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;

/**
 * VeinMiner v0.3.0 — server-side chain-mine with priority flood fill.
 *
 * All Minecraft types are accessed only via reflection to avoid
 * NoClassDefFoundError when EventBus scans this class's method signatures.
 *
 * Obfuscated names sourced from forge/conf/packaged.srg (1.4.7-6.6.2.534).
 * Each helper tries the MCP name first, then the obfuscated name.
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

    // -----------------------------------------------------------------------
    // Event handler
    // -----------------------------------------------------------------------

    @ForgeSubscribe
    public void onPlayerInteract(PlayerInteractEvent event) {
        try {
            if (event.action != PlayerInteractEvent.Action.LEFT_CLICK_BLOCK) return;

            // entityPlayer declared on superclass PlayerEvent — use reflection
            Object player = getFieldByNames(event, "entityPlayer");
            if (player == null) return;

            // worldObj field: MCP="worldObj", obf="p"  (Entity.field_70170_p)
            Object world = getFieldByNames(player, "worldObj", "p");
            if (world == null) return;

            // isRemote field: MCP="isRemote", obf="I"  (World.field_72995_K)
            boolean isRemote = getBooleanByNames(world, "isRemote", "I");
            if (isRemote) return;

            // isSneaking: MCP="isSneaking", obf="ai"  (Entity.func_70051_ag)
            boolean sneaking = invokeBooleanByNames(player, "isSneaking", "ai");
            if (!sneaking) return;

            int ox = event.x, oy = event.y, oz = event.z;
            int originId = invokeGetBlockId(world, ox, oy, oz);
            if (!VEINMINE_IDS.contains(originId)) return;

            // Hunger check — getFoodStats: MCP="getFoodStats", obf="cc"
            Object foodStats = invokeByNames(player, "getFoodStats", "cc");
            if (foodStats == null) return;
            // getFoodLevel: MCP="getFoodLevel", obf="a" (returns int)
            int foodLevel = invokeIntByNames(foodStats, "getFoodLevel", "a");
            if (foodLevel < 1) {
                invokeWithStringArg(player, "addChatMessage", "[OVM] Not enough food to veinmine.");
                return;
            }

            List<int[]> vein = buildVein(world, ox, oy, oz, originId, OvmConfig.maxBlocks);
            if (vein.isEmpty()) return;

            setCaptureDrops(player, true);
            clearCapturedDrops(player);

            int minedCount = 0;
            try {
                for (int[] pos : vein) {
                    int bx = pos[0], by = pos[1], bz = pos[2];
                    int blockId = invokeGetBlockId(world, bx, by, bz);
                    if (blockId != originId) continue;

                    Object block = getBlockFromArray(blockId);
                    if (block == null) continue;
                    if (!invokeCanHarvest(player, block)) continue;

                    int meta = invokeGetBlockMeta(world, bx, by, bz);
                    invokeHarvestBlock(block, world, player, bx, by, bz, meta);
                    invokeSetBlock(world, bx, by, bz, 0);
                    minedCount++;

                    // Damage tool — getHeldItem: MCP="getHeldItem", obf="bD"
                    Object held = invokeByNames(player, "getHeldItem", "bD");
                    if (held != null && isItemStackDamageable(held)) {
                        damageItemReflect(held, 1, player);
                        if (getIntField(held, "stackSize") <= 0) {
                            // destroyCurrentEquippedItem: MCP name, obf="bT"
                            invokeByNames(player, "destroyCurrentEquippedItem", "bT");
                            break;
                        }
                    }
                }
            } finally {
                setCaptureDrops(player, false);
            }

            deliverCapturedDrops(world, player);
            clearCapturedDrops(player);

            // addExhaustion: MCP="addExhaustion", obf on FoodStats="a(F)"
            if (OvmConfig.hungerPerBlocks > 0 && minedCount > 0) {
                int pts = minedCount / OvmConfig.hungerPerBlocks;
                if (pts > 0 && foodStats != null) {
                    invokeFloatByNames(foodStats, "addExhaustion", "a", 4.0f * pts);
                }
            }

            System.out.println("[OVM] VeinMiner: mined " + minedCount + " blocks at ("
                    + ox + "," + oy + "," + oz + ")");

        } catch (Exception e) {
            System.out.println("[OVM] VeinMiner error: " + e);
            e.printStackTrace();
        }
    }

    // -----------------------------------------------------------------------
    // Priority flood fill
    // -----------------------------------------------------------------------

    private List<int[]> buildVein(Object world, int ox, int oy, int oz, int targetId, int maxCount) {
        List<int[]> result = new ArrayList<int[]>();
        PriorityQueue<long[]> pq = new PriorityQueue<long[]>(16, new Comparator<long[]>() {
            public int compare(long[] a, long[] b) { return Long.compare(a[0], b[0]); }
        });
        Set<Long> visited = new HashSet<Long>();

        visited.add(coordKey(ox, oy, oz));
        pq.add(new long[]{0L, ox, oy, oz});

        while (!pq.isEmpty() && result.size() < maxCount) {
            long[] e = pq.poll();
            int bx = (int)e[1], by = (int)e[2], bz = (int)e[3];
            if (invokeGetBlockId(world, bx, by, bz) != targetId) continue;
            result.add(new int[]{bx, by, bz});
            for (int[] d : NEIGHBORS) {
                int nx = bx+d[0], ny = by+d[1], nz = bz+d[2];
                long nk = coordKey(nx, ny, nz);
                if (!visited.contains(nk) && invokeGetBlockId(world, nx, ny, nz) == targetId) {
                    visited.add(nk);
                    long ddx = nx-ox, ddy = ny-oy, ddz = nz-oz;
                    pq.add(new long[]{ddx*ddx+ddy*ddy+ddz*ddz, nx, ny, nz});
                }
            }
        }
        return result;
    }

    // -----------------------------------------------------------------------
    // Drop delivery
    // -----------------------------------------------------------------------

    private void deliverCapturedDrops(Object world, Object player) {
        ArrayList<Object> drops = getCapturedDrops(player);
        if (drops == null || drops.isEmpty()) return;
        for (Object ei : drops) {
            if (ei == null) continue;
            Object stack = getEntityItemStack(ei);
            if (stack == null || getIntField(stack, "stackSize") <= 0) continue;
            if (OvmConfig.dropsToInventory) {
                boolean fitted = addToInventory(player, stack);
                if (!fitted || getIntField(stack, "stackSize") > 0) {
                    double px = getDoubleField(player, "posX");
                    double py = getDoubleField(player, "posY");
                    double pz = getDoubleField(player, "posZ");
                    spawnItemReflect(world, px, py, pz, stack);
                }
            } else {
                double px = getDoubleField(ei, "posX");
                double py = getDoubleField(ei, "posY");
                double pz = getDoubleField(ei, "posZ");
                spawnItemReflect(world, px, py, pz, stack);
            }
        }
    }

    // -----------------------------------------------------------------------
    // Reflection helpers with dual MCP/obfuscated name support
    // -----------------------------------------------------------------------

    /** Get field by trying multiple names (MCP first, then obfuscated). */
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

    private static boolean invokeBooleanByNames(Object obj, String... names) {
        for (String name : names) {
            try {
                return (Boolean) obj.getClass().getMethod(name).invoke(obj);
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

    private static void invokeFloatByNames(Object obj, String mcpName, String obfName, float arg) {
        for (String name : new String[]{mcpName, obfName}) {
            try {
                obj.getClass().getMethod(name, float.class).invoke(obj, arg);
                return;
            } catch (Exception ignored) {}
        }
    }

    private static void invokeWithStringArg(Object obj, String method, String arg) {
        try {
            obj.getClass().getMethod(method, String.class).invoke(obj, arg);
        } catch (Exception ignored) {}
    }

    // World.getBlockId(III): MCP="getBlockId", obf="a"
    private static Method getBlockIdMethod = null;
    private static int invokeGetBlockId(Object world, int x, int y, int z) {
        try {
            if (getBlockIdMethod == null) {
                for (String n : new String[]{"getBlockId", "a"}) {
                    try {
                        getBlockIdMethod = world.getClass().getMethod(n, int.class, int.class, int.class);
                        break;
                    } catch (Exception ignored) {}
                }
            }
            if (getBlockIdMethod == null) return 0;
            return (Integer) getBlockIdMethod.invoke(world, x, y, z);
        } catch (Exception e) { return 0; }
    }

    // World.getBlockMetadata(III): MCP="getBlockMetadata", obf="h"
    private static Method getBlockMetaMethod = null;
    private static int invokeGetBlockMeta(Object world, int x, int y, int z) {
        try {
            if (getBlockMetaMethod == null) {
                for (String n : new String[]{"getBlockMetadata", "h"}) {
                    try {
                        getBlockMetaMethod = world.getClass().getMethod(n, int.class, int.class, int.class);
                        break;
                    } catch (Exception ignored) {}
                }
            }
            if (getBlockMetaMethod == null) return 0;
            return (Integer) getBlockMetaMethod.invoke(world, x, y, z);
        } catch (Exception e) { return 0; }
    }

    // World.setBlockWithNotify(IIII): MCP="setBlockWithNotify", obf="c"
    private static Method setBlockMethod = null;
    private static void invokeSetBlock(Object world, int x, int y, int z, int id) {
        try {
            if (setBlockMethod == null) {
                for (String n : new String[]{"setBlockWithNotify", "c"}) {
                    try {
                        setBlockMethod = world.getClass().getMethod(n, int.class, int.class, int.class, int.class);
                        break;
                    } catch (Exception ignored) {}
                }
            }
            if (setBlockMethod != null) setBlockMethod.invoke(world, x, y, z, id);
        } catch (Exception e) { /* skip */ }
    }

    private static Object getBlockFromArray(int id) {
        try {
            Class<?> blockClass = Class.forName("net.minecraft.block.Block");
            Field f = blockClass.getField("blocksList");
            Object[] arr = (Object[]) f.get(null);
            return (id >= 0 && id < arr.length) ? arr[id] : null;
        } catch (Exception e) { return null; }
    }

    // EntityPlayer.canHarvestBlock(Block): scans by parameter count
    private static Method canHarvestMethod = null;
    private static boolean invokeCanHarvest(Object player, Object block) {
        try {
            if (canHarvestMethod == null) {
                for (Method m : player.getClass().getMethods()) {
                    if (m.getParameterTypes().length == 1 &&
                        (m.getName().equals("canHarvestBlock") || m.getName().length() == 1)) {
                        // verify it returns boolean and takes a Block-like type
                        if (m.getReturnType() == boolean.class || m.getReturnType() == Boolean.class) {
                            try {
                                m.invoke(player, block);
                                canHarvestMethod = m;
                                break;
                            } catch (Exception ignored) {}
                        }
                    }
                }
            }
            if (canHarvestMethod != null) return (Boolean) canHarvestMethod.invoke(player, block);
        } catch (Exception ignored) {}
        return true;
    }

    // Block.harvestBlock(World, EntityPlayer, III, int): 6 params
    private static Method harvestBlockMethod = null;
    private static void invokeHarvestBlock(Object block, Object world, Object player,
                                            int x, int y, int z, int meta) {
        try {
            if (harvestBlockMethod == null) {
                for (Method m : block.getClass().getMethods()) {
                    if (m.getParameterTypes().length == 6) {
                        harvestBlockMethod = m;
                        break;
                    }
                }
            }
            if (harvestBlockMethod != null)
                harvestBlockMethod.invoke(block, world, player, x, y, z, meta);
        } catch (Exception e) { /* skip */ }
    }

    // ItemStack.damageItem(int, EntityLiving): 2 params, first is int
    private static Method damageItemMethod = null;
    private static void damageItemReflect(Object stack, int amount, Object player) {
        try {
            if (damageItemMethod == null) {
                for (Method m : stack.getClass().getMethods()) {
                    if (m.getParameterTypes().length == 2 && m.getParameterTypes()[0] == int.class) {
                        damageItemMethod = m;
                        break;
                    }
                }
            }
            if (damageItemMethod != null) damageItemMethod.invoke(stack, amount, player);
        } catch (Exception e) { /* skip */ }
    }

    private static boolean isItemStackDamageable(Object stack) {
        try {
            for (String n : new String[]{"isItemStackDamageable", "q"}) {
                try {
                    return (Boolean) stack.getClass().getMethod(n).invoke(stack);
                } catch (Exception ignored) {}
            }
        } catch (Exception ignored) {}
        return false;
    }

    private static Field captureDropsField = null;
    private static void setCaptureDrops(Object player, boolean value) {
        try {
            if (captureDropsField == null)
                captureDropsField = player.getClass().getField("captureDrops");
            captureDropsField.setBoolean(player, value);
        } catch (Exception e) { /* skip */ }
    }

    private static Field capturedDropsField = null;
    @SuppressWarnings("unchecked")
    private static ArrayList<Object> getCapturedDrops(Object player) {
        try {
            if (capturedDropsField == null)
                capturedDropsField = player.getClass().getField("capturedDrops");
            return (ArrayList<Object>) capturedDropsField.get(player);
        } catch (Exception e) { return null; }
    }

    private static void clearCapturedDrops(Object player) {
        ArrayList<Object> drops = getCapturedDrops(player);
        if (drops != null) drops.clear();
    }

    // EntityItem.getEntityItem(): no-arg, returns ItemStack
    private static Method getEntityItemMethod = null;
    private static Object getEntityItemStack(Object entityItem) {
        try {
            if (getEntityItemMethod == null) {
                for (Method m : entityItem.getClass().getMethods()) {
                    if (m.getParameterTypes().length == 0 &&
                        (m.getName().equals("getEntityItem") || m.getName().equals("c"))) {
                        getEntityItemMethod = m;
                        break;
                    }
                }
            }
            return getEntityItemMethod != null ? getEntityItemMethod.invoke(entityItem) : null;
        } catch (Exception e) { return null; }
    }

    // InventoryPlayer.addItemStackToInventory(ItemStack)
    private static Method addToInventoryMethod = null;
    private static boolean addToInventory(Object player, Object stack) {
        try {
            // inventory field: MCP="inventory", obf="bJ"
            Object inventory = getFieldByNames(player, "inventory", "bJ");
            if (inventory == null) return false;
            if (addToInventoryMethod == null) {
                for (String n : new String[]{"addItemStackToInventory", "a"}) {
                    try {
                        addToInventoryMethod = inventory.getClass().getMethod(n, stack.getClass());
                        break;
                    } catch (Exception ignored) {}
                }
            }
            if (addToInventoryMethod == null) return false;
            return (Boolean) addToInventoryMethod.invoke(inventory, stack);
        } catch (Exception e) { return false; }
    }

    // World.spawnEntityInWorld(Entity): MCP="spawnEntityInWorld", obf="d"
    private static Constructor<?> entityItemCtor = null;
    private static Method spawnEntityMethod = null;
    private static void spawnItemReflect(Object world, double x, double y, double z, Object stack) {
        try {
            if (entityItemCtor == null) {
                Class<?> eiClass = Class.forName("net.minecraft.entity.item.EntityItem");
                // Walk up to find World superclass for constructor
                Class<?> worldClass = world.getClass();
                while (worldClass != null) {
                    try {
                        entityItemCtor = eiClass.getConstructor(
                                worldClass, double.class, double.class, double.class, stack.getClass());
                        break;
                    } catch (Exception ignored) { worldClass = worldClass.getSuperclass(); }
                }
            }
            if (entityItemCtor == null) return;
            Object ei = entityItemCtor.newInstance(world, x, y, z, stack);
            if (spawnEntityMethod == null) {
                for (String n : new String[]{"spawnEntityInWorld", "d"}) {
                    try {
                        spawnEntityMethod = world.getClass().getMethod(n, ei.getClass().getSuperclass().getSuperclass());
                        break;
                    } catch (Exception ignored) {}
                }
                if (spawnEntityMethod == null) {
                    for (Method m : world.getClass().getMethods()) {
                        if (m.getParameterTypes().length == 1 &&
                            (m.getName().equals("spawnEntityInWorld") || m.getName().equals("d"))) {
                            spawnEntityMethod = m; break;
                        }
                    }
                }
            }
            if (spawnEntityMethod != null) spawnEntityMethod.invoke(world, ei);
        } catch (Exception e) { /* skip */ }
    }

    private static Object getField(Object obj, String name) {
        try {
            Field f = obj.getClass().getField(name);
            return f.get(obj);
        } catch (Exception e) { return null; }
    }

    private static int getIntField(Object obj, String name) {
        try {
            return obj.getClass().getField(name).getInt(obj);
        } catch (Exception e) { return 0; }
    }

    private static double getDoubleField(Object obj, String name) {
        try {
            return obj.getClass().getField(name).getDouble(obj);
        } catch (Exception e) { return 0.0; }
    }

    private static long coordKey(int x, int y, int z) {
        return ((long)(x + 30000)) * 60001L * 512L
             + ((long)(y + 256))  * 60001L
             + (z + 30000);
    }
}
