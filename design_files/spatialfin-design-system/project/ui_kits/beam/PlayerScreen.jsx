// Beam (phone) — Player screen with full transport + quality/audio/subtitle/cast/syncplay
function BeamPlayer({ item, onBack, voiceState, onVoice,
  onOpenCast, castActive, castLabel, castSplit,
  onOpenSyncPlay, syncActive }) {
  const NS = window.SpatialFinDesignSystem_0d3fe7;
  const { IconButton, Pill, ProgressBar, VoiceFeedback, Icon } = NS;
  const [playing, setPlaying] = React.useState(true);
  const [sheet, setSheet] = React.useState(null); // "quality" | "audio" | "subtitle" | null
  const [quality, setQuality] = React.useState("auto");
  const [audio, setAudio] = React.useState("en51");
  const [subtitle, setSubtitle] = React.useState("off");
  const showVoice = voiceState && voiceState !== "idle";

  const qualLabel = { auto: "Auto", orig: "Original", "1080": "1080p", "720": "720p", "480": "480p" }[quality];
  const audioLabel = { en51: "EN 5.1", enatmos: "Atmos", en20: "EN", comm: "Comm.", es: "ES" }[audio];
  const subLabel = subtitle === "off" ? "Off" : { ensdh: "EN SDH", enforced: "Forced", es: "ES", fr: "FR" }[subtitle];

  // bottom control: stacked icon + label, with active accent state
  const Ctrl = ({ icon, label, value, active, onClick }) => (
    <button type="button" onClick={onClick}
      style={{ display: "flex", flexDirection: "column", alignItems: "center", gap: 6, flex: 1,
        background: "transparent", border: "none", cursor: "pointer", padding: 0, minWidth: 0 }}>
      <span style={{ width: 46, height: 46, borderRadius: "50%",
        background: active ? "var(--accent)" : "var(--glass-fill-strong)",
        border: "1px solid " + (active ? "transparent" : "var(--glass-border)"),
        backdropFilter: "blur(var(--glass-blur))", WebkitBackdropFilter: "blur(var(--glass-blur))",
        color: active ? "var(--on-primary)" : "#fff",
        display: "inline-flex", alignItems: "center", justifyContent: "center" }}>
        <Icon name={icon} size={21} />
      </span>
      <span style={{ fontSize: 10.5, fontWeight: 600, lineHeight: 1, color: "rgba(255,255,255,0.82)",
        fontFamily: "var(--font-sans)", whiteSpace: "nowrap", maxWidth: 64, overflow: "hidden", textOverflow: "ellipsis" }}>
        {value || label}
      </span>
    </button>
  );

  return (
    <div style={{ position: "absolute", inset: 0, background: "#000", display: "flex", flexDirection: "column" }}>
      {/* video surface */}
      <div style={{ position: "absolute", inset: 0 }}>
        <img src={item.backdrop} alt="" style={{ width: "100%", height: "100%", objectFit: "cover", opacity: 0.85 }} />
        <div style={{ position: "absolute", inset: 0, background: "linear-gradient(180deg, rgba(0,0,0,0.55) 0%, rgba(0,0,0,0) 28%, rgba(0,0,0,0) 52%, rgba(0,0,0,0.82) 100%)" }} />
      </div>

      {/* top chrome */}
      <div style={{ position: "relative", display: "flex", alignItems: "flex-start", gap: 12, padding: "14px 16px" }}>
        <IconButton icon="chevron-down" variant="glass" label="Back" onClick={onBack} />
        <div style={{ flex: 1, minWidth: 0, paddingTop: 2 }}>
          <div className="m3-title-medium" style={{ color: "#fff", fontWeight: 600, whiteSpace: "nowrap", overflow: "hidden", textOverflow: "ellipsis" }}>{item.title}</div>
          {castActive ? (
            <div style={{ display: "flex", alignItems: "center", gap: 6, marginTop: 3, color: "rgba(255,255,255,0.85)", fontSize: 12 }}>
              <Icon name="cast" size={13} /> Playing on {castLabel}{castSplit ? " · split audio" : ""}
            </div>
          ) : null}
        </div>
        <Pill tone="accent">{qualLabel === "Auto" ? (item.tags[0] || "4K") : qualLabel}</Pill>
        <IconButton icon="cast" variant={castActive ? "filled" : "glass"} label="Cast" onClick={onOpenCast} />
      </div>

      {showVoice ? (
        <div style={{ position: "relative", display: "flex", justifyContent: "center", marginTop: 4 }}>
          <VoiceFeedback state={voiceState === "listening" ? "listening" : "answered"}
            text={voiceState === "listening" ? "“skip the intro”" : "Skipping intro."} />
        </div>
      ) : null}

      {/* center play/pause */}
      <div style={{ position: "relative", flex: 1, display: "flex", alignItems: "center", justifyContent: "center", gap: 28 }}>
        <IconButton icon="rotate-ccw" variant="glass" label="Back 10s" size="lg" />
        <button onClick={() => setPlaying(!playing)} aria-label="Play/Pause"
          style={{ width: 76, height: 76, borderRadius: "50%", cursor: "pointer",
            background: "var(--glass-fill-strong)", backdropFilter: "blur(var(--glass-blur))", WebkitBackdropFilter: "blur(var(--glass-blur))",
            border: "1px solid var(--glass-border)", color: "#fff", display: "inline-flex", alignItems: "center", justifyContent: "center" }}>
          <Icon name={playing ? "pause" : "play"} size={32} />
        </button>
        <IconButton icon="rotate-cw" variant="glass" label="Forward 10s" size="lg" />
      </div>

      {/* bottom controls */}
      <div style={{ position: "relative", padding: "0 16px calc(20px + env(safe-area-inset-bottom))", display: "flex", flexDirection: "column", gap: 16 }}>
        <div style={{ display: "flex", alignItems: "center", gap: 12 }}>
          <span className="m3-label-medium" style={{ color: "#fff", fontFamily: "var(--font-mono)" }}>02:38</span>
          <div style={{ flex: 1 }}><ProgressBar value={item.progress || 34} height={6} /></div>
          <span className="m3-label-medium" style={{ color: "rgba(255,255,255,0.7)", fontFamily: "var(--font-mono)" }}>{item.runtime}</span>
        </div>
        <div style={{ display: "flex", gap: 4, justifyContent: "space-between" }}>
          <Ctrl icon={subtitle === "off" ? "captions-off" : "captions"} label="Subtitles" value={subLabel} active={subtitle !== "off"} onClick={() => setSheet("subtitle")} />
          <Ctrl icon="audio-lines" label="Audio" value={audioLabel} onClick={() => setSheet("audio")} />
          <Ctrl icon="gauge" label="Quality" value={qualLabel} onClick={() => setSheet("quality")} />
          <Ctrl icon="users" label="SyncPlay" active={syncActive} onClick={onOpenSyncPlay} />
          <Ctrl icon="mic" label="Voice" active={voiceState === "listening"} onClick={onVoice} />
        </div>
      </div>

      {/* player-local sheets */}
      <window.QualitySheet open={sheet === "quality"} value={quality} onPick={setQuality} onClose={() => setSheet(null)} />
      <window.AudioSheet open={sheet === "audio"} value={audio} onPick={setAudio} onClose={() => setSheet(null)} />
      <window.SubtitleSheet open={sheet === "subtitle"} value={subtitle} onPick={setSubtitle} onClose={() => setSheet(null)} />
    </div>
  );
}
window.BeamPlayer = BeamPlayer;
