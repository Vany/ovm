# VeinMiner Development Knowledge Base

This document contains all knowledge gained during the development process, including problems encountered, solutions found, and important discoveries.

## Table of Contents
1. [Environment Setup](#environment-setup)
2. [Compilation Issues](#compilation-issues)
3. [Obfuscation Challenges](#obfuscation-challenges)
4. [API Compatibility](#api-compatibility)
5. [Reflection Techniques](#reflection-techniques)
6. [File Structure Requirements](#file-structure-requirements)
7. [Debugging Methods](#debugging-methods)
8. [Best Practices](#best-practices)

---

## Environment Setup

### Python and Java Requirements

**Discovery**: Minecraft 1.4.7 + Forge requires specific versions
- Python 2.7.18 (not Python 3) for MCP scripts
- Java 8 x86_64 via Rosetta on ARM64 Mac
- Newer Java versions cause ASM library errors

**Installation Paths**:
```
Python: ~/.pyenv/versions/2.7.18/bin/python
Java: /Library/Java/JavaVirtualMachines/temurin-8.jdk/Contents/Home
```

**Why Java 8 Specifically**:
- Forge 1.4.7's ASM library reads Java bytecode
- ASM version in Forge 1.4.7 only supports up to Java 8 bytecode
- Java 25 produces bytecode version 69.0 (unsupported)
- Java 8 with `-source 1.6 -target 1.6` produces version 50.0 (supported)

### Initial Setup Scripts

**Problem**: Forge setup required native library fixes for ARM64 Mac

**Solution**: Created custom setup scripts
- `setup_x86_natives.sh` - Downloads x86_64 LWJGL natives
- `complete_setup.sh` - Full setup automation
- `run_minecraft_forge.sh` - Launch with correct Java and natives path

**Critical Discovery**: x86_64 natives must be used even on ARM64 because Java is running via Rosetta

---

## Compilation Issues

### Java Bytecode Version Mismatch

**Error**:
```
java.lang.UnsupportedClassVersionError: com/veinminer/VeinMiner :
Unsupported major.minor version 69.0
```

**Root Cause**:
- Initial compilation used system Java (Java 25)
- Produced bytecode version 69.0
- Forge 1.4.7 ASM library only reads up to version 52.0

**Solution**:
```bash
/Library/Java/JavaVirtualMachines/temurin-8.jdk/Contents/Home/bin/javac \
  -source 1.6 -target 1.6 \
  -cp "lib/*:mcp/jars/bin/minecraft.jar:..." \
  -d mcp/bin/minecraft \
  common/com/veinminer/*.java
```

**Key Flags**:
- `-source 1.6`: Use Java 1.6 language features
- `-target 1.6`: Generate Java 1.6 bytecode (version 50.0)

### Package Structure Confusion

**Error**:
```
error: package net.minecraft.src does not exist
import net.minecraft.src.Block;
```

**Root Cause**: Two different package structures exist:
1. **Deobfuscated Source**: `net.minecraft.client.*`, `net.minecraft.server.*`
2. **Obfuscated Runtime**: Flat `net.minecraft.src.*` structure in compiled JAR

**Solution**:
- When compiling against JAR: Use `net.minecraft.client.*` imports
- When compiling from full source: Same imports work
- MCP handles the mapping automatically

### Forge API Missing

**Error**:
```
error: package cpw.mods.fml.common does not exist
```

**Root Cause**: Forge libraries not in classpath

**Solution**: Always include `lib/*` in classpath:
```bash
-cp "lib/*:mcp/jars/bin/minecraft.jar:..."
```

**Contents of lib/***:
- Forge JAR with FML classes
- ASM library
- Other Forge dependencies

### Corrupted jinput.jar

**Error During MCP Recompile**:
```
error: error reading jars/bin/jinput.jar; zip END header not found
```

**Discovery**: jinput.jar was only 307 bytes (corrupted)

**Solution**:
```bash
mv mcp/jars/bin/jinput.jar mcp/jars/bin/jinput.jar.broken
```

**Why It Works**: jinput.jar is optional for compilation, only needed at runtime for controller support

---

## Obfuscation Challenges

### The Minecraft Singleton Problem

**Initial Approach**: Call `Minecraft.getMinecraft()`

**Error**:
```
java.lang.NoSuchMethodError: net.minecraft.client.Minecraft.getMinecraft()
Lnet/minecraft/client/Minecraft;
```

**Root Cause**:
- Method signature in compiled JAR: `net.minecraft.client.Minecraft.x()`
- Return type is obfuscated too
- Trying to call with deobfuscated name fails

**First Solution Attempt**: Reflection on `theMinecraft` field

```java
Field field = Minecraft.class.getDeclaredField("theMinecraft");
field.setAccessible(true);
minecraftInstance = (Minecraft) field.get(null);
```

**Error**:
```
NoSuchFieldException: theMinecraft
```

**Investigation**: Used `javap` to inspect compiled JAR:
```bash
jar -xf mcp/jars/bin/minecraft.jar net/minecraft/client/Minecraft.class
javap -private net/minecraft/client/Minecraft.class | grep "static.*Minecraft"
```

**Discovery**:
```
private static net.minecraft.client.Minecraft P;
public static net.minecraft.client.Minecraft x();
```

**Field name at runtime**: `P` (not `theMinecraft`)
**Method name at runtime**: `x()` (not `getMinecraft()`)

### Dual-Mode Reflection Solution

**Problem**: Need code that works in BOTH contexts:
1. When compiling from deobfuscated source (field name: `theMinecraft`)
2. When running with obfuscated bytecode (field name: `P`)

**Final Solution**: Try both field names
```java
String[] fieldNames = {"P", "theMinecraft"};
for (String name : fieldNames) {
    try {
        Field field = Minecraft.class.getDeclaredField(name);
        field.setAccessible(true);
        Object value = field.get(null);
        if (value != null) {
            minecraftInstance = (Minecraft) value;
            fieldName = name; // Cache for performance
            return minecraftInstance;
        }
    } catch (NoSuchFieldException e) {
        // Try next name
    }
}
```

**Why This Works**:
- At runtime: Finds 'P', caches it, uses it every time
- From source: Would find 'theMinecraft' if it existed in that context
- Gracefully handles both scenarios

### Confirmed Obfuscated Names (minecraft.jar in Prism install)

Verified via `javap` + `conf/joined.srg`:

| Deobfuscated | Obfuscated | Kind |
|---|---|---|
| `Minecraft.getMinecraft()` | `Minecraft.x()` | static method |
| `Minecraft.theMinecraft` | `Minecraft.P` | static field |
| `Minecraft.theWorld` | `Minecraft.e` | instance field |
| `Minecraft.thePlayer` | `Minecraft.g` | instance field |
| class `WorldClient` | `ayp` | class name |
| class `EntityClientPlayerMP` | `ays` | class name |
| class `EntityPlayerSP` | `bag` | class name |

**Critical rule**: FML's `RelaunchClassLoader` does NOT remap deobfuscated names for mod code.
Directly accessing `mc.theWorld`, `mc.thePlayer`, `Minecraft.getMinecraft()` etc. throws
`NoSuchFieldError` / `NoSuchMethodError` at runtime. Always use reflection with both names as
fallback (obfuscated first, deobfuscated second).

**How to look up obfuscated names**:
```bash
# Class names (inside Docker container):
grep '^CL:' /opt/forge/conf/joined.srg | grep 'WorldClient'

# Field/method names:
jar -xf /opt/forge/jars/bin/minecraft.jar net/minecraft/client/Minecraft.class
javap -private net/minecraft/client/Minecraft.class
rm -rf net
```

### MCP Obfuscation Mapping

**How MCP Works**:
1. Takes obfuscated `minecraft.jar`
2. Deobfuscates using SRG mappings
3. Produces readable source in `mcp/src/minecraft/`
4. Source uses readable names: `theMinecraft`, `getMinecraft()`, etc.

**Runtime Reality**:
- Game runs with OBFUSCATED bytecode
- Class names: Readable (`net.minecraft.client.Minecraft`)
- Method/field names: Obfuscated (`P`, `x()`)

**Key Insight**: Source code and runtime bytecode have different member names!

---

## API Compatibility

### Forge 1.4.7 vs Modern Forge

**Modern Forge** (1.7+):
- `@EventHandler` annotation
- Event bus system
- Organized packages: `net.minecraftforge.event.*`

**Forge 1.4.7**:
- `@PreInit`, `@Init`, `@PostInit` annotations (no `@EventHandler`)
- `ITickHandler` interface for ticking
- `@ForgeSubscribe` for events (limited)
- Flat package structure in many places

### Minecraft 1.4.7 API Differences

**Package Structure**:
- No organized packages in 1.4.7
- Everything in `net.minecraft.client.*` and `net.minecraft.server.*`
- Source shows `net.minecraft.src.*` flat structure

**Missing Modern Methods**:
- `World.setBlockToAir()` - Doesn't exist in 1.4.7
- Use: `world.setBlock(x, y, z, 0)` instead

**KeyBinding System**:
- Creating `KeyBinding` adds to global `KeyBinding.keybindArray`
- Does NOT automatically add to `GameSettings.keyBindings` (controls menu)
- Must manually modify `GameSettings.keyBindings` array via reflection

### Configuration API

**Forge 1.4.7 Configuration**:
```java
import net.minecraftforge.common.Configuration;

config = new Configuration(configFile);
config.load();

// Get values
int value = config.get(Configuration.CATEGORY_GENERAL, "key", defaultValue, "comment").getInt();
String str = config.get(Configuration.CATEGORY_GENERAL, "key", "default", "comment").value;

config.save();
```

**Important**: Use `.value` for strings, not `.getString()` in Forge 1.4.7

---

## Reflection Techniques

### Accessing Private Static Fields

**Pattern**:
```java
Field field = ClassName.class.getDeclaredField("fieldName");
field.setAccessible(true);
Object value = field.get(null); // null for static fields
```

**Used For**:
- Minecraft singleton access
- PlayerController netClientHandler access

### Replacing Object Instances via Reflection

**Use Case**: Replace `Minecraft.playerController` with custom subclass

**Pattern**:
```java
// Get the field
Field field = Minecraft.class.getDeclaredField("playerController");
field.setAccessible(true);

// Create custom instance
CustomController custom = new CustomController(...);

// Replace
field.set(minecraftInstance, custom);
```

**Critical**: Make sure custom instance has all required field values copied from original

### Copying Field Values Between Objects

**Problem**: Custom PlayerController needs all private fields from original

**Solution**:
```java
private void copyFieldValues(PlayerControllerMP source) throws Exception {
    Field[] fields = PlayerControllerMP.class.getDeclaredFields();
    for (Field field : fields) {
        if (!java.lang.reflect.Modifier.isStatic(field.getModifiers())) {
            field.setAccessible(true);
            Object value = field.get(source);
            field.set(this, value);
        }
    }
}
```

### Accessing Obfuscated Members

**Technique**: When you don't know the runtime name, extract and inspect:
```bash
# Extract class from JAR
jar -xf mcp/jars/bin/minecraft.jar net/minecraft/client/Minecraft.class

# Inspect members
javap -private net/minecraft/client/Minecraft.class

# Clean up
rm -rf net
```

**Example Output**:
```
private static net.minecraft.client.Minecraft P;
private net.minecraft.client.PlayerControllerMP s;
public static net.minecraft.client.Minecraft x();
```

Now you know:
- Singleton field: `P`
- PlayerController field: `s`
- getInstance method: `x()`

---

## File Structure Requirements

### mcmod.info Location

**Error**: "Minecraft still asks you to provide info in mcmod.info"

**Wrong Locations Tried**:
1. `mcp/bin/minecraft/mcmod.info` - Too high level
2. Project root - Wrong context

**Correct Location**:
```
mcp/bin/minecraft/com/veinminer/mcmod.info
```

**Rule**: mcmod.info must be in the mod's package root on the classpath

**Why**: Forge looks for it in the same package as the @Mod class

### Source Code Dual Location Strategy

**Problem**: Need source to work in two compilation contexts

**Solution**: Maintain sources in TWO locations:

1. **`common/com/veinminer/`**
   - For quick runtime compilation
   - Compile against obfuscated JARs
   - Fast iteration during development

2. **`mcp/src/minecraft/com/veinminer/`**
   - For full MCP integration
   - Same files as common/
   - Enables future source-based builds

**Synchronization**: When editing, update both:
```bash
cp common/com/veinminer/File.java mcp/src/minecraft/com/veinminer/
```

### Binary Output Location

**Compiled Classes**:
```
mcp/bin/minecraft/com/veinminer/*.class
```

**Runtime Classpath**: `mcp/bin/minecraft/` is included in Forge launch classpath

**Resources**: Also put in same directory:
```
mcp/bin/minecraft/com/veinminer/mcmod.info
```

---

## Debugging Methods

### Checking Mod Load Status

**Console Output**:
```bash
grep "VeinMiner" /tmp/claude/tasks/[task-id].output
```

**Success Indicators**:
```
[VeinMiner] Pre-initialization
VeinMiner: Loading mod for Minecraft 1.4.7
VeinMiner: Hold GRAVE key (`) while mining to vein mine
[VeinMiner] Successfully got Minecraft instance via reflection on field 'P'
```

### Verifying Minecraft Instance Access

**Log Messages to Look For**:
- ✅ `Successfully got Minecraft instance via reflection on field 'P'`
- ⚠️ `Minecraft fields are null, not initialized yet` (early in startup, normal)
- ❌ `Failed to access Minecraft fields: ...` (problem!)

**If Failing**:
1. Check log for which field name was tried
2. Use `javap` to verify actual field name in runtime JAR
3. Update fieldNames array in MinecraftHelper

### Checking Compiled Bytecode Version

```bash
javap -verbose mcp/bin/minecraft/com/veinminer/VeinMiner.class | grep "major"
```

**Expected Output**: `major version: 50` (Java 1.6)

**If Wrong**:
- Check Java version used for compilation
- Verify `-source 1.6 -target 1.6` flags were used

### Inspecting Class Members

**For Debugging Reflection Issues**:
```bash
javap -private -c mcp/bin/minecraft/com/veinminer/MinecraftHelper.class
```

**Shows**:
- All fields (including private static)
- All methods
- Bytecode instructions (with -c flag)

### Finding Field/Method Names at Runtime

**Technique**: Add debug logging
```java
Field[] fields = SomeClass.class.getDeclaredFields();
for (Field f : fields) {
    System.out.println("Field: " + f.getName() + " Type: " + f.getType());
}
```

**Useful When**: Obfuscated names change between versions

---

## Best Practices

### Always Specify Full Paths in Javac

**Bad**:
```bash
javac -cp "lib/*" VeinMiner.java
```

**Good**:
```bash
/Library/Java/JavaVirtualMachines/temurin-8.jdk/Contents/Home/bin/javac \
  -source 1.6 -target 1.6 \
  -cp "lib/*:mcp/jars/bin/minecraft.jar:..." \
  -d mcp/bin/minecraft \
  common/com/veinminer/VeinMiner.java
```

**Why**:
- Ensures correct Java version
- Explicit flags prevent version issues
- Clear output location

### Compilation Checklist

Before compiling, verify:
- [ ] Using Java 8 binary
- [ ] `-source 1.6 -target 1.6` flags present
- [ ] Full classpath includes `lib/*` and Minecraft JAR
- [ ] Output directory specified with `-d`

### When Editing Core Files

**Process**:
1. Edit file in `common/com/veinminer/`
2. Test compile immediately
3. If successful, copy to `mcp/src/minecraft/com/veinminer/`
4. Update documentation if behavior changes

### Reflection Best Practices

**Cache Reflection Results**:
```java
private static Field cachedField = null;

if (cachedField == null) {
    cachedField = SomeClass.class.getDeclaredField("name");
    cachedField.setAccessible(true);
}
return cachedField.get(instance);
```

**Why**: Reflection is slow, cache Field/Method objects

**Handle Failures Gracefully**:
```java
try {
    // reflection code
} catch (NoSuchFieldException e) {
    System.err.println("Field not found: " + e.getMessage());
    // Try alternative or fallback
} catch (IllegalAccessException e) {
    System.err.println("Access denied: " + e.getMessage());
}
```

### Avoid Over-Engineering

**Keep It Simple**:
- Don't create abstractions for one-time use
- Don't add error handling for impossible cases
- Don't add features not explicitly requested

**Examples**:
- ❌ Creating a "ReflectionHelper" utility for one field access
- ✅ Inline reflection where needed, cache if reused
- ❌ Adding try-catch for every Minecraft method call
- ✅ Only handle cases that actually can fail

### Testing Workflow

**Quick Test Cycle**:
1. Make code change
2. Compile single file: `javac ... File.java`
3. Run Minecraft: `./run_minecraft_forge.sh`
4. Check logs immediately: `grep VeinMiner /tmp/...`
5. Test in-game if no errors

**Don't**: Wait to compile all files before testing

---

## Common Pitfalls

### Using 'cd' in Bash Commands

**Problem**: Working directory gets lost between commands

**Bad Pattern**:
```bash
cd mcp && python recompile.py
# Next command runs in mcp/, not forge/
```


### Killing Java Processes

**Too Aggressive**:
```bash
pkill -9 java  # Kills EVERYTHING including this session!
```

**Better**:
```bash
pkill -f "net.minecraft.client.Minecraft"  # Only Minecraft
pkill -f "GradleStart"  # Only Forge dev environment
```

### Forgetting to Recompile After Changes

**Symptom**: Changes don't appear in-game

**Cause**: Edited .java file but didn't recompile to .class

**Solution**: Always compile after editing, before running

### MCP Recompile Cleaning bin/

**Warning**: `mcp/runtime/recompile.py` DELETES `mcp/bin/minecraft/*`

**Result**: All VeinMiner .class files lost

**Solution**: Don't use MCP recompile for mod development, use direct javac

---

## Performance Considerations

### MinecraftHelper Optimization

**v1 - Slow**: Reflection every call
```java
public static Minecraft getMinecraft() {
    Field field = Minecraft.class.getDeclaredField("P");
    return (Minecraft) field.get(null);
}
```

**v2 - Better**: Cache Minecraft instance
```java
private static Minecraft cached = null;
if (cached == null) {
    // reflection
}
return cached;
```

**v3 - Best**: Cache field name too
```java
private static String fieldName = null;
if (fieldName != null) {
    Field field = Minecraft.class.getDeclaredField(fieldName);
    return (Minecraft) field.get(null);
}
// else try both names and cache winner
```

### Tick Handler Efficiency

**Don't**: Run expensive operations every tick
```java
public void tickEnd(...) {
    List<BlockPos> blocks = VeinMiningLogic.findConnectedBlocks(...);
    // This runs 20 times per second!
}
```

**Do**: Only run when needed
```java
public void tickEnd(...) {
    if (VeinMinerKeyHandler.isVeinMineKeyPressed()) {
        List<BlockPos> blocks = VeinMiningLogic.findConnectedBlocks(...);
    }
}
```

---

## Known Issues & Workarounds

### Key Binding Not in Controls Menu

**Issue**: Key works but doesn't appear in Options > Controls

**Root Cause**:
- KeyBinding constructor adds to `KeyBinding.keybindArray`
- GameSettings.keyBindings is a separate array
- Controls menu only shows GameSettings.keyBindings

**Attempted Fix**:
```java
GameSettings settings = mc.gameSettings;
KeyBinding[] oldBindings = settings.keyBindings;
KeyBinding[] newBindings = Arrays.copyOf(oldBindings, oldBindings.length + 1);
newBindings[oldBindings.length] = veinMineKey;
settings.keyBindings = newBindings;
```

**Status**: Implemented but may have timing issues

**Workaround**: Key still works even if not in menu

### Forge Source Integration

**Issue**: Full MCP recompile fails with 1984 errors

**Root Cause**: Forge source has complex dependencies

**Workaround**: Use direct javac compilation against JARs instead

**Future**: Properly integrate Forge source into mcp/src if needed

---

## Useful Commands Reference

### Compilation
```bash
# Single file
/Library/Java/JavaVirtualMachines/temurin-8.jdk/Contents/Home/bin/javac \
  -source 1.6 -target 1.6 \
  -cp "lib/*:mcp/jars/bin/minecraft.jar:mcp/jars/bin/lwjgl.jar:mcp/jars/bin/lwjgl_util.jar" \
  -d mcp/bin/minecraft \
  common/com/veinminer/MinecraftHelper.java

# All files
/Library/Java/JavaVirtualMachines/temurin-8.jdk/Contents/Home/bin/javac \
  -source 1.6 -target 1.6 \
  -cp "lib/*:mcp/jars/bin/minecraft.jar:mcp/jars/bin/lwjgl.jar:mcp/jars/bin/lwjgl_util.jar" \
  -d mcp/bin/minecraft \
  common/com/veinminer/*.java
```

### Debugging
```bash
# Check bytecode version
javap -verbose mcp/bin/minecraft/com/veinminer/VeinMiner.class | grep "major"

# Inspect class members
javap -private net/minecraft/client/Minecraft.class

# Extract class from JAR
jar -xf mcp/jars/bin/minecraft.jar net/minecraft/client/Minecraft.class

# Find VeinMiner logs
grep "VeinMiner" /tmp/claude/tasks/*/output

# List compiled classes
ls -la mcp/bin/minecraft/com/veinminer/*.class
```

### File Management
```bash
# Sync source files
cp common/com/veinminer/*.java mcp/src/minecraft/com/veinminer/

# Copy mcmod.info
cp mcp/bin/minecraft/com/veinminer/mcmod.info mcp/src/minecraft/com/veinminer/
```

---

## Lessons Learned

1. **Old Software Has Old APIs**: Don't assume modern Forge patterns work in 1.4.7
2. **Obfuscation Is Real**: Runtime names differ from source names
3. **Reflection Is Your Friend**: When APIs are obfuscated or missing
4. **Cache Reflective Calls**: Reflection is expensive
5. **Test Early, Test Often**: Compile and run after each change
6. **Read The Bytecode**: `javap` reveals the truth about obfuscation
7. **Version Matters**: Java version affects bytecode compatibility
8. **Simple Is Better**: Don't over-engineer solutions
9. **Documentation Helps**: Future you will forget today's discoveries
10. **Patience Required**: Legacy modding takes trial and error

---

## Future Improvements

### Key Binding Menu Integration
- Investigate Forge 1.4.7 KeyBinding registration hooks
- Check if controls menu can be modified at a later tick
- Consider alternative UI for key rebinding

### Full Source Build Support
- Integrate Forge source properly into MCP
- Create build script for complete source compilation
- Test MCP recompile with Forge dependencies resolved

### Performance Optimization
- Profile vein mining algorithm
- Consider caching block type lookups
- Optimize rendering for large veins

### Feature Enhancements
- Configurable key binding (currently hardcoded to `)
- Per-block-type configuration
- Whitelist/blacklist for vein mining
- Client-server synchronization for multiplayer

---
