# Michi Aero · Matriz de diálogos y hojas AMR-90

Los nombres y roles se consultaron en `DialogComponents.kt`, `TrackActionMenu.kt` y `devices/DeviceDetailSheet.kt`. Todos siguen con sus callbacks, validaciones y políticas de dismiss preexistentes. La inspección estática no demuestra qué modal está alcanzable desde cada caller; **se requiere verificación de código completo y test de evento**.

| Composable | Archivo | Tratamiento/seguridad | Estado documental | Prueba real |
|---|---|---|---|---|
| `QueueDialog` | `DialogComponents.kt` | Estado activo por índice, Floating, filas Plain/Satin | SOURCE_EDITED | NOT_RUN |
| `EqualizerDialog` | `DialogComponents.kt` | Floating, controles DSP; PEQ con Inset sin afirmar medido | SHARED_FOUNDATION | NOT_RUN |
| `QrScannerDialog` | `DialogComponents.kt` | Floating y entrada de link manual; sin nuevo pairing | SHARED_FOUNDATION | NOT_RUN |
| `ManualConnectionDialog` | `DialogComponents.kt` | Floating + form scroll/field token y dos campos con radio12; endpoint parse intacto | SOURCE_EDITED | NOT_RUN |
| `CreatePlaylistDialog` | `DialogComponents.kt` | Floating + form scroll/field token; campo con error y loading intactos | SOURCE_EDITED | NOT_RUN |
| `AudioRouteDialog` | `DialogComponents.kt` | Floating, roles observados/anunciados no mezclados | SHARED_FOUNDATION | NOT_RUN |
| `PinPairingDialog` | `DialogComponents.kt` | Floating + form scroll/field token; campo PIN; sin cambio de seguridad | SOURCE_EDITED | NOT_RUN |
| `ReceiverButtonPairingDialog` | `DialogComponents.kt` | Legacy solo por gate existente; no activar pairing físico Stream | LEGACY_GATED | NOT_RUN |
| `PlayerPasswordPairingDialog` | `DialogComponents.kt` | Floating + form scroll/field token, usuario/contraseña, radio12 | SOURCE_EDITED | NOT_RUN |
| `AddTracksDialog` | `DialogComponents.kt` | Floating; icon-only close target 48dp, selección y control de carga intactos | SOURCE_EDITED | NOT_RUN |
| `RemoveTrackFromPlaylistDialog` | `DialogComponents.kt` | Floating; CTA 48dp and disabled semantics, confirmación y error | SOURCE_EDITED | NOT_RUN |
| `DeletePlaylistDialog` | `DialogComponents.kt` | Floating; CTA 48dp and disabled semantics, loading bloquea dismissal | SOURCE_EDITED | NOT_RUN |
| `RenamePlaylistDialog` | `DialogComponents.kt` | Floating + form scroll/field token, campo original y errores | SOURCE_EDITED | NOT_RUN |
| `RemoteQueueConfirmationDialog` | `DialogComponents.kt` | Floating; CTA 48dp and disabled semantics, acciones de cola remota confirmadas | SOURCE_EDITED | NOT_RUN |
| `TrackActionBottomSheet` | `TrackActionMenu.kt` | Sheet, acciones icon-only sin discos | SHARED_FOUNDATION | NOT_RUN |
| `AddToPlaylistDialog` | `TrackActionMenu.kt` | Floating+Smoked; filas Plain, sin control de progreso ficticio | SOURCE_EDITED | NOT_RUN |
| `DeviceDetailSheet` | `devices/DeviceDetailSheet.kt` | Sheet, identidad Inset y confirmación explícita | SOURCE_EDITED | NOT_RUN |

Los `AeroFloatingSurface` existentes son la única capa óptica exterior y **no deben anidarse**. El uso de `SOURCE_EDITED` implica un cambio concreto para este modal o un corte anterior; `SHARED_FOUNDATION` solo usa infraestructura material y **no acredita pulido/QA individual**. `LEGACY_GATED` no equivale a permiso para volver a pairing mediante botón en Michi Stream. Antes del cierre deben probarse: contenido, cierre/confirmación, teclado/foco, fontScale 1.3–2.0 y errores de formulario, sin hardware físico.

### Nuevo corte AMR-90

Las filas `SOURCE_EDITED` indican código concreto o uso del helper nuevo. Los 17 modales **siguen sin pruebas Robolectric ejecutadas**, así que la columna final permanece `NOT_RUN`. Pruebas de formularios y edición en `AeroDialogMaterialAndFormsTest.kt` (fuente) y detalles en `AERO_MODAL_FIELD_CONTRACT.md`. El legacy `ReceiverButtonPairingDialog` conserva su gate, no se reactiva pairing por botón en Stream.
