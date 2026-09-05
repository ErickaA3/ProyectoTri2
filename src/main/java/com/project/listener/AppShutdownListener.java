package com.project.listener;

import com.project.dao.implementation.ContentDAOImpl;

import jakarta.servlet.ServletContextEvent;
import jakarta.servlet.ServletContextListener;
import jakarta.servlet.annotation.WebListener;

/**
 * Apaga de forma ordenada el pool de hilos usado para generar embeddings
 * en background cuando la aplicación se detiene o se redepliega.
 * Sin esto, Tomcat puede quedar con un classloader "pegado" en memoria
 * (los hilos daemon del pool siguen vivos aunque la app se descargue).
 */
@WebListener
public class AppShutdownListener implements ServletContextListener {

    @Override
    public void contextDestroyed(ServletContextEvent sce) {
        ContentDAOImpl.shutdownEmbeddingExecutor();
    }
}