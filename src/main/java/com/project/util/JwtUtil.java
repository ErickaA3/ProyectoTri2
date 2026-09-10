package com.project.util;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.UUID;

public class JwtUtil {

    // El secreto se lee de la variable de entorno JWT_SECRET (obligatoria en producción)
    // o de la propiedad de sistema jwt.secret. El valor por defecto es SOLO para
    // desarrollo local y NUNCA debe usarse en producción.
    // Rotar el secreto = definir un JWT_SECRET nuevo en el entorno (invalida los tokens vigentes).
    private static final String SECRET = resolveSecret();
    private static final long EXPIRATION_MS = 1000L * 60 * 60 * 8;

    private static final SecretKey KEY = Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8));

    private static String resolveSecret() {
        String secret = System.getenv("JWT_SECRET");
        if (secret == null || secret.isBlank()) {
            secret = System.getProperty("jwt.secret");
        }
        if (secret == null || secret.isBlank()) {
            System.err.println("[JwtUtil] ADVERTENCIA: usando el secreto JWT de desarrollo. "
                    + "Define la variable de entorno JWT_SECRET en producción.");
            secret = "polaris-jwt-secret-key-2026-universidad-invenio-tic";
        }
        // HS256 exige una clave de al menos 256 bits (32 bytes).
        if (secret.getBytes(StandardCharsets.UTF_8).length < 32) {
            throw new IllegalStateException(
                    "JWT_SECRET debe tener al menos 32 caracteres (256 bits) para HS256.");
        }
        return secret;
    }

    public static String generarToken(UUID userId, String rol) {
        return Jwts.builder()
                .subject(userId.toString())
                .claim("rol", rol)
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + EXPIRATION_MS))
                .signWith(KEY)
                .compact();
    }

    public static Claims validarToken(String token) {
        return Jwts.parser()
                .verifyWith(KEY)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    public static String getUserId(String token) {
        return validarToken(token).getSubject();
    }

    public static String getRol(String token) {
        return validarToken(token).get("rol", String.class);
    }
}
