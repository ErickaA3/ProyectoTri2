package com.project.model.shop;

import java.time.LocalDateTime;

/**
 * Mapea la tabla user_inventory y sirve como respuesta del endpoint de compra.
 *
 * Columnas reales de user_inventory:
 *   user_id | item_id | purchased_at | is_equipped
 *
 * Nota: PRIMARY KEY (user_id, item_id) — la BD ya previene compras duplicadas.
 */
public class Purchase {

    // ── Campos que van a la tabla user_inventory ──────────────────
    private String        userId;          // UUID como String
    private int           itemId;
    private LocalDateTime purchasedAt;
    private boolean       isEquipped;      // columna 'is_equipped' en BD (no 'equipped')

    // ── Campos extra para la respuesta HTTP (no van a la BD) ──────
    private String  itemName;
    private String  itemType;
    private int     costPaid;
    private int     remainingCoins;
    private boolean success;
    private String  message;

    // ────────────────────── constructores ──────────────────────

    public Purchase() {}

    public Purchase(String userId, int itemId, String itemName, String itemType, int costPaid) {
        this.userId      = userId;
        this.itemId      = itemId;
        this.itemName    = itemName;
        this.itemType    = itemType;
        this.costPaid    = costPaid;
        this.purchasedAt = LocalDateTime.now();
        this.isEquipped  = false;
    }

    // ────────────────────── getters / setters ──────────────────────

    public String        getUserId()                        { return userId; }
    public void          setUserId(String userId)           { this.userId = userId; }

    public int           getItemId()                        { return itemId; }
    public void          setItemId(int itemId)              { this.itemId = itemId; }

    public LocalDateTime getPurchasedAt()                           { return purchasedAt; }
    public void          setPurchasedAt(LocalDateTime purchasedAt)  { this.purchasedAt = purchasedAt; }

    public boolean       isEquipped()                       { return isEquipped; }
    public void          setEquipped(boolean isEquipped)    { this.isEquipped = isEquipped; }

    public String        getItemName()                      { return itemName; }
    public void          setItemName(String itemName)       { this.itemName = itemName; }

    public String        getItemType()                      { return itemType; }
    public void          setItemType(String itemType)       { this.itemType = itemType; }

    public int           getCostPaid()                      { return costPaid; }
    public void          setCostPaid(int costPaid)          { this.costPaid = costPaid; }

    public int           getRemainingCoins()                        { return remainingCoins; }
    public void          setRemainingCoins(int remainingCoins)      { this.remainingCoins = remainingCoins; }

    public boolean       isSuccess()                        { return success; }
    public void          setSuccess(boolean success)        { this.success = success; }

    public String        getMessage()                       { return message; }
    public void          setMessage(String message)         { this.message = message; }
}