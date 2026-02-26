package com.ovm;

import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;
import net.minecraftforge.event.ForgeSubscribe;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import org.lwjgl.input.Keyboard;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.lang.reflect.Method;

/**
 * Client-side event listener.
 * Key state polled via Keyboard.isKeyDown in onTick (game thread, after display init).
 * On macOS, KEY_GRAVE (41) reports as keycode 0 due to LWJGL 2 bug — we check both.
 *
 * Break detection: onPlayerInteract sets pending coords. onTick polls the block
 * each tick and sends the veinmine packet when it transitions to air.
 * For insta-break tools (shears on leaves), the block is already air when the
 * interact event fires — onTick pre-caches the block ID at the crosshair position
 * each tick so we have it available even after instant destruction.
 */
@SideOnly(Side.CLIENT)
public class OvmClientHandler {

    static volatile boolean activationKeyHeld = false;

    static int pendingX = Integer.MIN_VALUE;
    static int pendingY = Integer.MIN_VALUE;
    static int pendingZ = Integer.MIN_VALUE;
    static int pendingBlockId = 0;

    // Block ID at crosshair, captured each tick before any break event.
    // Used as fallback when getBlockId returns 0 at interact time (insta-break).
    private static int crosshairX, crosshairY, crosshairZ;
    private static int crosshairBlockId = 0;

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
            Object mc = McAccessor.getMc();
            Object world = mc != null ? Reflect.getField(mc, Object.class, "theWorld", "e") : null;
            int bid = world != null ? McAccessor.getBlockId(world, event.x, event.y, event.z) : 0;
            // Insta-break: block already air, use crosshair cache from last tick
            if (bid == 0 && event.x == crosshairX && event.y == crosshairY && event.z == crosshairZ)
                bid = crosshairBlockId;
            pendingX = event.x;
            pendingY = event.y;
            pendingZ = event.z;
            pendingBlockId = bid;
            System.out.println("[OVM] client: pending set id=" + bid + " at (" + event.x + "," + event.y + "," + event.z + ")");
        }
    }

    /** Called each CLIENT tick from ClientTickHandler (game thread). */
    static void onTick() {
        boolean keyNow = isActivationKeyDown();
        if (keyNow != activationKeyHeld) {
            activationKeyHeld = keyNow;
            System.out.println("[OVM] activationKey " + (keyNow ? "DOWN" : "UP"));
        }

        // Cache block at crosshair for insta-break detection
        updateCrosshair();

        if (pendingX == Integer.MIN_VALUE) return;

        if (!activationKeyHeld) {
            System.out.println("[OVM] client: pending cancelled (key released)");
            pendingX = Integer.MIN_VALUE;
            return;
        }

        Object mc = McAccessor.getMc();
        if (mc == null) return;

        Object world = Reflect.getField(mc, Object.class, "theWorld", "e");
        if (world == null) return;

        int blockId = McAccessor.getBlockId(world, pendingX, pendingY, pendingZ);
        if (blockId != 0) {
            pendingBlockId = blockId;
        } else if (pendingBlockId != 0) {
            int x = pendingX, y = pendingY, z = pendingZ, bid = pendingBlockId;
            pendingX = Integer.MIN_VALUE;
            pendingBlockId = 0;
            System.out.println("[OVM] client: block broke id=" + bid + ", sending packet (" + x + "," + y + "," + z + ")");
            sendPacket(x, y, z, bid);
        }
        else { pendingX = Integer.MIN_VALUE; }
    }

    /**
     * Cache the block ID at the player's crosshair (objectMouseOver).
     * This runs every tick before interact events, so for insta-break tools
     * we have the block ID from the previous tick even if the block is already gone.
     */
    private static void updateCrosshair() {
        Object mc = McAccessor.getMc();
        if (mc == null) return;
        // Minecraft.objectMouseOver → MovingObjectPosition (MCP field_71476_x) / obf "x"
        Object mop = Reflect.getField(mc, Object.class, "objectMouseOver", "x");
        if (mop == null) return;
        // MovingObjectPosition.typeOfHit → EnumMovingObjectType, 0=TILE
        Object typeOfHit = Reflect.getField(mop, Object.class, "typeOfHit", "a");
        if (typeOfHit == null) return;
        // Check it's a block hit (TILE), not entity. EnumMovingObjectType.TILE.ordinal() == 0
        if (typeOfHit instanceof Enum && ((Enum<?>) typeOfHit).ordinal() != 0) return;
        int bx = Reflect.getField(mop, int.class, "blockX", "b");
        int by = Reflect.getField(mop, int.class, "blockY", "c");
        int bz = Reflect.getField(mop, int.class, "blockZ", "d");
        Object world = Reflect.getField(mc, Object.class, "theWorld", "e");
        if (world == null) return;
        int bid = McAccessor.getBlockId(world, bx, by, bz);
        if (bid != 0) {
            crosshairX = bx;
            crosshairY = by;
            crosshairZ = bz;
            crosshairBlockId = bid;
        }
    }

    private static boolean isActivationKeyDown() {
        int key = OvmConfig.activationKey;
        if (Keyboard.isKeyDown(key)) return true;
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
}
