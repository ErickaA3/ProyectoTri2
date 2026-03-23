package com.project.dao.interfaces;

import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.project.model.users.DailyMission;
import com.project.model.users.Statistics;
import com.project.model.users.User;
import com.project.model.users.WeeklyObjective;

public interface IUserDAO {
    User register(User user) throws SQLException;
    Optional<User> findByEmail(String email) throws SQLException;
    Optional<User> findById(UUID id) throws SQLException;
    Optional<User> findByUsername(String username) throws SQLException;
    Optional<Statistics> getStatsByUserId(UUID userId) throws SQLException;
    void updateStats(Statistics stats) throws SQLException;
    boolean emailExists(String email) throws SQLException;
    boolean usernameExists(String username) throws SQLException;

    // Perfil
    void updateUser(User user) throws SQLException;
    List<WeeklyObjective> getWeeklyObjectives(UUID userId) throws SQLException;
    List<DailyMission> getDailyMissions(UUID userId) throws SQLException;

    // Auto-creación de misiones/objetivos si no existen
    void ensureWeeklyObjectives(UUID userId) throws SQLException;
    void ensureDailyMissions(UUID userId) throws SQLException;
}