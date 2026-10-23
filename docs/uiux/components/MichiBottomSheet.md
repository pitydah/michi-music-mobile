# Component Spec: MichiBottomSheet

**Package**: `org.michimusic.mobile.ui.components.premium`  
**Status**: ACTIVE  
**Last Updated**: 2026-10-22  

---

## 1. Visión General
`MichiBottomSheet` define la hoja modal inferior estándar del reproductor, empleada para menús de acciones de pista, selectores de ruta, detalles de cola y diálogo de playlists.

## 2. Especificación
- **Esquinas superiores:** Redondeo estándar de 24dp.
- **Drag handle:** Barra de arrastre centrada de 36dp x 4dp con borde redondeado (2dp).
- **Elevación tonal:** 8dp con color de contenedor oscuro (`SurfaceDark` o negro puro en alto contraste).
- **Comportamiento:** Salto directo de expansión parcial (`skipPartiallyExpanded = true`) para evitar estados intermedios inconsistentes.
