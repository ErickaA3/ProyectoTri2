package com.project.listener;

import com.project.dao.implementation.ContentDAOImpl;
import com.project.database.DatabaseConnection;
import com.project.websocket.DuelRoomManager;

import jakarta.servlet.ServletContextEvent;
import jakarta.servlet.ServletContextListener;
import jakarta.servlet.annotation.WebListener;

/**
 * Apaga de forma ordenada los recursos compartidos de la app
 * (executor de embeddings, timers de las salas de duelo y pool de
 * conexiones HikariCP) cuando
 * la aplicación se detiene o se redepliega. Sin esto, Tomcat puede
 * quedar con hilos daemon o conexiones huérfanas "pegadas" en memoria.
 */
@WebListener
public class AppShutdownListener implements ServletContextListener {

    @Override
    public void contextDestroyed(ServletContextEvent sce) {
        ContentDAOImpl.shutdownEmbeddingExecutor();
        DuelRoomManager.INSTANCE.shutdownAll();
        DatabaseConnection.shutdown();
    }
}