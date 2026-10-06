<div align="center">

<img src="docs/card.png" alt="Dogs BaseHunting & Misc Tools" width="640">

# Dogs BaseHunting & Misc Tools for DIH Client

**Base hunting, stash discovery, and DonutSMP utilities, ported to [DIH Client](https://dih.lxysg.com) from
[doghero002-cmd's AUTISM Client addon](https://github.com/doghero002-cmd/autism-client-basehunting-misc-tools)**

[![Minecraft](https://img.shields.io/badge/Minecraft-26.2%20%7C%2026.3-52a535?style=flat-square&logo=minecraft&logoColor=white)](https://www.minecraft.net/)
[![Fabric](https://img.shields.io/badge/Fabric-0.19.3%2B-dbd0b3?style=flat-square)](https://fabricmc.net/)
[![Java](https://img.shields.io/badge/Java-25%2B-e76f51?style=flat-square&logo=openjdk&logoColor=white)](https://adoptium.net/)
[![License](https://img.shields.io/badge/license-The%20Unlicense-f4a261?style=flat-square)](LICENSE)

</div>

## DIH Client version

This repo is the addon ported to **DIH Client** for Minecraft **26.2 and 26.3**. It loads as a DIH addon (`dih`
entrypoint, id `dogs-basehunting`). Install it from DIH's in-game **Addons → Browse Online**, from
[dih.lxysg.com/addons](https://dih.lxysg.com/addons#dogs-basehunting), or take the jar for your Minecraft version from
[Releases](https://github.com/ahigherdesire/dih-dogs-basehunting/releases) into your `mods` folder next to DIH.
All credit for the original addon goes to doghero002-cmd; the git history is kept.

```sh
# 26.2 (needs DIH's API in mavenLocal: run `gradlew publishToMavenLocal` in the DIH checkout)
./gradlew build -PdihBuildDir=C:/dih-build/dogs
# 26.3 (compiles against the released DIH jar)
./gradlew build -Pmc=26.3 -PdihJar=path/to/DIH-Client-5.0.1-26.3.jar -PdihBuildDir=C:/dih-build/dogs
```

Version-specific code sits in `//? if >=26.3` blocks (mostly `compat/McCompat`), DIH's Stonecutter convention;
`preprocessSources` in `build.gradle.kts` switches them for 26.3.

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

A client-side addon for the AUTISM Client that bundles base-finding tools for DonutSMP, including
seed cracking, coordinate finding, RTP stash hunting, an auction-house flipper, and a broader set of
utility modules. It loads as its own jar alongside the AUTISM client.

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

This addon combines a handful of different modules into one package so you can use a single jar with AUTISM Client instead of juggling multiple separate mods. The project focuses on base hunting, stash discovery, seed utility work, and general quality-of-life tools.

## Features

### What this pack is for

This addon bundles base-finding, stash scouting, seed-cracking, and utility tooling into a single AUTISM Client jar. It is designed for DonutSMP-style hunting workflows and for players who want a compact, all-in-one toolset without juggling separate mods.

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
| **Finders** | Netherite Finder, Stash Finder, Chunk Finder, Spawner Finder, SusChunk Finder, SeedRay, Chunk Waypoints, Finder Overlay, Player Chunks, Light Source Finder, Prime Chunk, Activity, Growth, Heat Map Radar, Raid Planner, Base Webhook, Chunk Keeper, Base Log Browser, Tunnel Base Finder, Tunnel Base Water, Nether Tunnel Finder, Structure Detector |
| **Entity** | Entity Scanner, AntiTrap, Eye Finder, Item Frame ESP, Bone Dropper, Spawner Protect |
| **Render** | AutoRender, PaperRig, Scoreboard Hider, Storage Recorder |
| **Fake** | FakePay, FakePayments, FakeRoles |
| **Trading** | AH Flipper, AH Sniper, Shop Buyer, AH Sell |
| **Dogs Misc Tools** | Sprint, AntiAFK, FastPlace, FreeLook, AutoEat, AutoMine, SwingSpeed, CoordSnapper, FakePlayer, AutoLog, Player Panic, Flag Detector, Macro Protector, Spectator Detector, Panic Pay, Anti-Cheat Guesser, Fake Latency, Position Packet Filter, Coordinate Protector, Auto Store, Auto Smelt, Chest Stealer, Auto Replenish, Balance Tags, AutoTool, TPASpammer, Tab Detector, Weather Notifier, Home Setter, Home Meta, Skin Changer, Chat Games, Region Map, Schematic Builder, Elytra Warner, Translate, Hole ESP, Hole Tunnel Stairs ESP, Amethyst ESP, Bedrock Hole ESP, Key Pearl, Auto Firework, Auto TPA, Quick Macro, Name Protect, Mace PVP |

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

1. Download the jar from the releases page and place it in your `mods` folder alongside the AUTISM client.
2. Install the required dependencies, especially [Fabric Loader](https://fabricmc.net/) and [Baritone](https://github.com/doghero002-cmd/baritone), if you plan to use movement-based modules.
3. Launch the game, open the module menu, and enable only the tools you want to use.

## Requirements

- Minecraft `26.2`
- Java `25+`
- [Fabric Loader](https://fabricmc.net/) `0.19.3+` and Fabric API
- [AUTISM Client](https://github.com/AutismDevelopment/Autism-Client) (any compatible version)
- [Baritone](https://github.com/cabaletta/baritone) (`baritone-meteor`) — required for the RTP / Relog / search movement
- DonutSMP API key — only needed for the AH Flipper’s live mode

## Install

1. Download the jar from [Releases](../../releases).
2. Drop it in your `mods` folder alongside the AUTISM client and Baritone if you plan to use movement-based tools.
3. Launch the game and open the module menu to enable the features you want.

## Build

From the project root, build the addon with either of these commands:

```bash
# Linux / macOS
./gradlew build --no-daemon
```

```powershell
# Windows
.\gradlew.bat build --no-daemon
```

The jar is produced in `build/libs/`.

> **AUTISM Client API:** the matching API jar is vendored in `libs/` and resolved via a `flatDir` repository, so the build is self-contained. It works on a fresh machine or CI runner with an empty `~/.m2` without needing `publishToMavenLocal`.
>
> To upgrade the client, drop the new `autism-<version>.jar` into `libs/` and bump `autism` in `gradle/libs.versions.toml`. The project is currently pinned to AUTISM Client `5.0-26.2-dev`.
>
> **AUTISM Client version:** the build resolves the API with a Maven version range (`[3.4,)`), so it uses the newest client you have published locally instead of locking to one exact version. The built jar declares `autism: "*"` and loads on compatible client versions. If a new major client release changes the API, the addon may need source updates.

## Credits

- **SeedCrackerX** by KaptainWutax and 19MisterX98 (MIT) — seed-cracking engine.

## License

This repository is licensed under [The Unlicense](LICENSE).

---

The project is released under The Unlicense, which means you are free to use, modify, and redistribute it however you want, as long as the software is provided as-is without warranty.
