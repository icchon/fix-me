package com.github.icchon.repository;

import com.github.icchon.protocol.Config;
import org.flywaydb.core.Flyway;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;

public class ExecutionRepository {
    private final String url;
    private final String user;
    private final String password;

    public ExecutionRepository() {
        this.url = Config.get("DB_URL", "jdbc:postgresql://localhost:5432/fixme");
        this.user = Config.get("DB_USER", "postgres");
        this.password = Config.get("DB_PASSWORD", "postgres");
        migrate();
    }

    private void migrate() {
        System.out.println("[DB] Running Flyway migrations...");
        Flyway flyway = Flyway.configure()
                .dataSource(url, user, password)
                .load();
        flyway.migrate();
        System.out.println("[DB] Migrations completed.");
    }

    private Connection getConnection() throws SQLException {
        return DriverManager.getConnection(url, user, password);
    }

    public void saveExecution(String execId, String clOrdId, String symbol, String side, int qty, double price, String status) {
        String sql = "INSERT INTO executions (exec_id, cl_ord_id, symbol, side, quantity, price, status) VALUES (?, ?, ?, ?, ?, ?, ?)";
        try (Connection conn = getConnection(); PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, execId);
            pstmt.setString(2, clOrdId);
            pstmt.setString(3, symbol);
            pstmt.setString(4, side);
            pstmt.setInt(5, qty);
            pstmt.setDouble(6, price);
            pstmt.setString(7, status);
            pstmt.executeUpdate();
            System.out.println("[DB] Saved execution: " + execId);
        } catch (SQLException e) {
            System.err.println("[DB ERROR] Failed to save execution: " + e.getMessage());
        }
    }
}
