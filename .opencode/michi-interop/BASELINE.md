# Baseline

## Última verificación: 2026-11-27 (inicio y cierre del lote MIM-01..08)

| Repositorio | Rama | HEAD verificado | Árbol | Rol |
|---|---|---|---|---|
| `pitydah/michi-music-mobile` | `master` | `972795f4` (implementación `d4082d6f`, evidencia `c8036bc4`, CI `af03ca41` + `972795f4`; baseline `a737eb9f`) | limpio | Único repo de desarrollo |
| `pitydah/michi-micro-server` | `refactor/killcritic-interop-r1` (PR #26) | `b507b47ebd9dec17740ca26d9da8ddb8b045735b` | limpio | Referencia SOLO LECTURA |
| `pitydah/michi-link` | `main` | `8dad0fb51128df98422bd74968e75f0ae8f85ccf` | limpio | Referencia SOLO LECTURA / norma |

- Pin vendorizado por Micro: `8dad0fb51128df98422bd74968e75f0ae8f85ccf` (`git rev-parse HEAD:vendor/michi-link`). Coincide exactamente con `main` local de Link.
- No hubo drift entre inicio y cierre.
- CI GitHub `46064991170` (intento 2): **SUCCESS**, incluido Job A (Lint + arquitectura) y Job G (instrumentado API 36).
- La rama de Micro es de interoperabilidad, **no** `main`; no se asumió que main incluya el PR #26.
- Mobile partía limpio; los cambios de este lote están descritos en `EVIDENCE.md` y no se han commiteado.

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
