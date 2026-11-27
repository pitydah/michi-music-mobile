# Block E — T48 real Library → editor → Guardar → reopen

Test: `app/src/androidTest/java/org/michimusic/mobile/ui/screens/MetadataEditorLibraryReopenInstrumentedTest.kt`.

Observed flow on `Medium_Phone(AVD) - 16`:
1. Complete first-run wizard if present; open `Biblioteca`.
2. Open the real row menu `Opciones para ME13 T48 Original` → `Editar metadatos`.
3. Assert four tabs `metadata_section_basics`, `credits`, `discography`, `art_lyrics`.
4. Edit TITLE, assert `Revertir cambios` enabled, use it and confirm; title returns to original without a save.
5. Edit TITLE to `ME13 T48 Guardada`; hide IME; click `Guardar`.
6. Observe **Cambios guardados** (not “biblioteca pendiente”).
7. Close the editor, recreate the Activity, reopen Biblioteca and the editor: the row and field show `ME13 T48 Guardada`.
8. Assert persistent `cached_tracks` row title, `verifiedAt=0`, `verifiedSha256=""`, audio suffix byte-identical and no pending journal artifacts.

Product fixes required by the real flow:
- `MetadataLibraryRefreshSignal` refreshes the live library after a verified save.
- `ExtendedTrackMetadataRepository.loadEditorColdRead` chooses MediaStore vs DocumentsContract columns by URI authority, so a cached `lib_device_local` row does not make a MediaStore document unreadable.

Limitation: the harness recreates the Activity, not a true process kill; T18/T19/T20/T22/T43 remain NOT RUN.
