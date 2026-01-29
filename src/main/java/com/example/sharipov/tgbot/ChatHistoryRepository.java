package com.example.sharipov.tgbot;

import java.sql.*;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

public class ChatHistoryRepository implements AutoCloseable {

    public record HistoryMessage(String role, String content) {}

    private final String dbUrl;
    private Connection db;

    public ChatHistoryRepository(String dbFilePath) {
        this.dbUrl = "jdbc:sqlite:" + dbFilePath;
    }

    public synchronized void init() throws SQLException {
        db = DriverManager.getConnection(dbUrl);

        try (Statement st = db.createStatement()) {
            st.execute("PRAGMA journal_mode=WAL;");
            st.execute("PRAGMA synchronous=NORMAL;");
            st.execute("PRAGMA busy_timeout=5000;");
            st.execute("""
                CREATE TABLE IF NOT EXISTS history (
                    chat_id INTEGER NOT NULL,
                    message_index INTEGER NOT NULL,
                    role TEXT NOT NULL,
                    content TEXT NOT NULL,
                    PRIMARY KEY(chat_id, message_index)
                )
            """);
        }

        log("DB", "Initialized. url=" + dbUrl);
    }

    public synchronized void clearAll() throws SQLException {
        try (PreparedStatement ps = db.prepareStatement("DELETE FROM history")) {
            int deleted = ps.executeUpdate();
            log("DB", "Cleared all history. deleted=" + deleted);
        }
    }

    public synchronized void append(long chatId, String role, String content) throws SQLException {
        int nextIdx = nextIndex(chatId);
        try (PreparedStatement ps = db.prepareStatement("""
            INSERT OR REPLACE INTO history (chat_id, message_index, role, content)
            VALUES (?, ?, ?, ?)
        """)) {
            ps.setLong(1, chatId);
            ps.setInt(2, nextIdx);
            ps.setString(3, role);
            ps.setString(4, content);
            ps.executeUpdate();
        }
    }

    public synchronized List<HistoryMessage> loadLastMessages(long chatId, int limit) throws SQLException {
        List<HistoryMessage> tmp = new ArrayList<>();

        try (PreparedStatement ps = db.prepareStatement("""
            SELECT role, content
            FROM history
            WHERE chat_id = ?
            ORDER BY message_index DESC
            LIMIT ?
        """)) {
            ps.setLong(1, chatId);
            ps.setInt(2, limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) tmp.add(new HistoryMessage(rs.getString("role"), rs.getString("content")));
            }
        }

        // Разворачиваем в ASC для правильного контекста
        List<HistoryMessage> out = new ArrayList<>(tmp.size());
        for (int i = tmp.size() - 1; i >= 0; i--) out.add(tmp.get(i));
        return out;
    }

    public synchronized void trimToLast(long chatId, int keepLastMessages) throws SQLException {
        int nextIdx = nextIndex(chatId);
        int minIdxToKeep = Math.max(1, nextIdx - keepLastMessages);

        try (PreparedStatement ps = db.prepareStatement("""
            DELETE FROM history
            WHERE chat_id = ? AND message_index < ?
        """)) {
            ps.setLong(1, chatId);
            ps.setInt(2, minIdxToKeep);
            int deleted = ps.executeUpdate();
            if (deleted > 0) log("DB", "Trim chatId=" + chatId + ", deleted=" + deleted);
        }
    }

    private int nextIndex(long chatId) throws SQLException {
        try (PreparedStatement ps = db.prepareStatement("""
            SELECT COALESCE(MAX(message_index), 0) + 1 AS next_index
            FROM history
            WHERE chat_id = ?
        """)) {
            ps.setLong(1, chatId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt("next_index");
            }
        }
    }

    @Override
    public synchronized void close() {
        if (db != null) {
            try { db.close(); } catch (Exception ignored) {}
            db = null;
        }
        log("DB", "Closed.");
    }

    private void log(String tag, String msg) {
        System.out.printf("%s [%s] %s%n", Instant.now(), tag, msg);
    }
}
