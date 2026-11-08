# Certificación de regresión UI3

## Candidato

- Rama: `qa/ui3-2-version-policy-integration`
- Base aprobada: `dd1b755b2954ff6f2a0a39b0ff1fe46575b35865`
- Head de implementación certificado antes de este informe: `f2ceaafb92fadbfd655abf52c46b375491d2e31a`
- Integración conservada: UI3.2 `34ffbe5` + política de versiones `b900083`
- Alcance: certificación y regresiones focalizadas; no se añadieron funciones de UI4.

El commit final de entrega incluye este informe y se registra en el PR borrador
#13 y en el reporte de entrega. Un archivo versionado no puede contener de forma
autorreferencial el hash del commit que lo contiene.

## Evidencia automatizada nueva

| Contrato | Evidencia | Resultado |
|---|---|---|
| CoverFlow reproduce solo por acción explícita | `CoverFlowAlbumActionsTest.explicitPlayInvokesOnlyPlayAction` | PASS |
| CoverFlow sin pistas desactiva reproducir | `CoverFlowAlbumActionsTest.unavailableAlbumDisablesExplicitPlay` | PASS |
| Mosaico → detalle → volver conserva identidad opaca, biblioteca y scroll | `ComposeNavigationIntegrationTest.mosaicDetailBackPreservesOpaqueAlbumIdentityLibraryAndScroll` | PASS |
| Menú y calidad son mutuamente exclusivos y cierran sin semántica residual | `AlbumDetailMenuSheetsTest.menuTransitionsExclusivelyToQualityAndClosesWithoutResidualSheet` | PASS |
| Dock por teclado en fila única | `M60_2DockNavigationTest.keyboardMovesFocusAndActivatesTheNextSingleRowTab` | PASS |
| Dock 2×2 al 200 % sigue geometría D-pad y activa destino | `M60_2DockNavigationTest.dpadFollowsTwoByTwoGeometryAtTwoHundredPercentFont` | PASS |

Los componentes extraídos son límites internos y state-driven. `AlbumsScreen`
conserva la autoridad de reproducción y `AlbumDetailScreen` conserva
`activeModal`; no se cambió navegación, persistencia, DI ni autoridad de audio.

## Evidencia reutilizada

- `MichiCoverFlowHostTest`: seleccionar portada lateral primero y abrir únicamente
  la portada ya centrada, preservando identidad opaca.
- `AlbumCoverFlowPolicyTest`: continuidad de selección durante refresh transitorio,
  rebinding por clave de continuidad y fallback tras eliminación confirmada.
- `AlbumDetailUiPolicyTest`, `AlbumQualityPolicyTest` y
  `AlbumTrackPositionPolicyTest`: encabezado exacto `Pistas`, ausencia de contador
  y A–Z en ese encabezado, MP3/MPEG correcto y orden canónico multidisco.
- `PlaylistDetailScreenTest`: cero pistas disponibles, resolución parcial y cola
  reproducible formada únicamente por pistas disponibles.
- `M60_2DockNavigationTest`: etiquetas completas, selección, targets mínimos de
  48 dp y elección de Dock 2×2 a 360 dp con fuente al 200 %.
- `scripts/verify_ui3_launcher.py`: estructura de recursos adaptive/monochrome.

## Gates ejecutados

Todas las invocaciones Gradle se realizaron mediante el workflow administrado
`1cf521537cfd6c3561c457d073121253`.

| Gate | Resultado |
|---|---|
| Regresiones focalizadas nuevas + contratos adyacentes | PASS, `BUILD SUCCESSFUL` |
| `test lint assembleNormalDebug assembleFdroidDebug assembleNormalRelease assembleFdroidRelease` | PASS en 488.325 s |
| `:app:connectedNormalDebugAndroidTest` en API 36 | PASS: 80 tests, 0 fallos, 8 omisiones esperadas de evidencia live |
| Verificador de launcher UI3 | PASS: `UI3.1 launcher resources verified` |
| Manifest del APK Normal debug local | `versionCode=448`, `versionName=0.1.0-alpha`, compile SDK 36 |

La revisión del paquete completo `dd1b755..f2ceaaf` no encontró defectos críticos
ni importantes. CodeRabbit CLI no estaba instalado, por lo que la revisión final
disponible fue una auto-revisión separada; esta limitación no se presenta como
revisión independiente.

## Bloqueos externos

| Validación | Estado |
|---|---|
| TalkBack físico | `BLOCKED_HARDWARE` |
| Máscaras, icono circular y render OEM del launcher | `BLOCKED_HARDWARE` |
| Toque lateral físico de CoverFlow | `BLOCKED_HARDWARE` |
| Dispositivo real de 320 dp | `BLOCKED_HARDWARE` |
| Tablet real | `BLOCKED_HARDWARE` |

La omisión de estas pruebas no se convierte en certificación física implícita.

## Veredicto

`TECHNICAL_READY_FOR_UI4_BASE`: **SÍ**, para usar este candidato como base técnica
de UI4 después de revisar el head final del PR #13. La matriz automatizada local
y API 36 está verde y las regresiones prioritarias están cubiertas sin cambiar
el comportamiento de producto.

`PHYSICAL_CERTIFICATION`: **BLOQUEADA** por el hardware indicado arriba.

`MERGE_GO` y `RELEASE_GO`: **NO AUTORIZADOS**. No se fusionó ni publicó ninguna
release.
