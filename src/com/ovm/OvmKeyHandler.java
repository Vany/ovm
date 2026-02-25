package com.ovm;

import java.util.EnumSet;

import net.minecraft.client.settings.KeyBinding;

import cpw.mods.fml.client.registry.KeyBindingRegistry;
import cpw.mods.fml.common.TickType;
import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;

/**
 * Client-side key handler for the veinmine modifier key.
 * Does NOT import PacketDispatcher or any Packet class — those have
 * net.minecraft.network.packet.Packet in their signatures which is
 * obfuscated at runtime and causes NoClassDefFoundError on class load.
 * Packet sending is delegated to OvmKeyPacket which uses reflection.
 */
@SideOnly(Side.CLIENT)
public class OvmKeyHandler extends KeyBindingRegistry.KeyHandler {

    public static KeyBinding keyVeinMine;

    public OvmKeyHandler() {
        super(
            new KeyBinding[]{ buildKeyBinding() },
            new boolean[]{ false }  // no repeat
        );
    }

    private static KeyBinding buildKeyBinding() {
        keyVeinMine = new KeyBinding("key.ovm.veinmine", OvmConfig.activationKey);
        return keyVeinMine;
    }

    @Override
    public void keyDown(EnumSet<TickType> types, KeyBinding kb, boolean tickEnd, boolean isRepeat) {
        if (isRepeat) return;
        OvmKeyPacket.send(true);
    }

    @Override
    public void keyUp(EnumSet<TickType> types, KeyBinding kb, boolean tickEnd) {
        OvmKeyPacket.send(false);
    }

    @Override
    public EnumSet<TickType> ticks() {
        return EnumSet.of(TickType.CLIENT);
    }

    @Override
    public String getLabel() {
        return "OvmKeyHandler";
    }
}
