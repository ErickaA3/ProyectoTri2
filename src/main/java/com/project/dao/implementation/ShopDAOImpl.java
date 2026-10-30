package com.project.dao.implementation;

import com.project.dao.interfaces.IShopDAO;
import com.project.database.DatabaseConnection;
import com.project.model.shop.Product;
import com.project.model.shop.Purchase;
import com.project.util.GamificationService;

import java.sql.*;
import java.time.Instant;
import java.time.LocalDate;
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

    private static final String SHIELD_ACTIVE_MESSAGE =
        "Ya tienes un protector de racha activo. Vence 24 horas después de comprarlo.";
    private static final String SHIELD_PENDING_MESSAGE =
        "Tu protector anterior está cuidando tu racha de ayer. Estudia hoy para usarlo "
        + "y después puedes comprar otro.";

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
    //  MONEDAS
    // ──────────────────────────────────────────────────────────────

    @Override
    public int getUserCoins(String userId) {
        String sql = "SELECT coins FROM user_stats WHERE user_id = ?::uuid";

        try (Connection conn = DatabaseConnection.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {

            ps.setString(1, userId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return rs.getInt("coins");
            }

        } catch (SQLException e) {
            System.err.println("[ShopDAO] Error en getUserCoins: " + e.getMessage());
        }

        return 0;
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

        // 2. ¿Ya lo tiene? (streak_shield se puede recomprar cuando se gastó el anterior)
        boolean isStreakShield = "streak_shield".equals(item.getType());
        if (!isStreakShield && userOwnsItem(userId, itemId)) {
            result.setSuccess(false);
            result.setMessage("Ya tienes este ítem.");
            return result;
        }

        // 3. Transacción: verificar monedas + descontar + insertar en inventario
        // FOR UPDATE: dos compras simultáneas del mismo usuario se atienden de a una,
        // así la segunda ya ve el protector que activó la primera.
        String checkCoinsSQL     = "SELECT coins, has_streak_shield, streak_shield_until, streak_last_activity " +
                                   "FROM user_stats WHERE user_id = ?::uuid FOR UPDATE";
        String deductCoinsSQL    = "UPDATE user_stats SET coins = coins - ? " +
                                   "WHERE user_id = ?::uuid AND coins >= ?";
        String insertInvSQL      = "INSERT INTO user_inventory (user_id, item_id, purchased_at, is_equipped) " +
                                   "VALUES (?::uuid, ?, NOW(), false) " +
                                   "ON CONFLICT (user_id, item_id) DO NOTHING";
        String activateShieldSQL = "UPDATE user_stats SET has_streak_shield = true, " +
                                   "streak_shield_until = NOW() + ? * INTERVAL '1 second' " +
                                   "WHERE user_id = ?::uuid " +
                                   "RETURNING (EXTRACT(EPOCH FROM streak_shield_until) * 1000)::bigint AS until_ms";

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
                    if (isStreakShield) {
                        boolean hasShield     = rs.getBoolean("has_streak_shield");
                        Timestamp untilTs     = rs.getTimestamp("streak_shield_until");
                        Instant shieldUntil   = untilTs != null ? untilTs.toInstant() : null;
                        Date lastTs           = rs.getDate("streak_last_activity");
                        LocalDate lastActivity = lastTs != null ? lastTs.toLocalDate() : null;

                        String blocked = null;
                        if (GamificationService.isShieldActive(hasShield, shieldUntil)) {
                            blocked = SHIELD_ACTIVE_MESSAGE;
                        } else if (GamificationService.isShieldStillUseful(hasShield, shieldUntil, lastActivity)) {
                            blocked = SHIELD_PENDING_MESSAGE;
                        }
                        if (blocked != null) {
                            conn.rollback();
                            result.setSuccess(false);
                            result.setMessage(blocked);
                            return result;
                        }
                    }
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
                    ps.setLong(1, GamificationService.SHIELD_DURATION.getSeconds());
                    ps.setString(2, userId);
                    try (ResultSet rs = ps.executeQuery()) {
                        if (rs.next()) result.setStreakShieldUntil(rs.getLong("until_ms"));
                    }
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
        Product item = getItemById(itemId);
        if (item == null) return false;

        if ("streak_shield".equals(item.getType())) {
            System.err.println("[ShopDAO] equipItem: streak_shield no se equipa manualmente.");
            return false;
        }

        Connection conn = null;
        PreparedStatement ps = null;
        try {
            conn = DatabaseConnection.getConnection();
            conn.setAutoCommit(false);

            // 1. Los ítems gratis (cost 0, ej. el fondo por defecto) se agregan
            //    al inventario al equiparlos. Los de pago solo se equipan si se compraron.
            if (item.getCost() == 0) {
                ps = conn.prepareStatement(
                    "INSERT INTO user_inventory (user_id, item_id, purchased_at, is_equipped) " +
                    "VALUES (?::uuid, ?, NOW(), false) ON CONFLICT (user_id, item_id) DO NOTHING");
                ps.setString(1, userId);
                ps.setInt(2, itemId);
                ps.executeUpdate();
                ps.close();
            }

            // 2. Desequipar todos del mismo tipo
            ps = conn.prepareStatement(
                "UPDATE user_inventory SET is_equipped = false " +
                "WHERE user_id = ?::uuid AND item_id IN " +
                "(SELECT id FROM store_items WHERE type = ?)");
            ps.setString(1, userId);
            ps.setString(2, item.getType());
            ps.executeUpdate();
            ps.close();

            // 3. Equipar el nuevo. Si no actualiza ninguna fila, el usuario no lo
            //    tiene: se deshace el paso 2 y queda equipado lo que tenía antes.
            ps = conn.prepareStatement(
                "UPDATE user_inventory SET is_equipped = true " +
                "WHERE user_id = ?::uuid AND item_id = ?");
            ps.setString(1, userId);
            ps.setInt(2, itemId);
            int equipped = ps.executeUpdate();
            ps.close();
            ps = null;

            if (equipped == 0) {
                conn.rollback();
                return false;
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
            if (ps != null) { try { ps.close(); } catch (SQLException e) { /* ignorar */ } }
            if (conn != null) {
                try { conn.setAutoCommit(true); conn.close(); } catch (SQLException e) { /* ignorar */ }
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