<div align="center">

<img src="docs/card.png" alt="Dogs BaseHunting & Misc Tools" width="640">

# Dogs BaseHunting & Misc Tools for DIH Client

**Base hunting, stash discovery, and DonutSMP utilities, ported to [DIH Client](https://dih.lxysg.com) from
[doghero002-cmd's AUTISM Client addon](https://github.com/doghero002-cmd/autism-client-basehunting-misc-tools)**

[![Minecraft](https://img.shields.io/badge/Minecraft-26.2%20%7C%2026.3-52a535?style=flat-square&logo=minecraft&logoColor=white)](https://www.minecraft.net/)
[![Fabric](https://img.shields.io/badge/Fabric-0.19.3%2B-dbd0b3?style=flat-square)](https://fabricmc.net/)
[![Java](https://img.shields.io/badge/Java-25%2B-e76f51?style=flat-square&logo=openjdk&logoColor=white)](https://adoptium.net/)
[![License](https://img.shields.io/badge/distribution-GPL--3.0--only-f4a261?style=flat-square)](src/main/resources/SPECTRE-NOTICES.txt)

</div>

## DIH Client version

This repo is the addon ported to **DIH Client** for Minecraft **26.2 and 26.3**. It loads as a DIH addon (`dih`
entrypoint, id `dogs-basehunting`). Install it from DIH's in-game **Addons → Browse Online**, from
[dih.lxysg.com/addons](https://dih.lxysg.com/addons#dogs-basehunting), or take the jar for your Minecraft version from
[Releases](https://github.com/ahigherdesire/dih-dogs-basehunting/releases) into your `mods` folder next to DIH.
All credit for the original addon goes to doghero002-cmd; the git history is kept.

```powershell
$env:JAVA_HOME = "$env:USERPROFILE\.jdks\ms-25.0.4"
.\gradlew.bat test build -Pmc=26.2 "-PdihJar=C:\SDK\DIH-Client-5.0.1-26.2.jar" "-PdihBuildDir=C:\dih-build\dogs"
.\gradlew.bat test build -Pmc=26.3 "-PdihJar=C:\SDK\DIH-Client-5.0.1-26.3.jar" "-PdihBuildDir=C:\dih-build\dogs"
```

Use the actual released DIH jar for each target. Without `-PdihJar`, the original matching
`com.dihclient:dih:5.0.1-<mc>-dev` Maven-local dependency is used. Add `--offline` after dependencies
are cached. Test working files stay under the selected build directory; no game/profile is required.

Version-specific code sits in `//? if >=26.3` blocks (mostly `compat/McCompat`), DIH's Stonecutter convention;
`preprocessSources` in `build.gradle.kts` switches them for 26.3.

## Dogs 3.0.6-dev.1 — seed geodes, consistent evidence and race fixes

This revision incorporates the fixed 3.0.6 source update and the corrected complete-crystal preview.
It retains the prior spawner, schematic, quiet-finder, donor and base-log improvements and synchronizes
portable fixes throughout the existing DIH module catalog. Earlier versioned artifacts are not replaced.

### Seed-based Amethyst ESP

Enable **Amethyst ESP → Seed prediction → Seed-based preview**. It is **off by default**;
**Prediction display** defaults to **reconstructed amethyst bounds**. Observed formations remain separate.

- Enter a signed 64-bit **World seed**, or leave it blank for a matching cracked seed and then the
  last locally remembered verified seed. Automatic/saved values must match the current world's
  biome-seed fingerprint. An invalid manual value never silently selects another seed.
- **Allow unverified manual seed** is a separate default-off fallback for unavailable/masked hashes.
  It stays labelled unverified and is never saved as a world-matched seed. A matching hash verifies
  the seed, not the generation version, terrain preset or current existence of a geode.
- Choose placement-attempt envelopes, reconstructed bounds, block outlines, or both. Cyan hollow
  forecasts are not observed server blocks, player activity or base proof. Bounds/detail include
  **all generated amethyst-family states, including buds and clusters**, not merely solid blocks.
  Predictions produce no base alerts or confidence scores.
- Both Minecraft targets use their **real native geode feature and native noise/surface/carver terrain**.
  The 26.3 backend is a separate implementation, not renamed 26.2 APIs or fabricated results.
  Models are structure-free and not full final-world reconstruction: structures/beardifying, other
  and neighbouring decoration/overlap order, blending/retrogen, changed datapacks, older generation,
  player edits, settled fluids, postprocessing and later bud growth can change the result.
- Flat/debug worlds and non-default Overworld dimensions/heights are rejected. Vanilla tags must
  be ready; this does not detect every possible datapack/mixin customization. Source anchors are
  placement attempts, not confirmed geode centres; terrain can reject an attempt.
- One low-priority worker owns generation and publishes immutable, seed-free views. No live client
  world is read or modified and no client chunk loads are forced. Seed/world/connection changes,
  tag reloads, travel and disabling discard obsolete output. Native calls can finish before
  cancellation takes effect; replacement workers do not overlap.
- Radius defaults to four chunks, maximum eight. Retained detail is capped at 65,536 cells;
  rendering caps are 128 bounds and 4,096 block outlines. Whole-geode detail is omitted at limits
  with an explicit status, never used to invent smaller complete bounds. Cold detail may take seconds.

Seed settings are private and excluded from whole-module sharing. Seeds are not uploaded or sent to
Chunkbase. Headless generation checks require no external prediction API or authenticated gameplay.
Separate isolated DIH startup checks on both versions verified defaults/privacy, access widening,
actual class/mixin loading and clean exits. They did not enter a world or exercise gameplay,
interactive UI/input, geode visuals or detection accuracy; original-client results are not DIH proof.

### Evidence and reliability

- Shared evidence distinguishes **unknown**, **natural-compatible** and **building-supported** data.
  Heuristic floors are capped at 35 and cannot manufacture stronger observations. Scores are not probabilities.
- FIFO admission/refresh performs one candidate scan per tick, admits at most 200 loaded candidates,
  rejects inputs above 4,096 and expires samples at 200 ticks. Status separates pending checks and
  unassessed coverage; missing/deferred data is not called natural or absent.
- Applied chunk/block/section/unload packets invalidate world-scoped reports once per affected chunk,
  including same-object updates. Stale in-flight results cannot restore invalidated evidence.
  Sus detection/defaults remain unchanged; only its coverage-status suffix changes.
- Dungeon/Shipwreck background finding no longer calls live `Level.getBlockEntity`: immutable block
  states and captured chest facing replace redundant entity-type lookups. Bytecode regressions guard
  against reintroduction. This fixes a real race contributor, not definitive attribution for a specific
  crash; EntityCulling can independently cause a similar race.

### Other retained and synchronized improvements

- Server-confirmed Spawner Protect storage, per-stack release/restart, shulker rollover and secure-completion logout.
- Preview-first schematic setup with explicit Start/Pause/Stop and native GLFW/SDL physical-input cancellation.
- Default-off Dogs / Observations, observed connected-amethyst bounds, quiet Larp/Anubis/Corz methods and local base-log review.
- Portable shared fixes include keyless/API-backed AH valuation and paging, purchase guards, `.flip` / `.price`
  views, offline chat-game solving and licensed word/answer data, conservative staff/visibility observations,
  storage persistence/clear latching, home acknowledgements, inventory/action pacing, and existing movement/utility fixes.
  Name privacy uses DIH's own **NameCensor**, and clear bindings use DIH's native binding utility.

The internal package remains `com.autism.seedcracker`; host imports, module IDs, config access and the
`dih` entrypoint remain native DIH. Unavailable AUTISM V4 host-AI/settings integrations are not replaced
with fake SDK classes; standalone opt-in Chat Games endpoints remain supported.

**Separate 26.3 limitation:** the older **Seed-aware scanner** in **Chunk Finder** (ore/blob prediction)
remains explicitly unavailable. Its real 26.2 backend is retained; a saved/enabled 26.3 request reports
unavailable and emits no predictions. Turn it off for stone-stack/Larp clues. This does **not** disable
the new independent 26.3 **Amethyst seed-geode preview**, and does not enable DIH's own OreSim.

See the bundled
[donor permissions/provenance](src/main/resources/DONOR-FINDERS-NOTICES.txt) and
[Spectre scope/license notice](src/main/resources/SPECTRE-NOTICES.txt).

Left out of the DIH version:

- **Already in DIH:** Sprint, Free Look, Auto Tool, Name Protect (DIH NameCensor), Scoreboard Hider (DIH NoRender),
  Fast Place (DIH FastUse), Fake Latency (DIH PingSpoof), Seed Ray (DIH OreSim), Swing Speed (DIH Viewmodel),
  the Session Stats HUD (built into DIH) and TPA Spammer (DIH Spam). Hole ESP is renamed **Shaft ESP**, since
  DIH's HoleESP is the PvP safe-hole kind.
- **Not carried over:** Fake Pay, Fake Payments, Fake Roles and the Fake Scoreboard HUD, which fake payment or
  staff-rank messages to other players; and Skin Changer, which only stored settings.

Network use is opt-in per feature: the SeedCrackerX seed database (off by default), DonutSMP's API (AH tools and
Balance Tags, your key), OpenAI (Chat Games, your key), Google Translate (Translate) and a Discord webhook you set
(Base Webhook).

---

A client-side addon for DIH Client that bundles base-finding tools for DonutSMP, including
seed cracking, coordinate finding, RTP stash hunting, trading and utility modules.
It loads as its own jar alongside DIH, not alongside AUTISM.

<div align="center">

[Overview](#overview) · [Features](#features) · [Quick Start](#quick-start) · [Requirements](#requirements) · [Install](#install) · [Build](#build) · [Credits](#credits) · [License](#license)

</div>

<div align="center">

| FIND | CRACK | SCAN | AUTOMATE |
| :---: | :---: | :---: | :---: |
| Base and stash discovery | Seed and terrain analysis | ESP, overlays, and radar | RTP, Baritone, and utility/macro tools |

</div>

> [!WARNING]
> Several modules move or act automatically (RTP, Baritone, elytra, auto-eat, auto-log, etc.) and may be flagged by server anti-cheats. Use at your own risk.
>
> Please use Prism Launcher if possible; the default Minecraft launcher can cause issues.

## Overview

This addon combines base hunting, stash discovery, seed utilities and quality-of-life tools in one DIH addon.

## Features

### What this pack is for

This addon bundles base-finding, stash scouting, seed-cracking and utility tooling for DonutSMP-style workflows. Enable only the features needed.

### Core modules

- **Seedcracker** — SeedCrackerX-style seed cracking and structure analysis.
- **Bedrock Finder** — Terrain and coordinate-based discovery tools.
- **Texture Cracker** — Visual and texture-led crack/search helpers.
- **Donut RTP Stash Finder** — RTP-based stash hunting around 0,0 and nearby structures.
- **Relog Loader** — Forces chunk refreshes so discovery tools can read newly generated terrain.

### Module groups

| Section | Included tools |
| --- | --- |
| **Core** | Seedcracker, Bedrock Finder, Texture Cracker, Donut RTP Stash Finder, Relog Loader |
| **Finders** | Netherite Finder, Stash Finder, Chunk Finder, Spawner Finder, Block Entity Debug, Sus Chunk Finder, Dogs / Observations, Chunk Waypoints, Finder Overlay, Player Finder, Light Bypass, Prime Chunk Finder, Activity, Growth, Heat Map Radar, Raid Planner, Base Webhook, Chunk Keeper, Base Log Browser, Tunnel Base Finder, Tunnel Base Water, Nether Tunnel Finder, Structure Detector, Sign Finder, Portal Finder, Quantum, Render Util, Finders controls |
| **Entity** | Entity Scanner, AntiTrap, Eye Finder, Item Frame ESP, Bone Dropper, Spawner Protect |
| **Render** | AutoRender, PaperRig, Storage Recorder |
| **Trading** | AH Flipper, AH Sniper, Shop Buyer, AH Sell, Price Check (`.flip` / `.price` views) |
| **Dogs Misc Tools** | AntiAFK, AutoEat, AutoMine, CoordSnapper, FakePlayer, AutoLog, Player Panic, Flag Detector, Macro Protector, Spectator Detector, Staff List / staff HUD, Panic Pay, Anti-Cheat Guesser, Position Packet Filter, Coordinate Protector, Auto Store, Auto Smelt, Chest Stealer, Auto Replenish, Balance Tags, Tab Detector, Weather Notifier, Home Setter, Home Meta, Chat Games, Region Map, Schematic Builder, Elytra Warner, Elytra Travel, Translate, Shaft ESP, Hole Tunnel Stairs ESP, Amethyst ESP, Bedrock Hole ESP, Key Pearl, Auto Firework, Auto TPA, Quick Macro, Mace PVP |

### DonutSMP stash tools

- **Donut RTP Stash Finder** — Uses RTP plus directed digging around key areas to locate stash blocks and log the results to `bases.txt`.
- **Relog Loader** — Digs down, relogs, and re-pulls chunks so finder modules can read fresh terrain more reliably.

> For tunnel base hunting, the water variant of the tunnel finder is usually the safer and less disruptive option for avoiding flags.

## Recommended setup by task

### Best stash-finding setup

- **Stash Finder**
- **Relog Loader**
- **Prime Chunk Finder**
- **AutoRender**
- **Elytra Warner**
- **Auto Firework**
- **Spectator Detector**
- **Amethyst ESP**
- **Storage Recorder**
- **Chunk Keeper**

### Best tunnel-base setup

- **Tunnel Base Finder** (Water / Dogs mode)
- **Storage Recorder**
- **Prime Chunk Finder**
- **Finder Overlay**
- **Amethyst ESP**

### Useful utility modules

- **Player Panic** — immediate defensive stop/disable state for risky automation.
- **Chest Stealer** — fast container pickup and inventory management.
- **Auto Replenish** — automatic item restock workflow.
- **Balance Tags** — tags and tracking for balance-heavy inventory work.
- **Translate** and **Home Meta** — helpful multitool utility modules for quick in-game workflows.

## Quick Start

1. Download the matching Minecraft-version jar and place it in your `mods` folder alongside DIH.
2. Install DIH's documented dependencies and the matching Fabric Loader/API. DIH already supplies Baritone.
3. Launch the game, open the module menu, and enable only the tools you want to use.

## Requirements

- Minecraft `26.2` or `26.3` with the matching addon and DIH jar; do not mix targets.
- Java `25+`.
- Fabric Loader `0.19.3+` / Fabric API `0.152.2+26.2` for 26.2; loader `0.19.5+` / API `0.161.0+26.3` for 26.3.
- [DIH Client 5.0.1](https://github.com/ahigherdesire/dih-client/releases/tag/v5.0.1) and its documented dependencies.
- DonutSMP API key only for opt-in API-backed trading features.

## Install

1. Download the jar from [Releases](../../releases).
2. Drop it in your `mods` folder alongside the matching DIH client and its dependencies.
3. Launch the game and open the module menu to enable the features you want.

## Build

Use the two target-specific commands above. With `-PdihBuildDir`, jars are under
`<directory>\26.2\libs` and `<directory>\26.3\libs`; without it they are under `build\libs`.
The corrected development version is `3.0.6-dev.1`; earlier `3.0.6-dev` artifacts remain distinct.

| Build target | Intended host | Output |
| --- | --- | --- |
| `-Pmc=26.2` | DIH Client 5.0.1, Minecraft 26.2, Fabric | `dogs-basehunting-3.0.6-dev.1-26.2.jar` |
| `-Pmc=26.3` | DIH Client 5.0.1, Minecraft 26.3, Fabric | `dogs-basehunting-3.0.6-dev.1-26.3.jar` |

Never substitute an AUTISM API jar or generated API stubs. Compilation, headless regression tests
and bytecode linkage/mixin selector checks must all pass for each real target before publishing.
Those checks do not establish live-world behavior or successful runtime mixin application.

For this DIH `3.0.6-dev.1` revision, full isolated builds pass **866 tests on 26.2** and
**868 tests on 26.3**, with zero failures. Both include real-SDK linkage/mixin contracts and the
two compiled-bytecode worker-race regressions. Native 26.3 geode reconstruction and complete-crystal
worker coverage are included; that worker regression was red before the selector correction and
green afterward. Only verified synthetic loopback HTTP fixtures execute; no game/login/auth flow,
real profile or external-data API is used. These are DIH results, not borrowed original-client totals.

Separate offline runtime smoke checks passed on both real DIH clients: **54 checks on 26.2** and
**55 on 26.3**, with 86 unique addon modules, private/default-off seed settings, actual required
mixin/class exports, the public flat-world flag, native GLFW/SDL bindings and no default seed workers.
Both exited normally with unchanged input hashes; no world/server was entered. Both retained a
nonfatal Log4j `Recursive call to appender Console` diagnostic during offline logging; its exact cause
was not established or patched. These checks do not certify live-world behavior or geode accuracy.

## Credits

- **SeedCrackerX** by KaptainWutax and 19MisterX98 (MIT) — seed-cracking engine.

## License

The combined addon includes **GPL-3.0-only** Spectre/Anubis-derived portions. Preserve attribution,
include corresponding source on distribution, and retain [the full GPLv3 text](src/main/resources/SPECTRE-LICENSE.txt)
and [GoNuts' MIT notice](src/main/resources/GONUTS-LICENSE.txt).
The root [Unlicense](LICENSE) applies to original code, **not** to all newly imported portions.
Dependencies and offline answer/word data retain their own licenses, including the GPL/AGPL/MIT notices and selected-source provenance under `src/main/resources/chatgames`.

[Larp/Corz permission and provenance](src/main/resources/DONOR-FINDERS-NOTICES.txt) are separate:
Larp had no located license declaration and Corz declares All-Rights-Reserved. The user confirmed
reuse permission; this notice does not grant third-party rights or relicense those works.
Confirm that redistribution and GPL combined-source obligations are covered before publishing.
No raw donor trees, donor native binaries, authentication or updater code are added.
