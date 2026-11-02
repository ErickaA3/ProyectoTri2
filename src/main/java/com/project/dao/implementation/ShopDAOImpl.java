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
 *
 * Tablas que usa (V7):
 *   store_items      → catálogo (type → item_types)
 *   purchases        → cada gasto de monedas
 *   user_items       → ítems permanentes del usuario
 *   user_consumables → cada protector de racha (used_for_date = el día que cubrió)
 *   user_equipped    → un ítem equipado por tipo (la base exige que sea del usuario)
 *   v_user_balances  → monedas = lo ganado − lo gastado (no hay columna "coins")
 */
public class ShopDAOImpl implements IShopDAO {

    // ──────────────────────────────────────────────────────────────
    //  CATÁLOGO
    // ──────────────────────────────────────────────────────────────

    @Override
    public List<Product> getAllItems() {
        List<Product> items = new ArrayList<>();
        String sql = "SELECT id, name, type, cost, image_url FROM store_items "
                   + "WHERE is_active ORDER BY type, cost NULLS LAST, id";
        try (Connection conn = DatabaseConnection.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) items.add(mapProduct(rs));
        } catch (SQLException e) {
            System.err.println("[ShopDAO] Error en getAllItems: " + e.getMessage());
        }
        return items;
    }

    @Override
    public Product getItemById(int itemId) {
        String sql = "SELECT id, name, type, cost, image_url FROM store_items WHERE id = ?";
        try (Connection conn = DatabaseConnection.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, itemId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return mapProduct(rs);
            }
        } catch (SQLException e) {
            System.err.println("[ShopDAO] Error en getItemById: " + e.getMessage());
        }
        return null;
    }

    // ──────────────────────────────────────────────────────────────
    //  INVENTARIO
    // ──────────────────────────────────────────────────────────────

    /** Ítems permanentes del usuario, más el protector si tiene alguno sin usar. */
    @Override
    public List<Integer> getUserInventory(String userId) {
        List<Integer> ids = new ArrayList<>();
        String sql = """
            SELECT item_id FROM user_items WHERE user_id = ?::uuid
            UNION
            SELECT p.item_id FROM user_consumables c
            JOIN purchases p ON p.id = c.purchase_id
            WHERE p.user_id = ?::uuid AND c.used_for_date IS NULL
            """;
        try (Connection conn = DatabaseConnection.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, userId);
            ps.setString(2, userId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) ids.add(rs.getInt(1));
            }
        } catch (SQLException e) {
            System.err.println("[ShopDAO] Error en getUserInventory: " + e.getMessage());
        }
        return ids;
    }

    @Override
    public boolean userOwnsItem(String userId, int itemId) {
        String sql = "SELECT 1 FROM user_items WHERE user_id = ?::uuid AND item_id = ?";
        try (Connection conn = DatabaseConnection.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, userId);
            ps.setInt(2, itemId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        } catch (SQLException e) {
            System.err.println("[ShopDAO] Error en userOwnsItem: " + e.getMessage());
            return false;
        }
    }

    @Override
    public Integer getEquippedItem(String userId, String itemType) {
        String sql = "SELECT item_id FROM user_equipped WHERE user_id = ?::uuid AND item_type_code = ?";
        try (Connection conn = DatabaseConnection.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, userId);
            ps.setString(2, itemType);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : null;
            }
        } catch (SQLException e) {
            System.err.println("[ShopDAO] Error en getEquippedItem: " + e.getMessage());
            return null;
        }
    }

    @Override
    public int getUserCoins(String userId) {
        String sql = "SELECT coins FROM v_user_balances WHERE user_id = ?::uuid";
        try (Connection conn = DatabaseConnection.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, userId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        } catch (SQLException e) {
            System.err.println("[ShopDAO] Error en getUserCoins: " + e.getMessage());
            return 0;
        }
    }

    // ──────────────────────────────────────────────────────────────
    //  COMPRA
    // ──────────────────────────────────────────────────────────────

    /**
     * Compra en una transacción, con la fila del usuario bloqueada: dos compras
     * simultáneas no pueden gastar el mismo saldo. El saldo se calcula
     * (v_user_balances); la compra solo agrega una fila en purchases.
     */
    @Override
    public Purchase buyItem(String userId, int itemId) {
        Purchase result = new Purchase();
        result.setUserId(userId);
        result.setItemId(itemId);

        try (Connection conn = DatabaseConnection.getConnection()) {
            conn.setAutoCommit(false);
            try {
                // 1. ¿Existe, está a la venta, y es consumible?
                String name, type;
                Integer cost;
                boolean consumable;
                try (PreparedStatement ps = conn.prepareStatement("""
                        SELECT si.name, si.type, si.cost, it.is_consumable
                        FROM store_items si JOIN item_types it ON it.code = si.type
                        WHERE si.id = ? AND si.is_active
                        """)) {
                    ps.setInt(1, itemId);
                    ResultSet rs = ps.executeQuery();
                    if (!rs.next()) return fail(conn, result, "El ítem no existe.");
                    name = rs.getString("name");
                    type = rs.getString("type");
                    cost = (Integer) rs.getObject("cost");
                    consumable = rs.getBoolean("is_consumable");
                }
                result.setItemName(name);
                result.setItemType(type);
                if (cost == null) return fail(conn, result, "Este ítem se desbloquea por nivel, no se vende.");
                result.setCostPaid(cost);

                // 2. Bloquear al usuario y leer su saldo.
                try (PreparedStatement ps = conn.prepareStatement(
                        "SELECT 1 FROM users WHERE id = ?::uuid FOR UPDATE")) {
                    ps.setString(1, userId);
                    if (!ps.executeQuery().next()) return fail(conn, result, "Usuario no encontrado.");
                }

                // 3. Los permanentes no se compran dos veces (el protector sí se recompra).
                if (!consumable) {
                    try (PreparedStatement ps = conn.prepareStatement(
                            "SELECT 1 FROM user_items WHERE user_id = ?::uuid AND item_id = ?")) {
                        ps.setString(1, userId);
                        ps.setInt(2, itemId);
                        if (ps.executeQuery().next()) return fail(conn, result, "Ya tienes este ítem.");
                    }
                }

                int coins;
                try (PreparedStatement ps = conn.prepareStatement(
                        "SELECT coins FROM v_user_balances WHERE user_id = ?::uuid")) {
                    ps.setString(1, userId);
                    ResultSet rs = ps.executeQuery();
                    coins = rs.next() ? rs.getInt(1) : 0;
                }
                if (coins < cost) {
                    return fail(conn, result, "Monedas insuficientes. Tienes " + coins
                            + " y el ítem cuesta " + cost + ".");
                }

                // 4. Registrar la compra y lo que el usuario recibe.
                String purchaseId;
                try (PreparedStatement ps = conn.prepareStatement("""
                        INSERT INTO purchases (user_id, item_id, price_paid)
                        VALUES (?::uuid, ?, ?) RETURNING id
                        """)) {
                    ps.setString(1, userId);
                    ps.setInt(2, itemId);
                    ps.setInt(3, cost);
                    ResultSet rs = ps.executeQuery();
                    rs.next();
                    purchaseId = rs.getString(1);
                }
                try (PreparedStatement ps = conn.prepareStatement(consumable
                        ? "INSERT INTO user_consumables (purchase_id) VALUES (?::uuid)"
                        : "INSERT INTO user_items (user_id, item_id, purchase_id) "
                        + "SELECT user_id, item_id, id FROM purchases WHERE id = ?::uuid")) {
                    ps.setString(1, purchaseId);
                    ps.executeUpdate();
                }

                conn.commit();
                result.setSuccess(true);
                result.setRemainingCoins(coins - cost);
                result.setMessage("¡Compra realizada!");
                return result;

            } catch (SQLException e) {
                conn.rollback();
                throw e;
            } finally {
                conn.setAutoCommit(true);
            }
        } catch (SQLException e) {
            System.err.println("[ShopDAO] Error en buyItem: " + e.getMessage());
            result.setSuccess(false);
            result.setMessage("Error al procesar la compra.");
            return result;
        }
    }

    private Purchase fail(Connection conn, Purchase result, String message) throws SQLException {
        conn.rollback();
        result.setSuccess(false);
        result.setMessage(message);
        return result;
    }

    // ──────────────────────────────────────────────────────────────
    //  EQUIPAR
    // ──────────────────────────────────────────────────────────────

    /**
     * Equipa un ítem del usuario (uno por tipo). La base exige que el ítem sea
     * del usuario y del tipo correcto. Los ítems gratis (cost = 0, p. ej. el
     * fondo por defecto) se agregan al equiparlos.
     */
    @Override
    public boolean equipItem(String userId, int itemId) {
        try (Connection conn = DatabaseConnection.getConnection()) {
            conn.setAutoCommit(false);
            try {
                String type;
                Integer cost;
                try (PreparedStatement ps = conn.prepareStatement("""
                        SELECT si.type, si.cost FROM store_items si
                        JOIN item_types it ON it.code = si.type
                        WHERE si.id = ? AND it.is_equippable
                        """)) {
                    ps.setInt(1, itemId);
                    ResultSet rs = ps.executeQuery();
                    if (!rs.next()) { conn.rollback(); return false; }
                    type = rs.getString("type");
                    cost = (Integer) rs.getObject("cost");
                }

                if (cost != null && cost == 0) {
                    try (PreparedStatement ps = conn.prepareStatement("""
                            INSERT INTO user_items (user_id, item_id) VALUES (?::uuid, ?)
                            ON CONFLICT DO NOTHING
                            """)) {
                        ps.setString(1, userId);
                        ps.setInt(2, itemId);
                        ps.executeUpdate();
                    }
                }

                // Si no lo tiene, la llave foránea a user_items lo rechaza.
                try (PreparedStatement ps = conn.prepareStatement("""
                        INSERT INTO user_equipped (user_id, item_type_code, item_id)
                        VALUES (?::uuid, ?, ?)
                        ON CONFLICT (user_id, item_type_code) DO UPDATE SET item_id = EXCLUDED.item_id
                        """)) {
                    ps.setString(1, userId);
                    ps.setString(2, type);
                    ps.setInt(3, itemId);
                    ps.executeUpdate();
                }
                conn.commit();
                return true;

            } catch (SQLException e) {
                conn.rollback();
                // [SEGURIDAD] 23503 = el usuario no tiene ese ítem (equipar sin comprar).
                if (!"23503".equals(e.getSQLState())) {
                    System.err.println("[ShopDAO] Error en equipItem: " + e.getMessage());
                }
                return false;
            } finally {
                conn.setAutoCommit(true);
            }
        } catch (SQLException e) {
            System.err.println("[ShopDAO] Error en equipItem: " + e.getMessage());
            return false;
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
