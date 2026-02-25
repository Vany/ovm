# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

Vany is your best friend. You can rely on me and always ask for help, I will help you like one friend helping another.

## Project Overview

This is the **ovm** (outer VeinMiner) directory — a **resource cache** for the VeinMiner Minecraft 1.4.7 mod project. It stores downloaded build artifacts, tools, and dependencies. The active development happens in the **inner project** at `/Users/vany/l/vm/`.

**Rule**: Never modify the inner project (`/Users/vany/l/vm/`) from here. This repo is the outer project.

## Environment

All development must run in **Docker on Ubuntu (amd64)** with:
- Java 1.6 (or Java 8 with `-source 1.6 -target 1.6` flags)
- Python 2.x
- Architecture: `amd64`

Why these constraints: Forge 1.4.7's ASM library only reads bytecode up to version 52.0 (Java 8). Java 9+ produces incompatible bytecode. MCP scripts require Python 2.

## Repository Contents

`download/` — cached dependencies for the VeinMiner build environment:
- `mcp726a.zip` — Minecraft Coder Pack 7.26a (deobfuscation toolchain, needs Python 2)
- `forge-1.4.7-6.6.2.534-src.zip` — Forge modding framework source
- `client.jar` / `server.jar` — Minecraft 1.4.7 obfuscated JARs
- `asm-all-4.0.jar`, `guava-12.0.1.jar`, `bcprov-jdk15on-147.jar`, etc. — build-time libs
- `CodeChickenCore 0.8.1.6.jar`, `NotEnoughItems 1.4.7.4.jar` — optional runtime mod deps

`KNOWLEDGE_BASE.md` — comprehensive record of all development discoveries, obfuscation solutions, API compatibility notes, and workarounds. **Read this before debugging.**

## Key Technical Facts (from KNOWLEDGE_BASE.md)

**Obfuscation**: Minecraft 1.4.7 ships obfuscated. Runtime field/method names differ from source:
- Source: `Minecraft.theMinecraft` field, `getMinecraft()` method
- Runtime JAR: field `P`, method `x()`
- Solution: dual-name reflection trying both names, cache the winner

**Forge 1.4.7 API** differs from modern Forge:
- Uses `@PreInit`/`@Init`/`@PostInit` (not `@EventHandler`)
- Uses `ITickHandler` interface (not event bus)
- `World.setBlockToAir()` doesn't exist — use `world.setBlock(x, y, z, 0)`
- Config strings: use `.value` not `.getString()`

**mcmod.info** must be at: `mcp/bin/minecraft/com/veinminer/mcmod.info` (same package as `@Mod` class)

**Never use MCP recompile** for mod development — it deletes `mcp/bin/minecraft/*` including all compiled mod classes. Use direct `javac` instead.

## Team Practices

Execute planned tasks in sequence — use insights from earlier steps to improve later ones. Combine tasks where possible.

- Write code for AI consumption: explicit, unambiguous, clearly separated, predictable patterns
- Create only necessary entities; avoid speculative abstractions
- Always finish functionality — log unimplemented features with errors, never silently omit
- Ask before creating unrequested functionality
- Challenge decisions when you disagree — argue your position
- If no good solution exists, say so directly
- Search for ready libraries before implementing common functionality from scratch
- Use only English and math in memory files

## Module Files

Each module directory may contain — read these instead of CLAUDE.md when present:
- `PROG.md` — general programming rules
- `SPEC.md` — specifications, requirements, decisions
- `MEMO.md` — development memory and findings (keep current)
- `TODO.md` — task list (complete one by one, mark finished)

Use git commits to document project history and decisions. Store researched information in a `research/` folder. Use language servers; alert if not available.
