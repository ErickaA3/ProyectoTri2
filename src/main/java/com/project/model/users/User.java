package com.project.model.users;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Representa la tabla `users`.
 */
public class User {

    private UUID          id;
    private String        username;
    private String        email;
    private String        passwordHash;   // NUNCA se expone al front
    private String        fullName;
    private LocalDate     birthdate;
    private String        country;
    private String        language;
    private LocalDateTime createdAt;

    // ── Constructores ──────────────────────────────────────────────────────────

    public User() {}

    public User(String username, String email, String passwordHash) {
        this.username     = username;
        this.email        = email;
        this.passwordHash = passwordHash;
        this.language     = "es";
    }

    // ── Getters & Setters ──────────────────────────────────────────────────────

    public UUID getId()                        { return id; }
    public void setId(UUID id)                 { this.id = id; }

    public String getUsername()                { return username; }
    public void setUsername(String username)   { this.username = username; }

    public String getEmail()                   { return email; }
    public void setEmail(String email)         { this.email = email; }

    public String getPasswordHash()            { return passwordHash; }
    public void setPasswordHash(String hash)   { this.passwordHash = hash; }

    public String getFullName()                { return fullName; }
    public void setFullName(String fullName)   { this.fullName = fullName; }

    public LocalDate getBirthdate()            { return birthdate; }
    public void setBirthdate(LocalDate d)      { this.birthdate = d; }

    public String getCountry()                 { return country; }
    public void setCountry(String country)     { this.country = country; }

    public String getLanguage()                { return language; }
    public void setLanguage(String language)   { this.language = language; }

    public LocalDateTime getCreatedAt()        { return createdAt; }
    public void setCreatedAt(LocalDateTime t)  { this.createdAt = t; }

    @Override
    public String toString() {
        return "User{id=" + id + ", username='" + username + "', email='" + email + "'}";
    }
}