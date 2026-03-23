package com.project.database;

import java.io.InputStream;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.Properties;

/**
 * Crea una conexión nueva en cada llamada.
 *
 * PRIORIDAD de configuración:
 *   1. Variables de entorno (Railway en producción)
 *   2. config/database.properties (desarrollo local)
 *
 * En local: seguís usando database.properties normalmente, sin cambiar nada.
 * En Railway: las variables DB_URL, DB_USERNAME, DB_PASSWORD sobreescriben el archivo.
 */
public class DatabaseConnection {

    private static Properties fileConfig = null;

    private static Properties loadFileConfig() {
        if (fileConfig != null) return fileConfig;
        try (InputStream in = DatabaseConnection.class
                .getClassLoader()
                .getResourceAsStream("config/database.properties")) {
            if (in == null) throw new RuntimeException("No se encontró config/database.properties");
            fileConfig = new Properties();
            fileConfig.load(in);
            return fileConfig;
        } catch (Exception e) {
            throw new RuntimeException("Error cargando database.properties: " + e.getMessage());
        }
    }

    /**
     * Lee una propiedad con esta prioridad:
     *   1. Variable de entorno (ej: DB_URL)
     *   2. Valor en database.properties (ej: db.url)
     */
    private static String getProp(String envKey, String fileKey) {
        String envVal = System.getenv(envKey);
        if (envVal != null && !envVal.isBlank()) return envVal.trim();
        return loadFileConfig().getProperty(fileKey);
    }

    public static Connection getConnection() {
        try {
            String url      = getProp("DB_URL",      "db.url");
            String username = getProp("DB_USERNAME",  "db.username");
            String password = getProp("DB_PASSWORD",  "db.password");
            String driver   = getProp("DB_DRIVER",    "db.driver");

            Properties props = new Properties();
            props.setProperty("user",             username);
            props.setProperty("password",         password);
            props.setProperty("sslmode",          "require");
            props.setProperty("prepareThreshold", "0");

            Class.forName(driver);
            Connection conn = DriverManager.getConnection(url, props);
            System.out.println("[DB] Conexión OK — " + (System.getenv("DB_URL") != null ? "Railway" : "Local"));
            return conn;

        } catch (Exception e) {
            System.err.println("[DB] Error conectando: " + e.getMessage());
            throw new RuntimeException("No se pudo conectar a la base de datos: " + e.getMessage());
        }
    }
}