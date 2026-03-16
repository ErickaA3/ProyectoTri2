package com.project.model.shop;

/**
 * Representa un avatar del profesor virtual en la tienda.
 * Hereda de Product — solo agrega el campo 'emoji' para la UI.
 */
public class Avatar extends Product {

    private String emoji;   

    // ────────────────────── constructores ──────────────────────

    public Avatar() {
        super();
        setType("avatar");
    }

    public Avatar(int id, String name, int cost, String imageUrl, String emoji) {
        super(id, name, "avatar", cost, imageUrl);   // constructor actual de Product
        this.emoji = emoji;
    }

    // ────────────────────── getters / setters ──────────────────────

    public String getEmoji()             { return emoji; }
    public void   setEmoji(String emoji) { this.emoji = emoji; }

    @Override
    public String toString() {
        return "Avatar{id=" + getId() + ", name='" + getName() + "', emoji='" + emoji + "', cost=" + getCost() + "}";
    }
}