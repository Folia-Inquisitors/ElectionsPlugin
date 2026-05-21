package com.electionsplugin.verification;

import com.electionsplugin.config.PluginConfig;
import com.electionsplugin.database.Database;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public final class VerificationService {
    private static final char[] CODE_CHARS = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789".toCharArray();
    private static final long CODE_TTL_MILLIS = 15 * 60 * 1000L;

    private final Database database;
    private final SecureRandom random = new SecureRandom();

    public VerificationService(Database database, PluginConfig pluginConfig) {
        this.database = database;
    }

    public String createCode(String discordId) {
        String code;
        do {
            code = randomCode();
        } while (codeExists(code));
        String verificationCode = code;

        long expiresAt = Instant.now().toEpochMilli() + CODE_TTL_MILLIS;
        database.update(
            "DELETE FROM verification_codes WHERE discord_id = ?",
            statement -> Database.setString(statement, 1, discordId)
        );
        database.update(
            "INSERT INTO verification_codes(code, discord_id, expires_at) VALUES(?, ?, ?)",
            statement -> {
                Database.setString(statement, 1, verificationCode);
                Database.setString(statement, 2, discordId);
                Database.setLong(statement, 3, expiresAt);
            }
        );
        return verificationCode;
    }

    public Optional<String> completeVerification(String code, UUID uuid, String playerName) {
        long now = Instant.now().toEpochMilli();
        Optional<String> discordId = database.queryOne(
            "SELECT discord_id FROM verification_codes WHERE code = ? AND expires_at >= ?",
            statement -> {
                Database.setString(statement, 1, code.toUpperCase());
                Database.setLong(statement, 2, now);
            },
            resultSet -> {
                try {
                    return resultSet.getString("discord_id");
                } catch (Exception exception) {
                    throw new RuntimeException(exception);
                }
            }
        );

        discordId.ifPresent(id -> {
            database.update(
                """
                INSERT INTO account_links(discord_id, minecraft_uuid, minecraft_name, linked_at)
                VALUES(?, ?, ?, ?)
                ON CONFLICT(discord_id) DO UPDATE SET
                    minecraft_uuid = excluded.minecraft_uuid,
                    minecraft_name = excluded.minecraft_name,
                    linked_at = excluded.linked_at
                """,
                statement -> {
                    Database.setString(statement, 1, id);
                    Database.setString(statement, 2, uuid.toString());
                    Database.setString(statement, 3, playerName);
                    Database.setLong(statement, 4, now);
                }
            );
            database.update("DELETE FROM verification_codes WHERE code = ?", statement -> Database.setString(statement, 1, code.toUpperCase()));
        });

        return discordId;
    }

    public void deleteExpiredCodes() {
        database.update(
            "DELETE FROM verification_codes WHERE expires_at < ?",
            statement -> Database.setLong(statement, 1, Instant.now().toEpochMilli())
        );
    }

    private boolean codeExists(String code) {
        return database.queryOne(
            "SELECT code FROM verification_codes WHERE code = ?",
            statement -> Database.setString(statement, 1, code),
            resultSet -> code
        ).isPresent();
    }

    private String randomCode() {
        StringBuilder builder = new StringBuilder(6);
        for (int i = 0; i < 6; i++) {
            builder.append(CODE_CHARS[random.nextInt(CODE_CHARS.length)]);
        }
        return builder.toString();
    }
}
