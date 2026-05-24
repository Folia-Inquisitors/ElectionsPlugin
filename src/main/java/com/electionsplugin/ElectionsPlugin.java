package com.electionsplugin;

import com.electionsplugin.cabinet.CabinetService;
import com.electionsplugin.command.ElectionCommand;
import com.electionsplugin.config.PluginConfig;
import com.electionsplugin.database.Database;
import com.electionsplugin.discord.DiscordBotService;
import com.electionsplugin.election.ElectionService;
import com.electionsplugin.impeachment.ImpeachmentService;
import com.electionsplugin.luckperms.LuckPermsService;
import com.electionsplugin.policy.PolicyService;
import com.electionsplugin.role.RoleSyncService;
import com.electionsplugin.verification.VerificationService;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

import java.nio.file.Path;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public final class ElectionsPlugin extends JavaPlugin {
    private ScheduledExecutorService worker;
    private PluginConfig pluginConfig;
    private Database database;
    private LuckPermsService luckPermsService;
    private VerificationService verificationService;
    private RoleSyncService roleSyncService;
    private ElectionService electionService;
    private ImpeachmentService impeachmentService;
    private CabinetService cabinetService;
    private PolicyService policyService;
    private DiscordBotService discordBotService;

    @Override
    public void onEnable() {
        ensureConfigDefaults();
        this.pluginConfig = new PluginConfig(this);
        this.worker = Executors.newScheduledThreadPool(4, runnable -> {
            Thread thread = new Thread(runnable, "ElectionsPlugin-worker");
            thread.setDaemon(true);
            return thread;
        });

        try {
            this.database = new Database(getDataFolder().toPath().resolve("elections.db"), getLogger());
            this.database.init();
            this.luckPermsService = new LuckPermsService(this, pluginConfig);
            this.verificationService = new VerificationService(database, pluginConfig);
            this.roleSyncService = new RoleSyncService(database, luckPermsService, pluginConfig, getLogger());
            this.cabinetService = new CabinetService(database, pluginConfig, roleSyncService);
            this.policyService = new PolicyService(this, database, pluginConfig, roleSyncService, getLogger());
            this.policyService.applyPendingChangesOnStartup();
            this.electionService = new ElectionService(database, pluginConfig, roleSyncService, getLogger());
            this.impeachmentService = new ImpeachmentService(database, pluginConfig, roleSyncService, electionService);

            registerCommands();
            startDiscordBot();
            scheduleTick();
            getLogger().info("ElectionsPlugin enabled.");
        } catch (Exception exception) {
            getLogger().severe("Failed to enable ElectionsPlugin: " + exception.getMessage());
            exception.printStackTrace();
            getServer().getPluginManager().disablePlugin(this);
        }
    }

    @Override
    public void onDisable() {
        if (discordBotService != null) {
            discordBotService.shutdown();
        }
        if (worker != null) {
            worker.shutdownNow();
            try {
                worker.awaitTermination(5, TimeUnit.SECONDS);
            } catch (InterruptedException interruptedException) {
                Thread.currentThread().interrupt();
            }
        }
        if (database != null) {
            database.close();
        }
    }

    public void reloadPluginConfig() {
        ensureConfigDefaults();
        this.pluginConfig = new PluginConfig(this);
        if (discordBotService != null) {
            discordBotService.reloadConfig(pluginConfig);
        }
    }

    private void ensureConfigDefaults() {
        saveDefaultConfig();
        reloadConfig();
        getConfig().options().copyDefaults(true);
        saveConfig();
    }

    private void registerCommands() {
        PluginCommand command = Objects.requireNonNull(getCommand("election"), "election command missing from plugin.yml");
        ElectionCommand executor = new ElectionCommand(this, verificationService, electionService, roleSyncService, policyService);
        command.setExecutor(executor);
        command.setTabCompleter(executor);
    }

    private void startDiscordBot() {
        if (!pluginConfig.discordTokenConfigured()) {
            getLogger().warning("Discord token is not configured. ElectionBot will not start.");
            return;
        }

        this.discordBotService = new DiscordBotService(
            this,
            pluginConfig,
            database,
            verificationService,
            electionService,
            cabinetService,
            policyService,
            impeachmentService,
            roleSyncService,
            worker,
            getLogger()
        );
        worker.execute(discordBotService::start);
    }

    private void scheduleTick() {
        worker.scheduleAtFixedRate(() -> {
            try {
                electionService.ensureMonthlyElection();
                if (discordBotService != null && discordBotService.isReady()) {
                    electionService.announceOpenElections(discordBotService);
                    electionService.closeDueElections(discordBotService);
                    impeachmentService.closeDueImpeachments(discordBotService);
                    policyService.closeDueProposals(discordBotService);
                }
                verificationService.deleteExpiredCodes();
            } catch (Exception exception) {
                getLogger().warning("Election tick failed: " + exception.getMessage());
            }
        }, 15, 60, TimeUnit.SECONDS);
    }

    public Path serverRoot() {
        return getServer().getWorldContainer().toPath().toAbsolutePath().normalize();
    }
}
