import React from "react";
import { Icon } from "../core/Icon.jsx";

/**
 * ServerPickerSheet — the SpatialFin server switcher (mirrors
 * `ServerSelectionBottomSheet.kt`). Lists every connected Jellyfin server with
 * its address; the current one is checked. A "Manage servers" entry routes to
 * the full server-management screen.
 *
 * `servers` items: { id, name, address, currentUser? }
 */
export function ServerPickerSheet({
  open = true,
  servers = [],
  currentId,
  onPick,
  onManage,
  onDismiss,
  style = {},
}) {
  if (!open) return null;
  const Row = ({ icon, title, subtitle, checked, onClick, first }) => (
    <button
      type="button" tabIndex={0} data-focusable=""
      data-focus-first={first ? "" : undefined}
      onClick={onClick} onMouseEnter={(e) => e.currentTarget.focus()}
      className="srv-row"
      style={{
        display: "flex", alignItems: "center", gap: 18, width: "100%",
        padding: "14px 22px", border: "none", background: "transparent",
        color: "var(--text-primary)", textAlign: "left", cursor: "pointer",
      }}
    >
      <span style={{ width: 44, height: 44, borderRadius: "var(--radius-full)",
        background: "var(--accent-container)", color: "var(--on-accent-container)",
        display: "inline-flex", alignItems: "center", justifyContent: "center", flexShrink: 0 }}>
        <Icon name={icon} size={22} />
      </span>
      <span style={{ flex: 1, minWidth: 0 }}>
        <span style={{ display: "block", fontSize: 17, fontWeight: 600, whiteSpace: "nowrap", overflow: "hidden", textOverflow: "ellipsis" }}>{title}</span>
        {subtitle ? <span style={{ display: "block", fontSize: 14, color: "var(--text-secondary)", whiteSpace: "nowrap", overflow: "hidden", textOverflow: "ellipsis", fontFamily: "var(--font-mono)" }}>{subtitle}</span> : null}
      </span>
      {checked ? <Icon name="check" size={22} color="var(--primary)" /> : null}
    </button>
  );

  return (
    <div role="dialog" aria-label="Switch server"
      style={{ position: "fixed", inset: 0, background: "rgba(0,0,0,0.6)", zIndex: 80,
        display: "flex", alignItems: "center", justifyContent: "center", ...style }}
      onClick={onDismiss}
    >
      <div
        onClick={(e) => e.stopPropagation()}
        style={{
          width: "min(560px, 92vw)",
          background: "var(--surface-container)",
          borderRadius: "var(--radius-lg)", border: "1px solid var(--glass-border)",
          boxShadow: "var(--elevation-5)", overflow: "hidden",
          maxHeight: "82vh", display: "flex", flexDirection: "column",
        }}
      >
        <div style={{ padding: "20px 24px 12px", display: "flex", alignItems: "center", justifyContent: "space-between" }}>
          <span className="m3-title-large" style={{ fontWeight: 700 }}>Switch server</span>
          <button type="button" tabIndex={0} data-focusable="" onClick={onDismiss}
            onMouseEnter={(e) => e.currentTarget.focus()} aria-label="Close" className="srv-row"
            style={{ width: 40, height: 40, borderRadius: "var(--radius-full)", border: "none",
              background: "var(--surface-container-high)", color: "var(--text-primary)", cursor: "pointer",
              display: "inline-flex", alignItems: "center", justifyContent: "center" }}>
            <Icon name="x" size={20} />
          </button>
        </div>

        <div style={{ flex: 1, minHeight: 0, overflowY: "auto", paddingBottom: 12 }}>
          {servers.map((s, i) => (
            <Row key={s.id} icon="server" title={s.name} subtitle={s.address}
              checked={s.id === currentId} first={i === 0}
              onClick={() => { onPick && onPick(s.id); onDismiss && onDismiss(); }} />
          ))}
          <div style={{ height: 1, background: "var(--border-subtle)", margin: "8px 24px" }} />
          <Row icon="settings" title="Manage servers" subtitle="Add or remove servers"
            onClick={() => { onManage && onManage(); onDismiss && onDismiss(); }} />
        </div>
        <style>{`
          .srv-row{ transition: background var(--duration-fast), outline-color var(--duration-fast); }
          .srv-row:focus{ outline: 3px solid var(--primary); outline-offset: -3px; background: var(--surface-container-high); }
        `}</style>
      </div>
    </div>
  );
}
