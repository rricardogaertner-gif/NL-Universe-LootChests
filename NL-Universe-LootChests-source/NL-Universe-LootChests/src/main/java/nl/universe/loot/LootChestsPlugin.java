
package nl.universe.loot;

import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.block.Chest;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.EnchantmentStorageMeta;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.CompletableFuture;

public final class LootChestsPlugin extends JavaPlugin implements Listener {
    private final Random random = new Random();
    private final Map<String, Set<String>> active = new HashMap<>();
    private final Set<String> searching = new HashSet<>();
    private File dataFile;
    private int taskId = -1;

    @Override public void onEnable() {
        saveDefaultConfig();
        dataFile = new File(getDataFolder(), "chests.yml");
        loadLocations();
        getServer().getPluginManager().registerEvents(this, this);
        schedule();
        getLogger().info("LootChests v1.2 enabled in: " + getConfig().getStringList("worlds"));
    }

    @Override public void onDisable() {
        if (taskId != -1) Bukkit.getScheduler().cancelTask(taskId);
        searching.clear();
        saveLocations();
    }

    private void schedule() {
        if (taskId != -1) Bukkit.getScheduler().cancelTask(taskId);
        long ticks = Math.max(1, getConfig().getLong("spawn-interval-minutes", 30)) * 1200L;
        taskId = Bukkit.getScheduler().scheduleSyncRepeatingTask(this, () -> {
            for (String name : getConfig().getStringList("worlds")) {
                World world = Bukkit.getWorld(name);
                if (world != null) startSearch(world, null);
            }
        }, ticks, ticks);
    }

    private String key(Location l) {
        return l.getBlockX() + "," + l.getBlockY() + "," + l.getBlockZ();
    }

    private Location parse(World w, String key) {
        try {
            String[] a = key.split(",");
            return new Location(w, Integer.parseInt(a[0]), Integer.parseInt(a[1]), Integer.parseInt(a[2]));
        } catch (Exception e) {
            return null;
        }
    }

    private void reply(CommandSender sender, String message) {
        if (sender != null) sender.sendMessage(message);
        else getLogger().info(ChatColor.stripColor(message));
    }

    private void startSearch(World world, CommandSender sender) {
        String name = world.getName();
        Set<String> keys = active.computeIfAbsent(name, n -> new HashSet<>());

        if (keys.size() >= Math.max(1, getConfig().getInt("max-active-per-world", 5))) {
            reply(sender, ChatColor.YELLOW + "Limiet actieve lootkisten bereikt in " + name + ".");
            return;
        }

        if (searching.contains(name)) {
            reply(sender, ChatColor.YELLOW + "Er loopt al een zoekopdracht in " + name + ".");
            return;
        }

        List<Location> anchors = new ArrayList<>();

        if (!keys.isEmpty()) {
            for (String s : keys) {
                Location l = parse(world, s);
                if (l != null) anchors.add(l);
            }
        } else {
            for (Player p : world.getPlayers()) {
                anchors.add(p.getLocation().clone());
            }
        }

        if (anchors.isEmpty()) {
            reply(sender, ChatColor.YELLOW + "Geen speler aanwezig in " + name + " voor de eerste kist.");
            return;
        }

        searching.add(name);
        reply(sender, ChatColor.AQUA + "Veilig zoeken naar een lootkist in " + name + "... (asynchroon)");

        int attempts = Math.max(1, Math.min(40, getConfig().getInt("spawn-search-attempts", 20)));
        tryNext(world, sender, anchors, 0, attempts);
    }

    private void finish(World world, CommandSender sender, String message) {
        searching.remove(world.getName());
        reply(sender, message);
    }

    private void tryNext(World world, CommandSender sender, List<Location> anchors, int attempt, int maxAttempts) {
        if (!isEnabled()) return;

        if (attempt >= maxAttempts) {
            finish(world, sender, ChatColor.YELLOW + "Geen veilige plek gevonden na " + maxAttempts + " pogingen in " + world.getName() + ".");
            return;
        }

        int min = Math.max(1, getConfig().getInt("min-distance", 500));
        int max = Math.max(min, getConfig().getInt("max-distance", 900));

        Location anchor = anchors.get(random.nextInt(anchors.size()));
        double angle = random.nextDouble() * Math.PI * 2;
        double distance = min + random.nextDouble() * (max - min);

        int x = anchor.getBlockX() + (int) Math.round(Math.cos(angle) * distance);
        int z = anchor.getBlockZ() + (int) Math.round(Math.sin(angle) * distance);

        if (!world.getWorldBorder().isInside(new Location(world, x, world.getSeaLevel(), z))
                || !validDistance(world, x, z, min, max)) {
            later(world, sender, anchors, attempt + 1, maxAttempts);
            return;
        }

        CompletableFuture<Chunk> future;

        try {
            future = world.getChunkAtAsync(x >> 4, z >> 4, true);
        } catch (Exception ex) {
            getLogger().warning("Async chunk request failed: " + ex.getMessage());
            later(world, sender, anchors, attempt + 1, maxAttempts);
            return;
        }

        future.whenComplete((chunk, error) -> {
            if (!isEnabled()) return;

            Bukkit.getScheduler().runTask(this, () -> {
                if (!isEnabled() || !searching.contains(world.getName())) return;

                if (error != null || chunk == null || !world.isChunkLoaded(x >> 4, z >> 4)) {
                    later(world, sender, anchors, attempt + 1, maxAttempts);
                    return;
                }

                if (!validDistance(world, x, z, min, max)) {
                    later(world, sender, anchors, attempt + 1, maxAttempts);
                    return;
                }

                int y = world.getHighestBlockYAt(x, z);

                if (y <= world.getMinHeight() + 1 || y >= world.getMaxHeight() - 2) {
                    later(world, sender, anchors, attempt + 1, maxAttempts);
                    return;
                }

                Block floor = world.getBlockAt(x, y - 1, z);
                Block target = world.getBlockAt(x, y, z);
                Block above = world.getBlockAt(x, y + 1, z);

                if (!floor.getType().isSolid()
                        || floor.isLiquid()
                        || !target.getType().isAir()
                        || !above.getType().isAir()
                        || !Set.of(
                            Material.GRASS_BLOCK,
                            Material.DIRT,
                            Material.COARSE_DIRT,
                            Material.PODZOL,
                            Material.MYCELIUM,
                            Material.SAND,
                            Material.RED_SAND,
                            Material.STONE,
                            Material.SNOW_BLOCK,
                            Material.GRAVEL,
                            Material.MOSS_BLOCK
                        ).contains(floor.getType())) {
                    later(world, sender, anchors, attempt + 1, maxAttempts);
                    return;
                }

                target.setType(Material.CHEST, false);

                if (!(target.getState() instanceof Chest chest)) {
                    target.setType(Material.AIR, false);
                    later(world, sender, anchors, attempt + 1, maxAttempts);
                    return;
                }

                populate(chest);

                active.computeIfAbsent(world.getName(), n -> new HashSet<>())
                        .add(key(target.getLocation()));

                saveLocations();

                if (getConfig().getBoolean("announce-spawns", false)) {
                    Bukkit.broadcastMessage(ChatColor.GOLD
                            + "[NL-Universe] Een mysterieuze lootkist is verschenen in "
                            + world.getName() + "!");
                }

                finish(world, sender, ChatColor.GREEN
                        + "Lootkist geplaatst in " + world.getName()
                        + " op X:" + x + " Y:" + y + " Z:" + z);
            });
        });
    }

    private void later(World world, CommandSender sender, List<Location> anchors, int next, int limit) {
        Bukkit.getScheduler().runTaskLater(this, () -> {
            if (isEnabled() && searching.contains(world.getName())) {
                tryNext(world, sender, anchors, next, limit);
            }
        }, 2L);
    }

    private boolean validDistance(World world, int x, int z, int min, int max) {
        Set<String> keys = active.getOrDefault(world.getName(), Collections.emptySet());

        if (keys.isEmpty()) return true;

        boolean inRange = false;

        for (String s : keys) {
            Location l = parse(world, s);
            if (l == null) continue;

            double d = Math.hypot(x - l.getX(), z - l.getZ());

            if (d < min) return false;
            if (d <= max) inRange = true;
        }

        return inRange;
    }

    private void populate(Chest chest) {
        Material[] gear = {
            Material.DIAMOND_HELMET,
            Material.DIAMOND_CHESTPLATE,
            Material.DIAMOND_LEGGINGS,
            Material.DIAMOND_BOOTS,
            Material.DIAMOND_SWORD,
            Material.DIAMOND_PICKAXE,
            Material.DIAMOND_AXE
        };

        Enchantment[] enchants = {
            Enchantment.MENDING,
            Enchantment.UNBREAKING,
            Enchantment.PROTECTION,
            Enchantment.EFFICIENCY,
            Enchantment.SHARPNESS,
            Enchantment.FORTUNE,
            Enchantment.SILK_TOUCH
        };

        chest.getBlockInventory().clear();

        int count = 1 + random.nextInt(4);

        for (int i = 0; i < count; i++) {
            int roll = random.nextInt(100);
            ItemStack item;

            if (roll < 50) {
                item = new ItemStack(gear[random.nextInt(gear.length)]);

                if (random.nextDouble() < .30) {
                    item.addUnsafeEnchantment(Enchantment.UNBREAKING, 1);
                }
            } else if (roll < 70) {
                item = new ItemStack(Material.NETHERITE_SCRAP, 1 + random.nextInt(3));
            } else {
                item = new ItemStack(Material.ENCHANTED_BOOK);
                EnchantmentStorageMeta meta = (EnchantmentStorageMeta) item.getItemMeta();

                Enchantment e = enchants[random.nextInt(enchants.length)];

                meta.addStoredEnchant(
                        e,
                        1 + random.nextInt(Math.max(1, e.getMaxLevel())),
                        true
                );

                item.setItemMeta(meta);
            }

            int slot;

            do {
                slot = random.nextInt(chest.getBlockInventory().getSize());
            } while (chest.getBlockInventory().getItem(slot) != null);

            chest.getBlockInventory().setItem(slot, item);
        }

        chest.update();
    }

    @EventHandler
    public void onClose(InventoryCloseEvent event) {
        if (!(event.getInventory().getHolder() instanceof Chest chest)) return;

        Location loc = chest.getLocation();
        Set<String> keys = active.get(loc.getWorld().getName());

        if (keys == null || !keys.contains(key(loc))) return;

        Bukkit.getScheduler().runTask(this, () -> {
            if (!loc.getWorld().isChunkLoaded(loc.getBlockX() >> 4, loc.getBlockZ() >> 4)) return;

            if (!(loc.getBlock().getState() instanceof Chest current)) {
                keys.remove(key(loc));
                saveLocations();
                return;
            }

            if (current.getBlockInventory().isEmpty()) {
                loc.getBlock().setType(Material.AIR, false);
                keys.remove(key(loc));
                saveLocations();
            }
        });
    }

    private void loadLocations() {
        if (!dataFile.exists()) return;

        YamlConfiguration data = YamlConfiguration.loadConfiguration(dataFile);
        ConfigurationSection section = data.getConfigurationSection("worlds");

        if (section == null) return;

        for (String name : section.getKeys(false)) {
            active.put(name, new HashSet<>(data.getStringList("worlds." + name)));
        }
    }

    private void saveLocations() {
        YamlConfiguration data = new YamlConfiguration();

        for (var entry : active.entrySet()) {
            data.set("worlds." + entry.getKey(), new ArrayList<>(entry.getValue()));
        }

        try {
            data.save(dataFile);
        } catch (IOException e) {
            getLogger().severe("Could not save chests.yml: " + e.getMessage());
        }
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("nlloot.admin")) {
            sender.sendMessage(ChatColor.RED + "Geen toestemming.");
            return true;
        }

        if (args.length == 0 || args[0].equalsIgnoreCase("status")) {
            for (String world : getConfig().getStringList("worlds")) {
                sender.sendMessage(ChatColor.GOLD + world + ": "
                        + active.getOrDefault(world, Collections.emptySet()).size()
                        + " actieve lootkisten");
            }
            return true;
        }

        if (args[0].equalsIgnoreCase("reload")) {
            reloadConfig();
            schedule();
            sender.sendMessage(ChatColor.GREEN + "LootChests configuratie herladen.");
            return true;
        }

        if (args[0].equalsIgnoreCase("locations")) {
            for (String name : getConfig().getStringList("worlds")) {
                Set<String> keys = active.getOrDefault(name, Collections.emptySet());

                sender.sendMessage(ChatColor.GOLD + name + " (" + keys.size() + "): "
                        + (keys.isEmpty() ? "geen" : String.join(" | ", keys)));
            }
            return true;
        }

        if (args[0].equalsIgnoreCase("spawn")) {
            if (args.length < 2) {
                sender.sendMessage("Gebruik: /nlloot spawn <wereld>");
                return true;
            }

            if (!getConfig().getStringList("worlds").contains(args[1])) {
                sender.sendMessage(ChatColor.RED + "Wereld niet toegestaan.");
                return true;
            }

            World world = Bukkit.getWorld(args[1]);

            if (world == null) {
                sender.sendMessage(ChatColor.RED + "Wereld niet geladen.");
                return true;
            }

            startSearch(world, sender);
            return true;
        }

        sender.sendMessage("Gebruik: /nlloot <status|spawn wereld|locations|reload>");
        return true;
    }
}
