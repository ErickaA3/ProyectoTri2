package com.project.model.shop;

/**
 * Representa un ítem de la tienda.
 * Mapea directamente la tabla store_items.
 *
 * Columnas reales de store_items:
 *   id | name | type | cost | image_url
 *
 * Tipos válidos (definidos por el CHECK de la tabla):
 *   'avatar' | 'background' | 'streak_shield'
 */
public class Product {

    private int    id;
    private String name;
    private String type;        // "avatar" | "background" | "streak_shield"
    private int    cost;        // en monedas — columna 'cost' en BD (no 'price')
    private String imageUrl;    // ruta de imagen — columna 'image_url' en BD

    // Campo extra calculado en el DAO, no existe en store_items.
    // El frontend lo necesita para mostrar "✓ TUYO" sin hacer dos peticiones.
    private boolean owned;

    // ────────────────────── constructores ──────────────────────

    public Product() {}

    public Product(int id, String name, String type, int cost, String imageUrl) {
        this.id       = id;
        this.name     = name;
        this.type     = type;
        this.cost     = cost;
        this.imageUrl = imageUrl;
    }

    // ────────────────────── getters / setters ──────────────────────

    public int     getId()                  { return id; }
    public void    setId(int id)            { this.id = id; }

    public String  getName()                { return name; }
    public void    setName(String name)     { this.name = name; }

    public String  getType()                { return type; }
    public void    setType(String type)     { this.type = type; }

    public int     getCost()                { return cost; }
    public void    setCost(int cost)        { this.cost = cost; }

    public String  getImageUrl()                    { return imageUrl; }
    public void    setImageUrl(String imageUrl)      { this.imageUrl = imageUrl; }

    public boolean isOwned()                        { return owned; }
    public void    setOwned(boolean owned)           { this.owned = owned; }

    @Override
    public String toString() {
        return "Product{id=" + id + ", name='" + name + "', type='" + type + "', cost=" + cost + "}";
    }
}