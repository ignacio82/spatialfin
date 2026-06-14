import React from "react";
import { Icon } from "../core/Icon.jsx";

/**
 * TvKeyboard — the on-screen QWERTY-grid keyboard for 10-foot Search. Every key
 * is focusable (data-focusable) so the D-pad engine reaches it; Enter types the
 * letter. Space / Delete / Clear are action keys. Calls onChange with the new
 * string. Pair with a search field above it.
 */
const ROWS = [
  ["A", "B", "C", "D", "E", "F", "G"],
  ["H", "I", "J", "K", "L", "M", "N"],
  ["O", "P", "Q", "R", "S", "T", "U"],
  ["V", "W", "X", "Y", "Z", "0", "1"],
  ["2", "3", "4", "5", "6", "7", "8"],
];

export function TvKeyboard({ value = "", onChange, style = {} }) {
  const press = (k) => onChange && onChange(value + k);
  const del = () => onChange && onChange(value.slice(0, -1));
  const clear = () => onChange && onChange("");
  const space = () => onChange && onChange(value + " ");

  const Key = ({ children, onClick, wide, label, first }) => (
    <button
      type="button"
      tabIndex={0}
      data-focusable=""
      data-focus-first={first ? "" : undefined}
      onClick={onClick}
      onMouseEnter={(e) => e.currentTarget.focus()}
      aria-label={label}
      className="tv-key"
      style={{
        width: wide ? 132 : 56, height: 56, borderRadius: "var(--radius-sm)",
        border: "1px solid var(--border-subtle)", background: "var(--surface-container-high)",
        color: "var(--text-primary)", fontSize: 20, fontWeight: 600, cursor: "pointer",
        display: "inline-flex", alignItems: "center", justifyContent: "center", gap: 8,
      }}
    >
      {children}
    </button>
  );

  return (
    <div style={{ display: "inline-flex", flexDirection: "column", gap: 10, ...style }}>
      {ROWS.map((row, ri) => (
        <div key={ri} data-row style={{ display: "flex", gap: 10 }}>
          {row.map((k, ki) => (
            <Key key={k} onClick={() => press(k)} label={"Letter " + k} first={ri === 0 && ki === 0}>{k}</Key>
          ))}
        </div>
      ))}
      <div data-row style={{ display: "flex", gap: 10 }}>
        <Key onClick={space} wide label="Space"><Icon name="space" size={20} /> Space</Key>
        <Key onClick={del} label="Delete"><Icon name="delete" size={20} /></Key>
        <Key onClick={clear} wide label="Clear">Clear</Key>
      </div>
      <style>{`.tv-key{transition:transform var(--duration-fast) var(--ease-standard),background var(--duration-fast)}
        .tv-key:focus{outline:3px solid var(--primary);outline-offset:3px;background:var(--primary);color:var(--on-primary);transform:scale(1.06)}`}</style>
    </div>
  );
}
