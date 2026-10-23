# ADR-MOB-DSD-LEGACY: Exclusión Definitiva de DSD, DoP y Certificación Bit-Perfect en Michi Music Mobile

**ID Único:** `ADR-MOB-DSD-LEGACY`  
**Estado:** `DECIDED`  
**Fecha:** 22 de octubre de 2026  
**Dueño:** Mobile Core & Player Architecture  
**Componentes implicados:** `:core`, `:player`, `:app`  

---

## 1. Contexto

Michi Music Mobile es un reproductor de audio portátil para Android enfocado en la eficiencia de almacenamiento, batería y calidad auditiva óptima para streaming y reproducción offline de formatos portátiles estándar (FLAC, MP3, AAC, Vorbis, Opus, WAV).

Históricamente se evaluó la factibilidad de incorporar Direct Stream Digital (DSD) y DSD-over-PCM (DoP) en el documento `docs/audio/DSD_DOP_FEASIBILITY.md`, así como un indicador de estado `BitPerfectStatus` en `SignalTruthTracker` y en el modelo serializado `AudioSignalObservation`.

Sin embargo, el marco de audio de Android (`AudioTrack`, `AudioFlinger`, HAL y mixers del sistema) está diseñado exclusivamente para PCM entero o punto flotante. Forzar DoP sin soporte de hardware verificado en cada modelo genera graves riesgos de ruido blanco a volumen máximo, mientras que la certificación "bit-perfect" en Android móvil no es garantizable sin control exclusivo a nivel de kernel/HAL y drivers USB propietarios.

---

## 2. Decisión

1. **Exclusión estricta de DSD y DoP en Mobile (Decisión D-02):**
   - No se implementará decodificación, extracción, passthrough ni transcodificación DSD2PCM en el runtime de Michi Music Mobile.
   - El estudio `docs/audio/DSD_DOP_FEASIBILITY.md` se archiva oficialmente como `ARCHIVED / OUT_OF_SCOPE_MOBILE`.

2. **Exclusión de promesas de "Bit-Perfect" (Decisión D-03):**
   - Se elimina de la UI de Signal Truth y de las pantallas de Now Playing cualquier etiqueta, semáforo o reclamo de "Bit-Perfect".
   - El modo `AudioProcessingMode.TRANSPARENT` se define y rotula honestamente en la UI como **«Sin efectos»** (bypass voluntario a nivel de aplicación del ecualizador y ReplayGain), nunca como bypass garantizado del sistema operativo Android ni bit-perfect de hardware.
   - El enum `BitPerfectStatus` en `:core` queda marcado como `@Deprecated` para preservar compatibilidad de serialización hacia atrás, fijando su valor por defecto en `BitPerfectStatus.UNVERIFIABLE`.

3. **Clasificación veraz de formatos Lossless:**
   - Una pista no se clasifica como Lossless basándose únicamente en `sampleRate >= 48000`. Debe validarse el códec y contenedor real (FLAC, ALAC, WAV PCM).

---

## 3. Consecuencias

### Positivas
- Se elimina el riesgo de introducir artefactos acústicos destructivos por streaming DoP no soportado.
- Se mantiene Signal Truth 100% veraz: sólo reporta lo que la aplicación puede observar y medir directamente (fuente original, decodificado PCM, estado DSP/ReplayGain, enrutamiento y configuración AudioTrack).
- Claridad conceptual absoluta para el usuario: "Sin efectos" significa neutralidad de DSP en la app.

### Mitigaciones de compatibilidad
- Se mantiene el campo `bitPerfect` en `AudioSignalObservation` con valor `@Deprecated` para evitar que rompa serializadores JSON en clientes antiguos o tests cross-module.
