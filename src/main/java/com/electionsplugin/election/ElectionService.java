package com.electionsplugin.election;

import com.electionsplugin.config.PluginConfig;
import com.electionsplugin.database.Database;
import com.electionsplugin.role.RoleSyncService;

import java.sql.ResultSet;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.logging.Logger;

public final class ElectionService {
    private static final String TYPE_MONTHLY = "MONTHLY";
    private static final String TYPE_SPECIAL = "SPECIAL";
    private static final DateTimeFormatter PERIOD = DateTimeFormatter.ofPattern("yyyy-MM");

    private final Database database;
    private final PluginConfig config;
    private final RoleSyncService roleSyncService;
    private final Logger logger;

    public ElectionService(Database database, PluginConfig config, RoleSyncService roleSyncService, Logger logger) {
        this.database = database;
        this.config = config;
        this.roleSyncService = roleSyncService;
        this.logger = logger;
    }

    public void ensureMonthlyElection() {
        ZonedDateTime now = ZonedDateTime.now(config.zoneId());
        Window window = monthlyWindow(YearMonth.from(now));
        if (!isWithin(now, window)) {
            return;
        }
        String periodKey = PERIOD.format(now);
        existingElection(TYPE_MONTHLY, periodKey).orElseGet(() -> createElection(TYPE_MONTHLY, periodKey, window.start(), window.end()));
    }

    public void announceOpenElections(DiscordElectionBridge bridge) {
        long now = Instant.now().toEpochMilli();
        List<ElectionRecord> open = database.query(
            "SELECT * FROM elections WHERE status = 'OPEN' AND starts_at <= ? AND ends_at >= ? AND opened_announced_at IS NULL",
            statement -> {
                Database.setLong(statement, 1, now);
                Database.setLong(statement, 2, now);
            },
            this::mapElection
        );
        for (ElectionRecord election : open) {
            bridge.publishElectionOpened(election);
            database.update(
                "UPDATE elections SET opened_announced_at = ? WHERE id = ?",
                statement -> {
                    Database.setLong(statement, 1, now);
                    Database.setLong(statement, 2, election.id());
                }
            );
        }
    }

    public CandidateRegistration registerCandidate(String discordId, String threadId, String messageId) {
        ZonedDateTime now = ZonedDateTime.now(config.zoneId());
        Optional<ElectionRecord> activeSpecial = activeSpecialElection();
        Window window = monthlyWindow(YearMonth.from(now));
        if (activeSpecial.isEmpty() && !isWithin(now, window)) {
            return CandidateRegistration.rejected(
                "Elections are closed. The next election starts on " + format(nextMonthlyWindow(now).start()) + ".",
                nextMonthlyWindow(now).start()
            );
        }
        if (!roleSyncService.canWinPresidency(discordId)) {
            return CandidateRegistration.rejected("You have already reached the presidential term limit.", null);
        }
        ElectionRecord election = activeSpecial.orElseGet(() -> existingElection(TYPE_MONTHLY, PERIOD.format(now))
            .orElseGet(() -> createElection(TYPE_MONTHLY, PERIOD.format(now), window.start(), window.end())));

        boolean exists = database.queryOne(
            "SELECT id FROM candidates WHERE thread_id = ? OR message_id = ?",
            statement -> {
                Database.setString(statement, 1, threadId);
                Database.setString(statement, 2, messageId);
            },
            resultSet -> 1
        ).isPresent();
        if (exists) {
            return CandidateRegistration.accepted("Candidate is already registered.");
        }

        long nowMillis = Instant.now().toEpochMilli();
        database.insert(
            """
            INSERT INTO candidates(election_id, discord_id, thread_id, message_id, created_at)
            VALUES(?, ?, ?, ?, ?)
            """,
            statement -> {
                Database.setLong(statement, 1, election.id());
                Database.setString(statement, 2, discordId);
                Database.setString(statement, 3, threadId);
                Database.setString(statement, 4, messageId);
                Database.setLong(statement, 5, nowMillis);
            }
        );
        return CandidateRegistration.accepted("Candidate registered.");
    }

    public void recordCandidateVote(String messageId, String discordId, int value, boolean removed) {
        Optional<CandidateRecord> candidate = candidateByMessage(messageId);
        if (candidate.isEmpty()) {
            return;
        }
        Optional<ElectionRecord> election = electionById(candidate.get().electionId());
        if (election.isEmpty() || !"OPEN".equals(election.get().status()) || Instant.now().toEpochMilli() > election.get().endsAt()) {
            return;
        }
        if (removed) {
            database.update(
                "DELETE FROM votes WHERE context_type = 'CANDIDATE' AND context_id = ? AND discord_id = ? AND value = ?",
                statement -> {
                    Database.setLong(statement, 1, candidate.get().id());
                    Database.setString(statement, 2, discordId);
                    Database.setInt(statement, 3, value);
                }
            );
        } else {
            database.update(
                """
                INSERT INTO votes(context_type, context_id, discord_id, value, updated_at)
                VALUES('CANDIDATE', ?, ?, ?, ?)
                ON CONFLICT(context_type, context_id, discord_id) DO UPDATE SET
                    value = excluded.value,
                    updated_at = excluded.updated_at
                """,
                statement -> {
                    Database.setLong(statement, 1, candidate.get().id());
                    Database.setString(statement, 2, discordId);
                    Database.setInt(statement, 3, value);
                    Database.setLong(statement, 4, Instant.now().toEpochMilli());
                }
            );
        }
        refreshCandidateScore(candidate.get().id());
    }

    public void closeDueElections(DiscordElectionBridge bridge) {
        long now = Instant.now().toEpochMilli();
        List<ElectionRecord> open = database.query(
            "SELECT * FROM elections WHERE status = 'OPEN' AND ends_at <= ?",
            statement -> Database.setLong(statement, 1, now),
            this::mapElection
        );

        for (ElectionRecord election : open) {
            List<CandidateRecord> candidates = candidatesForElection(election.id());
            candidates.forEach(candidate -> refreshCandidateScore(candidate.id()));
            candidates = candidatesForElection(election.id());

            int minimumVotes = TYPE_SPECIAL.equals(election.type()) ? config.specialElectionMinimumVotes() : config.electionMinimumVotes();
            List<CandidateRecord> eligible = candidates.stream()
                .filter(candidate -> candidate.upvotes() + candidate.downvotes() >= minimumVotes)
                .filter(candidate -> roleSyncService.canWinPresidency(candidate.discordId()))
                .sorted(Comparator.comparingInt(CandidateRecord::netScore).reversed())
                .toList();

            CandidateRecord winner = null;
            boolean tie = false;
            if (!eligible.isEmpty()) {
                winner = eligible.getFirst();
                if (eligible.size() > 1 && eligible.get(1).netScore() == winner.netScore()) {
                    winner = null;
                    tie = true;
                }
            }

            if (winner != null) {
                roleSyncService.clearPresident();
                roleSyncService.removeAllCabinetLuckPerms();
                roleSyncService.setPresident(winner.discordId());
                roleSyncService.recordPresidentialWin(winner.discordId());
                CandidateRecord finalWinner = winner;
                database.update(
                    "UPDATE elections SET status = 'CLOSED', winner_discord_id = ? WHERE id = ?",
                    statement -> {
                        Database.setString(statement, 1, finalWinner.discordId());
                        Database.setLong(statement, 2, election.id());
                    }
                );
            } else {
                database.update(
                    "UPDATE elections SET status = 'CLOSED' WHERE id = ?",
                    statement -> Database.setLong(statement, 1, election.id())
                );
            }

            ElectionResult result = new ElectionResult(
                electionById(election.id()).orElse(election),
                winner,
                tie,
                candidates,
                nextMonthlyWindow(ZonedDateTime.now(config.zoneId())).start(),
                nextMonthlyWindow(ZonedDateTime.now(config.zoneId())).end()
            );
            bridge.applyGovernmentDiscordRoles(winner == null ? null : winner.discordId());
            bridge.publishElectionResult(result);
            bridge.closeCandidatePosts(result);
        }
    }

    public ElectionRecord createSpecialElection() {
        ZonedDateTime now = ZonedDateTime.now(config.zoneId());
        ZonedDateTime end = now.plusDays(config.specialElectionDurationDays());
        String periodKey = "special-" + Instant.now().toEpochMilli();
        return createElection(TYPE_SPECIAL, periodKey, now, end);
    }

    public String statusLine() {
        ZonedDateTime now = ZonedDateTime.now(config.zoneId());
        Optional<ElectionRecord> special = activeSpecialElection();
        if (special.isPresent()) {
            return "Special election is open until " + format(Instant.ofEpochMilli(special.get().endsAt()).atZone(config.zoneId())) + ".";
        }
        Window current = monthlyWindow(YearMonth.from(now));
        if (isWithin(now, current)) {
            return "Monthly election is open until " + format(current.end()) + ".";
        }
        return "Monthly election is closed. Next election starts " + format(nextMonthlyWindow(now).start()) + ".";
    }

    public Window nextMonthlyWindow(ZonedDateTime now) {
        YearMonth month = YearMonth.from(now);
        Window current = monthlyWindow(month);
        if (now.isBefore(current.start())) {
            return current;
        }
        return monthlyWindow(month.plusMonths(1));
    }

    private ElectionRecord createElection(String type, String periodKey, ZonedDateTime startsAt, ZonedDateTime endsAt) {
        long id = database.insert(
            """
            INSERT INTO elections(type, period_key, starts_at, ends_at, status, created_at)
            VALUES(?, ?, ?, ?, 'OPEN', ?)
            """,
            statement -> {
                Database.setString(statement, 1, type);
                Database.setString(statement, 2, periodKey);
                Database.setLong(statement, 3, startsAt.toInstant().toEpochMilli());
                Database.setLong(statement, 4, endsAt.toInstant().toEpochMilli());
                Database.setLong(statement, 5, Instant.now().toEpochMilli());
            }
        );
        logger.info("Created " + type + " election " + periodKey + ".");
        return electionById(id).orElseThrow();
    }

    private Optional<ElectionRecord> existingElection(String type, String periodKey) {
        return database.queryOne(
            "SELECT * FROM elections WHERE type = ? AND period_key = ?",
            statement -> {
                Database.setString(statement, 1, type);
                Database.setString(statement, 2, periodKey);
            },
            this::mapElection
        );
    }

    private Optional<ElectionRecord> activeSpecialElection() {
        long now = Instant.now().toEpochMilli();
        return database.queryOne(
            "SELECT * FROM elections WHERE type = ? AND status = 'OPEN' AND starts_at <= ? AND ends_at >= ? ORDER BY starts_at DESC LIMIT 1",
            statement -> {
                Database.setString(statement, 1, TYPE_SPECIAL);
                Database.setLong(statement, 2, now);
                Database.setLong(statement, 3, now);
            },
            this::mapElection
        );
    }

    private Optional<ElectionRecord> electionById(long id) {
        return database.queryOne(
            "SELECT * FROM elections WHERE id = ?",
            statement -> Database.setLong(statement, 1, id),
            this::mapElection
        );
    }

    private Optional<CandidateRecord> candidateByMessage(String messageId) {
        return database.queryOne(
            "SELECT * FROM candidates WHERE message_id = ?",
            statement -> Database.setString(statement, 1, messageId),
            this::mapCandidate
        );
    }

    private List<CandidateRecord> candidatesForElection(long electionId) {
        return database.query(
            "SELECT * FROM candidates WHERE election_id = ?",
            statement -> Database.setLong(statement, 1, electionId),
            this::mapCandidate
        );
    }

    private void refreshCandidateScore(long candidateId) {
        int up = voteCount("CANDIDATE", candidateId, 1);
        int down = voteCount("CANDIDATE", candidateId, -1);
        database.update(
            "UPDATE candidates SET upvotes = ?, downvotes = ?, net_score = ? WHERE id = ?",
            statement -> {
                Database.setInt(statement, 1, up);
                Database.setInt(statement, 2, down);
                Database.setInt(statement, 3, up - down);
                Database.setLong(statement, 4, candidateId);
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

    private Window monthlyWindow(YearMonth month) {
        int startDay = Math.min(config.electionStartDay(), month.lengthOfMonth());
        int endDay = Math.min(config.electionEndDay(), month.lengthOfMonth());
        LocalDate startDate = month.atDay(startDay);
        LocalDate endDate = month.atDay(endDay);
        ZonedDateTime start = startDate.atStartOfDay(config.zoneId());
        ZonedDateTime end = endDate.atTime(config.electionCloseTime()).atZone(config.zoneId());
        return new Window(start, end);
    }

    private boolean isWithin(ZonedDateTime now, Window window) {
        return !now.isBefore(window.start()) && !now.isAfter(window.end());
    }

    private String format(ZonedDateTime value) {
        return value.format(DateTimeFormatter.ofPattern("MMMM d, yyyy h:mm a z"));
    }

    private ElectionRecord mapElection(ResultSet resultSet) {
        try {
            return new ElectionRecord(
                resultSet.getLong("id"),
                resultSet.getString("type"),
                resultSet.getString("period_key"),
                resultSet.getLong("starts_at"),
                resultSet.getLong("ends_at"),
                resultSet.getString("status"),
                resultSet.getString("winner_discord_id")
            );
        } catch (Exception exception) {
            throw new RuntimeException(exception);
        }
    }

    private CandidateRecord mapCandidate(ResultSet resultSet) {
        try {
            return new CandidateRecord(
                resultSet.getLong("id"),
                resultSet.getLong("election_id"),
                resultSet.getString("discord_id"),
                resultSet.getString("thread_id"),
                resultSet.getString("message_id"),
                resultSet.getInt("upvotes"),
                resultSet.getInt("downvotes"),
                resultSet.getInt("net_score")
            );
        } catch (Exception exception) {
            throw new RuntimeException(exception);
        }
    }

    public record Window(ZonedDateTime start, ZonedDateTime end) {
    }
}
