package com.ovm;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.PriorityQueue;
import java.util.Set;

import net.minecraft.block.Block;
import net.minecraft.entity.item.EntityItem;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;
import net.minecraft.world.World;
import net.minecraftforge.event.ForgeSubscribe;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;

/**
 * VeinMiner v0.3.0 — server-side chain-mine with priority flood fill.
 *
 * Activation: hold veinmine key (grave ` by default) while breaking a veinminable block.
 * Key state is synced from client via OvmPacketHandler.
 *
 * Algorithm: priority flood fill (PriorityQueue ordered by squared Euclidean distance
 * from origin), producing a sphere-shaped cavity.
 *
 * Supported block types:
 *   Ores: 14,15,16,21,56,73,74,129
 *   Wood (logs): 17
 *   Leaves: 18
 */
public class VeinMiner {

    // Ore block IDs (Minecraft 1.4.7 vanilla)
    private static final Set<Integer> VEINMINE_IDS = new HashSet<Integer>();
    static {
        VEINMINE_IDS.add(14);   // gold ore
        VEINMINE_IDS.add(15);   // iron ore
        VEINMINE_IDS.add(16);   // coal ore
        VEINMINE_IDS.add(21);   // lapis ore
        VEINMINE_IDS.add(56);   // diamond ore
        VEINMINE_IDS.add(73);   // redstone ore
        VEINMINE_IDS.add(74);   // lit redstone ore
        VEINMINE_IDS.add(129);  // emerald ore
        VEINMINE_IDS.add(17);   // wood (logs)
        VEINMINE_IDS.add(18);   // leaves
    }

    // 26-neighbor offsets (full 3x3x3 minus center)
    private static final int[][] NEIGHBORS = new int[26][3];
    static {
        int idx = 0;
        for (int dx = -1; dx <= 1; dx++)
            for (int dy = -1; dy <= 1; dy++)
                for (int dz = -1; dz <= 1; dz++)
                    if (dx != 0 || dy != 0 || dz != 0)
                        NEIGHBORS[idx++] = new int[]{dx, dy, dz};
    }

    @ForgeSubscribe
    public void onPlayerInteract(PlayerInteractEvent event) {
        if (event.action != PlayerInteractEvent.Action.LEFT_CLICK_BLOCK) return;

        EntityPlayer player = event.entityPlayer;
        World world = player.worldObj;
        if (world.isRemote) return;  // server-side only

        // Check veinmine key is held (state sent from client via packet)
        if (!OvmPacketHandler.isVeinKeyActive(player)) return;

        int ox = event.x, oy = event.y, oz = event.z;
        int originId = world.getBlockId(ox, oy, oz);
        if (!VEINMINE_IDS.contains(originId)) return;

        // Check hunger before starting: need at least 1 hunger point
        int foodLevel = player.getFoodStats().getFoodLevel();
        if (foodLevel < 1) {
            player.addChatMessage("[OVM] Not enough hunger to veinmine.");
            return;
        }

        // Build vein list via priority flood fill (sphere-shaped, closest-first)
        List<int[]> vein = buildVein(world, ox, oy, oz, originId, OvmConfig.maxBlocks);

        if (vein.isEmpty()) return;

        // Harvest each block, collecting drops into player inventory
        int minedCount = 0;
        player.captureDrops = true;
        player.capturedDrops.clear();

        try {
            for (int[] pos : vein) {
                int bx = pos[0], by = pos[1], bz = pos[2];
                int blockId = world.getBlockId(bx, by, bz);
                if (blockId != originId) continue;  // changed between calc and mining

                Block block = Block.blocksList[blockId];
                if (block == null) continue;
                if (!player.canHarvestBlock(block)) continue;

                int meta = world.getBlockMetadata(bx, by, bz);

                // Mine through full harvest pipeline (applies Fortune, Silk Touch, etc.)
                block.harvestBlock(world, player, bx, by, bz, meta);
                world.setBlockWithNotify(bx, by, bz, 0);
                minedCount++;

                // Damage tool once per extra block
                ItemStack held = player.getHeldItem();
                if (held != null && held.getItem() != null && held.isItemStackDamageable()) {
                    held.damageItem(1, player);
                    if (held.stackSize <= 0) {
                        // Tool broke — stop mining
                        player.destroyCurrentEquippedItem();
                        break;
                    }
                }
            }
        } finally {
            player.captureDrops = false;
        }

        // Deliver collected drops to player inventory or drop at feet
        deliverDrops(world, player, player.capturedDrops);
        player.capturedDrops.clear();

        // Deduct hunger after full operation: 1 point per hungerPerBlocks blocks
        if (OvmConfig.hungerPerBlocks > 0 && minedCount > 0) {
            int hungerPoints = minedCount / OvmConfig.hungerPerBlocks;
            if (hungerPoints > 0) {
                // addExhaustion: 4.0f per point drains exactly 1 hunger (bypasses saturation)
                // We use it to directly reduce food level via exhaustion accumulation.
                // Each hunger point costs 4.0 exhaustion.
                player.addExhaustion(4.0f * hungerPoints);
            }
        }

        System.out.println("[OVM] VeinMiner: mined " + minedCount + " blocks at ("
                + ox + "," + oy + "," + oz + ")");
    }

    /**
     * Priority flood fill: returns ordered list of block positions to mine,
     * closest to origin first (sphere-shaped selection).
     * Origin block is included as first entry.
     */
    private List<int[]> buildVein(World world, int ox, int oy, int oz, int targetId, int maxCount) {
        List<int[]> result = new ArrayList<int[]>();

        // PriorityQueue ordered by squared distance from origin (ascending)
        PriorityQueue<long[]> pq = new PriorityQueue<long[]>(16, new Comparator<long[]>() {
            public int compare(long[] a, long[] b) {
                return Long.compare(a[0], b[0]); // a[0] = distSquared
            }
        });
        Set<Long> visited = new HashSet<Long>();

        long originKey = coordKey(ox, oy, oz);
        visited.add(originKey);
        pq.add(new long[]{0L, ox, oy, oz});

        while (!pq.isEmpty() && result.size() < maxCount) {
            long[] entry = pq.poll();
            int bx = (int) entry[1];
            int by = (int) entry[2];
            int bz = (int) entry[3];

            // Verify block is still the right type (world may differ from when enqueued)
            if (world.getBlockId(bx, by, bz) != targetId) continue;

            result.add(new int[]{bx, by, bz});

            // Expand to 26 neighbors
            for (int[] d : NEIGHBORS) {
                int nx = bx + d[0];
                int ny = by + d[1];
                int nz = bz + d[2];
                long nKey = coordKey(nx, ny, nz);
                if (!visited.contains(nKey) && world.getBlockId(nx, ny, nz) == targetId) {
                    visited.add(nKey);
                    long dx = nx - ox, dy2 = ny - oy, dz = nz - oz;
                    long distSq = dx * dx + dy2 * dy2 + dz * dz;
                    pq.add(new long[]{distSq, nx, ny, nz});
                }
            }
        }

        return result;
    }

    /**
     * Delivers a list of EntityItem drops to the player inventory.
     * Items that don't fit drop at the player's feet.
     */
    private void deliverDrops(World world, EntityPlayer player,
                              java.util.ArrayList<EntityItem> drops) {
        for (EntityItem ei : drops) {
            ItemStack stack = ei.getEntityItem();
            if (stack == null || stack.stackSize <= 0) continue;

            if (OvmConfig.dropsToInventory) {
                boolean fitted = player.inventory.addItemStackToInventory(stack);
                if (!fitted || stack.stackSize > 0) {
                    // Drop remaining at player's position
                    spawnItemAtPlayer(world, player, stack);
                }
            } else {
                // Drop in place (at the EntityItem's spawn position)
                EntityItem drop = new EntityItem(world,
                        ei.posX, ei.posY, ei.posZ, stack);
                world.spawnEntityInWorld(drop);
            }
        }
    }

    private void spawnItemAtPlayer(World world, EntityPlayer player, ItemStack stack) {
        EntityItem drop = new EntityItem(world,
                player.posX, player.posY, player.posZ, stack);
        world.spawnEntityInWorld(drop);
    }

    /** Pack (x,y,z) into a single long key for visited set. */
    private static long coordKey(int x, int y, int z) {
        return ((long)(x + 30000)) * 60001L * 512L
             + ((long)(y + 256))  * 60001L
             + (z + 30000);
    }
}
