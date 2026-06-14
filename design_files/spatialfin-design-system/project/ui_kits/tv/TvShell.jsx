// TV — app shell: TvTopBar (home/search/library) + routing + MA mini player + sheets
function TvShell() {
  const NS = window.SpatialFinDesignSystem_0d3fe7;
  const { TvTopBar, MaMiniPlayer, MaPlayerPickerSheet, ServerPickerSheet } = NS;
  const [route, setRoute] = React.useState({ screen: "home" });
  const scroller = React.useRef(null);

  // dialogs
  const [serverPickerOpen, setServerPickerOpen] = React.useState(false);
  const [playerPickerOpen, setPlayerPickerOpen] = React.useState(false);
  const [userPickerOpen, setUserPickerOpen] = React.useState(false);
  const [currentServer, setCurrentServer] = React.useState(window.SF_CURRENT_SERVER || "home");
  const [currentUser, setCurrentUser] = React.useState(window.SF_CURRENT_USER || "ignacio");
  const servers = window.SF_SERVERS || [];
  const serverName = (servers.find((s) => s.id === currentServer) || { name: "Jellyfin" }).name;
  const users = window.SF_USERS || [];
  const user = users.find((u) => u.id === currentUser) || users[0] || null;

  // SendSpin / MA player selection (null = Auto/this device)
  const [maSelectedPlayerId, setMaSelectedPlayerId] = React.useState(null);
  const [maPlayers, setMaPlayers] = React.useState(window.SF_MA_PLAYERS || []);
  const maSelectedPlayer = maPlayers.find((p) => p.id === maSelectedPlayerId) || null;
  const maSelectedPlayerName = maSelectedPlayer ? maSelectedPlayer.name : "This device";

  // MA playback session
  const [maTrack, setMaTrack] = React.useState(null);
  const [maPhase, setMaPhase] = React.useState("idle");
  const [maQueue, setMaQueue] = React.useState([]);
  const [maPos, setMaPos] = React.useState(0);
  const maDuration = 240;
  // tick when playing
  React.useEffect(() => {
    if (maPhase !== "playing") return;
    const t = setInterval(() => setMaPos((p) => (p + 1) % maDuration), 1000);
    return () => clearInterval(t);
  }, [maPhase]);

  const playMaTrack = (t, queueRest = []) => {
    setMaTrack(t); setMaQueue([t, ...(queueRest || [])]); setMaPhase("preparing"); setMaPos(0);
    setTimeout(() => setMaPhase("playing"), 1800);
  };
  const playPauseMa = () => setMaPhase((p) => (p === "playing" ? "paused" : p === "paused" ? "playing" : p));
  const nextMa = () => {
    if (maQueue.length < 2) return;
    const next = maQueue[1]; const rest = maQueue.slice(2);
    setMaTrack(next); setMaQueue([next, ...rest]); setMaPos(0); setMaPhase("preparing");
    setTimeout(() => setMaPhase("playing"), 1200);
  };
  const prevMa = () => setMaPos(0);
  const stopMa = () => { setMaTrack(null); setMaPhase("idle"); setMaQueue([]); setMaPos(0); };

  // play a full MA shelf starting at the tapped track
  const onPlayMaTrack = (t) => {
    const shelf = window.SF_MA_CATALOG || [];
    const i = shelf.findIndex((x) => x.id === t.id);
    const rest = i >= 0 ? shelf.slice(i + 1) : [];
    playMaTrack(t, rest);
  };

  // routing helpers
  const findShow = (id) => (window.SF_TV_SHOWS || []).find((s) => s.id === id);
  const findMovie = (id) => (window.SF_TV_MOVIES || []).find((m) => m.id === id);
  const open = (sel) => {
    if (sel.type === "show") setRoute({ screen: "show", item: findShow(sel.id) });
    else if (sel.type === "movie") setRoute({ screen: "movie", item: findMovie(sel.id) });
  };
  const playMovie = (m) => setRoute({ screen: "player", item: m, episode: null });
  const playEpisode = (show, ep) => setRoute({ screen: "player", item: show, episode: ep });
  const playNext = (n) => {
    if (n.kind === "episode") setRoute({ screen: "player", item: n.show, episode: n.ep });
    else if (n.kind === "movie") setRoute({ screen: "player", item: n.movie, episode: null });
  };
  const goHome = () => setRoute({ screen: "home" });

  // top-bar destinations
  const showTopBar = route.screen === "home" || route.screen === "search" || route.screen === "library" || route.screen === "source";
  // reset scroll + focus on screen change
  React.useEffect(() => {
    if (scroller.current) scroller.current.scrollTop = 0;
    if (window.__tvFocusInit) window.__tvFocusInit();
  }, [route.screen, route.item, route.filter, route.source]);

  // group toggle helper
  const toggleGroupMember = (leaderId, memberId, grouped) => {
    setMaPlayers((all) => all.map((p) => {
      if (p.id === memberId) return { ...p, syncedToPlayerId: grouped ? null : leaderId };
      if (p.id === leaderId) {
        const members = new Set(p.groupMemberIds || []);
        if (grouped) members.delete(memberId); else members.add(memberId);
        return { ...p, groupMemberIds: [...members] };
      }
      return p;
    }));
  };

  const inPlayer = route.screen === "player" || route.screen === "nowplaying";

  // Overflow (…) action sheet
  const [overflowFor, setOverflowFor] = React.useState(null); // { kind, item }
  const openOverflow = (ctx) => setOverflowFor(ctx);
  const closeOverflow = () => setOverflowFor(null);
  const overflowActions = overflowFor && window.tvBuildOverflowActions ? window.tvBuildOverflowActions({
    item: overflowFor.item,
    kind: overflowFor.kind === "series" ? "series" : (overflowFor.kind === "episode" ? "episode" : "movie"),
    castActive: !!maSelectedPlayerId, castLabel: maSelectedPlayerName,
    onSyncPlay: () => {}, onPlaybackOptions: () => {},
    onGoToSeries: () => { if (overflowFor.item.seriesId) setRoute({ screen: "show", item: findShow(overflowFor.item.seriesId) }); },
    onGoToSeason: () => {}, onEditExternalIds: () => {},
    onCast: () => setPlayerPickerOpen(true), onRefreshMetadata: () => {},
    onShare: () => {}, onDelete: () => {},
  }) : [];

  return (
    <div className="theme-tv" style={{ position: "relative", width: "100%", height: "100%", background: "var(--surface-app)", overflow: "hidden" }}>
      {/* page scroller */}
      <div ref={scroller} data-scroll style={{ position: "absolute", inset: 0, overflowY: inPlayer ? "hidden" : "auto", overflowX: "hidden", scrollbarWidth: "none",
        paddingTop: showTopBar ? 104 : 0 }}>
        {route.screen === "home" && <window.TvHome onOpen={open}
          onPlayMaTrack={onPlayMaTrack} onOpenOverflow={openOverflow}
          onSourceSeeAll={(src) => setRoute({ screen: "source", source: src })} />}
        {route.screen === "search" && <window.TvSearch onOpen={open} />}
        {route.screen === "library" && <window.TvLibrary initialFilter={route.filter || "all"} onOpen={open} />}
        {route.screen === "source" && <window.TvSourceBrowse source={route.source} onBack={goHome} />}
        {route.screen === "show" && <window.TvShowDetail show={route.item} onBack={goHome} onPlay={playEpisode} onOpenOverflow={openOverflow} />}
        {route.screen === "movie" && <window.TvMovieDetail movie={route.item} onBack={goHome} onPlay={playMovie} onOpenOverflow={openOverflow} />}
        {route.screen === "player" && <window.TvPlayer item={route.item} episode={route.episode} onPlayNext={playNext}
          onBack={() => setRoute(route.episode ? { screen: "show", item: route.item } : { screen: "movie", item: route.item })} />}
        {route.screen === "nowplaying" && <window.TvNowPlaying
          track={maTrack} phase={maPhase} queue={maQueue} position={maPos} duration={maDuration}
          selectedPlayer={maSelectedPlayer ? maSelectedPlayer.name : null}
          onBack={() => setRoute({ screen: "home" })}
          onPlayPause={playPauseMa} onNext={nextMa} onPrev={prevMa} onStop={stopMa}
          onPickPlayer={() => setPlayerPickerOpen(true)}
          onOpenQueue={() => {}} />}
      </div>

      {showTopBar && TvTopBar ? (
        <TvTopBar serverName={serverName} logoSrc="../../assets/logo-mark.png" user={user}
          onServerClick={() => setServerPickerOpen(true)}
          onUserClick={() => setUserPickerOpen(true)}
          onSearchClick={() => setRoute({ screen: "search" })}
          onSettingsClick={() => { /* settings stub */ }}
          onCloseClick={() => { /* close stub */ }}
          onRetryClick={() => {}} />
      ) : null}

      {/* persistent MA mini player — visible on Home / Search / Library / Source / details */}
      {!inPlayer && maTrack && MaMiniPlayer ? (
        <div style={{ position: "absolute", left: 56, right: 56, bottom: 22, zIndex: 18 }}>
          <MaMiniPlayer track={maTrack} phase={maPhase} selectedPlayer={maSelectedPlayerName}
            onPlayPause={playPauseMa} onNext={nextMa} onStop={stopMa}
            onExpand={() => setRoute({ screen: "nowplaying" })} />
        </div>
      ) : null}

      {ServerPickerSheet ? (
        <ServerPickerSheet open={serverPickerOpen} servers={servers} currentId={currentServer}
          onPick={setCurrentServer} onManage={() => {}} onDismiss={() => setServerPickerOpen(false)} />
      ) : null}

      {MaPlayerPickerSheet ? (
        <MaPlayerPickerSheet open={playerPickerOpen} players={maPlayers} selectedId={maSelectedPlayerId}
          onPick={setMaSelectedPlayerId} onToggleGroupMember={toggleGroupMember}
          onDismiss={() => setPlayerPickerOpen(false)} />
      ) : null}
      {window.TvOverflowSheet ? (
        <window.TvOverflowSheet open={userPickerOpen}
          title="Switch user" subtitle="Choose a Jellyfin profile on this server"
          actions={users.map((u) => ({
            id: u.id,
            leading: u.avatar
              ? <img src={u.avatar} alt="" style={{ width: 48, height: 48, borderRadius: "50%", objectFit: "cover", flexShrink: 0 }} />
              : <span style={{ width: 48, height: 48, borderRadius: "50%", flexShrink: 0, background: u.color || "var(--surface-container-highest)", color: u.textColor || "#fff", fontSize: 17, fontWeight: 700, display: "inline-flex", alignItems: "center", justifyContent: "center" }}>{u.initials || u.name[0]}</span>,
            label: u.name + (u.id === currentUser ? " (current)" : ""),
            sub: u.role,
            onClick: () => setCurrentUser(u.id),
          })).concat([{ id: "add", icon: "user-plus", label: "Add another user", sub: "Sign in with a different account", onClick: () => {} }])}
          onClose={() => setUserPickerOpen(false)} />
      ) : null}
      {window.TvOverflowSheet ? (
        <window.TvOverflowSheet open={!!overflowFor}
          title={overflowFor ? ("More actions") : ""}
          subtitle={overflowFor ? overflowFor.item.title : ""}
          actions={overflowActions} onClose={closeOverflow} />
      ) : null}
    </div>
  );
}
window.TvShell = TvShell;
