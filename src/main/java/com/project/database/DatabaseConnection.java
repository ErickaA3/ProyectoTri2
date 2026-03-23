package com.project.database;

import java.io.InputStream;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.Properties;

/**
 * Crea una conexión nueva en cada llamada.
 * La versión anterior guardaba una sola conexión estática que expiraba
 * por timeout de Supabase y nunca se reconectaba ("This connection has been closed").
 *
 * Cada DAO debe usar try-with-resources para cerrar la conexión automáticamente:
 *   try (Connection conn = DatabaseConnection.getConnection()) { ... }
 */
public class DatabaseConnection {

    private static Properties config = null;

    private static Properties loadConfig() {
        if (config != null) return config;
        try (InputStream in = DatabaseConnection.class
                .getClassLoader()
                .getResourceAsStream("config/database.properties")) {
            if (in == null) throw new RuntimeException("No se encontró config/database.properties");
            config = new Properties();
            config.load(in);
            return config;
        } catch (Exception e) {
            throw new RuntimeException("Error cargando database.properties: " + e.getMessage());
        }
    }

    public static Connection getConnection() {
        try {
            Properties cfg = loadConfig();

            String url = cfg.getProperty("db.url");

            Properties props = new Properties();
            props.setProperty("user",     cfg.getProperty("db.username"));
            props.setProperty("password", cfg.getProperty("db.password"));
            props.setProperty("sslmode",  cfg.getProperty("db.sslmode", "require"));
            props.setProperty("prepareThreshold", "0");

            Class.forName(cfg.getProperty("db.driver"));
            Connection conn = DriverManager.getConnection(url, props);
            System.out.println("Conexión nueva a Supabase OK");
            return conn;

        } catch (Exception e) {
            System.err.println("Error conectando a Supabase: " + e.getMessage());
            throw new RuntimeException("No se pudo conectar a la base de datos: " + e.getMessage());
        }
    }
}