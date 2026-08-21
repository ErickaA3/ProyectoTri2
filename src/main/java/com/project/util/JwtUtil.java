package com.project.util;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;

public class JwtUtil {

    private static final String SECRET = "polaris-jwt-secret-key-2026-universidad-invenio-tic";
    private static final long EXPIRATION_MS = 1000L * 60 * 60 * 8;

    private static final SecretKey KEY = Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8));

    public static String generarToken(int userId, String rol) {
        return Jwts.builder()
                .subject(String.valueOf(userId))
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

    public static int getUserId(String token) {
        return Integer.parseInt(validarToken(token).getSubject());
    }

    public static String getRol(String token) {
        return validarToken(token).get("rol", String.class);
    }
}
