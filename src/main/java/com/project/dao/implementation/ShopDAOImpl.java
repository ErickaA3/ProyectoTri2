package com.project.dao.implementation;

import com.project.dao.interfaces.IShopDAO;
import com.project.database.DatabaseConnection;
import com.project.model.shop.Product;
import com.project.model.shop.Purchase;

import java.sql.*;
import java.util.ArrayList;
import java.util.List;

/**
 * Implementación real del IShopDAO.
 * Todo el SQL de la tienda vive aquí.
 *
 * Tablas que usa:
 *   store_items      → id, name, type, cost, image_url
 *   user_inventory   → user_id, item_id, purchased_at, is_equipped
 *   user_stats       → user_id, coins, has_streak_shield
 *   professor_config → user_id, avatar_item_id, background_item_id
 */
public class ShopDAOImpl implements IShopDAO {

    // ──────────────────────────────────────────────────────────────
    //  CATÁLOGO
    // ──────────────────────────────────────────────────────────────

    @Override
    public List<Product> getAllItems() {
        List<Product> items = new ArrayList<>();
        String sql = "SELECT id, name, type, cost, image_url " +
                     "FROM store_items " +
                     "ORDER BY type, cost";

        try (Connection conn = DatabaseConnection.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {

            while (rs.next()) {
                items.add(mapProduct(rs));
            }

        } catch (SQLException e) {
            System.err.println("[ShopDAO] Error en getAllItems: " + e.getMessage());
        }

        return items;
    }

    @Override
    public Product getItemById(int itemId) {
        String sql = "SELECT id, name, type, cost, image_url " +
                     "FROM store_items WHERE id = ?";

        try (Connection conn = DatabaseConnection.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {

            ps.setInt(1, itemId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return mapProduct(rs);
                }
            }

        } catch (SQLException e) {
            System.err.println("[ShopDAO] Error en getItemById: " + e.getMessage());
        }

        return null;
    }

    // ──────────────────────────────────────────────────────────────
    //  INVENTARIO
    // ──────────────────────────────────────────────────────────────

    @Override
    public List<Integer> getUserInventory(String userId) {
        List<Integer> ownedItemIds = new ArrayList<>();
        String sql = "SELECT item_id FROM user_inventory WHERE user_id = ?::uuid";

        try (Connection conn = DatabaseConnection.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {

            ps.setString(1, userId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    ownedItemIds.add(rs.getInt("item_id"));
                }
            }

        } catch (SQLException e) {
            System.err.println("[ShopDAO] Error en getUserInventory: " + e.getMessage());
        }

        return ownedItemIds;
    }

    @Override
    public boolean userOwnsItem(String userId, int itemId) {
        String sql = "SELECT 1 FROM user_inventory WHERE user_id = ?::uuid AND item_id = ?";

        try (Connection conn = DatabaseConnection.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {

            ps.setString(1, userId);
            ps.setInt(2, itemId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }

        } catch (SQLException e) {
            System.err.println("[ShopDAO] Error en userOwnsItem: " + e.getMessage());
        }

        return false;
    }

    @Override
    public Integer getEquippedItem(String userId, String itemType) {
        String sql = "SELECT ui.item_id " +
                     "FROM user_inventory ui " +
                     "JOIN store_items si ON si.id = ui.item_id " +
                     "WHERE ui.user_id = ?::uuid " +
                     "  AND si.type = ? " +
                     "  AND ui.is_equipped = true " +
                     "LIMIT 1";

        try (Connection conn = DatabaseConnection.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {

            ps.setString(1, userId);
            ps.setString(2, itemType);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return rs.getInt("item_id");
                }
            }

        } catch (SQLException e) {
            System.err.println("[ShopDAO] Error en getEquippedItem: " + e.getMessage());
        }

        return null;
    }

    // ──────────────────────────────────────────────────────────────
    //  COMPRA
    // ──────────────────────────────────────────────────────────────

    @Override
    public Purchase buyItem(String userId, int itemId) {
        Purchase result = new Purchase();
        result.setUserId(userId);
        result.setItemId(itemId);

        // 1. ¿Existe el ítem?
        Product item = getItemById(itemId);
        if (item == null) {
            result.setSuccess(false);
            result.setMessage("El ítem no existe.");
            return result;
        }

        result.setItemName(item.getName());
        result.setItemType(item.getType());
        result.setCostPaid(item.getCost());

        // 2. ¿Ya lo tiene? (streak_shield sí se puede recomprar para renovarlo)
        boolean isStreakShield = "streak_shield".equals(item.getType());
        if (!isStreakShield && userOwnsItem(userId, itemId)) {
            result.setSuccess(false);
            result.setMessage("Ya tienes este ítem.");
            return result;
        }

        // 3. Transacción: verificar monedas + descontar + insertar en inventario
        String checkCoinsSQL     = "SELECT coins FROM user_stats WHERE user_id = ?::uuid";
        String deductCoinsSQL    = "UPDATE user_stats SET coins = coins - ? " +
                                   "WHERE user_id = ?::uuid AND coins >= ?";
        String insertInvSQL      = "INSERT INTO user_inventory (user_id, item_id, purchased_at, is_equipped) " +
                                   "VALUES (?::uuid, ?, NOW(), false) " +
                                   "ON CONFLICT (user_id, item_id) DO NOTHING";
        String activateShieldSQL = "UPDATE user_stats SET has_streak_shield = true " +
                                   "WHERE user_id = ?::uuid";

        Connection conn = null;
        try {
            conn = DatabaseConnection.getConnection();
            conn.setAutoCommit(false);

            // 3a. Leer monedas actuales
            int currentCoins;
            try (PreparedStatement ps = conn.prepareStatement(checkCoinsSQL)) {
                ps.setString(1, userId);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) {
                        conn.rollback();
                        result.setSuccess(false);
                        result.setMessage("Usuario no encontrado.");
                        return result;
                    }
                    currentCoins = rs.getInt("coins");
                }
            }

            // 3b. ¿Alcanza el saldo?
            if (currentCoins < item.getCost()) {
                conn.rollback();
                result.setSuccess(false);
                result.setMessage("Monedas insuficientes. Tienes " + currentCoins +
                                  " y el ítem cuesta " + item.getCost() + ".");
                return result;
            }

            // 3c. Descontar monedas
            try (PreparedStatement ps = conn.prepareStatement(deductCoinsSQL)) {
                ps.setInt(1, item.getCost());
                ps.setString(2, userId);
                ps.setInt(3, item.getCost());
                int rows = ps.executeUpdate();
                if (rows == 0) {
                    conn.rollback();
                    result.setSuccess(false);
                    result.setMessage("No se pudo procesar el pago. Intenta de nuevo.");
                    return result;
                }
            }

            // 3d. Insertar en inventario
            try (PreparedStatement ps = conn.prepareStatement(insertInvSQL)) {
                ps.setString(1, userId);
                ps.setInt(2, itemId);
                ps.executeUpdate();
            }

            // 3e. Si es streak_shield, activarlo en user_stats
            if (isStreakShield) {
                try (PreparedStatement ps = conn.prepareStatement(activateShieldSQL)) {
                    ps.setString(1, userId);
                    ps.executeUpdate();
                }
            }

            conn.commit();

            result.setSuccess(true);
            result.setRemainingCoins(currentCoins - item.getCost());
            result.setMessage("¡Compra exitosa! Compraste: " + item.getName());

        } catch (SQLException e) {
            System.err.println("[ShopDAO] Error en buyItem: " + e.getMessage());
            if (conn != null) {
                try { conn.rollback(); } catch (SQLException ex) { /* ignorar */ }
            }
            result.setSuccess(false);
            result.setMessage("Error interno al procesar la compra.");

        } finally {
            if (conn != null) {
                try {
                    conn.setAutoCommit(true);
                    conn.close();
                } catch (SQLException e) { /* ignorar */ }
            }
        }

        return result;
    }

    // ──────────────────────────────────────────────────────────────
    //  EQUIPAR
    // ──────────────────────────────────────────────────────────────

    @Override
    public boolean equipItem(String userId, int itemId) {
        if (!userOwnsItem(userId, itemId)) {
            System.err.println("[ShopDAO] equipItem: usuario " + userId + " no posee ítem " + itemId);
            return false;
        }

        Product item = getItemById(itemId);
        if (item == null) return false;

        if ("streak_shield".equals(item.getType())) {
            System.err.println("[ShopDAO] equipItem: streak_shield no se equipa manualmente.");
            return false;
        }

        String unequipSQL = "UPDATE user_inventory ui " +
                            "SET is_equipped = false " +
                            "WHERE ui.user_id = ?::uuid " +
                            "  AND ui.item_id IN (" +
                            "    SELECT id FROM store_items WHERE type = ?" +
                            "  )";

        String equipSQL = "UPDATE user_inventory " +
                          "SET is_equipped = true " +
                          "WHERE user_id = ?::uuid AND item_id = ?";

        String updateProfessorSQL = "avatar".equals(item.getType())
            ? "UPDATE professor_config SET avatar_item_id = ?, updated_at = NOW() WHERE user_id = ?::uuid"
            : "UPDATE professor_config SET background_item_id = ?, updated_at = NOW() WHERE user_id = ?::uuid";

        Connection conn = null;
        try {
            conn = DatabaseConnection.getConnection();
            conn.setAutoCommit(false);

            try (PreparedStatement ps = conn.prepareStatement(unequipSQL)) {
                ps.setString(1, userId);
                ps.setString(2, item.getType());
                ps.executeUpdate();
            }

            try (PreparedStatement ps = conn.prepareStatement(equipSQL)) {
                ps.setString(1, userId);
                ps.setInt(2, itemId);
                ps.executeUpdate();
            }

            try (PreparedStatement ps = conn.prepareStatement(updateProfessorSQL)) {
                ps.setInt(1, itemId);
                ps.setString(2, userId);
                ps.executeUpdate();
            }

            conn.commit();
            return true;

        } catch (SQLException e) {
            System.err.println("[ShopDAO] Error en equipItem: " + e.getMessage());
            if (conn != null) {
                try { conn.rollback(); } catch (SQLException ex) { /* ignorar */ }
            }
            return false;

        } finally {
            if (conn != null) {
                try {
                    conn.setAutoCommit(true);
                    conn.close();
                } catch (SQLException e) { /* ignorar */ }
            }
        }
    }

    // ──────────────────────────────────────────────────────────────
    //  HELPER PRIVADO
    // ──────────────────────────────────────────────────────────────

    private Product mapProduct(ResultSet rs) throws SQLException {
        Product p = new Product();
        p.setId(rs.getInt("id"));
        p.setName(rs.getString("name"));
        p.setType(rs.getString("type"));
        p.setCost(rs.getInt("cost"));
        p.setImageUrl(rs.getString("image_url"));
        return p;
    }
}