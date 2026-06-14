// Beam (phone) — app shell: nav + routing + voice + cast/syncplay/server sheets
function BeamApp() {
  const NS = window.SpatialFinDesignSystem_0d3fe7;
  const { NavBar, VoiceFab, ServerPickerSheet } = NS;
  const [route, setRoute] = React.useState({ screen: "home", item: null });
  const [tab, setTab] = React.useState("home");
  const [voice, setVoice] = React.useState("idle");
  const scroller = React.useRef(null);

  // ---- app-level cast / syncplay / server state ----
  const devices = window.SF_CAST_DEVICES || [];
  const [castVideo, setCastVideo] = React.useState("this");
  const [castAudio, setCastAudio] = React.useState(null);   // null = follow video
  const [syncIds, setSyncIds] = React.useState([]);          // multi-room speakers
  const [syncGroup, setSyncGroup] = React.useState(null);    // SyncPlay watch-together
  const [currentServer, setCurrentServer] = React.useState(window.SF_CURRENT_SERVER || "home");
  const [currentUser, setCurrentUser] = React.useState(window.SF_CURRENT_USER || "ignacio");
  const [appSheet, setAppSheet] = React.useState(null);      // "cast" | "syncplay" | "server" | "user"

  const servers = window.SF_SERVERS || [];
  const serverName = (servers.find((s) => s.id === currentServer) || { name: "Jellyfin" }).name;
  const user = (window.SF_USERS || []).find((u) => u.id === currentUser) || (window.SF_USERS || [])[0];
  const videoDev = devices.find((d) => d.id === castVideo) || devices[0];
  const audioDev = castAudio ? devices.find((d) => d.id === castAudio) : null;
  const castSplit = !!castAudio && castAudio !== castVideo;
  const castActive = castVideo !== "this" || !!castAudio || syncIds.length > 0;
  const castLabel = castVideo !== "this" ? (videoDev && videoDev.name)
    : (audioDev ? audioDev.name : "This device");
  const syncActive = !!syncGroup;

  const toggleSync = (id) => setSyncIds((cur) => cur.includes(id) ? cur.filter((x) => x !== id) : [...cur, id]);

  const open = (item, screen) => { setRoute({ screen, item }); if (scroller.current) scroller.current.scrollTop = 0; };
  const goTab = (id) => { setTab(id); setRoute({ screen: id, item: null }); if (scroller.current) scroller.current.scrollTop = 0; };
  const openDetail = (item) => open(item, "detail");

  // voice demo: idle -> listening -> processing -> answered -> idle
  const cycleVoice = () => {
    if (voice !== "idle") { setVoice("idle"); return; }
    setVoice("listening");
    setTimeout(() => setVoice("processing"), 1600);
    setTimeout(() => setVoice("answered"), 3000);
    setTimeout(() => setVoice("idle"), 5200);
  };

  const inPlayer = route.screen === "player";
  const isTabScreen = ["home", "search", "sources", "downloads", "settings"].includes(route.screen);

  return (
    <div style={{ position: "relative", width: "100%", height: "100%", display: "flex", flexDirection: "column", background: "var(--surface-app)", overflow: "hidden" }}>
      <div ref={scroller} style={{ flex: 1, overflowY: "auto", position: "relative", scrollbarWidth: "none" }}>
        {route.screen === "home" && <window.BeamHome onOpen={open}
          serverName={serverName} castActive={castActive} castLabel={castLabel} user={user}
          onOpenServer={() => setAppSheet("server")} onOpenCast={() => setAppSheet("cast")} onOpenUser={() => setAppSheet("user")} />}
        {route.screen === "search" && <window.BeamSearch onOpen={openDetail} />}
        {route.screen === "sources" && <window.BeamSources onOpen={openDetail} />}
        {route.screen === "downloads" && <window.BeamDownloads onOpen={openDetail} />}
        {route.screen === "settings" && <window.BeamSettings serverName={serverName} user={user}
          castLabel={castActive ? castLabel : "This device"}
          onOpenServer={() => setAppSheet("server")} onOpenCast={() => setAppSheet("cast")} onOpenUser={() => setAppSheet("user")} />}
        {route.screen === "detail" && <window.BeamDetail item={route.item} onBack={() => goTab("home")} onPlay={() => open(route.item, "player")}
          onOpenCast={() => setAppSheet("cast")} onOpenSyncPlay={() => setAppSheet("syncplay")}
          castActive={castActive} castLabel={castLabel} />}
        {route.screen === "player" && <window.BeamPlayer item={route.item} onBack={() => setRoute({ screen: "detail", item: route.item })}
          voiceState={voice} onVoice={cycleVoice}
          onOpenCast={() => setAppSheet("cast")} castActive={castActive} castLabel={castLabel} castSplit={castSplit}
          onOpenSyncPlay={() => setAppSheet("syncplay")} syncActive={syncActive} />}
      </div>

      {/* mic FAB — hidden in player (player has its own mic) */}
      {!inPlayer ? (
        <div style={{ position: "absolute", right: 18, bottom: 92, zIndex: 5 }}>
          <VoiceFab state={voice} onClick={cycleVoice} />
        </div>
      ) : null}

      {/* voice feedback chip anchored top-center (non-player) */}
      {!inPlayer && voice !== "idle" ? (
        <div style={{ position: "absolute", top: 64, left: 0, right: 0, display: "flex", justifyContent: "center", zIndex: 6, padding: "0 16px" }}>
          <NS.VoiceFeedback state={voice === "listening" ? "listening" : voice === "processing" ? "processing" : "answered"}
            text={voice === "answered" ? "Starting Big Buck Bunny." : undefined} />
        </div>
      ) : null}

      {!inPlayer ? (
        <NavBar active={tab} onChange={goTab} items={[
          { id: "home", icon: "house", label: "Home" },
          { id: "search", icon: "search", label: "Search" },
          { id: "sources", icon: "puzzle", label: "Sources" },
          { id: "downloads", icon: "download", label: "Downloads" },
          { id: "settings", icon: "settings", label: "Settings" },
        ]} />
      ) : null}

      {/* ---- app-level sheets ---- */}
      <window.CastSheet open={appSheet === "cast"} videoId={castVideo} audioId={castAudio} syncIds={syncIds}
        onClose={() => setAppSheet(null)}
        onPickVideo={setCastVideo} onPickAudio={setCastAudio} onToggleSync={toggleSync} />
      <window.SyncPlaySheet open={appSheet === "syncplay"} group={syncGroup}
        onClose={() => setAppSheet(null)}
        onJoin={(g) => { setSyncGroup(g); setAppSheet(null); }}
        onLeave={() => { setSyncGroup(null); setAppSheet(null); }}
        onCreate={() => { setSyncGroup({ id: "new", name: "My Room", members: 1, ping: 18 }); }} />
      {ServerPickerSheet ? (
        <ServerPickerSheet open={appSheet === "server"} servers={servers} currentId={currentServer}
          onPick={setCurrentServer} onManage={() => {}} onDismiss={() => setAppSheet(null)} style={{ position: "absolute" }} />
      ) : null}
      <window.UserPickerSheet open={appSheet === "user"} currentId={currentUser}
        onPick={setCurrentUser} onClose={() => setAppSheet(null)} />
    </div>
  );
}
window.BeamApp = BeamApp;
