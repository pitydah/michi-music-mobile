# TESTING — Michi Music Mobile

## Validación manual

### 1. Build e install

```bash
./gradlew assembleNormalDebug
adb uninstall org.michimusic.mobile.debug || true
adb install -r app/build/outputs/apk/normal/debug/app-normal-debug.apk
```

### 2. Verificar paquete instalado

```bash
adb shell dumpsys package org.michimusic.mobile.debug | grep -E "versionName|versionCode|targetSdk"
adb shell pm path org.michimusic.mobile.debug
```

### 3. Logcat

```bash
adb logcat -c
adb shell am force-stop org.michimusic.mobile.debug
adb shell monkey -p org.michimusic.mobile.debug 1
adb logcat | grep -E "MichiAudio|MediaController|ExoPlayerImpl|MediaSessionImpl|PlaybackResumption|UnsupportedOperationException"
```

#### Al abrir sin interactuar:
- NO debe aparecer `MichiAudio: ensureConnected called`
- NO debe aparecer `ExoPlayerImpl Init`
- NO debe aparecer `UnsupportedOperationException onPlaybackResumption`

#### Al tocar reproducir:
- `MichiAudio: playQueue requested`
- `MediaController ready`
- `ExoPlayerImpl Init`

### 4. Pruebas unitarias

```bash
./gradlew :core:test
```

## Numeración de versiones

### Canales y prioridad

Android `versionCode` se resuelve en este orden:

1. `MICHI_VERSION_CODE`, obligatorio para una publicación oficial.
2. `1_000_000 + GITHUB_RUN_NUMBER` en GitHub Actions, para artefactos QA de una
   ejecución de workflow e independiente de la profundidad del checkout.
3. El recuento completo de commits Git para compilaciones locales.
4. `1` solo al compilar un archivo fuente sin metadatos Git.

Estos canales no constituyen una única secuencia de actualización. En particular,
un APK local de desarrollo puede tener un código menor que un APK QA instalado
desde GitHub Actions. Para cambiar entre ambos se debe desinstalar la aplicación
de depuración o compilar con un `MICHI_VERSION_CODE` superior al instalado; no se
debe usar `adb install -d` como flujo normal.

`GITHUB_RUN_NUMBER` identifica la ejecución lógica y no cambia al reintentarla.
Por ello, los artefactos automáticos de un reintento pueden repetir `versionCode`
y **no son artefactos de release**.

### Publicaciones oficiales

`gradle/version-code.properties` conserva `lastPublishedVersionCode`, cuya fuente
actual es el APK de la última GitHub Release publicada. Antes de publicar:

1. inspeccionar el `versionCode` del último APK oficial;
2. actualizar `lastPublishedVersionCode` si la release publicada es más reciente;
3. elegir un `MICHI_VERSION_CODE` único y estrictamente mayor;
4. compilar y verificar el APK resultante;
5. tras publicar, actualizar el valor registrado para la siguiente release.

El resolver rechaza un `MICHI_VERSION_CODE` que no supere el valor publicado y
valida el límite de Android antes de sumar el desplazamiento CI, evitando overflow.
Sin `MICHI_VERSION_CODE` no debe publicarse ningún APK como oficial.

Los valores explícitos, publicados o de GitHub Actions inválidos detienen la
configuración en vez de producir silenciosamente un downgrade. Las pruebas de
esta política se ejecutan con:

```bash
./gradlew -p buildSrc test
```
