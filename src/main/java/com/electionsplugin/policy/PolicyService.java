package com.electionsplugin.policy;

import com.electionsplugin.ElectionsPlugin;
import com.electionsplugin.config.PluginConfig;
import com.electionsplugin.database.Database;
import com.electionsplugin.role.RoleSyncService;
import com.google.gson.JsonParser;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.FileVisitResult;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.sql.ResultSet;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.Map;
import java.util.logging.Logger;
import java.util.regex.Pattern;
import java.util.jar.JarFile;

public final class PolicyService {
    private static final String REDACTION = "****";
    private static final Pattern IPV4_PATTERN = Pattern.compile("\\b(?:(?:25[0-5]|2[0-4]\\d|1?\\d?\\d)\\.){3}(?:25[0-5]|2[0-4]\\d|1?\\d?\\d)(?::\\d{1,5})?\\b");
    private static final Pattern HOST_PORT_PATTERN = Pattern.compile("(?i)\\b(?:[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\\.)+[a-z]{2,}(?::\\d{1,5})\\b");
    private static final Pattern KEY_VALUE_PATTERN = Pattern.compile("^(\\s*[\"']?([A-Za-z0-9_.-]+)[\"']?\\s*[:=]\\s*)(.*)$");
    private static final Set<String> SECRET_KEY_PARTS = Set.of("token", "password", "passwd", "secret", "apikey", "api-key", "api_key", "privatekey", "private-key", "private_key", "clientsecret", "client-secret", "client_secret");
    private static final Set<String> ADDRESS_KEY_PARTS = Set.of("ip", "host", "address", "addr", "url", "uri", "dsn", "jdbc");
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
        return createProposal(proposerDiscordId, relativePath, proposedContent, false);
    }

    public PolicyResult createProposal(String proposerDiscordId, String relativePath, String proposedContent, boolean bypassOfficeRequirement) {
        boolean president = roleSyncService.isPresident(proposerDiscordId);
        boolean cabinet = roleSyncService.isCabinet(proposerDiscordId);
        if (!bypassOfficeRequirement && !president && !cabinet) {
            return PolicyResult.failure("Only the president or cabinet members can submit policy proposals.");
        }
        if (!bypassOfficeRequirement && !president && !withinCabinetProposalLimit(proposerDiscordId)) {
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
            ProtectedMerge protectedMerge = mergeProtectedValues(current, proposedContent);
            if (!protectedMerge.success()) {
                return PolicyResult.failure(protectedMerge.message());
            }
            String effectiveProposedContent = protectedMerge.content();
            String validationError = validateSyntax(relativePath, effectiveProposedContent);
            if (validationError != null) {
                return PolicyResult.failure(validationError);
            }

            String diff = DiffUtil.unifiedDiff(redactProtectedValues(current), redactProtectedValues(effectiveProposedContent), 4);
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
                    Database.setString(statement, 3, effectiveProposedContent);
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

    public DirectoryListing listEditableDirectory(String relativeDirectory, int maxEntries) {
        Path root = plugin.serverRoot();
        String currentPath = normalizeDirectoryPath(relativeDirectory);
        Path directory = resolveAllowedDirectory(currentPath);
        if (directory == null || !Files.isDirectory(directory)) {
            return new DirectoryListing("", List.of());
        }

        List<BrowserEntry> directories = new ArrayList<>();
        List<BrowserEntry> files = new ArrayList<>();
        try (var children = Files.list(directory)) {
            children.forEach(child -> {
                String relative = normalizeRelativePath(root.relativize(child).toString());
                try {
                    if (Files.isDirectory(child)) {
                        String directoryPath = normalizeDirectoryPath(relative);
                        if (!isExcluded(directoryPath) && directoryContainsEditableFile(root, child)) {
                            directories.add(new BrowserEntry(directoryPath, true, 0));
                        }
                    } else if (Files.isRegularFile(child) && resolveAllowedPath(relative) != null) {
                        files.add(new BrowserEntry(relative, false, Files.size(child)));
                    }
                } catch (Exception ignored) {
                }
            });
        } catch (Exception exception) {
            logger.warning("Failed to list editable policy directory " + currentPath + ": " + exception.getMessage());
        }

        Comparator<BrowserEntry> byName = Comparator.comparing(entry -> entryName(entry.relativePath()).toLowerCase(Locale.ROOT));
        directories.sort(byName);
        files.sort(byName);

        List<BrowserEntry> entries = new ArrayList<>();
        entries.addAll(directories);
        entries.addAll(files);
        return new DirectoryListing(currentPath, entries.stream().limit(maxEntries).toList());
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
            String visibleContent = redactProtectedValues(content);
            int totalPages = Math.max(1, (int) Math.ceil((double) visibleContent.length() / pageChars));
            int safePage = Math.max(0, Math.min(page, totalPages - 1));
            int start = safePage * pageChars;
            int end = Math.min(visibleContent.length(), start + pageChars);
            String pageContent = visibleContent.substring(start, end);
            return FilePreview.success(normalizeRelativePath(relativePath), visibleContent, pageContent, safePage, totalPages);
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
                long stagedId = stageProposal(refreshed, now);
                ValidationReport report = validateProposal(refreshed);
                if (report.hasFailures()) {
                    markStageFailed(stagedId, "Dry-run validation failed: " + report.failureSummary());
                } else {
                    applyValidatedChange(new StagedChange(stagedId, refreshed.id(), refreshed.relativePath(), refreshed.proposedContent()), report);
                }
                bridge.publishPolicyApproved(refreshed);
            } else {
                database.update("UPDATE proposals SET status = 'REJECTED' WHERE id = ?", statement -> Database.setLong(statement, 1, refreshed.id()));
            }
        }
    }

    public String forceChange(long proposalId) {
        refreshProposalScore(proposalId);
        Optional<ProposalRecord> proposal = proposalById(proposalId);
        if (proposal.isEmpty()) {
            return "No proposal exists with id `" + proposalId + "`.";
        }
        ProposalRecord record = proposal.get();
        Path target = resolveAllowedPath(record.relativePath());
        if (target == null) {
            return "Proposal target is no longer allowed by config: `" + record.relativePath() + "`.";
        }
        String validation = validateSyntax(record.relativePath(), record.proposedContent());
        if (validation != null) {
            return "Proposal cannot be forced because the replacement content is invalid: " + validation;
        }

        database.update("UPDATE proposals SET status = 'APPROVED' WHERE id = ?", statement -> Database.setLong(statement, 1, record.id()));
        long stagedId = existingStagedChange(record.id()).orElseGet(() -> stageProposal(record, Instant.now().toEpochMilli()));
        ValidationReport report = validateProposal(record);
        if (report.hasFailures()) {
            markStageFailed(stagedId, "Dry-run validation failed: " + report.failureSummary());
            return "Forced proposal `" + record.id() + "` to APPROVED after running validation and sham simulation, but implementation was blocked by dry-run validation. " + stagedStatusLine(record.id());
        }
        ApplyDecision decision = applyValidatedChange(new StagedChange(stagedId, record.id(), record.relativePath(), record.proposedContent()), report);
        return "Forced proposal `" + record.id() + "` to APPROVED after running validation and sham simulation. " + decision.message() + " " + stagedStatusLine(record.id());
    }

    public String stagedStatusLine(long proposalId) {
        return database.queryOne(
            """
            SELECT status, failure FROM staged_file_changes
            WHERE proposal_id = ?
            ORDER BY id DESC
            LIMIT 1
            """,
            statement -> Database.setLong(statement, 1, proposalId),
            resultSet -> {
                try {
                    String status = resultSet.getString("status");
                    String failure = resultSet.getString("failure");
                    return "Stage status: " + status + (failure == null || failure.isBlank() ? "" : " (" + failure + ")");
                } catch (Exception exception) {
                    throw new RuntimeException(exception);
                }
            }
        ).orElse("Stage status: none");
    }

    public ValidationReport validateProposal(long proposalId) {
        Optional<ProposalRecord> proposal = proposalById(proposalId);
        if (proposal.isEmpty()) {
            List<ValidationCheck> checks = List.of(new ValidationCheck("FAIL", "No proposal exists with id `" + proposalId + "`."));
            ValidationReport report = new ValidationReport(proposalId, null, null, debugLogPath(), checks);
            appendValidationLog(report);
            return report;
        }
        return validateProposal(proposal.get());
    }

    public ValidationReport validateProposal(ProposalRecord proposal) {
        List<ValidationCheck> checks = new ArrayList<>();
        Path logPath = debugLogPath();
        Path sandboxPath = null;

        checks.add(new ValidationCheck("INFO", "Dry-run validation started for proposal `" + proposal.id() + "`."));
        Path target = resolveAllowedPath(proposal.relativePath());
        if (target == null) {
            checks.add(new ValidationCheck("FAIL", "Target path is not allowed by the current policy config."));
            ValidationReport report = new ValidationReport(proposal.id(), proposal.relativePath(), null, logPath, checks);
            appendValidationLog(report);
            return report;
        }

        checks.add(new ValidationCheck("OK", "Target path is allowed: `" + proposal.relativePath() + "`."));
        if (!Files.isRegularFile(target)) {
            checks.add(new ValidationCheck("FAIL", "Target file does not exist or is not a regular file."));
        } else {
            checks.add(new ValidationCheck("OK", "Target file exists."));
        }

        try {
            if (Files.exists(target) && Files.size(target) > config.policyMaxFileBytes()) {
                checks.add(new ValidationCheck("FAIL", "Current file is larger than the configured max file size."));
            } else {
                checks.add(new ValidationCheck("OK", "Current file size is within the configured limit."));
            }
        } catch (Exception exception) {
            checks.add(new ValidationCheck("FAIL", "Could not read current file size: " + exception.getMessage()));
        }

        String current = "";
        try {
            if (Files.exists(target)) {
                current = Files.readString(target, StandardCharsets.UTF_8);
                ProtectedScan currentScan = scanProtectedValues(current);
                if (currentScan.total() > 0) {
                    checks.add(new ValidationCheck("OK", "Current file contains " + currentScan.total() + " protected value(s); they are redacted in Discord and locked during edits."));
                } else {
                    checks.add(new ValidationCheck("OK", "Current file has no protected secret/IP/port values."));
                }
            }
        } catch (Exception exception) {
            checks.add(new ValidationCheck("FAIL", "Could not read current file: " + exception.getMessage()));
        }

        ProtectedMerge protectedMerge = mergeProtectedValues(current, proposal.proposedContent());
        if (protectedMerge.success()) {
            ProtectedScan proposedScan = scanProtectedValues(protectedMerge.content());
            checks.add(new ValidationCheck("OK", "Protected values are unchanged. Redacted/locked value count: " + proposedScan.total() + "."));
        } else {
            checks.add(new ValidationCheck("FAIL", protectedMerge.message()));
        }

        String effectiveProposedContent = protectedMerge.content() == null ? proposal.proposedContent() : protectedMerge.content();
        String syntax = validateSyntax(proposal.relativePath(), effectiveProposedContent);
        if (syntax == null) {
            checks.add(new ValidationCheck("OK", "Proposed content parses as valid YAML/JSON."));
        } else {
            checks.add(new ValidationCheck("FAIL", syntax));
        }

        if (!current.isEmpty()) {
            DiffStats stats = diffStats(redactProtectedValues(current), redactProtectedValues(effectiveProposedContent));
            checks.add(new ValidationCheck(stats.changed() ? "OK" : "WARN", "Diff summary: +" + stats.addedLines() + " / -" + stats.removedLines() + "."));
        }

        try {
            Path sandboxRoot = plugin.getDataFolder().toPath().resolve("dry-run").resolve("proposal-" + proposal.id()).normalize();
            sandboxPath = sandboxRoot.resolve(normalizeRelativePath(proposal.relativePath())).normalize();
            if (!sandboxPath.startsWith(sandboxRoot)) {
                checks.add(new ValidationCheck("FAIL", "Sandbox path escaped the dry-run folder."));
            } else {
                Files.createDirectories(sandboxPath.getParent());
                Files.writeString(sandboxPath, effectiveProposedContent, StandardCharsets.UTF_8);
                checks.add(new ValidationCheck("OK", "Wrote sandbox copy: `" + plugin.getDataFolder().toPath().relativize(sandboxPath) + "`."));
                String sandboxContent = Files.readString(sandboxPath, StandardCharsets.UTF_8);
                String sandboxSyntax = validateSyntax(proposal.relativePath(), sandboxContent);
                checks.add(new ValidationCheck(sandboxSyntax == null ? "OK" : "FAIL", sandboxSyntax == null ? "Sandbox copy parses cleanly." : "Sandbox parse failed: " + sandboxSyntax));
            }
        } catch (Exception exception) {
            checks.add(new ValidationCheck("FAIL", "Could not write/read sandbox copy: " + exception.getMessage()));
        }

        try {
            Path probeRoot = plugin.getDataFolder().toPath().resolve("dry-run").resolve("write-probe");
            Files.createDirectories(probeRoot);
            Path probe = probeRoot.resolve("proposal-" + proposal.id() + ".tmp");
            Files.writeString(probe, "ok", StandardCharsets.UTF_8);
            Files.deleteIfExists(probe);
            checks.add(new ValidationCheck("OK", "Plugin dry-run folder is writable."));
        } catch (Exception exception) {
            checks.add(new ValidationCheck("FAIL", "Plugin dry-run folder is not writable: " + exception.getMessage()));
        }

        if (target.getParent() != null && Files.isWritable(target.getParent())) {
            checks.add(new ValidationCheck("OK", "Target parent folder appears writable."));
        } else {
            checks.add(new ValidationCheck("WARN", "Target parent folder does not appear writable from Java's permission check."));
        }

        inspectTargetPlugin(proposal.relativePath(), checks);
        runStaticPluginChecks(proposal.relativePath(), proposal.proposedContent(), checks);
        checks.add(new ValidationCheck("INFO", "No live reload command was executed during this dry-run."));

        ValidationReport report = new ValidationReport(proposal.id(), proposal.relativePath(), sandboxPath, logPath, checks);
        appendValidationLog(report);
        return report;
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

    private ApplyDecision applyValidatedChange(StagedChange change, ValidationReport report) {
        Optional<String> reloadCommand = report.reloadCommand();
        if (report.reloadSimulationPassed() && reloadCommand.isPresent()) {
            boolean applied = applyStagedChange(
                change,
                "APPLIED",
                "Sham simulation passed; reload command dispatched: /" + reloadCommand.get()
            );
            if (!applied) {
                return new ApplyDecision(false, "File write failed after validation.");
            }
            dispatchReloadCommand(change, reloadCommand.get());
            return new ApplyDecision(true, "Sham simulation passed, the file was applied, and `/" + reloadCommand.get() + "` was dispatched.");
        }
        if (report.reloadSimulationPassed()) {
            Optional<String> pluginName = targetPluginName(change.relativePath());
            if (pluginName.isPresent()) {
                boolean applied = applyStagedChange(
                    change,
                    "APPLIED",
                    "Sham simulation passed; generic plugin reload dispatched for " + pluginName.get()
                );
                if (!applied) {
                    return new ApplyDecision(false, "File write failed after validation.");
                }
                dispatchGenericPluginReload(change, pluginName.get());
                return new ApplyDecision(true, "Sham simulation passed, the file was applied, and a generic reload was dispatched for `" + pluginName.get() + "`.");
            }
        }

        String reason;
        if (reloadCommand.isEmpty()) {
            reason = "No native reload command was detected.";
        } else {
            reason = "Sham simulation result was `" + report.reloadSimulationResult() + "`.";
        }
        boolean applied = applyStagedChange(
            change,
            "NEEDS_RESTART",
            reason + " Live reload skipped; file was written and will take effect on the next normal restart."
        );
        if (!applied) {
            return new ApplyDecision(false, "File write failed after validation.");
        }
        return new ApplyDecision(true, reason + " Live reload skipped; the file was written and will take effect on the next normal restart.");
    }

    public Optional<ProposalRecord> proposalById(long id) {
        return database.queryOne(
            "SELECT * FROM proposals WHERE id = ?",
            statement -> Database.setLong(statement, 1, id),
            this::mapProposal
        );
    }

    public Optional<ProposalRecord> proposalByThread(String threadId) {
        return database.queryOne(
            "SELECT * FROM proposals WHERE poll_thread_id = ?",
            statement -> Database.setString(statement, 1, threadId),
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

    private Optional<Long> existingStagedChange(long proposalId) {
        return database.queryOne(
            """
            SELECT id FROM staged_file_changes
            WHERE proposal_id = ?
            ORDER BY id DESC
            LIMIT 1
            """,
            statement -> Database.setLong(statement, 1, proposalId),
            resultSet -> {
                try {
                    return resultSet.getLong("id");
                } catch (Exception exception) {
                    throw new RuntimeException(exception);
                }
            }
        );
    }

    private long stageProposal(ProposalRecord proposal, long now) {
        return database.insert(
            """
            INSERT INTO staged_file_changes(proposal_id, relative_path, proposed_content, status, created_at)
            VALUES(?, ?, ?, 'PENDING', ?)
            """,
            statement -> {
                Database.setLong(statement, 1, proposal.id());
                Database.setString(statement, 2, proposal.relativePath());
                Database.setString(statement, 3, proposal.proposedContent());
                Database.setLong(statement, 4, now);
            }
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
        if (!isInEditableRoot(normalizedRelative)) {
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

    private Path resolveAllowedDirectory(String relativeDirectory) {
        String normalizedRelative = normalizeDirectoryPath(relativeDirectory);
        if (!isDirectoryInEditableScope(normalizedRelative)) {
            return null;
        }
        if (!normalizedRelative.isBlank() && isExcluded(normalizedRelative)) {
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

    private String normalizeDirectoryPath(String relativePath) {
        String normalized = normalizeRelativePath(relativePath);
        if (normalized.isBlank()) {
            return "";
        }
        return normalized.endsWith("/") ? normalized : normalized + "/";
    }

    private boolean isExcluded(String relativePath) {
        String lower = normalizeRelativePath(relativePath).toLowerCase(Locale.ROOT);
        for (String excluded : config.excludedPaths()) {
            String excludedNormalized = normalizeRelativePath(excluded).toLowerCase(Locale.ROOT);
            if (excludedNormalized.isBlank()) {
                continue;
            }
            if (excludedNormalized.contains("*") || excludedNormalized.contains("?")) {
                if (Pattern.compile(globToRegex(excludedNormalized)).matcher(lower).matches()) {
                    return true;
                }
                continue;
            }
            if (excludedNormalized.endsWith("/") && lower.startsWith(excludedNormalized)) {
                return true;
            }
            if (lower.equals(excludedNormalized) || lower.startsWith(excludedNormalized + "/")) {
                return true;
            }
        }
        return false;
    }

    private boolean isInEditableRoot(String relativePath) {
        String lower = normalizeRelativePath(relativePath).toLowerCase(Locale.ROOT);
        for (String root : config.editableRoots()) {
            String editableRoot = normalizeDirectoryPath(root).toLowerCase(Locale.ROOT);
            if (!editableRoot.isBlank() && lower.startsWith(editableRoot)) {
                return true;
            }
        }
        return false;
    }

    private boolean isDirectoryInEditableScope(String relativeDirectory) {
        String lower = normalizeDirectoryPath(relativeDirectory).toLowerCase(Locale.ROOT);
        if (lower.isBlank()) {
            return true;
        }
        for (String root : config.editableRoots()) {
            String editableRoot = normalizeDirectoryPath(root).toLowerCase(Locale.ROOT);
            if (!editableRoot.isBlank() && (lower.startsWith(editableRoot) || editableRoot.startsWith(lower))) {
                return true;
            }
        }
        return false;
    }

    private String globToRegex(String glob) {
        StringBuilder regex = new StringBuilder("^");
        for (int i = 0; i < glob.length(); i++) {
            char current = glob.charAt(i);
            if (current == '*') {
                boolean doublestar = i + 1 < glob.length() && glob.charAt(i + 1) == '*';
                if (doublestar) {
                    regex.append(".*");
                    i++;
                } else {
                    regex.append("[^/]*");
                }
            } else if (current == '?') {
                regex.append("[^/]");
            } else if ("\\.[]{}()+-^$|".indexOf(current) >= 0) {
                regex.append('\\').append(current);
            } else {
                regex.append(current);
            }
        }
        return regex.append("$").toString();
    }

    private boolean directoryContainsEditableFile(Path root, Path directory) {
        boolean[] found = {false};
        try {
            Files.walkFileTree(directory, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    if (!dir.equals(directory) && isExcluded(root.relativize(dir).toString() + "/")) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    return found[0] ? FileVisitResult.TERMINATE : FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    String relative = normalizeRelativePath(root.relativize(file).toString());
                    if (resolveAllowedPath(relative) != null) {
                        found[0] = true;
                        return FileVisitResult.TERMINATE;
                    }
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (Exception ignored) {
        }
        return found[0];
    }

    private String entryName(String relativePath) {
        String normalized = normalizeRelativePath(relativePath).replaceFirst("/$", "");
        int slash = normalized.lastIndexOf('/');
        return slash < 0 ? normalized : normalized.substring(slash + 1);
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

    private String redactProtectedValues(String content) {
        StringBuilder redacted = new StringBuilder();
        String[] lines = content.split("\\R", -1);
        for (int i = 0; i < lines.length; i++) {
            redacted.append(redactProtectedLine(lines[i]));
            if (i < lines.length - 1) {
                redacted.append('\n');
            }
        }
        return redacted.toString();
    }

    private String redactProtectedLine(String line) {
        ProtectedLine protectedLine = protectedLine(line);
        if (protectedLine.protectedValue()) {
            return protectedLine.prefix() + redactedScalar(protectedLine.value());
        }
        String redacted = IPV4_PATTERN.matcher(line).replaceAll(REDACTION);
        return HOST_PORT_PATTERN.matcher(redacted).replaceAll(REDACTION);
    }

    private ProtectedMerge mergeProtectedValues(String currentContent, String proposedContent) {
        String[] currentLines = currentContent.split("\\R", -1);
        String[] proposedLines = proposedContent.split("\\R", -1);
        Map<String, Deque<String>> currentProtectedByKey = new HashMap<>();
        Deque<String> currentInlineProtected = new ArrayDeque<>();
        for (String currentLine : currentLines) {
            ProtectedLine protectedLine = protectedLine(currentLine);
            if (!protectedLine.protectedValue()) {
                continue;
            }
            if (protectedLine.key().isBlank()) {
                currentInlineProtected.addLast(currentLine);
            } else {
                currentProtectedByKey.computeIfAbsent(protectedLine.key(), ignored -> new ArrayDeque<>()).addLast(currentLine);
            }
        }

        StringBuilder merged = new StringBuilder();
        for (int i = 0; i < proposedLines.length; i++) {
            String proposedLine = proposedLines[i];
            ProtectedLine proposedProtected = protectedLine(proposedLine);
            String lineToWrite = proposedLine;

            if (proposedProtected.protectedValue()) {
                if (proposedProtected.key().isBlank()) {
                    if (currentInlineProtected.removeFirstOccurrence(proposedLine)) {
                        lineToWrite = proposedLine;
                    } else if (proposedProtected.redactionPlaceholder() && !currentInlineProtected.isEmpty()) {
                        lineToWrite = currentInlineProtected.removeFirst();
                    } else {
                        return ProtectedMerge.failure("New protected IP/host-port value on line " + (i + 1) + " is not allowed through Discord.");
                    }
                } else {
                    Deque<String> currentValues = currentProtectedByKey.get(proposedProtected.key());
                    if (currentValues == null || currentValues.isEmpty()) {
                        if (proposedProtected.redactionPlaceholder()) {
                            return ProtectedMerge.failure("Protected placeholder on line " + (i + 1) + " does not match an existing locked field.");
                        }
                        return ProtectedMerge.failure("New protected " + proposedProtected.category() + " value on line " + (i + 1) + " is not allowed through Discord.");
                    }
                    if (proposedProtected.redactionPlaceholder()) {
                        lineToWrite = currentValues.removeFirst();
                    } else if (currentValues.removeFirstOccurrence(proposedLine)) {
                        lineToWrite = proposedLine;
                    } else {
                        return ProtectedMerge.failure("Protected value on line " + (i + 1) + " cannot be changed. Leave the `" + REDACTION + "` placeholder in place.");
                    }
                }
            } else if (containsProtectedInlineValue(proposedLine) && !containsOnlyRedactionInlineValue(proposedLine)) {
                return ProtectedMerge.failure("New protected IP/host-port value on line " + (i + 1) + " is not allowed through Discord.");
            }

            merged.append(lineToWrite);
            if (i < proposedLines.length - 1) {
                merged.append('\n');
            }
        }
        for (Deque<String> remaining : currentProtectedByKey.values()) {
            if (!remaining.isEmpty()) {
                return ProtectedMerge.failure("An existing protected value was removed. Protected secret/IP/port fields must stay in place.");
            }
        }
        if (!currentInlineProtected.isEmpty()) {
            return ProtectedMerge.failure("An existing protected IP/host-port value was removed. Protected values must stay in place.");
        }
        return ProtectedMerge.success(merged.toString());
    }

    private ProtectedScan scanProtectedValues(String content) {
        int secret = 0;
        int address = 0;
        int port = 0;
        int inline = 0;
        for (String line : content.split("\\R", -1)) {
            ProtectedLine protectedLine = protectedLine(line);
            if (protectedLine.protectedValue()) {
                switch (protectedLine.category()) {
                    case "port" -> port++;
                    case "address" -> address++;
                    default -> secret++;
                }
            } else if (containsProtectedInlineValue(line)) {
                inline++;
            }
        }
        return new ProtectedScan(secret, address, port, inline);
    }

    private ProtectedLine protectedLine(String line) {
        String trimmed = line.trim();
        if (trimmed.isBlank() || trimmed.startsWith("#") || trimmed.startsWith("//")) {
            return ProtectedLine.none();
        }
        if (trimmed.startsWith("- ")) {
            return ProtectedLine.none();
        }
        var matcher = KEY_VALUE_PATTERN.matcher(line);
        if (!matcher.matches()) {
            return ProtectedLine.none();
        }

        String prefix = matcher.group(1);
        String key = matcher.group(2).toLowerCase(Locale.ROOT);
        String value = matcher.group(3).trim();
        String scalar = scalarValue(value);
        if (scalar.isBlank() || isEmptyContainer(scalar) || looksLikeSafePathOrPattern(scalar)) {
            return containsProtectedInlineValue(value)
                ? new ProtectedLine(prefix, key, value, "address", false, true)
                : ProtectedLine.none();
        }
        boolean placeholder = scalar.equals(REDACTION);
        String compactKey = key.replace("_", "").replace("-", "").replace(".", "");

        if (matchesConfiguredSecretPattern(line) || containsAny(compactKey, SECRET_KEY_PARTS)) {
            return new ProtectedLine(prefix, key, value, "secret", placeholder, true);
        }
        if (isPortKey(key) && looksLikePortValue(value)) {
            return new ProtectedLine(prefix, key, value, "port", placeholder, true);
        }
        if (isIpKey(key) && !scalar.isBlank()) {
            return new ProtectedLine(prefix, key, value, "address", placeholder, true);
        }
        if (containsAny(compactKey, ADDRESS_KEY_PARTS) && containsProtectedInlineValue(value)) {
            return new ProtectedLine(prefix, key, value, "address", placeholder, true);
        }
        return ProtectedLine.none();
    }

    private boolean matchesConfiguredSecretPattern(String line) {
        for (String pattern : config.secretPatterns()) {
            if (Pattern.compile(pattern).matcher(line).find()) {
                return true;
            }
        }
        return false;
    }

    private boolean containsAny(String text, Set<String> needles) {
        for (String needle : needles) {
            if (text.contains(needle)) {
                return true;
            }
        }
        return false;
    }

    private boolean isPortKey(String key) {
        String lower = key.toLowerCase(Locale.ROOT);
        return lower.equals("port") || lower.endsWith("port") || lower.contains("_port") || lower.contains("-port") || lower.contains(".port");
    }

    private boolean isIpKey(String key) {
        String lower = key.toLowerCase(Locale.ROOT);
        return lower.equals("ip") || lower.endsWith("_ip") || lower.endsWith("-ip") || lower.endsWith(".ip")
            || lower.contains("ip_address") || lower.contains("ip-address") || lower.contains("ip.address");
    }

    private boolean looksLikePortValue(String value) {
        String unquoted = scalarValue(value);
        try {
            int port = Integer.parseInt(unquoted);
            return port >= 1 && port <= 65535;
        } catch (NumberFormatException ignored) {
            return false;
        }
    }

    private boolean isEmptyContainer(String value) {
        return "[]".equals(value) || "{}".equals(value);
    }

    private boolean looksLikeSafePathOrPattern(String value) {
        String lower = value.toLowerCase(Locale.ROOT);
        if (lower.contains("\\s*") || lower.contains("[:=]") || lower.startsWith("(?")) {
            return true;
        }
        return lower.contains("/") || lower.contains("\\") || lower.endsWith(".yml") || lower.endsWith(".yaml")
            || lower.endsWith(".json") || lower.endsWith(".txt") || lower.endsWith(".toml") || lower.endsWith(".db")
            || lower.contains("*");
    }

    private boolean containsProtectedInlineValue(String value) {
        return IPV4_PATTERN.matcher(value).find() || HOST_PORT_PATTERN.matcher(value).find();
    }

    private boolean containsOnlyRedactionInlineValue(String value) {
        return value.contains(REDACTION) && !IPV4_PATTERN.matcher(value).find() && !HOST_PORT_PATTERN.matcher(value).find();
    }

    private String redactedScalar(String value) {
        String trimmed = value.trim();
        String suffix = trimmed.endsWith(",") ? "," : "";
        String withoutComma = suffix.isEmpty() ? trimmed : trimmed.substring(0, trimmed.length() - 1).trim();
        if (withoutComma.startsWith("\"")) {
            return "\"" + REDACTION + "\"" + suffix;
        }
        if (withoutComma.startsWith("'")) {
            return "'" + REDACTION + "'" + suffix;
        }
        return REDACTION + suffix;
    }

    private String scalarValue(String value) {
        String trimmed = value.trim();
        if (trimmed.endsWith(",")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1).trim();
        }
        int comment = trimmed.indexOf(" #");
        if (comment >= 0) {
            trimmed = trimmed.substring(0, comment).trim();
        }
        if ((trimmed.startsWith("\"") && trimmed.endsWith("\"")) || (trimmed.startsWith("'") && trimmed.endsWith("'"))) {
            return trimmed.substring(1, trimmed.length() - 1).trim();
        }
        return trimmed;
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

    private Path debugLogPath() {
        return plugin.getDataFolder().toPath().resolve("policy-debug.log").normalize();
    }

    private void appendValidationLog(ValidationReport report) {
        try {
            Files.createDirectories(report.logPath().getParent());
            List<String> lines = new ArrayList<>();
            lines.add("[" + Instant.now() + "] proposal=" + report.proposalId() + " file=" + (report.relativePath() == null ? "none" : report.relativePath()) + " result=" + report.result());
            for (ValidationCheck check : report.checks()) {
                lines.add("  [" + check.status() + "] " + check.message());
            }
            lines.add("");
            Files.write(report.logPath(), lines, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (Exception exception) {
            logger.warning("Failed to write policy dry-run log: " + exception.getMessage());
        }
    }

    private DiffStats diffStats(String current, String proposed) {
        int added = 0;
        int removed = 0;
        String diff = DiffUtil.unifiedDiff(current, proposed, 0);
        for (String line : diff.split("\\R")) {
            if (line.startsWith("+") && !line.startsWith("+++")) {
                added++;
            } else if (line.startsWith("-") && !line.startsWith("---")) {
                removed++;
            }
        }
        return new DiffStats(added, removed);
    }

    private void inspectTargetPlugin(String relativePath, List<ValidationCheck> checks) {
        Optional<String> folderName = pluginFolderName(relativePath);
        if (folderName.isEmpty()) {
            checks.add(new ValidationCheck("INFO", "Target file is not inside a plugin data folder, so plugin jar metadata was not inspected."));
            return;
        }

        checks.add(new ValidationCheck("INFO", "Detected plugin data folder: `" + folderName.get() + "`."));
        Optional<PluginJarInfo> jarInfo = findPluginJar(folderName.get());
        if (jarInfo.isEmpty()) {
            checks.add(new ValidationCheck("WARN", "No matching plugin jar was found for `" + folderName.get() + "`. Add a path under `policy.pluginJarSearchPaths` if the jar is stored elsewhere."));
            return;
        }

        PluginJarInfo info = jarInfo.get();
        checks.add(new ValidationCheck("OK", "Matched plugin jar `" + info.jarName() + "` for plugin `" + info.name() + "`" + (info.version().isBlank() ? "." : " v" + info.version() + ".")));
        if (info.foliaSupported()) {
            checks.add(new ValidationCheck("OK", "Plugin metadata declares Folia support."));
        } else {
            checks.add(new ValidationCheck("WARN", "Plugin metadata does not declare Folia support."));
        }
        if (info.commands().isEmpty()) {
            checks.add(new ValidationCheck("WARN", "No commands were declared in plugin metadata."));
        } else {
            checks.add(new ValidationCheck("INFO", "Declared commands: " + String.join(", ", info.commands())));
        }
        Set<String> hints = new LinkedHashSet<>(info.reloadHints());
        knownReloadHint(folderName.get()).ifPresent(hints::add);
        if (hints.isEmpty()) {
            checks.add(new ValidationCheck("WARN", "No reload command hint was detected. This dry-run will not know whether the plugin can reload live."));
        } else {
            checks.add(new ValidationCheck("INFO", "Reload command hints, not executed: " + String.join(", ", hints)));
        }
        runShamReloadTerminal(folderName.get(), info, checks);
        checks.add(new ValidationCheck("INFO", "Generic plugin-manager reloads are not treated as safe on Folia; they can disable plugins, unregister commands/listeners, and touch classloaders. Prefer plugin-native reload commands when available."));
        knownReloadRisks(folderName.get()).forEach(message -> checks.add(new ValidationCheck("WARN", message)));
    }

    private void runStaticPluginChecks(String relativePath, String proposedContent, List<ValidationCheck> checks) {
        String lower = relativePath.toLowerCase(Locale.ROOT);
        if ((lower.endsWith(".yml") || lower.endsWith(".yaml")) && proposedContent.contains("\t")) {
            checks.add(new ValidationCheck("WARN", "YAML contains tab characters. Bukkit YAML may reject tabs depending on placement."));
        }
        if (lower.startsWith("plugins/viaversion/") && !proposedContent.toLowerCase(Locale.ROOT).contains("config-version")) {
            checks.add(new ValidationCheck("WARN", "ViaVersion config files commonly include `config-version`; absence may be normal for non-main files."));
        }
        if (lower.startsWith("plugins/viarewind/") && !proposedContent.toLowerCase(Locale.ROOT).contains("config-version")) {
            checks.add(new ValidationCheck("WARN", "ViaRewind config files commonly include `config-version`; absence may be normal for non-main files."));
        }
        if (lower.startsWith("plugins/scoreboardchatshop/")) {
            checks.add(new ValidationCheck("INFO", "ScoreboardChatShop-specific schema validation is not defined yet; generic YAML/JSON checks were used."));
        }
        if (lower.startsWith("plugins/puddlesplus/")) {
            checks.add(new ValidationCheck("INFO", "PuddlesPlus-specific schema validation is not defined yet; generic YAML/JSON checks were used."));
        }
    }

    private Optional<String> pluginFolderName(String relativePath) {
        String normalized = normalizeRelativePath(relativePath);
        String[] parts = normalized.split("/");
        if (parts.length >= 2 && "plugins".equalsIgnoreCase(parts[0])) {
            return Optional.of(parts[1]);
        }
        return Optional.empty();
    }

    private Optional<PluginJarInfo> findPluginJar(String folderName) {
        String normalizedFolder = normalizeName(folderName);
        Optional<PluginJarInfo> fallback = Optional.empty();
        for (Path jar : pluginJarPaths()) {
            Optional<PluginJarInfo> info = inspectPluginJar(jar);
            if (info.isEmpty()) {
                continue;
            }
            String normalizedPlugin = normalizeName(info.get().name());
            String normalizedJar = normalizeName(jar.getFileName().toString().replaceFirst("(?i)\\.jar$", ""));
            if (normalizedPlugin.equals(normalizedFolder)) {
                return info;
            }
            if (fallback.isEmpty() && (normalizedJar.contains(normalizedFolder) || normalizedFolder.contains(normalizedJar))) {
                fallback = info;
            }
        }
        return fallback;
    }

    private Optional<String> targetPluginName(String relativePath) {
        Optional<String> folderName = pluginFolderName(relativePath);
        if (folderName.isEmpty()) {
            return Optional.empty();
        }
        return findPluginJar(folderName.get())
            .map(PluginJarInfo::name)
            .or(() -> folderName);
    }

    private List<Path> pluginJarPaths() {
        List<Path> searchRoots = new ArrayList<>();
        searchRoots.add(plugin.serverRoot().resolve("plugins"));
        for (String configuredPath : config.policyPluginJarSearchPaths()) {
            if (configuredPath == null || configuredPath.isBlank()) {
                continue;
            }
            Path path = Path.of(configuredPath);
            searchRoots.add(path.isAbsolute() ? path : plugin.serverRoot().resolve(path));
        }

        List<Path> jars = new ArrayList<>();
        for (Path root : searchRoots) {
            if (!Files.isDirectory(root)) {
                continue;
            }
            try (var stream = Files.list(root)) {
                stream.filter(path -> path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".jar"))
                    .forEach(jars::add);
            } catch (Exception exception) {
                logger.warning("Failed to scan plugin jar path " + root + ": " + exception.getMessage());
            }
        }
        return jars;
    }

    private List<PluginJarInfo> inspectAvailablePluginJars() {
        List<PluginJarInfo> plugins = new ArrayList<>();
        for (Path jar : pluginJarPaths()) {
            inspectPluginJar(jar).ifPresent(plugins::add);
        }
        return plugins;
    }

    private Optional<PluginJarInfo> inspectPluginJar(Path jarPath) {
        try (JarFile jar = new JarFile(jarPath.toFile())) {
            var entry = Optional.ofNullable(jar.getEntry("plugin.yml")).orElse(jar.getEntry("paper-plugin.yml"));
            if (entry == null) {
                return Optional.empty();
            }
            YamlConfiguration yaml = new YamlConfiguration();
            try (InputStream inputStream = jar.getInputStream(entry)) {
                yaml.loadFromString(new String(inputStream.readAllBytes(), StandardCharsets.UTF_8));
            }
            String name = yaml.getString("name", jarPath.getFileName().toString().replaceFirst("(?i)\\.jar$", ""));
            String version = yaml.getString("version", "");
            boolean foliaSupported = yaml.getBoolean("folia-supported", false);
            List<String> commands = new ArrayList<>();
            List<String> reloadHints = new ArrayList<>();
            List<String> depend = yaml.getStringList("depend");
            List<String> softDepend = yaml.getStringList("softdepend");
            List<String> loadBefore = yaml.getStringList("loadbefore");
            List<String> jarSignals = scanJarSignals(jar);
            ConfigurationSection commandSection = yaml.getConfigurationSection("commands");
            if (commandSection != null) {
                for (String command : commandSection.getKeys(false)) {
                    commands.add("/" + command);
                    ConfigurationSection details = commandSection.getConfigurationSection(command);
                    if (command.toLowerCase(Locale.ROOT).contains("reload")) {
                        reloadHints.add("/" + command);
                    }
                    if (details != null) {
                        String description = details.getString("description", "");
                        if (description.toLowerCase(Locale.ROOT).contains("reload")) {
                            reloadHints.add("/" + command);
                        }
                        for (String alias : details.getStringList("aliases")) {
                            if (alias.toLowerCase(Locale.ROOT).contains("reload")) {
                                reloadHints.add("/" + alias);
                            }
                        }
                    }
                }
            }
            commands.sort(String::compareToIgnoreCase);
            return Optional.of(new PluginJarInfo(jarPath.getFileName().toString(), name, version, foliaSupported, commands, reloadHints, depend, softDepend, loadBefore, jarSignals));
        } catch (Exception exception) {
            logger.fine("Could not inspect plugin jar " + jarPath + ": " + exception.getMessage());
            return Optional.empty();
        }
    }

    private void runShamReloadTerminal(String folderName, PluginJarInfo info, List<ValidationCheck> checks) {
        List<String> lines = new ArrayList<>();
        String normalizedTarget = normalizeName(info.name());
        List<PluginJarInfo> availablePlugins = inspectAvailablePluginJars();
        List<String> hardDependents = new ArrayList<>();
        List<String> softDependents = new ArrayList<>();
        for (PluginJarInfo other : availablePlugins) {
            if (normalizeName(other.name()).equals(normalizedTarget)) {
                continue;
            }
            if (containsPluginName(other.depend(), normalizedTarget)) {
                hardDependents.add(other.name());
            }
            if (containsPluginName(other.softDepend(), normalizedTarget) || containsPluginName(other.loadBefore(), normalizedTarget)) {
                softDependents.add(other.name());
            }
        }
        hardDependents.sort(String::compareToIgnoreCase);
        softDependents.sort(String::compareToIgnoreCase);

        Set<String> hints = new LinkedHashSet<>(info.reloadHints());
        knownReloadHint(folderName).ifPresent(hints::add);
        List<String> risks = knownReloadRisks(folderName);
        List<String> recentLogSignals = recentServerLogSignals(folderName, info.name());
        ReloadSimulation simulation = simulateReloadOutcome(info, hardDependents, softDependents, hints, risks, recentLogSignals);

        lines.add("$ election-sim reload --target " + info.name());
        lines.add("mode=sham; no JVM started; no RCON used; no live plugin disabled or enabled");
        lines.add("read plugin descriptor: " + info.jarName() + " -> " + info.name() + (info.version().isBlank() ? "" : " " + info.version()));
        lines.add("folia-supported=" + info.foliaSupported());
        if (!info.commands().isEmpty()) {
            lines.add("declared commands to preserve after reload: " + String.join(", ", info.commands()));
        }
        if (!info.depend().isEmpty()) {
            lines.add("declared hard dependencies: " + String.join(", ", info.depend()));
        }
        if (!info.softDepend().isEmpty()) {
            lines.add("declared soft dependencies/hooks: " + String.join(", ", info.softDepend()));
        }
        if (!hardDependents.isEmpty()) {
            lines.add("reverse hard dependents that may break if target unloads: " + String.join(", ", hardDependents));
        }
        if (!softDependents.isEmpty()) {
            lines.add("reverse soft dependents/hooks to watch: " + String.join(", ", softDependents));
        }
        if (!info.jarSignals().isEmpty()) {
            lines.add("bytecode/string scan signals: " + String.join(", ", info.jarSignals()));
        }
        if (!recentLogSignals.isEmpty()) {
            lines.add("recent live-log memory: " + String.join(" | ", recentLogSignals));
        }
        if (hints.isEmpty()) {
            lines.add("reload plan: no native reload command known; simulated result=restart-preferred");
        } else {
            lines.add("reload plan: prefer native command(s) " + String.join(", ", hints));
        }
        lines.add("simulate: capture baseline log markers before apply");
        lines.add("simulate: apply proposed file to imaginary server tree");
        lines.add("simulate: validate plugin would still see target file and parent folder");
        if (hints.isEmpty()) {
            lines.add("simulate: generic unload/load would unregister commands, listeners, services, and classloader references");
        } else {
            lines.add("simulate: native reload would avoid plugin unload when supported");
        }
        if (!hardDependents.isEmpty() || !softDependents.isEmpty()) {
            lines.add("simulate: dependent/plugin-hook callbacks may re-run after reload");
        }
        if (!risks.isEmpty()) {
            lines.add("learned risk model: warning; " + String.join(" | ", risks));
        } else if (!hardDependents.isEmpty() || !softDependents.isEmpty()) {
            lines.add("learned risk model: caution; dependency or hook relationships exist");
        } else {
            lines.add("learned risk model: no known reload-specific failure pattern");
        }
        lines.add("simulated terminal result: " + simulation.result() + " (score=" + simulation.score() + ")");
        simulation.reasons().forEach(reason -> lines.add("simulated reason: " + reason));
        lines.add("simulated terminal complete; this is a prediction, not proof of runtime safety");

        checks.add(new ValidationCheck("INFO", "Sham reload simulation generated a terminal-style transcript in `policy-debug.log`."));
        if (!"PASS".equals(simulation.result())) {
            checks.add(new ValidationCheck("WARN", "Sham reload simulation result: " + simulation.result() + ". Review `policy-debug.log` before applying live reload."));
        }
        lines.forEach(line -> checks.add(new ValidationCheck("SIM", line)));
    }

    private List<String> scanJarSignals(JarFile jar) {
        Map<String, List<String>> signatures = Map.ofEntries(
            Map.entry("ProtocolLib/packet hook", List.of("com/comphenix/protocol", "ProtocolLibrary", "PacketAdapter", "PacketListener")),
            Map.entry("PacketEvents hook", List.of("com/github/retrooper/packetevents", "PacketEvents")),
            Map.entry("PlaceholderAPI hook", List.of("me/clip/placeholderapi", "PlaceholderAPI", "PlaceholderExpansion")),
            Map.entry("LuckPerms hook", List.of("net/luckperms/api", "LuckPermsProvider")),
            Map.entry("Vault service hook", List.of("net/milkbowl/vault", "RegisteredServiceProvider")),
            Map.entry("Bukkit scheduler", List.of("BukkitScheduler", "runTaskTimer", "scheduleSync", "runTaskLater")),
            Map.entry("Folia scheduler", List.of("RegionScheduler", "GlobalRegionScheduler", "AsyncScheduler")),
            Map.entry("plugin lifecycle", List.of("onEnable", "onDisable", "reloadConfig", "saveDefaultConfig")),
            Map.entry("database connection", List.of("jdbc:", "HikariDataSource", "SQLite", "mysql")),
            Map.entry("world/entity access", List.of("getWorld", "getPlayers", "getOnlinePlayers", "LivingEntity", "PlayerInteractEvent"))
        );
        Set<String> found = new LinkedHashSet<>();
        try {
            var entries = jar.entries();
            while (entries.hasMoreElements()) {
                var entry = entries.nextElement();
                if (entry.isDirectory() || !entry.getName().endsWith(".class") || entry.getSize() > 1_000_000) {
                    continue;
                }
                byte[] bytes;
                try (InputStream inputStream = jar.getInputStream(entry)) {
                    bytes = inputStream.readAllBytes();
                }
                String haystack = new String(bytes, StandardCharsets.ISO_8859_1);
                for (Map.Entry<String, List<String>> signature : signatures.entrySet()) {
                    if (found.contains(signature.getKey())) {
                        continue;
                    }
                    for (String needle : signature.getValue()) {
                        if (haystack.contains(needle)) {
                            found.add(signature.getKey());
                            break;
                        }
                    }
                }
            }
        } catch (Exception exception) {
            logger.fine("Could not scan jar bytecode strings for " + jar.getName() + ": " + exception.getMessage());
        }
        return new ArrayList<>(found);
    }

    private List<String> recentServerLogSignals(String folderName, String pluginName) {
        Path latestLog = plugin.serverRoot().resolve("logs").resolve("latest.log").normalize();
        if (!Files.isRegularFile(latestLog)) {
            return List.of();
        }
        String normalizedFolder = normalizeName(folderName);
        String normalizedPlugin = normalizeName(pluginName);
        Set<String> signals = new LinkedHashSet<>();
        try {
            String text = Files.readString(latestLog, StandardCharsets.UTF_8);
            String[] lines = text.split("\\R");
            int start = Math.max(0, lines.length - 500);
            for (int index = start; index < lines.length; index++) {
                String line = lines[index];
                String normalizedLine = normalizeName(line);
                if (!normalizedLine.contains(normalizedFolder) && !normalizedLine.contains(normalizedPlugin)) {
                    continue;
                }
                String lower = line.toLowerCase(Locale.ROOT);
                if (lower.contains("exception") || lower.contains("error") || lower.contains("failed")) {
                    signals.add("recent error/exception line mentioning " + pluginName);
                }
                if (lower.contains("warn")) {
                    signals.add("recent warning line mentioning " + pluginName);
                }
                if (lower.contains("already loaded")) {
                    signals.add("recent already-loaded reload warning");
                }
                if (lower.contains("cannot add effects") || lower.contains("asynchronously")) {
                    signals.add("recent Folia async/threading warning");
                }
                if (signals.size() >= 4) {
                    break;
                }
            }
        } catch (Exception exception) {
            logger.fine("Could not scan recent server log for reload simulation: " + exception.getMessage());
        }
        return new ArrayList<>(signals);
    }

    private ReloadSimulation simulateReloadOutcome(PluginJarInfo info, List<String> hardDependents, List<String> softDependents, Set<String> hints, List<String> knownRisks, List<String> recentLogSignals) {
        int score = 0;
        List<String> reasons = new ArrayList<>();
        if (!info.foliaSupported()) {
            score += 30;
            reasons.add("plugin metadata does not declare Folia support");
        }
        if (hints.isEmpty()) {
            score += 25;
            reasons.add("no native reload command was detected");
        } else {
            score -= 10;
            reasons.add("native reload command hint exists");
        }
        if (!hardDependents.isEmpty()) {
            score += 35;
            reasons.add("other plugins hard-depend on this plugin");
        }
        if (!softDependents.isEmpty()) {
            score += 20;
            reasons.add("other plugins soft-hook into this plugin");
        }
        if (!knownRisks.isEmpty()) {
            score += 35;
            reasons.add("known staged-smoke reload risk exists for this plugin family");
        }
        if (!recentLogSignals.isEmpty()) {
            score += 20;
            reasons.add("recent live server log already contains warning/error markers for this plugin");
        }
        for (String signal : info.jarSignals()) {
            if (signal.contains("ProtocolLib") || signal.contains("PacketEvents")) {
                score += 20;
                reasons.add("packet/protocol hooks tend to be reload-sensitive");
            } else if (signal.contains("PlaceholderAPI") || signal.contains("LuckPerms") || signal.contains("Vault")) {
                score += 12;
                reasons.add("external service/hook integration may re-register during reload");
            } else if (signal.contains("Bukkit scheduler") && !info.jarSignals().contains("Folia scheduler")) {
                score += 15;
                reasons.add("Bukkit scheduler usage without obvious Folia scheduler signal");
            } else if (signal.contains("database")) {
                score += 10;
                reasons.add("database resources may need clean shutdown/reopen");
            } else if (signal.contains("world/entity")) {
                score += 8;
                reasons.add("world/entity API usage can be thread-sensitive on Folia");
            }
        }
        if (reasons.isEmpty()) {
            reasons.add("no specific reload risk signals detected");
        }

        String result;
        if (score >= 70) {
            result = "RESTART_RECOMMENDED";
        } else if (score >= 35) {
            result = "PASS_WITH_WARNINGS";
        } else {
            result = "PASS";
        }
        return new ReloadSimulation(result, Math.max(score, 0), reasons.stream().distinct().toList());
    }

    private boolean containsPluginName(List<String> names, String normalizedTarget) {
        for (String name : names) {
            if (normalizeName(name).equals(normalizedTarget)) {
                return true;
            }
        }
        return false;
    }

    private Optional<String> knownReloadHint(String folderName) {
        return switch (normalizeName(folderName)) {
            case "viaversion" -> Optional.of("/viaversion reload");
            case "viabackwards" -> Optional.of("/viabackwards reload");
            case "viarewind" -> Optional.of("/viarewind reload");
            case "luckperms" -> Optional.of("/lp reloadconfig");
            case "tab" -> Optional.of("/tab reload");
            case "scoreboardchatshop" -> Optional.of("No known reload command yet; add one after testing.");
            case "puddlesplus" -> Optional.of("No known reload command yet; add one after testing.");
            default -> Optional.empty();
        };
    }

    private List<String> knownReloadRisks(String folderName) {
        return switch (normalizeName(folderName)) {
            case "viaversion" -> List.of("Staged Folia test: generic ViaVersion reload reported success, but ViaVersion logged that it was already loaded and warned about crash risk with ProtocolLib. Prefer `/viaversion reload` or restart.");
            case "protocolib" -> List.of("Staged Folia test: ProtocolLib reload reported success, but ProtocolLib also warned this Minecraft version was not tested. Treat ProtocolLib changes as restart-preferred.");
            case "placeholderapi" -> List.of("Staged Folia test: reloading PlaceholderAPI after SuperVanish caused a SuperVanish PlaceholderAPI-hook exception. Check dependent plugins before applying live.");
            case "supervanish" -> List.of("Staged Folia test: SuperVanish can throw hook errors around PlaceholderAPI reload ordering. Treat hook-heavy reloads as warning-level even when the command says success.");
            case "scoreboardchatshop" -> List.of("Staged Folia test: ScoreboardChatShop reload succeeded, but missing PlaceholderAPI was logged again after reload. Optional dependency warnings should be reviewed before marking the change clean.");
            default -> List.of();
        };
    }

    private String normalizeName(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }

    private void applyStagedChange(StagedChange change) {
        applyStagedChange(change, "APPLIED", null);
    }

    private boolean applyStagedChange(StagedChange change, String successStatus, String successNote) {
        Path target = resolveAllowedPath(change.relativePath());
        if (target == null) {
            markStageFailed(change.id(), "Target path is no longer allowed.");
            return false;
        }
        try {
            String validation = validateSyntax(change.relativePath(), change.proposedContent());
            if (validation != null) {
                markStageFailed(change.id(), validation);
                return false;
            }
            String currentContent = Files.exists(target) ? Files.readString(target, StandardCharsets.UTF_8) : "";
            ProtectedMerge protectedMerge = mergeProtectedValues(currentContent, change.proposedContent());
            if (!protectedMerge.success()) {
                markStageFailed(change.id(), protectedMerge.message());
                return false;
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
                Files.writeString(target, protectedMerge.content(), StandardCharsets.UTF_8);
                database.update(
                    "UPDATE staged_file_changes SET status = ?, applied_at = ?, failure = ? WHERE id = ?",
                    statement -> {
                        Database.setString(statement, 1, successStatus);
                        Database.setLong(statement, 2, Instant.now().toEpochMilli());
                        Database.setString(statement, 3, successNote);
                        Database.setLong(statement, 4, change.id());
                    }
                );
                logger.info("Applied approved policy change for " + change.relativePath() + " with status " + successStatus + ".");
                return true;
            } catch (Exception writeFailure) {
                if (Files.exists(backup)) {
                    Files.copy(backup, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                }
                markStageFailed(change.id(), writeFailure.getMessage());
                return false;
            }
        } catch (Exception exception) {
            markStageFailed(change.id(), exception.getMessage());
            return false;
        }
    }

    private void dispatchReloadCommand(StagedChange change, String command) {
        plugin.getServer().getGlobalRegionScheduler().execute(plugin, () -> {
            try {
                boolean accepted = plugin.getServer().dispatchCommand(plugin.getServer().getConsoleSender(), command);
                if (accepted) {
                    logger.info("Dispatched policy reload command after proposal " + change.proposalId() + ": /" + command);
                    return;
                }
                database.update(
                    "UPDATE staged_file_changes SET status = 'NEEDS_RESTART', failure = ? WHERE id = ?",
                    statement -> {
                        Database.setString(statement, 1, "Reload command was not accepted by the server: /" + command + ". File remains written and will take effect on restart.");
                        Database.setLong(statement, 2, change.id());
                    }
                );
                logger.warning("Reload command was not accepted after policy proposal " + change.proposalId() + ": /" + command);
            } catch (Exception exception) {
                database.update(
                    "UPDATE staged_file_changes SET status = 'NEEDS_RESTART', failure = ? WHERE id = ?",
                    statement -> {
                        Database.setString(statement, 1, "Reload command failed: " + exception.getMessage() + ". File remains written and will take effect on restart.");
                        Database.setLong(statement, 2, change.id());
                    }
                );
                logger.warning("Reload command failed after policy proposal " + change.proposalId() + ": " + exception.getMessage());
            }
        });
    }

    private void dispatchGenericPluginReload(StagedChange change, String pluginName) {
        plugin.getServer().getGlobalRegionScheduler().execute(plugin, () -> {
            try {
                if (plugin.getName().equalsIgnoreCase(pluginName)) {
                    markReloadNeedsRestart(change, "Generic reload of ElectionsPlugin itself is not allowed. File remains written and will take effect on restart.");
                    return;
                }
                org.bukkit.plugin.Plugin target = plugin.getServer().getPluginManager().getPlugin(pluginName);
                if (target == null) {
                    markReloadNeedsRestart(change, "Target plugin was not loaded: " + pluginName + ". File remains written and will take effect on restart.");
                    return;
                }
                plugin.getServer().getPluginManager().disablePlugin(target);
                plugin.getServer().getPluginManager().enablePlugin(target);
                logger.info("Generic reload completed after policy proposal " + change.proposalId() + ": " + pluginName);
            } catch (Exception exception) {
                markReloadNeedsRestart(change, "Generic reload failed for " + pluginName + ": " + exception.getMessage() + ". File remains written and will take effect on restart.");
                logger.warning("Generic reload failed after policy proposal " + change.proposalId() + " for " + pluginName + ": " + exception.getMessage());
            }
        });
    }

    private void markReloadNeedsRestart(StagedChange change, String reason) {
        database.update(
            "UPDATE staged_file_changes SET status = 'NEEDS_RESTART', failure = ? WHERE id = ?",
            statement -> {
                Database.setString(statement, 1, reason);
                Database.setLong(statement, 2, change.id());
            }
        );
        logger.warning(reason);
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

    private record ApplyDecision(boolean changedFile, String message) {
    }

    private record ProtectedLine(String prefix, String key, String value, String category, boolean redactionPlaceholder, boolean protectedValue) {
        private static ProtectedLine none() {
            return new ProtectedLine("", "", "", "", false, false);
        }
    }

    private record ProtectedMerge(boolean success, String content, String message) {
        private static ProtectedMerge success(String content) {
            return new ProtectedMerge(true, content, "");
        }

        private static ProtectedMerge failure(String message) {
            return new ProtectedMerge(false, null, message);
        }
    }

    private record ProtectedScan(int secret, int address, int port, int inline) {
        private int total() {
            return secret + address + port + inline;
        }
    }

    public record FileEntry(String relativePath, long size) {
    }

    public record BrowserEntry(String relativePath, boolean directory, long size) {
    }

    public record DirectoryListing(String relativePath, List<BrowserEntry> entries) {
    }

    private record DiffStats(int addedLines, int removedLines) {
        private boolean changed() {
            return addedLines > 0 || removedLines > 0;
        }
    }

    private record PluginJarInfo(String jarName, String name, String version, boolean foliaSupported, List<String> commands, List<String> reloadHints, List<String> depend, List<String> softDepend, List<String> loadBefore, List<String> jarSignals) {
    }

    private record ReloadSimulation(String result, int score, List<String> reasons) {
    }

    public record ValidationCheck(String status, String message) {
        public boolean failure() {
            return "FAIL".equals(status);
        }
    }

    public record ValidationReport(long proposalId, String relativePath, Path sandboxPath, Path logPath, List<ValidationCheck> checks) {
        public boolean hasFailures() {
            return checks.stream().anyMatch(ValidationCheck::failure);
        }

        public boolean hasWarnings() {
            return checks.stream().anyMatch(check -> "WARN".equals(check.status()));
        }

        public String result() {
            if (hasFailures()) {
                return "FAILED";
            }
            return hasWarnings() ? "PASSED_WITH_WARNINGS" : "PASSED";
        }

        public String failureSummary() {
            return checks.stream()
                .filter(ValidationCheck::failure)
                .map(ValidationCheck::message)
                .findFirst()
                .orElse("unknown validation failure");
        }

        public String reloadSimulationResult() {
            return checks.stream()
                .filter(check -> "SIM".equals(check.status()))
                .map(ValidationCheck::message)
                .filter(message -> message.startsWith("simulated terminal result: "))
                .map(message -> message.substring("simulated terminal result: ".length()).split(" ", 2)[0])
                .findFirst()
                .orElse("UNKNOWN");
        }

        public boolean reloadSimulationPassed() {
            return "PASS".equals(reloadSimulationResult());
        }

        public Optional<String> reloadCommand() {
            return checks.stream()
                .filter(check -> "SIM".equals(check.status()))
                .map(ValidationCheck::message)
                .filter(message -> message.startsWith("reload plan: prefer native command(s) "))
                .flatMap(message -> List.of(message.substring("reload plan: prefer native command(s) ".length()).split(", ")).stream())
                .map(String::trim)
                .filter(command -> command.startsWith("/"))
                .map(command -> command.substring(1))
                .filter(command -> !command.isBlank())
                .findFirst();
        }

        public String discordSummary() {
            StringBuilder builder = new StringBuilder();
            builder.append("Dry-run result for proposal `").append(proposalId).append("`: `").append(result()).append("`");
            if (relativePath != null) {
                builder.append("\nFile: `").append(relativePath).append("`");
            }
            int shown = 0;
            for (ValidationCheck check : checks) {
                if (shown >= 12) {
                    builder.append("\n... ").append(checks.size() - shown).append(" more checks in `policy-debug.log`");
                    break;
                }
                builder.append("\n[").append(check.status()).append("] ").append(check.message());
                shown++;
            }
            if (sandboxPath != null) {
                builder.append("\nSandbox copy: `").append(sandboxPath).append("`");
            }
            builder.append("\nLog: `").append(logPath).append("`");
            return builder.toString();
        }
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
