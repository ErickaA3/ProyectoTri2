package com.project.model.shop;


/**
 * Representa un fondo animado en la tienda.
 * Hereda de Product — agrega 'cssClass' para aplicar el fondo en el HTML.
 */
public class Theme extends Product {

    private String cssClass;   // ej: "bg-galaxy", "bg-volcano"

    // ────────────────────── constructores ──────────────────────

    public Theme() {
        super();
        setType("background");
    }

    public Theme(int id, String name, int cost, String imageUrl, String cssClass) {
        super(id, name, "background", cost, imageUrl);   // constructor actual de Product
        this.cssClass = cssClass;
    }

    // ────────────────────── getters / setters ──────────────────────

    public String getCssClass()                  { return cssClass; }
    public void   setCssClass(String cssClass)   { this.cssClass = cssClass; }

    @Override
    public String toString() {
        return "Theme{id=" + getId() + ", name='" + getName() + "', cssClass='" + cssClass + "', cost=" + getCost() + "}";
    }
}