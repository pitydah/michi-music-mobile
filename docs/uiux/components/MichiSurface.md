# Component Spec: MichiSurface

**Package**: `org.michimusic.mobile.ui.components.premium`  
**Status**: ACTIVE  
**Last Updated**: 2026-10-22  

---

## 1. Visión General
`MichiSurface` es el componente de superficie semántica reactivo al tema y a las opciones de accesibilidad visual de Michi Music Mobile.

## 2. Niveles Semánticos (`MichiSurfaceLevel`)
1. **Canvas:** Fondo obsidiana profundo (`SurfaceObsidian` o negro puro en alto contraste).
2. **Content:** Contenedores principales de información (`SurfaceDark` o `#101216` en alto contraste).
3. **Raised:** Tarjetas, celdas de lista e ítems elevados con borde translúcido (`GlassBorderLow`).
4. **Floating:** Overlays, diálogos, mini-reproductor y docks flotantes con elevación sombreada de 12dp y borde destacado (`GlassBorderHigh`).

## 3. Adaptación a Alto Contraste
Cuando `LocalVisualPreferences.current.highContrast` es verdadero:
- Los rellenos de vidrio semitransparentes cambian a fondos sólidos de contraste superior.
- Se dibuja automáticamente un borde de alto contraste (1dp con opacidad del 85% de `PureWhite`).
- Se desactivan las sombras difusas para maximizar la legibilidad en pantallas AMOLED u exteriores.
