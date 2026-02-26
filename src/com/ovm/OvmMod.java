package com.ovm;

import cpw.mods.fml.common.Mod;
import cpw.mods.fml.common.Mod.Init;
import cpw.mods.fml.common.Mod.Instance;
import cpw.mods.fml.common.Mod.PostInit;
import cpw.mods.fml.common.Mod.PreInit;
import cpw.mods.fml.common.event.FMLInitializationEvent;
import cpw.mods.fml.common.event.FMLPostInitializationEvent;
import cpw.mods.fml.common.event.FMLPreInitializationEvent;
import cpw.mods.fml.common.ITickHandler;
import cpw.mods.fml.common.TickType;
import cpw.mods.fml.common.network.NetworkMod;
import cpw.mods.fml.common.network.NetworkRegistry;
import cpw.mods.fml.common.registry.TickRegistry;
import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;
import net.minecraftforge.common.MinecraftForge;
import java.lang.reflect.Method;
import java.util.EnumSet;

@Mod(modid = OvmMod.MODID, name = OvmMod.NAME, version = OvmMod.VERSION)
@NetworkMod(clientSideRequired = true, serverSideRequired = false, channels = { OvmMod.CHANNEL })
public class OvmMod {
    public static final String MODID   = "ovm";
    public static final String NAME    = "OVM";
    public static final String VERSION = "0.6.1";
    public static final String CHANNEL = "OVM|VM";

    @Instance(MODID)
    public static OvmMod instance;

    @PreInit
    public void preInit(FMLPreInitializationEvent event) {
        System.out.println("[OVM] Pre-init: " + NAME + " " + VERSION);
        OvmConfig.load(event.getModConfigurationDirectory());
    }

    @Init
    public void init(FMLInitializationEvent event) {
        System.out.println("[OVM] Init");

        try {
            Object handler = OvmPacketHandler.createProxy();
            NetworkRegistry.instance().registerChannel(
                (cpw.mods.fml.common.network.IPacketHandler) handler, CHANNEL);
            System.out.println("[OVM] Packet channel registered");
        } catch (Exception e) {
            System.out.println("[OVM] Failed to register packet channel: " + e);
        }

        if (event.getSide().isClient()) {
            initClient();
        }
    }

    @SideOnly(Side.CLIENT)
    private void initClient() {
        TickRegistry.registerTickHandler(new ClientTickHandler(), Side.CLIENT);
        try {
            Class<?> cls = Class.forName("com.ovm.OvmClientHandler");
            Object handler = cls.newInstance();
            MinecraftForge.EVENT_BUS.register(handler);
            Method m = cls.getDeclaredMethod("registerActivationKey", int.class);
            m.setAccessible(true);
            m.invoke(null, OvmConfig.activationKey);
        } catch (Exception e) {
            System.out.println("[OVM] initClient error: " + e);
        }
    }

    @PostInit
    public void postInit(FMLPostInitializationEvent event) {
        System.out.println("[OVM] Post-init");
    }

    /** Prints version to chat on first tick, then delegates to OvmClientHandler.onTick(). */
    @SideOnly(Side.CLIENT)
    private static class ClientTickHandler implements ITickHandler {
        private boolean versionShown = false;

        @Override
        public void tickStart(EnumSet<TickType> type, Object... tickData) {}

        @Override
        public void tickEnd(EnumSet<TickType> type, Object... tickData) {
            OvmClientHandler.onTick();

            if (versionShown) return;
            Object mc = McAccessor.getMc();
            if (mc == null) return;
            Object world = Reflect.getField(mc, Object.class, "theWorld", "e");
            Object player = Reflect.getField(mc, Object.class, "thePlayer", "g");
            if (world == null || player == null) return;
            Reflect.invokeWithString(player,
                NAME + " " + VERSION + " loaded. Hold ` + left-click to veinmine.",
                "addChatMessage", "a");
            versionShown = true;
        }

        @Override
        public EnumSet<TickType> ticks() {
            return EnumSet.of(TickType.CLIENT);
        }

        @Override
        public String getLabel() {
            return "OvmClientTick";
        }
    }
}
