import React from "react";
import { Icon } from "../core/Icon.jsx";

/**
 * TvTopBar — the 10-foot home top header. Mirrors the real
 * HomeHeader.kt: a left server-switcher tile (logo + server name), animated
 * error / loading-retry chips, then Search · Settings · Close action chips.
 * Every chip is focusable (data-focusable) for D-pad navigation. Use this in
 * place of TvNavRail on Home, Search, Library — surfaces that need the
 * server identity + global actions at the top of frame.
 */
export function TvTopBar({
  serverName = "Jellyfin",
  logoSrc = null,
  user = null,
  isLoading = false,
  isError = false,
  onServerClick,
  onErrorClick,
  onRetryClick,
  onSearchClick,
  onSettingsClick,
  onUserClick,
  onCloseClick,
  style = {},
}) {
  const chip = (props) => ({
    height: 64, padding: "0 22px", borderRadius: "var(--radius-full)",
    background: props.tone === "error" ? "var(--error-container)" : "var(--surface-container-high)",
    color: props.tone === "error" ? "var(--on-error-container)" : "var(--text-primary)",
    border: "none", cursor: "pointer",
    display: "inline-flex", alignItems: "center", gap: 10,
    fontFamily: "var(--font-sans)", fontSize: 16, fontWeight: 500,
  });

  const Spin = () => (
    <span aria-hidden style={{ width: 22, height: 22, borderRadius: "50%",
      border: "2.5px solid var(--text-disabled)", borderTopColor: "var(--primary)",
      animation: "sf-topbar-spin 0.9s linear infinite" }} />
  );

  return (
    <header
      style={{
        position: "absolute", top: 24, left: 56, right: 56, zIndex: 20,
        display: "flex", alignItems: "center", gap: 16, height: 64, ...style,
      }}
    >
      <button
        type="button" tabIndex={0} data-focusable="" data-focus-first=""
        onClick={onServerClick} onMouseEnter={(e) => e.currentTarget.focus()}
        className="tv-topbar-chip"
        style={{ ...chip({}), height: 64, padding: "0 22px 0 18px", maxWidth: 360, minWidth: 220, gap: 14 }}
      >
        {logoSrc ? (
          <img src={logoSrc} alt="" style={{ width: 30, height: 30, borderRadius: 7, flexShrink: 0 }}
            onError={(e) => { e.currentTarget.style.display = "none"; }} />
        ) : null}
        <span style={{ flex: 1, fontSize: 20, fontWeight: 600, whiteSpace: "nowrap", overflow: "hidden", textOverflow: "ellipsis", textAlign: "left" }}>
          {serverName}
        </span>
        <Icon name="chevron-down" size={20} color="var(--text-secondary)" />
      </button>
      <span style={{ flex: 1 }} />

      {isError ? (
        <button type="button" tabIndex={0} data-focusable="" onClick={onErrorClick}
          onMouseEnter={(e) => e.currentTarget.focus()} className="tv-topbar-chip"
          style={chip({ tone: "error" })}>
          <Icon name="alert-circle" size={20} /> Error
        </button>
      ) : null}

      {isLoading || isError ? (
        <button type="button" tabIndex={0} data-focusable="" onClick={onRetryClick} disabled={isLoading}
          onMouseEnter={(e) => e.currentTarget.focus()} className="tv-topbar-chip" style={chip({})}>
          {isLoading ? <><Spin /> Loading</> : <><Icon name="rotate-ccw" size={20} /> Retry</>}
        </button>
      ) : null}

      <button type="button" tabIndex={0} data-focusable="" onClick={onSearchClick}
        onMouseEnter={(e) => e.currentTarget.focus()} className="tv-topbar-chip" style={chip({})}>
        <Icon name="search" size={22} /> Search
      </button>
      {user ? (
        <button type="button" tabIndex={0} data-focusable="" onClick={onUserClick}
          onMouseEnter={(e) => e.currentTarget.focus()} className="tv-topbar-chip"
          aria-label={"Switch user \u2014 " + (user.name || "")}
          style={{ ...chip({}), padding: "0 22px 0 12px", gap: 12 }}>
          {user.avatar ? (
            <img src={user.avatar} alt="" style={{ width: 40, height: 40, borderRadius: "50%", objectFit: "cover", flexShrink: 0 }} />
          ) : (
            <span style={{ width: 40, height: 40, borderRadius: "50%", flexShrink: 0,
              background: user.color || "var(--surface-container-highest)",
              color: user.textColor || "#fff", fontSize: 15, fontWeight: 700,
              display: "inline-flex", alignItems: "center", justifyContent: "center" }}>
              {user.initials || (user.name || "?")[0]}
            </span>
          )}
          <span style={{ whiteSpace: "nowrap", overflow: "hidden", textOverflow: "ellipsis", maxWidth: 180 }}>{user.name}</span>
          <Icon name="chevron-down" size={18} color="var(--text-secondary)" />
        </button>
      ) : null}
      <button type="button" tabIndex={0} data-focusable="" onClick={onSettingsClick}
        onMouseEnter={(e) => e.currentTarget.focus()} className="tv-topbar-chip" style={chip({})}>
        <Icon name="settings" size={22} /> Settings
      </button>
      <button type="button" tabIndex={0} data-focusable="" onClick={onCloseClick}
        onMouseEnter={(e) => e.currentTarget.focus()} className="tv-topbar-chip" style={chip({})}>
        <Icon name="x" size={22} /> Close
      </button>

      <style>{`
        .tv-topbar-chip{ transition: outline-color var(--duration-fast), transform var(--duration-fast) var(--ease-standard), background var(--duration-fast); }
        .tv-topbar-chip:focus{ outline: 3px solid var(--primary); outline-offset: 3px; background: var(--primary); color: var(--on-primary); transform: scale(1.04); }
        @keyframes sf-topbar-spin { to { transform: rotate(360deg); } }
      `}</style>
    </header>
  );
}
