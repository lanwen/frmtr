package dev.lanwen.frmtr.doc;

/**
 * The flat-vs-break verdict a layout decision reaches, shared by every walker over a {@link Doc} so a verdict recorded
 * under a group id in one walk seeds another. One enum, not one per walker, is what lets a ranking probe inherit the
 * modes the deciding walk already chose.
 */
enum GroupMode {
    FLAT,
    BREAK,
}
