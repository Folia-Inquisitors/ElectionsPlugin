package com.electionsplugin.luckperms;

import com.electionsplugin.config.PluginConfig;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;

import java.lang.reflect.Method;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.logging.Logger;

public final class LuckPermsService {
    private final Logger logger;
    private final Object luckPerms;
    private final Class<?> nodeClass;
    private final Class<?> inheritanceNodeClass;

    public LuckPermsService(JavaPlugin plugin, PluginConfig config) {
        this.logger = plugin.getLogger();
        Lookup lookup = lookupLuckPerms();
        this.luckPerms = lookup.luckPerms();
        this.nodeClass = lookup.nodeClass();
        this.inheritanceNodeClass = lookup.inheritanceNodeClass();

        if (luckPerms == null) {
            logger.warning("LuckPerms was not found. Discord roles will still work, but LuckPerms groups will not sync.");
        } else {
            logger.info("LuckPerms integration enabled.");
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
        return loadUser(uuid).thenCompose(user -> {
            try {
                Object data = user.getClass().getMethod("data").invoke(user);
                data.getClass().getMethod("add", nodeClass).invoke(data, inheritanceNode(groupName));
                return saveUser(user);
            } catch (Exception exception) {
                throw new CompletionException(exception);
            }
        });
    }

    public CompletableFuture<Void> removeGroup(UUID uuid, String groupName) {
        if (luckPerms == null || groupName == null || groupName.isBlank()) {
            return CompletableFuture.completedFuture(null);
        }
        return loadUser(uuid).thenCompose(user -> {
            try {
                Object data = user.getClass().getMethod("data").invoke(user);
                data.getClass().getMethod("remove", nodeClass).invoke(data, inheritanceNode(groupName));
                return saveUser(user);
            } catch (Exception exception) {
                throw new CompletionException(exception);
            }
        });
    }

    private CompletableFuture<Object> loadUser(UUID uuid) {
        try {
            Object userManager = luckPerms.getClass().getMethod("getUserManager").invoke(luckPerms);
            @SuppressWarnings("unchecked")
            CompletableFuture<Object> future = (CompletableFuture<Object>) userManager.getClass()
                .getMethod("loadUser", UUID.class)
                .invoke(userManager, uuid);
            return future;
        } catch (Exception exception) {
            CompletableFuture<Object> failed = new CompletableFuture<>();
            failed.completeExceptionally(exception);
            return failed;
        }
    }

    private CompletableFuture<Void> saveUser(Object user) {
        try {
            Object userManager = luckPerms.getClass().getMethod("getUserManager").invoke(luckPerms);
            @SuppressWarnings("unchecked")
            CompletableFuture<Void> future = (CompletableFuture<Void>) userManager.getClass()
                .getMethod("saveUser", user.getClass().getInterfaces()[0])
                .invoke(userManager, user);
            return future;
        } catch (NoSuchMethodException exception) {
            return saveUserFallback(user);
        } catch (Exception exception) {
            CompletableFuture<Void> failed = new CompletableFuture<>();
            failed.completeExceptionally(exception);
            return failed;
        }
    }

    private CompletableFuture<Void> saveUserFallback(Object user) {
        try {
            Object userManager = luckPerms.getClass().getMethod("getUserManager").invoke(luckPerms);
            Method saveUser = null;
            for (Method method : userManager.getClass().getMethods()) {
                if ("saveUser".equals(method.getName()) && method.getParameterCount() == 1) {
                    saveUser = method;
                    break;
                }
            }
            if (saveUser == null) {
                throw new NoSuchMethodException("saveUser");
            }
            @SuppressWarnings("unchecked")
            CompletableFuture<Void> future = (CompletableFuture<Void>) saveUser.invoke(userManager, user);
            return future;
        } catch (Exception exception) {
            CompletableFuture<Void> failed = new CompletableFuture<>();
            failed.completeExceptionally(exception);
            return failed;
        }
    }

    private Object inheritanceNode(String groupName) throws Exception {
        Object builder = inheritanceNodeClass.getMethod("builder", String.class).invoke(null, groupName);
        return builder.getClass().getMethod("build").invoke(builder);
    }

    private void warnIfMissing(String groupName) {
        if (luckPerms == null || groupName == null || groupName.isBlank()) {
            return;
        }
        try {
            Object groupManager = luckPerms.getClass().getMethod("getGroupManager").invoke(luckPerms);
            Object group = groupManager.getClass().getMethod("getGroup", String.class).invoke(groupManager, groupName);
            if (group == null) {
                logger.warning("LuckPerms group '" + groupName + "' does not exist. Create it in LuckPerms or change the ElectionsPlugin config.");
            }
        } catch (Exception exception) {
            logger.warning("Could not check LuckPerms group '" + groupName + "': " + exception.getMessage());
        }
    }

    private Lookup lookupLuckPerms() {
        Plugin luckPermsPlugin = Bukkit.getPluginManager().getPlugin("LuckPerms");
        if (luckPermsPlugin == null || !luckPermsPlugin.isEnabled()) {
            return Lookup.empty();
        }
        try {
            ClassLoader loader = luckPermsPlugin.getClass().getClassLoader();
            Class<?> luckPermsClass = loader.loadClass("net.luckperms.api.LuckPerms");
            Class<?> nodeClass = loader.loadClass("net.luckperms.api.node.Node");
            Class<?> inheritanceNodeClass = loader.loadClass("net.luckperms.api.node.types.InheritanceNode");
            RegisteredServiceProvider<?> provider = Bukkit.getServicesManager().getRegistration(luckPermsClass);
            if (provider == null) {
                return Lookup.empty();
            }
            return new Lookup(provider.getProvider(), nodeClass, inheritanceNodeClass);
        } catch (Exception exception) {
            logger.warning("Could not initialize LuckPerms integration: " + exception.getMessage());
            return Lookup.empty();
        }
    }

    private record Lookup(Object luckPerms, Class<?> nodeClass, Class<?> inheritanceNodeClass) {
        private static Lookup empty() {
            return new Lookup(null, null, null);
        }
    }
}
