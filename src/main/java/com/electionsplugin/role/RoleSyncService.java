package com.electionsplugin.role;

import com.electionsplugin.config.PluginConfig;
import com.electionsplugin.database.Database;
import com.electionsplugin.database.LinkedAccount;
import com.electionsplugin.luckperms.LuckPermsService;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.logging.Logger;

public final class RoleSyncService {
    private final Database database;
    private final LuckPermsService luckPermsService;
    private final PluginConfig config;
    private final Logger logger;

    public RoleSyncService(Database database, LuckPermsService luckPermsService, PluginConfig config, Logger logger) {
        this.database = database;
        this.luckPermsService = luckPermsService;
        this.config = config;
        this.logger = logger;
    }

    public boolean canWinPresidency(String discordId) {
        int max = config.maxPresidentialWins();
        if (max == config.unlimitedWinsValue()) {
            return true;
        }
        if (wins("DISCORD", discordId) >= max) {
            return false;
        }
        Optional<LinkedAccount> linked = database.linkedAccount(discordId);
        return linked.map(account -> wins("MINECRAFT", account.minecraftUuid().toString()) < max).orElse(true);
    }

    public void recordPresidentialWin(String discordId) {
        incrementWins("DISCORD", discordId);
        database.linkedAccount(discordId).ifPresent(account -> incrementWins("MINECRAFT", account.minecraftUuid().toString()));
    }

    public void setPresident(String discordId) {
        clearPresident();
        long now = Instant.now().toEpochMilli();
        Optional<LinkedAccount> linked = database.linkedAccount(discordId);
        database.update(
            """
            INSERT INTO office_holders(role, discord_id, minecraft_uuid, tier, since_at)
            VALUES('PRESIDENT', ?, ?, NULL, ?)
            ON CONFLICT(role) DO UPDATE SET
                discord_id = excluded.discord_id,
                minecraft_uuid = excluded.minecraft_uuid,
                tier = excluded.tier,
                since_at = excluded.since_at
            """,
            statement -> {
                Database.setString(statement, 1, discordId);
                Database.setString(statement, 2, linked.map(account -> account.minecraftUuid().toString()).orElse(null));
                Database.setLong(statement, 3, now);
            }
        );
        linked.ifPresent(account -> luckPermsService.addGroup(account.minecraftUuid(), config.presidentLuckPermsGroup()));
    }

    public void clearPresident() {
        currentPresident().ifPresent(holder -> {
            if (holder.minecraftUuid() != null) {
                luckPermsService.removeGroup(holder.minecraftUuid(), config.presidentLuckPermsGroup());
            }
        });
        database.update("DELETE FROM office_holders WHERE role = 'PRESIDENT'", statement -> {
        });
    }

    public void syncVerifiedUser(String discordId, UUID uuid) {
        currentPresident().filter(holder -> holder.discordId().equals(discordId)).ifPresent(holder ->
            luckPermsService.addGroup(uuid, config.presidentLuckPermsGroup())
        );
        cabinetTier(discordId).ifPresent(tier -> luckPermsService.addGroup(uuid, config.cabinetLuckPermsGroup(tier)));
    }

    public void removeCabinetLuckPerms(String discordId, int tier) {
        database.linkedAccount(discordId).ifPresent(account ->
            luckPermsService.removeGroup(account.minecraftUuid(), config.cabinetLuckPermsGroup(tier))
        );
    }

    public Optional<OfficeHolder> currentPresident() {
        return database.queryOne(
            "SELECT discord_id, minecraft_uuid, tier FROM office_holders WHERE role = 'PRESIDENT'",
            statement -> {
            },
            resultSet -> {
                try {
                    String uuidValue = resultSet.getString("minecraft_uuid");
                    return new OfficeHolder(
                        resultSet.getString("discord_id"),
                        uuidValue == null ? null : UUID.fromString(uuidValue),
                        0
                    );
                } catch (Exception exception) {
                    throw new RuntimeException(exception);
                }
            }
        );
    }

    public boolean isPresident(String discordId) {
        return currentPresident().map(holder -> holder.discordId().equals(discordId)).orElse(false);
    }

    public boolean isCabinet(String discordId) {
        return cabinetTier(discordId).isPresent();
    }

    public Optional<Integer> cabinetTier(String discordId) {
        return database.queryOne(
            "SELECT tier FROM cabinet_members WHERE discord_id = ?",
            statement -> Database.setString(statement, 1, discordId),
            resultSet -> {
                try {
                    return resultSet.getInt("tier");
                } catch (Exception exception) {
                    throw new RuntimeException(exception);
                }
            }
        );
    }

    public void removeAllCabinetLuckPerms() {
        database.query(
            "SELECT discord_id, minecraft_uuid, tier FROM cabinet_members",
            statement -> {
            },
            resultSet -> {
                try {
                    String uuidValue = resultSet.getString("minecraft_uuid");
                    if (uuidValue != null) {
                        luckPermsService.removeGroup(UUID.fromString(uuidValue), config.cabinetLuckPermsGroup(resultSet.getInt("tier")));
                    }
                    return 0;
                } catch (Exception exception) {
                    logger.warning("Failed to remove cabinet LuckPerms group: " + exception.getMessage());
                    return 0;
                }
            }
        );
        database.update("DELETE FROM cabinet_members", statement -> {
        });
    }

    public record OfficeHolder(String discordId, UUID minecraftUuid, int tier) {
    }

    private int wins(String identityType, String identityValue) {
        return database.queryOne(
            "SELECT wins FROM term_counts WHERE identity_type = ? AND identity_value = ?",
            statement -> {
                Database.setString(statement, 1, identityType);
                Database.setString(statement, 2, identityValue);
            },
            resultSet -> {
                try {
                    return resultSet.getInt("wins");
                } catch (Exception exception) {
                    throw new RuntimeException(exception);
                }
            }
        ).orElse(0);
    }

    private void incrementWins(String identityType, String identityValue) {
        database.update(
            """
            INSERT INTO term_counts(identity_type, identity_value, wins)
            VALUES(?, ?, 1)
            ON CONFLICT(identity_type, identity_value) DO UPDATE SET wins = wins + 1
            """,
            statement -> {
                Database.setString(statement, 1, identityType);
                Database.setString(statement, 2, identityValue);
            }
        );
    }
}
