package dev.agentcraft.gtnh.ui.panel;

/**
 * One kind of in-world panel (a screen drawn on a block's front face). Implementations are
 * registered by id in {@link PanelRegistry}; which block shows which panel, its theme and its
 * resolution come from the layout file ({@link PanelLayout}), so a later in-game edit tool (card 6)
 * can re-bind and re-arrange panels by writing that file, without touching renderer code.
 *
 * <p>
 * A renderer draws into {@link PanelContext}'s canvas: origin top-left, +x right, +y down, units
 * of {@code 1 / pxPerBlock} block. It picks its own type sizes from the physical size helpers
 * ({@link PanelContext#em(double)}) and its level of detail from {@link PanelContext#lod}.
 */
public interface PanelRenderer {

    /** Registry id used in the layout file, e.g. "kanban". */
    String id();

    /** Data source id this panel reads ({@link PanelRegistry#registerSource}), e.g. "board". */
    String source();

    /** One-line human description (for the docs and card 6's edit tool). */
    String describe();

    void render(PanelContext c);
}
