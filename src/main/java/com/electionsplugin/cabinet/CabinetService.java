package com.electionsplugin.cabinet;

import com.electionsplugin.config.PluginConfig;
import com.electionsplugin.database.Database;
import com.electionsplugin.database.LinkedAccount;
import com.electionsplugin.role.RoleSyncService;

import java.time.Instant;
import java.util.Optional;

public final class CabinetService {
    private final Database database;
    private final PluginConfig config;
    private final RoleSyncService roleSyncService;

    public CabinetService(Database database, PluginConfig config, RoleSyncService roleSyncService) {
        this.database = database;
        this.config = config;
        this.roleSyncService = roleSyncService;
    }

    public CabinetResult assign(String presidentDiscordId, String memberDiscordId, int tier) {
        if (!roleSyncService.isPresident(presidentDiscordId)) {
            return CabinetResult.failure("Only the current president can appoint cabinet members.");
        }
        if (tier < 1 || tier > 3) {
            return CabinetResult.failure("Cabinet tier must be 1, 2, or 3.");
        }
        boolean alreadyCabinet = roleSyncService.isCabinet(memberDiscordId);
        if (!alreadyCabinet && cabinetCount() >= config.maxCabinetMembers()) {
            return CabinetResult.failure("The cabinet is already full.");
        }

        Optional<Integer> oldTier = roleSyncService.cabinetTier(memberDiscordId);
        Optional<LinkedAccount> linked = database.linkedAccount(memberDiscordId);
        oldTier.ifPresent(value -> roleSyncService.removeCabinetLuckPerms(memberDiscordId, value));

        database.update(
            """
            INSERT INTO cabinet_members(discord_id, minecraft_uuid, tier, appointed_by, appointed_at)
            VALUES(?, ?, ?, ?, ?)
            ON CONFLICT(discord_id) DO UPDATE SET
                minecraft_uuid = excluded.minecraft_uuid,
                tier = excluded.tier,
                appointed_by = excluded.appointed_by,
                appointed_at = excluded.appointed_at
            """,
            statement -> {
                Database.setString(statement, 1, memberDiscordId);
                Database.setString(statement, 2, linked.map(account -> account.minecraftUuid().toString()).orElse(null));
                Database.setInt(statement, 3, tier);
                Database.setString(statement, 4, presidentDiscordId);
                Database.setLong(statement, 5, Instant.now().toEpochMilli());
            }
        );
        linked.ifPresent(account -> roleSyncService.syncVerifiedUser(memberDiscordId, account.minecraftUuid()));
        return CabinetResult.success("Cabinet member assigned to tier " + tier + ".");
    }

    public CabinetResult remove(String presidentDiscordId, String memberDiscordId) {
        if (!roleSyncService.isPresident(presidentDiscordId)) {
            return CabinetResult.failure("Only the current president can remove cabinet members.");
        }
        Optional<Integer> tier = roleSyncService.cabinetTier(memberDiscordId);
        if (tier.isEmpty()) {
            return CabinetResult.failure("That user is not in the cabinet.");
        }
        roleSyncService.removeCabinetLuckPerms(memberDiscordId, tier.get());
        database.update("DELETE FROM cabinet_members WHERE discord_id = ?", statement -> Database.setString(statement, 1, memberDiscordId));
        return CabinetResult.success("Cabinet member removed.");
    }

    public int cabinetCount() {
        return database.queryOne(
            "SELECT COUNT(*) AS total FROM cabinet_members",
            statement -> {
            },
            resultSet -> {
                try {
                    return resultSet.getInt("total");
                } catch (Exception exception) {
                    throw new RuntimeException(exception);
                }
            }
        ).orElse(0);
    }

    public record CabinetResult(boolean success, String message) {
        public static CabinetResult success(String message) {
            return new CabinetResult(true, message);
        }

        public static CabinetResult failure(String message) {
            return new CabinetResult(false, message);
        }
    }
}
