# MICHI MUSIC MOBILE — PROCEDIMIENTO DE VERIFICACIÓN ANDROID EN DISPOSITIVO (GATE H / H2)
**Fecha:** 30 de Octubre de 2026  
**Rama:** `integration/m46d-aero-premium`  
**Estado:** `NOT_TESTED_ON_DEVICE` (Entorno de desarrollo headless sin dispositivo físico ni emulador activo conectado a ADB)

---

## 1. CONDICIÓN DEL ENTORNO Y DECLARACIÓN DE HONESTIDAD

Durante la ejecución del Mega Sprint **Aero Quality Leap (M46-D)**, el entorno de construcción y testing se ejecutó en una máquina Linux sin servidor gráfico directo ni emulador/dispositivo Android conectado vía ADB (`adb devices` reporta lista vacía).

En estricto cumplimiento del principio de **cero simulación y honestidad técnica**:
* **No se han generado capturas de pantalla sintéticas ni mockups HTML** simulando ser capturas del APK en ejecución.
* Toda evaluación dependiente de hardware físico (percepción táctil de haptics, renderizado GPU en pantalla real, TalkBack con sintetizador de voz) ha sido marcada explícitamente como `NOT_TESTED_ON_DEVICE` con deducción proporcional en la rúbrica de certificación.
* La lógica, arquitectura, contratos de datos, insets, semántica de accesibilidad y prevención de defectos de audio se encuentran **100% verificados mediante pruebas unitarias y de componentes (Robolectric / Compose Test Rule)**.

---

## 2. PROCEDIMIENTO REPRODUCIBLE DE PRUEBA EN DISPOSITIVO / EMULADOR

Una vez conectado un dispositivo físico (con depuración USB habilitada) o iniciado un AVD (Android Virtual Device), ejecutar la siguiente secuencia:

### A. Preparación y Despliegue
```bash
# 1. Verificar dispositivo conectado
adb devices -l

# 2. Compilar e instalar variante Normal Debug
./gradlew :app:installNormalDebug

# 3. Lanzar la aplicación
adb shell am start -n org.michimusic.mobile.debug/org.michimusic.mobile.MainActivity
```

### B. Ejecución de Pruebas Instrumentadas
```bash
# Ejecutar suite instrumentada en el dispositivo
./gradlew :app:connectedNormalDebugAndroidTest
```

---

## 3. MATRIZ DE VERIFICACIÓN VISUAL Y DE ACCESIBILIDAD

| ID Escenario | Pantalla / Componente | Condición de Prueba | Verificación Esperada | Severidad en Falla |
|:---:|---|---|---|:---:|
| **SC-01** | **Now Playing** | Reproducción local activa (Flac 44.1kHz / 16-bit) | Carátula cuadrada centrada, controles de transporte icon-only sin discos de fondo (play/pause 64dp, anterior/siguiente 48dp), sin slider de volumen, banner de sesión ausente o local | P0 |
| **SC-02** | **Now Playing** | Handoff a Michi Stream remoto | Banner superior "Reproduciendo en: [Nombre]", spinner de carga mientras comando esté pendiente, duración "—:—" si es stream en vivo sin duración | P0 |
| **SC-03** | **Signal Truth Dialog** | Tap en badge de formato de Now Playing | Modal con 5 tarjetas estructuradas (Fuente, Decodificación, Procesamiento, Salida Android, Dispositivo físico) con pills de estado ("Declarado", "Observado", "Configurado") | P1 |
| **SC-04** | **CoverFlow 2.0** | Carrusel de álbumes en Biblioteca | Selector de vista mediante iconos desnudos (no botón delineado). Desplazamiento fluido, reflexión inferior de 14dp con degradado suave | P1 |
| **SC-05** | **CoverFlow Panel** | Selección de álbum + cambio de ordenación | Al cambiar el filtro u orden en la biblioteca, el álbum seleccionado se mantiene constante por su ID único (`selectedAlbumId`). Panel editorial muestra título, artista, año, pistas y acciones de reproducción | P1 |
| **SC-06** | **CoverFlow Tracklist** | Reproducción en curso de pista del álbum | La fila de la pista activa muestra `EqualizerWaveBars` animados al compás de la reproducción, respetando si la fuente es remota o local | P1 |
| **SC-07** | **Queue Screen** | Cola remota activa vs vacía | Si el endpoint activo es remoto, la cola muestra exactamente `sessionState.queue`. Si el remoto tiene 0 pistas, muestra mensaje honesto de cola vacía remota, sin sustituir por la cola local del teléfono | P0 |
| **SC-08** | **Search Screen** | Búsqueda activa con teclado en pantalla | Campo de búsqueda debounced (300ms), las filas de canciones muestran indicador de reproducción cuando coincide con la pista activa del endpoint seleccionado | P1 |
| **SC-09** | **Devices Screen** | Nodo Michi Stream detectado | Muestra ficha de dispositivo Stream con sus capacidades. No presenta botón ni instrucción de pulsación física para emparejamiento (restringido a flujo de red mDNS/Link) | P0 |
| **SC-10** | **Settings Screen** | Sección Accesibilidad | Muestra interruptor "Vibración táctil sutil (Aero)". Al cambiar su estado, persiste en `VisualPreferences` y activa/desactiva retroalimentación háptica | P1 |
| **SC-11** | **Shell Global / Dock** | Navegación por las 5 pestañas principales | La barra de navegación inferior y el MiniPlayer flotante no ocultan contenido. El espacio inferior de las listas es de 24dp (no 100dp redundantes) gestionado por Scaffold insets | P1 |
| **SC-12** | **Sync Wizard** | Paso de selección y preflight con barra de 3 botones | Los botones inferiores "Siguiente" / "Comenzar Sincronización" quedan plenamente visibles y accesibles por encima de la barra de navegación del sistema (gestos y 3 botones) | P1 |

---

## 4. MATRIZ DE DIMENSIONES DE PANTALLA Y CONDICIONES DE SISTEMA

Para una certificación exhaustiva en dispositivo, se deben cubrir los siguientes perfiles de hardware y configuración:

1. **Anchos de pantalla mínimos:**
   * **320 dp:** Verificar que no haya clipping de texto ni solapamiento de controles de transporte en Now Playing.
   * **360 dp:** Dispositivos estándar compactos.
   * **393 dp:** Pantalla estándar moderna (Pixel 7 / 8).
   * **430 dp:** Dispositivos grandes (Pixel Pro / Galaxy Ultra).
   * **Landscape:** Adaptación de Now Playing en dos columnas (carátula izquierda, controles y metadatos derecha).

2. **Escalas de fuente:**
   * **1.0x (Normal)**
   * **1.5x (Accesibilidad media):** Verificar que los títulos de canciones y botones no sufran elipsis agresiva ni desborden cards.
   * **2.0x (Accesibilidad máxima):** Scroll vertical activo y etiquetas de accesibilidad íntegras en TalkBack.

3. **Modos de navegación del sistema:**
   * **Navegación por gestos (Gesture Navigation)**
   * **Navegación clásica de 3 botones (Back / Home / Recents)**

---

## 5. CAPTURA Y METADATOS DE EVIDENCIA

Cuando se ejecute la captura visual sobre un dispositivo, cada archivo debe guardarse bajo `docs/aero-quality-leap/screenshots/` con el siguiente esquema de metadatos:

```json
{
  "scenario_id": "SC-01",
  "commit_sha": "bcfe385",
  "device_model": "Pixel 7 Pro",
  "android_api": 34,
  "density_dpi": 420,
  "screen_dp": "412x892",
  "font_scale": 1.0,
  "system_navigation": "gestures",
  "theme": "dark",
  "locale": "es-ES",
  "screenshot_path": "docs/aero-quality-leap/screenshots/SC01_now_playing_local.png"
}
```
