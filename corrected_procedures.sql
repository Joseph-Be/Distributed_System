-- database/corrected_procedures.sql
USE mail_system;

-- Supprimer les anciennes procédures
DROP PROCEDURE IF EXISTS fetch_emails;
DROP PROCEDURE IF EXISTS store_email;
DROP PROCEDURE IF EXISTS delete_email;
DROP PROCEDURE IF EXISTS mark_email_seen;
DROP PROCEDURE IF EXISTS authenticate_user;

DELIMITER //

-- Procédure fetch_emails corrigée - extrait le username de to_address
CREATE PROCEDURE fetch_emails(
    IN p_username VARCHAR(100)
)
BEGIN
    SELECT id, from_address, subject, content, sent_date, seen
    FROM emails
    WHERE SUBSTRING_INDEX(to_address, '@', 1) = p_username
      AND deleted = FALSE
    ORDER BY sent_date DESC;
END //

-- Procédure store_email - stocke l'email complet
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

-- Procédure delete_email - extrait le username de to_address
CREATE PROCEDURE delete_email(
    IN p_email_id INT,
    IN p_username VARCHAR(100)
)
BEGIN
    UPDATE emails
    SET deleted = TRUE
    WHERE id = p_email_id 
      AND SUBSTRING_INDEX(to_address, '@', 1) = p_username;
END //

-- Procédure mark_email_seen - extrait le username de to_address
CREATE PROCEDURE mark_email_seen(
    IN p_email_id INT,
    IN p_username VARCHAR(100)
)
BEGIN
    UPDATE emails
    SET seen = TRUE
    WHERE id = p_email_id 
      AND SUBSTRING_INDEX(to_address, '@', 1) = p_username;
END //

-- Procédure authenticate_user - inchangée
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

-- Procédure update_password - inchangée
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

-- Vérifier les procédures
SHOW PROCEDURE STATUS WHERE Db = 'mail_system';