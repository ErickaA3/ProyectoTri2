package com.project.model.content;

import java.util.List;

/**
 * Representa un esquema/diagrama generado por IA.
 * Se guarda en study_content con type = "schema".
 * El JSON tiene estructura jerárquica: nodo principal → subnodos → items.
 */
public class Diagram extends EducationalContent {

    private DiagramNode rootNode;

    public Diagram() {}

    public Diagram(String userId, String title, String sessionId, DiagramNode rootNode) {
        super(userId, "schema", title, sessionId);
        this.rootNode = rootNode;
    }

    public DiagramNode getRootNode() { return rootNode; }
    public void setRootNode(DiagramNode rootNode) { this.rootNode = rootNode; }

    // Nodo del esquema (puede tener hijos recursivamente)
    public static class DiagramNode {
        private String label;
        private List<DiagramNode> children;

        public DiagramNode() {}

        public DiagramNode(String label, List<DiagramNode> children) {
            this.label = label;
            this.children = children;
        }

        public String getLabel() { return label; }
        public void setLabel(String label) { this.label = label; }

        public List<DiagramNode> getChildren() { return children; }
        public void setChildren(List<DiagramNode> children) { this.children = children; }
    }
}
