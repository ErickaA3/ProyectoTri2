package com.project.listener;

import com.project.dao.implementation.LibraryDAOImpl;
import com.project.database.DatabaseConnection;

import jakarta.servlet.ServletContextEvent;
import jakarta.servlet.ServletContextListener;
import jakarta.servlet.annotation.WebListener;

/**
 * Ciclo de vida de los recursos compartidos de la app.
 *
 * Al arrancar: dispara la pasada que completa los embeddings pendientes de
 * document_chunks (por ejemplo, los fragmentos migrados desde study_content).
 *
 * Al detenerse: apaga de forma ordenada el executor de embeddings y el pool de
 * conexiones HikariCP. Sin esto, Tomcat puede quedar con hilos daemon o
 * conexiones huérfanas "pegadas" en memoria tras un redeploy.
 */
@WebListener
public class AppShutdownListener implements ServletContextListener {

    @Override
    public void contextInitialized(ServletContextEvent sce) {
        LibraryDAOImpl.scheduleEmbeddingBackfill();
    }

    @Override
    public void contextDestroyed(ServletContextEvent sce) {
        LibraryDAOImpl.shutdownEmbeddingExecutor();
        DatabaseConnection.shutdown();
    }
}
