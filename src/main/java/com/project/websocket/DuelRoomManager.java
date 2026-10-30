package com.project.websocket;

import java.util.concurrent.ConcurrentHashMap;

public enum DuelRoomManager {

    INSTANCE;

    private final ConcurrentHashMap<String, DuelRoom> rooms = new ConcurrentHashMap<>();

    public DuelRoom getOrCreate(String duelId) {
        return rooms.computeIfAbsent(duelId, DuelRoom::new);
    }

    public DuelRoom get(String duelId) {
        return rooms.get(duelId);
    }

    public void removeRoom(String duelId) {
        DuelRoom room = rooms.remove(duelId);
        if (room != null) room.shutdown();
    }

    public int activeRooms() {
        return rooms.size();
    }

    /** Apaga los timers de todas las salas. Lo llama AppShutdownListener al redesplegar. */
    public void shutdownAll() {
        rooms.values().forEach(DuelRoom::shutdown);
        rooms.clear();
    }
}