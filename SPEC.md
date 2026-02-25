# OVM Mod — Specification

## Overview
OVM is a Minecraft 1.4.7 Forge mod. Current feature set: **VeinMiner**.

## VeinMiner

### Behavior
When a player holds the **veinmine key** and breaks an ore block (or wood/leaves), VeinMiner
automatically mines all connected blocks of the same type in a single operation.

### Activation
- Hold the **veinmine key** (default: `` ` `` grave/backtick, above Tab) while breaking a block.
- Without the key: normal single-block break, no chain mining.
- Key is configurable via Forge `.cfg` config file (not in-game GUI).
- Key detection is client-side; active state is synced to server via a custom FML packet before
  mining begins.

### Scope limits
- **Max blocks per activation**: 64 (configurable). Includes the originally broken block.
- **Connectivity**: 26-neighbor (full 3×3×3 cube adjacency — faces + edges + corners).
- **Tool check**: `player.canHarvestBlock(Block)` is checked per block. Blocks the player cannot
  harvest with the current tool are skipped (not mined, not counted).
- **Tool durability**: each extra mined block calls `ItemStack.damageItem(1, player)` on the held
  item. If the tool breaks mid-operation, mining stops at that point (partial vein mine is OK).
- **Hunger check**: before starting, require at least 1 full hunger point. If hunger < 1, operation
  is refused. After the full vein mine, deduct 1 hunger point per 32 blocks mined (configurable
  ratio). Fraction rounded down.

### Supported block types

#### Ores (Minecraft 1.4.7 vanilla IDs)
| Block ID | Ore |
|----------|-----|
| 14 | Gold Ore |
| 15 | Iron Ore |
| 16 | Coal Ore |
| 21 | Lapis Lazuli Ore |
| 56 | Diamond Ore |
| 73 | Redstone Ore |
| 74 | Lit Redstone Ore (active state) |
| 129 | Emerald Ore |

#### Wood (logs)
| Block ID | Wood |
|----------|------|
| 17 | Wood (Oak, Spruce, Birch, Jungle — metadata variants) |

- Tool requirement: axe (`canHarvestBlock()` enforced).

#### Leaves
| Block ID | Leaves |
|----------|--------|
| 18 | Leaves (Oak, Spruce, Birch, Jungle — metadata variants) |

- Tool requirement: any tool or bare hand (`canHarvestBlock()` always returns true for leaves with
  any tool; shears give leaf block drop, other tools give normal drops including saplings).

### Mining algorithm — Priority Flood Fill

Replace the simple BFS of v0.2.0 with a **priority-driven flood fill** ordered by squared
Euclidean distance from the origin block. This produces a sphere-shaped cavity rather than a
cube-shaped one.

**Data structures**:
- `PriorityQueue<long[]>` — entries are `{distSquared, packedXYZ}`, ordered ascending by
  `distSquared`.
- `HashSet<Long>` — visited set, packed coords `key(x,y,z)`.
- `List<long[]>` — result list of coords to mine.

**Algorithm**:
1. Insert origin (dist=0) into PQ, mark visited.
2. Pop minimum-dist entry `p`.
3. Add `p` to result list.
4. For each of 26 neighbors `n` of `p`:
   - If `n` not visited and block at `n` is same type as origin:
     - Mark `n` visited, compute `distSquared(n, origin)`, push into PQ.
5. Repeat until result list has `N` entries or PQ is empty.
6. Mine blocks in result list order (closest first).

**Distance metric**: `distSquared = dx² + dy² + dz²` (integer, no sqrt).

### Drops handling
- Each block is mined through the full harvest pipeline:
  `Block.harvestBlock(world, player, x, y, z, meta)` then `world.setBlockWithNotify(x, y, z, 0)`.
- This applies all tool enchantments (Fortune, Silk Touch) per block.
- Drops are collected and inserted into the player's inventory (`player.inventory.addItemStackToInventory()`).
- If inventory is full, items are dropped at the player's feet coordinates
  (`world.spawnEntityInWorld(new EntityItem(world, px, py, pz, stack))`).

### Block updates
- `world.setBlockWithNotify(x, y, z, 0)` is used per block, which fires neighbor block-update
  cascades. This is correct behavior for vein mining.

### Configuration (Forge .cfg)
File generated automatically by Forge `Configuration` at first launch.

| Key | Default | Description |
|-----|---------|-------------|
| `maxBlocks` | 64 | Maximum blocks mined per vein operation |
| `hungerPerBlocks` | 32 | Blocks mined per 1 hunger point deducted |
| `dropsToInventory` | true | If true, drops go to inventory (overflow at feet); if false, drop in place |
| `activationKey` | 96 | Keycode for veinmine modifier key (96 = grave `` ` ``) |

### Mod metadata
- Author: vany ivan@serezhkin.com
- `mcmod.info` in `bin/minecraft/com/ovm/mcmod.info`
- Mod icon: `pack.png` in jar root (16×16 or 32×32 PNG)

### Implementation notes
- **Vein calculation**: server-side only (`world.isRemote == false`).
- **Key state sync**: client sends a custom FML packet (`OvmKeyPacket`) to server when key is
  pressed/released. Server stores active state per player in a `HashSet<String>` (player username).
- **Event hook**: `@ForgeSubscribe` on `PlayerInteractEvent.LEFT_CLICK_BLOCK` registered in
  `OvmMod.init()`. Guard: `!world.isRemote && isVeinKeyActive(player)`.
- **No BlockBreakEvent** in Forge 1.4.7 — `PlayerInteractEvent.LEFT_CLICK_BLOCK` is the correct
  server-side hook.
- **Packet registration**: `NetworkRegistry.instance().registerChannel(new OvmPacketHandler(), "ovm")`.
- BFS→PQ migration: replace `LinkedList` queue with `PriorityQueue`, same visited set pattern.

### Architecture
```
Client                          Server
------                          ------
KeyInputEvent (GAME tick)
  key pressed → send OvmKeyPacket ──→ OvmPacketHandler.onPacketData()
  key released → send OvmKeyPacket ──→   stores state in activeVeinPlayers set

PlayerInteractEvent.LEFT_CLICK_BLOCK (fires both sides)
                                    guard: !world.isRemote
                                    guard: player in activeVeinPlayers
                                    check hunger ≥ 1
                                    run priority flood fill
                                    per block:
                                      canHarvestBlock() check
                                      harvestBlock() → drops
                                      setBlockWithNotify() → 0
                                      damageItem() → stop if broken
                                    deduct hunger
                                    deliver drops to inventory
```

### Decisions made
- Forge 1.4.7 has **no BlockBreakEvent** — `PlayerInteractEvent.LEFT_CLICK_BLOCK` is the correct hook.
- Grave `` ` `` chosen as default key: unambiguous, rarely bound, above Tab.
- Priority flood fill (sphere) chosen over BFS (cube) per spec in TODO.md.
- 26-neighbor connectivity matches player expectation for diagonal veins.
- Hunger deducted after full operation (not per-block), to avoid partial deduction on tool-break stop.
- Drops-to-inventory default: more ergonomic than drop-in-place.
- Config file (not in-game GUI): sufficient for v0.3, simpler implementation.
- Preview/highlight rendering deferred to future version (requires client-side ray-cast + render hook).

## Version history
- `0.1.0` — stub mod, prints version to chat on first client tick.
- `0.2.0` — VeinMiner: sneak + ore break triggers BFS chain-mine up to 64 blocks.
- `0.3.0` — VeinMiner rewrite: keybind activation, priority flood fill, wood+leaves, tool
  durability, hunger, drops-to-inventory, config file, mod metadata.
