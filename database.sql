
CREATE DATABASE IF NOT EXISTS mail_system;
USE mail_system;

-- Table users
CREATE TABLE IF NOT EXISTS users (
    id INT AUTO_INCREMENT PRIMARY KEY,
    username VARCHAR(100) UNIQUE NOT NULL,
    password_hash VARCHAR(255) NOT NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    status ENUM('active', 'disabled') DEFAULT 'active'
);

-- Table emails
CREATE TABLE IF NOT EXISTS emails (
    id INT AUTO_INCREMENT PRIMARY KEY,
    message_id VARCHAR(255) UNIQUE,
    from_address VARCHAR(255) NOT NULL,
    to_address VARCHAR(255) NOT NULL,
    subject VARCHAR(500),
    content TEXT,
    sent_date TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    seen BOOLEAN DEFAULT FALSE,
    deleted BOOLEAN DEFAULT FALSE,
    INDEX idx_recipient (to_address),
    INDEX idx_seen (seen)
);

-- Procédures stockées
DELIMITER //

CREATE PROCEDURE authenticate_user(
    IN p_username VARCHAR(100),
    IN p_password VARCHAR(255),
    OUT p_result BOOLEAN
)
BEGIN
    SELECT COUNT(*) > 0 INTO p_result
    FROM users
    WHERE username = p_username 
      AND password_hash = SHA2(p_password, 256)
      AND status = 'active';
END //

CREATE PROCEDURE store_email(
    IN p_from VARCHAR(255),
    IN p_to VARCHAR(255),
    IN p_subject VARCHAR(500),
    IN p_content TEXT,
    OUT p_email_id INT
)
BEGIN
    INSERT INTO emails (from_address, to_address, subject, content, sent_date)
    VALUES (p_from, p_to, p_subject, p_content, NOW());
    SET p_email_id = LAST_INSERT_ID();
END //

CREATE PROCEDURE fetch_emails(
    IN p_username VARCHAR(100)
)
BEGIN
    SELECT id, from_address, subject, content, sent_date, seen
    FROM emails
    WHERE to_address = p_username
      AND deleted = FALSE
    ORDER BY sent_date DESC;
END //

CREATE PROCEDURE delete_email(
    IN p_email_id INT,
    IN p_username VARCHAR(100)
)
BEGIN
    UPDATE emails
    SET deleted = TRUE
    WHERE id = p_email_id AND to_address = p_username;
END //

CREATE PROCEDURE mark_email_seen(
    IN p_email_id INT,
    IN p_username VARCHAR(100)
)
BEGIN
    UPDATE emails
    SET seen = TRUE
    WHERE id = p_email_id AND to_address = p_username;
END //

CREATE PROCEDURE update_password(
    IN p_username VARCHAR(100),
    IN p_new_password VARCHAR(255)
)
BEGIN
    UPDATE users
    SET password_hash = SHA2(p_new_password, 256)
    WHERE username = p_username;
END //

DELIMITER ;