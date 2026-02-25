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
 * All Minecraft types (World, EntityPlayer, Block, ItemStack, etc.) are accessed
 * only via reflection or through the event parameter itself, to avoid triggering
 * NoClassDefFoundError when EventBus scans this class's method signatures.
 *
 * The only Minecraft type that appears in a method signature is PlayerInteractEvent
 * (required by @ForgeSubscribe). All other methods use Object / primitives only.
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
    // Event handler — only Minecraft type in any method signature is
    // PlayerInteractEvent (required by @ForgeSubscribe).
    // -----------------------------------------------------------------------

    @ForgeSubscribe
    public void onPlayerInteract(PlayerInteractEvent event) {
        // All Minecraft access goes through the event fields and reflection.
        // We never call a helper that takes World/EntityPlayer/Block/ItemStack
        // as a typed parameter — those types must not appear in method signatures.

        try {
            if (event.action != PlayerInteractEvent.Action.LEFT_CLICK_BLOCK) return;

            // entityPlayer is declared on PlayerEvent (superclass), not PlayerInteractEvent.
            // Direct field access compiles to getfield on PlayerInteractEvent -> NoSuchFieldError
            // at runtime. Use reflection to walk the superclass chain instead.
            Object player = getField(event, "entityPlayer");
            if (player == null) return;
            Object world  = getField(player, "worldObj");
            if (world == null) return;

            boolean isRemote = getBoolean(world, "isRemote");
            if (isRemote) return;

            // Use sneak state as the veinmine modifier — checked server-side, no packet needed.
            boolean sneaking = (Boolean) player.getClass().getMethod("isSneaking").invoke(player);
            if (!sneaking) return;

            int ox = event.x, oy = event.y, oz = event.z;
            int originId = invokeGetBlockId(world, ox, oy, oz);
            if (!VEINMINE_IDS.contains(originId)) return;

            // Hunger check
            Object foodStats = invoke(player, "getFoodStats");
            if (foodStats == null) return;
            int foodLevel = invokeInt(foodStats, "getFoodLevel");
            if (foodLevel < 1) {
                invoke(player, "addChatMessage", String.class, "[OVM] Not enough hunger to veinmine.");
                return;
            }

            // Priority flood fill
            List<int[]> vein = buildVein(world, ox, oy, oz, originId, OvmConfig.maxBlocks);
            if (vein.isEmpty()) return;

            // Enable drop capture
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

                    // Damage tool
                    Object held = invoke(player, "getHeldItem");
                    if (held != null && isItemStackDamageable(held)) {
                        damageItemReflect(held, 1, player);
                        if (getInt(held, "stackSize") <= 0) {
                            invoke(player, "destroyCurrentEquippedItem");
                            break;
                        }
                    }
                }
            } finally {
                setCaptureDrops(player, false);
            }

            deliverCapturedDrops(world, player);
            clearCapturedDrops(player);

            // Hunger deduction
            if (OvmConfig.hungerPerBlocks > 0 && minedCount > 0) {
                int pts = minedCount / OvmConfig.hungerPerBlocks;
                if (pts > 0) {
                    invoke(player, "addExhaustion", float.class, 4.0f * pts);
                }
            }

            System.out.println("[OVM] VeinMiner: mined " + minedCount + " blocks at ("
                    + ox + "," + oy + "," + oz + ")");

        } catch (Exception e) {
            System.out.println("[OVM] VeinMiner error: " + e);
        }
    }

    // -----------------------------------------------------------------------
    // Priority flood fill — only Object and primitives in signatures
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
    // Drop delivery — Object only in signatures
    // -----------------------------------------------------------------------

    private void deliverCapturedDrops(Object world, Object player) {
        ArrayList<Object> drops = getCapturedDrops(player);
        if (drops == null || drops.isEmpty()) return;
        for (Object ei : drops) {
            if (ei == null) continue;
            Object stack = getEntityItemStack(ei);
            if (stack == null || getInt(stack, "stackSize") <= 0) continue;
            if (OvmConfig.dropsToInventory) {
                boolean fitted = addToInventory(player, stack);
                if (!fitted || getInt(stack, "stackSize") > 0) {
                    double px = getDouble(player, "posX");
                    double py = getDouble(player, "posY");
                    double pz = getDouble(player, "posZ");
                    spawnItemReflect(world, px, py, pz, stack);
                }
            } else {
                double px = getDouble(ei, "posX");
                double py = getDouble(ei, "posY");
                double pz = getDouble(ei, "posZ");
                spawnItemReflect(world, px, py, pz, stack);
            }
        }
    }

    // -----------------------------------------------------------------------
    // Reflection helpers — no Minecraft types in method signatures
    // -----------------------------------------------------------------------

    private static Method getBlockIdMethod = null;
    private static int invokeGetBlockId(Object world, int x, int y, int z) {
        try {
            if (getBlockIdMethod == null)
                getBlockIdMethod = world.getClass().getMethod("getBlockId", int.class, int.class, int.class);
            return (Integer) getBlockIdMethod.invoke(world, x, y, z);
        } catch (Exception e) { return 0; }
    }

    private static Method getBlockMetaMethod = null;
    private static int invokeGetBlockMeta(Object world, int x, int y, int z) {
        try {
            if (getBlockMetaMethod == null)
                getBlockMetaMethod = world.getClass().getMethod("getBlockMetadata", int.class, int.class, int.class);
            return (Integer) getBlockMetaMethod.invoke(world, x, y, z);
        } catch (Exception e) { return 0; }
    }

    private static Method setBlockMethod = null;
    private static void invokeSetBlock(Object world, int x, int y, int z, int id) {
        try {
            if (setBlockMethod == null)
                setBlockMethod = world.getClass().getMethod("setBlockWithNotify", int.class, int.class, int.class, int.class);
            setBlockMethod.invoke(world, x, y, z, id);
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

    private static Method canHarvestMethod = null;
    private static boolean invokeCanHarvest(Object player, Object block) {
        try {
            if (canHarvestMethod == null)
                canHarvestMethod = player.getClass().getMethod("canHarvestBlock", block.getClass().getSuperclass());
            return (Boolean) canHarvestMethod.invoke(player, block);
        } catch (Exception e) {
            // try with exact class
            try {
                for (Method m : player.getClass().getMethods()) {
                    if (m.getName().equals("canHarvestBlock") && m.getParameterTypes().length == 1) {
                        canHarvestMethod = m;
                        return (Boolean) m.invoke(player, block);
                    }
                }
            } catch (Exception e2) { /* skip */ }
            return true;
        }
    }

    private static Method harvestBlockMethod = null;
    private static void invokeHarvestBlock(Object block, Object world, Object player,
                                            int x, int y, int z, int meta) {
        try {
            if (harvestBlockMethod == null) {
                for (Method m : block.getClass().getMethods()) {
                    if (m.getName().equals("harvestBlock") && m.getParameterTypes().length == 6) {
                        harvestBlockMethod = m;
                        break;
                    }
                }
            }
            if (harvestBlockMethod != null)
                harvestBlockMethod.invoke(block, world, player, x, y, z, meta);
        } catch (Exception e) { /* skip */ }
    }

    private static Method damageItemMethod = null;
    private static void damageItemReflect(Object stack, int amount, Object player) {
        try {
            if (damageItemMethod == null) {
                for (Method m : stack.getClass().getMethods()) {
                    if (m.getName().equals("damageItem") && m.getParameterTypes().length == 2
                            && m.getParameterTypes()[0] == int.class) {
                        damageItemMethod = m;
                        break;
                    }
                }
            }
            if (damageItemMethod != null)
                damageItemMethod.invoke(stack, amount, player);
        } catch (Exception e) { /* skip */ }
    }

    private static boolean isItemStackDamageable(Object stack) {
        try {
            return (Boolean) stack.getClass().getMethod("isItemStackDamageable").invoke(stack);
        } catch (Exception e) { return false; }
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

    private static Method getEntityItemMethod = null;
    private static Object getEntityItemStack(Object entityItem) {
        try {
            if (getEntityItemMethod == null) {
                for (Method m : entityItem.getClass().getMethods()) {
                    if (m.getName().equals("getEntityItem") && m.getParameterTypes().length == 0) {
                        getEntityItemMethod = m;
                        break;
                    }
                }
            }
            return getEntityItemMethod != null ? getEntityItemMethod.invoke(entityItem) : null;
        } catch (Exception e) { return null; }
    }

    private static Method addToInventoryMethod = null;
    private static boolean addToInventory(Object player, Object stack) {
        try {
            Object inventory = getField(player, "inventory");
            if (inventory == null) return false;
            if (addToInventoryMethod == null)
                addToInventoryMethod = inventory.getClass().getMethod("addItemStackToInventory", stack.getClass());
            return (Boolean) addToInventoryMethod.invoke(inventory, stack);
        } catch (Exception e) { return false; }
    }

    private static Constructor<?> entityItemCtor = null;
    private static Method spawnEntityMethod = null;
    private static void spawnItemReflect(Object world, double x, double y, double z, Object stack) {
        try {
            if (entityItemCtor == null) {
                Class<?> eiClass = Class.forName("net.minecraft.entity.item.EntityItem");
                entityItemCtor = eiClass.getConstructor(
                        world.getClass().getSuperclass(), // World
                        double.class, double.class, double.class,
                        stack.getClass()); // ItemStack
            }
            Object ei = entityItemCtor.newInstance(world, x, y, z, stack);
            if (spawnEntityMethod == null) {
                for (Method m : world.getClass().getMethods()) {
                    if (m.getName().equals("spawnEntityInWorld") && m.getParameterTypes().length == 1) {
                        spawnEntityMethod = m;
                        break;
                    }
                }
            }
            if (spawnEntityMethod != null)
                spawnEntityMethod.invoke(world, ei);
        } catch (Exception e) { /* skip */ }
    }

    // Generic reflection utilities

    private static Object invoke(Object obj, String method) {
        try {
            return obj.getClass().getMethod(method).invoke(obj);
        } catch (Exception e) { return null; }
    }

    private static Object invoke(Object obj, String method, Class<?> argType, Object arg) {
        try {
            return obj.getClass().getMethod(method, argType).invoke(obj, arg);
        } catch (Exception e) { return null; }
    }

    private static int invokeInt(Object obj, String method) {
        try {
            return (Integer) obj.getClass().getMethod(method).invoke(obj);
        } catch (Exception e) { return 0; }
    }

    private static Object getField(Object obj, String name) {
        try {
            Field f = obj.getClass().getField(name);
            return f.get(obj);
        } catch (Exception e) { return null; }
    }

    private static boolean getBoolean(Object obj, String name) {
        try {
            return obj.getClass().getField(name).getBoolean(obj);
        } catch (Exception e) { return false; }
    }

    private static int getInt(Object obj, String name) {
        try {
            return obj.getClass().getField(name).getInt(obj);
        } catch (Exception e) { return 0; }
    }

    private static double getDouble(Object obj, String name) {
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
