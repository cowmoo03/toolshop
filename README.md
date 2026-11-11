# ToolShop

A Paper plugin (Minecraft 26.2) for **legendary tools**: each player turns one iron, diamond or netherite
pickaxe, axe, shovel, hoe and sword, trident, crossbow and elytra into their own legendary item and upgrades it for good, unlocking tiers with
**mcMMO skill levels plus items**. Upgrades are permanent, but the tool can be lost like any item.

## Upgrades

| Upgrade | Tool | Tiers | Default unlock (mcMMO level + items) |
|---|---|---|---|
| **Drill** | Pickaxe | 1x2 → 3x3 → 5x5 area of stone | Mining 10 + 16 iron · 30 + 8 diamonds · 60 + 2 netherite ingots |
| **Vein Mining** | Pickaxe | up to 8 → 16 → 32 ores (switch modes with `/toolshop mode`) | Mining 15 / 35 / 65 |
| **Timber** | Axe | up to 16 → 64 → 150 logs; Silk Touch collects leaves at tier 3 | Woodcutting 10 / 30 / 60 |
| **Excavator** | Shovel | 1x2 → 3x3 → 5x5 area of dirt, sand, gravel | Excavation 10 / 30 / 60 |
| **Lifesteal** | Sword | heals 5% → 10% → 15% of the damage you deal | Swords 15 / 35 / 65 |
| **Beheading** | Sword | 3% → 6% → 10% chance for a zombie, skeleton, creeper, piglin, wither skeleton or player head | Swords 20 / 40 / 70 |
| **Bounty** | Sword | +25% → +50% → +100% XP orbs from kills | Swords 10 / 30 / 60 |
| **Harvest** | Hoe | sneak-break a grown crop: 3x3 → 5x5 → 7x7 of grown crops | Herbalism 10 / 30 / 60 |
| **Replant** | Hoe | harvested crops plant themselves again (seed from the drops or inventory) | Herbalism 20 |
| **Tiller** | Hoe | sneak-till: 3x3 → 5x5 → 7x7 | Herbalism 5 / 25 / 50 |
| **Impaler** | Trident | +2 → +4 → +6 damage against every mob | Tridents 15 / 35 / 65 |
| **Storm Caller** | Trident | a thrown trident calls lightning (5 damage + fire) in any weather, every 30 → 20 → 10 s at most | Tridents 25 / 45 / 75 |
| **Arrow Saver** | Crossbow | 20% → 35% → 50% chance to get the arrow back | Crossbows 10 / 30 / 60 |
| **Volley** | Crossbow | +1 → +2 → +4 extra arrows in a spread | Crossbows 20 / 40 / 70 |
| **Heavy Bolts** | Crossbow | +10% → +20% → +30% arrow damage | Crossbows 15 / 35 / 65 |
| **Boost** | Elytra | sneak while gliding for a push; 1 → 2 → 3 per flight, refilled on landing | Acrobatics 10 / 30 / 60 |
| **Rocket Saver** | Elytra | 25% → 50% → 75% chance a firework rocket isn't used up | Acrobatics 15 / 35 / 65 |
| **Safe Landing** | Elytra | no damage from crashing into walls or landing while gliding | Acrobatics 20 |
| **Magnet** | Pickaxe, axe, shovel, hoe | drops go straight into your inventory | 25 + 4 diamonds, 4 ender pearls |
| **Durability Saver** | Any | 5% → 10% → 20% chance to use no durability | 20 / 40 / 70 |
| **Last Stand** | Any | stops at 1 durability instead of breaking | 30 + 8 diamonds |
| **Fireproof** | Iron/diamond | survives lava and fire for 1 → 3 → 5 min of game time (and doesn't despawn meanwhile), then burns | 40 + 2 netherite scrap · 60 + 4 scrap · 80 + 1 netherite ingot |
| **Recovery** | Netherite | dropped tool glows (only for its owner) and stays 1 → 3 → 5 extra min · tier 2+: survives explosions and cactus · tier 3: returns from the void | 40 + 2 netherite scrap · 60 + 4 scrap · 80 + 1 netherite ingot |
| **Soulbound** | Any | survives 3 → 5 → 8 deaths; recharge in /toolshop once used up (or upgrade to refill) | 50 + 1 totem · 70 + 2 totems · 90 + 3 totems |

Tool abilities (pickaxe, axe, shovel, hoe) work while sneaking; sword, trident, crossbow and elytra abilities work on their own. Weapon abilities work at half strength against players (`sword-pvp-multiplier`). Weapons rank up by kills and the elytra by blocks flown. The elytra can't get Last Stand (elytras already stop at 1 durability). Everything above is set in `config.yml`.

## How it works

- **`/toolshop`** opens without holding anything. It shows your legendary pickaxe, axe and shovel; click one (it has to be somewhere in your inventory) to see and buy its upgrades. It also shows the Flight Charm, its recipe and whether you've unlocked it, . Clicking an item you don't have with you (or don't own yet) shows its upgrades, view only, with what each tier does, the levels and the items; a lost one can be retired from there.
- To make a tool legendary, hold it and click the star in `/toolshop`. Every purchase needs a second click to confirm.
- One legendary tool of each type per player. Making a new one **retires** the old one wherever it is. A tool you've lost can also be retired from its page in `/toolshop` (the barrier; click twice to confirm).
- Only the owner can use a legendary tool's abilities.
- The tool's description shows its upgrades, owner, and a rank based on blocks broken (Rusty → Seasoned → Veteran → Legendary).
- Repair with Mending or an anvil (legendary tool in the left slot). Crafting-grid merges, grindstones and putting a legendary tool in an anvil's right slot are blocked.
- Players get a warning when a legendary tool is low on durability.
- The Drill never breaks ancient debris, obsidian, containers or machines. Vein Mining never touches ancient debris.
- Timber only fells trees with natural leaves (log builds are safe); trees whose logs touch fall together, and leftover stumps can be chopped in one go.
- Only the block the player hits gives mcMMO XP; the extra blocks an ability breaks don't.
- mcMMO's Tree Feller is switched off because Timber does the same job (`mcmmo.disable-tree-feller`); `/skills` hides it and Knock On Wood.
- Logs that grew from a sapling still give Woodcutting XP after a piston moves them (tree farms). mcMMO's piston rule still blocks XP for logs a player placed (`mcmmo.pushed-logs-give-xp`).
- Without mcMMO installed, level requirements are skipped (items only).
- A one-time chat announcement greets each player after an update (`join-announcement` in the config; change its `id` to show a new one).

## Spawners

Spawners work like vanilla whether they're claimed or not. Right-click one to see its mob, tier and owner.
Sneak + right-click (twice, to confirm) to:

- **Claim** an unclaimed spawner: 8 diamonds + power level 500 (Retro Mode). Up to 3 per player.
- **Upgrade** your own: Tier I spawns 25% faster (16 iron + 8 bones, power 500), II 50% faster (8 diamonds,
  power 1000), III twice as fast (2 netherite ingots, power 1500). Only the owner can upgrade.
- **Take over** someone else's: 16 diamonds + power level 500. Nobody gets those items; the old owner is told.

Claimed spawners are listed in `/toolshop` under **Your Spawners**: upgrade from anywhere, turn on/off, unclaim.
Only the owner (or an op) can break a claimed spawner, and explosions can't. Tiers stay on the spawner when it
changes hands; breaking it loses them. The vanilla limits stay (about 6 of its mobs nearby, a player within
16 blocks). Settings under `spawner-upgrades`; data in `plugins/ToolShop/spawners.yml`; actions logged.

## Flight Charm

A crafted item anyone can use, no mcMMO level needed. Right-click it for 5 minutes of creative-style flight for building.
Each charm is used up. One flight at a time: a charm can't be used (and isn't used up) while a flight is running.

- Recipe: 4 feathers, 4 gold ingots and a diamond in the middle (feather, gold, feather / gold, diamond, gold / feather, gold, feather).
- Crafting unlocks at mcMMO power level 100 (1000 in Retro Mode, `unlock-power-level`); the recipe shows in the recipe book once unlocked, with a chat message. Anyone can use a charm they're given.
- The time left shows like a potion (a Slow Falling effect that lasts exactly as long as the flight) and as a bar at the top; the last 10 seconds tick. Either can be turned off.
- Going more than 64 blocks sideways from where you took off pauses the flight: you drop (no fall damage) and the clock stops. Back in range, flight comes back with the time that was left. A warning shows 8 blocks before. Height isn't limited.
- Hitting or being hit by a player ends the flight, and a new one can't start for 10 seconds.
- However the flight ends, the fall afterwards does no damage.
- Charms can't be used as feathers in crafting. Ops: `/toolshop give <player> flight [amount]`.
- All of it is under `flight-charm` in `config.yml`.

## Skills window

`/skills` (or `/skill`) shows every mcMMO skill with level and XP bar. Clicking a skill lists its abilities with
plain-language descriptions, whether each is unlocked, its rank, and the level the locked ones need (read from
mcMMO's own `skillranks.yml`, Standard or Retro Mode). `/mcstats` and mcMMO's per-skill commands (`/mining`,
`/axes`, ...) open the window instead of printing to chat, and those commands are hidden from players' tab
completion. Both can be turned off under `skills-menu` in the config.

## Commands

| Command | Who | What |
|---|---|---|
| `/toolshop` | Everyone | Open the shop |
| `/skills [skill]` | Everyone | Window with every mcMMO skill: level, XP, abilities in plain words, what's unlocked and the ToolShop upgrades each skill unlocks |
| `/toolshop mode` | Everyone | Switch a legendary pickaxe between Drill and Vein Mining |
| `/toolshop give <player> <pickaxe\|axe\|shovel> [iron\|diamond\|netherite]` | Ops | Give a new legendary tool |
| `/toolshop give <player> flight [amount]` | Ops | Give Flight Charms |
| `/toolshop upgrade <player> <upgrade> <tier>` | Ops | Set an upgrade on the player's held legendary tool |
| `/toolshop reset <player> [pickaxe\|axe\|shovel\|all]` | Ops | Remove a player's legendary tools so they can start over |
| `/toolshop reload` | Ops | Reload `config.yml` |

Creations, upgrades and admin actions are logged to `plugins/ToolShop/purchases.log`.

## How it's built

- Tool data (owner, ID, upgrade tiers, pickaxe mode) lives in the item's `PersistentDataContainer`.
  Each player's current tool IDs and blocks-broken counts live in `plugins/ToolShop/players.yml`.
- Extra blocks are broken with `Player#breakBlock`, so drops, enchantments, durability and protection plugins behave
  as if the player broke them by hand. Before each one, mcMMO's `UserBlockTracker.setIneligible` marks it so mcMMO gives no XP.
- mcMMO is reached through its public API by reflection, so ToolShop builds and runs without it.
- GitHub Actions builds the jar on every push and publishes it as the `latest` release.

## mcMMO build

`.github/workflows/mcmmo.yml` builds [mcMMO](https://github.com/mcMMO-Dev/mcMMO) 2.3.002 from its official GPLv3 source
and publishes it as the `mcmmo-2.3.002` release. Official builds are sold on
[SpigotMC](https://www.spigotmc.org/resources/official-mcmmo-original-author-returns.64348/); buying one supports the developers.

## Install and update

```bash
curl -fL -o plugins/ToolShop.jar https://github.com/cowmoo03/toolshop/releases/download/latest/ToolShop.jar
```

`scripts/update-toolshop.sh` downloads the latest build, checks it, keeps the current jar as a backup,
restarts the server and confirms the new version started.

```bash
sudo curl -fsSL -o /usr/local/bin/update-toolshop https://raw.githubusercontent.com/cowmoo03/toolshop/main/scripts/update-toolshop.sh
sudo chmod 755 /usr/local/bin/update-toolshop
sudo update-toolshop              # update (asks before restarting)
sudo update-toolshop --rollback   # go back to the previous version
```

## OreWatch (separate plugin, `orewatch/`)

Flags likely x-ray users. It never punishes anyone on its own; it alerts staff and you decide.

- **Buried veins vs stone mined.** For each resource (diamond, ancient debris, emerald, gold) it compares the veins a
  player reached only through their own digging ("buried") to how much stone or netherrack they mined. Veins sitting
  in caves are shown but don't count, so cave explorers don't look suspicious.
- **Cave breakthroughs.** When a player tunnels from their own tunnel into a natural cave, that's a breakthrough.
  Honest strip miners hit caves by chance; x-rayers tunnel to ores they can see in caves. If most of a player's
  breakthroughs lead straight to a valuable cave find nearby, staff get an alert.
- Counts veins, not blocks, so Vein Mining, the Drill and big veins don't make honest players look suspicious.
- Ignores ore blocks players placed themselves.
- Alerts staff in chat (with a cooldown) and logs to `alerts.log`.
- Rare-find messages for staff ("Steve found diamond ore (6, buried) at ... [tp]") and `finds.log`.
- The last 30 finds per player are saved, so staff can teleport to each one and look at the tunnel.
- Limits and block lists are in `plugins/OreWatch/config.yml`.

| Command | What |
|---|---|
| `/orewatch <player>` | Buried and cave veins, stone mined, rates and breakthroughs (24h, 7 days, all time) |
| `/orewatch top [24h\|7d\|all]` | Players ranked by how close they are to the limits |
| `/orewatch finds <player>` | The player's recent finds with clickable `[tp]` links |
| `/orewatch tp <player> <n>` | Teleport to find number `n` (creative or spectator only) |
| `/orewatch notify` | Turn rare-find messages on or off for yourself |
| `/orewatch reset <player>` | Clear a player's stats |
| `/orewatch reload` | Reload the config |

All commands are ops-only by default. Download: `https://github.com/cowmoo03/toolshop/releases/download/orewatch-latest/OreWatch.jar`
