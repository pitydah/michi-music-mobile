# Viabilidad Técnica de Transferencia AirCat sobre USB en Android

**Autor:** Equipo Michi Music Mobile  
**Estado:** ANÁLISIS DE FACTIBILIDAD Y DIRECTIVAS DE ARQUITECTURA  
**Fase:** M9  

---

## 1. Problema de Viabilidad en Android

Muchos reproductores anuncian "sincronización USB" asumiendo ciegamente que conectar el cable USB permite transferir archivos de música directamente hacia la aplicación. En el sistema operativo Android moderno (Android 10+, API 29-35), esta suposición es **falsa y peligrosa**:

1. **Scoped Storage y Aislamiento de Sandbox:**
   Las aplicaciones en Android no pueden acceder arbitrariamente a sistemas de archivos montados ni a carpetas privadas de otras apps sin SAF o permisos estrictos. La carpeta de música privada de la aplicación (`context.filesDir/synced_music`) es totalmente inaccesible desde el exterior salvo que el dispositivo esté rooteado.
2. **MTP (Media Transfer Protocol):**
   MTP es controlado enteramente por el daemon del sistema operativo Android y el MediaProvider. Una aplicación de usuario no puede interceptar transferencias MTP en curso ni garantizar atomicidad, transaccionalidad o validación de hash SHA-256 en tiempo real.
3. **Restricción de Rol USB:**
   Cuando un teléfono Android se conecta a una computadora (PC/Mac/Linux), el teléfono actúa típicamente como **USB Device** (MTP/PTP/Charging), mientras que la PC actúa como **USB Host**. Para que Android actúe como receptor controlado por la app, se requieren configuraciones específicas.

---

## 2. Modalidades Técnicas Evaluadas

| Modalidad | Viabilidad en Android | Requiere Root/ADB | Evaluación para AirCat |
|---|---|---|---|
| **A. USB Accessory Mode (AOA)** | **VIABLE** | **NO** | La PC actúa como USB Host e inicia protocolo Android Open Accessory. La app de Android abre un `FileDescriptor` bidireccional mediante `UsbManager.openAccessory()`. Requiere que el software de PC implemente libusb/AOA. |
| **B. ADB Port Forwarding** | **VIABLE** (Desarrollo) | **SÍ (Depuración USB)** | `adb forward tcp:8080 tcp:8080`. Muy eficiente para pruebas y usuarios avanzados, pero inviable para usuarios finales en Google Play. |
| **C. USB Host Mode (OTG)** | **PARCIALMENTE VIABLE** | **NO** | El teléfono actúa como host leyendo un pendrive/disco externo USB formateado en FAT32/exFAT. No sirve para conectar teléfono a PC directamente sin hardware OTG intermedio. |
| **D. MTP Automático** | **NO RECOMENDADO** | **NO** | No provee sockets de aplicación, callbacks de progreso confiables ni integridad transaccional previa a la promoción. |

---

## 3. Directiva de Implementación para Mobile

1. **No engañar al usuario:** La UI móvil **NO** mostrará "Sincronización USB conectada" simplemente porque el broadcast `ACTION_POWER_CONNECTED` o `ACTION_USB_DEVICE_ATTACHED` se dispare.
2. **Transporte Primario AirCat:** El transporte primario y garantizado de producción es **LAN Wi-Fi** (HTTP Streaming autenticado con soporte Range y Checkpoints transaccionales de M8).
3. **Transporte Secundario (USB Accessory):** Queda definido por interfaz para cuando el software de escritorio Michi Devices establezca el canal AOA (Android Open Accessory) o túnel local TCP.
4. **Declaración en Ledger:** Se reporta el estado con veracidad técnica: el canal de red LAN y la máquina de estados de contrato están 100% operativos; el soporte físico USB físico queda marcado como dependiente de hardware y protocolo AOA (`PENDING_HARDWARE`).
