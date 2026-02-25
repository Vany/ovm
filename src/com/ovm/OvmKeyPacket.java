package com.ovm;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;

/**
 * Builds and sends the veinmine key-state packet via reflection.
 * Packet250CustomPayload and Packet base class are NOT referenced directly —
 * they would cause NoClassDefFoundError when any class importing them is loaded.
 *
 * Channel: "ovm", payload: 1 byte (1=key down, 0=key up).
 */
public class OvmKeyPacket {

    public static final String CHANNEL = "ovm";

    private static Constructor<?> pkt250Ctor = null;
    private static Method sendMethod = null;

    /** Sends a key-state packet to the server via reflection. */
    public static void send(boolean keyDown) {
        try {
            Object pkt = makePkt250(CHANNEL, new byte[]{(byte)(keyDown ? 1 : 0)});
            if (pkt == null) return;
            sendToServer(pkt);
        } catch (Exception e) {
            System.out.println("[OVM] OvmKeyPacket.send failed: " + e);
        }
    }

    private static Object makePkt250(String channel, byte[] data) throws Exception {
        if (pkt250Ctor == null) {
            Class<?> cls = Class.forName("net.minecraft.network.packet.Packet250CustomPayload");
            pkt250Ctor = cls.getConstructor(String.class, byte[].class);
        }
        return pkt250Ctor.newInstance(channel, data);
    }

    private static void sendToServer(Object pkt) throws Exception {
        if (sendMethod == null) {
            Class<?> pd = Class.forName("cpw.mods.fml.common.network.PacketDispatcher");
            // sendPacketToServer(Packet) — parameter type is the Packet base class
            for (Method m : pd.getMethods()) {
                if (m.getName().equals("sendPacketToServer") && m.getParameterTypes().length == 1) {
                    sendMethod = m;
                    break;
                }
            }
        }
        if (sendMethod != null) sendMethod.invoke(null, pkt);
    }
}
