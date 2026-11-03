# ToolShop

A Paper plugin (Minecraft 26.2) for **legendary tools**: each player turns one iron, diamond or netherite
pickaxe, axe and shovel into their own legendary tool and upgrades it for good, unlocking tiers with
**mcMMO skill levels plus items**. Upgrades are permanent, but the tool can be lost like any item.

## Upgrades

| Upgrade | Tool | Tiers | Default unlock (mcMMO level + items) |
|---|---|---|---|
| **Drill** | Pickaxe | 1x2 → 3x3 → 5x5 area of stone | Mining 10 + 16 iron · 30 + 8 diamonds · 60 + 2 netherite ingots |
| **Vein Mining** | Pickaxe | up to 8 → 16 → 32 ores (switch modes with `/toolshop mode`) | Mining 15 / 35 / 65 |
| **Timber** | Axe | up to 16 → 64 → 150 logs; Silk Touch collects leaves at tier 3 | Woodcutting 10 / 30 / 60 |
| **Excavator** | Shovel | 1x2 → 3x3 → 5x5 area of dirt, sand, gravel | Excavation 10 / 30 / 60 |
| **Magnet** | Any | drops go straight into your inventory | 25 + 4 diamonds, 4 ender pearls |
| **Durability Saver** | Any | 5% → 10% → 20% chance to use no durability | 20 / 40 / 70 |
| **Last Stand** | Any | stops at 1 durability instead of breaking | 30 + 8 diamonds |
| **Fireproof** | Iron/diamond | survives lava and fire for 1 → 3 → 5 min of game time (and doesn't despawn meanwhile), then burns | 40 + 2 netherite scrap · 60 + 4 scrap · 80 + 1 netherite ingot |
| **Recovery** | Netherite | dropped tool glows (only for its owner) and stays 1 → 3 → 5 extra min · tier 2+: survives explosions and cactus · tier 3: returns from the void | 40 + 2 netherite scrap · 60 + 4 scrap · 80 + 1 netherite ingot |
| **Soulbound** | Any | survives 3 → 5 → 8 deaths; recharge in /toolshop once used up (or upgrade to refill) | 50 + 1 totem · 70 + 2 totems · 90 + 3 totems |

Abilities work while sneaking. Everything above is set in `config.yml`.

## How it works

- Hold the tool and open **`/toolshop`**. Click **Make it Legendary**, then buy upgrades. Every purchase needs a second click to confirm.
- One legendary tool of each type per player. Making a new one **retires** the old one wherever it is.
- Only the owner can use a legendary tool's abilities.
- The tool's description shows its upgrades, owner, and a rank based on blocks broken (Rusty → Seasoned → Veteran → Legendary).
- Repair with Mending or an anvil (legendary tool in the left slot). Crafting-grid merges, grindstones and putting a legendary tool in an anvil's right slot are blocked.
- Players get a warning when a legendary tool is low on durability.
- The Drill never breaks ancient debris, obsidian, containers or machines. Vein Mining never touches ancient debris.
- Timber only fells trees with natural leaves (log builds are safe); trees whose logs touch fall together, and leftover stumps can be chopped in one go.
- Only the block the player hits gives mcMMO XP; the extra blocks an ability breaks don't.
- Without mcMMO installed, level requirements are skipped (items only).
- A one-time chat announcement greets each player after an update (`join-announcement` in the config; change its `id` to show a new one).

## Commands

| Command | Who | What |
|---|---|---|
| `/toolshop` | Everyone | Open the shop |
| `/toolshop mode` | Everyone | Switch a legendary pickaxe between Drill and Vein Mining |
| `/toolshop give <player> <pickaxe\|axe\|shovel> [iron\|diamond\|netherite]` | Ops | Give a new legendary tool |
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
