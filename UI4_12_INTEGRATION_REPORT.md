# UI4.12 — Informe de integración Wizard + cierre de auditorías

## 1. Identidad del candidato

- Rama de convergencia: `feat/ui4-aero-motion`.
- UI4 previo: `6aafa4566227be491b97317c88eca5556b4d2611`.
- `origin/master` integrado: `577415205a8b9783e776727ab626666c6da8fb44` (merge del PR #15, Wizard + iconos).
- Commit de merge local: `3c3cba5f8871d68837a8ea49aef60f97e369eb10`.
- Padres del merge: `6aafa456...` (UI4) y `57741520...` (master).
- PR: #14, se mantiene **draft**. No se fusiona UI4 en master ni se publica release.

## 2. Estrategia de convergencia

El Wizard se desarrolló primero en una rama independiente para integrarlo de forma
controlada sobre UI3. Una vez fusionado en master, UI4 se convierte en la rama de
convergencia del producto. Esto evita mantener dos implementaciones de navegación
y preferencias, reduce conflictos repetidos y permite certificar una sola
combinación: UI3 + Wizard + UI4 Aero Motion + Dynamic Mesh.

No se reaplicaron los parches históricos del Wizard ni de R16/R16.4. El código
actual es la autoridad; los parches se usaron únicamente como referencia de
implementación.

## 3. Resolución del conflicto `MichiNavHost.kt`

El merge automático resolvió el cuerpo del archivo, pero dejó un conflicto de
imports: UI4 necesitaba `CompositionLocalProvider` y master necesitaba
`LaunchedEffect`. Se conservaron ambos.

La composición final conserva:

- El `SharedTransitionLayout` único de UI4 y las transiciones locales de álbum.
- Las transiciones globales M52 en `None`.
- Las rutas de álbum, artista, biblioteca, Remote, Sync y Audio Route.
- El MiniPlayer persistente y Now Playing sin shared-element de artwork del player.
- Los parámetros de onboarding: `onReopenOnboarding`, `onboardingDestination`,
  `completionRequestId`, `onboardingSnapshot`.
- El `LaunchedEffect` que navega solo a destinos permitidos (`home`, `devices`,
  `settings/privacy`, `audio-route`).
- La ruta real `settings/privacy` y la reapertura del Wizard desde Ajustes.
- Los iconos adaptativos/monocromáticos y recursos del Wizard.

No quedaron marcadores de conflicto.

## 4. Matriz de funciones preservadas

| Función | Estado | Evidencia |
|---|---|---|
| UI3: Mosaico, CoverFlow, Lista | Preservada | Código y tests UI3/UI4 existentes |
| UI4: Aero Motion y transiciones de álbum | Preservada | `MichiNavHost.kt`, tests de política |
| UI4.11: MiniPlayer estable y fallback local del player | Preservada | `MiniPlayer.kt`, tests Compose |
| Dynamic Mesh activo por defecto | Preservada | `BuildConfig.AERO_DYNAMIC_MESH_ENABLED` |
| Wizard de primera ejecución | Integrado | `FirstRunWizard.kt`, `OnboardingStore.kt` |
| Reapertura del Wizard desde Ajustes | Integrada | `SettingsScreen.kt`, ruta `settings/privacy` |
| Importación SAF y cobertura parcial | Integrada | `SafMediaRepository`, `ImportedSafMerge` |
| Iconos legacy/adaptativos/monochrome | Integrados | Recursos de `master` |
| PR #14 como único candidato | Preservado | PR draft, sin merge |

## 5. Matriz P1 acumulativa

| ID | Fuente | Evidencia del problema | Archivos implicados | Causa | Corrección | Test | Estado |
|---|---|---|---|---|---|---|---|
| P1-STATE-01 | `audit-ext3`, `audit-p1-ui4-11` | Now Playing conservaba pista anterior y `0:00` mientras Media3 avanzaba | `ActivePlaybackPresentation.kt` | El mapper local priorizaba `sessionState`, un espejo que puede llegar retrasado, sobre el motor local | La reproducción local usa `localState` como autoridad; el snapshot de sesión solo rellena campos ausentes | `ActivePlaybackPresentationTest.local player state wins when the mirrored session snapshot is stale` | `GO` de código; física pendiente |
| P1-VIS-01 | `audit-ext1`, `audit-p1-ui4-11` | «Tu música» solapado por el hero al 200 % | `HomeScreen.kt` | Cabecera con altura fija de 56 dp | `heightIn(min = 56.dp)` y padding vertical; la cabecera crece con el texto | `HomeScreenTest` a `fontScale=2` | `GO` de código; captura física pendiente |
| P1-VIS-02 | `audit-ext1`, `audit-p1-ui4-11` | Texto de «Este teléfono» fragmentado por la insignia al 200 % | `DevicesScreen.kt` | Insignia en la misma fila que el texto, sin umbral de apilado | La insignia se apila debajo desde `fontScale >= 1.5`; el texto conserva todo el ancho | Tests de layout existentes + validación visual pendiente | `GO` de código; captura física pendiente |
| P1-ACC-01 | `audit-ext2`, `audit-p1-ui4-11` | Pestaña «Cancio…» truncada al 200 % | `AlbumsScreen.kt` | Cuatro pestañas con peso igual en un ancho insuficiente | Se usa `AeroSegmentStack` cuando `aeroSegmentStacking` detecta ancho o fuente insuficientes | `AeroSegmentedChoiceTest`, tests de biblioteca | `GO` de código; captura física pendiente |
| P1-ACC-02 | `audit-ext2` | Etiqueta «Dispositivos» invade la selección del Dock al 150 % | `BottomNavBar.kt` | Texto sin padding interno y autosize mínimo de 8 sp | Padding horizontal interno y autosize mínimo de 7 sp; etiqueta con tag de prueba | `M60_2DockNavigationTest` a 1.5 | `GO` de código; captura física pendiente |
| P1-PERF-01 | `audit-ext1/2/3`, `audit-p1-ui4-11` | `gfxinfo` con 0 frames en 9/10 runs y 3 en uno | Protocolo A/B y driver de auditoría | La ventana de 60 s no verificaba reproducción, actividad del mesh ni frames válidos | El protocolo queda corregido en UI4.12; no se declara rendimiento sin frames válidos | Instrumentación de mesh compilada; A/B físico pendiente | `INCONCLUSO` hasta A/B válido |
| P1-NAV-01 | `audit-ext2`, `audit-p1-ui4-11` | 20 ciclos no contaban comandos ni sesiones reales | Harness de auditoría | El driver verificaba nodos, no autoridad de reproducción | El test de 20 ciclos de UI4 sigue activo; la prueba de sesión real requiere hardware | `AeroPlayerTransitionComposeTest` | Código `GO`; sesión física pendiente |

## 6. Matriz P2 acumulativa

| ID | Fuente | Evidencia | Corrección / decisión | Estado |
|---|---|---|---|---|
| P2-A11Y-01 | `audit-p2-ui4-11` | Falta demostrar una única parada TalkBack en el Dock | Semántica fusionada conservada; se añadió etiqueta de prueba sin cambiar la semántica accesible | `INCONCLUSO` físico |
| P2-A11Y-02 | `audit-p2-ui4-11` | CoverFlow sin recorrido TalkBack | Sin cambio de comportamiento; requiere dispositivo | `INCONCLUSO` físico |
| P2-A11Y-03 | `audit-p2-ui4-11` | Indicador de conexión Remote sin mínimo táctil | Se añadió `heightIn(min = 48.dp)` y aserción | `GO` de código |
| P2-A11Y-04 | `audit-ext2` | Dock a 150 % | Corregido junto con P1-ACC-02 | `GO` de código |
| P2-REG-01/02 | `audit-p2-ui4-11` | Faltan transiciones físicas de mosaico, Remote y AirCat | Tests automatizados conservados; campaña física pendiente | `INCONCLUSO` físico |
| P2-INT-01 | `audit-ext2` | El manifiesto incluía un informe mutable | Se conserva el paquete histórico sin sobrescribirlo; UI4.12 usa un directorio nuevo | `GO` documental |
| P2-INT-02 | `audit-p2-ui4-11` | Logs nativos de skills no corroborados | El TSV es un manifiesto; la corroboración independiente sigue pendiente | `INCONCLUSO` |
| P2-INT-03 | `audit-p2-ui4-11` | Identidad binaria CI↔APK físico no demostrada | Se documenta la limitación; no se eleva a GO | `INCONCLUSO` |

## 7. Reconciliación R16 / R16.4

Inventario diferencial:

- R16: 33 rutas, **33 presentes** en el candidato actual.
- R16.4: 41 rutas, **41 presentes**.
- No se ejecutaron los scripts históricos de aplicación ni se usó `--reject`.
- Los parches no aplican en reversa porque el código actual evolucionó con UI4 y el
  Wizard; la presencia de rutas no se usa como prueba de implementación.

Comprobaciones puntuales de los contratos críticos:

| Contrato | Estado en el candidato | Evidencia |
|---|---|---|
| K16-01/K16-02: no borrar catálogo de otros orígenes | Implementado | `SyncedTrackRepository` conserva filas y rechaza IDs duplicados |
| SSE con handshake HTTP | Implementado | `SseHandshakePolicy` usado por `EventClient` |
| Integridad SHA de transferencias | Implementado | `TransferIntegrityPolicy` usado por `LinkTransferManager` |
| Reintento terminal de Sync | Implementado | `SyncRetryPolicy` usado por `SyncWorker` |
| Importación con destino validado | Implementado | `ValidatedImportDestination` |
| Artist Enrichment con identidad y licencias | Implementado en código | `artist/` y `data/artist/` presentes |
| Headphone Studio / curva PEQ | Implementado en código | `PeqResponseModel`, `PeqResponseCurve`, `HeadphoneStudioSection` |
| Aero canónico, contraste y gradientes | Implementado en código | `AeroSurface`, `AeroContrastPolicy`, `AeroGradientPolicy` |
| PERF-01..08 | Especificaciones, no mediciones | `docs/contracts/R16_4_PERFORMANCE_IMPLEMENTATION_CONTRACT.md` |

Riesgo residual: la presencia y los tests no certifican hardware, proveedores
reales, licencias de imágenes ni rendimiento. Esos gates permanecen abiertos.

## 8. Validación ejecutada

- Merge: compilación focal y tests de Wizard, MiniPlayer, transición y Dynamic
  Mesh: PASS.
- P1-A: `ActivePlaybackPresentationTest`, `PlaybackSessionManagerTest` y
  `SignalSourcePresentationTest`: PASS.
- P1-B/Dock: `HomeScreenTest`, `M60_2DockNavigationTest`,
  `AeroSegmentedChoiceTest`, `LibraryHeaderLayoutTest`: PASS.
- P2-A11Y-03: `RemoteScreenTest`: PASS.
- Todos los módulos y la app (Normal/F-Droid): PASS.
- Lint, `Migration3To4Test`, compilación y ensamblado AndroidTest: PASS.
- Normal/F-Droid debug y release: PASS.
- `tools/ui4_acceptance.py`: 47/47. Tests Python UI4: 8/8. Contrato modal:
  16/16. Contrato release: 18/18 estático. Launcher UI3: PASS.
- Todas las ejecuciones Gradle usaron el workflow gestionado
  `56fea6d6a4a7edcf6cba34c5660dc93a`.

### Runtime en emulador (no certificación física)

- `AeroMotionComposeTest` en `Medium_Phone` API 36: **OK (7 tests)**. Incluye
  paleta local más reciente, movimiento solo en reproducción activa en primer
  plano y congelación en pausa/background.
- `AeroPlayerTransitionComposeTest`: **OK (2 tests)**, incluidos 20 ciclos de
  apertura/retorno sin comandos de transporte.
- Wizard sobre la integración: instalación limpia en emulador → 1/5, biblioteca
  «Más tarde», ecosistema «Omitir por ahora», audio Android, resumen
  (`Biblioteca OPCIONAL`, `Ecosistema OPCIONAL`, `Audio LISTO`) y `Finalizar` →
  Home. Preferencias finales:
  `completed_v1=true`, `step_v1=4`, `library_v1=LATER`, `audio_v1=ANDROID`,
  `discover_later_v1=false`, `link_later_v1=false`.
- Reinicio en frío: Home, sin repetir el Wizard. Reapertura desde Ajustes: 1/5
  con `Cerrar`; cerrar conserva las preferencias intactas.
- Fuente 200 %: «Tu música» ocupa `[53,212][535,327]` y el contenido siguiente
  comienza en `y=522`; «Este teléfono» ocupa `[232,760][674,846]` y la insignia
  se apila en `[592,1038][985,1108]`; «Canciones» se muestra completo en
  `[339,877][668,957]`.
- Dock 150 %: etiqueta «Dispositivos» `[792,2243][1003,2290]` dentro del target
  `[778,2142][1017,2300]`, con margen lateral.
- APK: `application-icon` resuelve a `res/mipmap-anydpi-v33/ic_launcher.xml`;
  contiene `mipmap-anydpi-v26` sin monochrome y `mipmap-anydpi-v33` con
  monochrome.

## 9. Gates UI4.12

| Gate | Estado | Motivo |
|---|---|---|
| `UI4_CODE_GO` | `GO` | Merge, correcciones y matriz local completa PASS |
| `UI4_AUTOMATED_GO` | `PENDIENTE` | Falta CI exacta del head que se publique |
| `UI4_VISUAL_GO` | `NO-GO` | Correcciones verificadas en emulador; capturas físicas pendientes |
| `UI4_PHYSICAL_GO` | `NO-GO` | Sin dispositivo físico conectado |
| `DYNAMIC_MESH_ACTIVE` | `GO` | Activo por defecto y runtime de emulador PASS |
| `DYNAMIC_MESH_PERFORMANCE_GO` | `INCONCLUSO` | A/B anterior sin frames válidos; falta A/B físico corregido |
| `WIZARD_UI4_INTEGRATION_GO` | `GO` | Merge y flujo 1/5→5/5 verificados; límite: emulador, no físico |
| `WIZARD_ACCESSIBILITY_GO` | `NO-GO` | TalkBack/fuente/rotación física pendientes |
| `P1_AUDIT_CLOSURE_GO` | `NO-GO` | P1 de código corregidos; rendimiento y evidencia física abiertos |
| `P2_AUDIT_CLOSURE_GO` | `NO-GO` | Accesibilidad/regresiones físicas y logs de skills sin corroborar |
| `UI4_MERGE_GO` | `NO-GO` | Sin autorización de merge y con gates físicos abiertos |

## 10. Limitaciones

- No hay teléfono físico conectado durante esta fase; el emulador no se usa como
  certificación física.
- La evidencia A/B de UI4.11 no es válida para rendimiento.
- La identidad binaria CI↔APK físico y los logs nativos de skills siguen sin
  corroboración independiente.
