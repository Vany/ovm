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
import cpw.mods.fml.common.network.NetworkRegistry;
import cpw.mods.fml.common.registry.TickRegistry;
import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;
import net.minecraft.client.Minecraft;
import net.minecraftforge.common.MinecraftForge;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.EnumSet;

@Mod(modid = OvmMod.MODID, name = OvmMod.NAME, version = OvmMod.VERSION)
public class OvmMod {
    public static final String MODID   = "ovm";
    public static final String NAME    = "OVM";
    public static final String VERSION = "0.3.0";

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

        // Register server-side packet handler via dynamic proxy.
        // We cannot use "new OvmPacketHandler()" directly because IPacketHandler's
        // onPacketData(INetworkManager, Packet250CustomPayload, Player) signature
        // references Packet250CustomPayload whose superclass Packet is obfuscated at runtime.
        try {
            Class<?> iph = Class.forName("cpw.mods.fml.common.network.IPacketHandler");
            Object proxy = Proxy.newProxyInstance(
                OvmMod.class.getClassLoader(),
                new Class<?>[]{iph},
                new InvocationHandler() {
                    public Object invoke(Object proxy, Method method, Object[] args) {
                        if ("onPacketData".equals(method.getName()) && args != null && args.length == 3) {
                            OvmPacketHandler.handlePacketData(args[1], args[2]);
                        }
                        return null;
                    }
                }
            );
            NetworkRegistry.instance().registerChannel(
                (cpw.mods.fml.common.network.IPacketHandler) proxy, OvmKeyPacket.CHANNEL);
        } catch (Exception e) {
            System.out.println("[OVM] Failed to register packet handler: " + e);
        }

        // Register server-side VeinMiner event listener
        MinecraftForge.EVENT_BUS.register(new VeinMiner());

        // Client-side: register key binding handler and version chat message
        if (event.getSide().isClient()) {
            initClient();
        }
    }

    @SideOnly(Side.CLIENT)
    private void initClient() {
        TickRegistry.registerTickHandler(new OvmKeyHandler(), Side.CLIENT);
        TickRegistry.registerTickHandler(new VersionChatHandler(), Side.CLIENT);
    }

    @PostInit
    public void postInit(FMLPostInitializationEvent event) {
        System.out.println("[OVM] Post-init");
    }

    // Prints version message to chat on the first available client tick
    @SideOnly(Side.CLIENT)
    private static class VersionChatHandler implements ITickHandler {
        private boolean done = false;

        @Override
        public void tickStart(EnumSet<TickType> type, Object... tickData) {}

        @Override
        public void tickEnd(EnumSet<TickType> type, Object... tickData) {
            if (done) return;
            Minecraft mc = getMinecraft();
            if (mc == null) return;
            try {
                Object world = getField(mc, new String[]{"theWorld", "e"});
                Object player = getField(mc, new String[]{"thePlayer", "g"});
                if (world == null || player == null) return;
                // addChatMessage(String) — try both obfuscated and deobfuscated names
                for (String name : new String[]{"addChatMessage", "a"}) {
                    try {
                        player.getClass().getMethod(name, String.class).invoke(player, NAME + " " + VERSION + " loaded.");
                        break;
                    } catch (Exception e) { /* try next */ }
                }
                done = true;
            } catch (Exception e) { /* not ready */ }
        }

        private static Object getField(Object obj, String[] names) throws Exception {
            for (String name : names) {
                try {
                    Field f = obj.getClass().getField(name);
                    f.setAccessible(true);
                    return f.get(obj);
                } catch (NoSuchFieldException e) { /* try next */ }
            }
            return null;
        }

        private static Minecraft cachedMc = null;
        private static Minecraft getMinecraft() {
            if (cachedMc != null) return cachedMc;
            for (String name : new String[]{"x", "getMinecraft"}) {
                try {
                    Method m = Minecraft.class.getDeclaredMethod(name);
                    m.setAccessible(true);
                    cachedMc = (Minecraft) m.invoke(null);
                    if (cachedMc != null) return cachedMc;
                } catch (Exception e) { /* try next */ }
            }
            for (String name : new String[]{"P", "theMinecraft"}) {
                try {
                    Field f = Minecraft.class.getDeclaredField(name);
                    f.setAccessible(true);
                    cachedMc = (Minecraft) f.get(null);
                    if (cachedMc != null) return cachedMc;
                } catch (Exception e) { /* try next */ }
            }
            return null;
        }

        @Override
        public EnumSet<TickType> ticks() {
            return EnumSet.of(TickType.CLIENT);
        }

        @Override
        public String getLabel() {
            return "OvmVersionChat";
        }
    }
}
