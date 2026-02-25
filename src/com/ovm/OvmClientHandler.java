package com.ovm;

import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;
import net.minecraftforge.event.ForgeSubscribe;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import org.lwjgl.input.Keyboard;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * Client-side event listener.
 * Key state polled via Keyboard.isKeyDown in onTick (game thread, after display init).
 * On macOS, KEY_GRAVE (41) reports as keycode 0 due to LWJGL 2 bug — we check both.
 */
@SideOnly(Side.CLIENT)
public class OvmClientHandler {

    static volatile boolean activationKeyHeld = false;

    static int pendingX = Integer.MIN_VALUE;
    static int pendingY = Integer.MIN_VALUE;
    static int pendingZ = Integer.MIN_VALUE;
    static int pendingBlockId = 0;

    @ForgeSubscribe
    public void onPlayerInteract(PlayerInteractEvent event) {
        if (event.action != PlayerInteractEvent.Action.LEFT_CLICK_BLOCK) return;

        boolean active = activationKeyHeld;
        System.out.println("[OVM] client: interact active=" + active + " at (" + event.x + "," + event.y + "," + event.z + ")");
        if (!active) {
            pendingX = Integer.MIN_VALUE;
            return;
        }

        if (event.x != pendingX || event.y != pendingY || event.z != pendingZ) {
            // New block targeted — capture its id immediately and send.
            // This handles both normal breaks (onTick will see air and send) and
            // instant-break tools where the block may not become air between ticks.
            Object mc = getMc();
            Object world = mc != null ? getField(mc, "theWorld", "e") : null;
            int bid = world != null ? invokeGetBlockId(world, event.x, event.y, event.z) : 0;
            pendingX = event.x;
            pendingY = event.y;
            pendingZ = event.z;
            pendingBlockId = bid;
            if (bid != 0) {
                // Instant-break path: send now, disable onTick to avoid double-send.
                pendingX = Integer.MIN_VALUE;
                pendingBlockId = 0;
                System.out.println("[OVM] client: block broke id=" + bid + ", sending packet (" + event.x + "," + event.y + "," + event.z + ")");
                sendPacket(event.x, event.y, event.z, bid);
            }
        }
        System.out.println("[OVM] client: pending veinmine set (" + pendingX + "," + pendingY + "," + pendingZ + ")");
    }

    /** Called each CLIENT tick from VersionChatHandler (game thread). */
    static void onTick() {
        boolean keyNow = isActivationKeyDown();
        if (keyNow != activationKeyHeld) {
            activationKeyHeld = keyNow;
            System.out.println("[OVM] activationKey " + (keyNow ? "DOWN" : "UP"));
        }

        if (pendingX == Integer.MIN_VALUE) return;

        if (!activationKeyHeld) {
            System.out.println("[OVM] client: pending cancelled (key released)");
            pendingX = Integer.MIN_VALUE;
            return;
        }

        Object mc = getMc();
        if (mc == null) return;

        Object world = getField(mc, "theWorld", "e");
        if (world == null) return;

        // Fallback for cases where onPlayerInteract fired before the world was ready.
        int blockId = invokeGetBlockId(world, pendingX, pendingY, pendingZ);
        if (blockId != 0) {
            pendingBlockId = blockId;
        } else if (pendingBlockId != 0) {
            int x = pendingX, y = pendingY, z = pendingZ, bid = pendingBlockId;
            pendingX = Integer.MIN_VALUE;
            pendingBlockId = 0;
            System.out.println("[OVM] client: block broke id=" + bid + ", sending packet (" + x + "," + y + "," + z + ")");
            sendPacket(x, y, z, bid);
        }
        // else: block is already air and pendingBlockId is 0 — nothing to do, clear pending
        else { pendingX = Integer.MIN_VALUE; }
    }

    /**
     * Check if the activation key is currently held.
     * KEY_GRAVE (41) reports as keycode 0 on macOS due to LWJGL 2 bug, so we check both.
     */
    private static boolean isActivationKeyDown() {
        int key = OvmConfig.activationKey;
        if (Keyboard.isKeyDown(key)) return true;
        // macOS: KEY_GRAVE (41) comes through as keycode 0
        if (key == 41 && Keyboard.isKeyDown(0)) return true;
        return false;
    }

    /** Called from OvmMod.initClient() via reflection. */
    static void registerActivationKey(int keyCode) {
        System.out.println("[OVM] Activation key configured: keycode=" + keyCode
            + " name=" + Keyboard.getKeyName(keyCode)
            + " (macOS grave workaround: " + (keyCode == 41) + ")");
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
}
