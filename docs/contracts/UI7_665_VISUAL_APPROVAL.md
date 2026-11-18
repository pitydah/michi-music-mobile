# UI7 665 — Visual approval receipt

Status: **UI7_ARTISTIC_APPROVAL_COMPLETE**

## What was approved

On **2026-11-18** the user confirmed, explicitly, that the source sheets and the
crops of the 353 new Mosaico Destacado resources and the 353 new Editorial
resources **had already been reviewed and approved visually**. This is an
approval of existing artwork; it does not authorise generating, recropping or
recompressing anything.

## Provenance recorded

Approval provenance: `USER_VISUAL_APPROVAL_CONFIRMED_2026-11-18`

| View | Packaged | Approved | Pending |
|---|---:|---:|---:|
| Mosaico destacado | 665 | 665 | 0 |
| Editorial posters | 665 | 665 | 0 |

The manifest rows for those 706 pairs moved from
`IMPORTED_PENDING_VISUAL_APPROVAL` (and `POSTER_IMPORTED_PENDING` for posters)
to `USER_CONFIRMED_COMPLETE` / `POSTER_COMPLETE`. Nothing else in the row
changed: identity, filename, SHA-256, dimensions and source sheet are byte for
byte the values verified during integration.

## How the approval was applied

The 353 identities were selected from the integration CSV
(`UI7_GENEROS_ESTILOS_PENDIENTES_INTEGRACION_353.csv`) by resource filename, not
by position, and the rewrite asserted that every other row kept all of its
fields unchanged. A global text replacement would have promoted anything that
happened to share a state string; selecting by identity is what makes this
receipt auditable.

## What this approval does not claim

- **Optical masks are still unmeasured for the 353 imported crops.** Approval is
  an artistic judgement, not a measurement. `optical.tsv` still covers only the
  312 previously measured assets, and those rows keep their real `verified`
  values. The provisional contour fallback stays in place; no coordinate was
  invented.
- **No image was regenerated, recropped, recompressed or renumbered.** The 1.330
  WebP payloads (665 + 665) are byte-identical to the verified integration
  batch.
- **Distribution is a separate decision.** Release certification proves the
  packaged bytes are approved and intact; publishing an APK or AAB to users is
  not implied by this receipt.

## States the system still supports

The approval axis remains in the code, because the next batch will need it:

| State | Meaning |
|---|---|
| `USER_CONFIRMED_COMPLETE` | Approved by the user. |
| `IMPORTED_PENDING_VISUAL_APPROVAL` | Imported and technically verified, not yet approved visually. |
| `REJECTED` | Rejected, never packaged. |
| `POSTER_COMPLETE` | Editorial poster finished and approved. |
| `POSTER_IMPORTED_PENDING` | Editorial poster imported, awaiting visual approval. |

Regenerating a manifest never promotes anything: a file with no published row
enters as pending, a rejected file aborts the rewrite, and a missing or corrupt
manifest produces no approvals at all.

## Certification gates

| Gate | Where | Contract |
|---|---|---|
| `verifyUi7MosaicAssets` | `preBuild` (every variant) | Integrity: bytes, hashes, dimensions, identity, geometry, inventory. |
| `verifyUi7EditorialPosters` | `preBuild` (every variant) | Same, for posters. |
| `verifyUi7MosaicCertification` | `package*Release` / `bundle*Release` | `--certified`: refuses to build a distributable artifact while any row is pending, and refuses corrupt bytes in any variant. |
| `verifyUi7EditorialPosterCertification` | `package*Release` / `bundle*Release` | Same, for posters. |

The release gate classifies its own outcome: only a real Gradle success, with
both certification gates actually executed (not skipped, cached or up to date),
release code compiled and packaged, and no pending rows, is reported as
`RELEASE_CERTIFIED_GO`.
