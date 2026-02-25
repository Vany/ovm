package com.ovm;

import java.util.EnumSet;

import net.minecraft.client.settings.KeyBinding;

import cpw.mods.fml.client.registry.KeyBindingRegistry;
import cpw.mods.fml.common.TickType;
import cpw.mods.fml.common.network.PacketDispatcher;
import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;

/**
 * Client-side key handler for the veinmine modifier key.
 * Registered via KeyBindingRegistry.
 * Sends OvmKeyPacket to server when key is pressed or released.
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
        if (isRepeat) return;  // only send on initial press
        PacketDispatcher.sendPacketToServer(OvmKeyPacket.make(true));
    }

    @Override
    public void keyUp(EnumSet<TickType> types, KeyBinding kb, boolean tickEnd) {
        PacketDispatcher.sendPacketToServer(OvmKeyPacket.make(false));
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
