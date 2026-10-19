# NL-Universe LootChests

A Paper Minecraft plugin for randomly spawning treasure chests in the selected worlds.

## Build

GitHub Actions builds a jar automatically on every push to `main`. Download it under the workflow run's Artifacts.

## Install

1. Stop server and back up worlds.
2. Put the jar in `plugins/`, start server.
3. Edit `plugins/NLUniverseLootChests/config.yml` and verify the **exact** world names (`vanilla` versus `vanille`).
4. Restart server or run `/nlloot reload`.
5. Join each world with a test player, then run `/nlloot spawn smp` or `/nlloot spawn vanilla` to test.

## Commands

- `/nlloot status`
- `/nlloot spawn <world>`
- `/nlloot reload`

Permission: `nlloot.admin` (operators by default).

## Important behavior

- Spawns every 30 minutes, one chest attempt **per configured world**; no player in a world and no existing chests means no spawn.
- For the first chest, uses a random player as an anchor, 500–900 blocks away. Later chests are 500–900 blocks away from one existing chest and **at least** 500 blocks from all existing chests. It is mathematically impossible to guarantee all pairs are at most 900 blocks apart as the number grows; the maximum is for each newly chosen anchor.
- Chests persist on disk, and disappear when completely looted and inventory closed.
- Does not integrate with land claims or WorldGuard and cannot guarantee avoidance of player builds. Test on a backup before production.
- Uses standard chest blocks. Players can break them; an externally destroyed chest can still count against the cap until its inventory is opened or data manually reset. This is a known limitation of v1.
- Build targets Paper API 1.21.4 and Java 21. Test compatibility with your installed Paper version.
