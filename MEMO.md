# OVM Mod — Development Memory

## Environment
- **Docker image**: `veinminer-dev` (Ubuntu 14.04 amd64, Java 8, Python 2.7)
- **MCP root**: `/opt/forge/` — decompiled Minecraft + Forge patches baked into image layer
- **Forge src**: `/opt/forge_src/forge/`
- **Compiled output**: `/opt/forge/bin/minecraft/` — Minecraft+Forge+FML + mod classes
- **Build command**:
  ```
  docker run --rm -v /Users/vany/l/ovm:/workspace veinminer-dev bash /workspace/scripts/compile.sh
  ```
- **Rebuild image** (only if Dockerfile changes):
  ```
  docker build -t veinminer-dev .
  ```

## Forge 1.4.7 API findings

### Event system
- Register: `MinecraftForge.EVENT_BUS.register(listener)` in `@Init` method.
- Annotation: `@ForgeSubscribe` (package `net.minecraftforge.event.ForgeSubscribe`).
- **No BlockBreakEvent** in 1.4.7. `ForgeEventFactory` only has: `doPlayerHarvestCheck`,
  `getBreakSpeed`, `onPlayerInteract`, `onPlayerDestroyItem`.
- `PlayerInteractEvent.Action.LEFT_CLICK_BLOCK` fires server + client; guard with `!world.isRemote`.

### Block break pipeline
```
Packet14BlockDig (status=2)
  → NetServerHandler.handleBlockDig
  → uncheckedTryHarvestBlock
  → tryHarvestBlock
  → Block.removeBlockByPlayer / removeBlock
  → Block.harvestBlock  (drops)
```

### Key APIs used
- `world.getBlockId(x, y, z)` — block ID at position
- `world.getBlockMetadata(x, y, z)` — block metadata (ore variant)
- `world.setBlockWithNotify(x, y, z, 0)` — remove block, notifies neighbors
- `Block.blocksList[id].harvestBlock(world, player, x, y, z, meta)` — trigger drops
- `player.canHarvestBlock(Block)` — checks tool tier requirement
- `player.isSneaking()` — available on `Entity`, server-side accessible

## Compile pipeline
- `scripts/compile.sh` compiles `src/com/ovm/*.java` with `-source 1.6 -target 1.6 -encoding UTF-8`
- Classpath: `/opt/forge/bin/minecraft:/opt/forge/lib/*:minecraft.jar:lwjgl.jar:lwjgl_util.jar`
- Output: `/opt/forge/bin/minecraft/com/ovm/` — major version must be **50** (Java 6 bytecode)

## Decompile errors (already fixed)
`scripts/fix_forge_sources.py` patches 86 known decompile/patch-rejection errors across 21+
source files in MCP 7.26 + Forge 1.4.7. Run once during Docker image build. Result: 0 errors
compiling all 1669 Minecraft+Forge source files.

Key patches:
- `Entity.java` — captureDrops fields, stub methods
- `EntityLiving.java` — var4b rename (local variable shadowing)
- `NetHandler.java`, `NetServerHandler.java` — getPlayer + FML stubs
- `Block.java`, `Item.java` — isDefaultTexture, getTextureFile stubs
- `Tessellator.java` — public constructor, renderingWorldRenderer field
- `EntityPlayer.java` — getCurrentPlayerStrVsBlock 2-arg overload, openGui overloads
- `SoundManager.java` — MUSIC_INTERVAL constant

## Obfuscation: runtime field/method names in minecraft.jar (Prism install)

FML's `RelaunchClassLoader` does NOT remap deobfuscated names for mod code — mod classes must
use reflection to access obfuscated Minecraft members at runtime.

Confirmed obfuscated names (from `javap` on `minecraft.jar` + `conf/joined.srg`):

| Deobfuscated | Obfuscated | Type |
|---|---|---|
| `Minecraft.getMinecraft()` | `Minecraft.x()` | static method |
| `Minecraft.theMinecraft` | `Minecraft.P` | static field |
| `Minecraft.theWorld` | `Minecraft.e` | instance field |
| `Minecraft.thePlayer` | `Minecraft.g` | instance field |
| `WorldClient` class | `ayp` | class |
| `EntityClientPlayerMP` class | `ays` | class |
| `EntityPlayerSP` class | `bag` | class |

**Rule**: never access these directly in mod code — always use reflection with both names as
fallback: try obfuscated first, then deobfuscated.

**How to look up obfuscated names**:
```bash
# Class names: grep conf/joined.srg
grep 'WorldClient' /opt/forge/conf/joined.srg | grep '^CL:'
# Field/method names: javap on the class extracted from minecraft.jar
jar -xf /opt/forge/jars/bin/minecraft.jar net/minecraft/client/Minecraft.class
javap -private net/minecraft/client/Minecraft.class
```

## Obfuscation: which classes need reflection

All `net.minecraft.*` classes are obfuscated in the runtime `minecraft.jar`. The JVM resolves
**every class reference in a `.class` constant pool** at class-load time — not just executed code.
So even `new EntityItem(...)` in a never-called method causes `NoClassDefFoundError` on load.

Classes confirmed to require full reflection (never reference directly in bytecode):
- `EntityLiving` → obfuscated to `md`
- `EntityItem` → obfuscated to `px`
- `EntityPlayerSP` → obfuscated

Classes confirmed safe to reference directly (Forge transformers provide deobf names):
- `EntityPlayer`, `EntityPlayerMP`, `Block`, `World`, `ItemStack`, `FoodStats`
- All `net.minecraftforge.*` and `cpw.mods.fml.*` classes

Rule: if a class is in `net.minecraft.entity.*` below `EntityPlayer`, use reflection.
Use `Class.forName("full.class.Name")` for class lookup — string literals are safe.

## Known issues / deferred
- Client keybind for toggle (alternative to sneak): requires `KeyInputEvent` + server sync packet.
  Deferred — sneak is simpler and works server-side without network code.
- No config file: ore list and max-blocks are compile-time constants. Config with Forge ConfigManager
  is straightforward but not yet needed.
- Tool durability is not reduced per chain-mined block (vanilla break handles the original only).
  Fixing requires `ItemStack.damageItem()` per extra block.
- `setBlockWithNotify` fires block-update cascades — could cause lag for very large redstone ore
  veins. Acceptable for v0.2; `setBlockAndMetadata(x,y,z,0,0)` with deferred notifications is
  the fix if needed.
