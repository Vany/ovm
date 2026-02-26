package com.ovm;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;

/**
 * Cached accessors for Minecraft objects via reflection.
 * Separates "how to call Minecraft" from game logic.
 * All lookups cached after first successful resolution.
 */
public class McAccessor {

    // -----------------------------------------------------------------------
    // Minecraft instance (client-only)
    // -----------------------------------------------------------------------

    private static Object cachedMc;

    /** Get the Minecraft singleton. Returns null on server side or before init. */
    public static Object getMc() {
        if (cachedMc != null) return cachedMc;
        try {
            Class<?> mcClass = Class.forName("net.minecraft.client.Minecraft");
            cachedMc = Reflect.invokeStatic(mcClass, Object.class, "getMinecraft", "x");
            if (cachedMc == null)
                cachedMc = Reflect.getStaticField(mcClass, Object.class, "theMinecraft", "P");
        } catch (Exception ignored) {}
        return cachedMc;
    }

    // -----------------------------------------------------------------------
    // World accessors (cached method lookup)
    // -----------------------------------------------------------------------

    private static Method getBlockIdMethod;

    public static int getBlockId(Object world, int x, int y, int z) {
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

    private static Method getBlockMetaMethod;

    public static int getBlockMeta(Object world, int x, int y, int z) {
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

    private static Method setBlockMethod;
    private static Method markBlockForUpdateMethod;
    private static Method notifyBlockChangeMethod;

    public static void setBlock(Object world, int x, int y, int z, int id) {
        try {
            if (setBlockMethod == null) {
                for (String n : new String[]{ "setBlock", "b" }) {
                    try {
                        Method m = world.getClass().getMethod(n, int.class, int.class, int.class, int.class);
                        if (m.getReturnType() == boolean.class) { setBlockMethod = m; break; }
                    } catch (Exception ignored) {}
                }
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
            System.out.println("[OVM] setBlock error: " + e);
        }
    }

    // -----------------------------------------------------------------------
    // Block registry
    // -----------------------------------------------------------------------

    private static Object[] blocksListCache;

    public static Object getBlock(int id) {
        try {
            if (blocksListCache == null) {
                for (String cname : new String[]{ "net.minecraft.block.Block", "amq" }) {
                    try {
                        Class<?> cls = Class.forName(cname);
                        Object[] arr = Reflect.getStaticField(cls, Object[].class, "blocksList", "p");
                        if (arr != null) { blocksListCache = arr; break; }
                    } catch (Exception ignored) {}
                }
            }
            return (blocksListCache != null && id >= 0 && id < blocksListCache.length)
                ? blocksListCache[id] : null;
        } catch (Exception e) { return null; }
    }

    // -----------------------------------------------------------------------
    // Player helpers
    // -----------------------------------------------------------------------

    private static Field inventoryField;
    private static Method inventoryGetCurrent;
    private static Method getHeldItemMethod;

    public static Object getHeldItem(Object player) {
        if (inventoryField == null) {
            for (String n : new String[]{ "inventory", "bJ" }) {
                try { inventoryField = Reflect.findField(player.getClass(), n); break; }
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

    /** Get the player's inventory object (cached). */
    public static Object getInventory(Object player) {
        if (inventoryField == null) {
            getHeldItem(player); // resolves inventoryField as side effect
        }
        try { return inventoryField != null ? inventoryField.get(player) : null; }
        catch (Exception e) { return null; }
    }

    // -----------------------------------------------------------------------
    // ItemStack construction and field resolution
    // -----------------------------------------------------------------------

    private static Constructor<?> itemStackCtor;
    private static boolean itemStackCtorHasClass;

    public static Object makeItemStack(int itemId, int damage, int count) {
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

    // Per-class cache: [itemId, stackSize, itemDamage]
    private static final Map<String, Field[]> stackFieldCache = new HashMap<String, Field[]>();

    /** Resolve ItemStack fields [itemId, stackSize, itemDamage] for the given stack instance. */
    public static Field[] resolveStackFields(Object stack) {
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

        result = new Field[3];
        stackFieldCache.put(cls, result);
        return result;
    }

    private static Field[] tryStackSchema(Class<?> cls, String idName, String sizeName, String damageName) {
        Field[] result = new Field[3];
        try { result[0] = Reflect.findIntField(cls, idName);    } catch (Exception ignored) {}
        try { result[1] = Reflect.findIntField(cls, sizeName);  } catch (Exception ignored) {}
        try { result[2] = Reflect.findIntField(cls, damageName);} catch (Exception ignored) {}
        return (result[0] != null && result[1] != null && result[2] != null) ? result : null;
    }

    public static int getStackSize(Object stack) {
        Field[] f = resolveStackFields(stack);
        try { return f[1] != null ? f[1].getInt(stack) : 0; } catch (Exception e) { return 0; }
    }

    public static int getStackItemId(Object stack) {
        Field[] f = resolveStackFields(stack);
        try { return f[0] != null ? f[0].getInt(stack) : 0; } catch (Exception e) { return 0; }
    }

    public static int getStackDamage(Object stack) {
        Field[] f = resolveStackFields(stack);
        try { return f[2] != null ? f[2].getInt(stack) : 0; } catch (Exception e) { return 0; }
    }

    // -----------------------------------------------------------------------
    // Item spawn
    // -----------------------------------------------------------------------

    private static Constructor<?> entityItemCtor;
    private static Method spawnEntityMethod;

    public static void spawnItem(Object world, Object player, Object stack) {
        try {
            double px = Reflect.getField(player, double.class, "posX", "bT");
            double py = Reflect.getField(player, double.class, "posY", "bU");
            double pz = Reflect.getField(player, double.class, "posZ", "bV");

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
            System.out.println("[OVM] spawnItem error: " + e);
        }
    }

    // -----------------------------------------------------------------------
    // Inventory add
    // -----------------------------------------------------------------------

    private static Method addToInventoryMethod;

    /** Add stack to player inventory. Returns leftover count (0 if fully added). */
    public static int addToInventory(Object player, Object stack) {
        try {
            Object inv = getInventory(player);
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
}
