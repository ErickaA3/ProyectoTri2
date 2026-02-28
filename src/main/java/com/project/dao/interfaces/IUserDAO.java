package com.project.dao.interfaces;

import java.sql.SQLException;
import java.util.Optional;
import java.util.UUID;

import com.project.model.users.Statistics;
import com.project.model.users.User;

public interface IUserDAO {
    User register(User user) throws SQLException;
    Optional<User> findByEmail(String email) throws SQLException;
    Optional<User> findById(UUID id) throws SQLException;
    Optional<Statistics> getStatsByUserId(UUID userId) throws SQLException;
    void updateStats(Statistics stats) throws SQLException;
    boolean emailExists(String email) throws SQLException;
    boolean usernameExists(String username) throws SQLException;
}