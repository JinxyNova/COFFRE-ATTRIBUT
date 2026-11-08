package fr.merci.givefx;

import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.PrepareAnvilEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.EnchantmentStorageMeta;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.view.AnvilView;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Enchantements sans limite de niveau.
 *
 *  - Enclume : deux enchantements identiques de même niveau donnent le niveau +1,
 *    sans plafond vanilla (Tranchant V + Tranchant V = Tranchant VI, etc.).
 *    Un livre/objet qui porte déjà un niveau "hors limite" est accepté.
 *  - Plus de "Trop cher !" : le coût en niveaux est plafonné (cost-cap).
 *  - Commande /givefx enchant <enchant> <niveau> : met n'importe quel niveau sur l'objet en main.
 *
 * Seule limite : 255, plafond technique de Minecraft pour un niveau d'enchantement.
 */
public final class UnlimitedEnchants implements Listener {

    public static final int HARD_MAX = 255;

    private boolean enabled = true;
    private boolean anvil = true;
    private boolean ignoreConflicts = false;
    private int maxLevel = HARD_MAX;
    private int costCap = 39;

    public void load(FileConfiguration cfg) {
        enabled = cfg.getBoolean("unlimited-enchants.enabled", true);
        anvil = cfg.getBoolean("unlimited-enchants.anvil", true);
        ignoreConflicts = cfg.getBoolean("unlimited-enchants.ignore-conflicts", false);
        maxLevel = Math.max(1, Math.min(HARD_MAX, cfg.getInt("unlimited-enchants.max-level", HARD_MAX)));
        costCap = Math.max(1, cfg.getInt("unlimited-enchants.cost-cap", 39));
    }

    // ------------------------------------------------------------ enclume

    @EventHandler(priority = EventPriority.HIGH)
    public void onAnvil(PrepareAnvilEvent e) {
        if (!enabled || !anvil) return;
        AnvilView view = e.getView();
        ItemStack first = e.getInventory().getItem(0);
        ItemStack second = e.getInventory().getItem(1);
        if (first == null || second == null || first.getType().isAir() || second.getType().isAir()) return;

        boolean secondIsBook = second.getType() == Material.ENCHANTED_BOOK;
        if (!secondIsBook && second.getType() != first.getType()) return;
        boolean firstIsBook = first.getType() == Material.ENCHANTED_BOOK;

        Map<Enchantment, Integer> toAdd = enchantsOf(second);
        if (toAdd.isEmpty()) return;

        Map<Enchantment, Integer> merged = enchantsOf(first);
        int costSum = 0;
        boolean changed = false;
        for (Map.Entry<Enchantment, Integer> en : toAdd.entrySet()) {
            Enchantment ench = en.getKey();
            int lvl = en.getValue();
            Integer cur = merged.get(ench);

            if (cur == null) {
                if (!firstIsBook && !ench.canEnchantItem(first)) continue;
                if (!ignoreConflicts && conflicts(ench, merged)) continue;
            }
            int next = cur == null ? lvl : (cur == lvl ? cur + 1 : Math.max(cur, lvl));
            next = Math.min(next, maxLevel);
            if (cur == null || next > cur) {
                merged.put(ench, next);
                costSum += next;
                changed = true;
            }
        }
        if (!changed) return;

        ItemStack result = e.getResult();
        if (result == null || result.getType().isAir()) result = first.clone();
        ItemMeta rm = result.getItemMeta();
        if (rm == null) return;
        for (Map.Entry<Enchantment, Integer> en : merged.entrySet()) {
            if (rm instanceof EnchantmentStorageMeta esm) esm.addStoredEnchant(en.getKey(), en.getValue(), true);
            else rm.addEnchant(en.getKey(), en.getValue(), true);
        }
        result.setItemMeta(rm);
        e.setResult(result);

        int cost = Math.min(costCap, Math.max(Math.max(1, costSum), view.getRepairCost()));
        view.setMaximumRepairCost(Math.max(40, costCap + 1));
        view.setRepairCost(cost);
    }

    private static boolean conflicts(Enchantment ench, Map<Enchantment, Integer> existing) {
        for (Enchantment other : existing.keySet()) {
            if (!other.equals(ench) && ench.conflictsWith(other)) return true;
        }
        return false;
    }

    private static Map<Enchantment, Integer> enchantsOf(ItemStack it) {
        Map<Enchantment, Integer> out = new LinkedHashMap<>();
        ItemMeta m = it.getItemMeta();
        if (m == null) return out;
        if (m instanceof EnchantmentStorageMeta esm) out.putAll(esm.getStoredEnchants());
        else out.putAll(m.getEnchants());
        return out;
    }

    // ------------------------------------------------------------ commande

    /** /givefx enchant <enchantement> <niveau>   (niveau 0 = retirer) */
    public boolean command(CommandSender sender, String[] args) {
        if (!(sender instanceof Player p)) {
            sender.sendMessage("Commande réservée aux joueurs.");
            return true;
        }
        if (args.length < 3) {
            sender.sendMessage("§e/givefx enchant <enchantement> <niveau>  §7(ex : /givefx enchant sharpness 100)");
            return true;
        }
        Enchantment ench = find(args[1]);
        if (ench == null) {
            sender.sendMessage("§cEnchantement inconnu : " + args[1]);
            return true;
        }
        int level;
        try {
            level = Integer.parseInt(args[2]);
        } catch (NumberFormatException ex) {
            sender.sendMessage("§cNiveau invalide : " + args[2]);
            return true;
        }
        if (level < 0 || level > maxLevel) {
            sender.sendMessage("§cLe niveau doit être entre 0 et " + maxLevel + " (limite technique de Minecraft : " + HARD_MAX + ").");
            return true;
        }
        ItemStack item = p.getInventory().getItemInMainHand();
        if (item.getType().isAir()) {
            sender.sendMessage("§cTiens un objet en main principale.");
            return true;
        }
        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            sender.sendMessage("§cCet objet ne peut pas être enchanté.");
            return true;
        }
        if (meta instanceof EnchantmentStorageMeta esm) {
            if (level == 0) esm.removeStoredEnchant(ench);
            else esm.addStoredEnchant(ench, level, true);
        } else {
            if (level == 0) meta.removeEnchant(ench);
            else meta.addEnchant(ench, level, true);
        }
        item.setItemMeta(meta);
        p.getInventory().setItemInMainHand(item);
        sender.sendMessage(level == 0
                ? "§aEnchantement retiré : " + ench.getKey().getKey()
                : "§a" + ench.getKey().getKey() + " " + level + " appliqué.");
        return true;
    }

    private static Enchantment find(String name) {
        String n = name.toLowerCase(Locale.ROOT);
        NamespacedKey k = n.contains(":") ? NamespacedKey.fromString(n) : NamespacedKey.minecraft(n);
        return k == null ? null : Registry.ENCHANTMENT.get(k);
    }

    /** Complétion automatique pour /givefx enchant. */
    public List<String> complete(String[] args) {
        List<String> out = new ArrayList<>();
        if (args.length == 2) {
            String start = args[1].toLowerCase(Locale.ROOT);
            for (Enchantment en : Registry.ENCHANTMENT) {
                String k = en.getKey().getKey();
                if (k.startsWith(start)) out.add(k);
            }
        } else if (args.length == 3) {
            out.addAll(List.of("1", "10", "50", "100", "255"));
        }
        return out;
    }
}
