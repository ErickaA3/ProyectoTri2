package com.project.dao.interfaces;

import com.google.gson.JsonArray;

/**
 * Contrato para operaciones del Historial.
 * Separado de IContentDAO para no modificar el código del compañero.
 * HistorialDAOImpl implementa esto con el SQL real.
 */
public interface IHistorialDAO {

    /**
     * Obtiene el historial del usuario con filtros opcionales.
     * @param userId UUID del usuario
     * @param type   "summary"|"flashcard"|"schema"|"quiz" — null para todos
     * @param date   Fecha en formato "YYYY-MM-DD" — null para todas
     * @param search Texto a buscar en el título (case-insensitive) — null para todos
     * @return JsonArray con los ítems ordenados por fecha descendente
     */
    JsonArray getHistory(String userId, String type, String date, String search) throws Exception;

    /**
     * Elimina todo el historial de un usuario.
     * @return Número de filas eliminadas
     */
    int deleteAll(String userId) throws Exception;
}
















