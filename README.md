# ToolShop

A Paper plugin (Minecraft 26.2) that rents out tool upgrades for netherite.

| Upgrade | Ability | Upgrade price | Renewal |
|---|---|---|---|
| **Timber Axe** | Sneak + break any log to fell the whole tree, clear its leaves (Silk Touch keeps them as blocks) and replant a sapling | Netherite axe + 2 netherite ingots | 1 ingot |
| **Vein Pickaxe** | Sneak + break an ore to mine the whole vein (max 64, no ancient debris) | Netherite pickaxe + 3 netherite ingots | 1 ingot |
| **Excavator Shovel** | Sneak + dig to clear a 3×3 area of dirt, sand, gravel and other shovel blocks | Netherite shovel + 2 netherite ingots | 1 ingot |

## How it works

- Players hold the netherite tool and buy the upgrade in **`/toolshop`**. Purchases need a second click to confirm.
- Upgrades last **7 real days** and count down while the player is offline. The tool's description shows the time left.
- Players are warned 24 hours and 1 hour before it runs out.
- When it expires, the tool turns back into a normal netherite tool (it is never taken away).
- For **24 hours** after it expires, it can be renewed for the renewal price. After that, the full upgrade price applies again.
- Renewing early adds another 7 days on top of the time left.
- Durability works like vanilla (1 per block), with Fortune, Silk Touch, Unbreaking and Mending all applying normally.
- Repair upgraded tools with Mending or an anvil (upgraded tool in the left slot). Crafting-grid merges, grindstones and putting an upgraded tool in the anvil's right slot are blocked, because they would throw the upgrade away.
- The Timber Axe only fells trees with natural leaves, so log builds are safe. Afterwards it clears leaves that are no longer attached to any log (neighbouring trees keep theirs) and plants a matching sapling.
- Abilities play a sound and show break particles (turn off with `effects: false`).

All prices, times and limits are in `config.yml`.

## Commands

| Command | Who | What |
|---|---|---|
| `/toolshop` | Everyone | Open the shop |
| `/toolshop give <player> <timber\|vein\|excavator> [days]` | Ops | Give a new upgraded tool for free |
| `/toolshop reload` | Ops | Reload `config.yml` |

Every purchase, renewal and admin give is written to `plugins/ToolShop/purchases.log`.

## How it's built

- Upgrade data (type, expiry timestamp, warning flags) lives in each item's `PersistentDataContainer`, so it survives restarts, chests and anvils, and can't be reset.
- Extra blocks are broken with `Player#breakBlock`, so drops, enchantments, durability and protection plugins behave exactly as if the player broke them by hand.
- GitHub Actions builds the jar on every push and publishes it as the `latest` release.

## Install

```bash
curl -fL -o plugins/ToolShop.jar https://github.com/cowmoo03/toolshop/releases/download/latest/ToolShop.jar
```

Then restart the server.
