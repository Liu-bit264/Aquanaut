# Spawn-island flank fog, rewriter noise, and the review cleanups

Report on commit `68b39b3` (`biomes/initial_island`, pushed to the fork `Liu-bit264/Aquanaut`,
not to upstream). Follows a review of the branch plus four parallel quality subagents
(correctness, simplification/reuse, performance, test coverage) reconciled against an inline
read of the code.

## How to read this

Every item is stated in three parts:

- **Direction** — what changes and in one line why.
- **Evidence & necessity** — how the defect was established (measured or derived from the
  constants, never assumed), and what proves the fix is both required and sufficient.
- **Fix** — the exact edit, what it deliberately leaves alone, and what verifies it.

Items are ordered by severity, not by file.

## Scope

Ruled out before any edit:

- **No upstream merge was needed.** `upstream/main` (`19db5d8`) is an ancestor of the branch
  tip (`d53f26e`), verified with `git merge-base --is-ancestor`.
- The mixin targets had already been checked against the real decompiled
  `neoforge-21.1.233-sources.jar` in `build/moddev/artifacts/`, so the correctness agent's two
  "unverified" flags were resolved rather than carried forward.
- Only findings that survived re-reading the code are listed. Several subagent suggestions
  (e.g. reordering the stony-shore and mask probes, widening the shore band, threading the
  island decision into the sampler) were dropped as behaviour-changing or unprovable.

---

## 1. The island's submerged flank was rendering as land

### Direction

`island_beach` also owns the sand apron that runs out to the open ocean *below* the waterline.
A fog profile is read only with the camera under water, so the beach's land profile opened a
clear bubble in the middle of the sea, and the flank carried no water spawners at all. Fix the
biome's fog profile and spawn tables — not the biome map.

### Evidence & necessity

**The claim is far wider than the beach.** `BiomeRewriter.islandSurfaceAt` returns
`SHORE` for every column with `mask > 0`, across quart Y 10–25 (block Y 40–103). But
`SpawnIslandMask.maskAt` is

```
maskAt = smoothstep((coastFieldAt(x,z) + fadeWidthAt(x,z) - dist) / fadeWidthAt(x,z))
```

so `mask = 1` at the coastline field and `mask = 0` only at `coastField + fade`, with
`fade ∈ [FADE_MIN, FADE_MAX] = [149.6, 202.4]` blocks. `mask > 0` therefore reaches out to
`MAX_FADE_RADIUS = COAST_MEAN_RADIUS + COAST_AMPLITUDE + FADE_MAX = 112 + 56 + 202.4 = 370.4`
blocks.

**Most of that ground is under water.** `OceanColumnPlanner.rawFloorAt` lifts the floor with
`islandShelfBase` (toward the cap top, `cap_top_min_y..cap_top_max_y` = 35–40) and then
`SpawnIslandMask.blendFloor`, which lerps the base toward `max(base, ISLAND_TOP_Y = 70)` on
`mask^BLEND_SHAPE_EXPONENT (1.3)`. Plugging the actual constants in (raw floor 0, which yields
the lowest — most submerged — result, since both `islandShelfBase` and the blend target rise
with the raw floor):

| `mask` | planned floor Y | vs sea level 63 | biome today |
| --- | --- | --- | --- |
| 0.25 | ≈ 16 | **47 below** | `island_beach` |
| 0.50 | ≈ 39 | **24 below** | `island_beach` |
| 0.75 | ≈ 58 | 5 below | `island_beach` |
| ≈ 0.83 | 63 | waterline | `island_beach` |
| 1.00 | 70 | plateau | `island_plains` / `island_hills` |

Since `smoothstep(u) = 0.83` at `u ≈ 0.74`, the waterline sits at
`dist ≈ coastField + 0.26 × fade`: the emerged apron is only the outermost quarter of the fade
(≈ 37–51 blocks of a 150–202-block band), and the inner three quarters — **≈ 112–152 blocks per
bearing** — are submerged ground claimed as beach. (These are arithmetic from the shipped
constants, not an in-game measurement — but the direction is not in doubt: at `mask = 0.5` the
planner's own floor target is 39, twenty-four blocks under the water it is standing in.)
`SpawnIslandMask` documents the same threshold from the other side: `ISLAND_STRUCTURE_MASK =
0.75`, "even that blends to 62.2 at the threshold — under the sea level 63".

**Why that is user-visible, not cosmetic.** `ClientFogState.update` consults the fog profile
only in its submerged branch:

```java
if (medium.submerged() && level != null) {
    FogField.Blend blend = FogField.sample(level, camera.getPosition());   // reads level.getBiome(...)
    aim(..., blend.visibility(), ramp, seconds);
} else {
    // Air: the world's own fog is left alone...
}
```

`FogField` samples the biome at the camera and six neighbours (±10 blocks) and blends the
table's profiles. `aquanaut:island_beach` declared `near -4 / far 256 / cast 0`, and
`FogVisibility` clamps `far` to `MAX_FAR_PLANE = 192`. The open ocean around the island is
`minecraft:deep_ocean` — what the stack's `surface_ocean` layer writes (band 40–320, weight 1.0)
over every supported column — and it is not in the table, so it resolves to `default`:
**far 96, cast 0.45**. Diving anywhere
on the flank moved the eye from 96 blocks to 192 and removed the medium's cast entirely: a
clear bubble ~112–152 blocks wide ringing the spawn island, blended over only ~10 blocks at
its edge.

**The flank was also dead water.** `island_beach.json` carried `"water_creature": []` and
`"water_ambient": []` — copied verbatim from vanilla `beach`, which has no water spawners
because vanilla beaches are a shoreline, not a seabed. Here the same biome covers the seabed.

**Why the data-side fix and not the biome map.** Two facts make narrowing the claim the worse
option, even though it is the more obvious "correct" one:

1. `OceanColumnShading.islandStateFor` deliberately shades the entire `mask < 1` band as sand
   over sandstone — "dug open exactly like a vanilla beach island — no mod blocks on any
   surface the player walks on". The biome map matches an intentional terrain design; narrowing
   the biome would leave `deep_ocean` sitting on mod-owned sand and re-open the question of
   what the flank is.
2. The claim is load-bearing for something else. `islandSurfaceAt`'s band starts at block Y 40
   precisely so that `frozen_ocean` iceberg features — whose placement reads the biome at the
   sea surface — can never take root on an island column. Reverting those columns to the
   underlying noise biome would re-open iceberg generation on the shelf.

A fog/spawn-table mismatch cannot justify a biome-map change that alters worldgen for every
seed and needs eyes on a running client. The defect is entirely in the two things a land biome
cannot supply under water, so that is where it was fixed.

**Why the fix is sufficient.** Colour was never wrong, which bounds the blast radius:
`island_beach` carries vanilla beach's `water_fog_color 329011` and `water_color 4159204`, and
vanilla `deep_ocean` uses those same two values. Only the profile and the spawn lists disagreed.

### Fix

`assets/aquanaut/fog_profile.json` — `island_beach` moves from the land profile to the one the
surrounding `deep_ocean` already resolves to, so the flank blends into the open ocean instead of
standing out of it:

```json
"aquanaut:island_beach": {
  "__comment": "Unlike its three neighbours this biome is mostly under water: ... they mirror
                the fallback, which is what the deep_ocean around the island resolves to, so the
                flank blends into the open ocean instead of opening a clear bubble in it.",
  "near": -4.0, "far": 96.0, "cast": 0.45
}
```

The three genuinely land-based entries keep their clear profile, and their comments now say what
that profile can actually reach: air is not this table's business, so `island_plains`' entry only
ever describes the plateau pond (where clear water is right) and `island_hills` /
`island_stony_shore` only describe bodies a player builds.

`data/aquanaut/worldgen/biome/island_beach.json` — the flank gets the surface ocean's sea life,
matching `middle_level_ocean`:

```json
"water_ambient":  [ { "minecraft:cod",      maxCount 6, minCount 3, weight 8 } ],
"water_creature": [ { "minecraft:squid",    maxCount 4, minCount 1, weight 2 },
                    { "minecraft:dolphin",  maxCount 2, minCount 1, weight 1 } ]
```

Deliberately untouched: the spawn claim itself (so the iceberg guard is preserved by
construction), the terrain shading, and the other three island biomes.

New regression tests, asserting the data because the data is the fix:

- `FogTableTest.theIslandBeachReadsAsSeaUnderWater` — the shipped `island_beach` entry must
  equal the shipped fallback.
- `IslandBiomeResourcesTest.islandBeachCarriesTheSeaLifeOfItsSubmergedFlank` — squid, dolphin
  and cod must be present.

---

## 2. `stoneShoreWeight` and `lavaPoolAt` were paid for at every block height

### Direction

`OceanColumnShading.islandStateFor` runs once per block Y per column during the fill. Two
noise-heavy island probes inside it were evaluated at heights where their answer cannot change
anything. Evaluate them only inside the window where they matter.

### Evidence & necessity

**The waste is provable by reading the two branches** of the `mask >= 1` split:

| depth below floor | stony shore branch | grass plateau branch |
| --- | --- | --- |
| 0 | ore speckle, else stone | pond / sand patch → sand, crag → stone, else grass |
| 1–3 | stone | dirt |
| 4 | `floorStateFor(...)` | limestone |
| ≥ 5 | `floorStateFor(...)` | `floorStateFor(...)` |

(Stony shore at depth ≤ 1 is lava wherever `lavaPoolAt` says so — the same five-block window.)

From depth 5 down the two branches are **identical** — both fall through to the regional
lithology at the same depths, so the weight cannot change a single block there. Depth ≤ 4 is
therefore the exact window in which it can change anything: that is the whole span where the two
faces differ. So the stony-shore weight, a `SoftMixNoise.valueNoise` plus a `sqrt`, was being
sampled at every level of every plateau column to decide a five-block window.

**The short-circuit was the wrong way round.** The lava-pool test read

```java
if (SpawnIslandMask.lavaPoolAt(plan.islandSeed(), plan.blockX(), plan.blockZ()) && depth <= 1)
```

with the expensive call on the left, so `lavaPoolAt` — which internally samples *another*
`stoneShoreWeight` — ran at every height, only for the cheap `depth <= 1` on its right to
discard the result for all but at most two levels out of roughly two hundred.

### Fix

`layers/OceanColumnShading.java`:

- New constant `ISLAND_SURFACE_DRESSING_DEPTH = 4`, documented as the depth at which the two
  island faces converge.
- The stony-shore probe is hoisted behind `depth <= ISLAND_SURFACE_DRESSING_DEPTH`, which is
  exactly the decision window from the table above — so the result is identical block for block.
- The lava-pool test is swapped to `depth <= 1 && lavaPoolAt(...)`.
- The nine inline `com.dexer.aquanaut.common.worldgen.blend.*` qualified names are replaced
  with imports.

Net effect per plateau column: the stony-shore weight goes from ~one call per block to a handful,
and the lava-pool probe from ~200 calls to at most two.

**Equivalent by inspection, and that is the honest limit of the claim** — no test pins the exact
block state at the depth-4 boundary. See "What the tests do not cover".

---

## 3. The island column decision was re-derived 16 times per column

### Direction

Nothing in the island biome decision depends on the column's height, but `BiomeRewriter.rewrite`
called it once per quart level. Resolve it once per column.

### Evidence & necessity

`rewrite` walks `quartY` from `minQuartY` to `maxQuartY` and asks `dominantBiome` for each level.
From the shipped `default_deep_stack.json`, the layer bands give `minRewriteQuartY = -16`
(`deep_sea` at `min_y = -64`) and `maxRewriteQuartY = 80` (`surface_ocean` at `max_y = 320`), so
the loop spans the entire stack band while the island band (quart 10–25) is 16 levels of it —
sixteen evaluations of the same height-independent answer.

Each in-band call reached `islandSurfaceAt`, which sampled `stoneShoreWeight`, then `maskAt` —
and `maskAt` → `coastFieldAt` constructs a **fresh `BoundaryWarp.Fbm` on every call** (four
value-noise octaves through a domain warp), then `applyBays` → `bayCenterAngles` +
`bayProfiles` — and on the plateau, `reliefAt`. All of it, sixteen times per column, for an
answer that is a pure function of `(seed, quartX, quartZ)`.

### Fix

`layers/BiomeRewriter.java`:

- The decision splits into `islandSurfaceColumn(spawnIsland, seed, quartX, quartZ)` (height
  independent) and `inIslandSurfaceBand(quartY)`; `rewrite` resolves the column once, before the
  `quartY` loop, and `dominantBiome` takes the resolved value and applies the band check.
- A cheap `islandBandPossible` guard keeps a chunk whose quart range misses the band from
  paying anything at all.
- The body is otherwise the original sequence of the same pure functions in the same order, so
  the decision is bit-identical; `islandSurfaceAt` remains as the band-aware wrapper the unit
  test drives.
- Inline fully-qualified names (`ChunkAccess`, `QuartPos`, `Function`, `SpawnIslandMask`) are
  replaced with imports.

Verified by `BiomeRewriterIslandSurfaceTest` (5 tests, including `decisionIsStablePerColumn`).

---

## 4. `bayProfiles` allocated per block, and the bay layout was falling short

### Direction

The per-bay profiles are per-seed constants reached from the per-block mask evaluation, and
`bayCenterAngles` right beside them was already memoized. Memoize them too, and give the layout
search enough budget to satisfy its own rules.

### Evidence & necessity

**Allocation on a per-block path.** `SpawnIslandMask.coastFieldAt` calls `applyBays`, which calls
`bayProfiles(islandSeed)`. The old body allocated `new double[count][3]` and ran about a dozen
`scramble` calls *every time* — i.e. on every block sampled through `maskAt`, which includes the
chunk fill, the cliff guard's samples and every quart column of the biome rewrite. Its
neighbour `bayCenterAngles` had carried a single-entry per-seed memo (with a `// Benign race:`
note) while `bayProfiles` did not.

**A minority of seeds miss the layout rules.** A standalone probe over 5000 seeds found:

```
seeds=5000  anyViol=366  sep=0  dev=219  quarry=211
worstSep=999.00  worstDev=19.65°  worstQuarry=50.47°
```

366 seeds (7.3%) end up with a layout violating at least one of the three documented rules (bay
separation, even-grid deviation, quarry clearance). `999` is the probe's sentinel for "this rule
was never violated", and `worstX` is the tightest margin seen across the run.

**Proof this is a search budget, not a logic bug.** `bayLayoutScore` returns

```
min( minSeparation - BAY_MIN_SEPARATION_DEG,
     maxDeviation  - BAY_EVEN_TOLERANCE_DEG,
     minClearance  - BAY_QUARRY_CLEARANCE_DEG )
```

The deviation term uses the **maximum** deviation across gaps, so it is satisfied when *some*
gap sits at least `BAY_EVEN_TOLERANCE_DEG` off the even grid — which is exactly what the
javadoc promises ("so the layout never reads as a symmetric pair or star"). Doc and code agree;
the comparison is not inverted, and separation is never the violated rule. The failures are
seeds where 64 draws could not satisfy three simultaneous constraints.

**Proof the budget raise cannot regress anything.** `computeBayCenterAngles` redraws the rotation
per attempt (`scramble(hash, BAY_ROTATION_SALT + attempt)`, so attempt *i* is independent),
scores it, keeps the best, and breaks immediately on a pass. Attempts 0..63 are byte-identical
to before, so a seed that already passed returns the same layout on the same attempt, and a seed
that was short simply gets more candidates under the same `score > bestScore` rule. The returned
score is therefore monotonically non-decreasing in the budget — never worse, sometimes better.
Cost is one-time per seed behind the cache.

### Fix

`worldgen/blend/SpawnIslandMask.java`:

- `bayProfiles` gains the same single-entry memo as `bayCenterAngles`, with its own
  `bayProfileCacheSeed` field so the two caches cannot be confused with each other, and its body
  moved to `computeBayProfiles`. Result is read-only; the javadoc says so ("do not mutate the
  result").
- `BAY_ANGLE_ATTEMPTS` 64 → 192, with the monotonicity argument recorded at the constant.

**Honest limit:** the probe was not re-run, so the *new* violation rate is unmeasured, and a
wider budget is not a proof of zero. Reaching zero would mean changing the rules or the angle
space, which reshapes island silhouettes for existing seeds — a design decision, not a cleanup.
See "Not done".

---

## 5. Three copies of "is this the water world?"

### Direction

One id, one predicate.

### Evidence & necessity

`NoiseBasedChunkGeneratorMixin`, `ChunkGeneratorStructureMixin` and `SpawnIslandPlacement` each
declared

```java
private static final ResourceLocation WATER_WORLD_SETTINGS =
        ResourceLocation.fromNamespaceAndPath("aquanaut", "water_world");
```

plus an identical `settings.unwrapKey().map(key -> key.location().equals(...)).orElse(false)`.
Meanwhile `WorldPresetRegistry` already owned the preset key, with a javadoc explaining that the
preset and the noise settings deliberately share the id. Three hand-copied spellings of an id
that must agree with a data file (`data/aquanaut/worldgen/noise_settings/water_world.json`) is
precisely the drift the registry class exists to prevent — and one of the three sites drives
structure suppression while another drives world-spawn placement, so a silent divergence would
be a worldgen bug, not a cosmetic one.

### Fix

`core/WorldPresetRegistry.java` gains `WATER_WORLD_SETTINGS` and
`isWaterWorld(Holder<NoiseGeneratorSettings>)`; all three sites delegate in one line. Imports
that went unused as a result (`Holder`, `NoiseGeneratorSettings`, `ResourceLocation`) were
removed from the two files that no longer need them. Behaviour is unchanged — the predicate is
the same expression on the same holder.

---

## 6. `.gitignore` named a path that could never match

### Direction

The rule was anchored to the repository root; the directory it was meant to ignore lives inside
the source tree.

### Evidence & necessity

`git status` showed `?? src/main/java/.mimosa/` while `.gitignore` said `/.mimosa`. A leading
slash anchors to the repository root, so the rule could only ever match a top-level `.mimosa/`.
The hook directories land beside the source they watch, which is the whole point of them.

### Fix

`.gitignore` — `/.mimosa` becomes `.mimosa/` (unanchored), under a new `### Tool scratch ###`
heading that says why it is unanchored. Verified:

```
$ git check-ignore -v src/main/java/.mimosa/
.gitignore:58:.mimosa/   src/main/java/.mimosa/
```

`/.zcode/` was added to the same group — same class of agent scratch at the root, showing up
untracked. `.mimocode` and `/.scratch` were left root-anchored: there is no evidence they appear
nested, and guessing would be editing by superstition.

---

## Change set

| File | Item |
| --- | --- |
| `src/main/resources/assets/aquanaut/fog_profile.json` | 1 |
| `src/main/resources/data/aquanaut/worldgen/biome/island_beach.json` | 1 |
| `src/main/java/com/dexer/aquanaut/common/worldgen/layers/OceanColumnShading.java` | 2 |
| `src/main/java/com/dexer/aquanaut/common/worldgen/layers/BiomeRewriter.java` | 3 |
| `src/main/java/com/dexer/aquanaut/common/worldgen/blend/SpawnIslandMask.java` | 4 |
| `src/main/java/com/dexer/aquanaut/core/WorldPresetRegistry.java` | 5 |
| `src/main/java/com/dexer/aquanaut/mixin/NoiseBasedChunkGeneratorMixin.java` | 5 |
| `src/main/java/com/dexer/aquanaut/mixin/ChunkGeneratorStructureMixin.java` | 5 |
| `src/main/java/com/dexer/aquanaut/common/worldgen/SpawnIslandPlacement.java` | 5 |
| `.gitignore` | 6 |
| `src/test/java/com/dexer/aquanaut/common/fog/FogTableTest.java` | 1 |
| `src/test/java/com/dexer/aquanaut/common/worldgen/IslandBiomeResourcesTest.java` | 1 |

12 files, +190 / −79. Commit `68b39b3d1c120ade3f034669dcb63dbfe06eae28` on `biomes/initial_island`,
pushed to `origin` (`Liu-bit264/Aquanaut`); `upstream/biomes/initial_island` untouched at
`46c2d52`.

## Verification

- `gradle build`: BUILD SUCCESSFUL (main, test, mixin annotation processing and refmap
  generation all clean).
- `gradle test`: **344 tests, 6 failed** — all six in `NotebookLayoutTest` / `NotebookCatalogTest`
  / `NotebookProgressTest`, none of them touched by this work.
- **The six were proven pre-existing rather than assumed.** The work was stashed, the Notebook
  tests were run against the clean `HEAD` tree, and the identical six failed
  (`7 tests completed, 6 failed`), then the stash was popped. Root causes: four
  `NoClassDefFoundError: com/mojang/datafixers/kinds/App` (a missing test-classpath dependency)
  and two screen-layout float assertions.
- **Non-Notebook: 337 tests, 0 failures** — including `SpawnIslandMaskTest` (23),
  `BiomeRewriterIslandSurfaceTest` (5), `FogTableTest` (9), `IslandBiomeResourcesTest` (5).
- Build tooling note: `gradlew` remains unusable in this workspace (PKIX failure fetching the
  wrapper distribution, plus `org.gradle.java.home=/usr/lib/jvm/java-21-graalvm` in
  `gradle.properties`). The system `gradle` was used with
  `-Dorg.gradle.java.home=E:/dev-tools/runtimes/jdk21 -Dorg.gradle.configuration-cache=false`.

## What the tests do not cover

Stated plainly, because each of these is a place where the reasoning above is stronger than the
evidence:

- **The depth-4 equivalence in `islandStateFor` is by inspection.** No test pins the block state
  at that boundary. A table test over depths 0–5 × {stony, plateau} would make item 2 as safe as
  the others.
- **The fog/spawn fix is data-only and was never observed in a running client.** The suite
  asserts the tables are right; that the flank no longer reads as a bubble follows from
  `ClientFogState`'s submerged branch and `FogField`'s kernel, which were read but not
  rendered. One dive on the island's flank in a dev client would close it.
- **The bay-layout violation rate after the budget raise is unmeasured.** Only the direction of
  the change is proven (monotone in the budget), not the new rate, and not that it reaches zero.

## Not done, and why

- **The bay layout rules themselves.** The remaining ~7% is a genuine constraint-satisfaction
  shortfall across three rules on 2–3 angles. Widening the budget is the only change that is
  monotone by construction; making it zero means relaxing a rule or enlarging the angle space,
  both of which move island coastlines. That needs a decision about how much coastline change is
  acceptable, not a cleanup.
- **Anything requiring a terrain change.** Item 1 could have been solved by restricting the
  `SHORE` claim to emerged columns, which is arguably the "more correct" model. It was rejected
  because it would change the biome map for every seed, leave `deep_ocean` over mod-owned sand,
  and re-open iceberg generation on the shelf — none of which can be validated without a client,
  for a defect that lives entirely in two data tables.
- **`.mimocode` / `/.scratch` un-anchoring** — no evidence they need it.
