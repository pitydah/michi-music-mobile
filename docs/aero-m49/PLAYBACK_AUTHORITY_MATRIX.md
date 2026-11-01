# Playback Authority Matrix & Destination Governance (M49)

**Auditor:** Distributed Audio Reliability Engineer & Principal Android Engineer  
**Milestone:** M49 — AERO FINAL 100  
**Branch:** `integration/m46d-aero-premium`  

---

## 1. Playback Authority Invariant

> **Strict Invariant:** At any instant, exactly **one** playback authority exists across the distributed Michi audio mesh. Simultaneous multi-authority playback or uncoordinated double-pipeline emission is strictly forbidden.

### Destination Endpoints & Device Profiles

| Destination Type | Protocol / Transport | Local Pipeline State | Authority Holder | Hardware Controls |
|---|---|---|---|---|
| **Local Device** (Phone) | Android ExoPlayer / AudioTrack | Active (Playing/Paused) | Local `PlaybackSessionManager` | Android physical volume buttons |
| **Remote Host** (Desktop/Mac) | WebSocket / HTTP Michi-Link | Surrendered (Paused & AudioTrack released) | Remote Host Daemon | Remote Host Volume via RemoteScreen |
| **Michi Stream** | Uncompressed PCM / I2S Network Audio | Surrendered (Local decode suspended) | Michi Stream Receiver | Dedicated DAC Attenuator |

---

## 2. Playback Authority State Transition Matrix

```
       [ LOCAL PLAYBACK ]
             │         ▲
       Attach│         │Handoff / Disconnect
       Remote│         │Local
             ▼         │
      [ CONFIRMATION GATE ]
             │
     User    ├─── Replace ───► [ REMOTE ACTIVE (Wipe & Load) ]
    Decision └─── Append  ───► [ REMOTE ACTIVE (Queue Preserved) ]
             └─── Cancel  ───► [ LOCAL PLAYBACK (Aborted) ]
```

| Current State | Trigger Event | Precondition | Confirmation Required? | Target State | Audio Focus Action |
|---|---|---|---|---|---|
| **Local Active** | User selects Remote Host | Remote queue is empty | No | **Remote Active** | Abandon local audio focus; Pause ExoPlayer. |
| **Local Active** | User selects Remote Host | Remote queue has active items | **YES (K48-05)** | Pending User Choice | Local playback continues until choice confirmed. |
| **Pending Confirmation** | User selects "Reemplazar" | Confirmation Dialog displayed | Completed | **Remote Active** | Flush remote queue, send new queue, surrender local focus. |
| **Pending Confirmation** | User selects "Añadir al final" | Confirmation Dialog displayed | Completed | **Remote Active** | Append items to remote queue, surrender local focus. |
| **Pending Confirmation** | User selects "Cancelar" | Confirmation Dialog displayed | Completed | **Local Active** | Maintain local audio; dismiss dialog. |
| **Remote Active** | Network Disconnect / Error | Ping heartbeat timeout | No | **Local Paused** | Mark remote OFFLINE, restore local session in paused state. |
| **Remote Active** | User selects "Handoff to Local" | Local queue available | No | **Local Active** | Query remote position, resume local ExoPlayer at matched timestamp. |

---

## 3. Remote Queue Protection Rules (K48-01 / K48-05)

1. **Non-Destructive Default:** Switching output targets NEVER silently wipes a destination's active queue.
2. **Explicit Dialogue:** If destination queue size $> 0$, the UI displays:
   - Destination name and current track playing.
   - Distinct options: `Reemplazar cola` (Destructive) vs `Añadir a la cola` (Non-destructive).
3. **Cancellation Safety:** Dismissing the prompt or navigating away aborts the remote command with zero mutations to the remote queue.
4. **Volume Slider Absence:** NowPlaying and MiniPlayer contain NO software volume sliders. Volume is strictly delegated to hardware rocker buttons (Local) or the dedicated remote device options (RemoteScreen).

