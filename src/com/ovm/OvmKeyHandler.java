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
        try {
            if (!Keyboard.isCreated()) return;
            // Scan all key codes to find what ` actually reports on this system
            for (int i = 0; i < 256; i++) {
                if (Keyboard.isKeyDown(i)) {
                    System.out.println("[OVM] Key down: code=" + i + " name=" + Keyboard.getKeyName(i));
                }
            }
        } catch (Exception e) {
            System.out.println("[OVM] KeyHandler error: " + e);
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
