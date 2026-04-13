// org/example/database/EmailDAO.java
package org.example.database;

import org.example.common.MailMessage;

import java.sql.*;
import java.util.ArrayList;
import java.util.List;

public class EmailDAO {
    
    public static int storeEmail(String from, String to, String subject, String content) {
        String sql = "{call store_email(?, ?, ?, ?, ?)}";
        
        try (Connection conn = DatabaseConfig.getConnection();
             CallableStatement stmt = conn.prepareCall(sql)) {
            
            stmt.setString(1, from);
            stmt.setString(2, to);
            stmt.setString(3, subject);
            stmt.setString(4, content);
            stmt.registerOutParameter(5, Types.INTEGER);
            
            stmt.execute();
            return stmt.getInt(5);
            
        } catch (SQLException e) {
            e.printStackTrace();
            return -1;
        }
    }
    
    public static List<MailMessageDB> fetchEmails(String username) {
        List<MailMessageDB> emails = new ArrayList<>();
        String sql = "{call fetch_emails(?)}";
        
        try (Connection conn = DatabaseConfig.getConnection();
             CallableStatement stmt = conn.prepareCall(sql)) {
            
            stmt.setString(1, username);
            ResultSet rs = stmt.executeQuery();
            
            while (rs.next()) {
                MailMessageDB msg = new MailMessageDB();
                msg.setId(rs.getInt("id"));
                msg.setFromAddress(rs.getString("from_address"));
                msg.setSubject(rs.getString("subject"));
                msg.setContent(rs.getString("content"));
                msg.setSentDate(rs.getTimestamp("sent_date"));
                msg.setSeen(rs.getBoolean("seen"));
                emails.add(msg);
            }
            
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return emails;
    }
    
    public static boolean deleteEmail(int emailId, String username) {
        String sql = "{call delete_email(?, ?)}";
        
        try (Connection conn = DatabaseConfig.getConnection();
             CallableStatement stmt = conn.prepareCall(sql)) {
            
            stmt.setInt(1, emailId);
            stmt.setString(2, username);
            
            return stmt.execute();
            
        } catch (SQLException e) {
            e.printStackTrace();
            return false;
        }
    }
    
    public static boolean markEmailSeen(int emailId, String username) {
        String sql = "{call mark_email_seen(?, ?)}";
        
        try (Connection conn = DatabaseConfig.getConnection();
             CallableStatement stmt = conn.prepareCall(sql)) {
            
            stmt.setInt(1, emailId);
            stmt.setString(2, username);
            
            return stmt.execute();
            
        } catch (SQLException e) {
            e.printStackTrace();
            return false;
        }
    }
}