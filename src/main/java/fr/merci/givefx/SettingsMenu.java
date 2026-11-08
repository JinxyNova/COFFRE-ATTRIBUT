package fr.merci.givefx;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.List;

/**
 * Menu de réglages en jeu (/givefx menu) : tout se règle à la souris.
 * Clic gauche = augmenter, clic droit = diminuer, Maj + clic = grand pas.
 * Les valeurs sont enregistrées dans menu-settings.yml (elles passent avant config.yml).
 */
public final class SettingsMenu implements Listener {

    private enum Kind { BOOL, INT, TIME }

    private record Setting(int slot, String path, Kind kind, int def, int min, int max, int step, int shiftStep,
                           Material icon, String label, String help, String unit) {}

    private static final List<Setting> SETTINGS = List.of(
            // ── Coffre quotidien
            new Setting(10, "daily-event.enabled", Kind.BOOL, 0, 0, 0, 0, 0, Material.CHEST,
                    "§6Coffre quotidien", "Un coffre rempli apparait chaque jour près des joueurs.", ""),
            new Setting(11, "daily-event.time", Kind.TIME, 480, 0, 0, 0, 0, Material.CLOCK,
                    "§6Heure du coffre", "Heure du serveur (HH:mm).", ""),
            new Setting(12, "daily-event.items-min", Kind.INT, 3, 1, 27, 1, 5, Material.HOPPER,
                    "§6Objets min dans le coffre", "Nombre minimum d'emplacements remplis.", ""),
            new Setting(13, "daily-event.items-max", Kind.INT, 6, 1, 27, 1, 5, Material.HOPPER,
                    "§6Objets max dans le coffre", "Nombre maximum d'emplacements remplis.", ""),
            new Setting(14, "daily-event.drop-chance", Kind.INT, 2, 0, 100, 1, 10, Material.IRON_PICKAXE,
                    "§6Chance d'objet en cassant un bloc", "Chance de trouver un objet de la liste en minant.", "%"),
            new Setting(15, "daily-event.open-events.chance", Kind.INT, 100, 0, 100, 1, 10, Material.TNT,
                    "§6Chance d'événement à l'ouverture", "TNT, minerais, faux coffre, blagues...", "%"),
            // ── Lucky Blocks
            new Setting(19, "daily-event.lucky-blocks.enabled", Kind.BOOL, 1, 0, 0, 0, 0, Material.GOLD_BLOCK,
                    "§eLucky Blocks dans le coffre", "Ajoute des Lucky Blocks (plugin LuckyBlock requis).", ""),
            new Setting(20, "daily-event.lucky-blocks.chance", Kind.INT, 100, 0, 100, 1, 10, Material.GOLD_NUGGET,
                    "§eChance de Lucky Blocks", "100 = tous les coffres en contiennent.", "%"),
            new Setting(21, "daily-event.lucky-blocks.min", Kind.INT, 2, 1, 64, 1, 5, Material.GOLD_INGOT,
                    "§eLucky Blocks min", "Quantité minimum.", ""),
            new Setting(22, "daily-event.lucky-blocks.max", Kind.INT, 10, 1, 64, 1, 5, Material.GOLD_INGOT,
                    "§eLucky Blocks max", "Quantité maximum.", ""),
            // ── Enchantements
            new Setting(28, "unlimited-enchants.enabled", Kind.BOOL, 1, 0, 0, 0, 0, Material.ENCHANTED_BOOK,
                    "§dEnchantements sans limite", "Interrupteur général.", ""),
            new Setting(29, "unlimited-enchants.anvil", Kind.BOOL, 1, 0, 0, 0, 0, Material.ANVIL,
                    "§dEnclume sans plafond", "V + V = VI, VII... sans limite vanilla.", ""),
            new Setting(30, "unlimited-enchants.max-level", Kind.INT, 255, 1, 255, 1, 10, Material.EXPERIENCE_BOTTLE,
                    "§dNiveau maximum", "255 = limite technique de Minecraft.", ""),
            new Setting(31, "unlimited-enchants.ignore-conflicts", Kind.BOOL, 0, 0, 0, 0, 0, Material.NETHER_STAR,
                    "§dIgnorer les incompatibilités", "Autorise Tranchant + Châtiment, etc.", ""),
            new Setting(32, "unlimited-enchants.cost-cap", Kind.INT, 39, 1, 1000, 1, 10, Material.GOLDEN_APPLE,
                    "§dCoût max à l'enclume", "39 = jamais « Trop cher ! ».", " niv."),
            // ── Limites
            new Setting(37, "max-explosion-power", Kind.INT, 20, 1, 100, 1, 10, Material.GUNPOWDER,
                    "§cPuissance max d'explosion", "4 = une TNT.", ""),
            new Setting(38, "max-radius", Kind.INT, 5, 1, 20, 1, 5, Material.DIAMOND_PICKAXE,
                    "§cRayon max « Casser autour »", "1 = zone 3x3x3.", ""),
            new Setting(39, "allow-commands", Kind.BOOL, 0, 0, 0, 0, 0, Material.COMMAND_BLOCK,
                    "§cAutoriser l'action Commande", "DANGEREUX si des joueurs sont en créatif.", "")
    );

    private static final int SLOT_CHEST = 46;
    private static final int SLOT_RELOAD = 48;
    private static final int SLOT_LOOT = 50;
    private static final int SLOT_RESET = 52;

    private static final class Holder implements InventoryHolder {
        private Inventory inv;

        @Override
        public Inventory getInventory() {
            return inv;
        }
    }

    private final GiveFx plugin;

    public SettingsMenu(GiveFx plugin) {
        this.plugin = plugin;
    }

    public void open(Player p) {
        Holder h = new Holder();
        Inventory inv = Bukkit.createInventory(h, 54, Component.text("GiveFx · Réglages"));
        h.inv = inv;
        render(inv);
        p.openInventory(inv);
    }

    // ------------------------------------------------------------ affichage

    private void render(Inventory inv) {
        ItemStack bg = button(Material.GRAY_STAINED_GLASS_PANE, " ", List.of());
        for (int i = 0; i < inv.getSize(); i++) inv.setItem(i, bg);

        for (Setting s : SETTINGS) inv.setItem(s.slot(), settingItem(s));

        inv.setItem(SLOT_CHEST, button(Material.CHEST, "§a§lFaire apparaître un coffre maintenant",
                List.of("§7Pose le coffre du jour près de toi,", "§7sans attendre l'heure prévue.", "", "§8Clic : poser")));
        inv.setItem(SLOT_RELOAD, button(Material.PAPER, "§b§lRecharger config.yml",
                List.of("§7Relit config.yml (les réglages de ce menu", "§7restent prioritaires).", "", "§8Clic : recharger")));
        inv.setItem(SLOT_LOOT, button(Material.BOOK, "§f§lButin du coffre : " + plugin.lootCount() + " objet(s)",
                List.of("§7Pour choisir ce que le coffre contient :", "§e/givefx loot list",
                        "§e/givefx loot add [poids]  §7(objet en main)", "§e/givefx loot addrare <chance%>",
                        "§e/givefx loot remove <n>", "§e/givefx loot clear")));
        inv.setItem(SLOT_RESET, button(Material.BARRIER, "§4§lRéinitialiser ce menu",
                List.of("§7Annule tous les réglages faits ici", "§7et revient à config.yml.", "", "§8Maj + clic : confirmer")));
    }

    private ItemStack settingItem(Setting s) {
        List<String> lore = new ArrayList<>();
        lore.add("§7" + s.help());
        lore.add("");
        switch (s.kind()) {
            case BOOL -> {
                lore.add("§7État : " + (boolValue(s) ? "§a§lACTIVÉ" : "§c§lDÉSACTIVÉ"));
                lore.add("");
                lore.add("§8Clic : activer / désactiver");
            }
            case INT -> {
                lore.add("§7Valeur : §e§l" + intValue(s) + s.unit());
                lore.add("");
                lore.add("§8Clic gauche : +" + s.step() + "   Clic droit : -" + s.step());
                lore.add("§8Maj + clic : ±" + s.shiftStep());
            }
            case TIME -> {
                lore.add("§7Heure : §e§l" + timeText(intValue(s)));
                lore.add("");
                lore.add("§8Clic gauche : +1 h   Clic droit : -1 h");
                lore.add("§8Maj + clic : ±10 min");
            }
        }
        return button(s.icon(), s.label(), lore);
    }

    private static ItemStack button(Material m, String name, List<String> lore) {
        ItemStack it = new ItemStack(m);
        ItemMeta meta = it.getItemMeta();
        LegacyComponentSerializer ser = LegacyComponentSerializer.legacySection();
        meta.displayName(ser.deserialize(name).decoration(TextDecoration.ITALIC, false));
        if (!lore.isEmpty()) {
            List<Component> l = new ArrayList<>();
            for (String line : lore) l.add(ser.deserialize(line).decoration(TextDecoration.ITALIC, false));
            meta.lore(l);
        }
        it.setItemMeta(meta);
        return it;
    }

    // ------------------------------------------------------------ valeurs

    private boolean boolValue(Setting s) {
        return plugin.setting(s.path()) instanceof Boolean b ? b : s.def() == 1;
    }

    private int intValue(Setting s) {
        Object o = plugin.setting(s.path());
        if (s.kind() == Kind.TIME) {
            if (o instanceof String t) {
                try {
                    String[] p = t.trim().split(":");
                    return Integer.parseInt(p[0]) * 60 + Integer.parseInt(p[1]);
                } catch (Exception ignored) {
                    return s.def();
                }
            }
            return s.def();
        }
        return o instanceof Number n ? n.intValue() : s.def();
    }

    private static String timeText(int minutes) {
        return String.format("%02d:%02d", minutes / 60, minutes % 60);
    }

    // ------------------------------------------------------------ clics

    @EventHandler
    public void onClick(InventoryClickEvent e) {
        if (!(e.getInventory().getHolder() instanceof Holder)) return;
        e.setCancelled(true);
        if (!(e.getWhoClicked() instanceof Player p)) return;
        if (e.getClickedInventory() == null || e.getClickedInventory() != e.getInventory()) return;
        if (!p.hasPermission("givefx.admin")) return;

        ClickType ct = e.getClick();
        boolean left = ct.isLeftClick();
        boolean right = ct.isRightClick();
        boolean shift = ct.isShiftClick();
        if (!left && !right) return;
        int slot = e.getRawSlot();

        if (slot == SLOT_CHEST) {
            p.closeInventory();
            plugin.spawnChestNow(p);
            return;
        }
        if (slot == SLOT_RELOAD) {
            plugin.reloadAll();
            p.sendMessage("§aGiveFx rechargé.");
            render(e.getInventory());
            return;
        }
        if (slot == SLOT_RESET) {
            if (shift) {
                plugin.resetOverrides();
                p.sendMessage("§aRéglages du menu réinitialisés.");
                render(e.getInventory());
            } else {
                p.sendMessage("§cMaj + clic pour confirmer la réinitialisation.");
            }
            return;
        }
        for (Setting s : SETTINGS) {
            if (s.slot() != slot) continue;
            change(s, left, shift);
            render(e.getInventory());
            return;
        }
    }

    private void change(Setting s, boolean up, boolean shift) {
        switch (s.kind()) {
            case BOOL -> plugin.applySetting(s.path(), !boolValue(s));
            case INT -> {
                int d = (up ? 1 : -1) * (shift ? s.shiftStep() : s.step());
                int v = Math.max(s.min(), Math.min(s.max(), intValue(s) + d));
                plugin.applySetting(s.path(), v);
            }
            case TIME -> {
                int d = (up ? 1 : -1) * (shift ? 10 : 60);
                int v = Math.floorMod(intValue(s) + d, 1440);
                plugin.applySetting(s.path(), timeText(v));
            }
        }
    }

    @EventHandler
    public void onDrag(InventoryDragEvent e) {
        if (e.getInventory().getHolder() instanceof Holder) e.setCancelled(true);
    }
}
