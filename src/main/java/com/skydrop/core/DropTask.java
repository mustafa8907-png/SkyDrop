package com.skydrop.core;

import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.block.Chest;
import org.bukkit.entity.FallingBlock;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class DropTask implements Listener {

    private final JavaPlugin plugin;
    private final ChestManager chestManager;
    private final NamespacedKey dropKey;

    private final Set<Location> activeDrops = ConcurrentHashMap.newKeySet();
    private final HashMap<Location, BukkitTask> expirationTasks = new HashMap<>();
    private final Map<Location, UUID> activeViewers = new HashMap<>();
    private BukkitTask autoDropTask;

    public DropTask(JavaPlugin plugin, ChestManager chestManager) {
        this.plugin = plugin;
        this.chestManager = chestManager;
        this.dropKey = new NamespacedKey(plugin, "supply_drop");
    }

    public void reload() {
        if (autoDropTask != null) {
            autoDropTask.cancel();
        }
        long intervalMinutes = plugin.getConfig().getLong("drop.interval-minutes", 60L);
        startAutoDrops(intervalMinutes);
    }

    public void startAutoDrops(long intervalMinutes) {
        long intervalTicks = Math.max(20L, intervalMinutes * 60L * 20L);
        autoDropTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> triggerRandomDrop(false), intervalTicks, intervalTicks);
    }

    public void triggerRandomDrop(boolean manual) {
        ChestManager.LootChest selectedChest = chestManager.getRandomWeightedChest();
        if (selectedChest == null) {
            plugin.getLogger().warning("No valid loot chest found for drop.");
            return;
        }

        World world = Bukkit.getWorld(plugin.getConfig().getString("drop.world", "world"));
        if (world == null) {
            plugin.getLogger().warning("Configured world not found.");
            return;
        }

        Location target = randomLocationWithinBorder(world);
        if (target == null) {
            plugin.getLogger().warning("Could not find drop location.");
            return;
        }

        int spawnHeight = world.getHighestBlockYAt(target) + plugin.getConfig().getInt("drop.spawn-height-offset", 20);
        Location spawn = new Location(world, target.getX() + 0.5, spawnHeight, target.getZ() + 0.5);
        spawnFallingDrop(spawn, target, selectedChest, manual);
    }

    private void spawnFallingDrop(Location spawn, Location groundTarget, ChestManager.LootChest chest, boolean manual) {
        World world = spawn.getWorld();
        if (world == null) {
            return;
        }

        FallingBlock falling = world.spawnFallingBlock(spawn, Material.CHEST.createBlockData());
        falling.setDropItem(false);
        falling.setHurtEntities(false);
        falling.setGravity(false);
        falling.setVelocity(new Vector(0, 0, 0));

        String msg = plugin.getConfig().getString("messages.drop-start",
                "&6&l[SkyDrop] &7An orbital supply crate is dropping at &e%x%, %z%&7!");
        announce(format(msg, groundTarget));

        new BukkitRunnable() {
            @Override
            public void run() {
                if (!falling.isValid()) {
                    cancel();
                    return;
                }

                Location current = falling.getLocation();
                world.spawnParticle(Particle.FIREWORKS_SPARK, current, 12, 0.2, 0.2, 0.2, 0.01);
                world.spawnParticle(Particle.SMOKE_NORMAL, current, 6, 0.15, 0.15, 0.15, 0.01);

                if (current.getY() <= groundTarget.getY() + 1.0) {
                    falling.remove();
                    placeSupplyChest(groundTarget, chest);
                    onDropLanded(groundTarget);
                    cancel();
                    return;
                }

                falling.teleport(current.subtract(0, 1, 0));
            }
        }.runTaskTimer(plugin, 0L, 10L);
    }

    private void placeSupplyChest(Location location, ChestManager.LootChest chestLoot) {
        Block block = location.getBlock();
        block.setType(Material.CHEST, false);

        if (block.getState() instanceof Chest chestState) {
            chestState.getPersistentDataContainer().set(dropKey, PersistentDataType.STRING, chestLoot.name());

            Inventory inventory = chestState.getBlockInventory();
            for (ItemStack item : chestLoot.items()) {
                inventory.addItem(item.clone());
            }
            chestState.update(true, false);
        }

        Location keyLocation = location.toBlockLocation();
        activeDrops.add(keyLocation);
        long expiryTicks = plugin.getConfig().getLong("drop.expire-minutes", 30L) * 60L * 20L;
        BukkitTask expiryTask = Bukkit.getScheduler().runTaskLater(plugin, () -> expireDrop(keyLocation), expiryTicks);
        expirationTasks.put(keyLocation, expiryTask);
    }

    private void onDropLanded(Location location) {
        World world = location.getWorld();
        if (world == null) {
            return;
        }

        world.playSound(location, Sound.ENTITY_FIREWORK_ROCKET_LARGE_BLAST, 1f, 1f);
        for (Player player : world.getPlayers()) {
            if (player.getLocation().distanceSquared(location) <= 250 * 250) {
                player.playSound(location, Sound.BLOCK_CHEST_OPEN, 1f, 0.9f);
            }
        }

        String msg = plugin.getConfig().getString("messages.drop-landed",
                "&a&l[SkyDrop] &7Supply crate has landed at &e%x%, %z%&7.");
        announce(format(msg, location));
    }

    private void expireDrop(Location location) {
        Block block = location.getBlock();
        if (block.getType() == Material.CHEST) {
            block.getWorld().spawnParticle(Particle.BLOCK_CRACK, location.clone().add(0.5, 0.5, 0.5), 32,
                    Material.GLASS.createBlockData());
            block.setType(Material.AIR, false);
        }

        activeDrops.remove(location);
        expirationTasks.remove(location);
        activeViewers.remove(location);

        String msg = plugin.getConfig().getString("messages.drop-expired", "&c[SkyDrop] A supply crate expired.");
        announce(format(msg, location));
    }

    public void stopAllDrops() {
        if (autoDropTask != null) {
            autoDropTask.cancel();
        }

        for (Location location : new HashSet<>(activeDrops)) {
            Block block = location.getBlock();
            if (block.getType() == Material.CHEST) {
                block.setType(Material.AIR, false);
            }
        }
        activeDrops.clear();

        for (BukkitTask task : expirationTasks.values()) {
            task.cancel();
        }
        expirationTasks.clear();
        activeViewers.clear();
    }

    @EventHandler
    public void onSupplyInteract(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK || event.getClickedBlock() == null) {
            return;
        }

        Block block = event.getClickedBlock();
        if (!(block.getState() instanceof Chest chestState)) {
            return;
        }

        String chestName = chestState.getPersistentDataContainer().get(dropKey, PersistentDataType.STRING);
        if (chestName == null) {
            return;
        }

        Location keyLocation = block.getLocation().toBlockLocation();
        BukkitTask expiryTask = expirationTasks.remove(keyLocation);
        if (expiryTask != null) {
            expiryTask.cancel();
        }
        activeDrops.remove(keyLocation);

        String msg = plugin.getConfig().getString("messages.drop-opened",
                "&b[SkyDrop] %player% opened a supply crate at %x%, %z%.");
        msg = msg.replace("%player%", event.getPlayer().getName());
        announce(format(msg, keyLocation));
    }

    @EventHandler
    public void onSupplyOpen(InventoryOpenEvent event) {
        if (!(event.getPlayer() instanceof Player player)) {
            return;
        }

        InventoryHolder holder = event.getInventory().getHolder();
        if (!(holder instanceof Chest chest)) {
            return;
        }

        String tag = chest.getPersistentDataContainer().get(dropKey, PersistentDataType.STRING);
        if (tag == null) {
            return;
        }

        if (!plugin.getConfig().getBoolean("drop.anti-steal", false)) {
            return;
        }

        Location keyLocation = chest.getLocation().toBlockLocation();
        UUID currentViewer = activeViewers.get(keyLocation);
        if (currentViewer != null && !currentViewer.equals(player.getUniqueId())) {
            event.setCancelled(true);
            player.sendMessage(ChatColor.RED + "This supply crate is currently being looted by another player.");
            return;
        }
        activeViewers.put(keyLocation, player.getUniqueId());
    }

    @EventHandler
    public void onSupplyClose(InventoryCloseEvent event) {
        InventoryHolder holder = event.getInventory().getHolder();
        if (!(holder instanceof Chest chest)) {
            return;
        }

        String tag = chest.getPersistentDataContainer().get(dropKey, PersistentDataType.STRING);
        if (tag == null) {
            return;
        }

        Location keyLocation = chest.getLocation().toBlockLocation();
        UUID currentViewer = activeViewers.get(keyLocation);
        if (currentViewer != null && currentViewer.equals(event.getPlayer().getUniqueId())) {
            activeViewers.remove(keyLocation);
        }
    }

    private Location randomLocationWithinBorder(World world) {
        WorldBorder border = world.getWorldBorder();
        Location center = border.getCenter();
        double radius = border.getSize() / 2.0;

        Random random = new Random();
        for (int attempt = 0; attempt < 20; attempt++) {
            double x = center.getX() + (random.nextDouble() * 2 - 1) * radius;
            double z = center.getZ() + (random.nextDouble() * 2 - 1) * radius;
            int y = world.getHighestBlockYAt((int) x, (int) z);
            Location location = new Location(world, x, y, z);
            if (border.isInside(location)) {
                return location;
            }
        }
        return null;
    }

    private void announce(String message) {
        Bukkit.broadcastMessage(colorize(message));
    }

    private String format(String message, Location location) {
        long seconds = plugin.getConfig().getLong("drop.expire-minutes", 30L) * 60L;
        return message
                .replace("%x%", String.valueOf(location.getBlockX()))
                .replace("%y%", String.valueOf(location.getBlockY()))
                .replace("%z%", String.valueOf(location.getBlockZ()))
                .replace("%world%", Objects.requireNonNull(location.getWorld()).getName())
                .replace("%time_left%", String.valueOf(seconds));
    }

    private String colorize(String input) {
        return ChatColor.translateAlternateColorCodes('&', input);
    }
}
