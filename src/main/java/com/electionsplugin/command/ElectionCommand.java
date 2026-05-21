package com.electionsplugin.command;

import com.electionsplugin.ElectionsPlugin;
import com.electionsplugin.election.ElectionService;
import com.electionsplugin.role.RoleSyncService;
import com.electionsplugin.verification.VerificationService;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.Locale;

public final class ElectionCommand implements CommandExecutor, TabCompleter {
    private final ElectionsPlugin plugin;
    private final VerificationService verificationService;
    private final ElectionService electionService;
    private final RoleSyncService roleSyncService;

    public ElectionCommand(ElectionsPlugin plugin, VerificationService verificationService, ElectionService electionService, RoleSyncService roleSyncService) {
        this.plugin = plugin;
        this.verificationService = verificationService;
        this.electionService = electionService;
        this.roleSyncService = roleSyncService;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            sender.sendMessage("Usage: /election <verify|status|reload>");
            return true;
        }

        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "verify" -> verify(sender, args);
            case "status" -> status(sender);
            case "reload" -> reload(sender);
            default -> sender.sendMessage("Unknown election command. Use /election <verify|status|reload>.");
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            return List.of("verify", "status", "reload").stream()
                .filter(value -> value.startsWith(args[0].toLowerCase(Locale.ROOT)))
                .toList();
        }
        return List.of();
    }

    private void verify(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("Only players can verify a Minecraft account.");
            return;
        }
        if (args.length < 2) {
            sender.sendMessage("Usage: /election verify <code>");
            return;
        }
        verificationService.completeVerification(args[1], player.getUniqueId(), player.getName()).ifPresentOrElse(discordId -> {
            roleSyncService.syncVerifiedUser(discordId, player.getUniqueId());
            sender.sendMessage("Your Minecraft account has been linked to Discord.");
            plugin.getServer().getGlobalRegionScheduler().execute(plugin, () -> {
                try {
                    plugin.getLogger().info("Verified Discord user " + discordId + " as " + player.getName() + ".");
                } catch (Exception exception) {
                    plugin.getLogger().warning("Verification post-sync failed: " + exception.getMessage());
                }
            });
        }, () -> sender.sendMessage("That verification code is invalid or expired."));
    }

    private void status(CommandSender sender) {
        sender.sendMessage(electionService.statusLine());
    }

    private void reload(CommandSender sender) {
        if (!sender.hasPermission("electionsplugin.admin")) {
            sender.sendMessage("You do not have permission to reload ElectionsPlugin.");
            return;
        }
        plugin.reloadPluginConfig();
        sender.sendMessage("ElectionsPlugin config reloaded.");
    }
}
