package com.project.util;

import java.io.IOException;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.FilterConfig;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.annotation.WebFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

@WebFilter("/*")
public class CorsFilter implements Filter {

    // Origen permitido para CORS. Configurable por entorno (CORS_ALLOWED_ORIGIN o
    // propiedad cors.allowed.origin). El valor por defecto es el del editor local
    // (Live Server) para no romper el desarrollo; en producción se define la variable
    // con el dominio real. Antes estaba fijo a http://127.0.0.1:5500 (#127).
    private static final String ALLOWED_ORIGIN = resolveAllowedOrigin();

    private static String resolveAllowedOrigin() {
        String o = System.getenv("CORS_ALLOWED_ORIGIN");
        if (o == null || o.isBlank()) o = System.getProperty("cors.allowed.origin");
        if (o == null || o.isBlank()) o = "http://127.0.0.1:5500";
        return o.trim();
    }

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {

        HttpServletRequest httpRequest   = (HttpServletRequest) request;
        HttpServletResponse httpResponse = (HttpServletResponse) response;

        // ── CORS ──
        httpResponse.setHeader("Access-Control-Allow-Origin", ALLOWED_ORIGIN);
        httpResponse.setHeader("Access-Control-Allow-Credentials", "true");
        httpResponse.setHeader("Access-Control-Allow-Methods", "GET, POST, PUT, DELETE, OPTIONS");
        httpResponse.setHeader("Access-Control-Allow-Headers", "Content-Type, Authorization");
        httpResponse.setHeader("Vary", "Origin");

        // ── Cabeceras de seguridad (#145) ──
        // Fuerza HTTPS en navegadores (se ignora sobre HTTP local, así que es seguro).
        httpResponse.setHeader("Strict-Transport-Security", "max-age=31536000; includeSubDomains");
        // Evita que el navegador "adivine" el tipo de un recurso.
        httpResponse.setHeader("X-Content-Type-Options", "nosniff");
        // Impide incrustar la página en un iframe de otro sitio (clickjacking).
        httpResponse.setHeader("X-Frame-Options", "DENY");
        httpResponse.setHeader("Content-Security-Policy", "frame-ancestors 'none'");
        // No filtra la URL de origen a terceros.
        httpResponse.setHeader("Referrer-Policy", "no-referrer");

        // ← Responder al preflight y cortar
        if ("OPTIONS".equalsIgnoreCase(httpRequest.getMethod())) {
            httpResponse.setStatus(HttpServletResponse.SC_OK);
            return;
        }

        chain.doFilter(request, response);
    }

    @Override
    public void init(FilterConfig filterConfig) {}

    @Override
    public void destroy() {}
}
