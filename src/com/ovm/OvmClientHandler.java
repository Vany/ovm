package com.ovm;

import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;
import net.minecraftforge.event.ForgeSubscribe;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * Client-side event listener.
 * On sneak + LEFT_CLICK_BLOCK: records target block.
 * On tick: when that block becomes air (i.e. player finished mining it), sends veinmine packet.
 */
@SideOnly(Side.CLIENT)
public class OvmClientHandler {

    // Pending veinmine target — set when sneak+click, cleared when block breaks or player moves away
    static int pendingX = Integer.MIN_VALUE;
    static int pendingY = Integer.MIN_VALUE;
    static int pendingZ = Integer.MIN_VALUE;

    @ForgeSubscribe
    public void onPlayerInteract(PlayerInteractEvent event) {
        if (event.action != PlayerInteractEvent.Action.LEFT_CLICK_BLOCK) return;

        Object localPlayer = getLocalPlayer();
        if (localPlayer == null) return;

        boolean sneaking = isSneaking(localPlayer);
        System.out.println("[OVM] client: interact sneak=" + sneaking + " at (" + event.x + "," + event.y + "," + event.z + ")");
        if (!sneaking) {
            // Clear pending if player stopped sneaking
            pendingX = Integer.MIN_VALUE;
            return;
        }

        // Record target — will send packet when block actually breaks
        pendingX = event.x;
        pendingY = event.y;
        pendingZ = event.z;
        pendingBlockId = 0; // will be filled in by onTick
        System.out.println("[OVM] client: pending veinmine set (" + pendingX + "," + pendingY + "," + pendingZ + ")");
    }

    /** Called each CLIENT tick. Checks if pending block became air → send packet. */
    // Block ID at the pending position, recorded before it breaks
    static int pendingBlockId = 0;

    static void onTick() {
        if (pendingX == Integer.MIN_VALUE) return;

        Object mc = getMc();
        if (mc == null) return;

        // Check if player is still sneaking — if not, cancel
        Object player = getField(mc, "thePlayer", "g");
        if (player == null) return;
        if (!isSneaking(player)) {
            System.out.println("[OVM] client: pending cancelled (not sneaking)");
            pendingX = Integer.MIN_VALUE;
            return;
        }

        // Check if the block at pending coords is now air
        Object world = getField(mc, "theWorld", "e");
        if (world == null) return;

        int blockId = invokeGetBlockId(world, pendingX, pendingY, pendingZ);
        if (blockId != 0) {
            // Block still there — update our recorded ID (in case it changed)
            pendingBlockId = blockId;
        } else if (pendingBlockId != 0) {
            // Block just became air — send veinmine packet with the original block ID
            int x = pendingX, y = pendingY, z = pendingZ, bid = pendingBlockId;
            pendingX = Integer.MIN_VALUE;
            pendingBlockId = 0;
            System.out.println("[OVM] client: block broke id=" + bid + ", sending veinmine packet (" + x + "," + y + "," + z + ")");
            sendPacket(x, y, z, bid);
        }
    }

    private static void sendPacket(int x, int y, int z, int blockId) {
        try {
            ByteArrayOutputStream buf = new ByteArrayOutputStream(16);
            DataOutputStream out = new DataOutputStream(buf);
            out.writeInt(x);
            out.writeInt(y);
            out.writeInt(z);
            out.writeInt(blockId);
            byte[] data = buf.toByteArray();
            Class<?> pd = Class.forName("cpw.mods.fml.common.network.PacketDispatcher");
            Method getPacket = pd.getMethod("getPacket", String.class, byte[].class);
            Object packet = getPacket.invoke(null, OvmMod.CHANNEL, data);
            Method send = pd.getMethod("sendPacketToServer", packet.getClass().getSuperclass());
            send.invoke(null, packet);
            System.out.println("[OVM] Sent veinmine packet x=" + x + " y=" + y + " z=" + z);
        } catch (Exception e) {
            System.out.println("[OVM] Failed to send packet: " + e);
        }
    }

    private static int invokeGetBlockId(Object world, int x, int y, int z) {
        for (String n : new String[]{"getBlockId", "a"}) {
            try {
                return (Integer) world.getClass().getMethod(n, int.class, int.class, int.class).invoke(world, x, y, z);
            } catch (Exception ignored) {}
        }
        return -1;
    }

    private static Object cachedMc = null;
    static Object getMc() {
        if (cachedMc != null) return cachedMc;
        try {
            Class<?> mcClass = Class.forName("net.minecraft.client.Minecraft");
            for (String name : new String[]{"getMinecraft", "x"}) {
                try {
                    Method m = mcClass.getDeclaredMethod(name);
                    m.setAccessible(true);
                    cachedMc = m.invoke(null);
                    if (cachedMc != null) return cachedMc;
                } catch (Exception ignored) {}
            }
            for (String name : new String[]{"theMinecraft", "P"}) {
                try {
                    Field f = mcClass.getDeclaredField(name);
                    f.setAccessible(true);
                    cachedMc = f.get(null);
                    if (cachedMc != null) return cachedMc;
                } catch (Exception ignored) {}
            }
        } catch (Exception e) {
            System.out.println("[OVM] getMc error: " + e);
        }
        return null;
    }

    static Object getLocalPlayer() {
        Object mc = getMc();
        if (mc == null) return null;
        return getField(mc, "thePlayer", "g");
    }

    private static Object getField(Object obj, String mcpName, String obfName) {
        for (String name : new String[]{mcpName, obfName}) {
            try {
                Field f = obj.getClass().getField(name);
                return f.get(obj);
            } catch (Exception ignored) {}
        }
        return null;
    }

    static boolean isSneaking(Object player) {
        for (String name : new String[]{"ah", "isSneaking"}) {
            try {
                return (Boolean) player.getClass().getMethod(name).invoke(player);
            } catch (NoSuchMethodException ignored) {
            } catch (Exception e) {
                System.out.println("[OVM] isSneaking '" + name + "' error: " + e);
            }
        }
        return false;
    }
}
