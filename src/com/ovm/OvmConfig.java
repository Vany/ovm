package com.ovm;

import java.io.File;

import net.minecraftforge.common.Configuration;

/**
 * OVM mod configuration. Loaded during PreInit from the Forge config directory.
 * File: config/ovm.cfg
 */
public class OvmConfig {

    // Defaults
    public static int maxBlocks       = 64;
    public static int hungerPerBlocks = 32;
    public static boolean dropsToInventory = true;
    public static void load(File configDir) {
        Configuration cfg = new Configuration(new File(configDir, "ovm.cfg"));
        try {
            cfg.load();

            maxBlocks = cfg.get(
                Configuration.CATEGORY_GENERAL,
                "maxBlocks",
                64,
                "Maximum number of blocks mined per vein operation (default: 64)"
            ).getInt(64);

            hungerPerBlocks = cfg.get(
                Configuration.CATEGORY_GENERAL,
                "hungerPerBlocks",
                32,
                "Blocks mined per 1 hunger point deducted (default: 32; 0 = no hunger cost)"
            ).getInt(32);

            dropsToInventory = cfg.get(
                Configuration.CATEGORY_GENERAL,
                "dropsToInventory",
                true,
                "If true, mined drops go to player inventory (overflow drops at feet). If false, drop in place."
            ).getBoolean(true);

} finally {
            cfg.save();
        }
    }
}
