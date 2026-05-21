package com.electionsplugin.luckperms;

import com.electionsplugin.config.PluginConfig;
import net.luckperms.api.LuckPerms;
import net.luckperms.api.model.group.Group;
import net.luckperms.api.model.user.User;
import net.luckperms.api.node.types.InheritanceNode;
import org.bukkit.Bukkit;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Logger;

public final class LuckPermsService {
    private final Logger logger;
    private final LuckPerms luckPerms;

    public LuckPermsService(JavaPlugin plugin, PluginConfig config) {
        this.logger = plugin.getLogger();
        RegisteredServiceProvider<LuckPerms> provider = Bukkit.getServicesManager().getRegistration(LuckPerms.class);
        this.luckPerms = provider == null ? null : provider.getProvider();
        if (luckPerms == null) {
            logger.warning("LuckPerms was not found. Discord roles will still work, but LuckPerms groups will not sync.");
        } else {
            warnIfMissing(config.presidentLuckPermsGroup());
            warnIfMissing(config.cabinetLuckPermsGroup(1));
            warnIfMissing(config.cabinetLuckPermsGroup(2));
            warnIfMissing(config.cabinetLuckPermsGroup(3));
        }
    }

    public boolean available() {
        return luckPerms != null;
    }

    public CompletableFuture<Void> addGroup(UUID uuid, String groupName) {
        if (luckPerms == null || groupName == null || groupName.isBlank()) {
            return CompletableFuture.completedFuture(null);
        }
        warnIfMissing(groupName);
        return luckPerms.getUserManager().loadUser(uuid).thenCompose(user -> {
            user.data().add(InheritanceNode.builder(groupName).build());
            return luckPerms.getUserManager().saveUser(user);
        });
    }

    public CompletableFuture<Void> removeGroup(UUID uuid, String groupName) {
        if (luckPerms == null || groupName == null || groupName.isBlank()) {
            return CompletableFuture.completedFuture(null);
        }
        return luckPerms.getUserManager().loadUser(uuid).thenCompose(user -> {
            removeGroupNode(user, groupName);
            return luckPerms.getUserManager().saveUser(user);
        });
    }

    private void removeGroupNode(User user, String groupName) {
        user.data().remove(InheritanceNode.builder(groupName).build());
    }

    private void warnIfMissing(String groupName) {
        if (luckPerms == null || groupName == null || groupName.isBlank()) {
            return;
        }
        Group group = luckPerms.getGroupManager().getGroup(groupName);
        if (group == null) {
            logger.warning("LuckPerms group '" + groupName + "' does not exist. Create it in LuckPerms or change the ElectionsPlugin config.");
        }
    }
}
