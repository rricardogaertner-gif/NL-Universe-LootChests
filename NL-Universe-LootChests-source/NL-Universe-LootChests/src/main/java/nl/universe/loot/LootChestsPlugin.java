package nl.universe.loot;

import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.block.Chest;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.EnchantmentStorageMeta;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.configuration.ConfigurationSection;

import java.io.File;
import java.io.IOException;
import java.util.*;

public final class LootChestsPlugin extends JavaPlugin implements Listener {
    private final Random random = new Random();
    private final Map<String, Set<String>> active = new HashMap<>();
    private File dataFile;
    private int taskId = -1;

    @Override public void onEnable() {
        saveDefaultConfig();
        dataFile = new File(getDataFolder(), "chests.yml");
        loadLocations();
        getServer().getPluginManager().registerEvents(this, this);
        schedule();
        getLogger().info("LootChests enabled. Worlds: " + getConfig().getStringList("worlds"));
    }
    @Override public void onDisable() {
        if (taskId != -1) Bukkit.getScheduler().cancelTask(taskId);
        saveLocations();
    }
    private void schedule() {
        if (taskId != -1) Bukkit.getScheduler().cancelTask(taskId);
        long ticks = Math.max(1, getConfig().getLong("spawn-interval-minutes", 30)) * 60L * 20L;
        taskId = Bukkit.getScheduler().scheduleSyncRepeatingTask(this, this::spawnForAll, ticks, ticks);
    }
    private void spawnForAll() {
        for (String worldName : getConfig().getStringList("worlds")) {
            World world = Bukkit.getWorld(worldName);
            if (world != null) spawnIn(world);
        }
    }
    private String key(Location l) { return l.getBlockX() + "," + l.getBlockY() + "," + l.getBlockZ(); }
    private Location parse(World world, String key) {
        try {
            String[] a = key.split(",");
            return new Location(world, Integer.parseInt(a[0]), Integer.parseInt(a[1]), Integer.parseInt(a[2]));
        } catch (Exception e) { return null; }
    }
    private void spawnIn(World world) {
        Set<String> keys = active.computeIfAbsent(world.getName(), n -> new HashSet<>());
        if (keys.size() >= Math.max(1, getConfig().getInt("max-active-per-world", 5))) return;
        int min = Math.max(1, getConfig().getInt("min-distance", 500));
        int max = Math.max(min, getConfig().getInt("max-distance", 900));
        List<Location> existing = new ArrayList<>();
        for (String s : keys) { Location loc = parse(world, s); if (loc != null) existing.add(loc); }
        List<Location> anchors = new ArrayList<>();
        if (!existing.isEmpty()) anchors.addAll(existing);
        else for (Player p : world.getPlayers()) anchors.add(p.getLocation());
        if (anchors.isEmpty()) return;
        int attempts = Math.max(1, Math.min(300, getConfig().getInt("spawn-search-attempts", 90)));
        for (int i = 0; i < attempts; i++) {
            Location anchor = anchors.get(random.nextInt(anchors.size()));
            double angle = random.nextDouble() * Math.PI * 2;
            double distance = min + random.nextDouble() * (max - min);
            int x = anchor.getBlockX() + (int)Math.round(Math.cos(angle) * distance);
            int z = anchor.getBlockZ() + (int)Math.round(Math.sin(angle) * distance);
            if (!world.getWorldBorder().isInside(new Location(world, x, world.getSeaLevel(), z))) continue;
            boolean validDistance = true;
            for (Location other : existing) {
                double d = Math.hypot(x - other.getX(), z - other.getZ());
                if (d < min) { validDistance = false; break; }
            }
            if (!validDistance) continue;
            int y = world.getHighestBlockYAt(x, z);
            if (y <= world.getMinHeight() + 1 || y >= world.getMaxHeight() - 2) continue;
            Block floor = world.getBlockAt(x, y - 1, z);
            Block target = world.getBlockAt(x, y, z);
            Block above = world.getBlockAt(x, y + 1, z);
            if (!floor.getType().isSolid() || floor.isLiquid() || !target.getType().isAir() || !above.getType().isAir()) continue;
            // Avoid placing on leaves, buildings, water, or player-placed-looking surfaces.
            if (!Set.of(Material.GRASS_BLOCK, Material.DIRT, Material.COARSE_DIRT, Material.PODZOL,
                    Material.MYCELIUM, Material.SAND, Material.RED_SAND, Material.STONE,
                    Material.SNOW_BLOCK, Material.GRAVEL, Material.MOSS_BLOCK).contains(floor.getType())) continue;
            target.setType(Material.CHEST, false);
            if (!(target.getState() instanceof Chest chest)) { target.setType(Material.AIR, false); continue; }
            populate(chest);
            keys.add(key(target.getLocation()));
            saveLocations();
            if (getConfig().getBoolean("announce-spawns", false))
                Bukkit.broadcastMessage(ChatColor.GOLD + "[NL-Universe] Er is een mysterieuze lootkist verschenen in " + world.getName() + "!");
            return;
        }
        getLogger().fine("No safe loot chest position found in " + world.getName());
    }
    private void populate(Chest chest) {
        List<ItemStack> rewards = new ArrayList<>();
        Material[] gear = {Material.DIAMOND_HELMET, Material.DIAMOND_CHESTPLATE, Material.DIAMOND_LEGGINGS,
                Material.DIAMOND_BOOTS, Material.DIAMOND_SWORD, Material.DIAMOND_PICKAXE, Material.DIAMOND_AXE};
        Enchantment[] enchants = {Enchantment.MENDING, Enchantment.UNBREAKING, Enchantment.PROTECTION,
                Enchantment.EFFICIENCY, Enchantment.SHARPNESS, Enchantment.FORTUNE, Enchantment.SILK_TOUCH};
        int count = 1 + random.nextInt(4);
        for (int i = 0; i < count; i++) {
            int roll = random.nextInt(100);
            ItemStack item;
            if (roll < 50) {
                item = new ItemStack(gear[random.nextInt(gear.length)]);
                if (random.nextDouble() < .30) {
                    Enchantment e = item.getType() == Material.DIAMOND_PICKAXE || item.getType() == Material.DIAMOND_AXE
                            ? Enchantment.UNBREAKING : Enchantment.MENDING;
                    item.addUnsafeEnchantment(e, 1);
                }
            } else if (roll < 70) {
                item = new ItemStack(Material.NETHERITE_SCRAP, 1 + random.nextInt(3));
            } else {
                item = new ItemStack(Material.ENCHANTED_BOOK);
                EnchantmentStorageMeta meta = (EnchantmentStorageMeta) item.getItemMeta();
                Enchantment e = enchants[random.nextInt(enchants.length)];
                int level = 1 + random.nextInt(Math.max(1, e.getMaxLevel()));
                meta.addStoredEnchant(e, level, true);
                item.setItemMeta(meta);
            }
            rewards.add(item);
        }
        chest.getBlockInventory().clear();
        for (ItemStack reward : rewards) {
            int slot;
            do { slot = random.nextInt(chest.getBlockInventory().getSize()); }
            while (chest.getBlockInventory().getItem(slot) != null);
            chest.getBlockInventory().setItem(slot, reward);
        }
        chest.update();
    }
    @EventHandler public void onClose(InventoryCloseEvent event) {
        if (!(event.getInventory().getHolder() instanceof Chest chest)) return;
        Location loc = chest.getLocation();
        Set<String> keys = active.get(loc.getWorld().getName());
        if (keys == null || !keys.contains(key(loc))) return;
        Bukkit.getScheduler().runTask(this, () -> {
            if (!(loc.getBlock().getState() instanceof Chest current)) {
                keys.remove(key(loc)); saveLocations(); return;
            }
            if (current.getBlockInventory().isEmpty()) {
                loc.getBlock().setType(Material.AIR, false);
                keys.remove(key(loc)); saveLocations();
            }
        });
    }
    private void loadLocations() {
        if (!dataFile.exists()) return;
        YamlConfiguration data = YamlConfiguration.loadConfiguration(dataFile);
        ConfigurationSection section = data.getConfigurationSection("worlds");
        if (section == null) return;
        for (String name : section.getKeys(false)) active.put(name, new HashSet<>(data.getStringList("worlds." + name)));
    }
    private void saveLocations() {
        YamlConfiguration data = new YamlConfiguration();
        for (var entry : active.entrySet()) data.set("worlds." + entry.getKey(), new ArrayList<>(entry.getValue()));
        try { data.save(dataFile); } catch (IOException e) { getLogger().severe("Could not save chests.yml: " + e.getMessage()); }
    }
    @Override public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("nlloot.admin")) { sender.sendMessage(ChatColor.RED + "Geen toestemming."); return true; }
        if (args.length == 0 || args[0].equalsIgnoreCase("status")) {
            for (String world : getConfig().getStringList("worlds"))
                sender.sendMessage(ChatColor.GOLD + world + ": " + active.getOrDefault(world, Collections.emptySet()).size() + " actieve lootkisten");
            return true;
        }
        if (args[0].equalsIgnoreCase("reload")) {
            reloadConfig(); schedule(); sender.sendMessage(ChatColor.GREEN + "LootChests config herladen."); return true;
        }
        if (args[0].equalsIgnoreCase("spawn")) {
            if (args.length < 2) { sender.sendMessage("Gebruik: /nlloot spawn <wereld>"); return true; }
            if (!getConfig().getStringList("worlds").contains(args[1])) { sender.sendMessage(ChatColor.RED + "Wereld niet toegestaan."); return true; }
            World world = Bukkit.getWorld(args[1]);
            if (world == null) { sender.sendMessage(ChatColor.RED + "Wereld niet geladen."); return true; }
            int before = active.getOrDefault(world.getName(), Collections.emptySet()).size();
            spawnIn(world);
            int after = active.getOrDefault(world.getName(), Collections.emptySet()).size();
            sender.sendMessage(after > before ? ChatColor.GREEN + "Lootkist geplaatst." : ChatColor.YELLOW + "Geen plek gevonden, geen speler aanwezig of limiet bereikt.");
            return true;
        }
        sender.sendMessage("Gebruik: /nlloot <status|spawn wereld|reload>"); return true;
    }
}
