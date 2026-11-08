package fr.merci.givefx;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Registry;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.block.Block;
import org.bukkit.block.Container;
import org.bukkit.block.TileState;
import org.bukkit.block.BlockState;
import org.bukkit.entity.ExperienceOrb;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Fireball;
import org.bukkit.entity.Item;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.TNTPrimed;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockDropItemEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityShootBowEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.event.player.PlayerToggleSneakEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;

import java.io.File;
import java.io.IOException;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalTime;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/**
 * GiveFx : lit les règles écrites dans l'objet par le générateur de /give
 * (composant custom_data, clé "givegen:fx") et les exécute.
 */
public final class GiveFx extends JavaPlugin implements Listener {

    private record Rule(String trigger, String action, JsonObject p, double cooldown, double chance, String cond) {}

    private NamespacedKey key;
    private final Map<String, List<Rule>> cache = new HashMap<>();
    private final Map<String, Long> cooldowns = new HashMap<>();
    private boolean allowCommands;
    private double maxPower;
    private int maxRadius;
    private final Map<String, Integer> attrGen = new HashMap<>();
    private final Map<String, long[]> pendingDrops = new HashMap<>();
    private final Map<String, float[]> hungerSnap = new HashMap<>();
    private NamespacedKey flyKey;
    private boolean warnedCommands;
    private boolean busy;

    // événement quotidien + objets rares
    /** fx = règles déjà encodées (même format que le générateur), ou null. */
    /** chance < 0 : objet normal (tiré selon weight). chance >= 0 : objet rare, en % à chaque tirage. */
    private record Loot(Material mat, int min, int max, double weight, String name, List<String> lore, String fx, double chance) {}
    private final List<Loot> loot = new ArrayList<>();
    private boolean dailyOn;
    private LocalTime dailyTime = LocalTime.of(8, 0);
    private Material dailyBlock = Material.CHEST;
    private int dailyMinStacks = 3;
    private int dailyMaxStacks = 6;
    private double dropChance;
    // Lucky Blocks ajoutés dans chaque coffre quotidien (plugin LuckyBlock requis)
    private boolean luckyOn = true;
    private double luckyChance = 100;
    private int luckyMin = 2;
    private int luckyMax = 10;
    private boolean luckyWarned = false;
    private final UnlimitedEnchants unlimited = new UnlimitedEnchants();
    private final SettingsMenu menu = new SettingsMenu(this);
    private org.bukkit.configuration.file.YamlConfiguration overrides = new org.bukkit.configuration.file.YamlConfiguration();
    private LocalDate lastDailyDay;
    private NamespacedKey dailyChestKey;
    private double openChance = 100;
    private record OpenEvent(String name, double weight) {}
    private final List<OpenEvent> openEvents = new ArrayList<>();
    private static final List<String> OPEN_EVENTS = List.of(
            "TNT", "ORE_RAIN", "MIMIC", "CHICKENS", "XP", "LIGHTNING", "BOOM", "RARE", "PARTY", "NOTHING",
            "COWS", "PIGS", "SHEEP", "JOKE", "CAKE");
    private static final List<OpenEvent> OPEN_DEFAULTS = List.of(
            new OpenEvent("TNT", 3), new OpenEvent("ORE_RAIN", 4), new OpenEvent("MIMIC", 2),
            new OpenEvent("CHICKENS", 2), new OpenEvent("XP", 3), new OpenEvent("LIGHTNING", 2),
            new OpenEvent("BOOM", 2), new OpenEvent("RARE", 1), new OpenEvent("PARTY", 2),
            new OpenEvent("NOTHING", 2));

    @Override
    public void onEnable() {
        key = NamespacedKey.fromString("givegen:fx");
        flyKey = NamespacedKey.fromString("givefx:fly");
        saveDefaultConfig();
        loadSettings();
        getServer().getPluginManager().registerEvents(this, this);
        getServer().getPluginManager().registerEvents(unlimited, this);
        getServer().getPluginManager().registerEvents(menu, this);
        // effets "en continu" : une fois par seconde
        Bukkit.getScheduler().runTaskTimer(this, this::tickPassive, 20L, 20L);
        // "jamais de faim" : vérifié 4 fois par seconde
        Bukkit.getScheduler().runTaskTimer(this, this::tickHunger, 5L, 5L);
        // événement quotidien : vérifié une fois par minute
        loadLastDaily();
        dailyChestKey = new NamespacedKey(this, "daily-chest");
        Bukkit.getScheduler().runTaskTimer(this, this::tickDaily, 20L * 30, 20L * 60);
    }

    @Override
    public void onDisable() {
        for (Player p : Bukkit.getOnlinePlayers()) cleanAttributes(p);
    }

    /** Retire les bonus d'attributs temporaires laissés par le plugin (arrêt du serveur en plein effet). */
    private void cleanAttributes(Player p) {
        for (Attribute a : Registry.ATTRIBUTE) {
            AttributeInstance inst = p.getAttribute(a);
            if (inst == null) continue;
            for (AttributeModifier m : new ArrayList<>(inst.getModifiers())) {
                if (m.getKey().getNamespace().equals("givefx")) inst.removeModifier(m);
            }
        }
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        cleanAttributes(e.getPlayer());
    }

    private void tickPassive() {
        for (Player p : Bukkit.getOnlinePlayers()) {
            fire("held", p, p.getInventory().getItemInMainHand(), p.getLocation(), null, 0);
            for (ItemStack a : p.getInventory().getArmorContents()) {
                fire("worn", p, a, p.getLocation(), null, 0);
            }
            tickFly(p);
        }
    }

    /** Vol : activé tant qu'un objet avec l'effet "Vol" est tenu ou porté. Le drapeau est gardé dans le joueur (survit à un redémarrage). */
    private void tickFly(Player p) {
        GameMode gm = p.getGameMode();
        if (gm != GameMode.SURVIVAL && gm != GameMode.ADVENTURE) return;
        boolean want = passive(p, "fly") != null;
        boolean has = p.getPersistentDataContainer().get(flyKey, PersistentDataType.BYTE) != null;
        if (want && !has) {
            p.setAllowFlight(true);
            p.getPersistentDataContainer().set(flyKey, PersistentDataType.BYTE, (byte) 1);
        } else if (!want && has) {
            p.setFlying(false);
            p.setAllowFlight(false);
            p.getPersistentDataContainer().remove(flyKey);
        }
    }

    /** Jamais de faim : si la faim ou la saturation baisse, on remet la valeur d'avant. */
    private void tickHunger() {
        for (Player p : Bukkit.getOnlinePlayers()) {
            String id = p.getUniqueId().toString();
            if (passive(p, "nohunger") == null) {
                hungerSnap.remove(id);
                continue;
            }
            int f = p.getFoodLevel();
            float sat = p.getSaturation();
            float[] sn = hungerSnap.get(id);
            if (sn == null) {
                hungerSnap.put(id, new float[]{f, sat});
                continue;
            }
            if (f < sn[0]) p.setFoodLevel((int) sn[0]);
            else sn[0] = f;
            if (sat < sn[1]) p.setSaturation(sn[1]);
            else sn[1] = sat;
        }
    }

    /** Cherche une règle "en continu" (tenu en main ou porté) avec cette action. */
    private Rule passive(Player p, String action) {
        for (Rule r : rules(p.getInventory().getItemInMainHand())) {
            if (r.trigger().equals("held") && r.action().equals(action) && cond(r.cond(), p)) return r;
        }
        for (ItemStack a : p.getInventory().getArmorContents()) {
            for (Rule r : rules(a)) {
                if (r.trigger().equals("worn") && r.action().equals(action) && cond(r.cond(), p)) return r;
            }
        }
        return null;
    }

    private void loadSettings() {
        reloadConfig();
        loadOverrides();
        cache.clear();
        cooldowns.clear();
        reapplySettings();
    }

    /** Relit les valeurs de la config en mémoire (après un rechargement ou un changement du menu). */
    private void reapplySettings() {
        allowCommands = getConfig().getBoolean("allow-commands", false);
        maxPower = getConfig().getDouble("max-explosion-power", 20);
        maxRadius = getConfig().getInt("max-radius", 5);
        loadDaily();
        unlimited.load(getConfig());
    }

    // ------------------------------------------------------------ réglages du menu (menu-settings.yml)

    private File overridesFile() {
        return new File(getDataFolder(), "menu-settings.yml");
    }

    /** Les réglages faits en jeu passent avant config.yml (config.yml n'est jamais réécrit). */
    private void loadOverrides() {
        overrides = org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(overridesFile());
        for (String k : overrides.getKeys(true)) {
            Object v = overrides.get(k);
            if (!(v instanceof ConfigurationSection)) getConfig().set(k, v);
        }
    }

    Object setting(String path) {
        return getConfig().get(path);
    }

    void applySetting(String path, Object value) {
        overrides.set(path, value);
        try {
            getDataFolder().mkdirs();
            overrides.save(overridesFile());
        } catch (IOException e) {
            getLogger().warning("Impossible d'écrire menu-settings.yml : " + e.getMessage());
        }
        getConfig().set(path, value);
        reapplySettings();
    }

    void resetOverrides() {
        try {
            Files.deleteIfExists(overridesFile().toPath());
        } catch (IOException e) {
            getLogger().warning("Impossible de supprimer menu-settings.yml : " + e.getMessage());
        }
        loadSettings();
    }

    void reloadAll() {
        loadSettings();
    }

    int lootCount() {
        return loot.size();
    }

    /** Pose tout de suite le coffre du jour près du joueur (sans attendre l'heure, sans limite par jour). */
    void spawnChestNow(Player p) {
        placeDailyBlock(p);
    }

    // ------------------------------------------------------------ butin du coffre (commandes /givefx loot)

    private List<Map<String, Object>> currentLootList() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<?, ?> m : getConfig().getMapList("daily-event.loot")) {
            Map<String, Object> c = new java.util.LinkedHashMap<>();
            for (Map.Entry<?, ?> en : m.entrySet()) c.put(String.valueOf(en.getKey()), en.getValue());
            out.add(c);
        }
        return out;
    }

    private boolean lootCommand(CommandSender sender, String[] args) {
        String sub = args.length > 1 ? args[1].toLowerCase() : "list";
        List<Map<String, Object>> list = currentLootList();
        switch (sub) {
            case "list" -> {
                if (list.isEmpty()) sender.sendMessage("§7Le butin est vide.");
                int i = 1;
                for (Map<String, Object> m : list) {
                    Object max = m.getOrDefault("max", m.getOrDefault("min", 1));
                    String extra = m.get("chance") != null ? " §6rare " + m.get("chance") + "%" : " §7poids " + m.getOrDefault("weight", 1);
                    String nm = m.get("name") != null ? " §7(" + m.get("name") + "§7)" : "";
                    sender.sendMessage("§e#" + i + " §f" + m.get("item") + " §7x" + m.getOrDefault("min", 1) + "-" + max + extra + nm);
                    i++;
                }
            }
            case "add", "addrare" -> {
                if (!(sender instanceof Player p)) {
                    sender.sendMessage("Commande réservée aux joueurs.");
                    return true;
                }
                ItemStack hand = p.getInventory().getItemInMainHand();
                if (hand.getType().isAir()) {
                    sender.sendMessage("§cTiens l'objet à ajouter en main principale.");
                    return true;
                }
                boolean rare = sub.equals("addrare");
                double val;
                try {
                    val = args.length > 2 ? Double.parseDouble(args[2]) : (rare ? 1 : 10);
                } catch (NumberFormatException ex) {
                    sender.sendMessage("§cNombre invalide : " + args[2]);
                    return true;
                }
                if (val <= 0) {
                    sender.sendMessage("§cLe nombre doit être supérieur à 0.");
                    return true;
                }
                Map<String, Object> m = new java.util.LinkedHashMap<>();
                m.put("item", hand.getType().name());
                m.put("min", hand.getAmount());
                m.put("max", hand.getAmount());
                if (rare) m.put("chance", Math.min(100, val));
                else m.put("weight", val);
                ItemMeta meta = hand.getItemMeta();
                if (meta != null) {
                    if (meta.hasDisplayName()) m.put("name", LegacyComponentSerializer.legacyAmpersand().serialize(meta.displayName()));
                    List<net.kyori.adventure.text.Component> lore = meta.lore();
                    if (lore != null && !lore.isEmpty()) {
                        m.put("lore", lore.stream().map(x -> LegacyComponentSerializer.legacyAmpersand().serialize(x)).toList());
                    }
                    String raw = meta.getPersistentDataContainer().get(key, PersistentDataType.STRING);
                    if (raw != null) m.put("fxraw", raw);
                }
                list.add(m);
                applySetting("daily-event.loot", list);
                sender.sendMessage("§aAjouté au butin : " + hand.getType().name() + " x" + hand.getAmount()
                        + (rare ? " (rare " + Math.min(100, val) + "%)" : " (poids " + val + ")") + " §7· #" + list.size());
            }
            case "remove" -> {
                int n;
                try {
                    n = args.length > 2 ? Integer.parseInt(args[2]) : -1;
                } catch (NumberFormatException ex) {
                    n = -1;
                }
                if (n < 1 || n > list.size()) {
                    sender.sendMessage("§c/givefx loot remove <numéro>  §7(voir /givefx loot list)");
                    return true;
                }
                Map<String, Object> gone = list.remove(n - 1);
                applySetting("daily-event.loot", list);
                sender.sendMessage("§aRetiré : " + gone.get("item"));
            }
            case "clear" -> {
                applySetting("daily-event.loot", new ArrayList<Map<String, Object>>());
                sender.sendMessage("§aButin vidé (le coffre ne contiendra plus que des Lucky Blocks).");
            }
            default -> sender.sendMessage("§e/givefx loot <list | add [poids] | addrare <chance%> | remove <n> | clear>");
        }
        return true;
    }

    // ------------------------------------------------------------ événement quotidien

    /** Lit la section daily-event de config.yml. Sans cette section, rien ne se passe. */
    private void loadDaily() {
        loot.clear();
        openEvents.clear();
        dailyOn = false;
        ConfigurationSection s = getConfig().getConfigurationSection("daily-event");
        if (s == null) return;
        dailyOn = s.getBoolean("enabled", false);
        ConfigurationSection oe = s.getConfigurationSection("open-events");
        openChance = oe == null ? 100 : Math.max(0, Math.min(100, oe.getDouble("chance", 100)));
        if (oe != null) {
            for (Map<?, ?> m : oe.getMapList("events")) {
                String n = String.valueOf(m.get("event")).toUpperCase();
                double w = toD(m.get("weight"), 1);
                if (!OPEN_EVENTS.contains(n)) {
                    getLogger().warning("Événement d'ouverture inconnu : " + n);
                } else if (w > 0) {
                    openEvents.add(new OpenEvent(n, w));
                }
            }
        }
        if (openEvents.isEmpty()) openEvents.addAll(OPEN_DEFAULTS);
        dailyTime = parseTime(s.getString("time", "08:00"));
        Material m = Material.matchMaterial(s.getString("block", "CHEST"));
        dailyBlock = (m != null && m.isBlock()) ? m : Material.CHEST;
        dailyMinStacks = (int) Math.max(1, Math.min(27, s.getDouble("items-min", 3)));
        dailyMaxStacks = (int) Math.max(dailyMinStacks, Math.min(27, s.getDouble("items-max", 6)));
        dropChance = Math.max(0, Math.min(100, s.getDouble("drop-chance", 2)));
        ConfigurationSection lb = s.getConfigurationSection("lucky-blocks");
        luckyOn = lb == null || lb.getBoolean("enabled", true);
        luckyChance = lb == null ? 100 : Math.max(0, Math.min(100, lb.getDouble("chance", 100)));
        luckyMin = lb == null ? 2 : (int) Math.max(1, Math.min(64, lb.getDouble("min", 2)));
        luckyMax = lb == null ? 10 : (int) Math.max(luckyMin, Math.min(64, lb.getDouble("max", 10)));
        for (Map<?, ?> e : s.getMapList("loot")) {
            Material mat = Material.matchMaterial(String.valueOf(e.get("item")));
            if (mat == null || !mat.isItem()) {
                getLogger().warning("Objet inconnu dans daily-event.loot : " + e.get("item"));
                continue;
            }
            int min = (int) Math.max(1, toD(e.get("min"), 1));
            int max = (int) Math.max(min, toD(e.get("max"), min));
            double w = toD(e.get("weight"), 1);
            String name = e.get("name") == null ? null : String.valueOf(e.get("name"));
            List<String> lore = new ArrayList<>();
            if (e.get("lore") instanceof List<?> ll) for (Object x : ll) lore.add(String.valueOf(x));
            String fx = e.get("fxraw") instanceof String raw && !raw.isBlank() ? raw : null;
            if (fx == null && e.get("fx") instanceof List<?> fl && !fl.isEmpty()) {
                String enc = URLEncoder.encode(toJson(fl).toString(), StandardCharsets.UTF_8);
                if (parse(enc).isEmpty()) getLogger().warning("Règles fx invalides pour l'objet " + e.get("item"));
                else fx = enc;
            }
            double ch = toD(e.get("chance"), -1);
            if (ch >= 0 ? ch > 0 : w > 0) loot.add(new Loot(mat, min, max, w, name, lore, fx, ch));
        }
        if (loot.isEmpty()) {
            dailyOn = false;
            if (s.getBoolean("enabled", false)) getLogger().warning("daily-event activé mais sans objets (loot) : désactivé.");
        }
    }

    private static LocalTime parseTime(String t) {
        try {
            return LocalTime.parse(t.trim());
        } catch (Exception e) {
            return LocalTime.of(8, 0);
        }
    }

    /** Convertit la config YAML (maps, listes, nombres, texte) en JSON pour les règles. */
    private static JsonElement toJson(Object o) {
        if (o instanceof Map<?, ?> m) {
            JsonObject jo = new JsonObject();
            for (Map.Entry<?, ?> en : m.entrySet()) jo.add(String.valueOf(en.getKey()), toJson(en.getValue()));
            return jo;
        }
        if (o instanceof List<?> l) {
            JsonArray ja = new JsonArray();
            for (Object x : l) ja.add(toJson(x));
            return ja;
        }
        if (o instanceof Number n) return new JsonPrimitive(n);
        if (o instanceof Boolean b) return new JsonPrimitive(b);
        return new JsonPrimitive(String.valueOf(o));
    }

    private static double toD(Object o, double d) {
        if (o instanceof Number n) return n.doubleValue();
        try {
            return Double.parseDouble(String.valueOf(o));
        } catch (Exception e) {
            return d;
        }
    }

    private File dailyFile() {
        return new File(getDataFolder(), "daily.txt");
    }

    private void loadLastDaily() {
        try {
            Path p = dailyFile().toPath();
            if (Files.exists(p)) lastDailyDay = LocalDate.parse(Files.readString(p).trim());
        } catch (Exception e) {
            getLogger().warning("Impossible de lire daily.txt : " + e.getMessage());
        }
    }

    private void saveLastDaily(LocalDate d) {
        lastDailyDay = d;
        try {
            getDataFolder().mkdirs();
            Files.writeString(dailyFile().toPath(), d.toString());
        } catch (IOException e) {
            getLogger().warning("Impossible d'écrire daily.txt : " + e.getMessage());
        }
    }

    /** Une fois par jour, après l'heure prévue, si au moins un joueur est connecté. */
    private void tickDaily() {
        if (!dailyOn) return;
        LocalDate today = LocalDate.now();
        if (today.equals(lastDailyDay)) return;
        if (LocalTime.now().isBefore(dailyTime)) return;
        if (Bukkit.getOnlinePlayers().isEmpty()) return;
        saveLastDaily(today);
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (p.getGameMode() == GameMode.SPECTATOR) continue;
            placeDailyBlock(p);
        }
    }

    /** Pose le bloc quotidien à 4-8 blocs du joueur, sur un sol libre, et le remplit d'objets aléatoires. */
    private void placeDailyBlock(Player p) {
        World w = p.getWorld();
        Location base = p.getLocation();
        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        for (int attempt = 0; attempt < 25; attempt++) {
            double ang = rnd.nextDouble() * Math.PI * 2;
            int dist = rnd.nextInt(4, 9);
            int x = base.getBlockX() + (int) Math.round(Math.cos(ang) * dist);
            int z = base.getBlockZ() + (int) Math.round(Math.sin(ang) * dist);
            for (int y = base.getBlockY() + 3; y >= base.getBlockY() - 4; y--) {
                Block spot = w.getBlockAt(x, y, z);
                if (!spot.getType().isAir()) continue;
                if (!w.getBlockAt(x, y - 1, z).getType().isSolid()) continue;
                if (!w.getBlockAt(x, y + 1, z).getType().isAir()) continue;
                spot.setType(dailyBlock, false);
                if (spot.getState() instanceof Container c) {
                    int stacks = rnd.nextInt(dailyMinStacks, dailyMaxStacks + 1);
                    for (int i = 0; i < stacks; i++) {
                        ItemStack s2 = rollRare();
                        if (s2 == null) s2 = rollCommon();
                        if (s2 != null) c.getInventory().addItem(s2);
                    }
                    ItemStack lucky = rollLuckyBlocks();
                    if (lucky != null) c.getInventory().addItem(lucky);
                }
                BlockState marked = spot.getState();
                if (marked instanceof TileState ts) {
                    ts.getPersistentDataContainer().set(dailyChestKey, PersistentDataType.BYTE, (byte) 1);
                    ts.update(true, false);
                }
                Particle pt = particle("happy_villager");
                if (pt != null) w.spawnParticle(pt, spot.getLocation().add(0.5, 1, 0.5), 20, 0.4, 0.4, 0.4, 0.02);
                p.sendMessage("§6Un bloc mystérieux est apparu près de toi !");
                return;
            }
        }
        p.sendMessage("§7Le bloc du jour n'a pas trouvé de place près de toi aujourd'hui.");
    }

    /**
     * Lucky Blocks du plugin LuckyBlock (entre luckyMin et luckyMax), avec luckyChance % de chance.
     * Utilise l'objet officiel du plugin LuckyBlock (par réflexion, pas de dépendance au build).
     */
    private ItemStack rollLuckyBlocks() {
        if (!luckyOn) return null;
        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        if (luckyChance < 100 && rnd.nextDouble() * 100 >= luckyChance) return null;
        org.bukkit.plugin.Plugin lb = getServer().getPluginManager().getPlugin("LuckyBlock");
        if (lb == null || !lb.isEnabled()) {
            if (!luckyWarned) {
                luckyWarned = true;
                getLogger().warning("Plugin LuckyBlock introuvable : pas de Lucky Blocks dans les coffres.");
            }
            return null;
        }
        int n = rnd.nextInt(luckyMin, luckyMax + 1);
        try {
            ClassLoader cl = lb.getClass().getClassLoader();
            Class<?> util = Class.forName("fr.raratt.luckyblock.ItemUtil", true, cl);
            Class<?> pl = Class.forName("fr.raratt.luckyblock.LuckyBlockPlugin", true, cl);
            Object r = util.getMethod("createLuckyBlockItem", pl, int.class).invoke(null, lb, n);
            return r instanceof ItemStack it ? it : null;
        } catch (Throwable t) {
            if (!luckyWarned) {
                luckyWarned = true;
                getLogger().warning("Impossible de créer un Lucky Block : " + t);
            }
            return null;
        }
    }

    /** Tirage des objets rares : chaque objet a sa propre chance (en %), indépendante des autres. */
    private ItemStack rollRare() {
        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        for (Loot l : loot) {
            if (l.chance() >= 0 && rnd.nextDouble() * 100 < l.chance()) return makeStack(l);
        }
        return null;
    }

    /** Tirage des objets normaux, selon leur weight. */
    private ItemStack rollCommon() {
        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        double total = 0;
        for (Loot l : loot) if (l.chance() < 0) total += l.weight();
        if (total <= 0) return null;
        double r = rnd.nextDouble() * total;
        Loot pick = null;
        for (Loot l : loot) {
            if (l.chance() >= 0) continue;
            pick = l;
            r -= l.weight();
            if (r < 0) break;
        }
        return pick == null ? null : makeStack(pick);
    }

    private ItemStack makeStack(Loot pick) {
        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        int n = rnd.nextInt(pick.min(), pick.max() + 1);
        ItemStack it = new ItemStack(pick.mat(), Math.min(n, pick.mat().getMaxStackSize()));
        if (pick.name() == null && pick.lore().isEmpty() && pick.fx() == null) return it;
        ItemMeta meta = it.getItemMeta();
        if (pick.name() != null) meta.displayName(LegacyComponentSerializer.legacyAmpersand().deserialize(pick.name()));
        if (!pick.lore().isEmpty()) {
            meta.lore(pick.lore().stream().map(x -> LegacyComponentSerializer.legacyAmpersand().deserialize(x)).toList());
        }
        if (pick.fx() != null) meta.getPersistentDataContainer().set(key, PersistentDataType.STRING, pick.fx());
        it.setItemMeta(meta);
        return it;
    }

    /** À l'ouverture d'un coffre quotidien : un événement, une seule fois par coffre. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onDailyChestOpen(InventoryOpenEvent e) {
        if (!dailyOn || dailyChestKey == null) return;
        if (!(e.getPlayer() instanceof Player p)) return;
        Location loc = e.getInventory().getLocation();
        if (loc == null) return;
        Bukkit.getScheduler().runTask(this, () -> {
            Block b = loc.getBlock();
            if (!(b.getState() instanceof TileState ts)) return;
            PersistentDataContainer pdc = ts.getPersistentDataContainer();
            if (!pdc.has(dailyChestKey, PersistentDataType.BYTE)) return;
            pdc.remove(dailyChestKey);
            ts.update(true, false);
            if (ThreadLocalRandom.current().nextDouble() * 100 < openChance) runOpenEvent(loc, p);
        });
    }

    private void runOpenEvent(Location loc, Player p) {
        World w = loc.getWorld();
        if (w == null) return;
        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        Location c = loc.clone().add(0.5, 0.5, 0.5);
        double total = 0;
        for (OpenEvent ev : openEvents) total += ev.weight();
        double r = rnd.nextDouble() * total;
        String pick = openEvents.get(0).name();
        for (OpenEvent ev : openEvents) {
            r -= ev.weight();
            if (r < 0) { pick = ev.name(); break; }
        }
        String title;
        switch (pick) {
            case "TNT" -> {
                title = "§c💣 Des TNT sortent du coffre !";
                for (int i = 0; i < 6; i++) {
                    Location at = c.clone().add(rnd.nextDouble(-3, 3), 1, rnd.nextDouble(-3, 3));
                    w.spawn(at, TNTPrimed.class, t -> t.setFuseTicks(40 + rnd.nextInt(40)));
                }
            }
            case "ORE_RAIN" -> {
                title = "§b⛏ Pluie de minerais !";
                for (int i = 0; i < 25; i++) {
                    Location at = c.clone().add(rnd.nextDouble(-3, 3), 6, rnd.nextDouble(-3, 3));
                    w.dropItemNaturally(at, oreStack(rnd));
                }
            }
            case "MIMIC" -> {
                title = "§6🎁 C'était un mimic !";
                Entity mimic = w.spawnEntity(c.clone().add(0, 1, 0), EntityType.ZOMBIE);
                mimic.customName(LegacyComponentSerializer.legacyAmpersand().deserialize("&6Faux coffre"));
                mimic.setCustomNameVisible(true);
            }
            case "CHICKENS" -> {
                title = "§e🐔 Coin coin ! Des poulets partout !";
                for (int i = 0; i < 8; i++) {
                    w.spawnEntity(c.clone().add(rnd.nextDouble(-3, 3), 1, rnd.nextDouble(-3, 3)), EntityType.CHICKEN);
                }
            }
            case "XP" -> {
                title = "§a✨ Une pluie d'expérience !";
                for (int i = 0; i < 10; i++) {
                    w.spawn(c.clone().add(rnd.nextDouble(-2, 2), 1, rnd.nextDouble(-2, 2)), ExperienceOrb.class, o -> o.setExperience(5));
                }
            }
            case "LIGHTNING" -> {
                title = "§9⚡ Le coffre appelle la foudre !";
                w.strikeLightningEffect(c);
            }
            case "BOOM" -> {
                title = "§c💥 BOUM !";
                w.createExplosion(c, 3f, false, false);
            }
            case "RARE" -> {
                title = "§d🌟 Un objet rare tombe du ciel !";
                ItemStack s = rollRare();
                if (s == null) s = rollCommon();
                if (s != null) w.dropItemNaturally(c.clone().add(0, 1, 0), s);
            }
            case "PARTY" -> {
                title = "§5🎉 Fête générale !";
                w.spawnParticle(Particle.FIREWORK, c, 120, 2, 2, 2, 0.2);
            }
            case "COWS" -> {
                title = "§f🐄 MOOOO ! Une invasion de vaches !";
                for (int i = 0; i < 5; i++) w.spawnEntity(c.clone().add(rnd.nextDouble(-3, 3), 1, rnd.nextDouble(-3, 3)), EntityType.COW);
            }
            case "PIGS" -> {
                title = "§d🐷 Oink ! Des cochons sortent du coffre !";
                for (int i = 0; i < 5; i++) w.spawnEntity(c.clone().add(rnd.nextDouble(-3, 3), 1, rnd.nextDouble(-3, 3)), EntityType.PIG);
            }
            case "SHEEP" -> {
                title = "§f🐑 Bêêê ! Un troupeau apparaît !";
                for (int i = 0; i < 5; i++) w.spawnEntity(c.clone().add(rnd.nextDouble(-3, 3), 1, rnd.nextDouble(-3, 3)), EntityType.SHEEP);
            }
            case "JOKE" -> {
                String[] blagues = {
                    "§eQu’est-ce qu’un canard qui rit ? Un canard qui pète. Pardon.",
                    "§eQuel est le comble du mineur ? Avoir un bon filon... de rigolade.",
                    "§eQue dit un coffre à un autre ? « T’es pas assez plein ! »",
                    "§eQu’est-ce qu’un poulet au ski ? Un poulet... qui pousse la chansonnette.",
                    "§eTu veux entendre une blague sur le diamant ? Dommage, elle est trop chère.",
                    "§eQuel est le comble d’un zombie ? Être mort de rire.",
                    "§eCe coffre est tellement vieux qu’il a des souvenirs de la préhistoire.",
                    "§eBravo, tu as ouvert le coffre. Tu as gagné... le droit de continuer à jouer."
                };
                title = blagues[rnd.nextInt(blagues.length)];
            }
            case "CAKE" -> {
                title = "§d🎂 Joyeux anniversaire ! Un gâteau pour toi !";
                w.dropItemNaturally(c.clone().add(0, 1, 0), new ItemStack(Material.CAKE, 3));
            }
            default -> title = "§7Le coffre est vide... enfin, presque.";
        }
        p.sendMessage(title);
    }

    private ItemStack oreStack(ThreadLocalRandom rnd) {
        Material[] ores = {Material.COAL, Material.IRON_INGOT, Material.GOLD_INGOT, Material.COPPER_INGOT,
                Material.LAPIS_LAZULI, Material.REDSTONE, Material.EMERALD, Material.DIAMOND};
        Material m = rnd.nextInt(40) == 0 ? Material.NETHERITE_SCRAP : ores[rnd.nextInt(ores.length)];
        return new ItemStack(m, 1 + rnd.nextInt(3));
    }

    /** Petite chance, en cassant un bloc en survie, de trouver un objet de la liste. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onRareDrop(BlockBreakEvent e) {
        if (!dailyOn || loot.isEmpty()) return;
        if (e.getPlayer().getGameMode() != GameMode.SURVIVAL) return;
        Location at = center(e.getBlock());
        ItemStack rare = rollRare();
        if (rare != null) {
            e.getBlock().getWorld().dropItemNaturally(at, rare);
        } else if (dropChance > 0 && ThreadLocalRandom.current().nextDouble() * 100 < dropChance) {
            ItemStack common = rollCommon();
            if (common != null) e.getBlock().getWorld().dropItemNaturally(at, common);
        }
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("givefx.admin")) {
            sender.sendMessage("Permission refusée.");
            return true;
        }
        String sub = args.length > 0 ? args[0].toLowerCase() : "menu";
        switch (sub) {
            case "menu" -> {
                if (sender instanceof Player p) menu.open(p);
                else sender.sendMessage("Le menu est réservé aux joueurs.");
            }
            case "reload" -> {
                loadSettings();
                sender.sendMessage("GiveFx rechargé.");
            }
            case "chest" -> {
                Player target = args.length > 1 ? Bukkit.getPlayerExact(args[1]) : (sender instanceof Player p ? p : null);
                if (target == null) sender.sendMessage("§c/givefx chest [joueur]");
                else spawnChestNow(target);
            }
            case "loot" -> {
                return lootCommand(sender, args);
            }
            case "enchant" -> {
                return unlimited.command(sender, args);
            }
            default -> {
                sender.sendMessage("§e/givefx §7menu  §8- réglages en jeu");
                sender.sendMessage("§e/givefx chest [joueur]  §8- pose un coffre tout de suite");
                sender.sendMessage("§e/givefx loot <list|add|addrare|remove|clear>  §8- butin du coffre");
                sender.sendMessage("§e/givefx enchant <enchantement> <niveau>");
                sender.sendMessage("§e/givefx reload");
            }
        }
        return true;
    }

    @Override
    public java.util.List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!sender.hasPermission("givefx.admin")) return java.util.List.of();
        if (args.length == 1) return java.util.List.of("menu", "chest", "loot", "enchant", "reload");
        if (args[0].equalsIgnoreCase("enchant")) return unlimited.complete(args);
        if (args[0].equalsIgnoreCase("loot") && args.length == 2) return java.util.List.of("list", "add", "addrare", "remove", "clear");
        return java.util.List.of();
    }

    // ------------------------------------------------------------ lecture des règles

    private List<Rule> rules(ItemStack item) {
        if (item == null || item.getType().isAir() || !item.hasItemMeta()) return List.of();
        String raw = item.getItemMeta().getPersistentDataContainer().get(key, PersistentDataType.STRING);
        if (raw == null) return List.of();
        return cache.computeIfAbsent(raw, this::parse);
    }

    private List<Rule> parse(String raw) {
        try {
            String json = URLDecoder.decode(raw, StandardCharsets.UTF_8);
            JsonArray arr = JsonParser.parseString(json).getAsJsonArray();
            List<Rule> out = new ArrayList<>();
            for (JsonElement e : arr) {
                JsonObject o = e.getAsJsonObject();
                out.add(new Rule(
                        o.get("t").getAsString(),
                        o.get("a").getAsString(),
                        o.has("p") ? o.getAsJsonObject("p") : new JsonObject(),
                        o.has("cd") ? o.get("cd").getAsDouble() : 0,
                        o.has("ch") ? o.get("ch").getAsDouble() : 100,
                        o.has("if") ? o.get("if").getAsString() : ""));
            }
            return out;
        } catch (Exception ex) {
            getLogger().warning("Règles illisibles sur un objet : " + ex.getMessage());
            return List.of();
        }
    }

    private void fire(String trigger, Player p, ItemStack item, Location target, Block block, double dmg) {
        if (busy) return; // évite les boucles (un effet qui déclenche un autre effet)
        List<Rule> rs = rules(item);
        if (rs.isEmpty()) return;
        busy = true;
        try {
            for (int i = 0; i < rs.size(); i++) {
                Rule r = rs.get(i);
                if (!r.trigger().equals(trigger)) continue;
                if (!cond(r.cond(), p)) continue;
                if (r.chance() < 100 && ThreadLocalRandom.current().nextDouble() * 100 >= r.chance()) continue;
                if (r.cooldown() > 0) {
                    String k = p.getUniqueId() + "|" + System.identityHashCode(rs) + "|" + i;
                    long now = System.currentTimeMillis();
                    Long until = cooldowns.get(k);
                    if (until != null && now < until) continue;
                    cooldowns.put(k, now + (long) (r.cooldown() * 1000));
                }
                try {
                    run(r, p, item, target, block, dmg);
                } catch (Exception ex) {
                    getLogger().warning("Effet \"" + r.action() + "\" : " + ex);
                }
            }
        } finally {
            busy = false;
        }
    }

    private static boolean cond(String c, Player p) {
        return switch (c) {
            case "sneak" -> p.isSneaking();
            case "air" -> !p.isOnGround();
            case "ground" -> p.isOnGround();
            case "water" -> p.isInWater();
            case "lowhp" -> p.getHealth() < maxHealth(p) / 2;
            default -> true;
        };
    }

    private static double maxHealth(Player p) {
        Attribute attr = Registry.ATTRIBUTE.get(NamespacedKey.minecraft("max_health"));
        if (attr != null) {
            AttributeInstance inst = p.getAttribute(attr);
            if (inst != null) return inst.getValue();
        }
        return 20;
    }

    private static void removeMod(AttributeInstance inst, NamespacedKey mk) {
        for (AttributeModifier m : new ArrayList<>(inst.getModifiers())) {
            if (m.getKey().equals(mk)) inst.removeModifier(m);
        }
    }

    // ------------------------------------------------------------ événements

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent e) {
        Player p = e.getPlayer();
        fire("break", p, p.getInventory().getItemInMainHand(), center(e.getBlock()), e.getBlock(), 0);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent e) {
        fire("place", e.getPlayer(), e.getItemInHand(), center(e.getBlockPlaced()), e.getBlockPlaced(), 0);
    }

    @EventHandler
    public void onInteract(PlayerInteractEvent e) {
        if (e.getHand() != EquipmentSlot.HAND) return;
        if (e.useItemInHand() == Event.Result.DENY) return;
        Action a = e.getAction();
        String t;
        if (a == Action.RIGHT_CLICK_AIR || a == Action.RIGHT_CLICK_BLOCK) t = "right";
        else if (a == Action.LEFT_CLICK_AIR || a == Action.LEFT_CLICK_BLOCK) t = "left";
        else return;
        ItemStack item = e.getItem();
        if (item == null) return;
        Block cb = e.getClickedBlock();
        Location target = cb != null ? center(cb) : aim(e.getPlayer());
        fire(t, e.getPlayer(), item, target, cb, 0);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onHit(EntityDamageByEntityEvent e) {
        if (!(e.getDamager() instanceof Player p)) return;
        Entity v = e.getEntity();
        fire("hit", p, p.getInventory().getItemInMainHand(), v.getLocation().add(0, v.getHeight() / 2, 0), null, e.getFinalDamage());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onKill(EntityDeathEvent e) {
        Player p = e.getEntity().getKiller();
        if (p == null) return;
        fire("kill", p, p.getInventory().getItemInMainHand(), e.getEntity().getLocation(), null, 0);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEat(PlayerItemConsumeEvent e) {
        fire("eat", e.getPlayer(), e.getItem(), e.getPlayer().getLocation(), null, 0);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onSneak(PlayerToggleSneakEvent e) {
        if (!e.isSneaking()) return;
        Player p = e.getPlayer();
        fire("sneak", p, p.getInventory().getItemInMainHand(), p.getLocation(), null, 0);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onHurt(EntityDamageEvent e) {
        if (!(e.getEntity() instanceof Player p)) return;
        fire("hurt", p, p.getInventory().getItemInMainHand(), p.getLocation(), null, e.getFinalDamage());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onShoot(EntityShootBowEvent e) {
        if (!(e.getEntity() instanceof Player p)) return;
        fire("shoot", p, e.getBow(), aim(p), null, 0);
    }

    /** Dégâts infligés multipliés : pas de plafond, contrairement à l'attribut. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onHitBoost(EntityDamageByEntityEvent e) {
        if (!(e.getDamager() instanceof Player p)) return;
        Rule r = passive(p, "damage_boost");
        if (r == null) return;
        e.setDamage(e.getDamage() * num(r.p(), "mult", 2) + num(r.p(), "add", 0));
    }

    /** Dégâts reçus réduits : 100 % = invincible. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onHurtReduce(EntityDamageEvent e) {
        if (!(e.getEntity() instanceof Player p)) return;
        Rule r = passive(p, "damage_reduce");
        if (r == null) return;
        double pct = Math.max(0, Math.min(100, num(r.p(), "percent", 50)));
        if (pct >= 100) e.setCancelled(true);
        else e.setDamage(e.getDamage() * (1 - pct / 100.0));
    }

    /** Butin multiplié (règle "Je casse un bloc" -> "Butin multiplié"). */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDrops(BlockDropItemEvent e) {
        long[] pd = pendingDrops.remove(e.getPlayer().getUniqueId().toString());
        if (pd == null || pd[0] < 2 || System.currentTimeMillis() > pd[1]) return;
        Location loc = e.getBlock().getLocation().add(0.5, 0.5, 0.5);
        for (Item it : new ArrayList<>(e.getItems())) {
            for (int i = 1; i < pd[0]; i++) {
                loc.getWorld().dropItemNaturally(loc, it.getItemStack().clone());
            }
        }
    }

    // ------------------------------------------------------------ cibles

    private static Location center(Block b) {
        return b.getLocation().add(0.5, 0.5, 0.5);
    }

    /** Le point regardé par le joueur (60 blocs max), sinon 8 blocs devant lui. */
    private static Location aim(Player p) {
        Location eye = p.getEyeLocation();
        RayTraceResult r = p.getWorld().rayTraceBlocks(eye, eye.getDirection(), 60);
        if (r != null && r.getHitPosition() != null) return r.getHitPosition().toLocation(p.getWorld());
        return eye.clone().add(eye.getDirection().multiply(8));
    }

    // ------------------------------------------------------------ actions

    private void run(Rule r, Player p, ItemStack item, Location at, Block block, double dmg) {
        World w = at.getWorld();
        JsonObject o = r.p();
        switch (r.action()) {
            case "explode" -> w.createExplosion(at,
                    (float) Math.min(maxPower, num(o, "power", 4)),
                    bool(o, "fire", false), bool(o, "blocks", true), p);

            case "lightning" -> {
                if (bool(o, "harmless", false)) w.strikeLightningEffect(at);
                else w.strikeLightning(at);
            }

            case "tnt" -> {
                int n = (int) Math.max(1, Math.min(20, num(o, "count", 1)));
                int fuse = (int) (num(o, "fuse", 2) * 20);
                for (int i = 0; i < n; i++) {
                    TNTPrimed t = w.spawn(at, TNTPrimed.class);
                    t.setFuseTicks(fuse);
                    t.setSource(p);
                }
            }

            case "fire" -> {
                int ticks = (int) (num(o, "sec", 5) * 20);
                for (LivingEntity le : w.getNearbyLivingEntities(at, num(o, "radius", 3))) {
                    if (!le.equals(p)) le.setFireTicks(ticks);
                }
                if (bool(o, "ground", false)) {
                    Block b = at.getBlock();
                    if (!b.getType().isAir()) b = b.getRelative(0, 1, 0);
                    if (b.getType().isAir()) b.setType(Material.FIRE);
                }
            }

            case "fireball" -> {
                Fireball fb = p.launchProjectile(Fireball.class);
                fb.setYield((float) Math.min(maxPower, num(o, "power", 1)));
                fb.setIsIncendiary(bool(o, "fire", true));
            }

            case "dash" -> {
                Vector v = p.getLocation().getDirection().normalize().multiply(num(o, "power", 1.5));
                v.setY(num(o, "up", 0.3));
                p.setVelocity(v);
            }

            case "shock" -> {
                double power = num(o, "power", 1.5);
                for (LivingEntity le : w.getNearbyLivingEntities(at, num(o, "radius", 5))) {
                    if (le.equals(p)) continue;
                    Vector d = le.getLocation().toVector().subtract(at.toVector());
                    if (d.lengthSquared() > 0.01) d.normalize();
                    else d = new Vector(0, 0, 0);
                    d.multiply(power);
                    d.setY(0.6);
                    le.setVelocity(d);
                }
                Particle pt = particle("explosion_emitter");
                if (pt != null) w.spawnParticle(pt, at, 1);
            }

            case "teleport" -> {
                Vector back = p.getEyeLocation().getDirection().multiply(-0.6);
                Block base = at.clone().add(back).getBlock();
                Location dest = null;
                for (int i = -2; i <= 4 && dest == null; i++) {
                    Block f = base.getRelative(0, i, 0);
                    if (f.isPassable() && f.getRelative(0, 1, 0).isPassable() && !f.getRelative(0, -1, 0).isPassable()) {
                        dest = f.getLocation().add(0.5, 0, 0.5);
                    }
                }
                if (dest == null) dest = at.clone().add(back);
                dest.setYaw(p.getLocation().getYaw());
                dest.setPitch(p.getLocation().getPitch());
                p.teleport(dest);
            }

            case "heal" -> p.setHealth(Math.min(maxHealth(p), p.getHealth() + num(o, "amount", 4)));

            case "potion" -> {
                PotionEffectType type = Registry.EFFECT.get(NamespacedKey.minecraft(str(o, "effect", "speed")));
                if (type == null) return;
                double sec = num(o, "sec", 10);
                int ticks = sec <= 0 ? PotionEffect.INFINITE_DURATION : (int) (sec * 20);
                int amp = (int) Math.max(0, num(o, "level", 1) - 1);
                if ("near".equals(str(o, "who", "self"))) {
                    for (LivingEntity le : w.getNearbyLivingEntities(at, num(o, "radius", 5))) {
                        if (!le.equals(p)) le.addPotionEffect(new PotionEffect(type, ticks, amp));
                    }
                } else {
                    p.addPotionEffect(new PotionEffect(type, ticks, amp));
                }
            }

            case "summon" -> {
                EntityType type = Registry.ENTITY_TYPE.get(NamespacedKey.minecraft(str(o, "mob", "zombie")));
                if (type == null || !type.isSpawnable()) return;
                int n = (int) Math.max(1, Math.min(10, num(o, "count", 1)));
                double h = num(o, "height", 0);
                double sc = num(o, "scale", 0);
                for (int i = 0; i < n; i++) {
                    ThreadLocalRandom rr = ThreadLocalRandom.current();
                    Location sp = h > 0 ? at.clone().add(rr.nextDouble(-4, 4), h, rr.nextDouble(-4, 4)) : at;
                    Entity ent = w.spawnEntity(sp, type);
                    if (sc > 0 && ent instanceof LivingEntity le && le.getAttribute(Attribute.SCALE) != null) {
                        le.getAttribute(Attribute.SCALE).setBaseValue(Math.min(sc, 16));
                    }
                }
            }

            case "area" -> {
                int rad = (int) Math.max(1, Math.min(maxRadius, num(o, "radius", 1)));
                Block c = at.getBlock();
                for (int dx = -rad; dx <= rad; dx++) {
                    for (int dy = -rad; dy <= rad; dy++) {
                        for (int dz = -rad; dz <= rad; dz++) {
                            Block b = c.getRelative(dx, dy, dz);
                            if (block != null && b.equals(block)) continue;
                            Material m = b.getType();
                            if (m.isAir() || b.isLiquid() || m.getHardness() < 0) continue;
                            b.breakNaturally(item);
                        }
                    }
                }
            }

            case "attribute" -> {
                String an = str(o, "attr", "movement_speed");
                Attribute attr = Registry.ATTRIBUTE.get(NamespacedKey.minecraft(an));
                if (attr == null) return;
                AttributeInstance inst = p.getAttribute(attr);
                if (inst == null) return;
                NamespacedKey mk = new NamespacedKey(this, "fx_" + an);
                AttributeModifier.Operation op = switch (str(o, "op", "add_value")) {
                    case "add_multiplied_base" -> AttributeModifier.Operation.ADD_SCALAR;
                    case "add_multiplied_total" -> AttributeModifier.Operation.MULTIPLY_SCALAR_1;
                    default -> AttributeModifier.Operation.ADD_NUMBER;
                };
                removeMod(inst, mk);
                inst.addModifier(new AttributeModifier(mk, num(o, "amount", 1), op));
                String gk = p.getUniqueId() + "|" + an;
                int gen = attrGen.merge(gk, 1, Integer::sum);
                long ticks = (long) Math.max(1, num(o, "sec", 10) * 20);
                Bukkit.getScheduler().runTaskLater(this, () -> {
                    if (attrGen.getOrDefault(gk, 0) != gen) return;
                    AttributeInstance i2 = p.getAttribute(attr);
                    if (i2 != null) removeMod(i2, mk);
                }, ticks);
            }

            case "damage" -> {
                double amt = num(o, "amount", 4);
                for (LivingEntity le : w.getNearbyLivingEntities(at, num(o, "radius", 3))) {
                    if (!le.equals(p)) le.damage(amt, p);
                }
            }

            case "lifesteal" -> {
                if (dmg > 0) p.setHealth(Math.min(maxHealth(p), p.getHealth() + dmg * num(o, "percent", 30) / 100.0));
            }

            case "knockup" -> {
                double pw = num(o, "power", 1);
                for (LivingEntity le : w.getNearbyLivingEntities(at, num(o, "radius", 3))) {
                    if (le.equals(p)) continue;
                    Vector v = le.getVelocity();
                    v.setY(pw);
                    le.setVelocity(v);
                }
            }

            case "pull" -> {
                double pw = num(o, "power", 1);
                for (LivingEntity le : w.getNearbyLivingEntities(at, num(o, "radius", 6))) {
                    if (le.equals(p)) continue;
                    Vector d = p.getLocation().toVector().subtract(le.getLocation().toVector());
                    if (d.lengthSquared() < 0.01) continue;
                    d.normalize();
                    d.multiply(pw);
                    d.setY(0.3);
                    le.setVelocity(d);
                }
            }

            case "freeze" -> {
                int ticks = (int) (num(o, "sec", 5) * 20);
                PotionEffectType slow = Registry.EFFECT.get(NamespacedKey.minecraft("slowness"));
                for (LivingEntity le : w.getNearbyLivingEntities(at, num(o, "radius", 3))) {
                    if (le.equals(p)) continue;
                    le.setFreezeTicks(ticks);
                    if (slow != null) le.addPotionEffect(new PotionEffect(slow, ticks, 2));
                }
            }

            case "xp" -> p.giveExp((int) num(o, "amount", 10));

            case "feed" -> {
                int a = (int) num(o, "amount", 6);
                p.setFoodLevel(Math.min(20, p.getFoodLevel() + a));
                p.setSaturation(Math.min(20f, p.getSaturation() + a));
            }

            case "repair" -> {
                if (item != null && item.getItemMeta() instanceof Damageable d && d.hasDamage()) {
                    d.setDamage(Math.max(0, d.getDamage() - (int) num(o, "amount", 5)));
                    item.setItemMeta(d);
                }
            }

            case "particle" -> {
                Particle pt = particle(str(o, "particle", "flame"));
                if (pt != null) w.spawnParticle(pt, at, (int) num(o, "count", 30), 0.5, 0.5, 0.5, 0.05);
                String s = str(o, "sound", "");
                if (!s.isEmpty()) w.playSound(at, s, 1f, 1f);
            }

            case "message" ->
                p.sendMessage(LegacyComponentSerializer.legacyAmpersand().deserialize(str(o, "text", "")));

            case "command" -> {
                if (!allowCommands) {
                    if (!warnedCommands) {
                        warnedCommands = true;
                        getLogger().warning("Une règle \"Commande\" a été ignorée : mets allow-commands: true dans config.yml pour l'autoriser.");
                    }
                    return;
                }
                String cmd = str(o, "cmd", "")
                        .replace("%player%", p.getName())
                        .replace("%x%", String.valueOf(at.getBlockX()))
                        .replace("%y%", String.valueOf(at.getBlockY()))
                        .replace("%z%", String.valueOf(at.getBlockZ()));
                if (cmd.startsWith("/")) cmd = cmd.substring(1);
                if (cmd.isBlank()) return;
                if ("player".equals(str(o, "who", "console"))) Bukkit.dispatchCommand(p, cmd);
                else Bukkit.dispatchCommand(Bukkit.getConsoleSender(), cmd);
            }

            case "drop_mult" -> pendingDrops.put(p.getUniqueId().toString(),
                    new long[]{(long) Math.max(1, Math.min(50, num(o, "mult", 2))), System.currentTimeMillis() + 500});

            case "swap" -> {
                LivingEntity best = null;
                double bd = Double.MAX_VALUE;
                for (LivingEntity le : w.getNearbyLivingEntities(at, num(o, "radius", 12))) {
                    if (le.equals(p)) continue;
                    double d = le.getLocation().distanceSquared(p.getLocation());
                    if (d < bd) { bd = d; best = le; }
                }
                if (best != null) {
                    Location a = p.getLocation();
                    Location b = best.getLocation();
                    p.teleport(b);
                    best.teleport(a);
                }
            }

            case "pig_taxi" -> {
                Entity pig = w.spawnEntity(at, EntityType.PIG);
                pig.addPassenger(p);
                Bukkit.getScheduler().runTaskLater(this, pig::remove, (long) (num(o, "sec", 20) * 20));
            }

            case "anvil_rain" -> {
                int n = (int) Math.max(1, Math.min(5, num(o, "count", 1)));
                for (LivingEntity le : w.getNearbyLivingEntities(at, num(o, "radius", 4))) {
                    if (le.equals(p)) continue;
                    for (int i = 0; i < n; i++) {
                        w.spawnFallingBlock(le.getLocation().add(0, 12, 0), Material.ANVIL.createBlockData());
                    }
                }
            }

            case "shuffle" -> {
                var inv = p.getInventory();
                List<ItemStack> hb = new ArrayList<>();
                for (int i = 0; i < 9; i++) hb.add(inv.getItem(i));
                java.util.Collections.shuffle(hb);
                for (int i = 0; i < 9; i++) inv.setItem(i, hb.get(i));
            }

            case "bounce" -> {
                Vector v = p.getVelocity();
                p.setVelocity(new Vector(v.getX(), num(o, "power", 1.2), v.getZ()));
            }

            case "broadcast" -> Bukkit.broadcastMessage(str(o, "text", "").replace('&', '§'));

            // effets "en continu" gérés ailleurs (rien à faire à chaque seconde)
            case "damage_boost", "damage_reduce", "nohunger", "fly" -> { }

            default -> getLogger().warning("Action inconnue : " + r.action());
        }
    }

    private static Particle particle(String name) {
        return Registry.PARTICLE_TYPE.get(NamespacedKey.minecraft(name));
    }

    // ------------------------------------------------------------ lecture des paramètres

    private static double num(JsonObject o, String k, double d) {
        try {
            return o.has(k) ? o.get(k).getAsDouble() : d;
        } catch (Exception e) {
            return d;
        }
    }

    private static boolean bool(JsonObject o, String k, boolean d) {
        try {
            return o.has(k) ? o.get(k).getAsBoolean() : d;
        } catch (Exception e) {
            return d;
        }
    }

    private static String str(JsonObject o, String k, String d) {
        try {
            return o.has(k) ? o.get(k).getAsString() : d;
        } catch (Exception e) {
            return d;
        }
    }
}
