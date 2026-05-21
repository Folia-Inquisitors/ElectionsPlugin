package com.electionsplugin.impeachment;

import com.electionsplugin.config.PluginConfig;
import com.electionsplugin.database.Database;
import com.electionsplugin.election.ElectionService;
import com.electionsplugin.role.RoleSyncService;

import java.sql.ResultSet;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

public final class ImpeachmentService {
    private final Database database;
    private final PluginConfig config;
    private final RoleSyncService roleSyncService;
    private final ElectionService electionService;

    public ImpeachmentService(Database database, PluginConfig config, RoleSyncService roleSyncService, ElectionService electionService) {
        this.database = database;
        this.config = config;
        this.roleSyncService = roleSyncService;
        this.electionService = electionService;
    }

    public boolean registerImpeachment(String proposerDiscordId, String threadId, String messageId) {
        boolean exists = database.queryOne(
            "SELECT id FROM impeachments WHERE thread_id = ? OR message_id = ?",
            statement -> {
                Database.setString(statement, 1, threadId);
                Database.setString(statement, 2, messageId);
            },
            resultSet -> 1
        ).isPresent();
        if (exists) {
            return true;
        }
        long now = Instant.now().toEpochMilli();
        long closesAt = now + config.impeachmentDurationDays() * 24L * 60L * 60L * 1000L;
        database.insert(
            """
            INSERT INTO impeachments(proposer_discord_id, thread_id, message_id, created_at, closes_at, status)
            VALUES(?, ?, ?, ?, ?, 'OPEN')
            """,
            statement -> {
                Database.setString(statement, 1, proposerDiscordId);
                Database.setString(statement, 2, threadId);
                Database.setString(statement, 3, messageId);
                Database.setLong(statement, 4, now);
                Database.setLong(statement, 5, closesAt);
            }
        );
        return true;
    }

    public void recordVote(String messageId, String discordId, int value, boolean removed) {
        Optional<ImpeachmentRecord> impeachment = impeachmentByMessage(messageId);
        if (impeachment.isEmpty() || !"OPEN".equals(impeachment.get().status()) || Instant.now().toEpochMilli() > impeachment.get().closesAt()) {
            return;
        }
        if (removed) {
            database.update(
                "DELETE FROM votes WHERE context_type = 'IMPEACHMENT' AND context_id = ? AND discord_id = ? AND value = ?",
                statement -> {
                    Database.setLong(statement, 1, impeachment.get().id());
                    Database.setString(statement, 2, discordId);
                    Database.setInt(statement, 3, value);
                }
            );
        } else {
            database.update(
                """
                INSERT INTO votes(context_type, context_id, discord_id, value, updated_at)
                VALUES('IMPEACHMENT', ?, ?, ?, ?)
                ON CONFLICT(context_type, context_id, discord_id) DO UPDATE SET
                    value = excluded.value,
                    updated_at = excluded.updated_at
                """,
                statement -> {
                    Database.setLong(statement, 1, impeachment.get().id());
                    Database.setString(statement, 2, discordId);
                    Database.setInt(statement, 3, value);
                    Database.setLong(statement, 4, Instant.now().toEpochMilli());
                }
            );
        }
        refreshScore(impeachment.get().id());
    }

    public void closeDueImpeachments(DiscordImpeachmentBridge bridge) {
        long now = Instant.now().toEpochMilli();
        List<ImpeachmentRecord> open = database.query(
            "SELECT * FROM impeachments WHERE status = 'OPEN' AND closes_at <= ?",
            statement -> Database.setLong(statement, 1, now),
            this::mapImpeachment
        );
        for (ImpeachmentRecord impeachment : open) {
            refreshScore(impeachment.id());
            ImpeachmentRecord refreshed = impeachmentById(impeachment.id()).orElse(impeachment);
            int total = refreshed.upvotes() + refreshed.downvotes();
            boolean passed = refreshed.upvotes() > refreshed.downvotes() && total >= config.impeachmentMinimumVotes();
            database.update(
                "UPDATE impeachments SET status = ? WHERE id = ?",
                statement -> {
                    Database.setString(statement, 1, passed ? "PASSED" : "FAILED");
                    Database.setLong(statement, 2, refreshed.id());
                }
            );
            if (passed) {
                roleSyncService.clearPresident();
                roleSyncService.removeAllCabinetLuckPerms();
                electionService.createSpecialElection();
                bridge.clearGovernmentDiscordRolesAfterImpeachment();
            }
            bridge.publishImpeachmentResult(refreshed, passed);
        }
    }

    private Optional<ImpeachmentRecord> impeachmentByMessage(String messageId) {
        return database.queryOne(
            "SELECT * FROM impeachments WHERE message_id = ?",
            statement -> Database.setString(statement, 1, messageId),
            this::mapImpeachment
        );
    }

    private Optional<ImpeachmentRecord> impeachmentById(long id) {
        return database.queryOne(
            "SELECT * FROM impeachments WHERE id = ?",
            statement -> Database.setLong(statement, 1, id),
            this::mapImpeachment
        );
    }

    private void refreshScore(long impeachmentId) {
        int up = voteCount(impeachmentId, 1);
        int down = voteCount(impeachmentId, -1);
        database.update(
            "UPDATE impeachments SET upvotes = ?, downvotes = ?, net_score = ? WHERE id = ?",
            statement -> {
                Database.setInt(statement, 1, up);
                Database.setInt(statement, 2, down);
                Database.setInt(statement, 3, up - down);
                Database.setLong(statement, 4, impeachmentId);
            }
        );
    }

    private int voteCount(long contextId, int value) {
        return database.queryOne(
            "SELECT COUNT(*) AS total FROM votes WHERE context_type = 'IMPEACHMENT' AND context_id = ? AND value = ?",
            statement -> {
                Database.setLong(statement, 1, contextId);
                Database.setInt(statement, 2, value);
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

    private ImpeachmentRecord mapImpeachment(ResultSet resultSet) {
        try {
            return new ImpeachmentRecord(
                resultSet.getLong("id"),
                resultSet.getString("proposer_discord_id"),
                resultSet.getString("thread_id"),
                resultSet.getString("message_id"),
                resultSet.getLong("created_at"),
                resultSet.getLong("closes_at"),
                resultSet.getString("status"),
                resultSet.getInt("upvotes"),
                resultSet.getInt("downvotes"),
                resultSet.getInt("net_score")
            );
        } catch (Exception exception) {
            throw new RuntimeException(exception);
        }
    }
}
