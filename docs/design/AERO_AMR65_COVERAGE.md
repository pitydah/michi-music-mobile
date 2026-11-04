# Michi Aero · AMR-65 · Cobertura parcial de materiales y rutas

**Base del parche:** `pitydah/michi-music-mobile` @ `09a48411617daf81d2f93576fd27690da8c7962f` · **Fecha:** 2026-11-03.

**Estado:** `PARTIAL_PATCH_IN_PROGRESS` · **Progreso estimado de implementación:** 65% · **Aplicado al repositorio:** 0% · **Compilación Android:** `NOT_RUN` · **Dispositivo:** `NOT_AVAILABLE`.

Esta matriz es un seguimiento de **archivos intervenidos**, no de una pantalla certificada. `SOURCE_EDITED` significa diff incorporado y revisión estática; `INDIRECT_ONLY` y `PENDING` no equivalen a finalización. No se declara cobertura 15/15 certificada.

## Rutas y componentes de primer nivel

| Área | Estado | Qué quedó intervenido | Pendiente no certificado |
|---|---|---|---|
| Home | SOURCE_EDITED | Hero Anodized, Continuar escuchando, vacío, tiles | Capturas / integración |
| Biblioteca / Álbumes | SOURCE_EDITED | Cuatro wrappers ligeros Plain | Resto de estados, QA |
| Buscar | SOURCE_EDITED | Campo Inset, resultados Plain | Semántica de IME en suite |
| Now Playing | SOURCE_EDITED | Portada única, banda ciruela compacta, pie centrado | QA responsive en Compose |
| Devices | SOURCE_EDITED | Local y nodos LAN, sin fingir conexión | Detalle + estados especiales |
| Remote | SOURCE_EDITED | Placa sin portada inventada, salida solicitada | Variantes pending y error |
| Settings | SOURCE_EDITED | Acciones Satin, secciones Anodized, controles Plain | Todas categorías/estados |
| Playlist | SOURCE_EDITED | Filas Plain con acciones existentes | Diálogos y listas extensas |
| Playlist Detail | SOURCE_EDITED | Fila Plain accesible, menú de orden intacto | Estados error/renombrar |
| Queue | SOURCE_EDITED | Banda de estado Satin, fila activa del diálogo Satin | Otras acciones/confirmaciones |
| Audio Route | SOURCE_EDITED | Observado Anodized, anunciados Plain | Verdad técnica y UI íntegra |
| Diagnostics | SOURCE_EDITED | Telemetría Plain, resultados conservan estado | Reportes sensibles / layouts |
| Sync / Michi Link | SOURCE_EDITED | Descubrimiento Satin, progreso Anodized | Wizard, QR, fallos, AirCat |
| Synced Tracks | SOURCE_EDITED | Filas offline Plain; sin onClick cuando no descargada | Paging y estados de lista |
| Otros modales y áreas del ecosistema | PENDING | Superficie común del bottom sheet heredada de AMR-38 | Inventario de modales + Settings completos |

## Contratos de seguridad visual revisados

- `AeroSignalRouteStrip` sigue siendo un único panel ciruela de dos columnas con pie **centrado**; no se vuelve a introducir el contenedor gigante.
- La jerarquía es portada > título > transporte > telemetría. Ningún control icon-only adquiere círculo o fondo independiente.
- DSP: el gráfico `PeqResponseCurve` conserva su cálculo y el aviso «NO es una medición»; solo se cambia el canal de dibujo a `Inset`.
- Audio Route: la ruta observada puede ser `Anodized`, pero los dispositivos solamente anunciados se muestran `Plain`. Las frecuencias anunciadas siguen sin demostrar una salida física.
- Sincronización: búsqueda, progreso desconocido, conexión y error conservan sus estados reales; no hay avance inventado.
- Cola: únicamente **un índice activo** recibe tratamiento enfocado, incluso con pistas duplicadas; se conserva la identidad por índice.
- Biblioteca offline: una pista sin archivo descargado deja de ser botón interactivo; conserva el rótulo «Pendiente».
- Diagnóstico: el código que expurga tokens/secretos y la exportación del informe no se modifican.
- La textura PNG ya incluida sigue siendo propia, 128×128 y reproducible. No se descarga ni redistribuye material externo no necesario.

## Pruebas y lagunas

**Ejecutado:** `git diff --check`, `git apply --check`, `git apply` solo en copia aislada, comparación de archivos originales/resultado, análisis PSI de los `.kt` modificados para errores de sintaxis. **Pendiente:** compilación Gradle Normal/F-Droid, Robolectric, lint, tests de navegación, capturas o benchmark. Estas ausencias **no** constituyen PASS.

**Tests incorporados sin ejecutar:** `AeroMaterialIntegrationTest`, `AeroScreenMaterialHierarchyTest`, `AeroSignalStripCompactTest`, `AeroFinishSpecTest`, `AeroSecondaryMaterialCoverageTest`. Los tests no prueban una pantalla visualmente perfecta sin renderizado; se exige revisión al correr Gradle.

## Referencias materiales y licencias

La matriz por componente, el estado de incorporación y la licencia de cada referencia están en [`AERO_MATERIAL_REFERENCE_LIBRARY.md`](AERO_MATERIAL_REFERENCE_LIBRARY.md). Autoridades: [Chris Banes component design](https://github.com/chrisbanes/skills/blob/main/skills/compose-component-design/SKILL.md), [Android Compose draw modifiers](https://developer.android.com/develop/ui/compose/graphics/draw/modifiers), [Paper Shaders Android (Apache 2.0)](https://github.com/AndreFrelicot/paper-shaders-android), [Haze (Apache 2.0)](https://github.com/chrisbanes/haze), [Compose Unstyled (MIT)](https://github.com/composablehorizons/compose-unstyled), [AeroPlay como referencia visual sin copiar recursos](https://dribbble.com/shots/27588212-AeroPlay-Car-Media-Player).

El parche **sigue sin estar listo para aplicarse**. No modificar `master` hasta completar todas las rutas, modales y gates offline. El 65% es una estimación de ingeniería según componentes/rutas intervenidos, no un resultado de tests Android.
