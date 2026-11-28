# Baseline

## Última verificación: 2026-11-27 (LOTE 03, sin commit)

| Repositorio | Rama | HEAD verificado | Árbol | Rol |
|---|---|---|---|---|
| `pitydah/michi-music-mobile` | `master` | `64db675e` (HEAD publicado; LOTE 02+03 en working tree) | modificado por LOTE 02+03, sin commit | Único repo de desarrollo |
| `pitydah/michi-micro-server` | `refactor/killcritic-interop-r1` (PR #26) | `b507b47ebd9dec17740ca26d9da8ddb8b045735b` | limpio | Referencia SOLO LECTURA |
| `pitydah/michi-link` | `main` | **DRIFT**: `ea76ee0e3ffabf23c921db71454cd6294b7ae960` (R1.5); baseline de código fijado `8dad0fb5` | limpio | Referencia SOLO LECTURA / norma |

- Pin vendorizado por Micro: `8dad0fb51128df98422bd74968e75f0ae8f85ccf` (`git rev-parse HEAD:vendor/michi-link`). Coincide exactamente con `main` local de Link.
- **DRIFT EXTERNO DETECTADO AL CIERRE:** Link `main` avanzó de `8dad0fb5` a `ea76ee0e` (R1.5: ML-001..004). Micro HEAD sigue `b507b47`, pero su working tree tiene 24 entradas modificadas/nuevas R1.5 (incluye docs/handoff, tests `interop_r1_5_*` y `vendor/michi-link` → `ea76ee0e`). MIM no escribió en ninguno; no se adopta R1.5 hasta que Micro lo commitee y fije el pin. El código/tests de LOTE 02 siguen contra R1.4 `8dad0fb5`.
- CI GitHub `46064991170` (`972795f4`, intento 2): **SUCCESS**.
- CI GitHub `46071123540` (`64db675e`, intento 1): **SUCCESS**, incluido Job A (Lint + arquitectura), Job G (instrumentado API 36) y Job F (F-Droid).
- CI GitHub `46068074299` (`f8a37cf3`, intento 3): **SUCCESS** (histórico).
- La rama de Micro es de interoperabilidad, **no** `main`; no se asumió que main incluya el PR #26.
- Mobile partía limpio en `64db675e`; los cambios de LOTE 02+03 están descritos en `EVIDENCE.md` y no se han commiteado.

## Compatibilidad fijada

- Contratos: Link `8dad0fb5` (schemas, OpenAPI, vectores `micro-mobile-interop-vectors.json`, `aircat-golden-v1`).
- Productor: Micro `b507b47` (handlers reales inspeccionados, no sólo documentación).
- Mobile: cliente implementado contra ambos; donde el productor y el contrato difieren se abrió ticket y se eligió el camino seguro (ver `DECISIONS.md`).

## Comandos de verificación usados

```bash
git -C <repo> rev-parse HEAD
git -C <repo> rev-parse --abbrev-ref HEAD
git --no-optional-locks -C <repo> status --porcelain
git -C /home/cristian/michi-micro-server rev-parse HEAD:vendor/michi-link
```

No se ejecutó `fetch`, `pull`, `checkout`, build ni escritura en los repos de referencia.
