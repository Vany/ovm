package com.ovm;

import java.util.EnumSet;

import org.lwjgl.input.Keyboard;

import cpw.mods.fml.common.ITickHandler;
import cpw.mods.fml.common.TickType;
import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;

/**
 * Client-side key poller for the veinmine modifier key.
 * Uses LWJGL Keyboard directly — avoids KeyBinding and KeyBindingRegistry
 * which reference obfuscated net.minecraft.client.settings.KeyBinding at runtime.
 */
@SideOnly(Side.CLIENT)
public class OvmKeyHandler implements ITickHandler {

    private boolean prevKeyDown = false;

    @Override
    public void tickStart(EnumSet<TickType> type, Object... tickData) {}

    @Override
    public void tickEnd(EnumSet<TickType> type, Object... tickData) {
        boolean keyDown = Keyboard.isKeyDown(OvmConfig.activationKey);
        if (keyDown != prevKeyDown) {
            prevKeyDown = keyDown;
            OvmKeyPacket.send(keyDown);
        }
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
