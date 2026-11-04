# AMR-81/82 · Contrato de campos y formularios Aero

**Estado:** implementado en parche acumulativo, NO aplicado a `master`. UI tests / Gradle: NOT_RUN.

## Decisión material (sin Aero 2.0)

El `AeroDialogField` es un **token de presentación**, no un nuevo componente con estado ni un sistema de formularios paralelo. Usa el `OutlinedTextField` nativo y una sola receta compartida `aeroDialogFieldColors(accent)`; conserva los callbacks y las validaciones de cada pantalla. En `Settings` se aplica la misma receta.

| Zona | Selección | Política implementada |
|---|---|---|
| Superficie interior | Inset técnico estático `#141923`; alto contraste `#0C0F14` | Sin textura por tecla, sin blur, sin segunda tarjeta |
| Radio | `AeroDialogFieldShape = RoundedCornerShape(12.dp)` | Un solo borde por input |
| Contorno enfocado | Cian `TertiaryCyan` en conexión / credenciales; rosa `PrimaryPink` en playlists | Blanco en alto contraste |
| Contorno reposo | `AeroHairline` (alto contraste blanco 92%) | Un único trazo de Material3 |
| Error | `ErrorColor` explícito | Preserva `isError` de cada formulario |
| Texto | `PureWhite`, `TextSecondary` para placeholder/deshabilitado | Mantiene legibilidad |
| Password | `PasswordVisualTransformation` + teclado tipo `Password` | Se envía la misma contraseña al callback, pero no se revela en la UI |

## Layout y accesibilidad

`aeroModalFormViewport()` se incorpora únicamente a los diálogos de formulario simples (`ManualConnection`, `CreatePlaylist`, `PinPairing`, `ReceiverButtonPairing` —legacy gated—, `PlayerPasswordPairing`, `RenamePlaylist`). Limita el contenido a 78% del alto lógico de pantalla con scroll y mínimo nominal 240 dp. **No aplicarlo a Queue ni AddTracks**, que ya contienen listas desplazables, porque el nested scroll o restricciones infinitas pueden romperlos.

Se corrigen objetivos inferiores a 48 dp: CTA de confirmación/cancelación, acciones de cola remota y cierre icon-only de AddTracks; sin fondos circulares para acciones icon-only. Los confirmadores de guardar/eliminar exponen `enabled = false` durante operaciones activas, no solo un callback bloqueado internamente. `DeviceDetailSheet` mantiene la confirmación destructiva explícita, con un único tono Floating para el `AlertDialog`.

## Tests incluidos para ejecución posterior

`AeroDialogMaterialAndFormsTest` contiene casos de edición en contraste alto, bloqueo de creación con nombre vacío, error de dirección manual, callbacks de credenciales, y confirmaciones deshabilitadas durante guardado/eliminación. Test de sintaxis ya ejecutado; **Robolectric y Compose typecheck aún NOT_RUN**.

## Fuentes concretas (sin copiar activos)

- Jetpack Compose `OutlinedTextFieldDefaults.colors`: https://developer.android.com/reference/kotlin/androidx/compose/material3/OutlinedTextFieldDefaults
- Entrada de texto/teclado Compose: https://developer.android.com/develop/ui/compose/text/user-input
- Chris Banes Component Design: https://github.com/chrisbanes/skills/blob/main/skills/compose-component-design/SKILL.md
- Chris Banes UI Testing Patterns: https://github.com/chrisbanes/skills/blob/main/skills/compose-ui-testing-patterns/SKILL.md

**Nota:** las capturas del usuario de Now Playing siguen siendo autoridad para la banda Signal Truth. Esta intervención no cambia su geometría ni texto centrado.

**Guard reproducible:** `python3 scripts/verify_aero_modal_contract.py` → 16/16 controles de fuente PASS en este corte. El guard no sustituye typecheck ni Compose UI tests.
