package com.project.listener;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.output.MigrateResult;

import com.project.database.DatabaseConnection;

import jakarta.servlet.ServletContextEvent;
import jakarta.servlet.ServletContextListener;
import jakarta.servlet.annotation.WebListener;

/**
 * Aplica las migraciones pendientes de src/main/resources/db/migration al
 * arrancar la app, antes de que entre la primera petición.
 *
 * Solo corre si la variable de entorno DB_MIGRATE=true (se define en Railway).
 * Localmente queda apagado a propósito: si cada integrante corriera su rama
 * contra la base compartida, una migración a medio hacer llegaría a producción.
 *
 * La base de producción ya tenía las V1 y V2 aplicadas a mano antes de usar
 * Flyway: con baselineOnMigrate, la primera vez Flyway la marca como "al día
 * hasta la V2" y solo ejecuta de la V3 en adelante. En una base vacía corre
 * todas desde la V1.
 */
@WebListener
public class DatabaseMigrationListener implements ServletContextListener {

    private static final String BASELINE_VERSION = "2";

    @Override
    public void contextInitialized(ServletContextEvent sce) {
        if (!"true".equalsIgnoreCase(System.getenv("DB_MIGRATE"))) {
            System.out.println("[Flyway] DB_MIGRATE no está en true: no se aplican migraciones.");
            return;
        }

        MigrateResult result = Flyway.configure()
                .dataSource(DatabaseConnection.dataSource())
                .locations("classpath:db/migration")
                .baselineOnMigrate(true)
                .baselineVersion(BASELINE_VERSION)
                .baselineDescription("Esquema previo a Flyway (V1 + V2 aplicadas a mano)")
                .load()
                .migrate();

        // Si una migración falla, migrate() lanza una excepción y Tomcat no
        // levanta la app: es preferible a arrancar con un esquema a medias.
        System.out.println("[Flyway] Migraciones aplicadas: " + result.migrationsExecuted
                + " — versión actual: " + result.targetSchemaVersion);
    }
}
