# Android Reality Check — Screenshots Manifest (M49 AERO FINAL 100)

**Validation Date:** 2026-10-31  
**Target Environment:** Genuine Android Emulator (AVD: `Medium_Phone`)  
**OS Version:** Android 16 (API 36 / VanillaIceCream)  
**Display Resolution:** 1080 × 2400 px, Density: 420 dpi (xxhdpi)  
**Package:** `org.michimusic.mobile.debug` (`app-normal-debug.apk`)  
**Branch:** `integration/m46d-aero-premium`  

---

## 1. Device & Runtime Verification

| Parameter | Value |
|---|---|
| AVD Name | `Medium_Phone` |
| ABI | `x86_64` |
| Framebuffer Geometry | `1080x2400` |
| Window Orientation | `0` (Portrait) |
| System Theme | Dark / Surface Obsidian (`#0D0D0E`) |
| Edge-to-Edge Navigation | Enabled (`enableEdgeToEdge` with dark glyphs) |
| Pure Icon Controls Verified | Yes — Zero background disks, rings, bubbles, or pills under pictograms |

---

## 2. Screenshot Ledger & Authenticity Hashes

| Artifact File | Dimensions | SHA-256 Checksum | Screen & State Verified |
|---|---|---|---|
| `emulator_home.png` | 1080 × 2400 | `562154a92ff9e2768cce27da799e5a3343f7eb53297b7895953312345c5905b0` | Home Screen (`Inicio`): Quick actions, recently played cards, Aero obsidian theme. |
| `emulator_library_list.png` | 1080 × 2400 | `f1b2e440ce3024db9e2b27e158b6b92dcace880f3884b3e6def05b0e781b42ea` | Library List Mode (`Biblioteca`): Grid/list view mode selector, album tracks listing. |
| `emulator_library_coverflow_real.png` | 1080 × 2400 | `a79347f41a7d2dd47a4274638422e6bdc77ca75c4fd1101d7536df1f772a487c` | **Genuine CoverFlow 3D Carousel**: 3D rotation along Y-axis, reflection floor, central album elevation, track explorer panel. |
| `emulator_miniplayer.png` | 1080 × 2400 | `5c0f0ea25af181f8811fccb0f79c96ba9a5048180637d378433ec2c69932d1a0` | MiniPlayer docked above 4-tab bottom bar: pure icon-only play/pause, zero volume sliders, smooth swipe affordance. |
| `emulator_nowplaying_playing.png` | 1080 × 2400 | `514630c7e873c06fcfb1d5bc571a66e847c4db43439de4c83449215753863738` | NowPlaying (Playing): 64dp hero Pause icon with NO circular background disc, pure icon previous/next, seek slider, Signal Truth badge. |
| `emulator_nowplaying_paused.png` | 1080 × 2400 | `2484a0249404bedd4a95e3f0ffb80f5afda7f9e1709a24c695f7ee7b6af3b723` | NowPlaying (Paused): Hero Play icon with pure transparent backing, 48dp min hitboxes. |
| `emulator_signal_truth_sheet.png` | 1080 × 2400 | `f557c06fac7bb4c4353b5278f95466ad38b460642694474f641499483af144e0` | Signal Truth Bottom Sheet: Real provenance, genuine sample rate & bit depth (44.1 kHz / 16-bit PCM), latency, zero fake measurements. |
| `emulator_queue.png` | 1080 × 2400 | `3d31f2585b8562007bb73bb61380398837f5c4df1d4cb36a025068c941818ded` | Playback Queue (`Cola de Reproducción`): Track ordering, drag handles, active indicator. |
| `emulator_search.png` | 1080 × 2400 | `3e33a2f2944134de1ba3b3dd602109093ec142e3eccb6b97aade1eaa2da95868` | Search Screen (`Buscar`): Query input, filtered results, instant response. |
| `emulator_devices.png` | 1080 × 2400 | `91b49c3e59d16d16452089014c99b6f66da09b60354fab49389d7e3380f3ece6` | Devices Screen (`Dispositivos`): 4th navigation tab, AirCat transfer entry, discovered endpoints. |
| `emulator_aircat_dialog.png` | 1080 × 2400 | `3863022b1f1919285c59f43998ef35f86550770a9db16edea70ad701c3ca36eb` | AirCat Device Picker: Filtered through `AirCatEligibilityPolicy` (verified only, no unverified/Stream/DAC). |
| `emulator_settings.png` | 1080 × 2400 | `9042aa3e0cd10eb3934b85ab164c4093a7964118f585d5d1e6b89ac43ce34fd9` | Settings Screen: Visual theme options, audio engine configurations, diagnostics route. |
| `emulator_remote_disconnected.png` | 1080 × 2400 | `55bb06c852d853bc3cd6332e5fc2a2b921081e0faf48e40437c53f66a14acced` | Remote Screen Disconnected State: Clear empty state guidance, "No hay dispositivo conectado", CTA button. |
| `emulator_remote_connected.png` | 1080 × 2400 | `a906b68d8993db4a95cb48d733fed069ae5695f1cad3b78543141daf4d48fd54` | Remote Screen Connected State: Active endpoint ("Michi Studio Mac"), live elapsed position, icon-only transport controls. |

