# UI/UX Guide — Michi Music Mobile

## Filosofía visual

Michi Music Mobile es el cliente Android de Michi Music Player (KDE/Linux).  
La UI debe ser:

- **Oscura y premium**: fondo `#090B11` (SurfaceDark), acentos rosa/ coral.
- **Glassmorphism sutil**: tarjetas semitransparentes con bordes tenues, sin blur pesado.
- **Coherente**: todas las pantallas usan los mismos tokens, colores y componentes.
- **Liviana**: evitar sombras excesivas, gradientes complejos y animaciones costosas.
- **Orientada a contenido**: la música es la protagonista, no la decoración.

## Colores

Ver `ui/theme/Color.kt`:

- `SurfaceDark` (`#090B11`) — fondo principal
- `SurfaceElevated` (`#12141E`) — tarjetas y elevaciones
- `GlassBg` (`#AA151820`) — fondos glass
- `GlassBorder` (`#1AFFFFFF`) — bordes glass
- `AccentPink` (`#FF6B9D`) — acento primario, items activos
- `AccentCoral` (`#FF6A3D`) — acento secundario
- `TextPrimary` (`#D9FFFFFF`) — texto principal
- `TextSecondary` (`#9EFFFFFF`) — texto secundario
- `TextMuted` (`#85FFFFFF`) — texto tenue
- `TextDim` (`#52FFFFFF`) — texto casi invisible

No hardcodear colores nuevos en pantallas. Si un color no existe, agregarlo a `Color.kt`.

## Tokens

Ver `ui/theme/MichiTokens.kt`:

- `MichiRadius`: small (6dp), card (14dp), pill (28dp)
- `MichiSpacing`: xs (4dp), sm (8dp), md (12dp), lg (16dp), xl (24dp), xxl (32dp)
- `MichiSize`: iconSmall (20dp), coverSmall (40dp), miniPlayerHeight (64dp)
- `MichiAlpha`: glassBg (0.67), glowPink (0.20), hover (0.08)

Usar estos tokens en lugar de valores literales.

## Componentes reutilizables

Todos en `ui/components/`:

| Componente | Uso |
|------------|-----|
| `GlassCard` | Tarjetas con variantes NORMAL / COMPACT / STRONG |
| `MichiBackground` | Fondo gradiente oscuro (opcional, no obligatorio) |
| `MichiScreen` | Wrapper con padding horizontal (opcional) |
| `MichiSectionHeader` | Título + subtítulo + acción |
| `MichiEmptyState` | Estado vacío con icono, título, descripción, acción |
| `MichiLoadingState` | Spinner + texto |
| `MichiActionButton` | Botón PRIMARY_GLOW (relleno) o SECONDARY_GLASS (borde) |
| `MichiIconButton` | Botón icono compacto |
| `MichiSlider` | Slider de progreso o volumen con colores Michi |
| `MichiBottomNavigation` | Barra de navegación inferior smoked glass |
| `TrackRow` | Fila de canción con título, artista, duración |
| `MiniPlayer` | Reproductor mini inferior, solo visible con currentTrack |
| `MichiSearchBar` | (futuro) Barra de búsqueda |

## Navegación

- `MichiBottomNavigation` con 6 items: Inicio, Biblioteca, Reproduciendo, Control, Sync, Ajustes
- La barra se oculta en NowPlaying
- MiniPlayer se oculta en NowPlaying
- Rutas internas (no en bottom bar): queue, playlist, playlists, synced, search, audio-route, diagnostics

## MiniPlayer

- Solo visible cuando hay `currentTrack != null`
- No mostrar "Sin reproducción" — ocultar completamente
- Fondo glass, bordes redondeados
- Título/artista + play/pause + skip anterior/siguiente
- Progress bar lineal fina
- Al tocar, navega a NowPlaying
- No debe inicializar Media3 al renderizarse (usa AudioController lazy)

## NowPlaying

- Fondo gradiente oscuro
- Selector de fuente (pill glass) — por defecto "Este dispositivo"
- Carátula grande con fallback synthwave
- Slider de progreso con tiempos
- Controles: shuffle, anterior, play/pause grande, siguiente, repeat
- Volumen (slider local, no conectado a Media3)
- Acciones secundarias: cola, ruta de audio, ajustes
- Sin bottom bar duplicada

## Estados vacíos

Todas las pantallas deben manejar:
- Cargando: `MichiLoadingState`
- Vacío: `MichiEmptyState` con icono, título, descripción
- Error: `MichiEmptyState` con mensaje + acción de reintento
- Permiso faltante: mensaje claro + guía para concederlo

## Reglas para nuevas pantallas

1. Extender `MichiScreen` o usar `Box + background(SurfaceDark)`
2. Usar `MichiSpacing` para paddings
3. Usar `MichiRadius` para esquinas redondeadas
4. No hardcodear colores
5. Inyectar `AudioController` con `rememberAudioController()`
6. Reproducir solo al hacer click, nunca al componer
7. Manejar estados: loading, empty, error
