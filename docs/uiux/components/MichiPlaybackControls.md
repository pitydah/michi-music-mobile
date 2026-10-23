# Component Spec: MichiPlaybackControls

**Package**: `org.michimusic.mobile.ui.components.premium`  
**Status**: ACTIVE  
**Last Updated**: 2026-10-22  

---

## 1. Visión General
`MichiPlaybackControls` estandariza el conjunto de controles primarios de reproducción multimedia (Play/Pausa, Pista Siguiente, Pista Anterior, Repetición y Modo Aleatorio) en Michi Music Mobile.

## 2. Reglas de Diseño y Accesibilidad
1. **Iconografía sólida y neutra:**
   - Íconos en color blanco puro (`PureWhite`) sin pastillas ni fondos circulares artificiales en botones secundarios (`skipNext`, `skipPrevious`, `repeat`, `shuffle`).
2. **Targets de Toque (WCAG 2.5.5 / Android Accessibility):**
   - Todos los botones secundarios garantizan un área de contacto mínima de `48dp x 48dp` (`MichiSpacing.minTouchTarget`).
   - El botón principal Play/Pause posee un diámetro de `68dp`.
3. **Respeto a Preferencias de Movimiento (`reducedMotion`):**
   - La escala animada con rebote (`spring`) del botón de reproducción se desactiva instantáneamente con `snap()` si `reducedMotion` está activo en `LocalVisualPreferences`.
4. **Desacoplamiento y Estado Puro:**
   - Componente completamente *stateless*. Recibe `PlaybackControlUiState` y emite funciones lambda de acción hacia el invocador (`sessionManager` / `playerController`).
