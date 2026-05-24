import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;

public final class SqliteProposalTool {
    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            throw new IllegalArgumentException("Usage: java SqliteProposalTool.java <insert|status> ...");
        }
        switch (args[0]) {
            case "insert" -> insert(args);
            case "status" -> status(args);
            default -> throw new IllegalArgumentException("Unknown action: " + args[0]);
        }
    }

    private static void insert(String[] args) throws Exception {
        if (args.length != 5) {
            throw new IllegalArgumentException("Usage: insert <db> <relative-path> <content-file> <diff-file>");
        }
        Path db = Path.of(args[1]);
        String relativePath = args[2];
        String content = Files.readString(Path.of(args[3]));
        String diff = Files.readString(Path.of(args[4]));
        long now = System.currentTimeMillis();
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + db.toAbsolutePath());
             var statement = connection.prepareStatement("""
                 INSERT INTO proposals(proposer_discord_id, relative_path, proposed_content, diff, created_at, closes_at, status, upvotes, downvotes, net_score)
                 VALUES('smoke-test', ?, ?, ?, ?, ?, 'OPEN', 1, 0, 1)
                 """, java.sql.Statement.RETURN_GENERATED_KEYS)) {
            statement.setString(1, relativePath);
            statement.setString(2, content);
            statement.setString(3, diff);
            statement.setLong(4, now);
            statement.setLong(5, now + 60000L);
            statement.executeUpdate();
            try (var keys = statement.getGeneratedKeys()) {
                if (keys.next()) {
                    System.out.println(keys.getLong(1));
                }
            }
        }
    }

    private static void status(String[] args) throws Exception {
        if (args.length != 3) {
            throw new IllegalArgumentException("Usage: status <db> <proposal-id>");
        }
        Path db = Path.of(args[1]);
        long proposalId = Long.parseLong(args[2]);
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + db.toAbsolutePath());
             var statement = connection.prepareStatement("""
                 SELECT status, failure FROM staged_file_changes
                 WHERE proposal_id = ?
                 ORDER BY id DESC
                 LIMIT 1
                 """)) {
            statement.setLong(1, proposalId);
            try (var resultSet = statement.executeQuery()) {
                if (resultSet.next()) {
                    String failure = resultSet.getString("failure");
                    System.out.println(resultSet.getString("status") + "|" + (failure == null ? "" : failure));
                } else {
                    System.out.println("none|");
                }
            }
        }
    }
}
