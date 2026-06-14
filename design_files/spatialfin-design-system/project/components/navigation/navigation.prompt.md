# Navigation

The phone bottom navigation bar. (On XR, navigation lives in an `Orbiter`; on TV it's a focusable rail — see those surfaces.)

```jsx
import { NavBar } from "./NavBar.jsx";

const [tab, setTab] = React.useState("home");
<NavBar active={tab} onChange={setTab} items={[
  { id: "home", icon: "house", label: "Home" },
  { id: "search", icon: "search", label: "Search" },
  { id: "downloads", icon: "download", label: "Downloads" },
  { id: "settings", icon: "settings", label: "Settings" },
  { id: "local", icon: "folder", label: "Local" },
]} />
```

- Active destination shows a tonal pill behind its icon + an accent label (Material 3).
- Keep 4–7 destinations; SpatialFin uses Home / Search / Downloads / Settings / Users / Local / Network.

## ServerPickerSheet

The Jellyfin server switcher modal — used from `TvTopBar`'s server tile and the matching Beam/XR header. Mirrors the real app's `ServerSelectionBottomSheet`.

```jsx
import { ServerPickerSheet } from "./ServerPickerSheet.jsx";

<ServerPickerSheet open
  servers={[{ id: "home", name: "Home", address: "http://192.168.1.42:8096" }]}
  currentId="home" onPick={setServerId} onManage={openManageScreen}
  onDismiss={close} />
```
