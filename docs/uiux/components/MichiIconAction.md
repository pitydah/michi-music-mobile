# Component Spec: MichiIconAction

**Package**: `org.michimusic.mobile.ui.components.premium`  
**Status**: ACTIVE  
**Last Updated**: 2026-10-22  

---

## 1. Visión General
`MichiIconAction` es el bloque de construcción atómico para botones de acción con ícono en la interfaz de usuario.

## 2. Especificación Técnica
- **Dimensión mínima:** 48dp x 48dp (`MichiSpacing.minTouchTarget`).
- **Fondo:** Transparente por defecto (sin contenedor elevado salvo en estados de foco/toque).
- **Indicación táctil:** Ripple delimitado sutil derivado del color del ícono (alpha 35%).
- **Semántica:** `Role.Button` explícito con `contentDescription` obligatorio para lectores de pantalla TalkBack.
