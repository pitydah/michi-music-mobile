# UI4 — Informe de aceptación técnica

## Candidato

- Rama: `feat/ui4-aero-motion`
- Base certificada: `master@6bae6486cf57a542db5517235c716c3ad5f4d1c2`
- Contrato: `/home/cristian/Descargas/plan(1).md` (UI4.10.0)
- Parche fuente: SHA-256 `f52920825032213a9526f2874a9565d72ac522a9b2f4558558ce8fc243f7fb15`
- SHA final: se registra en el PR y en la entrega, porque este informe forma parte del propio commit.

## Integración

El parche se aplicó sin forzar contextos. Cincuenta y cuatro rutas aplicaron de
forma directa. Tres rutas con cambios posteriores a la base del parche se
reconciliaron manualmente:

- `AlbumDetailScreen.kt`: conserva `AlbumDetailMenuSheets`, la autoridad modal,
  `Pistas`, el orden canónico y el playback; añade únicamente movimiento de
  artwork/hero/lista.
- `AudioRouteScreen.kt`: anima valores observados sin crear una autoridad de
  audio paralela.
- `BottomNavBar.kt`: añade énfasis visual sin cambiar el Dock responsive, sus
  semánticas, destinos, targets táctiles ni navegación por teclado/D-pad.

## Contratos certificados

| Contrato | Evidencia |
|---|---|
| Mosaico, CoverFlow y Lista permanecen disponibles | Tests UI3 reutilizados, compilación y suite API 36 |
| Identidad y scroll Mosaic → Detalle → Atrás | `ComposeNavigationIntegrationTest` reutilizado |
| CoverFlow no reproduce al abrir y conserva selección | `CoverFlowAlbumActionsTest`, `MichiCoverFlowHostTest`, `AlbumCoverFlowPolicyTest` |
| Menús de álbum exclusivos y sin rectángulo fantasma | `AlbumDetailMenuSheetsTest`; no se añadió ornamento al root de `ModalBottomSheet` |
| Mosaic ↔ Detalle comparte artwork solo con identidad válida | `AeroSharedAlbumMotionTest` y scopes limitados a rutas Library/Album |
| MiniPlayer ↔ Now Playing comparte solo la misma pista | `AeroSharedNowPlayingMotionTest`; no crea sesión ni comando de transporte |
| Reduced motion y alto contraste desactivan movimiento espacial | Tests de políticas Aero y Compose API 36 |
| Dock 1 fila/2×2 y teclado/D-pad | `M60_2DockNavigationTest` reutilizado |
| Signal Truth, Michi Link/Stream y rutas de audio conservan autoridad | Movimiento aplicado solo alrededor de estados observados existentes |
| Fondo Aero Dynamic Mesh no se habilita sin rendimiento certificado | `AeroDynamicMeshPerformanceCertified=false` y test de gate |

## Evidencia ejecutada

- `tools/ui4_acceptance.py`: **42/42 PASS**.
- Tests Python `tools/tests/test_ui4_*.py`: **7 PASS**.
- Compilación Kotlin de producción, unit tests e instrumentación: **PASS**.
- Suite focal UI4 + contratos UI3: **PASS**.
- `test lint assembleNormalDebug assembleFdroidDebug assembleNormalRelease assembleFdroidRelease`: **PASS** en 487.671 s sobre el árbol final staged.
- API 36 `:app:connectedNormalDebugAndroidTest`: **85 tests**, **0 fallos**, **8 omisiones esperadas** de evidencia viva.
- Verificador launcher UI3.1: **PASS**.
- APK Normal debug local previo al commit final: `versionCode=450`, `versionName=0.1.0-alpha`, compile SDK 36. El APK QA del SHA final se registra desde CI en el PR.

Durante instrumentación se reprodujo y corrigió una incompatibilidad real: el
modo alto contraste produce deliberadamente un color sólido, mientras
`Brush.verticalGradient` exige al menos dos colores. El test falló primero y
pasó después de renderizar directamente el color semántico sólido.

## Rendimiento y hardware

`AeroDynamicMeshBackground` está implementado, pero **deshabilitado por defecto**.
No se habilitará permanentemente hasta medir en hardware real 60/90/120 Hz y una
sesión sostenida con CPU, GPU y batería. Su gate interno también detiene el
movimiento con playback parado, app en background, reduced motion, alto
contraste, Power Save o dispositivo low-RAM.

Estado externo honesto:

- `BLOCKED_HARDWARE`: rendimiento 60/90/120 Hz y consumo sostenido del Mesh.
- `BLOCKED_HARDWARE`: TalkBack físico.
- `BLOCKED_HARDWARE`: máscaras/círculo en launchers OEM.
- `BLOCKED_HARDWARE`: toque lateral físico de CoverFlow.
- `BLOCKED_HARDWARE`: dispositivos reales 320dp y tablet.

## Revisión y veredicto

Se realizó revisión separada del diff integrado y de los tres hunks
reconciliados. No se detectaron cambios de autoridad, reproducción, identidad,
scroll o menús. La revisión es del autor porque no hay revisor independiente
disponible en este entorno; debe considerarse más débil que una revisión humana
externa antes de fusionar.

- `UI4_STATIC_AND_AUTOMATED_ACCEPTANCE`: **PASS**
- `DYNAMIC_MESH_PERMANENT_ENABLEMENT`: **BLOCKED_HARDWARE / DISABLED**
- `UI4_MERGE`: **NO AUTORIZADO**
- `RELEASE`: **NO AUTORIZADO**
