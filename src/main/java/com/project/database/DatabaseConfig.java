package com.project.database;

import java.sql.Connection;

public class DatabaseConfig {

    public static void main(String[] args) {
        Connection conn = DatabaseConnection.getConnection();

        if (conn != null) {
            System.out.println("Conexión exitosa a Supabase!");
        } else {
            System.out.println("No se pudo conectar.");
        }
    }
}