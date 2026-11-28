# Runbook — Home enrollment y sesión (Mobile R1.4 pin)

## Prerrequisitos
- Micro Server R1.4 (o R1.5 una vez commit/pin) con TLS y `pinnedBaseUrl`.
- QR `michi.aircat.home.enrollment.v1` mostrado por la WebUI administrativa del Home.
- Mobile con identidad local Ed25519; no se usa `SERVER_CODE` para Home.

## Flujo operativo
1. En Mobile: **Dispositivos → Confianza Home → Vincular nuevo Home**; escanear el QR desde la pantalla administrativa real (nunca desde un chat/foto).
2. Confirmar la huella TLS/SPKI y el `homeId` mostrados.
3. En la tarjeta del origen, pulsar **Solicitar membresía**. El estado queda `ENROLLMENT_PENDING`.
4. En la WebUI del Home, aprobar la solicitud pendiente.
5. En Mobile, **Actualizar estado** o **Esperar aprobación**. Al aprobarse, el estado pasa a `ENROLLED_OFFLINE` (membresía guardada; aún no hay sesión).
6. Pulsar **Autenticar sesión**. Sólo si membership del servidor + `server_signature` verifican, pasa a `AUTHENTICATED_ONLINE`.
7. AirCat/Control usan la sesión Home; no hay fallback a `SERVER_CODE`.

## Diagnóstico
| Síntoma | Significado | Acción |
|---|---|---|
| `HOME_HTTPS_REQUIRED` / `HOME_TLS_PIN_REQUIRED` | URL no HTTPS o pin ausente | Volver a escanear QR administrativo; revisar TLS del servidor |
| `ROOT_KEY_CHANGED` | La raíz Home no coincide con el QR | No continuar; obtener QR nuevo tras rotación autorizada |
| `MEMBER_REVOKED` | El Home revocó la membership | La app borra token/membership; re-enrolar con aprobación |
| `TLS_REQUIRED` | Servidor exige HTTPS | Corregir `pinnedBaseUrl`; nunca degradar a HTTP |
| `IDENTITY_MISMATCH` | Identidad local/membership incoherente | Verificar Keystore/identidad; no reutilizar una membership vieja |
| `REVOCATION_STORE_UNAVAILABLE` | Servidor no puede verificar revocaciones | Reintentar con backoff; no asumir autorización |
| `BACKOFF` | Reintento Home en curso | Esperar; no pulsar repetidamente |

## Revocación
- Operador: WebUI del Home revoca el dispositivo.
- Mobile recibe `MEMBER_REVOKED`: invalida sesión y membership, bloquea Control/AirCat para ese contexto y conserva reproducción local.
- La UI muestra `REVOKED`; no hay auto-recuperación de la sesión vieja.

## Verificación local
```bash
python3 tools/check_settings_architecture.py
# gradle-run:
:app:testNormalDebugUnitTest --tests "org.michimusic.mobile.devices.home.*"
:michi-link-client:testDebugUnitTest
```
E2E real requiere Micro Server/ZimaOS y dispositivo físico; no se certifica con MockEngine.
