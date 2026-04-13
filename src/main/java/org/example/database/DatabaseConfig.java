
package org.example.database;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Properties;

public class DatabaseConfig {
    private static final String URL = "jdbc:mysql://localhost:3306/mail_system";
    private static final String USER = "root";
    private static final String PASSWORD = "Y@BeD2003";
    
    private static final Properties props = new Properties();
    
    static {
        props.setProperty("user", USER);
        props.setProperty("password", PASSWORD);
        props.setProperty("useSSL", "false");
        props.setProperty("serverTimezone", "UTC");
    }
    
    public static Connection getConnection() throws SQLException {
        try {
            Class.forName("com.mysql.cj.jdbc.Driver");
        } catch (ClassNotFoundException e) {
            throw new SQLException("MySQL Driver not found", e);
        }
        return DriverManager.getConnection(URL, props);
    }
}