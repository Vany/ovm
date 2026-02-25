#!/usr/bin/env python
# -*- coding: utf-8 -*-
# Fix known decompile/patch-rejection errors in Forge 1.4.7 + MCP 7.26 source tree.
# These errors occur because ~210 patches fail to apply due to decompiler output variance.
# Strategy: inject missing fields/methods as stubs using search-and-replace on source text.
# Called during Docker build before recompile step.

import os
import sys
import re

SRC = '/opt/forge/src/minecraft'

def patch_file(rel_path, replacements):
    """Apply list of (old, new) string replacements to a source file."""
    full = os.path.join(SRC, rel_path)
    if not os.path.exists(full):
        print('SKIP (not found): ' + full)
        return
    with open(full, 'r') as f:
        content = f.read()
    original = content
    for old, new in replacements:
        if old not in content:
            print('WARN: pattern not found in %s:\n  %r' % (rel_path, old[:80]))
        content = content.replace(old, new, 1)
    if content != original:
        with open(full, 'w') as f:
            f.write(content)
        print('PATCHED: ' + rel_path)
    else:
        print('NOCHANGE: ' + rel_path)

# ---------------------------------------------------------------------------
# 1. Entity.java -- add Forge fields: captureDrops, capturedDrops, persistentID
#    and methods: getPersistentID(), generatePersistentID(), getPickedResult(),
#    resetEntityId(), getEntityData(), shouldRiderSit(), shouldRenderInPass()
# ---------------------------------------------------------------------------
patch_file('net/minecraft/entity/Entity.java', [
    # Add fields after the last field in the class (before first constructor)
    (
        'public boolean canRenderOnFire() {\n      return this.isBurning();\n   }\n\n}',
        '''public boolean canRenderOnFire() {
      return this.isBurning();
   }

   // Forge: captureDrops support (patch rejection fix)
   public boolean captureDrops = false;
   public java.util.ArrayList<net.minecraft.entity.item.EntityItem> capturedDrops = new java.util.ArrayList<net.minecraft.entity.item.EntityItem>();
   private java.util.UUID persistentID = null;
   private net.minecraft.nbt.NBTTagCompound customEntityData = null;

   public java.util.UUID getPersistentID() { return persistentID; }
   public synchronized void generatePersistentID() {
      if (persistentID == null) persistentID = java.util.UUID.randomUUID();
   }
   public net.minecraft.nbt.NBTTagCompound getEntityData() {
      if (customEntityData == null) customEntityData = new net.minecraft.nbt.NBTTagCompound();
      return customEntityData;
   }
   public boolean shouldRiderSit() { return true; }
   public boolean shouldRenderInPass(int pass) { return pass == 0; }
   public void resetEntityId() { this.entityId = nextEntityID++; }
   public net.minecraft.item.ItemStack getPickedResult(net.minecraft.util.MovingObjectPosition target) { return null; }
}'''
    ),
    # Fix entityDropItem to use captureDrops
    (
        '''public EntityItem entityDropItem(ItemStack par1ItemStack, float par2) {
      EntityItem var3 = new EntityItem(this.worldObj, this.posX, this.posY + (double)par2, this.posZ, par1ItemStack);
      var3.delayBeforeCanPickup = 10;
      this.worldObj.spawnEntityInWorld(var3);
      return var3;
   }''',
        '''public EntityItem entityDropItem(ItemStack par1ItemStack, float par2) {
      EntityItem var3 = new EntityItem(this.worldObj, this.posX, this.posY + (double)par2, this.posZ, par1ItemStack);
      var3.delayBeforeCanPickup = 10;
      if (captureDrops) {
         capturedDrops.add(var3);
      } else {
         this.worldObj.spawnEntityInWorld(var3);
      }
      return var3;
   }'''
    ),
])

# ---------------------------------------------------------------------------
# 2. EntityLiving.java -- fix captureDrops variable shadow and var4 redefinition
# ---------------------------------------------------------------------------
patch_file('net/minecraft/entity/EntityLiving.java', [
    # The bug: captureDrops=true;capturedDrops.clear();int var4=0; -- var4 was already declared
    # and captureDrops/capturedDrops don't exist yet (added to Entity above as instance fields).
    # Fix: the var4 re-declaration is a decompile error (variable name collision).
    (
        '''            captureDrops = true;
            capturedDrops.clear();
            int var4 = 0;''',
        '''            captureDrops = true;
            capturedDrops.clear();
            int var4b = 0;'''
    ),
    # Also fix the ForgeHooks.onLivingDrops call if present (may not be in this file)
])

# ---------------------------------------------------------------------------
# 3. EntityPlayer.java -- add openGui, captureDrops is inherited from Entity fix above
# ---------------------------------------------------------------------------
patch_file('net/minecraft/entity/player/EntityPlayer.java', [
    (
        'public abstract void openContainer();',
        '''public abstract void openContainer();
   public void openGui(Object mod, int modGuiId, net.minecraft.world.World world, int x, int y, int z) {}
   public void openGui(int networkId, int modGuiId, net.minecraft.world.World world, int x, int y, int z) {}'''
    ),
])

# ---------------------------------------------------------------------------
# 4. NetHandler.java -- add getPlayer() stub
# ---------------------------------------------------------------------------
patch_file('net/minecraft/network/packet/NetHandler.java', [
    (
        '   public boolean canProcessPacketsAsync() {\n      return false;\n   }\n}',
        '''   public boolean canProcessPacketsAsync() {
      return false;
   }
   // Forge stub
   public net.minecraft.entity.player.EntityPlayer getPlayer() { return null; }
}'''
    ),
])

# ---------------------------------------------------------------------------
# 5. NetLoginHandler.java -- add completeConnection(String) stub
# ---------------------------------------------------------------------------
patch_file('net/minecraft/network/NetLoginHandler.java', [
    (
        '\n}',
        '''
   // Forge stub
   public void completeConnection(String kickReason) {}
}''',
    ),
])

# ---------------------------------------------------------------------------
# 6. NetClientHandler.java -- add FML stubs
# ---------------------------------------------------------------------------
patch_file('net/minecraft/client/multiplayer/NetClientHandler.java', [
    (
        '\n}',
        '''
   // Forge stubs
   public void fmlPacket131Callback(net.minecraft.network.packet.Packet131MapData data) {}
   public static void setConnectionCompatibilityLevel(byte level) {}
   public static byte getConnectionCompatibilityLevel() { return 0; }
}''',
    ),
])

# ---------------------------------------------------------------------------
# 7. Minecraft.java -- add continueWorldLoading() stub
# ---------------------------------------------------------------------------
patch_file('net/minecraft/client/Minecraft.java', [
    (
        '\n}',
        '''
   // Forge stub
   public void continueWorldLoading() {}
}''',
    ),
])

# ---------------------------------------------------------------------------
# 8. GuiConnecting.java -- add forceTermination() stub
# ---------------------------------------------------------------------------
patch_file('net/minecraft/client/multiplayer/GuiConnecting.java', [
    (
        '\n}',
        '''
   // Forge stub
   public static void forceTermination(GuiConnecting gui) {}
}''',
    ),
])

# ---------------------------------------------------------------------------
# 9. WorldType.java -- add base12Biomes, addNewBiome(), removeBiome()
# ---------------------------------------------------------------------------
patch_file('net/minecraft/world/WorldType.java', [
    (
        '   public int getWorldTypeID() {\n      return this.worldTypeId;\n   }\n\n}',
        '''   public int getWorldTypeID() {
      return this.worldTypeId;
   }
   // Forge stubs
   public static net.minecraft.world.biome.BiomeGenBase[] base12Biomes = new net.minecraft.world.biome.BiomeGenBase[0];
   public void addNewBiome(net.minecraft.world.biome.BiomeGenBase biome) {}
   public void removeBiome(net.minecraft.world.biome.BiomeGenBase biome) {}
}'''
    ),
])

# ---------------------------------------------------------------------------
# 10. WorldServer.java -- add getChunkSaveLocation()
# ---------------------------------------------------------------------------
patch_file('net/minecraft/world/WorldServer.java', [
    (
        '\n}',
        '''
   // Forge stub
   public java.io.File getChunkSaveLocation() { return new java.io.File("."); }
}''',
    ),
])

# ---------------------------------------------------------------------------
# 11. WorldProvider.java -- add setDimension()
# ---------------------------------------------------------------------------
patch_file('net/minecraft/world/WorldProvider.java', [
    (
        '\n}',
        '''
   // Forge stub
   public void setDimension(int dim) { this.dimensionId = dim; }
}''',
    ),
])

# ---------------------------------------------------------------------------
# 12. Block.java -- add getPickBlock(), isBed(), getBedDirection(), isLadder(),
#     getTextureFile(), getExplosionResistance(entity, world, x, y, z)
# ---------------------------------------------------------------------------
patch_file('net/minecraft/block/Block.java', [
    (
        '\n}',
        '''
   // Forge stubs
   public net.minecraft.item.ItemStack getPickBlock(net.minecraft.util.MovingObjectPosition target, net.minecraft.world.World world, int x, int y, int z) { return null; }
   public boolean isBed(net.minecraft.world.World world, int x, int y, int z, net.minecraft.entity.EntityLiving player) { return false; }
   public int getBedDirection(net.minecraft.world.World world, int x, int y, int z) { return 0; }
   public boolean isLadder(net.minecraft.world.World world, int x, int y, int z) { return false; }
   public String getTextureFile() { return "/terrain.png"; }
   public float getExplosionResistance(net.minecraft.entity.Entity entity, net.minecraft.world.World world, int x, int y, int z, double eX, double eY, double eZ) { return getExplosionResistance(entity); }
}''',
    ),
])

# ---------------------------------------------------------------------------
# 13. Item.java -- add getTextureFile(), hasCustomEntity(), createEntity()
# ---------------------------------------------------------------------------
patch_file('net/minecraft/item/Item.java', [
    (
        '\n}',
        '''
   // Forge stubs
   public String getTextureFile() { return "/items.png"; }
   public boolean hasCustomEntity(net.minecraft.item.ItemStack stack) { return false; }
   public net.minecraft.entity.Entity createEntity(net.minecraft.world.World world, net.minecraft.entity.Entity location, net.minecraft.item.ItemStack itemstack) { return null; }
}''',
    ),
])

# ---------------------------------------------------------------------------
# 14. MapGenStronghold.java -- add allowedBiomes field
# ---------------------------------------------------------------------------
patch_file('net/minecraft/world/gen/structure/MapGenStronghold.java', [
    (
        '\n}',
        '''
   // Forge stub
   public static java.util.List allowedBiomes = new java.util.ArrayList();
}''',
    ),
])

# ---------------------------------------------------------------------------
# 15. WorldChunkManager.java -- add allowedBiomes field
# ---------------------------------------------------------------------------
patch_file('net/minecraft/world/biome/WorldChunkManager.java', [
    (
        '\n}',
        '''
   // Forge stub
   public static java.util.List allowedBiomes = new java.util.ArrayList();
}''',
    ),
])

# ---------------------------------------------------------------------------
# 16. MinecraftServer.java -- add worldTickTimes field
# ---------------------------------------------------------------------------
patch_file('net/minecraft/server/MinecraftServer.java', [
    (
        '\n}',
        '''
   // Forge stub
   public java.util.Map worldTickTimes = new java.util.HashMap();
}''',
    ),
])

# ---------------------------------------------------------------------------
# 17. EntityMinecart.java -- add getMinecartType()
# ---------------------------------------------------------------------------
patch_file('net/minecraft/entity/item/EntityMinecart.java', [
    (
        '\n}',
        '''
   // Forge stub
   public int getMinecartType() { return 0; }
}''',
    ),
])

# ---------------------------------------------------------------------------
# 18. EntityPlayer.java -- ensure getCurrentPlayerStrVsBlock(block, meta) exists
#     ForgeHooks calls player.getCurrentPlayerStrVsBlock(block, metadata) with 2 args
#     but vanilla has only 1-arg version
# ---------------------------------------------------------------------------
patch_file('net/minecraft/entity/player/EntityPlayer.java', [
    (
        '\n}',
        '''
   // Forge stub -- 2-arg version for metadata
   public float getCurrentPlayerStrVsBlock(net.minecraft.block.Block block, int meta) {
      return this.getCurrentPlayerStrVsBlock(block);
   }
}''',
    ),
])

# ---------------------------------------------------------------------------
# 19. SoundManager.java -- add MUSIC_INTERVAL field (ModCompatibilityClient)
# ---------------------------------------------------------------------------
patch_file('net/minecraft/client/audio/SoundManager.java', [
    (
        '\n}',
        '''
   // Forge stub
   public int MUSIC_INTERVAL = 12000;
}''',
    ),
])

# ---------------------------------------------------------------------------
# 20. Tessellator.java -- fix ForgeHooksClient: add textureID field, renderingWorldRenderer
#     and fix constructor issue (Tessellator(int) constructor)
# ---------------------------------------------------------------------------
patch_file('net/minecraft/client/renderer/Tessellator.java', [
    (
        '\n}',
        '''
   // Forge stubs
   public int textureID = -1;
   public static boolean renderingWorldRenderer = false;
}''',
    ),
])

# ---------------------------------------------------------------------------
# 21. DungeonHooks.java -- @Override on method that doesn't override anything
#     Remove the @Override annotation
# ---------------------------------------------------------------------------
patch_file('net/minecraftforge/common/DungeonHooks.java', [
    # The error is "@Override method does not override" in a Comparator
    (
        '        @Override\n        public int compare(',
        '        public int compare(',
    ),
])

# ---------------------------------------------------------------------------
# 22. ForgeHooksClient.java -- Tessellator constructor issue: new Tessellator() -> new Tessellator(0x200000)
# ---------------------------------------------------------------------------
patch_file('net/minecraftforge/client/ForgeHooksClient.java', [
    (
        'tess = new Tessellator();',
        'tess = new Tessellator(0x200000);',
    ),
    # Block.getTextureFile() and Item.getTextureFile() are added as stubs above
    # Block.isBed and getBedDirection -- stubs added above
])

# ---------------------------------------------------------------------------
# 23. WorldInfo.java -- add setAdditionalProperties() stub (FMLCommonHandler)
# ---------------------------------------------------------------------------
patch_file('net/minecraft/world/storage/WorldInfo.java', [
    (
        '\n}',
        '''
   // Forge stub
   public void setAdditionalProperties(java.util.Map<String, net.minecraft.nbt.NBTBase> props) {}
}''',
    ),
])

# ---------------------------------------------------------------------------
# 24. NetServerHandler.java -- add getPlayer() if not present (ModLoader)
# ---------------------------------------------------------------------------
patch_file('net/minecraft/network/NetServerHandler.java', [
    (
        '\n}',
        '''
   // Forge stub
   public net.minecraft.entity.player.EntityPlayer getPlayer() {
      return this.playerEntity;
   }
}''',
    ),
])

# ---------------------------------------------------------------------------
# 25. Item.java -- add getChestGenBase() (ChestGenHooks calls item.getChestGenBase())
# ---------------------------------------------------------------------------
patch_file('net/minecraft/item/Item.java', [
    (
        '   public boolean hasCustomEntity(net.minecraft.item.ItemStack stack) { return false; }',
        '''   public boolean hasCustomEntity(net.minecraft.item.ItemStack stack) { return false; }
   public net.minecraft.util.WeightedRandomChestContent getChestGenBase(
           net.minecraftforge.common.ChestGenHooks chest,
           java.util.Random rnd,
           net.minecraft.util.WeightedRandomChestContent original) { return original; }''',
    ),
])

# ---------------------------------------------------------------------------
# 26. NetHandler.java -- add handleVanilla250Packet() (FMLNetworkHandler uses it)
# ---------------------------------------------------------------------------
patch_file('net/minecraft/network/packet/NetHandler.java', [
    (
        '   public net.minecraft.entity.player.EntityPlayer getPlayer() { return null; }',
        '''   public net.minecraft.entity.player.EntityPlayer getPlayer() { return null; }
   public void handleVanilla250Packet(net.minecraft.network.packet.Packet250CustomPayload packet) {}''',
    ),
])

# ---------------------------------------------------------------------------
# 27. EntityPlayer.java -- add openGui() stubs (OpenGuiPacket + ModLoaderHelper both need them)
#     Insertion point: after getCurrentPlayerStrVsBlock stub, before closing brace
# ---------------------------------------------------------------------------
patch_file('net/minecraft/entity/player/EntityPlayer.java', [
    (
        '   // Forge stub -- 2-arg version for metadata\n   public float getCurrentPlayerStrVsBlock(net.minecraft.block.Block block, int meta) {\n      return this.getCurrentPlayerStrVsBlock(block);\n   }\n}',
        '''   // Forge stub -- 2-arg version for metadata
   public float getCurrentPlayerStrVsBlock(net.minecraft.block.Block block, int meta) {
      return this.getCurrentPlayerStrVsBlock(block);
   }
   public void openGui(int networkId, int modGuiId, net.minecraft.world.World world, int x, int y, int z) {}
   public void openGui(Object mod, int modGuiId, net.minecraft.world.World world, int x, int y, int z) {}
}''',
    ),
])

# ---------------------------------------------------------------------------
# 28. Tessellator.java -- make the Tessellator(int) constructor public
# ---------------------------------------------------------------------------
patch_file('net/minecraft/client/renderer/Tessellator.java', [
    (
        'private Tessellator(int par1)',
        'public Tessellator(int par1)',
    ),
])

# ---------------------------------------------------------------------------
# 29. Block.java -- add isDefaultTexture field (ForgeHooksClient uses block.isDefaultTexture)
# ---------------------------------------------------------------------------
patch_file('net/minecraft/block/Block.java', [
    (
        '   // Forge stubs\n   public net.minecraft.item.ItemStack getPickBlock(',
        '''   // Forge stubs
   public boolean isDefaultTexture = true;
   public net.minecraft.item.ItemStack getPickBlock(''',
    ),
])

# ---------------------------------------------------------------------------
# 30. DungeonHooks.java -- fix @Override on generateChestContent (not an override)
# ---------------------------------------------------------------------------
patch_file('net/minecraftforge/common/DungeonHooks.java', [
    (
        '        @Override\n        protected final ItemStack[] generateChestContent(',
        '        protected final ItemStack[] generateChestContent(',
    ),
])

# ---------------------------------------------------------------------------
# 31. EntityRenderer.java -- fix decompile artifact: entire Forge-injected block is
#     misplaced inside setupFog() where var5=int, var4=boolean, var14 doesn't exist.
#     The renderEntities/dispatchRenderLast calls belong in renderWorld(), not setupFog().
#     Fix: replace the broken block with valid no-ops.
# ---------------------------------------------------------------------------
patch_file('net/minecraft/client/renderer/EntityRenderer.java', [
    (
        '''            RenderHelper.enableStandardItemLighting();
            this.mc.mcProfiler.endStartSection("entities");
            ForgeHooksClient.setRenderPass(1);
            var5.renderEntities(var4.getPosition(par1), var14, par1);
            ForgeHooksClient.setRenderPass(-1);
            RenderHelper.disableStandardItemLighting();

            GL11.glFogi(2917, 9729);
            if(par1 < 0) {
               GL11.glFogf(2915, 0.0F);
               GL11.glFogf(2916, var6 * 0.8F);
            } else {
               GL11.glFogf(2915, var6 * 0.25F);
               GL11.glFogf(2916, var6);
            }

            this.mc.mcProfiler.endStartSection("FRenderLast");
            ForgeHooksClient.dispatchRenderLast(var5, par1);''',
        '''            // Forge fix: renderEntities/dispatchRenderLast removed (decompile artifact in wrong method)
            GL11.glFogi(2917, 9729);
            if(par1 < 0) {
               GL11.glFogf(2915, 0.0F);
               GL11.glFogf(2916, var6 * 0.8F);
            } else {
               GL11.glFogf(2915, var6 * 0.25F);
               GL11.glFogf(2916, var6);
            }''',
    ),
])

print('Done fixing sources.')
