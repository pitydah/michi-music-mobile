# Michi Aero · Límites de la UI Android (AMR-80)

- Tema de ventana `res/values/themes.xml` y icono adaptativo launcher deben contrastarse con `SurfaceObsidian #090C12` y coral Michi; no modificar tema/brand de sistema de forma especulativa sin comparar recursos reales.
- `MainActivity` usa edge-to-edge y permisos del SO. El código de este parche no altera permisos ni layout de barras del sistema.
- La notificación multimedia es administrada por Android Media3; sus superficies no admiten el compositor Aero en Jetpack Compose. Confirmar accesibilidad, títulos, carátulas y controles si hay suite instrumentada disponible, pero no sustituir proveedor ni dibujar efectos gráficos sobre la notificación.
- Modo alto contraste / fuentes aumentadas: `AeroFinish` decorativo debe caer a `Plain`; los colores semánticos no deben convertirse en evidencia técnica falsa.
- Sin teléfono físico: `DEVICE_UNVERIFIED`, `BATTERY_NOT_MEASURED`, `PERF_NOT_MEASURED`. Un emulador, si se usa, se etiqueta por separado.
