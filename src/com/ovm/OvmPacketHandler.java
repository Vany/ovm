package com.ovm;

import cpw.mods.fml.common.network.IPacketHandler;
import cpw.mods.fml.common.network.Player;
import net.minecraft.network.INetworkManager;
import net.minecraft.network.packet.Packet250CustomPayload;
import java.io.DataInputStream;
import java.io.ByteArrayInputStream;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;

/**
 * Server-side packet handler.
 * Receives OVM|VM packets from clients and triggers VeinMiner.
 * Packet format: int x, int y, int z (12 bytes).
 *
 * Implements IPacketHandler via dynamic proxy to avoid AbstractMethodError
 * caused by obfuscated Minecraft type signatures (INetworkManager -> ce,
 * Packet250CustomPayload -> di) not matching our compiled descriptor.
 */
public class OvmPacketHandler implements IPacketHandler {

    @Override
    public void onPacketData(INetworkManager manager, Packet250CustomPayload packet, Player player) {
        handlePacket(packet, player);
    }

    /**
     * Returns a dynamic proxy implementing IPacketHandler whose onPacketData
     * accepts any Object types, bypassing the obfuscated-signature mismatch.
     */
    public static Object createProxy() {
        try {
            Class<?> iph = Class.forName("cpw.mods.fml.common.network.IPacketHandler");
            return java.lang.reflect.Proxy.newProxyInstance(
                iph.getClassLoader(),
                new Class<?>[]{ iph },
                new InvocationHandler() {
                    @Override
                    public Object invoke(Object proxy, Method method, Object[] args) {
                        if ("onPacketData".equals(method.getName()) && args != null && args.length == 3) {
                            handlePacket(args[1], args[2]);
                        }
                        return null;
                    }
                }
            );
        } catch (Exception e) {
            System.out.println("[OVM] createProxy error: " + e);
            return new OvmPacketHandler();
        }
    }

    /** Extract data from packet and call VeinMiner. Works with obfuscated or MCP types. */
    private static void handlePacket(Object packet, Object player) {
        System.out.println("[OVM] handlePacket called packet=" + (packet == null ? "null" : packet.getClass().getName()) + " player=" + (player == null ? "null" : player.getClass().getName()));
        try {
            // Read channel field: MCP="channel", obf="a"
            String channel = null;
            for (String fname : new String[]{"channel", "a"}) {
                try { channel = (String) packet.getClass().getField(fname).get(packet); if (channel != null) break; }
                catch (Exception ignored) {}
            }
            System.out.println("[OVM] packet channel=" + channel);
            if (!OvmMod.CHANNEL.equals(channel)) {
                System.out.println("[OVM] channel mismatch, expected=" + OvmMod.CHANNEL);
                return;
            }

            // Read data field: MCP="data", obf="c"
            byte[] data = null;
            for (String fname : new String[]{"data", "c"}) {
                try { Object v = packet.getClass().getField(fname).get(packet); if (v instanceof byte[]) { data = (byte[]) v; break; } }
                catch (Exception ignored) {}
            }
            System.out.println("[OVM] packet data=" + (data == null ? "null" : data.length + " bytes"));
            if (data == null) { System.out.println("[OVM] data is null"); return; }

            DataInputStream in = new DataInputStream(new ByteArrayInputStream(data));
            int x = in.readInt();
            int y = in.readInt();
            int z = in.readInt();
            int originBlockId = (data.length >= 16) ? in.readInt() : 0;
            System.out.println("[OVM] Packet received x=" + x + " y=" + y + " z=" + z + " originBlockId=" + originBlockId + " player=" + player.getClass().getName());
            VeinMiner.veinmine((Player) player, x, y, z, originBlockId);
        } catch (Exception e) {
            System.out.println("[OVM] Packet error: " + e);
            e.printStackTrace();
        }
    }
}
