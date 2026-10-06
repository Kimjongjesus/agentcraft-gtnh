package dev.agentcraft.gtnh.edit;

/**
 * One kind of placeable office panel, as the edit tool sees it (the palette, the inspector, the
 * safety gate). Pure description; the block behind it is looked up by {@link #id} on the Forge
 * side. A later card adds a panel kind by registering one of these (and its block) in
 * {@link PanelTypes}; the editor screens list and edit it with no editor changes.
 */
public final class PanelType {

    /** What the binding names: an agent id, a board slug, an agent or "fleet", nothing, or (sign) the overflow pointer. */
    public static final String AGENT = "agent", BOARD = "board", LAMP = "agent-or-fleet", NONE = "none", SIGN = "sign";

    public final String id, name, description, source, previewPanel;
    public final boolean faced, resizable, placeable;
    public final int defW, defH;

    /**
     * @param id           registry id, also the block's registry name ("task_wall")
     * @param previewPanel card-4 PanelRenderer id used for the palette / inspector preview, or ""
     * @param placeable    false for kinds the tool must never place itself (the vanilla overflow sign)
     */
    public PanelType(String id, String name, String description, String source, boolean faced, boolean resizable, int defW,
        int defH, String previewPanel, boolean placeable) {
        this.id = id;
        this.name = name;
        this.description = description;
        this.source = source;
        this.faced = faced;
        this.resizable = resizable;
        this.defW = defW;
        this.defH = defH;
        this.previewPanel = previewPanel == null ? "" : previewPanel;
        this.placeable = placeable;
    }

    /** Default binding for a fresh panel of this kind ("all" for board panels, else unbound). */
    public String defaultBinding() {
        return BOARD.equals(source) ? "all" : LAMP.equals(source) ? "fleet" : "";
    }

    public PanelSpec fresh(String facing) {
        return new PanelSpec(id, faced ? facing : "north", defaultBinding(), resizable ? defW : 1, resizable ? defH : 1, "", "");
    }

    /** Does this binding make sense for the kind (the inspector offers only these; the engine re-checks)? */
    public boolean accepts(String binding) {
        String b = binding == null ? "" : binding;
        switch (source) {
            case NONE:
            case SIGN:
                return b.isEmpty();
            case BOARD:
                return b.isEmpty() || "all".equals(b) || PanelSpec.BINDING.matcher(b)
                    .matches();
            case LAMP:
                return b.isEmpty() || "fleet".equals(b) || PanelSpec.BINDING.matcher(b)
                    .matches();
            default:
                return b.isEmpty() || !"fleet".equals(b) && !"all".equals(b) && PanelSpec.BINDING.matcher(b)
                    .matches();
        }
    }

    /** A spec of this kind with size/binding/facing forced into what the kind allows. */
    public PanelSpec normalize(PanelSpec s) {
        String b = accepts(s.binding) ? s.binding : defaultBinding();
        int w = resizable ? s.w : 1, h = resizable ? s.h : 1;
        return new PanelSpec(id, faced ? s.facing : "north", b, w, h, s.label, s.theme);
    }
}
