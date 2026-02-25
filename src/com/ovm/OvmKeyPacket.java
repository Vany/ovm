package com.ovm;

import net.minecraft.network.packet.Packet250CustomPayload;

/**
 * Custom packet: client -> server, signals veinmine key pressed or released.
 * Channel: "ovm"
 * Payload: 1 byte, 1 = key down, 0 = key up.
 */
public class OvmKeyPacket {

    public static final String CHANNEL = "ovm";

    public static Packet250CustomPayload make(boolean keyDown) {
        return new Packet250CustomPayload(CHANNEL, new byte[]{(byte)(keyDown ? 1 : 0)});
    }
}
