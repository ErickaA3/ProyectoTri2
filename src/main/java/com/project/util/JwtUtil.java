package com.project.util;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Date;
import java.util.UUID;

public class JwtUtil {

    // El secreto se lee SOLO de la variable de entorno JWT_SECRET (o la propiedad
    // jwt.secret). En producción es obligatoria: si falta, la app NO arranca. En
    // desarrollo, si falta, se genera un secreto aleatorio por arranque (los tokens
    // se invalidan al reiniciar). Ya NO existe un secreto embebido en el código (#144).
    // Rotar el secreto = definir un JWT_SECRET nuevo en el entorno (invalida los tokens vigentes).
    private static final String SECRET = resolveSecret();
    private static final long EXPIRATION_MS = 1000L * 60 * 60 * 8;

    private static final SecretKey KEY = Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8));

    private static String resolveSecret() {
        String secret = System.getenv("JWT_SECRET");
        if (secret == null || secret.isBlank()) {
            secret = System.getProperty("jwt.secret");
        }

        if (secret != null && !secret.isBlank()) {
            // HS256 exige una clave de al menos 256 bits (32 bytes).
            if (secret.getBytes(StandardCharsets.UTF_8).length < 32) {
                throw new IllegalStateException(
                        "JWT_SECRET debe tener al menos 32 caracteres (256 bits) para HS256.");
            }
            return secret;
        }

        // No hay secreto definido.
        if (isProduction()) {
            // Fallar rápido: nunca operar en producción sin un secreto propio.
            throw new IllegalStateException(
                    "JWT_SECRET no está definida. Es obligatoria en producción. "
                    + "Definí la variable de entorno con un valor aleatorio de >= 32 caracteres.");
        }

        // Desarrollo: secreto aleatorio por arranque (no embebido en el repositorio).
        byte[] rnd = new byte[48];
        new SecureRandom().nextBytes(rnd);
        String dev = Base64.getUrlEncoder().withoutPadding().encodeToString(rnd);
        System.err.println("[JwtUtil] ADVERTENCIA: JWT_SECRET no definida; usando un secreto "
                + "aleatorio SOLO para desarrollo. Los tokens se invalidan al reiniciar. "
                + "Definí JWT_SECRET en el entorno para un valor estable.");
        return dev;
    }

    private static boolean isProduction() {
        // Railway define estas variables automáticamente en el entorno desplegado.
        if (System.getenv("RAILWAY_ENVIRONMENT") != null) return true;
        if (System.getenv("RAILWAY_PROJECT_ID") != null)  return true;
        if (System.getenv("RAILWAY_SERVICE_ID") != null)  return true;
        return "production".equalsIgnoreCase(System.getenv("APP_ENV"));
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
