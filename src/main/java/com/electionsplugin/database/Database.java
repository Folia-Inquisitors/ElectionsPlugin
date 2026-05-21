package com.electionsplugin.database;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.logging.Logger;

public final class Database implements AutoCloseable {
    private final Path databasePath;
    private final Logger logger;
    private Connection connection;

    public Database(Path databasePath, Logger logger) {
        this.databasePath = databasePath;
        this.logger = logger;
    }

    public synchronized void init() throws SQLException {
        try {
            Files.createDirectories(databasePath.getParent());
        } catch (Exception exception) {
            throw new SQLException("Unable to create plugin data folder", exception);
        }
        this.connection = DriverManager.getConnection("jdbc:sqlite:" + databasePath.toAbsolutePath());
        execute("""
            PRAGMA journal_mode = WAL;
            """);
        execute("""
            CREATE TABLE IF NOT EXISTS account_links (
                discord_id TEXT PRIMARY KEY,
                minecraft_uuid TEXT NOT NULL,
                minecraft_name TEXT NOT NULL,
                linked_at INTEGER NOT NULL
            );
            """);
        execute("""
            CREATE TABLE IF NOT EXISTS verification_codes (
                code TEXT PRIMARY KEY,
                discord_id TEXT NOT NULL,
                expires_at INTEGER NOT NULL
            );
            """);
        execute("""
            CREATE TABLE IF NOT EXISTS elections (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                type TEXT NOT NULL,
                period_key TEXT NOT NULL,
                starts_at INTEGER NOT NULL,
                ends_at INTEGER NOT NULL,
                status TEXT NOT NULL,
                winner_discord_id TEXT,
                created_at INTEGER NOT NULL,
                UNIQUE(type, period_key)
            );
            """);
        execute("""
            CREATE TABLE IF NOT EXISTS candidates (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                election_id INTEGER NOT NULL,
                discord_id TEXT NOT NULL,
                thread_id TEXT NOT NULL UNIQUE,
                message_id TEXT NOT NULL UNIQUE,
                created_at INTEGER NOT NULL,
                upvotes INTEGER NOT NULL DEFAULT 0,
                downvotes INTEGER NOT NULL DEFAULT 0,
                net_score INTEGER NOT NULL DEFAULT 0
            );
            """);
        execute("""
            CREATE TABLE IF NOT EXISTS votes (
                context_type TEXT NOT NULL,
                context_id INTEGER NOT NULL,
                discord_id TEXT NOT NULL,
                value INTEGER NOT NULL,
                updated_at INTEGER NOT NULL,
                PRIMARY KEY(context_type, context_id, discord_id)
            );
            """);
        execute("""
            CREATE TABLE IF NOT EXISTS term_counts (
                identity_type TEXT NOT NULL,
                identity_value TEXT NOT NULL,
                wins INTEGER NOT NULL,
                PRIMARY KEY(identity_type, identity_value)
            );
            """);
        execute("""
            CREATE TABLE IF NOT EXISTS office_holders (
                role TEXT PRIMARY KEY,
                discord_id TEXT NOT NULL,
                minecraft_uuid TEXT,
                tier INTEGER,
                since_at INTEGER NOT NULL
            );
            """);
        execute("""
            CREATE TABLE IF NOT EXISTS cabinet_members (
                discord_id TEXT PRIMARY KEY,
                minecraft_uuid TEXT,
                tier INTEGER NOT NULL,
                appointed_by TEXT NOT NULL,
                appointed_at INTEGER NOT NULL
            );
            """);
        execute("""
            CREATE TABLE IF NOT EXISTS proposals (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                proposer_discord_id TEXT NOT NULL,
                relative_path TEXT NOT NULL,
                proposed_content TEXT NOT NULL,
                diff TEXT NOT NULL,
                poll_thread_id TEXT,
                poll_message_id TEXT UNIQUE,
                created_at INTEGER NOT NULL,
                closes_at INTEGER NOT NULL,
                status TEXT NOT NULL,
                upvotes INTEGER NOT NULL DEFAULT 0,
                downvotes INTEGER NOT NULL DEFAULT 0,
                net_score INTEGER NOT NULL DEFAULT 0
            );
            """);
        execute("""
            CREATE TABLE IF NOT EXISTS impeachments (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                proposer_discord_id TEXT NOT NULL,
                thread_id TEXT NOT NULL UNIQUE,
                message_id TEXT NOT NULL UNIQUE,
                created_at INTEGER NOT NULL,
                closes_at INTEGER NOT NULL,
                status TEXT NOT NULL,
                upvotes INTEGER NOT NULL DEFAULT 0,
                downvotes INTEGER NOT NULL DEFAULT 0,
                net_score INTEGER NOT NULL DEFAULT 0
            );
            """);
        execute("""
            CREATE TABLE IF NOT EXISTS staged_file_changes (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                proposal_id INTEGER NOT NULL,
                relative_path TEXT NOT NULL,
                proposed_content TEXT NOT NULL,
                status TEXT NOT NULL,
                created_at INTEGER NOT NULL,
                applied_at INTEGER,
                failure TEXT
            );
            """);
        logger.info("SQLite database ready at " + databasePath.toAbsolutePath());
    }

    public synchronized void execute(String sql) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    public synchronized int update(String sql, Consumer<PreparedStatement> binder) {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            binder.accept(statement);
            return statement.executeUpdate();
        } catch (SQLException exception) {
            throw new DatabaseException(exception);
        }
    }

    public synchronized long insert(String sql, Consumer<PreparedStatement> binder) {
        try (PreparedStatement statement = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            binder.accept(statement);
            statement.executeUpdate();
            try (ResultSet keys = statement.getGeneratedKeys()) {
                if (keys.next()) {
                    return keys.getLong(1);
                }
            }
            return -1L;
        } catch (SQLException exception) {
            throw new DatabaseException(exception);
        }
    }

    public synchronized <T> List<T> query(String sql, Consumer<PreparedStatement> binder, Function<ResultSet, T> mapper) {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            binder.accept(statement);
            try (ResultSet resultSet = statement.executeQuery()) {
                List<T> results = new ArrayList<>();
                while (resultSet.next()) {
                    results.add(mapper.apply(resultSet));
                }
                return results;
            }
        } catch (SQLException exception) {
            throw new DatabaseException(exception);
        }
    }

    public synchronized <T> Optional<T> queryOne(String sql, Consumer<PreparedStatement> binder, Function<ResultSet, T> mapper) {
        List<T> results = query(sql, binder, mapper);
        if (results.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(results.getFirst());
    }

    public synchronized Optional<LinkedAccount> linkedAccount(String discordId) {
        return queryOne(
            "SELECT discord_id, minecraft_uuid, minecraft_name FROM account_links WHERE discord_id = ?",
            statement -> setString(statement, 1, discordId),
            resultSet -> {
                try {
                    return new LinkedAccount(
                        resultSet.getString("discord_id"),
                        UUID.fromString(resultSet.getString("minecraft_uuid")),
                        resultSet.getString("minecraft_name")
                    );
                } catch (SQLException exception) {
                    throw new DatabaseException(exception);
                }
            }
        );
    }

    public synchronized Optional<LinkedAccount> linkedAccountByUuid(UUID uuid) {
        return queryOne(
            "SELECT discord_id, minecraft_uuid, minecraft_name FROM account_links WHERE minecraft_uuid = ?",
            statement -> setString(statement, 1, uuid.toString()),
            resultSet -> {
                try {
                    return new LinkedAccount(
                        resultSet.getString("discord_id"),
                        UUID.fromString(resultSet.getString("minecraft_uuid")),
                        resultSet.getString("minecraft_name")
                    );
                } catch (SQLException exception) {
                    throw new DatabaseException(exception);
                }
            }
        );
    }

    public synchronized void close() {
        if (connection == null) {
            return;
        }
        try {
            connection.close();
        } catch (SQLException exception) {
            logger.warning("Failed to close SQLite database: " + exception.getMessage());
        }
    }

    public static void setString(PreparedStatement statement, int index, String value) {
        try {
            statement.setString(index, value);
        } catch (SQLException exception) {
            throw new DatabaseException(exception);
        }
    }

    public static void setLong(PreparedStatement statement, int index, long value) {
        try {
            statement.setLong(index, value);
        } catch (SQLException exception) {
            throw new DatabaseException(exception);
        }
    }

    public static void setInt(PreparedStatement statement, int index, int value) {
        try {
            statement.setInt(index, value);
        } catch (SQLException exception) {
            throw new DatabaseException(exception);
        }
    }
}
