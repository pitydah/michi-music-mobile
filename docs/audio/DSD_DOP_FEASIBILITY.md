# Estudio Técnico de Factibilidad: DSD y DSD-over-PCM (DoP) en Michi Music Mobile

**Fecha:** 21 de octubre de 2026 (Archivado: 22 de octubre de 2026)  
**Contexto de Arquitectura:** Michi Music Mobile (Android / Media3 / ExoPlayer / USB Audio)  
**Estado:** ARCHIVED / OUT_OF_SCOPE_MOBILE (Conforme a Decisión D-02 del Plan Maestro V5, DSD y DoP quedan formalmente excluidos del producto y runtime de Mobile. Este documento se conserva exclusivamente como referencia técnica histórica).  

---

## 1. Resumen Ejecutivo
Direct Stream Digital (DSD) es un formato de modulación de densidad de pulsos (PDM) de 1 bit a tasas extremadamente elevadas (DSD64 a 2.8224 MHz, DSD128 a 5.6448 MHz, DSD256 a 11.2896 MHz).
La pila estándar de audio de Android (`AudioTrack` / `AudioFlinger` / HAL) opera exclusivamente sobre tramas PCM enteras o de coma flotante. En consecuencia, la reproducción de archivos `.dsf` y `.dff` en Android presenta dos rutas técnicas:

1. **Transcodificación DSD a PCM (DSD2PCM):** Descompresión en software de las tramas PDM a PCM de alta resolución (p. ej. 24-bit / 88.2 kHz o 24-bit / 176.4 kHz).
2. **DSD over PCM (DoP v1.1):** Empaquetado de muestras DSD de 16 bits dentro de tramas PCM de 24 bits a 176.4 kHz (DSD64) o 352.8 kHz (DSD128) con palabras de sincronismo alternantes `0x05` y `0xFA` en los 8 bits superiores.

---

## 2. Limitaciones del Framework Android y Media3

### 2.1 Media3 / ExoPlayer
- `MediaCodecAudioRenderer` y `DefaultAudioSink` de AndroidX Media3 están diseñados alrededor de `AudioFormat.ENCODING_PCM_*`.
- No existe un decoder nativo de DSD en el sistema Android (MediaCodec no dispone de `audio/dsd` ni `audio/x-dsd`).
- Si se inyecta un archivo `.dsf` sin un extractor personalizado (`Extractor`), ExoPlayer falla en la fase de preparación (`UnrecognizedInputFormatException`).

### 2.2 Requisitos de DoP (DSD over PCM)
- Para que DoP funcione sin ruido blanco ensordecedor:
  - **La ruta debe ser 100% bit-perfect:** Cualquier modificación de volumen, ecualizador, mezclador de Android, remuestreo de 176.4 kHz a 192 kHz o procesamiento ReplayGain altera los marcadores de sincronismo `0x05`/`0xFA`, convirtiendo la modulación DSD en estática destructiva a volumen máximo.
  - Se requiere soporte para frecuencias de muestreo múltiplos de 44.1 kHz (176.4 kHz para DSD64, 352.8 kHz para DSD128). La inmensa mayoría de los controladores de audio de chipsets Android (Snapdragon/MediaTek) fuerzan el mezclador de hardware a 48 kHz / 96 kHz / 192 kHz.

---

## 3. Hoja de Ruta para Factibilidad

### Fase 1: Extractor y Decoder DSD2PCM en Módulo Aislado
- Implementar un extractor ligero de metadatos ID3 y bloques de audio DSF/DFF.
- Integrar biblioteca de transcodificación PCM multietapa (p. ej. algoritmo de decimación con filtro paso bajo IIR/FIR).
- Entregar PCM 24/88.2 kHz al pipeline estándar de ExoPlayer, permitiendo reproducción universal en cualquier hardware Android.

### Fase 2: Passthrough DoP con USB Host Directo (Driver Propietario)
- Omitir `AudioTrack` de Android utilizando la API `android.hardware.usb.UsbDeviceConnection` para comunicación USB Audio Class 2.0 asíncrona directa.
- Detección de endpoints isócronos USB con retroalimentación de reloj (clock feedback).
- Verificación en hardware con DAC USB que posea indicador LED de modo DSD nativo (p. ej. FiiO, iFi, Chord).

---

## 4. Política de Veracidad de Señal en Michi
En cumplimiento con el mandato de **Signal Truth**:
- **Prohibido:** Afirmar que se está reproduciendo DSD nativo si se está realizando transcodificación DSD2PCM.
- La UI debe reportar con total transparencia: `DSD64 -> PCM 24/88.2 kHz (Transcodificado)` salvo que exista passthrough USB certificado en hardware.
