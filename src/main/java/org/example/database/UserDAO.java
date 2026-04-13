
package org.example.database;

import java.sql.*;
import java.util.ArrayList;
import java.util.List;

public class UserDAO {
    
    public static boolean authenticate(String username, String password) {
        String sql = "{call authenticate_user(?, ?, ?)}";
        
        try (Connection conn = DatabaseConfig.getConnection();
             CallableStatement stmt = conn.prepareCall(sql)) {
            System.out.println("Authenticating user: " + username);
            stmt.setString(1, username);
            stmt.setString(2, password);
            stmt.registerOutParameter(3, Types.BOOLEAN);
            
            stmt.execute();
            return stmt.getBoolean(3);
            
        } catch (SQLException e) {
            e.printStackTrace();
            return false;
        }
    }
    
    public static boolean userExists(String username) {
        String sql = "SELECT COUNT(*) FROM users WHERE username = ? AND status = 'active'";
        
        try (Connection conn = DatabaseConfig.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            
            stmt.setString(1, username);
            ResultSet rs = stmt.executeQuery();
            
            if (rs.next()) {
                return rs.getInt(1) > 0;
            }
            
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return false;
    }
    
    public static boolean createUser(String username, String password) {
        String sql = "INSERT INTO users (username, password_hash) VALUES (?, SHA2(?, 256))";
        
        try (Connection conn = DatabaseConfig.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            
            stmt.setString(1, username);
            stmt.setString(2, password);
            
            return stmt.executeUpdate() > 0;
            
        } catch (SQLException e) {
            e.printStackTrace();
            return false;
        }
    }
    
    public static boolean updateUserPassword(String username, String newPassword) {
        String sql = "{call update_password(?, ?)}";
        
        try (Connection conn = DatabaseConfig.getConnection();
             CallableStatement stmt = conn.prepareCall(sql)) {
            
            stmt.setString(1, username);
            stmt.setString(2, newPassword);
            
            return stmt.execute();
            
        } catch (SQLException e) {
            e.printStackTrace();
            return false;
        }
    }
    
    public static boolean deleteUser(String username) {
        String sql = "UPDATE users SET status = 'disabled' WHERE username = ?";
        
        try (Connection conn = DatabaseConfig.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            
            stmt.setString(1, username);
            return stmt.executeUpdate() > 0;
            
        } catch (SQLException e) {
            e.printStackTrace();
            return false;
        }
    }
    
    public static List<String> getAllUsers() {
        List<String> users = new ArrayList<>();
        String sql = "SELECT username FROM users WHERE status = 'active'";
        
        try (Connection conn = DatabaseConfig.getConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            
            while (rs.next()) {
                users.add(rs.getString("username"));
            }
            
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return users;
    }
}