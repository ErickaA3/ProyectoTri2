package com.project.dao.interfaces;

import com.project.model.shop.Product;
import com.project.model.shop.Purchase;

import java.util.List;

/**
 * Contrato del DAO de la tienda.
 * Define QUÉ operaciones existen. El cómo lo implementa ShopDAOImpl.
 *
 * Regla de la guía: solo define firmas de métodos, sin lógica ni SQL aquí.
 */
public interface IShopDAO {

    // ──────────────────────────────────────────────────────────────
    //  CATÁLOGO — store_items
    // ──────────────────────────────────────────────────────────────

    /**
     * Devuelve todos los ítems disponibles en la tienda.
     * El frontend los usa para construir la grilla de avatares,
     * fondos y productos.
     */
    List<Product> getAllItems();

    /**
     * Devuelve un ítem específico por su ID.
     * Se usa antes de procesar una compra para validar precio y existencia.
     */
    Product getItemById(int itemId);

    // ──────────────────────────────────────────────────────────────
    //  INVENTARIO — user_inventory
    // ──────────────────────────────────────────────────────────────

    /**
     * Devuelve todos los IDs de ítems que el usuario ya compró.
     * El frontend los necesita para marcar "✓ TUYO" en la UI.
     */
    List<Integer> getUserInventory(String userId);

    /**
     * Verifica si el usuario ya posee un ítem específico.
     * Se usa en buyItem() para evitar compras duplicadas de avatares/fondos.
     */
    boolean userOwnsItem(String userId, int itemId);

    /**
     * Devuelve el ítem actualmente equipado de un tipo específico.
     * Por ejemplo: getEquippedItem(userId, "background") → "bg-galaxy"
     */
    Integer getEquippedItem(String userId, String itemType);

    // ──────────────────────────────────────────────────────────────
    //  COMPRA — user_inventory + user_stats
    // ──────────────────────────────────────────────────────────────

    /**
     * Ejecuta la compra completa:
     *   1. Verifica que el usuario tenga monedas suficientes.
     *   2. Verifica que no sea una compra duplicada (avatares/fondos).
     *   3. INSERT en user_inventory.
     *   4. UPDATE coins en user_stats (descuenta el precio).
     *
     * @return Purchase con success=true y remainingCoins,
     *         o success=false y message explicando el error.
     */
    Purchase buyItem(String userId, int itemId);

    /**
     * Equipa un ítem que el usuario ya posee.
     * Solo aplica a "avatar" y "background" — los consumibles se aplican automáticamente.
     *
     * @return true si se equipó correctamente.
     */
    boolean equipItem(String userId, int itemId);
}