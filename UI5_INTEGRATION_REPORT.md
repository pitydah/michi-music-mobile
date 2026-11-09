# UI5 — Informe de integración acumulada

## Fuente y baseline

- Repositorio: `pitydah/michi-music-mobile`.
- Baseline de integración: `master@7917d5c29c859e395455c0d3b84c1fc966f627e3`.
- Fuente UI5 canónica: `ui5.patch`, 335 804 bytes, SHA-256
  `c6f3cad9e4e5f14554fbeecf315ef647a238a1ec495a25344b5e2aeeb5aa6b9c`.
- Plan: `plan.md`, 366 489 bytes, SHA-256
  `e4b5151037f89061c4f5d08b97eca18401fb44b3e31bc3b690801f28e08acb5f`.
- El parche declara base `5d1dd07b926197040ea9a3a64caef18f68e4b01b` y
  contiene UI5.1–UI5.32 de forma acumulada.

El parche se aplicó primero sobre su base exacta, sin conflictos, y se
confirmó allí como `34ffa25`. Después se trasladó con `cherry-pick` sobre el
`master` actual para que cualquier divergencia apareciera explícitamente.

## Conflictos y reconciliación

El parche y el `master` actual solo se solapaban en dos archivos:

- `SettingsScreen.kt`: se conservaron la sección `APPEARANCE` de UI5 y la
  sección `LIBRARY_STORAGE` del directorio musical configurable de PR #17.
- `SearchViewModel.kt`: se conservaron la búsqueda estructurada, el filtro de
  playlists y la normalización de UI5, junto con la recarga forzada que PR #17
  necesita cuando cambia la raíz musical.

No quedan marcadores de conflicto.

## Ajustes de integración

- Una revisión confirmada de la raíz musical invalida una carga de catálogo en
  curso. La carga antigua no puede publicar resultados ni finalizar el estado
  de una raíz nueva.
- El filtro `PLAYLISTS` publica un resultado estructurado vacío con su filtro y
  generación, en lugar de dejar el snapshot anterior.
- `HomeScreen` y `PlaylistScreen` reciben `AppDao` como dependencia explícita
  para que sus pruebas de UI no dependan de un contenedor Koin global.
- Las pruebas de navegación aceptan que Playlists es ahora una pestaña
  primaria.
- Las pruebas de detalle de playlist desplazan la lista antes de interactuar
  con filas que quedan fuera del viewport tras el nuevo hero visual.

## Evidencia local

Todas las tareas Gradle se ejecutaron con el workflow gestionado
`34c7065a22805c88c3bc85848ca9b155`.

| Gate | Resultado |
|---|---|
| `:data:testDebugUnitTest` | PASS |
| `:app:testNormalDebugUnitTest` | PASS |
| `:app:testFdroidDebugUnitTest` | PASS |
| `lint` | PASS |
| `:app:compileNormalDebugAndroidTestKotlin` | PASS |
| `:app:compileFdroidDebugAndroidTestKotlin` | PASS |
| `assembleNormalDebug`, `assembleFdroidDebug` | PASS |
| `assembleNormalRelease`, `assembleFdroidRelease` | PASS |
| `tools/ui4_acceptance.py` | 47/47 PASS |
| Contrato modal Aero | 16/16 PASS |
| Contrato release Aero | 18/18 estático PASS |
| Verificador launcher UI3 | PASS |

La compilación de AndroidTest no equivale a ejecución instrumentada. No se
ejecutaron pruebas físicas ni se instaló este candidato en el teléfono.

## Límites que siguen abiertos

- Casos físicos consolidados U45-01…U45-20 y de cola U45-21…U45-33.
- TalkBack, foco, rotación y tamaños reales de interacción.
- Waveform con audio local real y formatos PCM admitidos.
- Salto remoto `/queue/jump` contra un servidor real y sus estados
  PENDING/ACCEPTED/CONFIRMED.
- Glass/backdrop en hardware API 31+ y su fallback.
- Rendimiento, batería y temperatura.
- La integración no autoriza merge ni release.

El PR debe permanecer en borrador hasta que el CI del head exacto y los gates
físicos aplicables estén resueltos.
