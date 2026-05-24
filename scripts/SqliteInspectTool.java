import java.nio.file.Path;
import java.sql.DriverManager;

public final class SqliteInspectTool {
    public static void main(String[] args) throws Exception {
        if (args.length != 1) {
            throw new IllegalArgumentException("Usage: java SqliteInspectTool.java <db>");
        }
        Path db = Path.of(args[0]);
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + db.toAbsolutePath())) {
            printRows(connection, "proposals", "SELECT id, relative_path, status, upvotes, downvotes, net_score, poll_thread_id, poll_message_id FROM proposals ORDER BY id DESC LIMIT 8");
            printRows(connection, "candidates", "SELECT id, election_id, discord_id, upvotes, downvotes, net_score, thread_id, message_id FROM candidates ORDER BY id DESC LIMIT 8");
            printRows(connection, "impeachments", "SELECT id, status, upvotes, downvotes, net_score, thread_id, message_id FROM impeachments ORDER BY id DESC LIMIT 8");
            printRows(connection, "votes", "SELECT context_type, context_id, discord_id, value, updated_at FROM votes ORDER BY updated_at DESC LIMIT 20");
        }
    }

    private static void printRows(java.sql.Connection connection, String label, String sql) throws Exception {
        System.out.println("== " + label + " ==");
        try (var statement = connection.createStatement();
             var resultSet = statement.executeQuery(sql)) {
            var meta = resultSet.getMetaData();
            int count = meta.getColumnCount();
            boolean any = false;
            while (resultSet.next()) {
                any = true;
                StringBuilder row = new StringBuilder();
                for (int i = 1; i <= count; i++) {
                    if (i > 1) {
                        row.append(" | ");
                    }
                    row.append(meta.getColumnName(i)).append("=").append(resultSet.getString(i));
                }
                System.out.println(row);
            }
            if (!any) {
                System.out.println("(none)");
            }
        }
    }
}
