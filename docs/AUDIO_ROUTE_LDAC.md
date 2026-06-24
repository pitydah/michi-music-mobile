# Audio Route & LDAC Support

## Design Principle

LDAC is treated as a **Bluetooth system output**, not an internal codec. Michi Music Mobile does not implement LDAC encoding/decoding itself — it relies on the Android Bluetooth stack to negotiate the appropriate codec with the connected device.

## What Michi Can Do

| Feature | Implementation |
|---------|---------------|
| Detect active output route | `AudioManager.getDevices(GET_DEVICES_OUTPUTS)` |
| Query Bluetooth codec (API 31+) | `BluetoothCodecStatus` via `BluetoothA2dp` |
| Show Hi-Res recommendations | Informational UI, no control |
| Warn about SBC/AAC fallback | Comparison text with LDAC capability info |
| Guide user to enable LDAC | Link to Developer Options in Settings |

## Audio Route Detection

The app detects and displays the current audio path:

```
AudioRoute:
├── Speaker             → AudioDevices.TYPE_BUILTIN_SPEAKER
├── Wired Headphones    → AudioDevices.TYPE_WIRED_HEADPHONES
├── Bluetooth (SBC)     → AudioDevices.TYPE_BLUETOOTH_A2DP + codec query
├── Bluetooth (AAC)     → (same, different codec)
├── Bluetooth (LDAC)    → (same, different codec)
├── Bluetooth (aptX)    → (same, different codec)
├── USB DAC             → AudioDevices.TYPE_USB_DEVICE
└── Unknown             → Fallback message
```

## LDAC Technical Limitations

1. **LDAC is not lossless.** Even with FLAC source files, LDAC re-compresses for Bluetooth transmission.
2. **Three factors must align:** phone supports LDAC, headphones support LDAC, Android negotiates LDAC.
3. **No guaranteed API:** `BluetoothCodecStatus` is available from API 31, but some manufacturers do not report it correctly.
4. **User control only:** LDAC mode (quality/adaptive/connection) can only be set via Developer Options or manufacturer settings.

## AudioRouteScreen Display

```
┌─────────────────────────────┐
│      Ruta de audio          │
├─────────────────────────────┤
│ Archivo:   FLAC 24/96 kHz   │
│ Motor:     Media3 ExoPlayer │
│ Proces.:   ReplayGain: ON   │
│            EQ: OFF          │
├─────────────────────────────┤
│ Salida:                     │
│ Bluetooth > Sony WH-1000XM5 │
│ Códec activo:    LDAC       │
│ Calidad estimada:           │
│ Alta (comprimida por BT)    │
├─────────────────────────────┤
│ Recomendación:              │
│ Para máxima fidelidad,      │
│ usa un DAC USB-C.           │
└─────────────────────────────┘
```

## USB-C DAC Support

- Detected via `AudioManager.getDevices(TYPE_USB_DEVICE)`
- No custom driver needed — Android handles USB Audio Class natively
- When USB DAC is detected, app recommends it as the best fidelity path

## Development Notes

- `BluetoothCodecStatus` requires `BLUETOOTH_CONNECT` permission (runtime, API 31+)
- On API < 31, codec info is unavailable — show fallback message
- LDAC is a Sony-developed codec included in AOSP
- Bitrate modes: 990 kbps (quality), 660 kbps (adaptive), 330 kbps (stable)

## References

- [Android BluetoothCodecStatus](https://developer.android.com/reference/android/bluetooth/BluetoothCodecStatus)
- [Android Developer Options — Bluetooth codec](https://source.android.com/docs/core/bluetooth/ldac)
- [Sony LDAC official site](https://www.sony.com/electronics/ldac)
