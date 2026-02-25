package com.ovm;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

import net.minecraft.entity.player.EntityPlayer;

/**
 * Server-side key-state tracker for vein mining.
 * Does NOT implement IPacketHandler directly — that interface has
 * Packet250CustomPayload in its signature which would cause
 * NoClassDefFoundError when this class is loaded.
 *
 * Instead, OvmMod registers a dynamic Proxy as the IPacketHandler,
 * which delegates to handlePacketData() here.
 */
public class OvmPacketHandler {

    private static final Set<String> activeVeinPlayers =
            Collections.synchronizedSet(new HashSet<String>());

    /**
     * Called by the dynamic proxy when a packet arrives on the "ovm" channel.
     * Parameters are Object to avoid any Minecraft class references in signature.
     *
     * @param packetObj  Packet250CustomPayload instance (as Object)
     * @param playerObj  Player instance (as Object, may be EntityPlayer)
     */
    public static void handlePacketData(Object packetObj, Object playerObj) {
        try {
            // Read channel field
            String channel = (String) packetObj.getClass().getField("channel").get(packetObj);
            if (!OvmKeyPacket.CHANNEL.equals(channel)) return;

            // Read data field
            byte[] data = (byte[]) packetObj.getClass().getField("data").get(packetObj);
            if (data == null || data.length < 1) return;

            // Get player username
            if (!(playerObj instanceof EntityPlayer)) return;
            String name = ((EntityPlayer) playerObj).username;

            boolean keyDown = (data[0] == 1);
            if (keyDown) {
                activeVeinPlayers.add(name);
            } else {
                activeVeinPlayers.remove(name);
            }
        } catch (Exception e) {
            System.out.println("[OVM] OvmPacketHandler error: " + e);
        }
    }

    public static boolean isVeinKeyActive(EntityPlayer player) {
        return activeVeinPlayers.contains(player.username);
    }

    public static void onPlayerLeft(EntityPlayer player) {
        activeVeinPlayers.remove(player.username);
    }
}
