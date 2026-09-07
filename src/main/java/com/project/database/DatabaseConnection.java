package com.project.database;

import java.io.InputStream;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Properties;

import com.pgvector.PGvector;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

public class DatabaseConnection {

    private static Properties fileConfig = null;
    private static volatile HikariDataSource dataSource;

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

    private static String getProp(String envKey, String fileKey) {
        String envVal = System.getenv(envKey);
        if (envVal != null && !envVal.isBlank()) return envVal.trim();
        return loadFileConfig().getProperty(fileKey);
    }

    /** Inicializa el pool UNA sola vez, la primera vez que se pide una conexión. */
    private static HikariDataSource getDataSource() {
        HikariDataSource ds = dataSource;
        if (ds == null) {
            synchronized (DatabaseConnection.class) {
                ds = dataSource;
                if (ds == null) {
                    String url      = getProp("DB_URL",     "db.url");
                    String username = getProp("DB_USERNAME", "db.username");
                    String password = getProp("DB_PASSWORD", "db.password");
                    String driver   = getProp("DB_DRIVER",   "db.driver");

                    HikariConfig config = new HikariConfig();
                    config.setJdbcUrl(url);
                    config.setUsername(username);
                    config.setPassword(password);
                    config.setDriverClassName(driver);

                    // Ajustables según carga real; valores conservadores para
                    // el límite de conexiones del plan free de Supabase.
                    config.setMaximumPoolSize(10);
                    config.setMinimumIdle(2);
                    config.setConnectionTimeout(10_000);   // 10s esperando conexión libre
                    config.setIdleTimeout(300_000);         // 5min
                    config.setMaxLifetime(1_800_000);       // 30min, refresca conexiones viejas

                    config.addDataSourceProperty("sslmode", "require");
                    config.addDataSourceProperty("prepareThreshold", "0");

                    ds = new HikariDataSource(config);
                    dataSource = ds;
                    System.out.println("[DB] Pool HikariCP inicializado — "
                        + (System.getenv("DB_URL") != null ? "Railway" : "Local"));
                }
            }
        }
        return ds;
    }

    public static Connection getConnection() {
        try {
            Connection conn = getDataSource().getConnection();
            // Registro client-side del tipo 'vector' — operación local, sin red.
            PGvector.addVectorType(conn);
            return conn;
        } catch (SQLException e) {
            System.err.println("[DB] Error obteniendo conexión del pool: " + e.getMessage());
            throw new RuntimeException("No se pudo conectar a la base de datos: " + e.getMessage());
        }
    }

    /** Llamar desde AppShutdownListener al redeploy/shutdown de Tomcat. */
    public static void shutdown() {
        if (dataSource != null) {
            dataSource.close();
            dataSource = null;
        }
    }
}