package com.project.database;

import java.io.InputStream;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.Properties;

public class DatabaseConnection {

    private static Connection connection = null;

    public static Connection getConnection() {
        if (connection == null) {
            try {
                Properties config = new Properties();
                InputStream in = DatabaseConnection.class
                    .getClassLoader()
                    .getResourceAsStream("config/database.properties");
                if (in == null) {
                    System.out.println("No se encontró el archivo database.properties");
                    return null;
                }
                config.load(in);

                String url = config.getProperty("db.url");

                Properties props = new Properties();
                props.setProperty("user",     config.getProperty("db.username"));
                props.setProperty("password", config.getProperty("db.password"));
                props.setProperty("sslmode",  config.getProperty("db.sslmode", "require"));

                Class.forName(config.getProperty("db.driver"));
                connection = DriverManager.getConnection(url, props);

                System.out.println("Conectado a Supabase!");

            } catch (Exception e) {
                System.err.println("Error: " + e.getMessage());
                e.printStackTrace();
            }
        }
        return connection;
    }
}