# Far Future todo List

- mod must have all fancy information files. author = vany ivan@serezhkin.com

- icon for mod 

- we are using configurable via minecraft configuration button. By default it is '`' tilda. it is modifying button, when it is hold down we are performing veinmining instead of regular mining. veinmining is mining more than one block in the same operation.
- when modified key is down and apropriate tool points on veinmineable block, calculate veinmined area and highlight (with most easiest way, outline, glow) all blocks that will be mined if veinmine operation will be started. Start of veinmining is start to mine block with modifier button is hold down.

- mining should trigger correct blockupdates.

- veinmining performed on 26 direction connected blocks.
 
- for veinmining player must have appropriate tool with correct harvest level for mined blocks in hands.

- wood and leaves may be mined too.

- for leaves and wool apropriate tool everything.

- veinmining spends hunger. It is 32 blocks per one hunger point. This parameter is configurable. if here is not enough hunger - mining operation not starts.

- mining spends durability of tool. like this tool was used to mine all this blocks one by one. Mining use silktouch or fortune and all other enchantments on tool, So let's make each block is mined one by one by the tool. if the durability ends, ve are stop the mining process prematurely. All mining happens in the same one tick.

- maximum of mined block by one mine operation is configurable and 64 by default.

- when blocks is mined it may be dropped inplace or placed into player's inventory. if here is no space in inventory, it may be dropped on the ground in the player's place. This behavior must be configurable and default to place into inventory.


- Vein calculation on server, Preview on client, Final validation on server before mining


# Vein Mining Algorithm Specification  
**Maximal-Radius Priority Flood Fill (26-Connected)**

## 1. Purpose

This document specifies an algorithm for vein mining in Minecraft-like voxel worlds.

The algorithm removes **exactly N blocks** from a natural ore vein such that:

- All removed blocks are connected to the starting block
- Connectivity is **26-connected** (faces, edges, vertices)
- The mined cavity has **maximum possible radius**
- The cavity shape is as close as possible to a **sphere**
- Mining can occur in **batches** (multiple blocks per tick)

This is a modification of flood fill using **priority-based expansion**.

## 2. Definitions

### 2.1 Block Position
A block position is a 3D integer coordinate: (x,y,z)


### 2.2 Target Block Type
Only blocks of the same type as the starting block are eligible for mining.

### 2.3 Connectivity
Two blocks are considered connected if their coordinates differ by:
dx, dy, dz ∈ {-1, 0, 1} and not all zero


This yields **26 neighbors per block**.

---

## 3. Problem Statement (Formal)

Given:
- A starting block position `S`
- A target block type `T`
- A maximum number of blocks to mine `N`

Find a set `M` of block positions such that:

1. `|M| ≤ N`
2. Every block in `M` is of type `T`
3. `M` is 26-connected
4. `S ∈ M`
5. The maximum Euclidean distance from `S` to any block in `M` is **minimal**

This ensures the mined cavity has the **largest possible radius** for the given block count.

---

## 4. Core Idea

The algorithm performs a **priority-driven flood fill**, expanding outward from the starting block in order of increasing Euclidean distance.

At each step:
- The *closest reachable block* is mined next
- Expansion continues until `N` blocks are mined or no more blocks are reachable

This guarantees optimal cavity compactness for **any prefix size**.

---

## 5. Distance Metric

For a block `p = (x, y, z)` and start block `S = (sx, sy, sz)`:

dx = x - sx
dy = y - sy
dz = z - sz

distanceSquared = dx² + dy² + dz²


- Squared distance is used (no square root)
- Ordering by squared distance preserves correctness

Optional anisotropy (natural veins):
distanceSquared = dx² + dz² + (dy * k)² where k > 1


---

## 6. Data Structures

### Required

- `PriorityQueue<BlockPos>`  
  Ordered by `distanceSquared` (ascending)

- `Set<BlockPos> visited`  
  Prevents revisiting blocks

- `List<BlockPos> mined`  
  Stores blocks selected for mining

### Optional

- Batch size `K` (blocks mined per tick)
- Hard cap on visited size (performance safety)

---

## 7. Algorithm Specification

### 7.1 Initialization

1. Let `origin = S`
2. Insert `origin` into the priority queue with priority `0`
3. Mark `origin` as visited
4. Initialize `mined = empty list`

---

### 7.2 Main Loop

Repeat until `mined.size == N` or the queue is empty:

p = pq.pop() // block with minimal distanceSquared
add p to mined

for each of the 26 neighbors n of p:
if n is not visited:
if block at n is of type T:
mark n as visited
compute distanceSquared(n)
push n into pq


---

### 7.3 Mining Execution

Mining may occur:
- Immediately upon selection, or
- Deferred and executed in batches

Example batch execution:
each tick:
mine next K blocks from mined list

---

## 8. Correctness Guarantees

### 8.1 Connectivity
- Every mined block is reached from an already-mined block
- 26-connectivity is preserved by construction

### 8.2 Optimal Radius
For any `k ≤ N`:
- The first `k` mined blocks minimize the maximum distance from the origin
- No other connected set of `k` blocks can produce a smaller enclosing radius

### 8.3 Shape
- The mined volume converges to a discrete sphere
- No tunnels, branches, or disconnected cavities occur

---

## 9. Performance Characteristics

### Time Complexity
O(N log N)

- Each block is inserted into the priority queue once

### Space Complexity
O(N)

- Visited set and priority queue are bounded by `N`

---

## 10. Comparison With Alternatives

| Method | Result |
|------|-------|
| DFS flood fill | Tunnels |
| BFS layers | Cube-shaped cavity |
| Random expansion | Disconnected shapes |
| Full fill + sort | Wasted work |
| **Priority flood fill** | Optimal |

---

## 11. Implementation Notes (Minecraft)

- Suitable for old Minecraft versions (1.4.7+)
- Avoid recursive calls (use iterative loop)
- Prefer integer math
- Mining should be scheduled to avoid tick lag
- Block updates should be deferred if possible

---

## 12. Summary

This algorithm is a **priority-based modification of flood fill** that:

- Mines entire natural veins
- Preserves diagonal connectivity
- Produces maximally compact, spherical cavities
- Supports partial mining with guaranteed optimality

It is the correct solution for controlled vein mining in voxel worlds.
