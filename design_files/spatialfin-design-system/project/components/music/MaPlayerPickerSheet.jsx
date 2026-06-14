import React from "react";
import { Icon } from "../core/Icon.jsx";

/**
 * MaPlayerPickerSheet — the SendSpin "Play on" picker. Mirrors
 * `MaPlayerPickerSheet.kt`: a modal sheet listing every visible Music
 * Assistant player; the currently-selected one is checked; tap commits.
 * Includes the "Auto (this device)" entry that clears the override and lets
 * SendSpin auto-detect the local player. When the selected player supports
 * grouping, the bottom section ("Also play on (in sync)") lets the user
 * build a multi-room sync group with checkboxes.
 *
 * `players` items: { id, name, provider, isPlaying?, supportsGrouping?,
 *   syncedToPlayerId?, groupMemberIds?, canGroupWith? }
 * `selectedId` is null for Auto.
 */
export function MaPlayerPickerSheet({
  open = true,
  players = [],
  selectedId = null,
  onPick,
  onToggleGroupMember,
  onDismiss,
  style = {},
}) {
  if (!open) return null;
  const selected = players.find((p) => p.id === selectedId) || null;
  const leader = selected && selected.supportsGrouping && !selected.syncedToPlayerId ? selected : null;
  const groupable = leader
    ? players.filter((p) =>
        p.id !== leader.id && p.supportsGrouping &&
        ((leader.canGroupWith || []).includes(p.id) ||
          (leader.canGroupWith || []).includes(p.provider) ||
          (p.canGroupWith || []).includes(leader.id) ||
          (p.canGroupWith || []).includes(leader.provider)))
    : [];

  const Row = ({ icon, title, subtitle, checked, onClick, trailing, first }) => (
    <button
      type="button" tabIndex={0} data-focusable=""
      data-focus-first={first ? "" : undefined}
      onClick={onClick} onMouseEnter={(e) => e.currentTarget.focus()}
      className="ma-pick-row"
      style={{
        display: "flex", alignItems: "center", gap: 18, width: "100%",
        padding: "14px 22px", border: "none", background: "transparent",
        color: "var(--text-primary)", textAlign: "left", cursor: "pointer",
      }}
    >
      <span style={{ width: 44, height: 44, borderRadius: "var(--radius-full)",
        background: "var(--surface-container-highest)", color: "var(--text-secondary)",
        display: "inline-flex", alignItems: "center", justifyContent: "center", flexShrink: 0 }}>
        <Icon name={icon} size={22} />
      </span>
      <span style={{ flex: 1, minWidth: 0 }}>
        <span style={{ display: "block", fontSize: 17, fontWeight: 600, whiteSpace: "nowrap", overflow: "hidden", textOverflow: "ellipsis" }}>{title}</span>
        {subtitle ? <span style={{ display: "block", fontSize: 14, color: "var(--text-secondary)", whiteSpace: "nowrap", overflow: "hidden", textOverflow: "ellipsis" }}>{subtitle}</span> : null}
      </span>
      {trailing || (checked ? <Icon name="check" size={22} color="var(--primary)" /> : null)}
    </button>
  );

  return (
    <div role="dialog" aria-label="Play on"
      style={{ position: "fixed", inset: 0, background: "rgba(0,0,0,0.6)", zIndex: 80,
        display: "flex", alignItems: "flex-end", justifyContent: "center", ...style }}
      onClick={onDismiss}
    >
      <div
        onClick={(e) => e.stopPropagation()}
        style={{
          width: "min(560px, 92vw)", marginBottom: 24,
          background: "var(--surface-container)",
          borderRadius: "var(--radius-lg)", border: "1px solid var(--glass-border)",
          boxShadow: "var(--elevation-5)", overflow: "hidden",
          maxHeight: "82vh", display: "flex", flexDirection: "column",
        }}
      >
        <div style={{ padding: "20px 24px 12px", display: "flex", alignItems: "center", justifyContent: "space-between" }}>
          <span className="m3-title-large" style={{ fontWeight: 700 }}>Play on</span>
          <button type="button" tabIndex={0} data-focusable="" onClick={onDismiss}
            onMouseEnter={(e) => e.currentTarget.focus()} aria-label="Close" className="ma-pick-row"
            style={{ width: 40, height: 40, borderRadius: "var(--radius-full)", border: "none",
              background: "var(--surface-container-high)", color: "var(--text-primary)", cursor: "pointer",
              display: "inline-flex", alignItems: "center", justifyContent: "center" }}>
            <Icon name="x" size={20} />
          </button>
        </div>

        <div style={{ flex: 1, minHeight: 0, overflowY: "auto", paddingBottom: 18 }}>
          <Row icon="speaker" title="Auto (this device)" subtitle="Auto-detected SendSpin wrapper"
            checked={selectedId == null} first onClick={() => { onPick && onPick(null); onDismiss && onDismiss(); }} />

          <div style={{ height: 1, background: "var(--border-subtle)", margin: "8px 24px" }} />

          {players.length === 0 ? (
            <div style={{ padding: 24, textAlign: "center", color: "var(--text-secondary)" }}>
              No other Music Assistant players available.
            </div>
          ) : players.map((p) => (
            <Row key={p.id} icon="speaker" title={p.name}
              subtitle={p.isPlaying ? "Now playing" : p.provider}
              checked={p.id === selectedId}
              onClick={() => { onPick && onPick(p.id); onDismiss && onDismiss(); }} />
          ))}

          {leader && groupable.length > 0 ? (
            <>
              <div style={{ padding: "16px 24px 4px", color: "var(--text-secondary)", fontSize: 13, fontWeight: 600, letterSpacing: 0.6, textTransform: "uppercase" }}>
                Also play on (in sync)
              </div>
              {groupable.map((p) => {
                const grouped = p.syncedToPlayerId === leader.id || (leader.groupMemberIds || []).includes(p.id);
                return (
                  <Row key={"g-" + p.id} icon="speaker" title={p.name}
                    subtitle={grouped ? "In sync" : "Tap to add"}
                    onClick={() => onToggleGroupMember && onToggleGroupMember(leader.id, p.id, grouped)}
                    trailing={
                      <span style={{ width: 22, height: 22, borderRadius: 5,
                        border: "2px solid " + (grouped ? "var(--primary)" : "var(--border-strong)"),
                        background: grouped ? "var(--primary)" : "transparent",
                        color: "var(--on-primary)",
                        display: "inline-flex", alignItems: "center", justifyContent: "center" }}>
                        {grouped ? <Icon name="check" size={14} /> : null}
                      </span>
                    } />
                );
              })}
            </>
          ) : null}
        </div>
        <style>{`
          .ma-pick-row{ transition: background var(--duration-fast), outline-color var(--duration-fast); }
          .ma-pick-row:focus{ outline: 3px solid var(--primary); outline-offset: -3px; background: var(--surface-container-high); }
        `}</style>
      </div>
    </div>
  );
}
