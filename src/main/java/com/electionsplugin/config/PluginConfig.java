package com.electionsplugin.config;

import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;

public final class PluginConfig {
    private final FileConfiguration config;

    public PluginConfig(JavaPlugin plugin) {
        this.config = plugin.getConfig();
    }

    public ZoneId zoneId() {
        return ZoneId.of(config.getString("timezone", "America/New_York"));
    }

    public String discordToken() {
        return config.getString("discord.token", "");
    }

    public boolean discordTokenConfigured() {
        String token = discordToken();
        return token != null && !token.isBlank() && !token.equals("PUT_TOKEN_HERE");
    }

    public long serverId() {
        String serverId = config.getString("discord.serverId", "");
        if (serverId != null && !serverId.isBlank() && !serverId.startsWith("PUT_")) {
            return parseSnowflake(serverId);
        }
        return parseSnowflake(config.getString("discord.guildId", "0"));
    }

    public long electionsForumId() {
        return parseSnowflake(config.getString("discord.forums.elections", "0"));
    }

    public long impeachmentForumId() {
        return parseSnowflake(config.getString("discord.forums.impeachment", "0"));
    }

    public long policyForumId() {
        return parseSnowflake(config.getString("discord.forums.policyProposals", "0"));
    }

    public long pollsForumId() {
        return parseSnowflake(config.getString("discord.forums.polls", "0"));
    }

    public long approvedChangesLogChannelId() {
        return parseSnowflake(config.getString("discord.channels.approvedChangesLog", "0"));
    }

    public long electionInfoChannelId() {
        return parseSnowflake(config.getString("discord.channels.electionInfo", "0"));
    }

    public String messageTemplate(String key, String fallback) {
        return config.getString("messages." + key, fallback);
    }

    public boolean autoCreateDiscordRoles() {
        return config.getBoolean("roles.autoCreateDiscordRoles", true);
    }

    public String presidentRoleName() {
        return config.getString("roles.president", "President");
    }

    public String cabinetRoleName(int tier) {
        return switch (tier) {
            case 1 -> config.getString("roles.cabinetTier1", "Cabinet Tier 1");
            case 2 -> config.getString("roles.cabinetTier2", "Cabinet Tier 2");
            case 3 -> config.getString("roles.cabinetTier3", "Cabinet Tier 3");
            default -> throw new IllegalArgumentException("Unknown cabinet tier: " + tier);
        };
    }

    public String presidentLuckPermsGroup() {
        return config.getString("luckPerms.groups.president", "president");
    }

    public String cabinetLuckPermsGroup(int tier) {
        return switch (tier) {
            case 1 -> config.getString("luckPerms.groups.cabinetTier1", "cabinet_tier_1");
            case 2 -> config.getString("luckPerms.groups.cabinetTier2", "cabinet_tier_2");
            case 3 -> config.getString("luckPerms.groups.cabinetTier3", "cabinet_tier_3");
            default -> throw new IllegalArgumentException("Unknown cabinet tier: " + tier);
        };
    }

    public int electionStartDay() {
        return config.getInt("elections.monthlyStartDay", 1);
    }

    public int electionEndDay() {
        return config.getInt("elections.monthlyEndDay", 7);
    }

    public LocalTime electionCloseTime() {
        return LocalTime.parse(config.getString("elections.closeTime", "23:59"));
    }

    public int electionMinimumVotes() {
        return config.getInt("elections.minimumVotes", 0);
    }

    public int maxPresidentialWins() {
        return config.getInt("elections.maxPresidentialWins", 2);
    }

    public int unlimitedWinsValue() {
        return config.getInt("elections.unlimitedWinsValue", -1);
    }

    public int impeachmentDurationDays() {
        return config.getInt("impeachment.durationDays", 3);
    }

    public int impeachmentMinimumVotes() {
        return config.getInt("impeachment.minimumVotes", 0);
    }

    public int specialElectionDurationDays() {
        return config.getInt("specialElection.durationDays", 7);
    }

    public int specialElectionMinimumVotes() {
        return config.getInt("specialElection.minimumVotes", 0);
    }

    public int maxCabinetMembers() {
        return config.getInt("cabinet.maxMembers", 3);
    }

    public int cabinetProposalLimitAmount() {
        return config.getInt("cabinet.proposalLimit.amount", 1);
    }

    public int cabinetProposalLimitDays() {
        return config.getInt("cabinet.proposalLimit.periodDays", 7);
    }

    public int presidentProposalLimit() {
        return config.getInt("cabinet.presidentProposalLimit", -1);
    }

    public int policyDurationDays() {
        return config.getInt("policy.durationDays", 7);
    }

    public int policyMinimumVotes() {
        return config.getInt("policy.minimumVotes", 0);
    }

    public long policyMaxFileBytes() {
        return config.getLong("policy.maxFileBytes", 200000);
    }

    public boolean policyAllowDiscordOwnerBypass() {
        return config.getBoolean("policy.allowDiscordOwnerBypass", true);
    }

    public boolean policyAllowDiscordManageServerBypass() {
        return config.getBoolean("policy.allowDiscordManageServerBypass", false);
    }

    public List<String> policyAdminBypassRoles() {
        return config.getStringList("policy.adminBypassRoles");
    }

    public List<String> policyPluginJarSearchPaths() {
        return config.getStringList("policy.pluginJarSearchPaths");
    }

    public List<String> allowedExtensions() {
        return config.getStringList("policy.allowedExtensions");
    }

    public List<String> excludedPaths() {
        return config.getStringList("policy.excludedPaths");
    }

    public List<String> secretPatterns() {
        return config.getStringList("policy.secretPatterns");
    }

    private long parseSnowflake(String value) {
        if (value == null || value.isBlank() || value.startsWith("PUT_")) {
            return 0L;
        }
        try {
            return Long.parseUnsignedLong(value);
        } catch (NumberFormatException ignored) {
            return 0L;
        }
    }
}
