# Voice

SpatialFin is voice-first, with parity across XR, Beam and TV. These render the assistant's affordance and state.

```jsx
import { VoiceFab } from "./VoiceFab.jsx";
import { VoiceFeedback } from "./VoiceFeedback.jsx";

<VoiceFab state="listening" />                    {/* idle | listening | processing */}

<VoiceFeedback state="listening" />               {/* "Listening…" */}
<VoiceFeedback state="answered" text="Playing Big Buck Bunny." />
```

- **VoiceFab** — the 56dp mic FAB (Beam primary affordance). Pulses a ring while listening; tap-while-busy cancels. In XR the same intent is a mic in the orbiter + near-face open-palm hold.
- **VoiceFeedback** — glass capsule showing listening / processing / answered / error. Anchor **top-center** on Beam (never under the FAB). Visual intent must match across surfaces.
- Don't add per-item rationale to recommendation replies — titles only (DESIGN.md).
