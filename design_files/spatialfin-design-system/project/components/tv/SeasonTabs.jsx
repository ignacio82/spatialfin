import React from "react";

/**
 * SeasonTabs — focusable season selector for a TV show. A horizontal row of
 * pill tabs; the active season is filled, focused tab shows the cyan ring.
 */
export function SeasonTabs({ seasons = [], active, onChange, style = {} }) {
  return (
    <div data-row style={{ display: "flex", gap: 10, overflowX: "auto", padding: "6px 2px", scrollbarWidth: "none", ...style }}>
      {seasons.map((s) => {
        const on = s === active;
        return (
          <button
            key={s}
            type="button"
            tabIndex={0}
            data-focusable=""
            onClick={() => onChange && onChange(s)}
            onMouseEnter={(e) => e.currentTarget.focus()}
            className="tv-season-tab"
            style={{
              flex: "0 0 auto", height: 44, padding: "0 22px", borderRadius: "var(--radius-full)",
              border: on ? "1px solid transparent" : "1px solid var(--border-strong)",
              background: on ? "var(--primary)" : "transparent",
              color: on ? "var(--on-primary)" : "var(--text-secondary)",
              fontFamily: "var(--font-sans)", fontSize: 15, fontWeight: 600, cursor: "pointer",
              transition: "background var(--duration-fast) var(--ease-standard)",
            }}
          >
            Season {s}
          </button>
        );
      })}
      <style>{`.tv-season-tab:focus{outline:3px solid var(--primary);outline-offset:3px}`}</style>
    </div>
  );
}
