package com.skydrop.core;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.io.BukkitObjectInputStream;
import org.bukkit.util.io.BukkitObjectOutputStream;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class ChestManager implements Listener {

    private static final int INVENTORY_SIZE = 54;
    private static final int WEIGHT_BUTTON_SLOT = 53;

    private final JavaPlugin plugin;
    private final File chestFile;
    private YamlConfiguration chestConfig;

    private final Map<String, LootChest> chests = new ConcurrentHashMap<>();
    private final Map<UUID, String> editSessions = new HashMap<>();

    public ChestManager(JavaPlugin plugin) {
        this.plugin = plugin;
        this.chestFile = new File(plugin.getDataFolder(), "chests.yml");
        reload();
    }

    public void reload() {
        if (!chestFile.exists()) {
            try {
                chestFile.getParentFile().mkdirs();
                chestFile.createNewFile();
            } catch (IOException e) {
                plugin.getLogger().warning("Could not create chests.yml: " + e.getMessage());
            }
        }

        this.chestConfig = YamlConfiguration.loadConfiguration(chestFile);
        this.chests.clear();

        ConfigurationSection section = chestConfig.getConfigurationSection("chests");
        if (section == null) {
            return;
        }

        for (String chestName : section.getKeys(false)) {
            String path = "chests." + chestName;
            int weight = chestConfig.getInt(path + ".weight", 1);
            List<String> serializedItems = chestConfig.getStringList(path + ".items");

            List<ItemStack> items = new ArrayList<>();
            for (String base64 : serializedItems) {
                ItemStack item = deserializeItemStack(base64);
                if (item != null && item.getType() != Material.AIR) {
                    items.add(item);
                }
            }
            chests.put(chestName.toLowerCase(Locale.ROOT), new LootChest(chestName, weight, items));
        }
    }

    public void save() {
        chestConfig.set("chests", null);

        for (LootChest chest : chests.values()) {
            String path = "chests." + chest.name();
            chestConfig.set(path + ".weight", chest.weight());

            List<String> serializedItems = new ArrayList<>();
            for (ItemStack item : chest.items()) {
                if (item == null || item.getType() == Material.AIR) {
                    continue;
                }
                serializedItems.add(serializeItemStack(item));
            }
            chestConfig.set(path + ".items", serializedItems);
        }

        try {
            chestConfig.save(chestFile);
        } catch (IOException e) {
            plugin.getLogger().warning("Failed to save chests.yml: " + e.getMessage());
        }
    }

    public void openEditor(Player player, String chestName) {
        LootChest chest = chests.computeIfAbsent(chestName.toLowerCase(Locale.ROOT),
                key -> new LootChest(chestName, 1, new ArrayList<>()));

        Inventory editor = Bukkit.createInventory(player, INVENTORY_SIZE,
                ChatColor.DARK_AQUA + "SkyDrop Edit: " + chest.name());

        for (int i = 0; i < INVENTORY_SIZE - 1; i++) {
            if (i < chest.items().size()) {
                editor.setItem(i, chest.items().get(i).clone());
            }
        }

        editor.setItem(WEIGHT_BUTTON_SLOT, createWeightButton(chest.weight()));
        editSessions.put(player.getUniqueId(), chest.name().toLowerCase(Locale.ROOT));
        player.openInventory(editor);
    }

    public LootChest getRandomWeightedChest() {
        List<LootChest> values = chests.values().stream()
                .filter(ch -> !ch.items().isEmpty() && ch.weight() > 0)
                .toList();

        if (values.isEmpty()) {
            return null;
        }

        int totalWeight = values.stream().mapToInt(LootChest::weight).sum();
        int roll = new Random().nextInt(totalWeight) + 1;

        int cumulative = 0;
        for (LootChest chest : values) {
            cumulative += chest.weight();
            if (roll <= cumulative) {
                return chest;
            }
        }
        return values.get(0);
    }

    @EventHandler
    public void onEditorClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }

        String chestKey = editSessions.get(player.getUniqueId());
        if (chestKey == null || event.getView().getTitle() == null ||
                !event.getView().getTitle().startsWith(ChatColor.DARK_AQUA + "SkyDrop Edit:")) {
            return;
        }

        if (event.getRawSlot() == WEIGHT_BUTTON_SLOT) {
            event.setCancelled(true);
            LootChest chest = chests.get(chestKey);
            int current = chest == null ? 1 : chest.weight();
            int next = event.isRightClick() ? Math.max(1, current - 1) : current + 1;

            if (chest != null) {
                chests.put(chestKey, new LootChest(chest.name(), next, chest.items()));
            }
            event.getInventory().setItem(WEIGHT_BUTTON_SLOT, createWeightButton(next));
            player.sendMessage("§b[SkyDrop] §7Weight set to §e" + next);
        }

        if (event.getRawSlot() > WEIGHT_BUTTON_SLOT) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onEditorClose(InventoryCloseEvent event) {
        if (!(event.getPlayer() instanceof Player player)) {
            return;
        }

        String chestKey = editSessions.remove(player.getUniqueId());
        if (chestKey == null || event.getView().getTitle() == null ||
                !event.getView().getTitle().startsWith(ChatColor.DARK_AQUA + "SkyDrop Edit:")) {
            return;
        }

        LootChest chest = chests.get(chestKey);
        if (chest == null) {
            return;
        }

        List<ItemStack> items = new ArrayList<>();
        Inventory inventory = event.getInventory();
        for (int i = 0; i < INVENTORY_SIZE - 1; i++) {
            ItemStack item = inventory.getItem(i);
            if (item != null && item.getType() != Material.AIR) {
                items.add(item.clone());
            }
        }

        chests.put(chestKey, new LootChest(chest.name(), chest.weight(), items));
        save();
        player.sendMessage("§a[SkyDrop] Chest '" + chest.name() + "' saved with " + items.size() + " items.");
    }

    private ItemStack createWeightButton(int weight) {
        ItemStack item = new ItemStack(Material.NETHER_STAR);
        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return item;
        }

        meta.setDisplayName(ChatColor.GOLD + "Spawn Weight");
        meta.setLore(Arrays.asList(
                ChatColor.GRAY + "Current: " + ChatColor.YELLOW + weight,
                ChatColor.GRAY + "Left click: +1",
                ChatColor.GRAY + "Right click: -1"
        ));
        meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES);
        item.setItemMeta(meta);
        return item;
    }

    public static String serializeItemStack(ItemStack itemStack) {
        try (ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
             BukkitObjectOutputStream dataOutput = new BukkitObjectOutputStream(outputStream)) {
            dataOutput.writeObject(itemStack);
            return Base64.getEncoder().encodeToString(outputStream.toByteArray());
        } catch (IOException e) {
            throw new IllegalStateException("Could not serialize ItemStack", e);
        }
    }

    public static ItemStack deserializeItemStack(String data) {
        try (ByteArrayInputStream inputStream = new ByteArrayInputStream(Base64.getDecoder().decode(data));
             BukkitObjectInputStream dataInput = new BukkitObjectInputStream(inputStream)) {
            Object object = dataInput.readObject();
            if (object instanceof ItemStack itemStack) {
                return itemStack;
            }
            return null;
        } catch (IOException | ClassNotFoundException e) {
            return null;
        }
    }

    public record LootChest(String name, int weight, List<ItemStack> items) {
    }
}
