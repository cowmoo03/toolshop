package io.github.cowmoo03.toolshop;

import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

/**
 * What buying from the shop would do for the tool in the player's main hand.
 *
 * @param action    short name for the purchase log
 * @param verb      shown on the button ("upgrade", "renew")
 * @param price     amount of currency to pay
 * @param newExpiry expiry timestamp after paying
 * @param note      extra line shown on the button, may be null
 */
public record Offer(String action, String verb, int price, long newExpiry, String note) {

    /** Returns null if the player is not holding the right base tool. */
    public static Offer forHand(ToolShopPlugin plugin, Player player, ToolType type) {
        ItemStack hand = player.getInventory().getItemInMainHand();
        if (hand.getType() != type.material()) return null;

        Settings s = plugin.settings();
        ToolData data = plugin.toolData();
        long now = System.currentTimeMillis();

        if (data.getType(hand) == null) {
            return new Offer("UPGRADE", "upgrade", s.upgradePrice(type), now + s.durationMs, null);
        }
        long expires = data.getExpires(hand);
        return switch (data.getState(hand, now)) {
            case ACTIVE -> new Offer("RENEW_EARLY", "renew", s.renewPrice(type), expires + s.durationMs,
                    "Adds " + ToolData.formatDuration(s.durationMs) + " to the "
                            + ToolData.formatDuration(expires - now) + " left");
            case GRACE -> new Offer("RENEW", "renew", s.renewPrice(type), now + s.durationMs,
                    "Grace period ends in " + ToolData.formatDuration(expires + s.graceMs - now));
            case LAPSED -> new Offer("REACTIVATE", "re-upgrade", s.upgradePrice(type), now + s.durationMs,
                    "Grace period is over, so this costs the full price");
        };
    }
}
