package com.ovm;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.network.INetworkManager;
import net.minecraft.network.packet.Packet250CustomPayload;

import cpw.mods.fml.common.network.IPacketHandler;
import cpw.mods.fml.common.network.Player;

/**
 * Server-side handler for the "ovm" channel.
 * Tracks which players currently hold the veinmine key down.
 */
public class OvmPacketHandler implements IPacketHandler {

    // Thread-safe set of player usernames with key currently held
    private static final Set<String> activeVeinPlayers =
            Collections.synchronizedSet(new HashSet<String>());

    @Override
    public void onPacketData(INetworkManager manager, Packet250CustomPayload packet, Player player) {
        if (!OvmKeyPacket.CHANNEL.equals(packet.channel)) return;
        if (packet.data == null || packet.data.length < 1) return;
        if (!(player instanceof EntityPlayer)) return;

        String name = ((EntityPlayer) player).username;
        boolean keyDown = (packet.data[0] == 1);
        if (keyDown) {
            activeVeinPlayers.add(name);
        } else {
            activeVeinPlayers.remove(name);
        }
    }

    public static boolean isVeinKeyActive(EntityPlayer player) {
        return activeVeinPlayers.contains(player.username);
    }

    /** Called when a player disconnects to clean up state. */
    public static void onPlayerLeft(EntityPlayer player) {
        activeVeinPlayers.remove(player.username);
    }
}
