package com.skydrop.core;

import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

public class SkyDropPlugin extends JavaPlugin {

    private ChestManager chestManager;
    private DropTask dropTask;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        this.chestManager = new ChestManager(this);
        this.dropTask = new DropTask(this, chestManager);

        getServer().getPluginManager().registerEvents(chestManager, this);
        getServer().getPluginManager().registerEvents(dropTask, this);

        long intervalMinutes = getConfig().getLong("drop.interval-minutes", 60L);
        dropTask.startAutoDrops(intervalMinutes);
    }

    @Override
    public void onDisable() {
        if (dropTask != null) {
            dropTask.stopAllDrops();
        }
        if (chestManager != null) {
            chestManager.save();
        }
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!command.getName().equalsIgnoreCase("sd")) {
            return false;
        }

        if (args.length == 0) {
            sender.sendMessage("§e/skydrop edit <chest_name>|start|stop|reload");
            return true;
        }

        switch (args[0].toLowerCase()) {
            case "edit":
                if (!(sender instanceof Player player)) {
                    sender.sendMessage("Only players can use /sd edit");
                    return true;
                }
                if (args.length < 2) {
                    sender.sendMessage("§cUsage: /sd edit <chest_name>");
                    return true;
                }
                chestManager.openEditor(player, args[1]);
                return true;
            case "start":
                dropTask.triggerRandomDrop(true);
                sender.sendMessage("§aManual drop started.");
                return true;
            case "stop":
                dropTask.stopAllDrops();
                sender.sendMessage("§cAll active drops removed.");
                return true;
            case "reload":
                reloadConfig();
                chestManager.reload();
                dropTask.reload();
                sender.sendMessage("§aSkyDrop reloaded.");
                return true;
            default:
                sender.sendMessage("§e/skydrop edit <chest_name>|start|stop|reload");
                return true;
        }
    }
}
