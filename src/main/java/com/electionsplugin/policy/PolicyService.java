package com.electionsplugin.policy;

import com.electionsplugin.ElectionsPlugin;
import com.electionsplugin.config.PluginConfig;
import com.electionsplugin.database.Database;
import com.electionsplugin.role.RoleSyncService;
import com.google.gson.JsonParser;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.FileVisitResult;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.sql.ResultSet;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Locale;
import java.util.Optional;
import java.util.logging.Logger;
import java.util.regex.Pattern;

public final class PolicyService {
    private final ElectionsPlugin plugin;
    private final Database database;
    private final PluginConfig config;
    private final RoleSyncService roleSyncService;
    private final Logger logger;

    public PolicyService(ElectionsPlugin plugin, Database database, PluginConfig config, RoleSyncService roleSyncService, Logger logger) {
        this.plugin = plugin;
        this.database = database;
        this.config = config;
        this.roleSyncService = roleSyncService;
        this.logger = logger;
    }

    public PolicyResult createProposal(String proposerDiscordId, String relativePath, String proposedContent) {
        if (!roleSyncService.isPresident(proposerDiscordId) && !roleSyncService.isCabinet(proposerDiscordId)) {
            return PolicyResult.failure("Only the president or cabinet members can submit policy proposals.");
        }
        if (!roleSyncService.isPresident(proposerDiscordId) && !withinCabinetProposalLimit(proposerDiscordId)) {
            return PolicyResult.failure("You have reached your configured cabinet proposal limit.");
        }

        Path target = resolveAllowedPath(relativePath);
        if (target == null) {
            return PolicyResult.failure("That file path is not allowed.");
        }
        if (!Files.isRegularFile(target)) {
            return PolicyResult.failure("That file does not exist or is not a regular file.");
        }
        try {
            if (Files.size(target) > config.policyMaxFileBytes()) {
                return PolicyResult.failure("That file is larger than the configured max file size.");
            }
            String current = Files.readString(target, StandardCharsets.UTF_8);
            if (containsSecret(current) || containsSecret(proposedContent)) {
                return PolicyResult.failure("Secret-like content was detected, so this proposal was blocked before public posting.");
            }
            String validationError = validateSyntax(relativePath, proposedContent);
            if (validationError != null) {
                return PolicyResult.failure(validationError);
            }

            String diff = DiffUtil.unifiedDiff(current, proposedContent, 4);
            long now = Instant.now().toEpochMilli();
            long closesAt = now + config.policyDurationDays() * 24L * 60L * 60L * 1000L;
            long id = database.insert(
                """
                INSERT INTO proposals(proposer_discord_id, relative_path, proposed_content, diff, created_at, closes_at, status)
                VALUES(?, ?, ?, ?, ?, ?, 'OPEN')
                """,
                statement -> {
                    Database.setString(statement, 1, proposerDiscordId);
                    Database.setString(statement, 2, normalizeRelativePath(relativePath));
                    Database.setString(statement, 3, proposedContent);
                    Database.setString(statement, 4, diff);
                    Database.setLong(statement, 5, now);
                    Database.setLong(statement, 6, closesAt);
                }
            );
            return PolicyResult.success("Proposal created.", proposalById(id).orElseThrow());
        } catch (Exception exception) {
            logger.warning("Failed to create policy proposal: " + exception.getMessage());
            return PolicyResult.failure("Failed to create proposal: " + exception.getMessage());
        }
    }

    public boolean canSubmitPolicy(String discordId) {
        return roleSyncService.isPresident(discordId) || roleSyncService.isCabinet(discordId);
    }

    public List<FileEntry> listEditableFiles(int maxFiles) {
        Path root = plugin.serverRoot();
        List<FileEntry> results = new ArrayList<>();
        try {
            Files.walkFileTree(root, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    if (!dir.equals(root) && isExcluded(root.relativize(dir).toString() + "/")) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    return results.size() >= maxFiles ? FileVisitResult.TERMINATE : FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    if (results.size() >= maxFiles) {
                        return FileVisitResult.TERMINATE;
                    }
                    String relative = normalizeRelativePath(root.relativize(file).toString());
                    if (resolveAllowedPath(relative) != null) {
                        results.add(new FileEntry(relative, attrs.size()));
                    }
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (Exception exception) {
            logger.warning("Failed to scan editable policy files: " + exception.getMessage());
        }
        return results.stream()
            .sorted(Comparator.comparing(FileEntry::relativePath))
            .limit(maxFiles)
            .toList();
    }

    public FilePreview previewFile(String relativePath, int page, int pageChars) {
        Path target = resolveAllowedPath(relativePath);
        if (target == null || !Files.isRegularFile(target)) {
            return FilePreview.failure("That file is not allowed or no longer exists.");
        }
        try {
            if (Files.size(target) > config.policyMaxFileBytes()) {
                return FilePreview.failure("That file is larger than the configured max file size.");
            }
            String content = Files.readString(target, StandardCharsets.UTF_8);
            if (containsSecret(content)) {
                return FilePreview.failure("That file contains secret-like content and cannot be viewed through Discord.");
            }
            int totalPages = Math.max(1, (int) Math.ceil((double) content.length() / pageChars));
            int safePage = Math.max(0, Math.min(page, totalPages - 1));
            int start = safePage * pageChars;
            int end = Math.min(content.length(), start + pageChars);
            String pageContent = content.substring(start, end);
            return FilePreview.success(normalizeRelativePath(relativePath), content, pageContent, safePage, totalPages);
        } catch (Exception exception) {
            return FilePreview.failure("Could not read file: " + exception.getMessage());
        }
    }

    public void markProposalPosted(long proposalId, String threadId, String messageId) {
        database.update(
            "UPDATE proposals SET poll_thread_id = ?, poll_message_id = ? WHERE id = ?",
            statement -> {
                Database.setString(statement, 1, threadId);
                Database.setString(statement, 2, messageId);
                Database.setLong(statement, 3, proposalId);
            }
        );
    }

    public void recordProposalVote(String messageId, String discordId, int value, boolean removed) {
        Optional<ProposalRecord> proposal = proposalByMessage(messageId);
        if (proposal.isEmpty() || !"OPEN".equals(proposal.get().status()) || Instant.now().toEpochMilli() > proposal.get().closesAt()) {
            return;
        }
        if (removed) {
            database.update(
                "DELETE FROM votes WHERE context_type = 'PROPOSAL' AND context_id = ? AND discord_id = ? AND value = ?",
                statement -> {
                    Database.setLong(statement, 1, proposal.get().id());
                    Database.setString(statement, 2, discordId);
                    Database.setInt(statement, 3, value);
                }
            );
        } else {
            database.update(
                """
                INSERT INTO votes(context_type, context_id, discord_id, value, updated_at)
                VALUES('PROPOSAL', ?, ?, ?, ?)
                ON CONFLICT(context_type, context_id, discord_id) DO UPDATE SET
                    value = excluded.value,
                    updated_at = excluded.updated_at
                """,
                statement -> {
                    Database.setLong(statement, 1, proposal.get().id());
                    Database.setString(statement, 2, discordId);
                    Database.setInt(statement, 3, value);
                    Database.setLong(statement, 4, Instant.now().toEpochMilli());
                }
            );
        }
        refreshProposalScore(proposal.get().id());
    }

    public void closeDueProposals(DiscordPolicyBridge bridge) {
        long now = Instant.now().toEpochMilli();
        List<ProposalRecord> proposals = database.query(
            "SELECT * FROM proposals WHERE status = 'OPEN' AND closes_at <= ? AND poll_message_id IS NOT NULL",
            statement -> Database.setLong(statement, 1, now),
            this::mapProposal
        );
        for (ProposalRecord proposal : proposals) {
            refreshProposalScore(proposal.id());
            ProposalRecord refreshed = proposalById(proposal.id()).orElse(proposal);
            int totalVotes = refreshed.upvotes() + refreshed.downvotes();
            boolean passed = refreshed.upvotes() > refreshed.downvotes() && totalVotes >= config.policyMinimumVotes();
            if (passed) {
                database.update("UPDATE proposals SET status = 'APPROVED' WHERE id = ?", statement -> Database.setLong(statement, 1, refreshed.id()));
                database.insert(
                    """
                    INSERT INTO staged_file_changes(proposal_id, relative_path, proposed_content, status, created_at)
                    VALUES(?, ?, ?, 'PENDING', ?)
                    """,
                    statement -> {
                        Database.setLong(statement, 1, refreshed.id());
                        Database.setString(statement, 2, refreshed.relativePath());
                        Database.setString(statement, 3, refreshed.proposedContent());
                        Database.setLong(statement, 4, now);
                    }
                );
                bridge.publishPolicyApproved(refreshed);
            } else {
                database.update("UPDATE proposals SET status = 'REJECTED' WHERE id = ?", statement -> Database.setLong(statement, 1, refreshed.id()));
            }
        }
    }

    public void applyPendingChangesOnStartup() {
        List<StagedChange> changes = database.query(
            "SELECT * FROM staged_file_changes WHERE status = 'PENDING'",
            statement -> {
            },
            resultSet -> {
                try {
                    return new StagedChange(
                        resultSet.getLong("id"),
                        resultSet.getLong("proposal_id"),
                        resultSet.getString("relative_path"),
                        resultSet.getString("proposed_content")
                    );
                } catch (Exception exception) {
                    throw new RuntimeException(exception);
                }
            }
        );
        for (StagedChange change : changes) {
            applyStagedChange(change);
        }
    }

    public Optional<ProposalRecord> proposalById(long id) {
        return database.queryOne(
            "SELECT * FROM proposals WHERE id = ?",
            statement -> Database.setLong(statement, 1, id),
            this::mapProposal
        );
    }

    private Optional<ProposalRecord> proposalByMessage(String messageId) {
        return database.queryOne(
            "SELECT * FROM proposals WHERE poll_message_id = ?",
            statement -> Database.setString(statement, 1, messageId),
            this::mapProposal
        );
    }

    private boolean withinCabinetProposalLimit(String discordId) {
        int amount = config.cabinetProposalLimitAmount();
        if (amount < 0) {
            return true;
        }
        long since = Instant.now().minusSeconds(config.cabinetProposalLimitDays() * 24L * 60L * 60L).toEpochMilli();
        int count = database.queryOne(
            "SELECT COUNT(*) AS total FROM proposals WHERE proposer_discord_id = ? AND created_at >= ?",
            statement -> {
                Database.setString(statement, 1, discordId);
                Database.setLong(statement, 2, since);
            },
            resultSet -> {
                try {
                    return resultSet.getInt("total");
                } catch (Exception exception) {
                    throw new RuntimeException(exception);
                }
            }
        ).orElse(0);
        return count < amount;
    }

    private Path resolveAllowedPath(String relativePath) {
        String normalizedRelative = normalizeRelativePath(relativePath);
        if (normalizedRelative.isBlank()) {
            return null;
        }
        String lower = normalizedRelative.toLowerCase(Locale.ROOT);
        boolean extensionAllowed = config.allowedExtensions().stream()
            .map(value -> value.toLowerCase(Locale.ROOT))
            .anyMatch(lower::endsWith);
        if (!extensionAllowed) {
            return null;
        }
        if (isExcluded(normalizedRelative)) {
            return null;
        }
        Path root = plugin.serverRoot();
        Path target = root.resolve(normalizedRelative).normalize();
        if (!target.startsWith(root)) {
            return null;
        }
        return target;
    }

    private String normalizeRelativePath(String relativePath) {
        return relativePath == null ? "" : relativePath.replace('\\', '/').replaceFirst("^/+", "");
    }

    private boolean isExcluded(String relativePath) {
        String lower = normalizeRelativePath(relativePath).toLowerCase(Locale.ROOT);
        for (String excluded : config.excludedPaths()) {
            String excludedNormalized = normalizeRelativePath(excluded).toLowerCase(Locale.ROOT);
            if (!excludedNormalized.isBlank() && lower.startsWith(excludedNormalized)) {
                return true;
            }
        }
        return false;
    }

    private String validateSyntax(String relativePath, String content) {
        String lower = relativePath.toLowerCase(Locale.ROOT);
        try {
            if (lower.endsWith(".json")) {
                JsonParser.parseString(content);
            } else if (lower.endsWith(".yml") || lower.endsWith(".yaml")) {
                YamlConfiguration yaml = new YamlConfiguration();
                yaml.loadFromString(content);
            }
            return null;
        } catch (InvalidConfigurationException exception) {
            return "Invalid YAML: " + exception.getMessage();
        } catch (Exception exception) {
            return "Invalid JSON/YAML: " + exception.getMessage();
        }
    }

    private boolean containsSecret(String text) {
        for (String pattern : config.secretPatterns()) {
            if (Pattern.compile(pattern).matcher(text).find()) {
                return true;
            }
        }
        return false;
    }

    private void refreshProposalScore(long proposalId) {
        int up = voteCount("PROPOSAL", proposalId, 1);
        int down = voteCount("PROPOSAL", proposalId, -1);
        database.update(
            "UPDATE proposals SET upvotes = ?, downvotes = ?, net_score = ? WHERE id = ?",
            statement -> {
                Database.setInt(statement, 1, up);
                Database.setInt(statement, 2, down);
                Database.setInt(statement, 3, up - down);
                Database.setLong(statement, 4, proposalId);
            }
        );
    }

    private int voteCount(String contextType, long contextId, int value) {
        return database.queryOne(
            "SELECT COUNT(*) AS total FROM votes WHERE context_type = ? AND context_id = ? AND value = ?",
            statement -> {
                Database.setString(statement, 1, contextType);
                Database.setLong(statement, 2, contextId);
                Database.setInt(statement, 3, value);
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

    private void applyStagedChange(StagedChange change) {
        Path target = resolveAllowedPath(change.relativePath());
        if (target == null) {
            markStageFailed(change.id(), "Target path is no longer allowed.");
            return;
        }
        try {
            String validation = validateSyntax(change.relativePath(), change.proposedContent());
            if (validation != null) {
                markStageFailed(change.id(), validation);
                return;
            }
            Files.createDirectories(target.getParent());
            String backupStamp = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneOffset.UTC).format(Instant.now());
            Path backup = plugin.getDataFolder().toPath()
                .resolve("backups")
                .resolve(backupStamp + "-proposal-" + change.proposalId())
                .resolve(change.relativePath())
                .normalize();
            Files.createDirectories(backup.getParent());
            if (Files.exists(target)) {
                Files.copy(target, backup);
            }
            try {
                Files.writeString(target, change.proposedContent(), StandardCharsets.UTF_8);
                database.update(
                    "UPDATE staged_file_changes SET status = 'APPLIED', applied_at = ? WHERE id = ?",
                    statement -> {
                        Database.setLong(statement, 1, Instant.now().toEpochMilli());
                        Database.setLong(statement, 2, change.id());
                    }
                );
                logger.info("Applied approved policy change for " + change.relativePath() + ".");
            } catch (Exception writeFailure) {
                if (Files.exists(backup)) {
                    Files.copy(backup, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                }
                markStageFailed(change.id(), writeFailure.getMessage());
            }
        } catch (Exception exception) {
            markStageFailed(change.id(), exception.getMessage());
        }
    }

    private void markStageFailed(long stagedId, String failure) {
        database.update(
            "UPDATE staged_file_changes SET status = 'FAILED', failure = ? WHERE id = ?",
            statement -> {
                Database.setString(statement, 1, failure);
                Database.setLong(statement, 2, stagedId);
            }
        );
        logger.warning("Failed to apply staged policy change " + stagedId + ": " + failure);
    }

    private ProposalRecord mapProposal(ResultSet resultSet) {
        try {
            return new ProposalRecord(
                resultSet.getLong("id"),
                resultSet.getString("proposer_discord_id"),
                resultSet.getString("relative_path"),
                resultSet.getString("proposed_content"),
                resultSet.getString("diff"),
                resultSet.getString("poll_thread_id"),
                resultSet.getString("poll_message_id"),
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

    private record StagedChange(long id, long proposalId, String relativePath, String proposedContent) {
    }

    public record FileEntry(String relativePath, long size) {
    }

    public record FilePreview(boolean success, String message, String relativePath, String fullContent, String pageContent, int page, int totalPages) {
        public static FilePreview success(String relativePath, String fullContent, String pageContent, int page, int totalPages) {
            return new FilePreview(true, null, relativePath, fullContent, pageContent, page, totalPages);
        }

        public static FilePreview failure(String message) {
            return new FilePreview(false, message, null, null, null, 0, 0);
        }
    }
}
