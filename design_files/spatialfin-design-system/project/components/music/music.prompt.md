# Music

Music Assistant (MA) playback surfaces shared across SpatialFin form factors. MA is the project's universal music backend — Spotify / YouTube Music / Plex et al all flow through the same player + queue. SendSpin is the routing layer that sends audio to a specific speaker, optionally synchronizing multiple players into a multi-room group.

```jsx
import { MaMiniPlayer } from "./MaMiniPlayer.jsx";
import { MaPlayerPickerSheet } from "./MaPlayerPickerSheet.jsx";

<MaMiniPlayer
  track={{ title: "Midnight City", artist: "M83", artwork: url }}
  phase="playing"               // preparing | playing | paused | idle
  selectedPlayer="Living Room"
  onPlayPause={() => …} onNext={() => …} onStop={() => …}
  onExpand={() => openNowPlaying()} />

<MaPlayerPickerSheet
  open players={players} selectedId={selectedId}
  onPick={setSelectedId}
  onToggleGroupMember={toggleGroup}
  onDismiss={close} />
```

- **MaMiniPlayer** is persistent — visible whenever a track is loaded. The `preparing` state surfaces the optimistic "MA is loading the queue" gap (top progress strip + "Preparing audio…") so the user has signal during the 5–15 s cold-play window. `stop` dismisses the bar (distinct from pause).
- **MaPlayerPickerSheet** mirrors the real MA app: an **Auto (this device)** entry at the top clears the override and lets SendSpin auto-detect the local wrapper. When the selected player supports grouping, an **Also play on (in sync)** section appears for multi-room.
