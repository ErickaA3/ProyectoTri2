package com.project.util;

import java.util.concurrent.ConcurrentHashMap;

/**
 * Limitador de intentos de login en memoria (#146).
 *
 * Cuenta los fallos por clave (IP + email). Tras {@link #MAX_FAILS} fallos dentro de
 * una ventana de tiempo, bloquea la clave durante {@link #LOCK_SECONDS} segundos.
 * Un login exitoso limpia el contador. Suficiente para un único nodo como el actual;
 * si se escala a varios nodos habría que mover el estado a una tienda compartida.
 */
public final class LoginRateLimiter {

    public static final int  MAX_FAILS      = 5;
    public static final long WINDOW_SECONDS = 300;   // 5 min: ventana para acumular fallos
    public static final long LOCK_SECONDS   = 300;   // 5 min: bloqueo tras superar el umbral

    private static final ConcurrentHashMap<String, Entry> ATTEMPTS = new ConcurrentHashMap<>();

    private static final class Entry {
        int  count;
        long windowStartMs;
        long lockedUntilMs;
    }

    private LoginRateLimiter() {}

    /** @return segundos restantes de bloqueo si la clave está bloqueada, o 0 si puede intentar. */
    public static long retryAfterSeconds(String key) {
        Entry e = ATTEMPTS.get(key);
        if (e == null) return 0;
        synchronized (e) {
            long now = System.currentTimeMillis();
            if (e.lockedUntilMs > now) {
                return (long) Math.ceil((e.lockedUntilMs - now) / 1000.0);
            }
            return 0;
        }
    }

    /** Registra un intento fallido y bloquea si se supera el umbral dentro de la ventana. */
    public static void recordFailure(String key) {
        Entry e = ATTEMPTS.computeIfAbsent(key, k -> new Entry());
        synchronized (e) {
            long now = System.currentTimeMillis();
            if (e.lockedUntilMs > now) return;                 // ya bloqueado, no re-contar
            if (now - e.windowStartMs > WINDOW_SECONDS * 1000) {
                e.count = 0;                                     // ventana expirada: reiniciar
                e.windowStartMs = now;
            }
            e.count++;
            if (e.count >= MAX_FAILS) {
                e.lockedUntilMs = now + LOCK_SECONDS * 1000;
            }
        }
    }

    /** Limpia el estado de la clave (llamar tras un login exitoso). */
    public static void reset(String key) {
        ATTEMPTS.remove(key);
    }

    /** Clave de limitación: IP del cliente + email, para no permitir que un email
     *  ajeno sea bloqueado desde cualquier IP. Detrás de proxy usa X-Forwarded-For. */
    public static String keyFor(String clientIp, String email) {
        String ip = clientIp == null ? "?" : clientIp;
        String mail = email == null ? "?" : email.trim().toLowerCase();
        return ip + "|" + mail;
    }
}
