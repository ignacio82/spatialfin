/* @ds-bundle: {"format":3,"namespace":"SpatialFinDesignSystem_0d3fe7","components":[{"name":"Badge","sourcePath":"components/core/Badge.jsx"},{"name":"Button","sourcePath":"components/core/Button.jsx"},{"name":"Icon","sourcePath":"components/core/Icon.jsx"},{"name":"IconButton","sourcePath":"components/core/IconButton.jsx"},{"name":"Pill","sourcePath":"components/core/Pill.jsx"},{"name":"HeroBanner","sourcePath":"components/media/HeroBanner.jsx"},{"name":"PosterCard","sourcePath":"components/media/PosterCard.jsx"},{"name":"ProgressBar","sourcePath":"components/media/ProgressBar.jsx"},{"name":"SectionHeader","sourcePath":"components/media/SectionHeader.jsx"},{"name":"MaMiniPlayer","sourcePath":"components/music/MaMiniPlayer.jsx"},{"name":"MaPlayerPickerSheet","sourcePath":"components/music/MaPlayerPickerSheet.jsx"},{"name":"NavBar","sourcePath":"components/navigation/NavBar.jsx"},{"name":"ServerPickerSheet","sourcePath":"components/navigation/ServerPickerSheet.jsx"},{"name":"OnboardingStepper","sourcePath":"components/onboarding/OnboardingStepper.jsx"},{"name":"PointerHint","sourcePath":"components/onboarding/PointerHint.jsx"},{"name":"QrPairCode","sourcePath":"components/onboarding/QrPairCode.jsx"},{"name":"QuickConnectCode","sourcePath":"components/onboarding/QuickConnectCode.jsx"},{"name":"GlassPanel","sourcePath":"components/surfaces/GlassPanel.jsx"},{"name":"Orbiter","sourcePath":"components/surfaces/Orbiter.jsx"},{"name":"EpisodeCard","sourcePath":"components/tv/EpisodeCard.jsx"},{"name":"FocusCard","sourcePath":"components/tv/FocusCard.jsx"},{"name":"SeasonTabs","sourcePath":"components/tv/SeasonTabs.jsx"},{"name":"TvKeyboard","sourcePath":"components/tv/TvKeyboard.jsx"},{"name":"TvNavRail","sourcePath":"components/tv/TvNavRail.jsx"},{"name":"TvTopBar","sourcePath":"components/tv/TvTopBar.jsx"},{"name":"UpNextCard","sourcePath":"components/tv/UpNextCard.jsx"},{"name":"VoiceFab","sourcePath":"components/voice/VoiceFab.jsx"},{"name":"VoiceFeedback","sourcePath":"components/voice/VoiceFeedback.jsx"}],"sourceHashes":{"components/core/Badge.jsx":"494ec37c5b7a","components/core/Button.jsx":"dab5bae019f6","components/core/Icon.jsx":"06532334118f","components/core/IconButton.jsx":"9089b0a551a2","components/core/Pill.jsx":"e7ca4ae140bf","components/media/HeroBanner.jsx":"483d60c5b747","components/media/PosterCard.jsx":"c20bc8ff4f83","components/media/ProgressBar.jsx":"4faf4ee78c71","components/media/SectionHeader.jsx":"33c6a0bedecc","components/music/MaMiniPlayer.jsx":"eb84ffcfe907","components/music/MaPlayerPickerSheet.jsx":"6a8b306ac6eb","components/navigation/NavBar.jsx":"e203e1808171","components/navigation/ServerPickerSheet.jsx":"decdf6590727","components/onboarding/OnboardingStepper.jsx":"cd56a7339b54","components/onboarding/PointerHint.jsx":"034db1c0c6f2","components/onboarding/QrPairCode.jsx":"cfdeef9ca6d3","components/onboarding/QuickConnectCode.jsx":"6a8c1fd27580","components/surfaces/GlassPanel.jsx":"71d31c1506f0","components/surfaces/Orbiter.jsx":"1fb952657685","components/tv/EpisodeCard.jsx":"9d9b0039ae02","components/tv/FocusCard.jsx":"5791018fd50d","components/tv/SeasonTabs.jsx":"2dd4800b6c91","components/tv/TvKeyboard.jsx":"d6f86c8bc155","components/tv/TvNavRail.jsx":"18f0449000ac","components/tv/TvTopBar.jsx":"6cf640838b1e","components/tv/UpNextCard.jsx":"a1bd44fadb87","components/voice/VoiceFab.jsx":"c982dd5da8c1","components/voice/VoiceFeedback.jsx":"6228a0f1eb85","ui_kits/beam/BeamApp.jsx":"145a8f483ae9","ui_kits/beam/DetailScreen.jsx":"6b0373791693","ui_kits/beam/HomeScreen.jsx":"b55fc0a4cf80","ui_kits/beam/MoreScreens.jsx":"92828272f25b","ui_kits/beam/PlayerScreen.jsx":"54aac6562bf9","ui_kits/beam/PlayerSheets.jsx":"28d28bdd7736","ui_kits/beam/data.js":"9c9b124ab6f0","ui_kits/tv/MovieDetailScreen.jsx":"87d22a1c6b74","ui_kits/tv/ShowDetailScreen.jsx":"da22ead5e77f","ui_kits/tv/TvHomeScreen.jsx":"d21009d8996f","ui_kits/tv/TvLibraryScreen.jsx":"0d19b35b5ffa","ui_kits/tv/TvNowPlayingScreen.jsx":"16823c0ab424","ui_kits/tv/TvOverflowSheet.jsx":"d5bd1e29eb45","ui_kits/tv/TvPlayerScreen.jsx":"b03d7b46942d","ui_kits/tv/TvSearchScreen.jsx":"f9639cba34da","ui_kits/tv/TvShell.jsx":"559312a96153","ui_kits/tv/TvSourceBrowseScreen.jsx":"40a5cf162420","ui_kits/tv/data.js":"8210e69d7239","ui_kits/tv/tvFocus.js":"14dbb4f11299","ui_kits/xr/XrApp.jsx":"ce90fe6dd4c7"},"inlinedExternals":[],"unexposedExports":[]} */

(() => {

const __ds_ns = (window.SpatialFinDesignSystem_0d3fe7 = window.SpatialFinDesignSystem_0d3fe7 || {});

const __ds_scope = {};

(__ds_ns.__errors = __ds_ns.__errors || []);

// components/core/Icon.jsx
try { (() => {
/**
 * Custom glyphs rendered as inline SVG instead of going through Lucide.
 * `cast` is the canonical SpatialFin cast mark — lifted verbatim from the
 * app's `core/src/main/res/drawable/ic_cast.xml` drawable (TV containing
 * a radiating wave with a corner dot) so Beam, TV and XR share one
 * identical glyph. `currentColor`-tinted.
 */
const CUSTOM_ICONS = {
  cast: {
    viewBox: "0 0 838.06427 658.34259",
    // The source drawable's group translate is baked into a wrapping <g>.
    transform: "translate(-89.28497 -173.86796)",
    paths: ["m 285,173.8941 c 183.828,0.006 367.156,0.005 550.484,0.0248 45.296,0.005 82.569,31.162 90.485,75.721 0.898,5.056 1.381,10.198 1.38,15.398 -0.046,155.495 -0.003,310.991 -0.041,466.486 -0.011,45.833 -36,84.813 -81.701,88.933 -5.162,0.465 -10.298,0.657 -15.459,0.657 -87.664,0.005 -175.328,0.012 -262.992,-0.009 -15.813,-0.004 -25.125,-11.614 -20.431,-25.338 2.685,-7.848 9.164,-12.156 18.632,-12.164 40.16,-0.031 80.321,-0.012 120.481,-0.016 49.324,-0.005 98.649,-0.009 147.973,-0.026 12.094,-0.004 23.523,-2.44 33.491,-9.711 15.379,-11.217 22.632,-26.446 22.825,-45.436 1.041,-102.657 0.19,-205.316 0.432,-307.974 0.12,-50.998 0.064,-101.997 0.052,-152.995 -0.005,-23.586 -12.423,-43.165 -32.673,-51.74 -6.235,-2.64 -12.721,-3.682 -19.481,-3.681 -212.827,0.04 -425.654,0.17 -638.481,-0.09 -30.261,-0.037 -52.999,25.72 -52.859,52.073 0.153,28.831 0.088,57.664 -0.012,86.496 -0.043,12.492 -8.142,20.344 -20.032,19.809 -7.568,-0.34 -14.499,-5.895 -16.249,-13.298 -0.607,-2.569 -0.898,-5.272 -0.902,-7.915 -0.05,-27.499 -0.433,-55.007 0.11,-82.495 0.627,-31.71 13.012,-57.839 39.344,-76.446 15.566,-11 33.153,-16.118 52.134,-16.214 27.663,-0.139 55.327,-0.045 83.491,-0.05 z", "m 754.138,296.825 c 25.549,-8.311 49.772,7.529 51.974,33.604 1.693,20.048 -15.997,39.716 -36.442,39.698 -6.382,-0.006 -10.385,1.945 -14.11,7.005 -16.015,21.758 -26.106,45.864 -30.452,72.51 -0.622,3.816 1.382,4.973 3.693,6.663 34.097,24.931 56.822,57.579 66.946,98.771 2.013,8.189 2.071,16.234 -2.156,23.774 -5.04,8.991 -12.985,13.602 -23.173,14.34 -3.317,0.24 -6.663,0.108 -9.996,0.109 -90.656,0.005 -181.312,-0.009 -271.968,0.035 -5.935,0.003 -11.64,-0.747 -16.903,-3.589 -11.856,-6.402 -16.82,-19.194 -13.295,-34.444 9.548,-41.313 32.711,-73.498 66.531,-98.347 3.932,-2.889 5.136,-5.368 4.114,-10.256 -5.575,-26.67 -15.606,-51.204 -32.901,-72.539 -2.337,-2.883 -4.785,-3.984 -8.493,-3.936 -22.184,0.29 -39.048,-15.007 -39.471,-37.087 -0.365,-19.054 12.404,-35.471 33.166,-38.244 20.512,-2.74 38.199,9.803 42.455,29.49 1.834,8.482 1.075,16.939 -3.041,24.781 -1.402,2.672 -1.267,4.879 0.291,7.43 15.131,24.763 26.896,50.892 32.052,79.677 0.168,0.939 0.577,1.835 0.988,3.102 8.492,-3.133 16.627,-6.452 25.084,-8.86 38.875,-11.069 76.996,-8.776 114.43,6.255 6.707,2.693 6.77,2.587 8.293,-4.503 5.438,-25.314 15.095,-48.968 28.659,-70.98 3.146,-5.105 4.266,-9.238 1.733,-15.446 -7.928,-19.426 1.264,-39.243 21.992,-49.013 z", "M 382.477,591.495 C 339.713,533.544 283.662,496.302 213.86,479.84 c -26.29,-6.2 -53.003,-7.391 -79.87,-6.94 -2.979,0.05 -6.008,-0.137 -8.943,-0.631 -8.902,-1.5 -15.544,-9.668 -15.206,-18.425 0.357,-9.235 7.851,-17.487 16.962,-18.095 69.887,-4.664 135.084,10.454 194.58,47.692 61.675,38.601 105.913,92.156 133.31,159.508 13.12,32.254 20.89,65.818 23.826,100.579 1.743,20.631 2.136,41.207 0.774,61.84 -0.615,9.323 -9.222,16.891 -19.12,16.864 -9.933,-0.027 -18.2,-7.916 -18.377,-18.03 -0.101,-5.811 0.714,-11.631 0.81,-17.452 1.01,-61.137 -12.616,-118.532 -44.067,-171.315 -4.86,-8.157 -9.971,-16.168 -16.063,-23.94 z", "m 155.839,579.275 c -8.977,-0.097 -17.477,-0.052 -25.968,-0.292 -13.607,-0.385 -22.474,-11.52 -19.104,-23.78 2.155,-7.842 9.273,-13.211 18.46,-13.381 58.876,-1.09 111.717,15.862 157.05,53.899 42.3,35.492 69.007,80.528 80.292,134.678 5.097,24.455 6.407,49.11 4.703,73.97 -0.671,9.793 -9.549,17.586 -18.946,16.785 -10.865,-0.926 -18.034,-9.831 -17.201,-21.182 2.105,-28.68 -0.054,-57.063 -9.542,-84.253 -25.12,-71.983 -74.231,-117.73 -149.483,-133.955 -6.47,-1.395 -13.18,-1.676 -20.26,-2.489 z", "m 172.485,653.341 c 48.574,16.437 78.212,49.599 89.772,98.955 4.102,17.512 4.075,35.34 2.047,53.113 -1.121,9.823 -9.574,16.544 -18.678,15.733 -10.021,-0.892 -16.794,-8.53 -16.965,-18.78 -0.175,-10.472 0.793,-20.893 0.192,-31.417 -2.315,-40.545 -37.845,-79.905 -78.104,-85.417 -7.536,-1.032 -15.288,-0.378 -22.89,-1.061 -10.93,-0.982 -18.249,-9.098 -17.816,-19.087 0.409,-9.43 8.658,-17.166 19.155,-17.558 14.575,-0.543 28.889,1.318 43.287,5.519 z", "m 101.033,814.922 c -24.197,-30.972 -9.192,-73.703 28.328,-81.618 25.509,-5.381 51.102,11.103 57.386,36.962 7.923,32.602 -16.481,62.997 -50.011,61.916 -14.14,-0.456 -26.028,-6.346 -35.703,-17.26 m 55.668,-29.927 c 0.04,-0.829 0.118,-1.659 0.113,-2.488 -0.064,-10.512 -8.444,-19.162 -18.421,-19.026 -9.921,0.135 -18.275,9.13 -18.09,19.476 0.152,8.529 7.183,16.622 15.381,17.705 10.082,1.333 17.918,-4.218 21.017,-15.667 z"]
  }
};

/**
 * Icon — Lucide line icon, rendered as inline SVG. Lucide is SpatialFin's icon
 * system (matches the app's vector drawables: 24px grid, stroke-2, round caps).
 * Loads the Lucide UMD from CDN on first use; colour follows `currentColor`.
 *
 * A small set of glyphs in CUSTOM_ICONS bypass Lucide and render the app's
 * actual drawable instead — `cast` is the canonical SpatialFin cast mark.
 *
 * Requires the Lucide script. Card/kit hosts should include:
 *   <script src="https://unpkg.com/lucide@latest/dist/umd/lucide.min.js"></script>
 * If absent, this component injects it automatically.
 */
function ensureLucide(cb) {
  if (window.lucide) return cb();
  let s = document.getElementById("__lucide_cdn");
  if (!s) {
    s = document.createElement("script");
    s.id = "__lucide_cdn";
    s.src = "https://unpkg.com/lucide@latest/dist/umd/lucide.min.js";
    document.head.appendChild(s);
  }
  s.addEventListener("load", cb);
}
function Icon({
  name,
  size = 20,
  strokeWidth = 2,
  color = "currentColor",
  style = {}
}) {
  const custom = CUSTOM_ICONS[name];
  const ref = React.useRef(null);
  React.useEffect(() => {
    if (custom) return; // hand-authored SVG below, no Lucide needed
    const el = ref.current;
    if (!el) return;
    const render = () => {
      if (!window.lucide || !el.isConnected) return;
      el.innerHTML = "";
      const i = document.createElement("i");
      i.setAttribute("data-lucide", name);
      el.appendChild(i);
      try {
        window.lucide.createIcons({
          attrs: {
            width: size,
            height: size,
            "stroke-width": strokeWidth
          }
        });
      } catch (e) {/* noop */}
    };
    ensureLucide(render);
  }, [name, size, strokeWidth, custom]);
  if (custom) {
    const inner = custom.paths.map((d, i) => /*#__PURE__*/React.createElement("path", {
      key: i,
      d: d
    }));
    return /*#__PURE__*/React.createElement("span", {
      "aria-hidden": "true",
      style: {
        display: "inline-flex",
        width: size,
        height: size,
        color,
        flexShrink: 0,
        ...style
      }
    }, /*#__PURE__*/React.createElement("svg", {
      width: size,
      height: size,
      viewBox: custom.viewBox || "0 0 24 24",
      fill: "currentColor",
      xmlns: "http://www.w3.org/2000/svg"
    }, custom.transform ? /*#__PURE__*/React.createElement("g", {
      transform: custom.transform
    }, inner) : inner));
  }
  return /*#__PURE__*/React.createElement("span", {
    ref: ref,
    "aria-hidden": "true",
    style: {
      display: "inline-flex",
      width: size,
      height: size,
      color,
      flexShrink: 0,
      ...style
    }
  });
}
Object.assign(__ds_scope, { Icon });
})(); } catch (e) { __ds_ns.__errors.push({ path: "components/core/Icon.jsx", error: String((e && e.message) || e) }); }

// components/core/Badge.jsx
try { (() => {
function _extends() { return _extends = Object.assign ? Object.assign.bind() : function (n) { for (var e = 1; e < arguments.length; e++) { var t = arguments[e]; for (var r in t) ({}).hasOwnProperty.call(t, r) && (n[r] = t[r]); } return n; }, _extends.apply(null, arguments); }
/**
 * Badge — small status marker overlaid on media (downloaded, downloading, 4K,
 * watched count). Filled circle for icon-only, or a labelled tonal capsule.
 */
function Badge({
  children,
  tone = "accent",
  icon = null,
  dot = false,
  style = {},
  ...rest
}) {
  const tones = {
    accent: {
      background: "var(--accent)",
      color: "var(--on-primary)"
    },
    neutral: {
      background: "var(--surface-container-highest)",
      color: "var(--text-primary)"
    },
    error: {
      background: "var(--error)",
      color: "var(--on-error)"
    },
    success: {
      background: "var(--tertiary-container)",
      color: "var(--on-tertiary-container)"
    },
    overlay: {
      background: "rgba(0,0,0,0.62)",
      color: "#fff"
    }
  };
  const t = tones[tone] || tones.accent;
  if (dot) {
    return /*#__PURE__*/React.createElement("span", _extends({
      style: {
        width: 10,
        height: 10,
        borderRadius: "50%",
        display: "inline-block",
        ...t,
        ...style
      }
    }, rest));
  }
  const iconOnly = icon && !children;
  return /*#__PURE__*/React.createElement("span", _extends({
    style: {
      display: "inline-flex",
      alignItems: "center",
      justifyContent: "center",
      gap: 4,
      minWidth: iconOnly ? 22 : undefined,
      height: 22,
      padding: iconOnly ? 0 : "0 8px",
      borderRadius: iconOnly ? "var(--radius-full)" : "var(--radius-sm)",
      fontFamily: "var(--font-sans)",
      fontSize: 11,
      fontWeight: 600,
      letterSpacing: 0.3,
      lineHeight: 1,
      ...t,
      ...style
    }
  }, rest), icon ? /*#__PURE__*/React.createElement(__ds_scope.Icon, {
    name: icon,
    size: 13
  }) : null, children);
}
Object.assign(__ds_scope, { Badge });
})(); } catch (e) { __ds_ns.__errors.push({ path: "components/core/Badge.jsx", error: String((e && e.message) || e) }); }

// components/core/Button.jsx
try { (() => {
function _extends() { return _extends = Object.assign ? Object.assign.bind() : function (n) { for (var e = 1; e < arguments.length; e++) { var t = arguments[e]; for (var r in t) ({}).hasOwnProperty.call(t, r) && (n[r] = t[r]); } return n; }, _extends.apply(null, arguments); }
/**
 * SpatialFin Button — Material 3 action used across XR, Beam and TV.
 * Pill-shaped (full radius) like the player action buttons and the phone
 * hero "Play". Variants map to M3 emphasis: filled > tonal > outlined > text.
 */
function Button({
  children,
  variant = "filled",
  size = "md",
  icon = null,
  fullWidth = false,
  disabled = false,
  onClick,
  style = {},
  ...rest
}) {
  const sizes = {
    sm: {
      h: 36,
      px: 16,
      fs: 13,
      gap: 6,
      ic: 16
    },
    md: {
      h: 44,
      px: 22,
      fs: 14,
      gap: 8,
      ic: 18
    },
    lg: {
      h: 56,
      px: 28,
      fs: 16,
      gap: 10,
      ic: 22
    }
  };
  const s = sizes[size] || sizes.md;
  const variants = {
    filled: {
      background: "var(--accent)",
      color: "var(--on-primary)",
      border: "1px solid transparent"
    },
    tonal: {
      background: "var(--accent-container)",
      color: "var(--on-accent-container)",
      border: "1px solid transparent"
    },
    outlined: {
      background: "transparent",
      color: "var(--accent)",
      border: "1px solid var(--border-strong)"
    },
    text: {
      background: "transparent",
      color: "var(--accent)",
      border: "1px solid transparent"
    },
    glass: {
      background: "var(--glass-fill-strong)",
      color: "var(--text-primary)",
      border: "1px solid var(--glass-border)",
      backdropFilter: "blur(var(--glass-blur))",
      WebkitBackdropFilter: "blur(var(--glass-blur))"
    }
  };
  const v = variants[variant] || variants.filled;
  return /*#__PURE__*/React.createElement("button", _extends({
    type: "button",
    disabled: disabled,
    onClick: onClick,
    style: {
      display: "inline-flex",
      alignItems: "center",
      justifyContent: "center",
      gap: s.gap,
      height: s.h,
      padding: `0 ${s.px}px`,
      width: fullWidth ? "100%" : "auto",
      fontFamily: "var(--font-sans)",
      fontSize: s.fs,
      fontWeight: 500,
      lineHeight: 1,
      letterSpacing: 0.1,
      borderRadius: "var(--radius-full)",
      cursor: disabled ? "not-allowed" : "pointer",
      opacity: disabled ? 0.38 : 1,
      transition: "background var(--duration-fast) var(--ease-standard), transform var(--duration-fast) var(--ease-standard), filter var(--duration-fast) var(--ease-standard)",
      whiteSpace: "nowrap",
      ...v,
      ...style
    },
    onMouseDown: e => {
      if (!disabled) e.currentTarget.style.transform = "scale(0.97)";
    },
    onMouseUp: e => {
      e.currentTarget.style.transform = "scale(1)";
    },
    onMouseLeave: e => {
      e.currentTarget.style.transform = "scale(1)";
      e.currentTarget.style.filter = "none";
    },
    onMouseEnter: e => {
      if (!disabled) e.currentTarget.style.filter = "brightness(1.08)";
    }
  }, rest), icon ? /*#__PURE__*/React.createElement(__ds_scope.Icon, {
    name: icon,
    size: s.ic
  }) : null, children);
}
Object.assign(__ds_scope, { Button });
})(); } catch (e) { __ds_ns.__errors.push({ path: "components/core/Button.jsx", error: String((e && e.message) || e) }); }

// components/core/IconButton.jsx
try { (() => {
function _extends() { return _extends = Object.assign ? Object.assign.bind() : function (n) { for (var e = 1; e < arguments.length; e++) { var t = arguments[e]; for (var r in t) ({}).hasOwnProperty.call(t, r) && (n[r] = t[r]); } return n; }, _extends.apply(null, arguments); }
/**
 * IconButton — a circular, icon-only control. Used for orbiter controls (XR),
 * top-bar actions (Beam), and player chrome. `tonal` is the default emphasis;
 * `glass` matches the floating XR orbiter look.
 */
function IconButton({
  icon,
  variant = "tonal",
  size = "md",
  disabled = false,
  label,
  onClick,
  style = {},
  ...rest
}) {
  const dims = {
    sm: 36,
    md: 44,
    lg: 56
  };
  const ic = {
    sm: 18,
    md: 22,
    lg: 26
  };
  const d = dims[size] || dims.md;
  const variants = {
    tonal: {
      background: "var(--surface-container-high)",
      color: "var(--text-primary)",
      border: "1px solid transparent"
    },
    filled: {
      background: "var(--accent)",
      color: "var(--on-primary)",
      border: "1px solid transparent"
    },
    ghost: {
      background: "transparent",
      color: "var(--text-secondary)",
      border: "1px solid transparent"
    },
    glass: {
      background: "var(--glass-fill-strong)",
      color: "var(--text-primary)",
      border: "1px solid var(--glass-border)",
      backdropFilter: "blur(var(--glass-blur))",
      WebkitBackdropFilter: "blur(var(--glass-blur))"
    },
    outlined: {
      background: "transparent",
      color: "var(--text-primary)",
      border: "1px solid var(--border-strong)"
    }
  };
  const v = variants[variant] || variants.tonal;
  return /*#__PURE__*/React.createElement("button", _extends({
    type: "button",
    "aria-label": label,
    title: label,
    disabled: disabled,
    onClick: onClick,
    style: {
      display: "inline-flex",
      alignItems: "center",
      justifyContent: "center",
      width: d,
      height: d,
      borderRadius: "var(--radius-full)",
      cursor: disabled ? "not-allowed" : "pointer",
      opacity: disabled ? 0.38 : 1,
      transition: "background var(--duration-fast) var(--ease-standard), transform var(--duration-fast) var(--ease-standard)",
      ...v,
      ...style
    },
    onMouseEnter: e => {
      if (!disabled) e.currentTarget.style.filter = "brightness(1.12)";
    },
    onMouseLeave: e => {
      e.currentTarget.style.filter = "none";
      e.currentTarget.style.transform = "scale(1)";
    },
    onMouseDown: e => {
      if (!disabled) e.currentTarget.style.transform = "scale(0.94)";
    },
    onMouseUp: e => {
      e.currentTarget.style.transform = "scale(1)";
    }
  }, rest), /*#__PURE__*/React.createElement(__ds_scope.Icon, {
    name: icon,
    size: ic[size] || ic.md
  }));
}
Object.assign(__ds_scope, { IconButton });
})(); } catch (e) { __ds_ns.__errors.push({ path: "components/core/IconButton.jsx", error: String((e && e.message) || e) }); }

// components/core/Pill.jsx
try { (() => {
function _extends() { return _extends = Object.assign ? Object.assign.bind() : function (n) { for (var e = 1; e < arguments.length; e++) { var t = arguments[e]; for (var r in t) ({}).hasOwnProperty.call(t, r) && (n[r] = t[r]); } return n; }, _extends.apply(null, arguments); }
/**
 * Pill — non-focusable rounded chip for static metadata (year, runtime, rating,
 * codec, resolution). Mirrors MetadataPill.kt (full radius, translucent fill).
 * `tone` picks the fill; pass `icon` for leading glyphs (e.g. a star on rating).
 */
function Pill({
  children,
  tone = "chip",
  icon = null,
  style = {},
  ...rest
}) {
  const tones = {
    chip: {
      background: "var(--chip-fill)",
      color: "var(--text-primary)"
    },
    neutral: {
      background: "var(--surface-container-high)",
      color: "var(--text-secondary)"
    },
    accent: {
      background: "var(--accent-container)",
      color: "var(--on-accent-container)"
    },
    outline: {
      background: "transparent",
      color: "var(--text-secondary)",
      boxShadow: "inset 0 0 0 1px var(--border-subtle)"
    },
    rating: {
      background: "var(--chip-fill)",
      color: "var(--rating-star)"
    }
  };
  const t = tones[tone] || tones.chip;
  return /*#__PURE__*/React.createElement("span", _extends({
    style: {
      display: "inline-flex",
      alignItems: "center",
      gap: 5,
      padding: "5px 10px",
      borderRadius: "var(--radius-full)",
      fontFamily: "var(--font-sans)",
      fontSize: 11,
      fontWeight: 500,
      letterSpacing: 0.4,
      lineHeight: 1,
      whiteSpace: "nowrap",
      ...t,
      ...style
    }
  }, rest), icon ? /*#__PURE__*/React.createElement(__ds_scope.Icon, {
    name: icon,
    size: 13
  }) : null, children);
}
Object.assign(__ds_scope, { Pill });
})(); } catch (e) { __ds_ns.__errors.push({ path: "components/core/Pill.jsx", error: String((e && e.message) || e) }); }

// components/media/HeroBanner.jsx
try { (() => {
/**
 * HeroBanner — the featured spotlight at the top of Home (phone) / a detail
 * banner (XR). Full-bleed backdrop with a bottom protection scrim, title,
 * metadata pills and primary actions. Content-first: chrome recedes.
 */
function HeroBanner({
  title,
  kind = "Movie",
  backdrop,
  meta = [],
  height = 320,
  actions = null,
  rounded = true,
  style = {}
}) {
  return /*#__PURE__*/React.createElement("div", {
    style: {
      position: "relative",
      width: "100%",
      height,
      overflow: "hidden",
      borderRadius: rounded ? "var(--radius-lg)" : 0,
      background: "var(--surface-container-low)",
      ...style
    }
  }, backdrop ? /*#__PURE__*/React.createElement("img", {
    src: backdrop,
    alt: title,
    style: {
      position: "absolute",
      inset: 0,
      width: "100%",
      height: "100%",
      objectFit: "cover"
    }
  }) : null, /*#__PURE__*/React.createElement("div", {
    style: {
      position: "absolute",
      inset: 0,
      background: "var(--scrim-gradient)"
    }
  }), /*#__PURE__*/React.createElement("div", {
    style: {
      position: "absolute",
      left: 0,
      right: 0,
      bottom: 0,
      padding: "var(--space-lg)",
      display: "flex",
      flexDirection: "column",
      gap: 12
    }
  }, kind ? /*#__PURE__*/React.createElement("div", {
    className: "m3-label-large",
    style: {
      color: "rgba(255,255,255,0.82)",
      letterSpacing: 0.6
    }
  }, kind.toUpperCase()) : null, /*#__PURE__*/React.createElement("div", {
    className: "m3-display-small",
    style: {
      color: "#fff",
      fontWeight: 700,
      textShadow: "0 2px 12px rgba(0,0,0,0.5)"
    }
  }, title), meta.length ? /*#__PURE__*/React.createElement("div", {
    style: {
      display: "flex",
      gap: 8,
      flexWrap: "wrap"
    }
  }, meta.map((m, i) => /*#__PURE__*/React.createElement(__ds_scope.Pill, {
    key: i,
    tone: "chip"
  }, m))) : null, /*#__PURE__*/React.createElement("div", {
    style: {
      display: "flex",
      gap: 12,
      marginTop: 4
    }
  }, actions || /*#__PURE__*/React.createElement(React.Fragment, null, /*#__PURE__*/React.createElement(__ds_scope.Button, {
    variant: "filled",
    icon: "play",
    size: "lg"
  }, "Play"), /*#__PURE__*/React.createElement(__ds_scope.Button, {
    variant: "glass",
    size: "lg"
  }, "Details")))));
}
Object.assign(__ds_scope, { HeroBanner });
})(); } catch (e) { __ds_ns.__errors.push({ path: "components/media/HeroBanner.jsx", error: String((e && e.message) || e) }); }

// components/media/ProgressBar.jsx
try { (() => {
/**
 * ProgressBar — thin watched-progress indicator shown under poster art and on
 * the player scrubber. Accent fill on a translucent track.
 */
function ProgressBar({
  value = 0,
  height = 4,
  style = {}
}) {
  const pct = Math.max(0, Math.min(100, value));
  return /*#__PURE__*/React.createElement("div", {
    style: {
      width: "100%",
      height,
      borderRadius: "var(--radius-full)",
      background: "rgba(255,255,255,0.22)",
      overflow: "hidden",
      ...style
    }
  }, /*#__PURE__*/React.createElement("div", {
    style: {
      width: `${pct}%`,
      height: "100%",
      background: "var(--accent)",
      borderRadius: "var(--radius-full)"
    }
  }));
}
Object.assign(__ds_scope, { ProgressBar });
})(); } catch (e) { __ds_ns.__errors.push({ path: "components/media/ProgressBar.jsx", error: String((e && e.message) || e) }); }

// components/media/PosterCard.jsx
try { (() => {
/**
 * PosterCard — the portrait media tile used in shelves & grids (phone, XR).
 * Rounded poster art with an optional dark metadata footer, watched-progress
 * bar, and corner status badge. Hover lifts and reveals the accent outline.
 */
function PosterCard({
  title,
  subtitle,
  poster,
  progress = null,
  badge = null,
  width = 150,
  showFooter = true,
  onClick,
  style = {}
}) {
  const [hover, setHover] = React.useState(false);
  return /*#__PURE__*/React.createElement("div", {
    onClick: onClick,
    onMouseEnter: () => setHover(true),
    onMouseLeave: () => setHover(false),
    style: {
      width,
      cursor: onClick ? "pointer" : "default",
      borderRadius: "var(--radius-md)",
      overflow: "hidden",
      background: "var(--surface-card)",
      transition: "transform var(--duration-medium) var(--ease-standard), box-shadow var(--duration-medium) var(--ease-standard)",
      transform: hover ? "translateY(-4px)" : "none",
      boxShadow: hover ? "var(--elevation-4)" : "var(--elevation-1)",
      outline: hover ? "2px solid var(--accent)" : "2px solid transparent",
      ...style
    }
  }, /*#__PURE__*/React.createElement("div", {
    style: {
      position: "relative",
      width: "100%",
      aspectRatio: "2 / 3",
      background: "var(--surface-container-low)"
    }
  }, poster ? /*#__PURE__*/React.createElement("img", {
    src: poster,
    alt: title,
    style: {
      width: "100%",
      height: "100%",
      objectFit: "cover",
      display: "block"
    }
  }) : null, badge ? /*#__PURE__*/React.createElement("div", {
    style: {
      position: "absolute",
      top: 8,
      right: 8
    }
  }, badge) : null, progress != null ? /*#__PURE__*/React.createElement("div", {
    style: {
      position: "absolute",
      left: 8,
      right: 8,
      bottom: 8
    }
  }, /*#__PURE__*/React.createElement(__ds_scope.ProgressBar, {
    value: progress
  })) : null), showFooter ? /*#__PURE__*/React.createElement("div", {
    style: {
      padding: "10px 12px 12px"
    }
  }, /*#__PURE__*/React.createElement("div", {
    className: "m3-title-small",
    style: {
      color: "var(--text-primary)",
      display: "-webkit-box",
      WebkitLineClamp: 2,
      WebkitBoxOrient: "vertical",
      overflow: "hidden"
    }
  }, title), subtitle ? /*#__PURE__*/React.createElement("div", {
    className: "m3-body-small",
    style: {
      color: "var(--text-secondary)",
      marginTop: 2,
      whiteSpace: "nowrap",
      overflow: "hidden",
      textOverflow: "ellipsis"
    }
  }, subtitle) : null) : null);
}
Object.assign(__ds_scope, { PosterCard });
})(); } catch (e) { __ds_ns.__errors.push({ path: "components/media/PosterCard.jsx", error: String((e && e.message) || e) }); }

// components/media/SectionHeader.jsx
try { (() => {
/**
 * SectionHeader — a shelf/row title with the signature leading accent bar
 * (see phone Home: "Suggestions", "Continue Watching"). Optional trailing
 * action (e.g. "See all").
 */
function SectionHeader({
  title,
  action = null,
  size = "large",
  style = {}
}) {
  const cls = size === "large" ? "m3-headline-small" : "m3-title-large";
  return /*#__PURE__*/React.createElement("div", {
    style: {
      display: "flex",
      alignItems: "center",
      gap: 12,
      ...style
    }
  }, /*#__PURE__*/React.createElement("span", {
    style: {
      width: 4,
      height: size === "large" ? 26 : 20,
      borderRadius: "var(--radius-full)",
      background: "var(--accent)",
      flexShrink: 0
    }
  }), /*#__PURE__*/React.createElement("span", {
    className: cls,
    style: {
      color: "var(--text-primary)",
      fontWeight: 700
    }
  }, title), /*#__PURE__*/React.createElement("span", {
    style: {
      flex: 1
    }
  }), action);
}
Object.assign(__ds_scope, { SectionHeader });
})(); } catch (e) { __ds_ns.__errors.push({ path: "components/media/SectionHeader.jsx", error: String((e && e.message) || e) }); }

// components/music/MaMiniPlayer.jsx
try { (() => {
/**
 * MaMiniPlayer — the persistent Music Assistant playback bar shown on Beam, XR
 * and TV. Three states (mirrors `MaMiniPlayer.kt`):
 *   - **preparing** — user just tapped play; MA hasn't echoed yet. Indeterminate
 *     progress strip at top + "Preparing audio…" subtitle. Transport disabled.
 *   - **playing / paused** — live title + artist (or "On <speaker>"), transport
 *     enabled.
 *   - **idle (no track)** — hide entirely (caller decides; pass `track={null}`).
 *
 * Pass `onExpand` for tap-to-open Now Playing. `onStop` dismisses the bar.
 */
function MaMiniPlayer({
  track,
  phase = "playing",
  // preparing | playing | paused | idle
  selectedPlayer,
  onPlayPause,
  onNext,
  onStop,
  onExpand,
  style = {}
}) {
  if (!track) return null;
  const preparing = phase === "preparing";
  const playing = phase === "playing";
  const subtitle = preparing ? "Preparing audio…" : track.artist || (selectedPlayer ? "On " + selectedPlayer : null);
  const btn = (icon, label, onClick, opts = {}) => /*#__PURE__*/React.createElement("button", {
    type: "button",
    tabIndex: 0,
    "data-focusable": "",
    onClick: e => {
      e.stopPropagation();
      onClick && onClick();
    },
    onMouseEnter: e => e.currentTarget.focus(),
    disabled: opts.disabled,
    "aria-label": label,
    title: label,
    className: "ma-mini-btn",
    style: {
      width: 44,
      height: 44,
      borderRadius: "var(--radius-full)",
      border: "none",
      background: "transparent",
      color: opts.disabled ? "var(--text-disabled)" : "var(--text-primary)",
      cursor: opts.disabled ? "not-allowed" : "pointer",
      display: "inline-flex",
      alignItems: "center",
      justifyContent: "center"
    }
  }, /*#__PURE__*/React.createElement(__ds_scope.Icon, {
    name: icon,
    size: 22
  }));
  return /*#__PURE__*/React.createElement("div", {
    role: "region",
    "aria-label": "Music Assistant mini player",
    onClick: onExpand,
    tabIndex: 0,
    "data-focusable": "",
    onMouseEnter: e => e.currentTarget.focus(),
    className: "ma-mini",
    style: {
      position: "relative",
      display: "flex",
      alignItems: "stretch",
      background: "var(--surface-container-high)",
      borderRadius: "var(--radius-md)",
      overflow: "hidden",
      boxShadow: "var(--elevation-3)",
      cursor: "pointer",
      ...style
    }
  }, preparing ? /*#__PURE__*/React.createElement("div", {
    style: {
      position: "absolute",
      top: 0,
      left: 0,
      right: 0,
      height: 2,
      background: "rgba(255,255,255,0.08)"
    }
  }, /*#__PURE__*/React.createElement("div", {
    className: "ma-prep-bar",
    style: {
      height: "100%",
      width: "40%",
      background: "var(--primary)"
    }
  })) : null, /*#__PURE__*/React.createElement("div", {
    style: {
      width: 56,
      height: 56,
      flexShrink: 0,
      padding: 6,
      boxSizing: "border-box"
    }
  }, /*#__PURE__*/React.createElement("div", {
    style: {
      width: 44,
      height: 44,
      borderRadius: 8,
      overflow: "hidden",
      background: "var(--accent-container)",
      display: "flex",
      alignItems: "center",
      justifyContent: "center"
    }
  }, track.artwork ? /*#__PURE__*/React.createElement("img", {
    src: track.artwork,
    alt: "",
    style: {
      width: "100%",
      height: "100%",
      objectFit: "cover"
    }
  }) : /*#__PURE__*/React.createElement(__ds_scope.Icon, {
    name: "music",
    size: 20,
    color: "var(--on-accent-container)"
  }))), /*#__PURE__*/React.createElement("div", {
    style: {
      flex: 1,
      minWidth: 0,
      display: "flex",
      flexDirection: "column",
      justifyContent: "center",
      padding: "8px 4px"
    }
  }, /*#__PURE__*/React.createElement("div", {
    className: "m3-title-small",
    style: {
      color: "var(--text-primary)",
      fontWeight: 600,
      whiteSpace: "nowrap",
      overflow: "hidden",
      textOverflow: "ellipsis"
    }
  }, track.title || "Unknown track"), subtitle ? /*#__PURE__*/React.createElement("div", {
    className: "m3-body-small",
    style: {
      color: "var(--text-secondary)",
      whiteSpace: "nowrap",
      overflow: "hidden",
      textOverflow: "ellipsis"
    }
  }, subtitle) : null), /*#__PURE__*/React.createElement("div", {
    style: {
      display: "flex",
      alignItems: "center",
      padding: "0 8px",
      gap: 2,
      flexShrink: 0
    }
  }, btn(playing ? "pause" : "play", playing ? "Pause" : "Play", onPlayPause, {
    disabled: preparing
  }), btn("skip-forward", "Next", onNext, {
    disabled: preparing
  }), btn("square", "Stop", onStop)), /*#__PURE__*/React.createElement("style", null, `
        .ma-mini{ transition: outline-color var(--duration-fast), background var(--duration-fast); }
        .ma-mini:focus{ outline: 3px solid var(--primary); outline-offset: 3px; }
        .ma-mini-btn:focus{ outline: 3px solid var(--primary); outline-offset: 2px; background: var(--surface-container-highest); }
        @keyframes ma-prep { 0%{ margin-left: -40% } 100%{ margin-left: 100% } }
        .ma-prep-bar{ animation: ma-prep 1.5s var(--ease-standard) infinite; }
      `));
}
Object.assign(__ds_scope, { MaMiniPlayer });
})(); } catch (e) { __ds_ns.__errors.push({ path: "components/music/MaMiniPlayer.jsx", error: String((e && e.message) || e) }); }

// components/music/MaPlayerPickerSheet.jsx
try { (() => {
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
function MaPlayerPickerSheet({
  open = true,
  players = [],
  selectedId = null,
  onPick,
  onToggleGroupMember,
  onDismiss,
  style = {}
}) {
  if (!open) return null;
  const selected = players.find(p => p.id === selectedId) || null;
  const leader = selected && selected.supportsGrouping && !selected.syncedToPlayerId ? selected : null;
  const groupable = leader ? players.filter(p => p.id !== leader.id && p.supportsGrouping && ((leader.canGroupWith || []).includes(p.id) || (leader.canGroupWith || []).includes(p.provider) || (p.canGroupWith || []).includes(leader.id) || (p.canGroupWith || []).includes(leader.provider))) : [];
  const Row = ({
    icon,
    title,
    subtitle,
    checked,
    onClick,
    trailing,
    first
  }) => /*#__PURE__*/React.createElement("button", {
    type: "button",
    tabIndex: 0,
    "data-focusable": "",
    "data-focus-first": first ? "" : undefined,
    onClick: onClick,
    onMouseEnter: e => e.currentTarget.focus(),
    className: "ma-pick-row",
    style: {
      display: "flex",
      alignItems: "center",
      gap: 18,
      width: "100%",
      padding: "14px 22px",
      border: "none",
      background: "transparent",
      color: "var(--text-primary)",
      textAlign: "left",
      cursor: "pointer"
    }
  }, /*#__PURE__*/React.createElement("span", {
    style: {
      width: 44,
      height: 44,
      borderRadius: "var(--radius-full)",
      background: "var(--surface-container-highest)",
      color: "var(--text-secondary)",
      display: "inline-flex",
      alignItems: "center",
      justifyContent: "center",
      flexShrink: 0
    }
  }, /*#__PURE__*/React.createElement(__ds_scope.Icon, {
    name: icon,
    size: 22
  })), /*#__PURE__*/React.createElement("span", {
    style: {
      flex: 1,
      minWidth: 0
    }
  }, /*#__PURE__*/React.createElement("span", {
    style: {
      display: "block",
      fontSize: 17,
      fontWeight: 600,
      whiteSpace: "nowrap",
      overflow: "hidden",
      textOverflow: "ellipsis"
    }
  }, title), subtitle ? /*#__PURE__*/React.createElement("span", {
    style: {
      display: "block",
      fontSize: 14,
      color: "var(--text-secondary)",
      whiteSpace: "nowrap",
      overflow: "hidden",
      textOverflow: "ellipsis"
    }
  }, subtitle) : null), trailing || (checked ? /*#__PURE__*/React.createElement(__ds_scope.Icon, {
    name: "check",
    size: 22,
    color: "var(--primary)"
  }) : null));
  return /*#__PURE__*/React.createElement("div", {
    role: "dialog",
    "aria-label": "Play on",
    style: {
      position: "fixed",
      inset: 0,
      background: "rgba(0,0,0,0.6)",
      zIndex: 80,
      display: "flex",
      alignItems: "flex-end",
      justifyContent: "center",
      ...style
    },
    onClick: onDismiss
  }, /*#__PURE__*/React.createElement("div", {
    onClick: e => e.stopPropagation(),
    style: {
      width: "min(560px, 92vw)",
      marginBottom: 24,
      background: "var(--surface-container)",
      borderRadius: "var(--radius-lg)",
      border: "1px solid var(--glass-border)",
      boxShadow: "var(--elevation-5)",
      overflow: "hidden",
      maxHeight: "82vh",
      display: "flex",
      flexDirection: "column"
    }
  }, /*#__PURE__*/React.createElement("div", {
    style: {
      padding: "20px 24px 12px",
      display: "flex",
      alignItems: "center",
      justifyContent: "space-between"
    }
  }, /*#__PURE__*/React.createElement("span", {
    className: "m3-title-large",
    style: {
      fontWeight: 700
    }
  }, "Play on"), /*#__PURE__*/React.createElement("button", {
    type: "button",
    tabIndex: 0,
    "data-focusable": "",
    onClick: onDismiss,
    onMouseEnter: e => e.currentTarget.focus(),
    "aria-label": "Close",
    className: "ma-pick-row",
    style: {
      width: 40,
      height: 40,
      borderRadius: "var(--radius-full)",
      border: "none",
      background: "var(--surface-container-high)",
      color: "var(--text-primary)",
      cursor: "pointer",
      display: "inline-flex",
      alignItems: "center",
      justifyContent: "center"
    }
  }, /*#__PURE__*/React.createElement(__ds_scope.Icon, {
    name: "x",
    size: 20
  }))), /*#__PURE__*/React.createElement("div", {
    style: {
      flex: 1,
      minHeight: 0,
      overflowY: "auto",
      paddingBottom: 18
    }
  }, /*#__PURE__*/React.createElement(Row, {
    icon: "speaker",
    title: "Auto (this device)",
    subtitle: "Auto-detected SendSpin wrapper",
    checked: selectedId == null,
    first: true,
    onClick: () => {
      onPick && onPick(null);
      onDismiss && onDismiss();
    }
  }), /*#__PURE__*/React.createElement("div", {
    style: {
      height: 1,
      background: "var(--border-subtle)",
      margin: "8px 24px"
    }
  }), players.length === 0 ? /*#__PURE__*/React.createElement("div", {
    style: {
      padding: 24,
      textAlign: "center",
      color: "var(--text-secondary)"
    }
  }, "No other Music Assistant players available.") : players.map(p => /*#__PURE__*/React.createElement(Row, {
    key: p.id,
    icon: "speaker",
    title: p.name,
    subtitle: p.isPlaying ? "Now playing" : p.provider,
    checked: p.id === selectedId,
    onClick: () => {
      onPick && onPick(p.id);
      onDismiss && onDismiss();
    }
  })), leader && groupable.length > 0 ? /*#__PURE__*/React.createElement(React.Fragment, null, /*#__PURE__*/React.createElement("div", {
    style: {
      padding: "16px 24px 4px",
      color: "var(--text-secondary)",
      fontSize: 13,
      fontWeight: 600,
      letterSpacing: 0.6,
      textTransform: "uppercase"
    }
  }, "Also play on (in sync)"), groupable.map(p => {
    const grouped = p.syncedToPlayerId === leader.id || (leader.groupMemberIds || []).includes(p.id);
    return /*#__PURE__*/React.createElement(Row, {
      key: "g-" + p.id,
      icon: "speaker",
      title: p.name,
      subtitle: grouped ? "In sync" : "Tap to add",
      onClick: () => onToggleGroupMember && onToggleGroupMember(leader.id, p.id, grouped),
      trailing: /*#__PURE__*/React.createElement("span", {
        style: {
          width: 22,
          height: 22,
          borderRadius: 5,
          border: "2px solid " + (grouped ? "var(--primary)" : "var(--border-strong)"),
          background: grouped ? "var(--primary)" : "transparent",
          color: "var(--on-primary)",
          display: "inline-flex",
          alignItems: "center",
          justifyContent: "center"
        }
      }, grouped ? /*#__PURE__*/React.createElement(__ds_scope.Icon, {
        name: "check",
        size: 14
      }) : null)
    });
  })) : null), /*#__PURE__*/React.createElement("style", null, `
          .ma-pick-row{ transition: background var(--duration-fast), outline-color var(--duration-fast); }
          .ma-pick-row:focus{ outline: 3px solid var(--primary); outline-offset: -3px; background: var(--surface-container-high); }
        `)));
}
Object.assign(__ds_scope, { MaPlayerPickerSheet });
})(); } catch (e) { __ds_ns.__errors.push({ path: "components/music/MaPlayerPickerSheet.jsx", error: String((e && e.message) || e) }); }

// components/navigation/NavBar.jsx
try { (() => {
/**
 * NavBar — the phone (Beam) bottom navigation bar. Material 3 style: the active
 * destination gets a tonal pill behind its icon and an accent label. Pass
 * `items` as [{ id, icon, label }] plus the active id.
 */
function NavBar({
  items = [],
  active,
  onChange,
  style = {}
}) {
  return /*#__PURE__*/React.createElement("nav", {
    style: {
      display: "flex",
      alignItems: "stretch",
      justifyContent: "space-around",
      background: "var(--surface-container)",
      borderTop: "1px solid var(--border-subtle)",
      padding: "10px 4px 12px",
      gap: 2,
      ...style
    }
  }, items.map(it => {
    const on = it.id === active;
    return /*#__PURE__*/React.createElement("button", {
      key: it.id,
      type: "button",
      onClick: () => onChange && onChange(it.id),
      style: {
        flex: 1,
        display: "flex",
        flexDirection: "column",
        alignItems: "center",
        gap: 4,
        background: "transparent",
        border: "none",
        cursor: "pointer",
        padding: 0
      }
    }, /*#__PURE__*/React.createElement("span", {
      style: {
        display: "inline-flex",
        alignItems: "center",
        justifyContent: "center",
        width: 56,
        height: 30,
        borderRadius: "var(--radius-full)",
        background: on ? "var(--accent-container)" : "transparent",
        color: on ? "var(--on-accent-container)" : "var(--text-secondary)",
        transition: "background var(--duration-medium) var(--ease-standard)"
      }
    }, /*#__PURE__*/React.createElement(__ds_scope.Icon, {
      name: it.icon,
      size: 20,
      strokeWidth: on ? 2.4 : 2
    })), /*#__PURE__*/React.createElement("span", {
      style: {
        fontFamily: "var(--font-sans)",
        fontSize: 11,
        fontWeight: 500,
        letterSpacing: 0.4,
        color: on ? "var(--text-primary)" : "var(--text-secondary)"
      }
    }, it.label));
  }));
}
Object.assign(__ds_scope, { NavBar });
})(); } catch (e) { __ds_ns.__errors.push({ path: "components/navigation/NavBar.jsx", error: String((e && e.message) || e) }); }

// components/navigation/ServerPickerSheet.jsx
try { (() => {
/**
 * ServerPickerSheet — the SpatialFin server switcher (mirrors
 * `ServerSelectionBottomSheet.kt`). Lists every connected Jellyfin server with
 * its address; the current one is checked. A "Manage servers" entry routes to
 * the full server-management screen.
 *
 * `servers` items: { id, name, address, currentUser? }
 */
function ServerPickerSheet({
  open = true,
  servers = [],
  currentId,
  onPick,
  onManage,
  onDismiss,
  style = {}
}) {
  if (!open) return null;
  const Row = ({
    icon,
    title,
    subtitle,
    checked,
    onClick,
    first
  }) => /*#__PURE__*/React.createElement("button", {
    type: "button",
    tabIndex: 0,
    "data-focusable": "",
    "data-focus-first": first ? "" : undefined,
    onClick: onClick,
    onMouseEnter: e => e.currentTarget.focus(),
    className: "srv-row",
    style: {
      display: "flex",
      alignItems: "center",
      gap: 18,
      width: "100%",
      padding: "14px 22px",
      border: "none",
      background: "transparent",
      color: "var(--text-primary)",
      textAlign: "left",
      cursor: "pointer"
    }
  }, /*#__PURE__*/React.createElement("span", {
    style: {
      width: 44,
      height: 44,
      borderRadius: "var(--radius-full)",
      background: "var(--accent-container)",
      color: "var(--on-accent-container)",
      display: "inline-flex",
      alignItems: "center",
      justifyContent: "center",
      flexShrink: 0
    }
  }, /*#__PURE__*/React.createElement(__ds_scope.Icon, {
    name: icon,
    size: 22
  })), /*#__PURE__*/React.createElement("span", {
    style: {
      flex: 1,
      minWidth: 0
    }
  }, /*#__PURE__*/React.createElement("span", {
    style: {
      display: "block",
      fontSize: 17,
      fontWeight: 600,
      whiteSpace: "nowrap",
      overflow: "hidden",
      textOverflow: "ellipsis"
    }
  }, title), subtitle ? /*#__PURE__*/React.createElement("span", {
    style: {
      display: "block",
      fontSize: 14,
      color: "var(--text-secondary)",
      whiteSpace: "nowrap",
      overflow: "hidden",
      textOverflow: "ellipsis",
      fontFamily: "var(--font-mono)"
    }
  }, subtitle) : null), checked ? /*#__PURE__*/React.createElement(__ds_scope.Icon, {
    name: "check",
    size: 22,
    color: "var(--primary)"
  }) : null);
  return /*#__PURE__*/React.createElement("div", {
    role: "dialog",
    "aria-label": "Switch server",
    style: {
      position: "fixed",
      inset: 0,
      background: "rgba(0,0,0,0.6)",
      zIndex: 80,
      display: "flex",
      alignItems: "center",
      justifyContent: "center",
      ...style
    },
    onClick: onDismiss
  }, /*#__PURE__*/React.createElement("div", {
    onClick: e => e.stopPropagation(),
    style: {
      width: "min(560px, 92vw)",
      background: "var(--surface-container)",
      borderRadius: "var(--radius-lg)",
      border: "1px solid var(--glass-border)",
      boxShadow: "var(--elevation-5)",
      overflow: "hidden",
      maxHeight: "82vh",
      display: "flex",
      flexDirection: "column"
    }
  }, /*#__PURE__*/React.createElement("div", {
    style: {
      padding: "20px 24px 12px",
      display: "flex",
      alignItems: "center",
      justifyContent: "space-between"
    }
  }, /*#__PURE__*/React.createElement("span", {
    className: "m3-title-large",
    style: {
      fontWeight: 700
    }
  }, "Switch server"), /*#__PURE__*/React.createElement("button", {
    type: "button",
    tabIndex: 0,
    "data-focusable": "",
    onClick: onDismiss,
    onMouseEnter: e => e.currentTarget.focus(),
    "aria-label": "Close",
    className: "srv-row",
    style: {
      width: 40,
      height: 40,
      borderRadius: "var(--radius-full)",
      border: "none",
      background: "var(--surface-container-high)",
      color: "var(--text-primary)",
      cursor: "pointer",
      display: "inline-flex",
      alignItems: "center",
      justifyContent: "center"
    }
  }, /*#__PURE__*/React.createElement(__ds_scope.Icon, {
    name: "x",
    size: 20
  }))), /*#__PURE__*/React.createElement("div", {
    style: {
      flex: 1,
      minHeight: 0,
      overflowY: "auto",
      paddingBottom: 12
    }
  }, servers.map((s, i) => /*#__PURE__*/React.createElement(Row, {
    key: s.id,
    icon: "server",
    title: s.name,
    subtitle: s.address,
    checked: s.id === currentId,
    first: i === 0,
    onClick: () => {
      onPick && onPick(s.id);
      onDismiss && onDismiss();
    }
  })), /*#__PURE__*/React.createElement("div", {
    style: {
      height: 1,
      background: "var(--border-subtle)",
      margin: "8px 24px"
    }
  }), /*#__PURE__*/React.createElement(Row, {
    icon: "settings",
    title: "Manage servers",
    subtitle: "Add or remove servers",
    onClick: () => {
      onManage && onManage();
      onDismiss && onDismiss();
    }
  })), /*#__PURE__*/React.createElement("style", null, `
          .srv-row{ transition: background var(--duration-fast), outline-color var(--duration-fast); }
          .srv-row:focus{ outline: 3px solid var(--primary); outline-offset: -3px; background: var(--surface-container-high); }
        `)));
}
Object.assign(__ds_scope, { ServerPickerSheet });
})(); } catch (e) { __ds_ns.__errors.push({ path: "components/navigation/ServerPickerSheet.jsx", error: String((e && e.message) || e) }); }

// components/onboarding/OnboardingStepper.jsx
try { (() => {
/**
 * OnboardingStepper — a compact dots-and-line progress indicator for multi-step
 * setup flows. The active dot is filled with the accent; completed dots are
 * filled muted; upcoming dots are hollow. Optional labels under each dot.
 *
 * Use the same component on all three surfaces — sizes scale via `size`.
 */
function OnboardingStepper({
  steps = 3,
  active = 0,
  labels = null,
  size = "md",
  style = {}
}) {
  const dims = {
    sm: {
      dot: 8,
      gap: 24,
      fs: 12
    },
    md: {
      dot: 10,
      gap: 36,
      fs: 13
    },
    lg: {
      dot: 14,
      gap: 64,
      fs: 15
    }
  }[size] || {
    dot: 10,
    gap: 36,
    fs: 13
  };
  return /*#__PURE__*/React.createElement("div", {
    style: {
      display: "inline-flex",
      flexDirection: "column",
      alignItems: "center",
      gap: 10,
      ...style
    }
  }, /*#__PURE__*/React.createElement("div", {
    style: {
      display: "inline-flex",
      alignItems: "center",
      gap: 0
    }
  }, Array.from({
    length: steps
  }).map((_, i) => {
    const done = i < active,
      on = i === active;
    return /*#__PURE__*/React.createElement(React.Fragment, {
      key: i
    }, /*#__PURE__*/React.createElement("span", {
      style: {
        width: on ? dims.dot * 2.4 : dims.dot,
        height: dims.dot,
        borderRadius: "var(--radius-full)",
        background: on ? "var(--primary)" : done ? "var(--accent-container)" : "transparent",
        border: !on && !done ? "1.5px solid var(--border-strong)" : "none",
        transition: "all var(--duration-medium) var(--ease-standard)",
        flexShrink: 0
      }
    }), i < steps - 1 ? /*#__PURE__*/React.createElement("span", {
      style: {
        width: dims.gap,
        height: 2,
        background: done ? "var(--accent-container)" : "var(--border-subtle)"
      }
    }) : null);
  })), labels ? /*#__PURE__*/React.createElement("div", {
    style: {
      display: "inline-flex",
      alignItems: "center",
      gap: 0
    }
  }, labels.map((l, i) => /*#__PURE__*/React.createElement(React.Fragment, {
    key: i
  }, /*#__PURE__*/React.createElement("span", {
    style: {
      fontSize: dims.fs,
      fontWeight: 500,
      color: i === active ? "var(--text-primary)" : "var(--text-secondary)",
      width: dims.dot * 2.4 + dims.gap,
      textAlign: "center",
      marginLeft: i === 0 ? -dims.gap / 2 : 0,
      marginRight: i === labels.length - 1 ? -dims.gap / 2 : 0
    }
  }, l)))) : null);
}
Object.assign(__ds_scope, { OnboardingStepper });
})(); } catch (e) { __ds_ns.__errors.push({ path: "components/onboarding/OnboardingStepper.jsx", error: String((e && e.message) || e) }); }

// components/onboarding/PointerHint.jsx
try { (() => {
/**
 * PointerHint — a small animated pointer used during onboarding to call out a
 * UI element (e.g. "this is the mic FAB"). Renders as an absolutely-positioned
 * wrapper around the target slot with a soft pulse + an angled label callout.
 *
 * Place the wrapper around any element. `direction` controls which side the
 * label appears on.
 */
function PointerHint({
  label,
  hint,
  direction = "top",
  // top | bottom | left | right
  visible = true,
  children,
  style = {}
}) {
  const offset = {
    top: {
      bottom: "calc(100% + 18px)",
      left: "50%",
      transform: "translateX(-50%)"
    },
    bottom: {
      top: "calc(100% + 18px)",
      left: "50%",
      transform: "translateX(-50%)"
    },
    left: {
      right: "calc(100% + 18px)",
      top: "50%",
      transform: "translateY(-50%)"
    },
    right: {
      left: "calc(100% + 18px)",
      top: "50%",
      transform: "translateY(-50%)"
    }
  }[direction];
  return /*#__PURE__*/React.createElement("span", {
    style: {
      position: "relative",
      display: "inline-flex",
      ...style
    }
  }, visible ? /*#__PURE__*/React.createElement("span", {
    "aria-hidden": true,
    style: {
      position: "absolute",
      inset: -10,
      borderRadius: "var(--radius-full)",
      border: "2px solid var(--primary)",
      pointerEvents: "none",
      animation: "sf-hint-ring 1.6s var(--ease-standard) infinite"
    }
  }) : null, children, visible && (label || hint) ? /*#__PURE__*/React.createElement("span", {
    style: {
      position: "absolute",
      ...offset,
      minWidth: 180,
      maxWidth: 280,
      padding: "10px 14px",
      borderRadius: "var(--radius-md)",
      background: "var(--surface-container-highest)",
      color: "var(--text-primary)",
      boxShadow: "var(--elevation-3)",
      border: "1px solid var(--glass-border)",
      textAlign: "center",
      pointerEvents: "none",
      animation: "sf-hint-bob 2.4s var(--ease-standard) infinite"
    }
  }, label ? /*#__PURE__*/React.createElement("div", {
    className: "m3-title-small",
    style: {
      fontWeight: 700
    }
  }, label) : null, hint ? /*#__PURE__*/React.createElement("div", {
    className: "m3-body-small",
    style: {
      color: "var(--text-secondary)",
      marginTop: 2
    }
  }, hint) : null, /*#__PURE__*/React.createElement("span", {
    "aria-hidden": true,
    style: {
      position: "absolute",
      ...(direction === "top" ? {
        bottom: -7,
        left: "50%",
        transform: "translateX(-50%) rotate(45deg)"
      } : {}),
      ...(direction === "bottom" ? {
        top: -7,
        left: "50%",
        transform: "translateX(-50%) rotate(45deg)"
      } : {}),
      ...(direction === "left" ? {
        right: -7,
        top: "50%",
        transform: "translateY(-50%) rotate(45deg)"
      } : {}),
      ...(direction === "right" ? {
        left: -7,
        top: "50%",
        transform: "translateY(-50%) rotate(45deg)"
      } : {}),
      width: 14,
      height: 14,
      background: "var(--surface-container-highest)",
      borderLeft: "1px solid var(--glass-border)",
      borderBottom: "1px solid var(--glass-border)"
    }
  })) : null, /*#__PURE__*/React.createElement("style", null, `
        @keyframes sf-hint-ring { 0%{ transform: scale(1); opacity: 0.9; } 70%{ transform: scale(1.18); opacity: 0; } 100%{ opacity: 0; } }
        @keyframes sf-hint-bob { 0%,100%{ transform: ${direction === "top" || direction === "bottom" ? "translateX(-50%) translateY(0)" : "translateY(-50%) translateX(0)"}; }
          50%{ transform: ${direction === "top" ? "translateX(-50%) translateY(-4px)" : direction === "bottom" ? "translateX(-50%) translateY(4px)" : direction === "left" ? "translateY(-50%) translateX(-4px)" : "translateY(-50%) translateX(4px)"}; } }
      `));
}
Object.assign(__ds_scope, { PointerHint });
})(); } catch (e) { __ds_ns.__errors.push({ path: "components/onboarding/PointerHint.jsx", error: String((e && e.message) || e) }); }

// components/onboarding/QrPairCode.jsx
try { (() => {
/**
 * QrPairCode — the "scan to pair" QR card used during onboarding on TV and XR,
 * and during companion handoff on Beam. Renders the QR image with a soft glow
 * + a 4-character verification code underneath (so the user can confirm they
 * paired the right thing). For the design system this expects a placeholder
 * QR PNG; consumers swap in a real one.
 *
 * The `pairCode` is shown big and mono — same vocabulary as Quick Connect.
 */
function QrPairCode({
  qrSrc,
  pairCode = "AX-94K",
  caption = "Scan with the SpatialFin app on your phone",
  size = 220,
  tone = "default",
  // default | glass
  style = {}
}) {
  const isGlass = tone === "glass";
  return /*#__PURE__*/React.createElement("div", {
    style: {
      display: "inline-flex",
      flexDirection: "column",
      alignItems: "center",
      gap: 14,
      padding: 18,
      borderRadius: "var(--radius-lg)",
      background: isGlass ? "var(--glass-fill-strong)" : "var(--surface-container-high)",
      border: isGlass ? "1px solid var(--glass-border)" : "1px solid var(--border-subtle)",
      backdropFilter: isGlass ? "blur(var(--glass-blur))" : "none",
      WebkitBackdropFilter: isGlass ? "blur(var(--glass-blur))" : "none",
      boxShadow: isGlass ? "var(--shadow-glass)" : "var(--elevation-2)",
      ...style
    }
  }, /*#__PURE__*/React.createElement("div", {
    style: {
      position: "relative",
      padding: 12,
      borderRadius: "var(--radius-md)",
      background: "#fff",
      boxShadow: "0 12px 28px -8px rgba(125,218,255,0.22)"
    }
  }, qrSrc ? /*#__PURE__*/React.createElement("img", {
    src: qrSrc,
    alt: "Pair code QR — " + pairCode,
    style: {
      width: size,
      height: size,
      display: "block",
      imageRendering: "pixelated"
    }
  }) : /*#__PURE__*/React.createElement("div", {
    style: {
      width: size,
      height: size,
      background: "var(--surface-container)",
      display: "flex",
      alignItems: "center",
      justifyContent: "center",
      color: "var(--text-secondary)",
      fontFamily: "var(--font-mono)",
      fontSize: 12
    }
  }, "QR placeholder"), /*#__PURE__*/React.createElement("span", {
    "aria-hidden": true,
    style: {
      position: "absolute",
      left: "50%",
      top: "50%",
      transform: "translate(-50%, -50%)",
      width: size * 0.22,
      height: size * 0.22,
      borderRadius: "50%",
      background: "#fff",
      border: "3px solid var(--primary)",
      display: "flex",
      alignItems: "center",
      justifyContent: "center",
      color: "var(--primary)",
      fontWeight: 800,
      fontSize: size * 0.1
    }
  }, "SF")), /*#__PURE__*/React.createElement("div", {
    style: {
      display: "flex",
      flexDirection: "column",
      alignItems: "center",
      gap: 4
    }
  }, /*#__PURE__*/React.createElement("span", {
    style: {
      fontFamily: "var(--font-mono)",
      fontSize: 22,
      fontWeight: 700,
      letterSpacing: 6,
      color: "var(--text-primary)"
    }
  }, pairCode), caption ? /*#__PURE__*/React.createElement("span", {
    className: "m3-body-small",
    style: {
      color: "var(--text-secondary)",
      textAlign: "center",
      maxWidth: size + 80
    }
  }, caption) : null));
}
Object.assign(__ds_scope, { QrPairCode });
})(); } catch (e) { __ds_ns.__errors.push({ path: "components/onboarding/QrPairCode.jsx", error: String((e && e.message) || e) }); }

// components/onboarding/QuickConnectCode.jsx
try { (() => {
/**
 * QuickConnectCode — Jellyfin Quick Connect-style 6-character code rendered
 * BIG so the user can read it from across the room and enter it on a phone
 * or web client. Each character sits in its own tile with subtle entry
 * animation as the code is revealed. Pass `expiresAt` for an optional
 * countdown bar underneath.
 */
function QuickConnectCode({
  code = "73-9KQA",
  helper = "On any device, open spatialfin.app/connect and enter this code",
  status = "waiting",
  // waiting | connecting | confirmed
  style = {}
}) {
  const chars = code.split("");
  const statusMeta = {
    waiting: {
      label: "Waiting for confirmation",
      color: "var(--text-secondary)",
      dot: "var(--text-disabled)"
    },
    connecting: {
      label: "Confirming…",
      color: "var(--primary)",
      dot: "var(--primary)"
    },
    confirmed: {
      label: "Connected!",
      color: "var(--tertiary)",
      dot: "var(--tertiary)"
    }
  }[status] || {
    label: status,
    color: "var(--text-secondary)",
    dot: "var(--text-disabled)"
  };
  return /*#__PURE__*/React.createElement("div", {
    style: {
      display: "inline-flex",
      flexDirection: "column",
      alignItems: "center",
      gap: 16,
      ...style
    }
  }, /*#__PURE__*/React.createElement("div", {
    style: {
      display: "inline-flex",
      gap: 8
    }
  }, chars.map((ch, i) => ch === "-" ? /*#__PURE__*/React.createElement("span", {
    key: i,
    "aria-hidden": true,
    style: {
      width: 18,
      alignSelf: "center",
      color: "var(--text-disabled)",
      fontSize: 36,
      fontWeight: 400
    }
  }, "\xB7") : /*#__PURE__*/React.createElement("span", {
    key: i,
    style: {
      width: 72,
      height: 96,
      borderRadius: "var(--radius-md)",
      background: "var(--surface-container-high)",
      border: "1px solid var(--border-subtle)",
      display: "inline-flex",
      alignItems: "center",
      justifyContent: "center",
      fontFamily: "var(--font-mono)",
      fontSize: 56,
      fontWeight: 700,
      color: "var(--text-primary)",
      letterSpacing: 0,
      boxShadow: "inset 0 -3px 0 rgba(0,0,0,0.15)",
      animation: `sf-qc-pop ${0.2 + i * 0.05}s var(--ease-standard) both`
    }
  }, ch))), /*#__PURE__*/React.createElement("div", {
    style: {
      display: "inline-flex",
      alignItems: "center",
      gap: 8
    }
  }, /*#__PURE__*/React.createElement("span", {
    style: {
      width: 8,
      height: 8,
      borderRadius: "50%",
      background: statusMeta.dot,
      animation: status === "connecting" ? "sf-qc-pulse 1.2s ease-in-out infinite" : "none"
    }
  }), /*#__PURE__*/React.createElement("span", {
    className: "m3-label-large",
    style: {
      color: statusMeta.color,
      fontWeight: 600
    }
  }, statusMeta.label)), helper ? /*#__PURE__*/React.createElement("div", {
    className: "m3-body-medium",
    style: {
      color: "var(--text-secondary)",
      maxWidth: 580,
      textAlign: "center"
    }
  }, helper) : null, /*#__PURE__*/React.createElement("style", null, `
        @keyframes sf-qc-pop { 0%{ transform: translateY(8px); opacity: 0; } 100%{ transform: none; opacity: 1; } }
        @keyframes sf-qc-pulse { 0%,100%{ transform: scale(1); opacity: 1; } 50%{ transform: scale(1.6); opacity: 0.5; } }
      `));
}
Object.assign(__ds_scope, { QuickConnectCode });
})(); } catch (e) { __ds_ns.__errors.push({ path: "components/onboarding/QuickConnectCode.jsx", error: String((e && e.message) || e) }); }

// components/surfaces/GlassPanel.jsx
try { (() => {
function _extends() { return _extends = Object.assign ? Object.assign.bind() : function (n) { for (var e = 1; e < arguments.length; e++) { var t = arguments[e]; for (var r in t) ({}).hasOwnProperty.call(t, r) && (n[r] = t[r]); } return n; }, _extends.apply(null, arguments); }
/**
 * GlassPanel — the translucent floating surface that defines the XR look.
 * darkSurface at 62–88% with a 24px backdrop blur, hairline border, large
 * radius and a soft ambient shadow. Use `tone="strong"` for dense controls /
 * dialogs that need extra legibility; `tone="panel"` for content over
 * passthrough or video.
 */
function GlassPanel({
  children,
  tone = "panel",
  radius = "lg",
  padding = "lg",
  style = {},
  ...rest
}) {
  const radii = {
    md: "var(--radius-md)",
    lg: "var(--radius-lg)",
    full: "var(--radius-full)"
  };
  const pads = {
    none: 0,
    sm: "var(--space-sm)",
    md: "var(--space-md)",
    lg: "var(--space-lg)"
  };
  return /*#__PURE__*/React.createElement("div", _extends({
    style: {
      background: tone === "strong" ? "var(--glass-fill-strong)" : "var(--glass-fill)",
      WebkitBackdropFilter: "blur(var(--glass-blur))",
      backdropFilter: "blur(var(--glass-blur))",
      border: "1px solid var(--glass-border)",
      borderRadius: radii[radius] || radii.lg,
      boxShadow: "var(--shadow-glass)",
      padding: pads[padding] ?? pads.lg,
      color: "var(--text-primary)",
      ...style
    }
  }, rest), children);
}
Object.assign(__ds_scope, { GlassPanel });
})(); } catch (e) { __ds_ns.__errors.push({ path: "components/surfaces/GlassPanel.jsx", error: String((e && e.message) || e) }); }

// components/surfaces/Orbiter.jsx
try { (() => {
function _extends() { return _extends = Object.assign ? Object.assign.bind() : function (n) { for (var e = 1; e < arguments.length; e++) { var t = arguments[e]; for (var r in t) ({}).hasOwnProperty.call(t, r) && (n[r] = t[r]); } return n; }, _extends.apply(null, arguments); }
/**
 * Orbiter — the floating glass control cluster that hovers beside a spatial
 * panel in XR (Material 3 for XR adapts TopAppBar / NavigationRail into these).
 * A rounded-full or pill capsule of icon buttons. ONE orbiter per panel.
 *
 * Pass `items` as [{icon, label, onClick, active}] or supply `children`.
 */
function Orbiter({
  items = [],
  orientation = "vertical",
  children,
  style = {},
  ...rest
}) {
  const vertical = orientation === "vertical";
  return /*#__PURE__*/React.createElement("div", _extends({
    style: {
      display: "inline-flex",
      flexDirection: vertical ? "column" : "row",
      gap: "var(--space-sm)",
      padding: "var(--space-sm)",
      background: "var(--glass-fill-strong)",
      WebkitBackdropFilter: "blur(var(--glass-blur))",
      backdropFilter: "blur(var(--glass-blur))",
      border: "1px solid var(--glass-border)",
      borderRadius: "var(--radius-full)",
      boxShadow: "var(--shadow-glass)",
      ...style
    }
  }, rest), children || items.map((it, i) => /*#__PURE__*/React.createElement(__ds_scope.IconButton, {
    key: i,
    icon: it.icon,
    label: it.label,
    variant: it.active ? "filled" : "ghost",
    onClick: it.onClick
  })));
}
Object.assign(__ds_scope, { Orbiter });
})(); } catch (e) { __ds_ns.__errors.push({ path: "components/surfaces/Orbiter.jsx", error: String((e && e.message) || e) }); }

// components/tv/EpisodeCard.jsx
try { (() => {
/**
 * EpisodeCard — a focusable landscape episode tile for TV season rows. Shows a
 * still with the episode number, a watched-progress bar, and a footer with
 * "E# · Title", runtime and a one-line synopsis. Focus reveals a play overlay
 * and brightens the title (focus-scale + cyan ring, never shadow).
 */
function EpisodeCard({
  number,
  title,
  still,
  runtime,
  synopsis,
  progress = null,
  width = 340,
  onClick,
  focusFirst = false,
  style = {}
}) {
  const [focused, setFocused] = React.useState(false);
  return /*#__PURE__*/React.createElement("div", {
    style: {
      width,
      flex: "0 0 auto",
      ...style
    }
  }, /*#__PURE__*/React.createElement("div", {
    tabIndex: 0,
    "data-focusable": "",
    "data-focus-first": focusFirst ? "" : undefined,
    onFocus: () => setFocused(true),
    onBlur: () => setFocused(false),
    onMouseEnter: e => e.currentTarget.focus(),
    onClick: onClick,
    style: {
      position: "relative",
      width: "100%",
      aspectRatio: "16 / 9",
      borderRadius: "var(--radius-md)",
      overflow: "hidden",
      background: "var(--surface-container-low)",
      cursor: "pointer",
      outline: focused ? "3px solid var(--primary)" : "3px solid transparent",
      outlineOffset: 3,
      transform: focused ? "scale(var(--tv-focus-scale))" : "scale(1)",
      transition: "transform var(--duration-fast) var(--ease-standard), outline-color var(--duration-fast) var(--ease-standard), box-shadow var(--duration-fast) var(--ease-standard)",
      boxShadow: focused ? "0 14px 44px -8px rgba(125,218,255,0.5)" : "none"
    }
  }, still ? /*#__PURE__*/React.createElement("img", {
    src: still,
    alt: title,
    style: {
      width: "100%",
      height: "100%",
      objectFit: "cover"
    }
  }) : null, /*#__PURE__*/React.createElement("div", {
    style: {
      position: "absolute",
      inset: 0,
      background: "linear-gradient(180deg, rgba(0,0,0,0.35) 0%, rgba(0,0,0,0) 35%, rgba(0,0,0,0.55) 100%)"
    }
  }), /*#__PURE__*/React.createElement("span", {
    style: {
      position: "absolute",
      top: 8,
      left: 10,
      color: "#fff",
      fontWeight: 700,
      fontSize: 13,
      textShadow: "0 1px 4px rgba(0,0,0,0.6)"
    }
  }, "EP ", number), focused ? /*#__PURE__*/React.createElement("span", {
    style: {
      position: "absolute",
      inset: 0,
      display: "flex",
      alignItems: "center",
      justifyContent: "center"
    }
  }, /*#__PURE__*/React.createElement("span", {
    style: {
      width: 52,
      height: 52,
      borderRadius: "50%",
      background: "var(--glass-fill-strong)",
      border: "1px solid var(--glass-border)",
      color: "#fff",
      display: "inline-flex",
      alignItems: "center",
      justifyContent: "center"
    }
  }, /*#__PURE__*/React.createElement(__ds_scope.Icon, {
    name: "play",
    size: 24
  }))) : null, progress != null ? /*#__PURE__*/React.createElement("div", {
    style: {
      position: "absolute",
      left: 8,
      right: 8,
      bottom: 8
    }
  }, /*#__PURE__*/React.createElement(__ds_scope.ProgressBar, {
    value: progress
  })) : null), /*#__PURE__*/React.createElement("div", {
    style: {
      marginTop: 10,
      paddingInline: 2
    }
  }, /*#__PURE__*/React.createElement("div", {
    style: {
      display: "flex",
      alignItems: "baseline",
      justifyContent: "space-between",
      gap: 8
    }
  }, /*#__PURE__*/React.createElement("span", {
    className: "m3-title-small",
    style: {
      color: focused ? "var(--text-primary)" : "var(--text-secondary)",
      fontWeight: 600,
      whiteSpace: "nowrap",
      overflow: "hidden",
      textOverflow: "ellipsis"
    }
  }, number, ". ", title), runtime ? /*#__PURE__*/React.createElement("span", {
    className: "m3-body-small",
    style: {
      color: "var(--text-disabled)",
      flexShrink: 0
    }
  }, runtime) : null), synopsis ? /*#__PURE__*/React.createElement("div", {
    className: "m3-body-small",
    style: {
      color: "var(--text-disabled)",
      marginTop: 3,
      display: "-webkit-box",
      WebkitLineClamp: 2,
      WebkitBoxOrient: "vertical",
      overflow: "hidden"
    }
  }, synopsis) : null));
}
Object.assign(__ds_scope, { EpisodeCard });
})(); } catch (e) { __ds_ns.__errors.push({ path: "components/tv/EpisodeCard.jsx", error: String((e && e.message) || e) }); }

// components/tv/FocusCard.jsx
try { (() => {
/**
 * FocusCard — the focusable media tile for the 10-foot TV UI. Portrait for
 * movies/shows, landscape for episodes/continue-watching. On focus it lifts
 * with a focus-scale (≈1.06), a cyan focus ring + glow, and the title brightens
 * — TV communicates selection by scale + outline, NEVER shadow elevation.
 *
 * Mark with data-focusable so the D-pad focus engine (tvFocus.js) can reach it.
 */
function FocusCard({
  title,
  subtitle,
  image,
  orientation = "portrait",
  width,
  progress = null,
  badge = null,
  onClick,
  focusFirst = false,
  style = {}
}) {
  const [focused, setFocused] = React.useState(false);
  const portrait = orientation === "portrait";
  const w = width || (portrait ? 168 : 320);
  return /*#__PURE__*/React.createElement("div", {
    style: {
      width: w,
      flex: "0 0 auto",
      ...style
    }
  }, /*#__PURE__*/React.createElement("div", {
    tabIndex: 0,
    "data-focusable": "",
    "data-focus-first": focusFirst ? "" : undefined,
    onFocus: () => setFocused(true),
    onBlur: () => setFocused(false),
    onClick: onClick,
    onMouseEnter: e => e.currentTarget.focus(),
    style: {
      position: "relative",
      width: "100%",
      aspectRatio: portrait ? "2 / 3" : "16 / 9",
      borderRadius: "var(--radius-md)",
      overflow: "hidden",
      background: "var(--surface-container-low)",
      cursor: "pointer",
      outline: focused ? "3px solid var(--primary)" : "3px solid transparent",
      outlineOffset: 3,
      transform: focused ? "scale(var(--tv-focus-scale))" : "scale(1)",
      transition: "transform var(--duration-fast) var(--ease-standard), outline-color var(--duration-fast) var(--ease-standard), box-shadow var(--duration-fast) var(--ease-standard)",
      boxShadow: focused ? "0 14px 44px -8px rgba(125,218,255,0.5)" : "none"
    }
  }, image ? /*#__PURE__*/React.createElement("img", {
    src: image,
    alt: title,
    style: {
      width: "100%",
      height: "100%",
      objectFit: "cover",
      display: "block"
    }
  }) : null, badge ? /*#__PURE__*/React.createElement("div", {
    style: {
      position: "absolute",
      top: 8,
      left: 8
    }
  }, badge) : null, progress != null ? /*#__PURE__*/React.createElement("div", {
    style: {
      position: "absolute",
      left: 8,
      right: 8,
      bottom: 8
    }
  }, /*#__PURE__*/React.createElement(__ds_scope.ProgressBar, {
    value: progress
  })) : null), title ? /*#__PURE__*/React.createElement("div", {
    style: {
      marginTop: 10,
      paddingInline: 2
    }
  }, /*#__PURE__*/React.createElement("div", {
    className: "m3-title-small",
    style: {
      color: focused ? "var(--text-primary)" : "var(--text-secondary)",
      fontWeight: 600,
      whiteSpace: "nowrap",
      overflow: "hidden",
      textOverflow: "ellipsis",
      transition: "color var(--duration-fast) var(--ease-standard)"
    }
  }, title), subtitle ? /*#__PURE__*/React.createElement("div", {
    className: "m3-body-small",
    style: {
      color: "var(--text-disabled)",
      whiteSpace: "nowrap",
      overflow: "hidden",
      textOverflow: "ellipsis",
      marginTop: 2
    }
  }, subtitle) : null) : null);
}
Object.assign(__ds_scope, { FocusCard });
})(); } catch (e) { __ds_ns.__errors.push({ path: "components/tv/FocusCard.jsx", error: String((e && e.message) || e) }); }

// components/tv/SeasonTabs.jsx
try { (() => {
/**
 * SeasonTabs — focusable season selector for a TV show. A horizontal row of
 * pill tabs; the active season is filled, focused tab shows the cyan ring.
 */
function SeasonTabs({
  seasons = [],
  active,
  onChange,
  style = {}
}) {
  return /*#__PURE__*/React.createElement("div", {
    "data-row": true,
    style: {
      display: "flex",
      gap: 10,
      overflowX: "auto",
      padding: "6px 2px",
      scrollbarWidth: "none",
      ...style
    }
  }, seasons.map(s => {
    const on = s === active;
    return /*#__PURE__*/React.createElement("button", {
      key: s,
      type: "button",
      tabIndex: 0,
      "data-focusable": "",
      onClick: () => onChange && onChange(s),
      onMouseEnter: e => e.currentTarget.focus(),
      className: "tv-season-tab",
      style: {
        flex: "0 0 auto",
        height: 44,
        padding: "0 22px",
        borderRadius: "var(--radius-full)",
        border: on ? "1px solid transparent" : "1px solid var(--border-strong)",
        background: on ? "var(--primary)" : "transparent",
        color: on ? "var(--on-primary)" : "var(--text-secondary)",
        fontFamily: "var(--font-sans)",
        fontSize: 15,
        fontWeight: 600,
        cursor: "pointer",
        transition: "background var(--duration-fast) var(--ease-standard)"
      }
    }, "Season ", s);
  }), /*#__PURE__*/React.createElement("style", null, `.tv-season-tab:focus{outline:3px solid var(--primary);outline-offset:3px}`));
}
Object.assign(__ds_scope, { SeasonTabs });
})(); } catch (e) { __ds_ns.__errors.push({ path: "components/tv/SeasonTabs.jsx", error: String((e && e.message) || e) }); }

// components/tv/TvKeyboard.jsx
try { (() => {
/**
 * TvKeyboard — the on-screen QWERTY-grid keyboard for 10-foot Search. Every key
 * is focusable (data-focusable) so the D-pad engine reaches it; Enter types the
 * letter. Space / Delete / Clear are action keys. Calls onChange with the new
 * string. Pair with a search field above it.
 */
const ROWS = [["A", "B", "C", "D", "E", "F", "G"], ["H", "I", "J", "K", "L", "M", "N"], ["O", "P", "Q", "R", "S", "T", "U"], ["V", "W", "X", "Y", "Z", "0", "1"], ["2", "3", "4", "5", "6", "7", "8"]];
function TvKeyboard({
  value = "",
  onChange,
  style = {}
}) {
  const press = k => onChange && onChange(value + k);
  const del = () => onChange && onChange(value.slice(0, -1));
  const clear = () => onChange && onChange("");
  const space = () => onChange && onChange(value + " ");
  const Key = ({
    children,
    onClick,
    wide,
    label,
    first
  }) => /*#__PURE__*/React.createElement("button", {
    type: "button",
    tabIndex: 0,
    "data-focusable": "",
    "data-focus-first": first ? "" : undefined,
    onClick: onClick,
    onMouseEnter: e => e.currentTarget.focus(),
    "aria-label": label,
    className: "tv-key",
    style: {
      width: wide ? 132 : 56,
      height: 56,
      borderRadius: "var(--radius-sm)",
      border: "1px solid var(--border-subtle)",
      background: "var(--surface-container-high)",
      color: "var(--text-primary)",
      fontSize: 20,
      fontWeight: 600,
      cursor: "pointer",
      display: "inline-flex",
      alignItems: "center",
      justifyContent: "center",
      gap: 8
    }
  }, children);
  return /*#__PURE__*/React.createElement("div", {
    style: {
      display: "inline-flex",
      flexDirection: "column",
      gap: 10,
      ...style
    }
  }, ROWS.map((row, ri) => /*#__PURE__*/React.createElement("div", {
    key: ri,
    "data-row": true,
    style: {
      display: "flex",
      gap: 10
    }
  }, row.map((k, ki) => /*#__PURE__*/React.createElement(Key, {
    key: k,
    onClick: () => press(k),
    label: "Letter " + k,
    first: ri === 0 && ki === 0
  }, k)))), /*#__PURE__*/React.createElement("div", {
    "data-row": true,
    style: {
      display: "flex",
      gap: 10
    }
  }, /*#__PURE__*/React.createElement(Key, {
    onClick: space,
    wide: true,
    label: "Space"
  }, /*#__PURE__*/React.createElement(__ds_scope.Icon, {
    name: "space",
    size: 20
  }), " Space"), /*#__PURE__*/React.createElement(Key, {
    onClick: del,
    label: "Delete"
  }, /*#__PURE__*/React.createElement(__ds_scope.Icon, {
    name: "delete",
    size: 20
  })), /*#__PURE__*/React.createElement(Key, {
    onClick: clear,
    wide: true,
    label: "Clear"
  }, "Clear")), /*#__PURE__*/React.createElement("style", null, `.tv-key{transition:transform var(--duration-fast) var(--ease-standard),background var(--duration-fast)}
        .tv-key:focus{outline:3px solid var(--primary);outline-offset:3px;background:var(--primary);color:var(--on-primary);transform:scale(1.06)}`));
}
Object.assign(__ds_scope, { TvKeyboard });
})(); } catch (e) { __ds_ns.__errors.push({ path: "components/tv/TvKeyboard.jsx", error: String((e && e.message) || e) }); }

// components/tv/TvNavRail.jsx
try { (() => {
/**
 * TvNavRail — the left navigation rail for the 10-foot UI. Collapsed to an icon
 * column; expands to reveal labels when any item is focused or hovered (the
 * standard Android TV leanback pattern). Active destination gets a tonal pill.
 */
function TvNavRail({
  items = [],
  active,
  onChange,
  profile = "IM",
  style = {}
}) {
  const [open, setOpen] = React.useState(false);
  return /*#__PURE__*/React.createElement("nav", {
    onFocus: () => setOpen(true),
    onBlur: e => {
      if (!e.currentTarget.contains(e.relatedTarget)) setOpen(false);
    },
    onMouseEnter: () => setOpen(true),
    onMouseLeave: () => setOpen(false),
    style: {
      height: "100%",
      flexShrink: 0,
      width: open ? 248 : 88,
      transition: "width var(--duration-medium) var(--ease-standard)",
      background: open ? "linear-gradient(90deg, rgba(6,17,27,0.96) 0%, rgba(6,17,27,0.82) 70%, rgba(6,17,27,0) 100%)" : "transparent",
      display: "flex",
      flexDirection: "column",
      padding: "24px 20px",
      gap: 6,
      position: "absolute",
      left: 0,
      top: 0,
      bottom: 0,
      zIndex: 20,
      boxSizing: "border-box",
      ...style
    }
  }, /*#__PURE__*/React.createElement("div", {
    style: {
      display: "flex",
      alignItems: "center",
      gap: 14,
      height: 48,
      marginBottom: 18,
      paddingLeft: 4
    }
  }, /*#__PURE__*/React.createElement("span", {
    style: {
      width: 40,
      height: 40,
      borderRadius: "50%",
      background: "var(--secondary-container)",
      color: "var(--on-secondary-container)",
      display: "inline-flex",
      alignItems: "center",
      justifyContent: "center",
      fontWeight: 700,
      flexShrink: 0
    }
  }, profile), /*#__PURE__*/React.createElement("span", {
    style: {
      color: "var(--text-primary)",
      fontWeight: 600,
      whiteSpace: "nowrap",
      opacity: open ? 1 : 0,
      transition: "opacity var(--duration-fast)"
    }
  }, "Ignacio")), items.map(it => {
    const on = it.id === active;
    return /*#__PURE__*/React.createElement("button", {
      key: it.id,
      type: "button",
      tabIndex: 0,
      "data-focusable": "",
      onClick: () => onChange && onChange(it.id),
      onMouseEnter: e => e.currentTarget.focus(),
      className: "tv-rail-item",
      style: {
        display: "flex",
        alignItems: "center",
        gap: 16,
        height: 48,
        padding: "0 14px",
        borderRadius: "var(--radius-full)",
        border: "none",
        cursor: "pointer",
        width: "100%",
        background: on ? "var(--primary-container)" : "transparent",
        color: on ? "var(--on-primary-container)" : "var(--text-secondary)",
        transition: "background var(--duration-fast) var(--ease-standard)"
      }
    }, /*#__PURE__*/React.createElement(__ds_scope.Icon, {
      name: it.icon,
      size: 22,
      style: {
        flexShrink: 0
      }
    }), /*#__PURE__*/React.createElement("span", {
      style: {
        fontFamily: "var(--font-sans)",
        fontSize: 16,
        fontWeight: 500,
        whiteSpace: "nowrap",
        opacity: open ? 1 : 0,
        transition: "opacity var(--duration-fast)"
      }
    }, it.label));
  }), /*#__PURE__*/React.createElement("style", null, `.tv-rail-item:focus{outline:3px solid var(--primary);outline-offset:2px;background:var(--surface-container-high);color:var(--text-primary)}`));
}
Object.assign(__ds_scope, { TvNavRail });
})(); } catch (e) { __ds_ns.__errors.push({ path: "components/tv/TvNavRail.jsx", error: String((e && e.message) || e) }); }

// components/tv/TvTopBar.jsx
try { (() => {
/**
 * TvTopBar — the 10-foot home top header. Mirrors the real
 * HomeHeader.kt: a left server-switcher tile (logo + server name), animated
 * error / loading-retry chips, then Search · Settings · Close action chips.
 * Every chip is focusable (data-focusable) for D-pad navigation. Use this in
 * place of TvNavRail on Home, Search, Library — surfaces that need the
 * server identity + global actions at the top of frame.
 */
function TvTopBar({
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
  style = {}
}) {
  const chip = props => ({
    height: 64,
    padding: "0 22px",
    borderRadius: "var(--radius-full)",
    background: props.tone === "error" ? "var(--error-container)" : "var(--surface-container-high)",
    color: props.tone === "error" ? "var(--on-error-container)" : "var(--text-primary)",
    border: "none",
    cursor: "pointer",
    display: "inline-flex",
    alignItems: "center",
    gap: 10,
    fontFamily: "var(--font-sans)",
    fontSize: 16,
    fontWeight: 500
  });
  const Spin = () => /*#__PURE__*/React.createElement("span", {
    "aria-hidden": true,
    style: {
      width: 22,
      height: 22,
      borderRadius: "50%",
      border: "2.5px solid var(--text-disabled)",
      borderTopColor: "var(--primary)",
      animation: "sf-topbar-spin 0.9s linear infinite"
    }
  });
  return /*#__PURE__*/React.createElement("header", {
    style: {
      position: "absolute",
      top: 24,
      left: 56,
      right: 56,
      zIndex: 20,
      display: "flex",
      alignItems: "center",
      gap: 16,
      height: 64,
      ...style
    }
  }, /*#__PURE__*/React.createElement("button", {
    type: "button",
    tabIndex: 0,
    "data-focusable": "",
    "data-focus-first": "",
    onClick: onServerClick,
    onMouseEnter: e => e.currentTarget.focus(),
    className: "tv-topbar-chip",
    style: {
      ...chip({}),
      height: 64,
      padding: "0 22px 0 18px",
      maxWidth: 360,
      minWidth: 220,
      gap: 14
    }
  }, logoSrc ? /*#__PURE__*/React.createElement("img", {
    src: logoSrc,
    alt: "",
    style: {
      width: 30,
      height: 30,
      borderRadius: 7,
      flexShrink: 0
    },
    onError: e => {
      e.currentTarget.style.display = "none";
    }
  }) : null, /*#__PURE__*/React.createElement("span", {
    style: {
      flex: 1,
      fontSize: 20,
      fontWeight: 600,
      whiteSpace: "nowrap",
      overflow: "hidden",
      textOverflow: "ellipsis",
      textAlign: "left"
    }
  }, serverName), /*#__PURE__*/React.createElement(__ds_scope.Icon, {
    name: "chevron-down",
    size: 20,
    color: "var(--text-secondary)"
  })), /*#__PURE__*/React.createElement("span", {
    style: {
      flex: 1
    }
  }), isError ? /*#__PURE__*/React.createElement("button", {
    type: "button",
    tabIndex: 0,
    "data-focusable": "",
    onClick: onErrorClick,
    onMouseEnter: e => e.currentTarget.focus(),
    className: "tv-topbar-chip",
    style: chip({
      tone: "error"
    })
  }, /*#__PURE__*/React.createElement(__ds_scope.Icon, {
    name: "alert-circle",
    size: 20
  }), " Error") : null, isLoading || isError ? /*#__PURE__*/React.createElement("button", {
    type: "button",
    tabIndex: 0,
    "data-focusable": "",
    onClick: onRetryClick,
    disabled: isLoading,
    onMouseEnter: e => e.currentTarget.focus(),
    className: "tv-topbar-chip",
    style: chip({})
  }, isLoading ? /*#__PURE__*/React.createElement(React.Fragment, null, /*#__PURE__*/React.createElement(Spin, null), " Loading") : /*#__PURE__*/React.createElement(React.Fragment, null, /*#__PURE__*/React.createElement(__ds_scope.Icon, {
    name: "rotate-ccw",
    size: 20
  }), " Retry")) : null, /*#__PURE__*/React.createElement("button", {
    type: "button",
    tabIndex: 0,
    "data-focusable": "",
    onClick: onSearchClick,
    onMouseEnter: e => e.currentTarget.focus(),
    className: "tv-topbar-chip",
    style: chip({})
  }, /*#__PURE__*/React.createElement(__ds_scope.Icon, {
    name: "search",
    size: 22
  }), " Search"), user ? /*#__PURE__*/React.createElement("button", {
    type: "button",
    tabIndex: 0,
    "data-focusable": "",
    onClick: onUserClick,
    onMouseEnter: e => e.currentTarget.focus(),
    className: "tv-topbar-chip",
    "aria-label": "Switch user \u2014 " + (user.name || ""),
    style: {
      ...chip({}),
      padding: "0 22px 0 12px",
      gap: 12
    }
  }, user.avatar ? /*#__PURE__*/React.createElement("img", {
    src: user.avatar,
    alt: "",
    style: {
      width: 40,
      height: 40,
      borderRadius: "50%",
      objectFit: "cover",
      flexShrink: 0
    }
  }) : /*#__PURE__*/React.createElement("span", {
    style: {
      width: 40,
      height: 40,
      borderRadius: "50%",
      flexShrink: 0,
      background: user.color || "var(--surface-container-highest)",
      color: user.textColor || "#fff",
      fontSize: 15,
      fontWeight: 700,
      display: "inline-flex",
      alignItems: "center",
      justifyContent: "center"
    }
  }, user.initials || (user.name || "?")[0]), /*#__PURE__*/React.createElement("span", {
    style: {
      whiteSpace: "nowrap",
      overflow: "hidden",
      textOverflow: "ellipsis",
      maxWidth: 180
    }
  }, user.name), /*#__PURE__*/React.createElement(__ds_scope.Icon, {
    name: "chevron-down",
    size: 18,
    color: "var(--text-secondary)"
  })) : null, /*#__PURE__*/React.createElement("button", {
    type: "button",
    tabIndex: 0,
    "data-focusable": "",
    onClick: onSettingsClick,
    onMouseEnter: e => e.currentTarget.focus(),
    className: "tv-topbar-chip",
    style: chip({})
  }, /*#__PURE__*/React.createElement(__ds_scope.Icon, {
    name: "settings",
    size: 22
  }), " Settings"), /*#__PURE__*/React.createElement("button", {
    type: "button",
    tabIndex: 0,
    "data-focusable": "",
    onClick: onCloseClick,
    onMouseEnter: e => e.currentTarget.focus(),
    className: "tv-topbar-chip",
    style: chip({})
  }, /*#__PURE__*/React.createElement(__ds_scope.Icon, {
    name: "x",
    size: 22
  }), " Close"), /*#__PURE__*/React.createElement("style", null, `
        .tv-topbar-chip{ transition: outline-color var(--duration-fast), transform var(--duration-fast) var(--ease-standard), background var(--duration-fast); }
        .tv-topbar-chip:focus{ outline: 3px solid var(--primary); outline-offset: 3px; background: var(--primary); color: var(--on-primary); transform: scale(1.04); }
        @keyframes sf-topbar-spin { to { transform: rotate(360deg); } }
      `));
}
Object.assign(__ds_scope, { TvTopBar });
})(); } catch (e) { __ds_ns.__errors.push({ path: "components/tv/TvTopBar.jsx", error: String((e && e.message) || e) }); }

// components/tv/UpNextCard.jsx
try { (() => {
/**
 * UpNextCard — the autoplay card that slides in over the player as content ends.
 * Shows the next item's still, title, a "Up next" label and an auto-advancing
 * countdown ring around a Play Now button; a Cancel/secondary action dismisses.
 * The countdown is driven by the `seconds` + `remaining` props (the host owns
 * the timer); the ring fills as `remaining` drops.
 */
function UpNextCard({
  title,
  subtitle,
  still,
  seconds = 10,
  remaining = 10,
  onPlayNow,
  onCancel,
  style = {}
}) {
  const pct = Math.max(0, Math.min(1, remaining / seconds));
  const R = 26,
    C = 2 * Math.PI * R;
  return /*#__PURE__*/React.createElement("div", {
    style: {
      display: "flex",
      gap: 18,
      width: 460,
      padding: 16,
      background: "var(--glass-fill-strong)",
      WebkitBackdropFilter: "blur(var(--glass-blur))",
      backdropFilter: "blur(var(--glass-blur))",
      border: "1px solid var(--glass-border)",
      borderRadius: "var(--radius-lg)",
      boxShadow: "var(--shadow-glass)",
      ...style
    }
  }, /*#__PURE__*/React.createElement("div", {
    style: {
      width: 150,
      aspectRatio: "16 / 9",
      borderRadius: "var(--radius-md)",
      overflow: "hidden",
      flexShrink: 0,
      background: "var(--surface-container-low)"
    }
  }, still ? /*#__PURE__*/React.createElement("img", {
    src: still,
    alt: title,
    style: {
      width: "100%",
      height: "100%",
      objectFit: "cover"
    }
  }) : null), /*#__PURE__*/React.createElement("div", {
    style: {
      flex: 1,
      display: "flex",
      flexDirection: "column",
      minWidth: 0
    }
  }, /*#__PURE__*/React.createElement("div", {
    className: "m3-label-medium",
    style: {
      color: "var(--primary)",
      letterSpacing: 1,
      fontWeight: 700
    }
  }, "UP NEXT"), /*#__PURE__*/React.createElement("div", {
    className: "m3-title-large",
    style: {
      color: "var(--text-primary)",
      fontWeight: 700,
      marginTop: 2,
      whiteSpace: "nowrap",
      overflow: "hidden",
      textOverflow: "ellipsis"
    }
  }, title), subtitle ? /*#__PURE__*/React.createElement("div", {
    className: "m3-body-small",
    style: {
      color: "var(--text-secondary)",
      marginTop: 2
    }
  }, subtitle) : null, /*#__PURE__*/React.createElement("div", {
    style: {
      display: "flex",
      alignItems: "center",
      gap: 12,
      marginTop: "auto"
    }
  }, /*#__PURE__*/React.createElement("button", {
    type: "button",
    tabIndex: 0,
    "data-focusable": "",
    "data-focus-first": "",
    onClick: onPlayNow,
    onMouseEnter: e => e.currentTarget.focus(),
    className: "tv-upnext-play",
    style: {
      display: "inline-flex",
      alignItems: "center",
      gap: 10,
      height: 48,
      padding: "0 8px 0 4px",
      borderRadius: "var(--radius-full)",
      border: "none",
      background: "var(--primary)",
      color: "var(--on-primary)",
      fontSize: 16,
      fontWeight: 600,
      cursor: "pointer"
    }
  }, /*#__PURE__*/React.createElement("span", {
    style: {
      position: "relative",
      width: 40,
      height: 40,
      flexShrink: 0
    }
  }, /*#__PURE__*/React.createElement("svg", {
    width: "40",
    height: "40",
    style: {
      position: "absolute",
      inset: 0,
      transform: "rotate(-90deg)"
    }
  }, /*#__PURE__*/React.createElement("circle", {
    cx: "20",
    cy: "20",
    r: R,
    fill: "none",
    stroke: "rgba(0,0,0,0.2)",
    strokeWidth: "3"
  }), /*#__PURE__*/React.createElement("circle", {
    cx: "20",
    cy: "20",
    r: R,
    fill: "none",
    stroke: "var(--on-primary)",
    strokeWidth: "3",
    strokeDasharray: C,
    strokeDashoffset: C * (1 - pct),
    strokeLinecap: "round"
  })), /*#__PURE__*/React.createElement("span", {
    style: {
      position: "absolute",
      inset: 0,
      display: "flex",
      alignItems: "center",
      justifyContent: "center"
    }
  }, /*#__PURE__*/React.createElement(__ds_scope.Icon, {
    name: "play",
    size: 18
  }))), "Play Now \xB7 ", Math.ceil(remaining), "s"), /*#__PURE__*/React.createElement("button", {
    type: "button",
    tabIndex: 0,
    "data-focusable": "",
    onClick: onCancel,
    onMouseEnter: e => e.currentTarget.focus(),
    className: "tv-upnext-cancel",
    style: {
      height: 48,
      padding: "0 20px",
      borderRadius: "var(--radius-full)",
      border: "1px solid var(--border-strong)",
      background: "transparent",
      color: "var(--text-secondary)",
      fontSize: 16,
      fontWeight: 600,
      cursor: "pointer"
    }
  }, "Cancel"))), /*#__PURE__*/React.createElement("style", null, `.tv-upnext-play:focus,.tv-upnext-cancel:focus{outline:3px solid var(--primary);outline-offset:3px}
        .tv-upnext-cancel:focus{background:var(--surface-container-high);color:var(--text-primary)}`));
}
Object.assign(__ds_scope, { UpNextCard });
})(); } catch (e) { __ds_ns.__errors.push({ path: "components/tv/UpNextCard.jsx", error: String((e && e.message) || e) }); }

// components/voice/VoiceFab.jsx
try { (() => {
/**
 * VoiceFab — the primary assistant affordance on Beam (phone): a 56dp mic FAB.
 * Pulses a ring while listening; tapping while busy cancels. On XR the same
 * intent is a mic IconButton in the orbiter, and a near-face open-palm hold.
 */
function VoiceFab({
  state = "idle",
  onClick,
  size = 56,
  style = {}
}) {
  const listening = state === "listening";
  const processing = state === "processing";
  return /*#__PURE__*/React.createElement("button", {
    type: "button",
    "aria-label": "Voice assistant",
    onClick: onClick,
    style: {
      position: "relative",
      width: size,
      height: size,
      borderRadius: "var(--radius-full)",
      border: "none",
      cursor: "pointer",
      background: listening ? "var(--accent)" : "var(--accent-container)",
      color: listening ? "var(--on-primary)" : "var(--on-accent-container)",
      boxShadow: "var(--elevation-3)",
      display: "inline-flex",
      alignItems: "center",
      justifyContent: "center",
      transition: "background var(--duration-medium) var(--ease-standard)",
      ...style
    }
  }, listening ? /*#__PURE__*/React.createElement("span", {
    style: {
      position: "absolute",
      inset: -6,
      borderRadius: "var(--radius-full)",
      border: "3px solid var(--accent)",
      opacity: 0.6,
      animation: "sf-voice-pulse 1.4s var(--ease-standard) infinite"
    }
  }) : null, /*#__PURE__*/React.createElement(__ds_scope.Icon, {
    name: processing ? "loader" : "mic",
    size: size * 0.42,
    style: processing ? {
      animation: "sf-voice-spin 1s linear infinite"
    } : undefined
  }), /*#__PURE__*/React.createElement("style", null, `
        @keyframes sf-voice-pulse { 0%{transform:scale(1);opacity:.6} 70%{transform:scale(1.35);opacity:0} 100%{opacity:0} }
        @keyframes sf-voice-spin { to { transform: rotate(360deg) } }
      `));
}
Object.assign(__ds_scope, { VoiceFab });
})(); } catch (e) { __ds_ns.__errors.push({ path: "components/voice/VoiceFab.jsx", error: String((e && e.message) || e) }); }

// components/voice/VoiceFeedback.jsx
try { (() => {
/**
 * VoiceFeedback — the assistant state indicator. On Beam it anchors top-center
 * (never under the FAB); in XR it's a spatial overlay. Renders identically in
 * INTENT across surfaces (DESIGN.md voice-parity rule). States: listening,
 * processing, answered, error.
 */
const CONFIG = {
  listening: {
    icon: "mic",
    tint: "var(--accent)",
    label: "Listening…"
  },
  processing: {
    icon: "loader",
    tint: "var(--tertiary)",
    label: "Thinking…"
  },
  answered: {
    icon: "sparkles",
    tint: "var(--tertiary)",
    label: "Here you go"
  },
  error: {
    icon: "triangle-alert",
    tint: "var(--error)",
    label: "Didn't catch that"
  }
};
function VoiceFeedback({
  state = "listening",
  text,
  style = {}
}) {
  const c = CONFIG[state] || CONFIG.listening;
  return /*#__PURE__*/React.createElement("div", {
    role: "status",
    style: {
      display: "inline-flex",
      alignItems: "center",
      gap: 12,
      maxWidth: 520,
      padding: "12px 18px 12px 14px",
      background: "var(--glass-fill-strong)",
      WebkitBackdropFilter: "blur(var(--glass-blur))",
      backdropFilter: "blur(var(--glass-blur))",
      border: "1px solid var(--glass-border)",
      borderRadius: "var(--radius-full)",
      boxShadow: "var(--shadow-glass)",
      color: "var(--text-primary)",
      ...style
    }
  }, /*#__PURE__*/React.createElement("span", {
    style: {
      width: 34,
      height: 34,
      borderRadius: "var(--radius-full)",
      flexShrink: 0,
      display: "inline-flex",
      alignItems: "center",
      justifyContent: "center",
      background: "color-mix(in oklab, " + c.tint + " 22%, transparent)",
      color: c.tint
    }
  }, /*#__PURE__*/React.createElement(__ds_scope.Icon, {
    name: c.icon,
    size: 18,
    style: state === "processing" ? {
      animation: "sf-vf-spin 1s linear infinite"
    } : undefined
  })), /*#__PURE__*/React.createElement("span", {
    className: "m3-body-medium",
    style: {
      color: text ? "var(--text-primary)" : "var(--text-secondary)"
    }
  }, text || c.label), /*#__PURE__*/React.createElement("style", null, `@keyframes sf-vf-spin { to { transform: rotate(360deg) } }`));
}
Object.assign(__ds_scope, { VoiceFeedback });
})(); } catch (e) { __ds_ns.__errors.push({ path: "components/voice/VoiceFeedback.jsx", error: String((e && e.message) || e) }); }

// ui_kits/beam/BeamApp.jsx
try { (() => {
// Beam (phone) — app shell: nav + routing + voice + cast/syncplay/server sheets
function BeamApp() {
  const NS = window.SpatialFinDesignSystem_0d3fe7;
  const {
    NavBar,
    VoiceFab,
    ServerPickerSheet
  } = NS;
  const [route, setRoute] = React.useState({
    screen: "home",
    item: null
  });
  const [tab, setTab] = React.useState("home");
  const [voice, setVoice] = React.useState("idle");
  const scroller = React.useRef(null);

  // ---- app-level cast / syncplay / server state ----
  const devices = window.SF_CAST_DEVICES || [];
  const [castVideo, setCastVideo] = React.useState("this");
  const [castAudio, setCastAudio] = React.useState(null); // null = follow video
  const [syncIds, setSyncIds] = React.useState([]); // multi-room speakers
  const [syncGroup, setSyncGroup] = React.useState(null); // SyncPlay watch-together
  const [currentServer, setCurrentServer] = React.useState(window.SF_CURRENT_SERVER || "home");
  const [currentUser, setCurrentUser] = React.useState(window.SF_CURRENT_USER || "ignacio");
  const [appSheet, setAppSheet] = React.useState(null); // "cast" | "syncplay" | "server" | "user"

  const servers = window.SF_SERVERS || [];
  const serverName = (servers.find(s => s.id === currentServer) || {
    name: "Jellyfin"
  }).name;
  const user = (window.SF_USERS || []).find(u => u.id === currentUser) || (window.SF_USERS || [])[0];
  const videoDev = devices.find(d => d.id === castVideo) || devices[0];
  const audioDev = castAudio ? devices.find(d => d.id === castAudio) : null;
  const castSplit = !!castAudio && castAudio !== castVideo;
  const castActive = castVideo !== "this" || !!castAudio || syncIds.length > 0;
  const castLabel = castVideo !== "this" ? videoDev && videoDev.name : audioDev ? audioDev.name : "This device";
  const syncActive = !!syncGroup;
  const toggleSync = id => setSyncIds(cur => cur.includes(id) ? cur.filter(x => x !== id) : [...cur, id]);
  const open = (item, screen) => {
    setRoute({
      screen,
      item
    });
    if (scroller.current) scroller.current.scrollTop = 0;
  };
  const goTab = id => {
    setTab(id);
    setRoute({
      screen: id,
      item: null
    });
    if (scroller.current) scroller.current.scrollTop = 0;
  };
  const openDetail = item => open(item, "detail");

  // voice demo: idle -> listening -> processing -> answered -> idle
  const cycleVoice = () => {
    if (voice !== "idle") {
      setVoice("idle");
      return;
    }
    setVoice("listening");
    setTimeout(() => setVoice("processing"), 1600);
    setTimeout(() => setVoice("answered"), 3000);
    setTimeout(() => setVoice("idle"), 5200);
  };
  const inPlayer = route.screen === "player";
  const isTabScreen = ["home", "search", "sources", "downloads", "settings"].includes(route.screen);
  return /*#__PURE__*/React.createElement("div", {
    style: {
      position: "relative",
      width: "100%",
      height: "100%",
      display: "flex",
      flexDirection: "column",
      background: "var(--surface-app)",
      overflow: "hidden"
    }
  }, /*#__PURE__*/React.createElement("div", {
    ref: scroller,
    style: {
      flex: 1,
      overflowY: "auto",
      position: "relative",
      scrollbarWidth: "none"
    }
  }, route.screen === "home" && /*#__PURE__*/React.createElement(window.BeamHome, {
    onOpen: open,
    serverName: serverName,
    castActive: castActive,
    castLabel: castLabel,
    user: user,
    onOpenServer: () => setAppSheet("server"),
    onOpenCast: () => setAppSheet("cast"),
    onOpenUser: () => setAppSheet("user")
  }), route.screen === "search" && /*#__PURE__*/React.createElement(window.BeamSearch, {
    onOpen: openDetail
  }), route.screen === "sources" && /*#__PURE__*/React.createElement(window.BeamSources, {
    onOpen: openDetail
  }), route.screen === "downloads" && /*#__PURE__*/React.createElement(window.BeamDownloads, {
    onOpen: openDetail
  }), route.screen === "settings" && /*#__PURE__*/React.createElement(window.BeamSettings, {
    serverName: serverName,
    user: user,
    castLabel: castActive ? castLabel : "This device",
    onOpenServer: () => setAppSheet("server"),
    onOpenCast: () => setAppSheet("cast"),
    onOpenUser: () => setAppSheet("user")
  }), route.screen === "detail" && /*#__PURE__*/React.createElement(window.BeamDetail, {
    item: route.item,
    onBack: () => goTab("home"),
    onPlay: () => open(route.item, "player"),
    onOpenCast: () => setAppSheet("cast"),
    onOpenSyncPlay: () => setAppSheet("syncplay"),
    castActive: castActive,
    castLabel: castLabel
  }), route.screen === "player" && /*#__PURE__*/React.createElement(window.BeamPlayer, {
    item: route.item,
    onBack: () => setRoute({
      screen: "detail",
      item: route.item
    }),
    voiceState: voice,
    onVoice: cycleVoice,
    onOpenCast: () => setAppSheet("cast"),
    castActive: castActive,
    castLabel: castLabel,
    castSplit: castSplit,
    onOpenSyncPlay: () => setAppSheet("syncplay"),
    syncActive: syncActive
  })), !inPlayer ? /*#__PURE__*/React.createElement("div", {
    style: {
      position: "absolute",
      right: 18,
      bottom: 92,
      zIndex: 5
    }
  }, /*#__PURE__*/React.createElement(VoiceFab, {
    state: voice,
    onClick: cycleVoice
  })) : null, !inPlayer && voice !== "idle" ? /*#__PURE__*/React.createElement("div", {
    style: {
      position: "absolute",
      top: 64,
      left: 0,
      right: 0,
      display: "flex",
      justifyContent: "center",
      zIndex: 6,
      padding: "0 16px"
    }
  }, /*#__PURE__*/React.createElement(NS.VoiceFeedback, {
    state: voice === "listening" ? "listening" : voice === "processing" ? "processing" : "answered",
    text: voice === "answered" ? "Starting Big Buck Bunny." : undefined
  })) : null, !inPlayer ? /*#__PURE__*/React.createElement(NavBar, {
    active: tab,
    onChange: goTab,
    items: [{
      id: "home",
      icon: "house",
      label: "Home"
    }, {
      id: "search",
      icon: "search",
      label: "Search"
    }, {
      id: "sources",
      icon: "puzzle",
      label: "Sources"
    }, {
      id: "downloads",
      icon: "download",
      label: "Downloads"
    }, {
      id: "settings",
      icon: "settings",
      label: "Settings"
    }]
  }) : null, /*#__PURE__*/React.createElement(window.CastSheet, {
    open: appSheet === "cast",
    videoId: castVideo,
    audioId: castAudio,
    syncIds: syncIds,
    onClose: () => setAppSheet(null),
    onPickVideo: setCastVideo,
    onPickAudio: setCastAudio,
    onToggleSync: toggleSync
  }), /*#__PURE__*/React.createElement(window.SyncPlaySheet, {
    open: appSheet === "syncplay",
    group: syncGroup,
    onClose: () => setAppSheet(null),
    onJoin: g => {
      setSyncGroup(g);
      setAppSheet(null);
    },
    onLeave: () => {
      setSyncGroup(null);
      setAppSheet(null);
    },
    onCreate: () => {
      setSyncGroup({
        id: "new",
        name: "My Room",
        members: 1,
        ping: 18
      });
    }
  }), ServerPickerSheet ? /*#__PURE__*/React.createElement(ServerPickerSheet, {
    open: appSheet === "server",
    servers: servers,
    currentId: currentServer,
    onPick: setCurrentServer,
    onManage: () => {},
    onDismiss: () => setAppSheet(null),
    style: {
      position: "absolute"
    }
  }) : null, /*#__PURE__*/React.createElement(window.UserPickerSheet, {
    open: appSheet === "user",
    currentId: currentUser,
    onPick: setCurrentUser,
    onClose: () => setAppSheet(null)
  }));
}
window.BeamApp = BeamApp;
})(); } catch (e) { __ds_ns.__errors.push({ path: "ui_kits/beam/BeamApp.jsx", error: String((e && e.message) || e) }); }

// ui_kits/beam/DetailScreen.jsx
try { (() => {
// Beam (phone) — Detail screen.
// Action set mirrors the SpatialFin app's MovieScreen / EpisodeScreen:
//   Primary row : Play (or Resume), Restart, Favorite, Mark watched, Download, More (…)
//   More sheet  : SyncPlay, Playback options, Go to series, Go to season,
//                 Edit external IDs, Cast & audio, Refresh metadata, Share, Delete.
function BeamDetail({
  item,
  onBack,
  onPlay,
  onOpenCast,
  onOpenSyncPlay,
  castActive,
  castLabel
}) {
  const NS = window.SpatialFinDesignSystem_0d3fe7;
  const {
    Pill,
    Button,
    IconButton,
    Icon
  } = NS;
  const [fav, setFav] = React.useState(false);
  const [watched, setWatched] = React.useState(false);
  const [moreOpen, setMoreOpen] = React.useState(false);
  const [sheet, setSheet] = React.useState(null); // playback-options & external-ids inline sheets

  const resumeable = (item.progress || 0) > 0;
  const isEpisode = !!item.seriesTitle;

  // Overflow menu items — `admin` rows are styled the same; in a real shell
  // they'd be filtered by the current user's permissions.
  const overflow = [{
    id: "syncplay",
    icon: "users",
    title: "SyncPlay",
    subtitle: "Watch in sync with others",
    onClick: () => {
      setMoreOpen(false);
      onOpenSyncPlay && onOpenSyncPlay();
    }
  }, {
    id: "playback",
    icon: "sliders",
    title: "Playback options",
    subtitle: "Default subtitle & audio tracks",
    onClick: () => {
      setMoreOpen(false);
      setSheet("playback");
    }
  }, isEpisode ? {
    id: "series",
    icon: "tv",
    title: "Go to series",
    subtitle: item.seriesTitle,
    onClick: () => setMoreOpen(false)
  } : null, isEpisode ? {
    id: "season",
    icon: "list",
    title: "Go to season",
    subtitle: "Season " + (item.season || 1),
    onClick: () => setMoreOpen(false)
  } : null, {
    id: "extids",
    icon: "id-card",
    title: "Edit external IDs",
    subtitle: "TMDB · IMDB · TVDB",
    admin: true,
    onClick: () => {
      setMoreOpen(false);
      setSheet("extids");
    }
  }, {
    id: "cast",
    icon: "cast",
    title: "Cast & audio output",
    subtitle: castActive ? "On " + castLabel : "This device",
    onClick: () => {
      setMoreOpen(false);
      onOpenCast && onOpenCast();
    }
  }, {
    id: "refresh",
    icon: "refresh-cw",
    title: "Refresh metadata",
    subtitle: "Re-scan from providers",
    admin: true,
    onClick: () => setMoreOpen(false)
  }, {
    id: "share",
    icon: "share-2",
    title: "Share",
    subtitle: "Send a deeplink",
    onClick: () => setMoreOpen(false)
  }, {
    id: "delete",
    icon: "trash-2",
    title: "Delete",
    subtitle: "Remove from library",
    danger: true,
    admin: true,
    onClick: () => setMoreOpen(false)
  }].filter(Boolean);
  return /*#__PURE__*/React.createElement("div", {
    style: {
      paddingBottom: 24
    }
  }, /*#__PURE__*/React.createElement("div", {
    style: {
      position: "relative",
      height: 280
    }
  }, /*#__PURE__*/React.createElement("img", {
    src: item.backdrop,
    alt: item.title,
    style: {
      width: "100%",
      height: "100%",
      objectFit: "cover"
    }
  }), /*#__PURE__*/React.createElement("div", {
    style: {
      position: "absolute",
      inset: 0,
      background: "var(--scrim-gradient)"
    }
  }), /*#__PURE__*/React.createElement("div", {
    style: {
      position: "absolute",
      top: 12,
      left: 12
    }
  }, /*#__PURE__*/React.createElement(IconButton, {
    icon: "arrow-left",
    variant: "glass",
    label: "Back",
    onClick: onBack
  })), /*#__PURE__*/React.createElement("div", {
    style: {
      position: "absolute",
      left: 16,
      right: 16,
      bottom: 14
    }
  }, isEpisode ? /*#__PURE__*/React.createElement("div", {
    className: "m3-label-large",
    style: {
      color: "rgba(255,255,255,0.85)",
      marginBottom: 4
    }
  }, item.seriesTitle, " \xB7 S", item.season, " \xB7 E", item.episode) : null, /*#__PURE__*/React.createElement("div", {
    className: "m3-display-small",
    style: {
      color: "#fff",
      fontWeight: 700
    }
  }, item.title), /*#__PURE__*/React.createElement("div", {
    style: {
      display: "flex",
      gap: 8,
      marginTop: 10,
      flexWrap: "wrap"
    }
  }, /*#__PURE__*/React.createElement(Pill, {
    tone: "rating",
    icon: "star"
  }, item.stars), /*#__PURE__*/React.createElement(Pill, null, item.year), /*#__PURE__*/React.createElement(Pill, null, item.runtime), /*#__PURE__*/React.createElement(Pill, null, item.rating), item.tags.map(t => /*#__PURE__*/React.createElement(Pill, {
    key: t,
    tone: "outline"
  }, t))))), /*#__PURE__*/React.createElement("div", {
    style: {
      padding: "14px 16px 0",
      display: "flex",
      flexDirection: "column",
      gap: 18
    }
  }, /*#__PURE__*/React.createElement("div", {
    style: {
      display: "flex",
      gap: 8,
      alignItems: "center",
      flexWrap: "wrap"
    }
  }, /*#__PURE__*/React.createElement("div", {
    style: {
      flex: 1,
      minWidth: 160,
      display: "flex",
      gap: 8
    }
  }, /*#__PURE__*/React.createElement(Button, {
    variant: "filled",
    icon: "play",
    onClick: onPlay,
    style: {
      flex: 1
    }
  }, resumeable ? "Resume" : "Play"), resumeable ? /*#__PURE__*/React.createElement(IconButton, {
    icon: "rotate-ccw",
    variant: "tonal",
    label: "Restart from beginning",
    onClick: onPlay
  }) : null), /*#__PURE__*/React.createElement(IconButton, {
    icon: fav ? "heart" : "heart",
    variant: fav ? "filled" : "outlined",
    label: "Favorite",
    onClick: () => setFav(!fav)
  }), /*#__PURE__*/React.createElement(IconButton, {
    icon: watched ? "check-check" : "check",
    variant: watched ? "filled" : "outlined",
    label: "Mark watched",
    onClick: () => setWatched(!watched)
  }), /*#__PURE__*/React.createElement(IconButton, {
    icon: "download",
    variant: "outlined",
    label: "Download"
  }), /*#__PURE__*/React.createElement(IconButton, {
    icon: "ellipsis",
    variant: "outlined",
    label: "More",
    onClick: () => setMoreOpen(true)
  })), resumeable ? /*#__PURE__*/React.createElement("div", null, /*#__PURE__*/React.createElement("div", {
    style: {
      display: "flex",
      justifyContent: "space-between",
      marginBottom: 6
    }
  }, /*#__PURE__*/React.createElement("span", {
    className: "m3-label-medium",
    style: {
      color: "var(--text-secondary)"
    }
  }, Math.round(item.progress || 0), "% watched \xB7 ", item.remaining || "32 min left")), /*#__PURE__*/React.createElement(NS.ProgressBar, {
    value: item.progress || 0,
    height: 5
  })) : null, /*#__PURE__*/React.createElement("div", {
    className: "m3-body-large",
    style: {
      color: "var(--text-secondary)"
    }
  }, item.overview), /*#__PURE__*/React.createElement("div", {
    style: {
      display: "grid",
      gridTemplateColumns: "1fr 1fr",
      gap: 16
    }
  }, /*#__PURE__*/React.createElement("div", null, /*#__PURE__*/React.createElement("div", {
    className: "m3-label-large",
    style: {
      color: "var(--text-secondary)",
      letterSpacing: 0.5,
      marginBottom: 6
    }
  }, "GENRES"), /*#__PURE__*/React.createElement("div", {
    className: "m3-body-medium",
    style: {
      color: "var(--text-primary)"
    }
  }, item.genres)), item.director ? /*#__PURE__*/React.createElement("div", null, /*#__PURE__*/React.createElement("div", {
    className: "m3-label-large",
    style: {
      color: "var(--text-secondary)",
      letterSpacing: 0.5,
      marginBottom: 6
    }
  }, "DIRECTOR"), /*#__PURE__*/React.createElement("div", {
    className: "m3-body-medium",
    style: {
      color: "var(--text-primary)"
    }
  }, item.director)) : null), /*#__PURE__*/React.createElement("div", null, /*#__PURE__*/React.createElement("div", {
    className: "m3-label-large",
    style: {
      color: "var(--text-secondary)",
      letterSpacing: 0.5,
      marginBottom: 10
    }
  }, "CAST & CREW"), /*#__PURE__*/React.createElement("div", {
    style: {
      display: "flex",
      gap: 16
    }
  }, ["Proog", "Emo", "Director"].map((c, i) => /*#__PURE__*/React.createElement("div", {
    key: c,
    style: {
      display: "flex",
      flexDirection: "column",
      alignItems: "center",
      gap: 6,
      width: 72
    }
  }, /*#__PURE__*/React.createElement("span", {
    style: {
      width: 56,
      height: 56,
      borderRadius: "50%",
      background: ["#3C4758", "#543F5E", "#1F4876"][i],
      display: "inline-flex",
      alignItems: "center",
      justifyContent: "center",
      color: "#fff",
      fontWeight: 600
    }
  }, c[0]), /*#__PURE__*/React.createElement("span", {
    className: "m3-body-small",
    style: {
      color: "var(--text-secondary)",
      textAlign: "center"
    }
  }, c)))))), /*#__PURE__*/React.createElement(window.BeamSheet, {
    open: moreOpen,
    title: "More actions",
    subtitle: item.title,
    onClose: () => setMoreOpen(false)
  }, overflow.map(row => /*#__PURE__*/React.createElement(window.BeamOptionRow, {
    key: row.id,
    icon: row.icon,
    title: row.title,
    subtitle: row.subtitle,
    onClick: row.onClick,
    trailing: row.admin ? /*#__PURE__*/React.createElement(Pill, {
      tone: "outline"
    }, "admin") : /*#__PURE__*/React.createElement(Icon, {
      name: "chevron-right",
      size: 18,
      color: "var(--text-secondary)"
    })
  }))), /*#__PURE__*/React.createElement(window.BeamSheet, {
    open: sheet === "playback",
    title: "Playback options",
    subtitle: "Defaults for this title",
    onClose: () => setSheet(null)
  }, /*#__PURE__*/React.createElement(window.BeamOptionRow, {
    icon: "captions",
    title: "Default subtitle",
    subtitle: "English (SDH)"
  }), /*#__PURE__*/React.createElement(window.BeamOptionRow, {
    icon: "audio-lines",
    title: "Default audio",
    subtitle: "English 5.1"
  }), /*#__PURE__*/React.createElement(window.BeamOptionRow, {
    icon: "gauge",
    title: "Default quality",
    subtitle: "Auto \xB7 up to 4K"
  }), /*#__PURE__*/React.createElement(window.BeamOptionRow, {
    icon: "play",
    title: "Autoplay next",
    subtitle: "On"
  })), /*#__PURE__*/React.createElement(window.BeamSheet, {
    open: sheet === "extids",
    title: "Edit external IDs",
    subtitle: "Used for metadata sync",
    onClose: () => setSheet(null),
    footer: /*#__PURE__*/React.createElement(Button, {
      variant: "filled",
      fullWidth: true,
      icon: "check"
    }, "Save")
  }, [{
    id: "tmdb",
    label: "TMDB",
    value: "10378"
  }, {
    id: "imdb",
    label: "IMDB",
    value: "tt1254207"
  }, {
    id: "tvdb",
    label: "TVDB",
    value: "—"
  }].map(row => /*#__PURE__*/React.createElement("div", {
    key: row.id,
    style: {
      padding: "10px 14px"
    }
  }, /*#__PURE__*/React.createElement("div", {
    className: "m3-label-medium",
    style: {
      color: "var(--text-secondary)",
      marginBottom: 6
    }
  }, row.label), /*#__PURE__*/React.createElement("div", {
    style: {
      display: "flex",
      alignItems: "center",
      gap: 10,
      padding: "0 14px",
      height: 46,
      borderRadius: 14,
      background: "var(--surface-container-high)"
    }
  }, /*#__PURE__*/React.createElement("input", {
    defaultValue: row.value,
    style: {
      flex: 1,
      border: "none",
      outline: "none",
      background: "transparent",
      color: "var(--text-primary)",
      fontSize: 15,
      fontFamily: "var(--font-sans)"
    }
  }))))));
}
window.BeamDetail = BeamDetail;
})(); } catch (e) { __ds_ns.__errors.push({ path: "ui_kits/beam/DetailScreen.jsx", error: String((e && e.message) || e) }); }

// ui_kits/beam/HomeScreen.jsx
try { (() => {
// Beam (phone) — Home screen
function BeamHome({
  onOpen,
  serverName,
  castActive,
  castLabel,
  user,
  onOpenServer,
  onOpenCast,
  onOpenUser
}) {
  const NS = window.SpatialFinDesignSystem_0d3fe7;
  const {
    HeroBanner,
    SectionHeader,
    PosterCard,
    Badge,
    IconButton,
    Button,
    Icon
  } = NS;
  const F = window.SF_FEATURED,
    cat = window.SF_CATALOG;
  const cont = cat.filter(m => m.progress > 0);
  return /*#__PURE__*/React.createElement("div", {
    style: {
      paddingBottom: 16
    }
  }, /*#__PURE__*/React.createElement("div", {
    style: {
      display: "flex",
      alignItems: "center",
      gap: 10,
      padding: "14px 14px 8px"
    }
  }, /*#__PURE__*/React.createElement("img", {
    src: "../../assets/logo-mark.png",
    alt: "",
    style: {
      width: 30,
      height: 30,
      borderRadius: 8
    }
  }), /*#__PURE__*/React.createElement("button", {
    type: "button",
    onClick: onOpenServer,
    style: {
      display: "flex",
      flexDirection: "column",
      alignItems: "flex-start",
      gap: 0,
      flex: 1,
      minWidth: 0,
      background: "transparent",
      border: "none",
      cursor: "pointer",
      padding: 0,
      textAlign: "left"
    }
  }, /*#__PURE__*/React.createElement("span", {
    className: "m3-title-large",
    style: {
      color: "var(--text-primary)",
      fontWeight: 700,
      lineHeight: 1.1
    }
  }, "SpatialFin"), /*#__PURE__*/React.createElement("span", {
    style: {
      display: "flex",
      alignItems: "center",
      gap: 3,
      color: "var(--text-secondary)",
      fontSize: 12,
      maxWidth: "100%",
      whiteSpace: "nowrap",
      overflow: "hidden"
    }
  }, /*#__PURE__*/React.createElement("span", {
    style: {
      overflow: "hidden",
      textOverflow: "ellipsis"
    }
  }, serverName || "Jellyfin"), " ", /*#__PURE__*/React.createElement(Icon, {
    name: "chevron-down",
    size: 13
  }))), /*#__PURE__*/React.createElement(IconButton, {
    icon: "cast",
    variant: castActive ? "filled" : "ghost",
    label: castActive ? "Casting to " + castLabel : "Cast",
    size: "sm",
    onClick: onOpenCast
  }), /*#__PURE__*/React.createElement("button", {
    type: "button",
    onClick: onOpenUser,
    "aria-label": "Switch user — " + (user && user.name || ""),
    style: {
      border: "none",
      background: "transparent",
      padding: 0,
      cursor: "pointer",
      display: "inline-flex",
      borderRadius: "50%"
    }
  }, /*#__PURE__*/React.createElement(window.BeamUserAvatar, {
    user: user,
    size: 34
  }))), /*#__PURE__*/React.createElement("div", {
    style: {
      padding: "0 16px"
    }
  }, /*#__PURE__*/React.createElement(HeroBanner, {
    title: F.title,
    kind: F.kind,
    backdrop: F.backdrop,
    meta: [F.year, F.runtime, ...F.tags],
    height: 300,
    actions: /*#__PURE__*/React.createElement(React.Fragment, null, /*#__PURE__*/React.createElement(Button, {
      variant: "filled",
      icon: "play",
      onClick: () => onOpen(F, "player")
    }, "Play"), /*#__PURE__*/React.createElement(Button, {
      variant: "glass",
      onClick: () => onOpen(F, "detail")
    }, "Details"))
  })), /*#__PURE__*/React.createElement("div", {
    style: {
      padding: "22px 0 0 16px"
    }
  }, /*#__PURE__*/React.createElement(SectionHeader, {
    title: "Suggestions"
  }), /*#__PURE__*/React.createElement("div", {
    style: {
      display: "flex",
      gap: 14,
      overflowX: "auto",
      padding: "14px 16px 6px 0",
      scrollbarWidth: "none"
    }
  }, cat.map(m => /*#__PURE__*/React.createElement("div", {
    key: m.id,
    style: {
      flex: "0 0 auto"
    }
  }, /*#__PURE__*/React.createElement(PosterCard, {
    title: m.title,
    subtitle: `Movie${m.progress ? " · " + m.progress + "% watched" : ""}`,
    poster: m.poster,
    progress: m.progress || null,
    width: 132,
    badge: m.downloaded ? /*#__PURE__*/React.createElement(Badge, {
      tone: "accent",
      icon: "download"
    }) : null,
    onClick: () => onOpen(m, "detail")
  }))))), /*#__PURE__*/React.createElement("div", {
    style: {
      padding: "10px 0 0 16px"
    }
  }, /*#__PURE__*/React.createElement(SectionHeader, {
    title: "Continue Watching"
  }), /*#__PURE__*/React.createElement("div", {
    style: {
      display: "flex",
      gap: 14,
      overflowX: "auto",
      padding: "14px 16px 6px 0",
      scrollbarWidth: "none"
    }
  }, cont.map(m => /*#__PURE__*/React.createElement("div", {
    key: m.id,
    style: {
      flex: "0 0 auto"
    }
  }, /*#__PURE__*/React.createElement(PosterCard, {
    title: m.title,
    subtitle: `${m.progress}% watched`,
    poster: m.poster,
    progress: m.progress,
    width: 150,
    onClick: () => onOpen(m, "player")
  }))))));
}
window.BeamHome = BeamHome;
})(); } catch (e) { __ds_ns.__errors.push({ path: "ui_kits/beam/HomeScreen.jsx", error: String((e && e.message) || e) }); }

// ui_kits/beam/MoreScreens.jsx
try { (() => {
// Beam (phone) — Sources, Search, Downloads, Settings screens.
(function () {
  const NS = window.SpatialFinDesignSystem_0d3fe7;
  function TopBar({
    title,
    right
  }) {
    return /*#__PURE__*/React.createElement("div", {
      style: {
        display: "flex",
        alignItems: "center",
        gap: 12,
        padding: "16px 16px 8px"
      }
    }, /*#__PURE__*/React.createElement("span", {
      className: "m3-headline-small",
      style: {
        color: "var(--text-primary)",
        fontWeight: 700,
        flex: 1
      }
    }, title), right);
  }

  // ---- Sources (universal plugins) ---------------------------------------
  function BeamSources({
    onOpen
  }) {
    const {
      SectionHeader,
      PosterCard,
      Icon,
      Pill
    } = NS;
    const sources = window.SF_BEAM_SOURCES || [];
    return /*#__PURE__*/React.createElement("div", {
      style: {
        paddingBottom: 24
      }
    }, /*#__PURE__*/React.createElement(TopBar, {
      title: "Sources",
      right: /*#__PURE__*/React.createElement(Pill, {
        tone: "accent",
        icon: "puzzle"
      }, sources.length, " plugins")
    }), /*#__PURE__*/React.createElement("div", {
      style: {
        padding: "0 16px 6px",
        color: "var(--text-secondary)"
      },
      className: "m3-body-medium"
    }, "Browse content from your connected Jellyfin plugins and on-device files."), sources.map(src => /*#__PURE__*/React.createElement("div", {
      key: src.id,
      style: {
        padding: "16px 0 2px 16px"
      }
    }, /*#__PURE__*/React.createElement("div", {
      style: {
        display: "flex",
        alignItems: "center",
        gap: 12,
        paddingRight: 16,
        marginBottom: 4
      }
    }, /*#__PURE__*/React.createElement("span", {
      style: {
        width: 36,
        height: 36,
        borderRadius: 10,
        background: src.tint,
        color: "#fff",
        display: "inline-flex",
        alignItems: "center",
        justifyContent: "center",
        flexShrink: 0
      }
    }, /*#__PURE__*/React.createElement(Icon, {
      name: src.icon,
      size: 20
    })), /*#__PURE__*/React.createElement("div", {
      style: {
        flex: 1,
        minWidth: 0
      }
    }, /*#__PURE__*/React.createElement("div", {
      className: "m3-title-medium",
      style: {
        color: "var(--text-primary)",
        fontWeight: 700
      }
    }, src.name), /*#__PURE__*/React.createElement("div", {
      className: "m3-body-small",
      style: {
        color: "var(--text-secondary)"
      }
    }, "via ", src.pluginId, " \xB7 ", src.items.length, " items")), /*#__PURE__*/React.createElement("button", {
      type: "button",
      style: {
        display: "inline-flex",
        alignItems: "center",
        gap: 4,
        background: "transparent",
        border: "none",
        color: "var(--accent)",
        fontSize: 13,
        fontWeight: 600,
        cursor: "pointer",
        fontFamily: "var(--font-sans)"
      }
    }, "See all ", /*#__PURE__*/React.createElement(Icon, {
      name: "chevron-right",
      size: 16
    }))), /*#__PURE__*/React.createElement("div", {
      style: {
        display: "flex",
        gap: 12,
        overflowX: "auto",
        padding: "10px 16px 6px 0",
        scrollbarWidth: "none"
      }
    }, src.items.map(it => /*#__PURE__*/React.createElement("div", {
      key: it.id,
      style: {
        flex: "0 0 auto"
      }
    }, /*#__PURE__*/React.createElement(PosterCard, {
      title: it.title,
      subtitle: it.subtitle,
      poster: it.image,
      width: 128,
      onClick: () => onOpen && onOpen(it)
    })))))));
  }

  // ---- Search ------------------------------------------------------------
  function BeamSearch({
    onOpen
  }) {
    const {
      Icon,
      PosterCard,
      Pill
    } = NS;
    const cat = window.SF_CATALOG || [];
    const [q, setQ] = React.useState("");
    const [genre, setGenre] = React.useState("All");
    const genres = ["All", "Animation", "Comedy", "Science Fiction", "Drama", "Action"];
    const results = cat.filter(m => (genre === "All" || (m.genres || "").includes(genre)) && (q.trim() === "" || m.title.toLowerCase().includes(q.toLowerCase())));
    return /*#__PURE__*/React.createElement("div", {
      style: {
        paddingBottom: 24
      }
    }, /*#__PURE__*/React.createElement(TopBar, {
      title: "Search"
    }), /*#__PURE__*/React.createElement("div", {
      style: {
        padding: "4px 16px 0"
      }
    }, /*#__PURE__*/React.createElement("div", {
      style: {
        display: "flex",
        alignItems: "center",
        gap: 10,
        height: 48,
        padding: "0 14px",
        borderRadius: "var(--radius-full)",
        background: "var(--surface-container-high)"
      }
    }, /*#__PURE__*/React.createElement(Icon, {
      name: "search",
      size: 20,
      style: {
        color: "var(--text-secondary)"
      }
    }), /*#__PURE__*/React.createElement("input", {
      value: q,
      onChange: e => setQ(e.target.value),
      placeholder: "Search movies, shows, people",
      style: {
        flex: 1,
        border: "none",
        outline: "none",
        background: "transparent",
        color: "var(--text-primary)",
        fontSize: 15,
        fontFamily: "var(--font-sans)"
      }
    }), q ? /*#__PURE__*/React.createElement("button", {
      onClick: () => setQ(""),
      "aria-label": "Clear",
      style: {
        border: "none",
        background: "transparent",
        cursor: "pointer",
        color: "var(--text-secondary)",
        display: "inline-flex"
      }
    }, /*#__PURE__*/React.createElement(Icon, {
      name: "x",
      size: 18
    })) : null)), /*#__PURE__*/React.createElement("div", {
      style: {
        display: "flex",
        gap: 8,
        overflowX: "auto",
        padding: "14px 16px 4px",
        scrollbarWidth: "none"
      }
    }, genres.map(g => /*#__PURE__*/React.createElement("button", {
      key: g,
      onClick: () => setGenre(g),
      style: {
        flex: "0 0 auto",
        height: 34,
        padding: "0 16px",
        borderRadius: "var(--radius-full)",
        cursor: "pointer",
        fontSize: 13,
        fontWeight: 600,
        fontFamily: "var(--font-sans)",
        border: "1px solid " + (genre === g ? "transparent" : "var(--border-subtle)"),
        background: genre === g ? "var(--accent)" : "transparent",
        color: genre === g ? "var(--on-primary)" : "var(--text-secondary)"
      }
    }, g))), /*#__PURE__*/React.createElement("div", {
      style: {
        padding: "8px 16px 0",
        color: "var(--text-secondary)"
      },
      className: "m3-label-large"
    }, results.length, " result", results.length === 1 ? "" : "s"), /*#__PURE__*/React.createElement("div", {
      style: {
        display: "grid",
        gridTemplateColumns: "repeat(3, 1fr)",
        gap: 14,
        padding: "12px 16px 0"
      }
    }, results.map(m => /*#__PURE__*/React.createElement(PosterCard, {
      key: m.id,
      title: m.title,
      subtitle: m.year,
      poster: m.poster,
      width: "100%",
      progress: m.progress || null,
      onClick: () => onOpen && onOpen(m)
    }))), results.length === 0 ? /*#__PURE__*/React.createElement("div", {
      style: {
        textAlign: "center",
        color: "var(--text-secondary)",
        padding: 48
      }
    }, "No matches for \u201C", q, "\u201D.") : null);
  }

  // ---- Downloads ---------------------------------------------------------
  function BeamDownloads({
    onOpen
  }) {
    const {
      Icon,
      ProgressBar,
      Pill,
      IconButton
    } = NS;
    const cat = window.SF_CATALOG || [];
    const done = cat.filter(m => m.downloaded);
    const queued = cat.filter(m => !m.downloaded).slice(0, 2);
    const Item = ({
      m,
      pct
    }) => /*#__PURE__*/React.createElement("button", {
      type: "button",
      onClick: () => onOpen && onOpen(m),
      style: {
        display: "flex",
        alignItems: "center",
        gap: 14,
        width: "100%",
        textAlign: "left",
        padding: "10px 16px",
        border: "none",
        background: "transparent",
        cursor: "pointer"
      }
    }, /*#__PURE__*/React.createElement("img", {
      src: m.poster,
      alt: "",
      style: {
        width: 58,
        height: 82,
        borderRadius: 10,
        objectFit: "cover",
        flexShrink: 0
      }
    }), /*#__PURE__*/React.createElement("div", {
      style: {
        flex: 1,
        minWidth: 0
      }
    }, /*#__PURE__*/React.createElement("div", {
      className: "m3-title-small",
      style: {
        color: "var(--text-primary)",
        fontWeight: 600,
        whiteSpace: "nowrap",
        overflow: "hidden",
        textOverflow: "ellipsis"
      }
    }, m.title), /*#__PURE__*/React.createElement("div", {
      className: "m3-body-small",
      style: {
        color: "var(--text-secondary)"
      }
    }, m.runtime, " \xB7 ", m.tags.join(" · ")), pct != null ? /*#__PURE__*/React.createElement("div", {
      style: {
        marginTop: 8
      }
    }, /*#__PURE__*/React.createElement(ProgressBar, {
      value: pct,
      height: 5
    })) : /*#__PURE__*/React.createElement("div", {
      style: {
        marginTop: 6
      }
    }, /*#__PURE__*/React.createElement(Pill, {
      tone: "accent",
      icon: "circle-check"
    }, "Ready \xB7 1.2 GB"))), /*#__PURE__*/React.createElement(IconButton, {
      icon: pct != null ? "x" : "trash-2",
      variant: "ghost",
      size: "sm",
      label: pct != null ? "Cancel" : "Delete"
    }));
    return /*#__PURE__*/React.createElement("div", {
      style: {
        paddingBottom: 24
      }
    }, /*#__PURE__*/React.createElement(TopBar, {
      title: "Downloads",
      right: /*#__PURE__*/React.createElement(Pill, {
        tone: "neutral",
        icon: "hard-drive"
      }, "3.6 GB used")
    }), /*#__PURE__*/React.createElement("div", {
      style: {
        margin: "6px 16px 4px",
        padding: "12px 14px",
        borderRadius: 16,
        background: "var(--surface-container-high)"
      }
    }, /*#__PURE__*/React.createElement("div", {
      style: {
        display: "flex",
        justifyContent: "space-between",
        marginBottom: 8
      }
    }, /*#__PURE__*/React.createElement("span", {
      className: "m3-label-large",
      style: {
        color: "var(--text-primary)"
      }
    }, "Storage"), /*#__PURE__*/React.createElement("span", {
      className: "m3-label-large",
      style: {
        color: "var(--text-secondary)",
        fontFamily: "var(--font-mono)"
      }
    }, "3.6 / 64 GB")), /*#__PURE__*/React.createElement(ProgressBar, {
      value: 6,
      height: 6
    })), queued.length ? /*#__PURE__*/React.createElement("div", {
      style: {
        padding: "12px 16px 2px",
        color: "var(--text-secondary)"
      },
      className: "m3-label-large"
    }, "DOWNLOADING") : null, queued.map((m, i) => /*#__PURE__*/React.createElement(Item, {
      key: m.id,
      m: m,
      pct: [62, 24][i]
    })), /*#__PURE__*/React.createElement("div", {
      style: {
        padding: "12px 16px 2px",
        color: "var(--text-secondary)"
      },
      className: "m3-label-large"
    }, "ON THIS DEVICE"), done.map(m => /*#__PURE__*/React.createElement(Item, {
      key: m.id,
      m: m
    })));
  }

  // ---- Settings ----------------------------------------------------------
  function BeamSettings({
    serverName,
    user,
    onOpenServer,
    onOpenCast,
    onOpenUser,
    castLabel,
    quality
  }) {
    const {
      Icon
    } = NS;
    const [wifiOnly, setWifiOnly] = React.useState(true);
    const [autoplay, setAutoplay] = React.useState(true);
    const Row = ({
      icon,
      title,
      value,
      trailing,
      onClick
    }) => /*#__PURE__*/React.createElement("button", {
      type: "button",
      onClick: onClick,
      disabled: !onClick && !trailing,
      style: {
        display: "flex",
        alignItems: "center",
        gap: 16,
        width: "100%",
        textAlign: "left",
        padding: "14px 16px",
        border: "none",
        background: "transparent",
        cursor: onClick ? "pointer" : "default",
        color: "var(--text-primary)"
      }
    }, /*#__PURE__*/React.createElement("span", {
      style: {
        width: 40,
        height: 40,
        borderRadius: "50%",
        background: "var(--surface-container-high)",
        color: "var(--text-secondary)",
        display: "inline-flex",
        alignItems: "center",
        justifyContent: "center",
        flexShrink: 0
      }
    }, /*#__PURE__*/React.createElement(Icon, {
      name: icon,
      size: 20
    })), /*#__PURE__*/React.createElement("span", {
      style: {
        flex: 1
      }
    }, /*#__PURE__*/React.createElement("span", {
      style: {
        display: "block",
        fontSize: 16,
        fontWeight: 600
      }
    }, title), value ? /*#__PURE__*/React.createElement("span", {
      style: {
        display: "block",
        fontSize: 13,
        color: "var(--text-secondary)"
      }
    }, value) : null), trailing !== undefined ? trailing : onClick ? /*#__PURE__*/React.createElement(Icon, {
      name: "chevron-right",
      size: 20,
      color: "var(--text-secondary)"
    }) : null);
    const Toggle = ({
      on,
      onClick
    }) => /*#__PURE__*/React.createElement("span", {
      onClick: onClick,
      style: {
        width: 46,
        height: 28,
        borderRadius: 99,
        cursor: "pointer",
        flexShrink: 0,
        background: on ? "var(--accent)" : "var(--surface-container-highest)",
        position: "relative",
        transition: "background var(--duration-fast)"
      }
    }, /*#__PURE__*/React.createElement("span", {
      style: {
        position: "absolute",
        top: 3,
        left: on ? 21 : 3,
        width: 22,
        height: 22,
        borderRadius: "50%",
        background: "#fff",
        transition: "left var(--duration-fast) var(--ease-standard)"
      }
    }));
    const Group = ({
      label,
      children
    }) => /*#__PURE__*/React.createElement("div", {
      style: {
        margin: "8px 12px",
        borderRadius: 18,
        background: "var(--surface-container)",
        overflow: "hidden"
      }
    }, label ? /*#__PURE__*/React.createElement("div", {
      style: {
        padding: "12px 16px 4px",
        color: "var(--text-secondary)",
        fontSize: 12,
        fontWeight: 700,
        letterSpacing: 0.6
      }
    }, label) : null, children);
    const qualLabel = {
      auto: "Auto",
      orig: "Original",
      "1080": "1080p",
      "720": "720p",
      "480": "480p"
    }[quality] || "Auto";
    return /*#__PURE__*/React.createElement("div", {
      style: {
        paddingBottom: 24
      }
    }, /*#__PURE__*/React.createElement(TopBar, {
      title: "Settings"
    }), /*#__PURE__*/React.createElement("button", {
      type: "button",
      onClick: onOpenUser,
      style: {
        display: "flex",
        alignItems: "center",
        gap: 14,
        width: "100%",
        padding: "6px 18px 10px",
        background: "transparent",
        border: "none",
        cursor: "pointer",
        textAlign: "left"
      }
    }, window.BeamUserAvatar ? /*#__PURE__*/React.createElement(window.BeamUserAvatar, {
      user: user,
      size: 52
    }) : null, /*#__PURE__*/React.createElement("div", {
      style: {
        flex: 1
      }
    }, /*#__PURE__*/React.createElement("div", {
      className: "m3-title-medium",
      style: {
        color: "var(--text-primary)",
        fontWeight: 700
      }
    }, user ? user.name : "Sign in"), /*#__PURE__*/React.createElement("div", {
      className: "m3-body-small",
      style: {
        color: "var(--text-secondary)"
      }
    }, user ? "Signed in · " + (user.role || "user").toLowerCase() : "")), /*#__PURE__*/React.createElement(Icon, {
      name: "chevron-right",
      size: 20,
      color: "var(--text-secondary)"
    })), /*#__PURE__*/React.createElement(Group, {
      label: "SERVER & PLAYBACK"
    }, /*#__PURE__*/React.createElement(Row, {
      icon: "server",
      title: "Jellyfin server",
      value: serverName,
      onClick: onOpenServer
    }), /*#__PURE__*/React.createElement(Row, {
      icon: "cast",
      title: "Cast & audio output",
      value: castLabel,
      onClick: onOpenCast
    }), /*#__PURE__*/React.createElement(Row, {
      icon: "gauge",
      title: "Default streaming quality",
      value: qualLabel,
      onClick: () => {}
    })), /*#__PURE__*/React.createElement(Group, {
      label: "DOWNLOADS"
    }, /*#__PURE__*/React.createElement(Row, {
      icon: "wifi",
      title: "Download over Wi-Fi only",
      trailing: /*#__PURE__*/React.createElement(Toggle, {
        on: wifiOnly,
        onClick: () => setWifiOnly(!wifiOnly)
      })
    }), /*#__PURE__*/React.createElement(Row, {
      icon: "play",
      title: "Autoplay next episode",
      trailing: /*#__PURE__*/React.createElement(Toggle, {
        on: autoplay,
        onClick: () => setAutoplay(!autoplay)
      })
    })), /*#__PURE__*/React.createElement(Group, {
      label: "ABOUT"
    }, /*#__PURE__*/React.createElement(Row, {
      icon: "info",
      title: "SpatialFin Beam",
      value: "v3.2.0 \xB7 Jellyfin 10.9"
    })));
  }
  Object.assign(window, {
    BeamSources,
    BeamSearch,
    BeamDownloads,
    BeamSettings
  });
})();
})(); } catch (e) { __ds_ns.__errors.push({ path: "ui_kits/beam/MoreScreens.jsx", error: String((e && e.message) || e) }); }

// ui_kits/beam/PlayerScreen.jsx
try { (() => {
// Beam (phone) — Player screen with full transport + quality/audio/subtitle/cast/syncplay
function BeamPlayer({
  item,
  onBack,
  voiceState,
  onVoice,
  onOpenCast,
  castActive,
  castLabel,
  castSplit,
  onOpenSyncPlay,
  syncActive
}) {
  const NS = window.SpatialFinDesignSystem_0d3fe7;
  const {
    IconButton,
    Pill,
    ProgressBar,
    VoiceFeedback,
    Icon
  } = NS;
  const [playing, setPlaying] = React.useState(true);
  const [sheet, setSheet] = React.useState(null); // "quality" | "audio" | "subtitle" | null
  const [quality, setQuality] = React.useState("auto");
  const [audio, setAudio] = React.useState("en51");
  const [subtitle, setSubtitle] = React.useState("off");
  const showVoice = voiceState && voiceState !== "idle";
  const qualLabel = {
    auto: "Auto",
    orig: "Original",
    "1080": "1080p",
    "720": "720p",
    "480": "480p"
  }[quality];
  const audioLabel = {
    en51: "EN 5.1",
    enatmos: "Atmos",
    en20: "EN",
    comm: "Comm.",
    es: "ES"
  }[audio];
  const subLabel = subtitle === "off" ? "Off" : {
    ensdh: "EN SDH",
    enforced: "Forced",
    es: "ES",
    fr: "FR"
  }[subtitle];

  // bottom control: stacked icon + label, with active accent state
  const Ctrl = ({
    icon,
    label,
    value,
    active,
    onClick
  }) => /*#__PURE__*/React.createElement("button", {
    type: "button",
    onClick: onClick,
    style: {
      display: "flex",
      flexDirection: "column",
      alignItems: "center",
      gap: 6,
      flex: 1,
      background: "transparent",
      border: "none",
      cursor: "pointer",
      padding: 0,
      minWidth: 0
    }
  }, /*#__PURE__*/React.createElement("span", {
    style: {
      width: 46,
      height: 46,
      borderRadius: "50%",
      background: active ? "var(--accent)" : "var(--glass-fill-strong)",
      border: "1px solid " + (active ? "transparent" : "var(--glass-border)"),
      backdropFilter: "blur(var(--glass-blur))",
      WebkitBackdropFilter: "blur(var(--glass-blur))",
      color: active ? "var(--on-primary)" : "#fff",
      display: "inline-flex",
      alignItems: "center",
      justifyContent: "center"
    }
  }, /*#__PURE__*/React.createElement(Icon, {
    name: icon,
    size: 21
  })), /*#__PURE__*/React.createElement("span", {
    style: {
      fontSize: 10.5,
      fontWeight: 600,
      lineHeight: 1,
      color: "rgba(255,255,255,0.82)",
      fontFamily: "var(--font-sans)",
      whiteSpace: "nowrap",
      maxWidth: 64,
      overflow: "hidden",
      textOverflow: "ellipsis"
    }
  }, value || label));
  return /*#__PURE__*/React.createElement("div", {
    style: {
      position: "absolute",
      inset: 0,
      background: "#000",
      display: "flex",
      flexDirection: "column"
    }
  }, /*#__PURE__*/React.createElement("div", {
    style: {
      position: "absolute",
      inset: 0
    }
  }, /*#__PURE__*/React.createElement("img", {
    src: item.backdrop,
    alt: "",
    style: {
      width: "100%",
      height: "100%",
      objectFit: "cover",
      opacity: 0.85
    }
  }), /*#__PURE__*/React.createElement("div", {
    style: {
      position: "absolute",
      inset: 0,
      background: "linear-gradient(180deg, rgba(0,0,0,0.55) 0%, rgba(0,0,0,0) 28%, rgba(0,0,0,0) 52%, rgba(0,0,0,0.82) 100%)"
    }
  })), /*#__PURE__*/React.createElement("div", {
    style: {
      position: "relative",
      display: "flex",
      alignItems: "flex-start",
      gap: 12,
      padding: "14px 16px"
    }
  }, /*#__PURE__*/React.createElement(IconButton, {
    icon: "chevron-down",
    variant: "glass",
    label: "Back",
    onClick: onBack
  }), /*#__PURE__*/React.createElement("div", {
    style: {
      flex: 1,
      minWidth: 0,
      paddingTop: 2
    }
  }, /*#__PURE__*/React.createElement("div", {
    className: "m3-title-medium",
    style: {
      color: "#fff",
      fontWeight: 600,
      whiteSpace: "nowrap",
      overflow: "hidden",
      textOverflow: "ellipsis"
    }
  }, item.title), castActive ? /*#__PURE__*/React.createElement("div", {
    style: {
      display: "flex",
      alignItems: "center",
      gap: 6,
      marginTop: 3,
      color: "rgba(255,255,255,0.85)",
      fontSize: 12
    }
  }, /*#__PURE__*/React.createElement(Icon, {
    name: "cast",
    size: 13
  }), " Playing on ", castLabel, castSplit ? " · split audio" : "") : null), /*#__PURE__*/React.createElement(Pill, {
    tone: "accent"
  }, qualLabel === "Auto" ? item.tags[0] || "4K" : qualLabel), /*#__PURE__*/React.createElement(IconButton, {
    icon: "cast",
    variant: castActive ? "filled" : "glass",
    label: "Cast",
    onClick: onOpenCast
  })), showVoice ? /*#__PURE__*/React.createElement("div", {
    style: {
      position: "relative",
      display: "flex",
      justifyContent: "center",
      marginTop: 4
    }
  }, /*#__PURE__*/React.createElement(VoiceFeedback, {
    state: voiceState === "listening" ? "listening" : "answered",
    text: voiceState === "listening" ? "“skip the intro”" : "Skipping intro."
  })) : null, /*#__PURE__*/React.createElement("div", {
    style: {
      position: "relative",
      flex: 1,
      display: "flex",
      alignItems: "center",
      justifyContent: "center",
      gap: 28
    }
  }, /*#__PURE__*/React.createElement(IconButton, {
    icon: "rotate-ccw",
    variant: "glass",
    label: "Back 10s",
    size: "lg"
  }), /*#__PURE__*/React.createElement("button", {
    onClick: () => setPlaying(!playing),
    "aria-label": "Play/Pause",
    style: {
      width: 76,
      height: 76,
      borderRadius: "50%",
      cursor: "pointer",
      background: "var(--glass-fill-strong)",
      backdropFilter: "blur(var(--glass-blur))",
      WebkitBackdropFilter: "blur(var(--glass-blur))",
      border: "1px solid var(--glass-border)",
      color: "#fff",
      display: "inline-flex",
      alignItems: "center",
      justifyContent: "center"
    }
  }, /*#__PURE__*/React.createElement(Icon, {
    name: playing ? "pause" : "play",
    size: 32
  })), /*#__PURE__*/React.createElement(IconButton, {
    icon: "rotate-cw",
    variant: "glass",
    label: "Forward 10s",
    size: "lg"
  })), /*#__PURE__*/React.createElement("div", {
    style: {
      position: "relative",
      padding: "0 16px calc(20px + env(safe-area-inset-bottom))",
      display: "flex",
      flexDirection: "column",
      gap: 16
    }
  }, /*#__PURE__*/React.createElement("div", {
    style: {
      display: "flex",
      alignItems: "center",
      gap: 12
    }
  }, /*#__PURE__*/React.createElement("span", {
    className: "m3-label-medium",
    style: {
      color: "#fff",
      fontFamily: "var(--font-mono)"
    }
  }, "02:38"), /*#__PURE__*/React.createElement("div", {
    style: {
      flex: 1
    }
  }, /*#__PURE__*/React.createElement(ProgressBar, {
    value: item.progress || 34,
    height: 6
  })), /*#__PURE__*/React.createElement("span", {
    className: "m3-label-medium",
    style: {
      color: "rgba(255,255,255,0.7)",
      fontFamily: "var(--font-mono)"
    }
  }, item.runtime)), /*#__PURE__*/React.createElement("div", {
    style: {
      display: "flex",
      gap: 4,
      justifyContent: "space-between"
    }
  }, /*#__PURE__*/React.createElement(Ctrl, {
    icon: subtitle === "off" ? "captions-off" : "captions",
    label: "Subtitles",
    value: subLabel,
    active: subtitle !== "off",
    onClick: () => setSheet("subtitle")
  }), /*#__PURE__*/React.createElement(Ctrl, {
    icon: "audio-lines",
    label: "Audio",
    value: audioLabel,
    onClick: () => setSheet("audio")
  }), /*#__PURE__*/React.createElement(Ctrl, {
    icon: "gauge",
    label: "Quality",
    value: qualLabel,
    onClick: () => setSheet("quality")
  }), /*#__PURE__*/React.createElement(Ctrl, {
    icon: "users",
    label: "SyncPlay",
    active: syncActive,
    onClick: onOpenSyncPlay
  }), /*#__PURE__*/React.createElement(Ctrl, {
    icon: "mic",
    label: "Voice",
    active: voiceState === "listening",
    onClick: onVoice
  }))), /*#__PURE__*/React.createElement(window.QualitySheet, {
    open: sheet === "quality",
    value: quality,
    onPick: setQuality,
    onClose: () => setSheet(null)
  }), /*#__PURE__*/React.createElement(window.AudioSheet, {
    open: sheet === "audio",
    value: audio,
    onPick: setAudio,
    onClose: () => setSheet(null)
  }), /*#__PURE__*/React.createElement(window.SubtitleSheet, {
    open: sheet === "subtitle",
    value: subtitle,
    onPick: setSubtitle,
    onClose: () => setSheet(null)
  }));
}
window.BeamPlayer = BeamPlayer;
})(); } catch (e) { __ds_ns.__errors.push({ path: "ui_kits/beam/PlayerScreen.jsx", error: String((e && e.message) || e) }); }

// ui_kits/beam/PlayerSheets.jsx
try { (() => {
// Beam (phone) — bottom sheets for player & app chrome:
// Quality, Audio, Subtitles, Cast (+ split audio/video), SyncPlay.
(function () {
  const NS = window.SpatialFinDesignSystem_0d3fe7;

  // ---- generic phone bottom sheet ----------------------------------------
  function BeamSheet({
    open,
    title,
    subtitle,
    onClose,
    children,
    footer
  }) {
    if (!open) return null;
    const {
      Icon
    } = NS;
    return /*#__PURE__*/React.createElement("div", {
      onClick: onClose,
      style: {
        position: "absolute",
        inset: 0,
        zIndex: 70,
        background: "rgba(0,0,0,0.55)",
        display: "flex",
        alignItems: "flex-end",
        animation: "sfFade 160ms ease both"
      }
    }, /*#__PURE__*/React.createElement("div", {
      onClick: e => e.stopPropagation(),
      style: {
        width: "100%",
        maxHeight: "84%",
        background: "var(--surface-container)",
        borderTopLeftRadius: 26,
        borderTopRightRadius: 26,
        borderTop: "1px solid var(--border-subtle)",
        boxShadow: "0 -18px 50px rgba(0,0,0,0.5)",
        display: "flex",
        flexDirection: "column",
        animation: "sfSlide 240ms cubic-bezier(.2,.85,.25,1) both"
      }
    }, /*#__PURE__*/React.createElement("div", {
      style: {
        display: "flex",
        justifyContent: "center",
        paddingTop: 10
      }
    }, /*#__PURE__*/React.createElement("span", {
      style: {
        width: 38,
        height: 4,
        borderRadius: 99,
        background: "var(--border-strong)"
      }
    })), /*#__PURE__*/React.createElement("div", {
      style: {
        display: "flex",
        alignItems: "center",
        gap: 12,
        padding: "10px 16px 8px"
      }
    }, /*#__PURE__*/React.createElement("div", {
      style: {
        flex: 1,
        minWidth: 0
      }
    }, /*#__PURE__*/React.createElement("div", {
      className: "m3-title-large",
      style: {
        fontWeight: 700,
        color: "var(--text-primary)"
      }
    }, title), subtitle ? /*#__PURE__*/React.createElement("div", {
      className: "m3-body-small",
      style: {
        color: "var(--text-secondary)",
        marginTop: 2
      }
    }, subtitle) : null), /*#__PURE__*/React.createElement("button", {
      type: "button",
      onClick: onClose,
      "aria-label": "Close",
      style: {
        width: 38,
        height: 38,
        borderRadius: "50%",
        border: "none",
        cursor: "pointer",
        background: "var(--surface-container-high)",
        color: "var(--text-primary)",
        display: "inline-flex",
        alignItems: "center",
        justifyContent: "center"
      }
    }, /*#__PURE__*/React.createElement(Icon, {
      name: "x",
      size: 20
    }))), /*#__PURE__*/React.createElement("div", {
      style: {
        overflowY: "auto",
        padding: "2px 8px 8px",
        scrollbarWidth: "none"
      }
    }, children), footer ? /*#__PURE__*/React.createElement("div", {
      style: {
        padding: "8px 16px calc(16px + env(safe-area-inset-bottom))",
        borderTop: "1px solid var(--border-subtle)"
      }
    }, footer) : null, /*#__PURE__*/React.createElement("div", {
      style: {
        height: "env(safe-area-inset-bottom)"
      }
    })), /*#__PURE__*/React.createElement("style", null, `@keyframes sfSlide{from{transform:translateY(100%)}to{transform:translateY(0)}}
          @keyframes sfFade{from{opacity:0}to{opacity:1}}
          .sf-row{transition:background var(--duration-fast) var(--ease-standard)}
          .sf-row:active{background:var(--surface-container-highest)}`));
  }

  // ---- a selectable list row ---------------------------------------------
  function OptionRow({
    icon,
    iconBg,
    leading,
    title,
    subtitle,
    trailing,
    selected,
    dim,
    onClick
  }) {
    const {
      Icon
    } = NS;
    return /*#__PURE__*/React.createElement("button", {
      type: "button",
      onClick: onClick,
      className: "sf-row",
      style: {
        display: "flex",
        alignItems: "center",
        gap: 14,
        width: "100%",
        textAlign: "left",
        padding: "12px 12px",
        border: "none",
        background: "transparent",
        borderRadius: 16,
        cursor: "pointer",
        color: "var(--text-primary)",
        opacity: dim ? 0.55 : 1
      }
    }, leading ? leading : icon ? /*#__PURE__*/React.createElement("span", {
      style: {
        width: 42,
        height: 42,
        borderRadius: "50%",
        flexShrink: 0,
        background: iconBg || "var(--surface-container-highest)",
        color: iconBg ? "#fff" : "var(--text-secondary)",
        display: "inline-flex",
        alignItems: "center",
        justifyContent: "center"
      }
    }, /*#__PURE__*/React.createElement(Icon, {
      name: icon,
      size: 21
    })) : null, /*#__PURE__*/React.createElement("span", {
      style: {
        flex: 1,
        minWidth: 0
      }
    }, /*#__PURE__*/React.createElement("span", {
      style: {
        display: "block",
        fontSize: 16,
        fontWeight: 600,
        whiteSpace: "nowrap",
        overflow: "hidden",
        textOverflow: "ellipsis"
      }
    }, title), subtitle ? /*#__PURE__*/React.createElement("span", {
      style: {
        display: "block",
        fontSize: 13,
        color: "var(--text-secondary)",
        whiteSpace: "nowrap",
        overflow: "hidden",
        textOverflow: "ellipsis"
      }
    }, subtitle) : null), trailing !== undefined ? trailing : selected ? /*#__PURE__*/React.createElement(NS.Icon, {
      name: "check",
      size: 22,
      color: "var(--primary)"
    }) : null);
  }
  function SectionLabel({
    children,
    style
  }) {
    return /*#__PURE__*/React.createElement("div", {
      style: {
        padding: "14px 16px 6px",
        color: "var(--text-secondary)",
        fontSize: 12,
        fontWeight: 700,
        letterSpacing: 0.7,
        textTransform: "uppercase",
        ...style
      }
    }, children);
  }
  function Checkbox({
    on
  }) {
    const {
      Icon
    } = NS;
    return /*#__PURE__*/React.createElement("span", {
      style: {
        width: 24,
        height: 24,
        borderRadius: 7,
        flexShrink: 0,
        border: "2px solid " + (on ? "var(--primary)" : "var(--border-strong)"),
        background: on ? "var(--primary)" : "transparent",
        color: "var(--on-primary)",
        display: "inline-flex",
        alignItems: "center",
        justifyContent: "center"
      }
    }, on ? /*#__PURE__*/React.createElement(Icon, {
      name: "check",
      size: 15
    }) : null);
  }

  // ---- Quality -----------------------------------------------------------
  const QUALITIES = [{
    id: "auto",
    title: "Auto",
    sub: "Adapts to network · up to 4K"
  }, {
    id: "orig",
    title: "Original",
    sub: "Direct Play · MKV 14 Mbps"
  }, {
    id: "1080",
    title: "1080p",
    sub: "8 Mbps"
  }, {
    id: "720",
    title: "720p",
    sub: "4 Mbps"
  }, {
    id: "480",
    title: "480p",
    sub: "1.5 Mbps · data saver"
  }];
  function QualitySheet({
    open,
    value,
    onPick,
    onClose
  }) {
    return /*#__PURE__*/React.createElement(BeamSheet, {
      open: open,
      title: "Quality",
      subtitle: "Streaming resolution & bitrate",
      onClose: onClose
    }, QUALITIES.map(q => /*#__PURE__*/React.createElement(OptionRow, {
      key: q.id,
      icon: q.id === "auto" ? "gauge" : q.id === "orig" ? "badge-check" : "monitor",
      title: q.title,
      subtitle: q.sub,
      selected: value === q.id,
      onClick: () => {
        onPick(q.id);
        onClose();
      }
    })));
  }

  // ---- Audio tracks ------------------------------------------------------
  const AUDIO_TRACKS = [{
    id: "en51",
    title: "English",
    sub: "5.1 Surround · AC-3"
  }, {
    id: "enatmos",
    title: "English",
    sub: "Dolby Atmos · TrueHD"
  }, {
    id: "en20",
    title: "English",
    sub: "Stereo · AAC"
  }, {
    id: "comm",
    title: "Director commentary",
    sub: "Stereo"
  }, {
    id: "es",
    title: "Spanish",
    sub: "Stereo · AAC"
  }];
  function AudioSheet({
    open,
    value,
    onPick,
    onClose
  }) {
    return /*#__PURE__*/React.createElement(BeamSheet, {
      open: open,
      title: "Audio",
      subtitle: "Audio track",
      onClose: onClose
    }, AUDIO_TRACKS.map(a => /*#__PURE__*/React.createElement(OptionRow, {
      key: a.id,
      icon: "audio-lines",
      title: a.title,
      subtitle: a.sub,
      selected: value === a.id,
      onClick: () => {
        onPick(a.id);
        onClose();
      }
    })));
  }

  // ---- Subtitles ---------------------------------------------------------
  const SUBS = [{
    id: "off",
    title: "Off",
    sub: null
  }, {
    id: "ensdh",
    title: "English (SDH)",
    sub: "Full · burned-in off"
  }, {
    id: "enforced",
    title: "English (Forced)",
    sub: "Signs & songs"
  }, {
    id: "es",
    title: "Spanish",
    sub: "Full"
  }, {
    id: "fr",
    title: "French",
    sub: "Full"
  }];
  function SubtitleSheet({
    open,
    value,
    onPick,
    onClose
  }) {
    return /*#__PURE__*/React.createElement(BeamSheet, {
      open: open,
      title: "Subtitles",
      subtitle: "Subtitle track",
      onClose: onClose
    }, SUBS.map(s => /*#__PURE__*/React.createElement(OptionRow, {
      key: s.id,
      icon: s.id === "off" ? "captions-off" : "captions",
      title: s.title,
      subtitle: s.sub,
      selected: value === s.id,
      onClick: () => {
        onPick(s.id);
        onClose();
      }
    })));
  }
  const DTYPE_ICON = {
    phone: "smartphone",
    tv: "tv",
    speaker: "speaker",
    headphones: "headphones"
  };

  // ---- Cast / Play on (+ split audio/video + multi-room sync) ------------
  function CastSheet({
    open,
    videoId,
    audioId,
    syncIds,
    onClose,
    onPickVideo,
    onPickAudio,
    onToggleSync
  }) {
    const devices = window.SF_CAST_DEVICES || [];
    const videoDevices = devices.filter(d => d.video);
    const audioDevices = devices.filter(d => d.audio);
    const videoDev = devices.find(d => d.id === videoId) || devices[0];
    // split is active when audio is explicitly routed somewhere other than the video sink
    const split = !!audioId && audioId !== videoId;
    const effAudioId = audioId || videoId;
    // speakers available to add to a synced multi-room group (exclude the primary audio sink)
    const groupable = audioDevices.filter(d => d.id !== effAudioId && d.type !== "phone");
    return /*#__PURE__*/React.createElement(BeamSheet, {
      open: open,
      title: "Cast & audio",
      subtitle: "Send video and audio to your devices",
      onClose: onClose
    }, /*#__PURE__*/React.createElement(SectionLabel, null, "Play video on"), videoDevices.map(d => /*#__PURE__*/React.createElement(OptionRow, {
      key: d.id,
      icon: DTYPE_ICON[d.type],
      title: d.name,
      subtitle: d.hint,
      selected: d.id === videoId,
      onClick: () => onPickVideo(d.id)
    })), /*#__PURE__*/React.createElement(SectionLabel, {
      style: {
        display: "flex",
        alignItems: "center",
        gap: 8
      }
    }, /*#__PURE__*/React.createElement(NS.Icon, {
      name: "split",
      size: 13
    }), " Audio output"), /*#__PURE__*/React.createElement("div", {
      style: {
        padding: "0 16px 6px",
        color: "var(--text-secondary)",
        fontSize: 12.5,
        lineHeight: 1.4
      }
    }, split ? /*#__PURE__*/React.createElement(React.Fragment, null, "Audio is split from video \u2014 playing on ", /*#__PURE__*/React.createElement("b", {
      style: {
        color: "var(--text-primary)"
      }
    }, (devices.find(d => d.id === effAudioId) || {}).name), ".") : /*#__PURE__*/React.createElement(React.Fragment, null, "Audio follows the video device. Pick another to split it.")), /*#__PURE__*/React.createElement(OptionRow, {
      icon: "link",
      title: "Same as video",
      subtitle: videoDev ? videoDev.name : "",
      selected: !split,
      onClick: () => onPickAudio(null)
    }), audioDevices.filter(d => d.id !== videoId).map(d => /*#__PURE__*/React.createElement(OptionRow, {
      key: "a-" + d.id,
      icon: DTYPE_ICON[d.type],
      title: d.name,
      subtitle: d.hint,
      selected: split && audioId === d.id,
      onClick: () => onPickAudio(d.id)
    })), groupable.length ? /*#__PURE__*/React.createElement(React.Fragment, null, /*#__PURE__*/React.createElement(SectionLabel, {
      style: {
        display: "flex",
        alignItems: "center",
        gap: 8
      }
    }, /*#__PURE__*/React.createElement(NS.Icon, {
      name: "radio",
      size: 13
    }), " Also play in sync"), /*#__PURE__*/React.createElement("div", {
      style: {
        padding: "0 16px 6px",
        color: "var(--text-secondary)",
        fontSize: 12.5
      }
    }, "Group extra speakers for multi-room playback."), groupable.map(d => {
      const on = (syncIds || []).includes(d.id);
      return /*#__PURE__*/React.createElement(OptionRow, {
        key: "s-" + d.id,
        icon: DTYPE_ICON[d.type],
        title: d.name,
        subtitle: on ? "In sync" : d.hint,
        trailing: /*#__PURE__*/React.createElement(Checkbox, {
          on: on
        }),
        onClick: () => onToggleSync(d.id)
      });
    })) : null);
  }

  // ---- SyncPlay (watch-together) -----------------------------------------
  function Avatar({
    name,
    color,
    size = 34
  }) {
    return /*#__PURE__*/React.createElement("span", {
      style: {
        width: size,
        height: size,
        borderRadius: "50%",
        background: color || "#3C4758",
        color: "#fff",
        fontSize: size * 0.4,
        fontWeight: 700,
        flexShrink: 0,
        display: "inline-flex",
        alignItems: "center",
        justifyContent: "center"
      }
    }, name[0]);
  }
  function SyncPlaySheet({
    open,
    group,
    onClose,
    onJoin,
    onLeave,
    onCreate
  }) {
    const groups = window.SF_SYNCPLAY_GROUPS || [];
    const people = window.SF_SYNCPLAY_PEOPLE || [];
    const {
      Icon,
      Button
    } = NS;
    return /*#__PURE__*/React.createElement(BeamSheet, {
      open: open,
      title: "SyncPlay",
      subtitle: "Watch in perfect sync with others",
      onClose: onClose,
      footer: group ? /*#__PURE__*/React.createElement(Button, {
        variant: "tonal",
        fullWidth: true,
        icon: "log-out",
        onClick: onLeave
      }, "Leave group") : /*#__PURE__*/React.createElement(Button, {
        variant: "filled",
        fullWidth: true,
        icon: "plus",
        onClick: onCreate
      }, "New group")
    }, group ? /*#__PURE__*/React.createElement("div", null, /*#__PURE__*/React.createElement("div", {
      style: {
        display: "flex",
        alignItems: "center",
        gap: 12,
        padding: "10px 14px",
        margin: "4px 8px 8px",
        borderRadius: 16,
        background: "var(--accent-container)"
      }
    }, /*#__PURE__*/React.createElement("span", {
      style: {
        width: 40,
        height: 40,
        borderRadius: "50%",
        background: "var(--accent)",
        color: "var(--on-primary)",
        display: "inline-flex",
        alignItems: "center",
        justifyContent: "center"
      }
    }, /*#__PURE__*/React.createElement(Icon, {
      name: "users",
      size: 20
    })), /*#__PURE__*/React.createElement("div", {
      style: {
        flex: 1
      }
    }, /*#__PURE__*/React.createElement("div", {
      style: {
        fontSize: 16,
        fontWeight: 700,
        color: "var(--on-accent-container)"
      }
    }, group.name), /*#__PURE__*/React.createElement("div", {
      style: {
        fontSize: 12.5,
        color: "var(--on-accent-container)",
        opacity: 0.85,
        display: "flex",
        alignItems: "center",
        gap: 6
      }
    }, /*#__PURE__*/React.createElement("span", {
      style: {
        width: 7,
        height: 7,
        borderRadius: 99,
        background: "var(--success, #58c06b)"
      }
    }), " In sync \xB7 ", group.ping || 36, " ms"))), /*#__PURE__*/React.createElement(SectionLabel, null, "In this room"), people.map(p => /*#__PURE__*/React.createElement(OptionRow, {
      key: p.id,
      icon: null,
      trailing: /*#__PURE__*/React.createElement(Icon, {
        name: p.you ? "smartphone" : "check",
        size: 18,
        color: "var(--text-secondary)"
      }),
      title: p.name + (p.you ? " (you)" : ""),
      subtitle: p.you ? "Host · this device" : "Ready",
      onClick: () => {}
    }))) : /*#__PURE__*/React.createElement("div", null, /*#__PURE__*/React.createElement(SectionLabel, null, "Join a group"), groups.map(g => /*#__PURE__*/React.createElement(OptionRow, {
      key: g.id,
      icon: "users",
      title: g.name,
      subtitle: g.members + " watching · " + g.ping + " ms",
      trailing: /*#__PURE__*/React.createElement(Icon, {
        name: "chevron-right",
        size: 20,
        color: "var(--text-secondary)"
      }),
      onClick: () => onJoin(g)
    }))));
  }

  // ---- User avatar + profile switcher ------------------------------------
  function UserAvatar({
    user,
    size = 40,
    ring
  }) {
    const base = {
      width: size,
      height: size,
      borderRadius: "50%",
      flexShrink: 0,
      boxShadow: ring ? "0 0 0 2px var(--surface-app), 0 0 0 4px var(--accent)" : "none"
    };
    if (user && user.avatar) {
      return /*#__PURE__*/React.createElement("img", {
        src: user.avatar,
        alt: user.name,
        style: {
          ...base,
          objectFit: "cover"
        }
      });
    }
    return /*#__PURE__*/React.createElement("span", {
      style: {
        ...base,
        background: user && user.color || "var(--surface-container-highest)",
        color: user && user.textColor || "#fff",
        fontSize: Math.round(size * 0.38),
        fontWeight: 700,
        display: "inline-flex",
        alignItems: "center",
        justifyContent: "center"
      }
    }, user ? user.initials : "?");
  }
  function UserPickerSheet({
    open,
    currentId,
    onPick,
    onClose
  }) {
    const users = window.SF_USERS || [];
    const {
      Button
    } = NS;
    return /*#__PURE__*/React.createElement(BeamSheet, {
      open: open,
      title: "Switch user",
      subtitle: "Choose a Jellyfin profile on this server",
      onClose: onClose,
      footer: /*#__PURE__*/React.createElement(Button, {
        variant: "tonal",
        fullWidth: true,
        icon: "user-plus",
        onClick: onClose
      }, "Add another user")
    }, users.map(u => /*#__PURE__*/React.createElement(OptionRow, {
      key: u.id,
      leading: /*#__PURE__*/React.createElement(UserAvatar, {
        user: u,
        size: 44
      }),
      title: u.name,
      subtitle: u.role,
      selected: u.id === currentId,
      onClick: () => {
        onPick(u.id);
        onClose();
      }
    })));
  }
  Object.assign(window, {
    BeamSheet,
    BeamOptionRow: OptionRow,
    BeamUserAvatar: UserAvatar,
    QualitySheet,
    AudioSheet,
    SubtitleSheet,
    CastSheet,
    SyncPlaySheet,
    UserPickerSheet
  });
})();
})(); } catch (e) { __ds_ns.__errors.push({ path: "ui_kits/beam/PlayerSheets.jsx", error: String((e && e.message) || e) }); }

// ui_kits/beam/data.js
try { (() => {
// Shared fake catalog for the SpatialFin UI kits.
const SF_CATALOG = [{
  id: "bbb",
  title: "Big Buck Bunny",
  kind: "Movie",
  year: "2008",
  runtime: "9 min",
  rating: "G",
  stars: "8.0",
  genres: "Animation, Comedy, Family",
  progress: 79,
  poster: "../../assets/media/poster-bbb.png",
  backdrop: "../../assets/media/poster-bbb.png",
  overview: "A giant rabbit with a heart bigger than himself takes revenge on three rodents who tormented him and harassed the woodland creatures.",
  tags: ["4K", "HDR", "5.1"],
  downloaded: true
}, {
  id: "elephants",
  title: "Elephants Dream",
  kind: "Movie",
  year: "2006",
  runtime: "10 min",
  rating: "NR",
  stars: "5.8",
  genres: "Animation, Science Fiction",
  progress: 26,
  poster: "../../assets/media/poster-elephants.png",
  backdrop: "../../assets/media/backdrop-elephants.png",
  overview: "Two strange characters explore a capricious and seemingly infinite machine. The elder, Proog, acts as a tour-guide and protector to the increasingly skeptical Emo.",
  tags: ["4K", "5.1"],
  downloaded: false
}, {
  id: "spring",
  title: "Spring",
  kind: "Movie",
  year: "2019",
  runtime: "8 min",
  rating: "NR",
  stars: "7.9",
  genres: "Fantasy, Drama",
  progress: 0,
  poster: "../../assets/media/poster-spring.png",
  backdrop: "../../assets/media/poster-spring.png",
  overview: "A shepherd girl and her dog face ancient spirits in order to continue the cycle of life and bring the world a fresh new spring.",
  tags: ["4K", "HDR"],
  downloaded: false
}, {
  id: "sintel",
  title: "Sintel",
  kind: "Movie",
  year: "2010",
  runtime: "15 min",
  rating: "PG",
  stars: "7.5",
  genres: "Animation, Adventure",
  progress: 0,
  poster: "../../assets/media/poster-sintel.png",
  backdrop: "../../assets/media/poster-sintel.png",
  overview: "A lonely young woman, Sintel, helps and befriends a dragon she names Scales. When he is kidnapped, she embarks on a dangerous quest to find her lost friend.",
  tags: ["4K"],
  downloaded: false
}, {
  id: "tears",
  title: "Tears of Steel",
  kind: "Movie",
  year: "2012",
  runtime: "12 min",
  rating: "PG",
  stars: "6.9",
  genres: "Science Fiction, Action",
  progress: 0,
  poster: "../../assets/media/poster-tears.png",
  backdrop: "../../assets/media/poster-tears.png",
  overview: "In a dystopian Amsterdam, a group of warriors and scientists gather to stop an army of robots — but their success hinges on a man's painful memories.",
  tags: ["4K", "HDR"],
  downloaded: false
}, {
  id: "agent327",
  title: "Agent 327",
  kind: "Movie",
  year: "2017",
  runtime: "4 min",
  rating: "PG",
  stars: "7.6",
  genres: "Action, Comedy",
  progress: 0,
  poster: "../../assets/media/poster-agent327.png",
  backdrop: "../../assets/media/poster-agent327.png",
  overview: "Secret agent Hendrik IJzerbroot is ambushed in a barbershop. The clues lead toward a global conspiracy — and a very sharp razor.",
  tags: ["4K", "5.1"],
  downloaded: true
}, {
  id: "coffeerun",
  title: "Coffee Run",
  kind: "Movie",
  year: "2020",
  runtime: "4 min",
  rating: "NR",
  stars: "7.4",
  genres: "Drama, Animation",
  progress: 0,
  poster: "../../assets/media/poster-coffeerun.png",
  backdrop: "../../assets/media/poster-coffeerun.png",
  overview: "Fueled by caffeine, a young woman races through the highs and lows of a life lived one cup at a time.",
  tags: ["4K"],
  downloaded: false
}, {
  id: "charge",
  title: "Charge",
  kind: "Movie",
  year: "2022",
  runtime: "5 min",
  rating: "PG",
  stars: "7.1",
  genres: "Science Fiction",
  progress: 0,
  poster: "../../assets/media/poster-charge.png",
  backdrop: "../../assets/media/poster-charge.png",
  overview: "A lone soldier guards humanity's last power source through a long, dangerous night.",
  tags: ["4K", "HDR"],
  downloaded: false
}, {
  id: "glasshalf",
  title: "Glass Half",
  kind: "Movie",
  year: "2015",
  runtime: "3 min",
  rating: "G",
  stars: "6.8",
  genres: "Comedy",
  progress: 0,
  poster: "../../assets/media/poster-glasshalf.png",
  backdrop: "../../assets/media/poster-glasshalf.png",
  overview: "Two art critics argue over a painting until the disagreement spirals gloriously out of hand.",
  tags: ["HD"],
  downloaded: false
}, {
  id: "wingit",
  title: "Wing It!",
  kind: "Movie",
  year: "2023",
  runtime: "3 min",
  rating: "G",
  stars: "7.0",
  genres: "Adventure, Comedy",
  progress: 0,
  poster: "../../assets/media/poster-wing.png",
  backdrop: "../../assets/media/poster-wing.png",
  overview: "A would-be pilot pieces together a ramshackle flying machine and takes a leap of faith off the cliff's edge.",
  tags: ["4K"],
  downloaded: false
}];
const SF_FEATURED = {
  id: "sprite",
  title: "Sprite Fright",
  kind: "Movie",
  year: "2021",
  runtime: "10 min",
  rating: "PG",
  stars: "8.3",
  genres: "Animation, Comedy, Horror",
  backdrop: "../../assets/media/backdrop-sprite.png",
  poster: "../../assets/media/backdrop-sprite.png",
  overview: "When a rowdy group of teenagers visit the countryside, an encounter with the local wildlife quickly turns into a hilarious fight for survival.",
  tags: ["4K", "HDR", "Atmos"]
};
window.SF_CATALOG = SF_CATALOG;
window.SF_FEATURED = SF_FEATURED;

// --- Cast / "Play on" targets ---------------------------------------------
// A mix of video-capable sinks (TVs, this phone) and audio-only sinks
// (AVRs, speakers, headphones). The split between `video` and `audio`
// capability is what powers separate-output ("split") casting.
window.SF_CAST_DEVICES = [{
  id: "this",
  name: "This phone",
  type: "phone",
  video: true,
  audio: true,
  hint: "Beam · local"
}, {
  id: "lr-tv",
  name: "Living Room TV",
  type: "tv",
  video: true,
  audio: true,
  hint: "Chromecast · 4K HDR"
}, {
  id: "bedroom",
  name: "Bedroom Shield",
  type: "tv",
  video: true,
  audio: true,
  hint: "Android TV"
}, {
  id: "office",
  name: "Office Display",
  type: "tv",
  video: true,
  audio: true,
  hint: "Chromecast"
}, {
  id: "avr",
  name: "Living Room AVR",
  type: "speaker",
  video: false,
  audio: true,
  hint: "Atmos 7.1.4"
}, {
  id: "kitchen",
  name: "Kitchen Sonos",
  type: "speaker",
  video: false,
  audio: true,
  hint: "Sonos"
}, {
  id: "buds",
  name: "Pixel Buds Pro",
  type: "headphones",
  video: false,
  audio: true,
  hint: "Bluetooth"
}];

// --- SyncPlay (watch-together) --------------------------------------------
window.SF_SYNCPLAY_GROUPS = [{
  id: "movie-night",
  name: "Movie Night",
  members: 3,
  ping: 42
}, {
  id: "family-room",
  name: "Family Room",
  members: 2,
  ping: 28
}];
window.SF_SYNCPLAY_PEOPLE = [{
  id: "u1",
  name: "Ignacio",
  you: true,
  color: "#1F4876"
}, {
  id: "u2",
  name: "Mara",
  color: "#543F5E"
}, {
  id: "u3",
  name: "Devin",
  color: "#3C4758"
}];

// --- Universal-plugin Sources (phone) -------------------------------------
// Mirrors the TV "Sources" rows plus an on-device "Local files" source.
window.SF_BEAM_SOURCES = [{
  id: "yt",
  name: "YouTube",
  pluginId: "youtube",
  icon: "youtube",
  tint: "#C5402B",
  items: SF_CATALOG.slice(0, 6).map((m, i) => ({
    id: "yt-" + m.id,
    title: m.title,
    image: m.poster,
    subtitle: ["1.2M views", "428K views", "Featured", "Trending", "812K views", "New"][i % 6]
  }))
}, {
  id: "podcasts",
  name: "Podcasts",
  pluginId: "podcasts",
  icon: "podcast",
  tint: "#6E4FA3",
  items: SF_CATALOG.slice(3, 9).map((m, i) => ({
    id: "pd-" + m.id,
    title: m.title,
    image: m.poster,
    subtitle: ["Today · 38 min", "Mon · 1h 02m", "Episode 14", "New episode", "Episode 9", "Yesterday"][i % 6]
  }))
}, {
  id: "webdav",
  name: "NAS / WebDAV",
  pluginId: "webdav",
  icon: "hard-drive",
  tint: "#2C6E5B",
  items: SF_CATALOG.slice(2, 8).map(m => ({
    id: "nas-" + m.id,
    title: m.title,
    image: m.poster,
    subtitle: "MKV · 1080p"
  }))
}, {
  id: "local",
  name: "Local files",
  pluginId: "device",
  icon: "smartphone",
  tint: "#9A6A2C",
  items: SF_CATALOG.filter(m => m.downloaded).map(m => ({
    id: "loc-" + m.id,
    title: m.title,
    image: m.poster,
    subtitle: "On this device"
  }))
}];

// --- Jellyfin servers (switcher) ------------------------------------------
window.SF_SERVERS = [{
  id: "home",
  name: "Home Jellyfin",
  address: "http://192.168.1.42:8096"
}, {
  id: "cabin",
  name: "Cabin",
  address: "https://cabin.example.com"
}, {
  id: "demo",
  name: "Demo Server",
  address: "https://demo.jellyfin.org"
}];
window.SF_CURRENT_SERVER = "home";

// --- Jellyfin users (profile switcher) ------------------------------------
// `avatar` is the Jellyfin profile-image URL when the user has one set;
// otherwise a coloured letter avatar is shown (Jellyfin's default).
window.SF_USERS = [{
  id: "ignacio",
  name: "Ignacio M.",
  role: "Administrator",
  initials: "IM",
  color: "var(--tertiary-container)",
  textColor: "var(--on-tertiary-container)"
}, {
  id: "mara",
  name: "Mara",
  role: "User",
  initials: "MA",
  color: "#543F5E",
  avatar: "../../assets/music/album-mauve.png"
}, {
  id: "devin",
  name: "Devin",
  role: "User",
  initials: "DV",
  color: "#1F4876",
  avatar: "../../assets/music/album-blue.png"
}, {
  id: "kids",
  name: "Kids",
  role: "Restricted",
  initials: "K",
  color: "#2C6E5B"
}];
window.SF_CURRENT_USER = "ignacio";
})(); } catch (e) { __ds_ns.__errors.push({ path: "ui_kits/beam/data.js", error: String((e && e.message) || e) }); }

// ui_kits/tv/MovieDetailScreen.jsx
try { (() => {
// TV — Movie detail
function TvMovieDetail({
  movie,
  onBack,
  onPlay,
  onOpenOverflow
}) {
  const NS = window.SpatialFinDesignSystem_0d3fe7;
  const {
    Button,
    Pill,
    FocusCard
  } = NS;
  const more = (window.SF_TV_MOVIES || []).filter(m => m.id !== movie.id);
  const [fav, setFav] = React.useState(false);
  const [watched, setWatched] = React.useState(false);
  const resumeable = (movie.progress || 0) > 0;
  return /*#__PURE__*/React.createElement("div", null, /*#__PURE__*/React.createElement("div", {
    style: {
      position: "relative",
      height: 540
    }
  }, /*#__PURE__*/React.createElement("img", {
    src: movie.backdrop || movie.poster,
    alt: movie.title,
    style: {
      position: "absolute",
      inset: 0,
      width: "100%",
      height: "100%",
      objectFit: "cover"
    }
  }), /*#__PURE__*/React.createElement("div", {
    style: {
      position: "absolute",
      inset: 0,
      background: "linear-gradient(90deg, rgba(6,17,27,0.96) 0%, rgba(6,17,27,0.72) 40%, rgba(6,17,27,0.15) 78%)"
    }
  }), /*#__PURE__*/React.createElement("div", {
    style: {
      position: "absolute",
      inset: 0,
      background: "linear-gradient(0deg, var(--surface-app) 2%, rgba(6,17,27,0) 42%)"
    }
  }), /*#__PURE__*/React.createElement("div", {
    style: {
      position: "relative",
      padding: "0 56px",
      height: "100%",
      display: "flex",
      flexDirection: "column",
      justifyContent: "center",
      gap: 16,
      maxWidth: 720
    }
  }, /*#__PURE__*/React.createElement("button", {
    "data-focusable": true,
    "data-focus-first": true,
    onClick: onBack,
    className: "tv-back2",
    style: {
      position: "absolute",
      top: 28,
      left: 56,
      display: "inline-flex",
      alignItems: "center",
      gap: 8,
      height: 44,
      padding: "0 18px 0 14px",
      borderRadius: 999,
      border: "1px solid var(--glass-border)",
      background: "var(--glass-fill-strong)",
      color: "#fff",
      cursor: "pointer",
      fontSize: 15,
      fontWeight: 500
    }
  }, "\u2039 Back"), /*#__PURE__*/React.createElement("div", {
    className: "m3-label-large",
    style: {
      color: "var(--primary)",
      letterSpacing: 1.4,
      fontWeight: 700
    }
  }, "MOVIE"), /*#__PURE__*/React.createElement("div", {
    style: {
      fontSize: 64,
      lineHeight: "1.03",
      fontWeight: 700,
      color: "#fff"
    }
  }, movie.title), /*#__PURE__*/React.createElement("div", {
    style: {
      display: "flex",
      gap: 10,
      flexWrap: "wrap"
    }
  }, /*#__PURE__*/React.createElement(Pill, {
    tone: "rating",
    icon: "star"
  }, movie.stars), /*#__PURE__*/React.createElement(Pill, null, movie.year), /*#__PURE__*/React.createElement(Pill, null, movie.runtime), /*#__PURE__*/React.createElement(Pill, null, movie.rating), movie.tags.map(t => /*#__PURE__*/React.createElement(Pill, {
    key: t,
    tone: "outline"
  }, t))), /*#__PURE__*/React.createElement("div", {
    className: "m3-body-large",
    style: {
      color: "rgba(255,255,255,0.82)",
      maxWidth: 600
    }
  }, movie.overview), /*#__PURE__*/React.createElement("div", {
    style: {
      color: "rgba(255,255,255,0.6)",
      fontSize: 15
    }
  }, movie.genres), /*#__PURE__*/React.createElement("div", {
    style: {
      display: "flex",
      gap: 14,
      marginTop: 4,
      flexWrap: "wrap"
    }
  }, /*#__PURE__*/React.createElement(Button, {
    variant: "filled",
    icon: "play",
    size: "lg",
    "data-focusable": true,
    onClick: () => onPlay(movie)
  }, resumeable ? `Resume · ${movie.progress}%` : "Play"), resumeable ? /*#__PURE__*/React.createElement(Button, {
    variant: "glass",
    icon: "rotate-ccw",
    size: "lg",
    "data-focusable": true,
    onClick: () => onPlay(movie)
  }, "Restart") : null, /*#__PURE__*/React.createElement(Button, {
    variant: "glass",
    icon: fav ? "heart" : "heart",
    size: "lg",
    "data-focusable": true,
    onClick: () => setFav(!fav)
  }, fav ? "Favorited" : "Favorite"), /*#__PURE__*/React.createElement(Button, {
    variant: "glass",
    icon: watched ? "check-check" : "check",
    size: "lg",
    "data-focusable": true,
    onClick: () => setWatched(!watched)
  }, watched ? "Watched" : "Mark watched"), /*#__PURE__*/React.createElement(Button, {
    variant: "glass",
    icon: "download",
    size: "lg",
    "data-focusable": true
  }, "Download"), /*#__PURE__*/React.createElement(Button, {
    variant: "glass",
    icon: "plus",
    size: "lg",
    "data-focusable": true
  }, "Watchlist"), /*#__PURE__*/React.createElement(Button, {
    variant: "glass",
    icon: "ellipsis",
    size: "lg",
    "data-focusable": true,
    onClick: () => onOpenOverflow && onOpenOverflow({
      kind: "movie",
      item: movie
    })
  }, "More")))), /*#__PURE__*/React.createElement("div", {
    style: {
      marginTop: -28,
      position: "relative",
      padding: "0 56px 56px"
    }
  }, /*#__PURE__*/React.createElement("div", {
    style: {
      display: "flex",
      alignItems: "center",
      gap: 12
    }
  }, /*#__PURE__*/React.createElement("span", {
    style: {
      width: 4,
      height: 22,
      borderRadius: 99,
      background: "var(--primary)"
    }
  }), /*#__PURE__*/React.createElement("span", {
    className: "m3-headline-small",
    style: {
      color: "var(--text-primary)",
      fontWeight: 700
    }
  }, "More Like This")), /*#__PURE__*/React.createElement("div", {
    "data-row": true,
    style: {
      display: "flex",
      gap: 22,
      overflowX: "auto",
      padding: "18px 0 12px",
      scrollbarWidth: "none"
    }
  }, more.map(m => /*#__PURE__*/React.createElement(FocusCard, {
    key: m.id,
    title: m.title,
    subtitle: `${m.year} · ${m.runtime}`,
    image: m.poster,
    width: 190,
    onClick: () => onPlay(m)
  })))), /*#__PURE__*/React.createElement("style", null, `.tv-back2:focus{outline:3px solid var(--primary);outline-offset:3px}`));
}
window.TvMovieDetail = TvMovieDetail;
})(); } catch (e) { __ds_ns.__errors.push({ path: "ui_kits/tv/MovieDetailScreen.jsx", error: String((e && e.message) || e) }); }

// ui_kits/tv/ShowDetailScreen.jsx
try { (() => {
// TV — Show detail: hero + seasons + episodes
function TvShowDetail({
  show,
  onBack,
  onPlay,
  onOpenOverflow
}) {
  const NS = window.SpatialFinDesignSystem_0d3fe7;
  const {
    Button,
    Pill,
    SeasonTabs,
    EpisodeCard
  } = NS;
  const seasonKeys = Object.keys(show.seasons).map(Number);
  const [season, setSeason] = React.useState(seasonKeys[0]);
  const eps = show.seasons[season] || [];
  const next = eps.find(e => e.progress > 0 && e.progress < 100) || eps[0];
  const [fav, setFav] = React.useState(false);
  const [watched, setWatched] = React.useState(false);
  const resumeable = next && (next.progress || 0) > 0 && (next.progress || 0) < 100;
  return /*#__PURE__*/React.createElement("div", null, /*#__PURE__*/React.createElement("div", {
    style: {
      position: "relative",
      height: 520
    }
  }, /*#__PURE__*/React.createElement("img", {
    src: show.backdrop,
    alt: show.title,
    style: {
      position: "absolute",
      inset: 0,
      width: "100%",
      height: "100%",
      objectFit: "cover"
    }
  }), /*#__PURE__*/React.createElement("div", {
    style: {
      position: "absolute",
      inset: 0,
      background: "linear-gradient(90deg, rgba(6,17,27,0.96) 0%, rgba(6,17,27,0.72) 40%, rgba(6,17,27,0.15) 78%)"
    }
  }), /*#__PURE__*/React.createElement("div", {
    style: {
      position: "absolute",
      inset: 0,
      background: "linear-gradient(0deg, var(--surface-app) 2%, rgba(6,17,27,0) 42%)"
    }
  }), /*#__PURE__*/React.createElement("div", {
    style: {
      position: "relative",
      padding: "0 56px",
      height: "100%",
      display: "flex",
      flexDirection: "column",
      justifyContent: "center",
      gap: 16,
      maxWidth: 720
    }
  }, /*#__PURE__*/React.createElement("button", {
    "data-focusable": true,
    "data-focus-first": true,
    onClick: onBack,
    className: "tv-back",
    style: {
      position: "absolute",
      top: 28,
      left: 56,
      display: "inline-flex",
      alignItems: "center",
      gap: 8,
      height: 44,
      padding: "0 18px 0 14px",
      borderRadius: 999,
      border: "1px solid var(--glass-border)",
      background: "var(--glass-fill-strong)",
      color: "#fff",
      cursor: "pointer",
      fontSize: 15,
      fontWeight: 500
    }
  }, "\u2039 Back"), /*#__PURE__*/React.createElement("div", {
    className: "m3-label-large",
    style: {
      color: "var(--primary)",
      letterSpacing: 1.4,
      fontWeight: 700
    }
  }, "SERIES"), /*#__PURE__*/React.createElement("div", {
    style: {
      fontSize: 60,
      lineHeight: "1.04",
      fontWeight: 700,
      color: "#fff"
    }
  }, show.title), /*#__PURE__*/React.createElement("div", {
    style: {
      display: "flex",
      gap: 10,
      flexWrap: "wrap"
    }
  }, /*#__PURE__*/React.createElement(Pill, {
    tone: "rating",
    icon: "star"
  }, show.stars), /*#__PURE__*/React.createElement(Pill, null, show.year), /*#__PURE__*/React.createElement(Pill, null, seasonKeys.length, " season", seasonKeys.length > 1 ? "s" : ""), /*#__PURE__*/React.createElement(Pill, null, show.rating), show.tags.map(t => /*#__PURE__*/React.createElement(Pill, {
    key: t,
    tone: "outline"
  }, t))), /*#__PURE__*/React.createElement("div", {
    className: "m3-body-large",
    style: {
      color: "rgba(255,255,255,0.82)",
      maxWidth: 600
    }
  }, show.overview), /*#__PURE__*/React.createElement("div", {
    style: {
      display: "flex",
      gap: 14,
      marginTop: 4,
      flexWrap: "wrap"
    }
  }, /*#__PURE__*/React.createElement(Button, {
    variant: "filled",
    icon: "play",
    size: "lg",
    "data-focusable": true,
    onClick: () => onPlay(show, next)
  }, resumeable ? `Resume S${season} E${next.n}` : `Play S${season} E1`), resumeable ? /*#__PURE__*/React.createElement(Button, {
    variant: "glass",
    icon: "rotate-ccw",
    size: "lg",
    "data-focusable": true,
    onClick: () => onPlay(show, {
      ...next,
      progress: 0
    })
  }, "Restart episode") : null, /*#__PURE__*/React.createElement(Button, {
    variant: "glass",
    icon: fav ? "heart" : "heart",
    size: "lg",
    "data-focusable": true,
    onClick: () => setFav(!fav)
  }, fav ? "Favorited" : "Favorite"), /*#__PURE__*/React.createElement(Button, {
    variant: "glass",
    icon: watched ? "check-check" : "check",
    size: "lg",
    "data-focusable": true,
    onClick: () => setWatched(!watched)
  }, watched ? "Watched" : "Mark watched"), /*#__PURE__*/React.createElement(Button, {
    variant: "glass",
    icon: "plus",
    size: "lg",
    "data-focusable": true
  }, "Watchlist"), /*#__PURE__*/React.createElement(Button, {
    variant: "glass",
    icon: "ellipsis",
    size: "lg",
    "data-focusable": true,
    onClick: () => onOpenOverflow && onOpenOverflow({
      kind: "series",
      item: show
    })
  }, "More")))), /*#__PURE__*/React.createElement("div", {
    style: {
      marginTop: -28,
      position: "relative",
      padding: "0 56px 56px"
    }
  }, /*#__PURE__*/React.createElement("div", {
    style: {
      display: "flex",
      alignItems: "center",
      gap: 18,
      marginBottom: 6
    }
  }, /*#__PURE__*/React.createElement("span", {
    className: "m3-headline-small",
    style: {
      color: "var(--text-primary)",
      fontWeight: 700
    }
  }, "Episodes"), /*#__PURE__*/React.createElement(SeasonTabs, {
    seasons: seasonKeys,
    active: season,
    onChange: setSeason,
    style: {
      flex: 1
    }
  })), /*#__PURE__*/React.createElement("div", {
    "data-row": true,
    style: {
      display: "flex",
      gap: 22,
      overflowX: "auto",
      padding: "18px 0 12px",
      scrollbarWidth: "none"
    }
  }, eps.map(e => /*#__PURE__*/React.createElement(EpisodeCard, {
    key: e.n,
    number: e.n,
    title: e.title,
    still: e.still,
    runtime: e.runtime,
    synopsis: e.synopsis,
    progress: e.progress || null,
    width: 360,
    onClick: () => onPlay(show, e)
  })))), /*#__PURE__*/React.createElement("style", null, `.tv-back:focus{outline:3px solid var(--primary);outline-offset:3px}`));
}
window.TvShowDetail = TvShowDetail;
})(); } catch (e) { __ds_ns.__errors.push({ path: "ui_kits/tv/ShowDetailScreen.jsx", error: String((e && e.message) || e) }); }

// ui_kits/tv/TvHomeScreen.jsx
try { (() => {
// TV — Home / Browse
function TvHome({
  onOpen,
  onPlayMaTrack,
  onSourceSeeAll,
  onOpenOverflow
}) {
  const NS = window.SpatialFinDesignSystem_0d3fe7;
  const {
    FocusCard,
    Button,
    Pill,
    Icon
  } = NS;
  const F = window.SF_TV_FEATURED;
  const shows = window.SF_TV_SHOWS;
  const movies = window.SF_TV_MOVIES;
  const cont = window.SF_TV_CONTINUE;
  const maShelf = window.SF_MA_SHELF || [];
  const sources = window.SF_TV_SOURCES || [];
  const [fav, setFav] = React.useState(false);
  const [watched, setWatched] = React.useState(false);
  const setMoreOpen = v => onOpenOverflow && onOpenOverflow({
    kind: "episode",
    item: {
      ...F,
      seriesTitle: F.title,
      season: 1,
      episode: 1
    }
  });

  // pre-build a "Next Up" set: first unwatched episode of each show
  const nextUp = (shows || []).map(s => {
    const seasonKeys = Object.keys(s.seasons).map(Number);
    for (const sk of seasonKeys) {
      const eps = s.seasons[sk];
      const ep = eps.find(e => (e.progress || 0) < 100);
      if (ep) return {
        id: "nu-" + s.id + "-" + sk + "-" + ep.n,
        title: s.title,
        subtitle: `S${sk} E${ep.n} · ${ep.title}`,
        image: ep.still,
        progress: ep.progress || 0,
        show: s.id
      };
    }
    return null;
  }).filter(Boolean);
  const Shelf = ({
    title,
    onSeeAll,
    children
  }) => /*#__PURE__*/React.createElement("div", {
    style: {
      marginTop: 28
    }
  }, /*#__PURE__*/React.createElement("div", {
    style: {
      display: "flex",
      alignItems: "center",
      gap: 12,
      padding: "0 56px"
    }
  }, /*#__PURE__*/React.createElement("span", {
    style: {
      width: 4,
      height: 22,
      borderRadius: 99,
      background: "var(--primary)"
    }
  }), /*#__PURE__*/React.createElement("span", {
    className: "m3-headline-small",
    style: {
      color: "var(--text-primary)",
      fontWeight: 700
    }
  }, title), /*#__PURE__*/React.createElement("span", {
    style: {
      flex: 1
    }
  }), onSeeAll ? /*#__PURE__*/React.createElement("button", {
    "data-focusable": true,
    type: "button",
    onClick: onSeeAll,
    onMouseEnter: e => e.currentTarget.focus(),
    className: "tv-seeall",
    style: {
      display: "inline-flex",
      alignItems: "center",
      gap: 6,
      height: 38,
      padding: "0 16px",
      borderRadius: 999,
      border: "1px solid var(--border-strong)",
      background: "transparent",
      color: "var(--text-secondary)",
      fontSize: 14,
      fontWeight: 600,
      cursor: "pointer"
    }
  }, "See all ", /*#__PURE__*/React.createElement(Icon, {
    name: "chevron-right",
    size: 16
  })) : null), /*#__PURE__*/React.createElement("div", {
    "data-row": true,
    style: {
      display: "flex",
      gap: 22,
      overflowX: "auto",
      padding: "16px 56px 8px",
      scrollbarWidth: "none"
    }
  }, children));

  // Music Assistant tile — landscape glass card with artwork + title/artist
  const MaTile = ({
    t,
    onPlay,
    first
  }) => {
    const [foc, setFoc] = React.useState(false);
    return /*#__PURE__*/React.createElement("div", {
      "data-focusable": true,
      tabIndex: 0,
      "data-focus-first": first ? "" : undefined,
      onFocus: () => setFoc(true),
      onBlur: () => setFoc(false),
      onMouseEnter: e => e.currentTarget.focus(),
      onClick: onPlay,
      style: {
        width: 220,
        flex: "0 0 auto",
        borderRadius: "var(--radius-md)",
        padding: 10,
        background: "var(--surface-container-high)",
        outline: foc ? "3px solid var(--primary)" : "3px solid transparent",
        outlineOffset: 3,
        transform: foc ? "scale(var(--tv-focus-scale))" : "scale(1)",
        transition: "transform var(--duration-fast) var(--ease-standard), outline-color var(--duration-fast)",
        boxShadow: foc ? "0 14px 44px -8px rgba(125,218,255,0.5)" : "none",
        cursor: "pointer",
        display: "flex",
        gap: 12,
        alignItems: "center"
      }
    }, /*#__PURE__*/React.createElement("img", {
      src: t.artwork,
      alt: "",
      style: {
        width: 64,
        height: 64,
        borderRadius: 10,
        flexShrink: 0,
        objectFit: "cover"
      }
    }), /*#__PURE__*/React.createElement("div", {
      style: {
        minWidth: 0,
        flex: 1
      }
    }, /*#__PURE__*/React.createElement("div", {
      style: {
        fontSize: 14,
        fontWeight: 600,
        color: "var(--text-primary)",
        whiteSpace: "nowrap",
        overflow: "hidden",
        textOverflow: "ellipsis"
      }
    }, t.title), /*#__PURE__*/React.createElement("div", {
      style: {
        fontSize: 12,
        color: "var(--text-secondary)",
        whiteSpace: "nowrap",
        overflow: "hidden",
        textOverflow: "ellipsis",
        marginTop: 2
      }
    }, t.artist), foc ? /*#__PURE__*/React.createElement("div", {
      style: {
        marginTop: 6,
        fontSize: 11,
        color: "var(--primary)",
        fontWeight: 600,
        letterSpacing: 0.5
      }
    }, "ENTER \xB7 PLAY  \xB7  LONG-PRESS \xB7 QUEUE") : null));
  };
  return /*#__PURE__*/React.createElement("div", null, /*#__PURE__*/React.createElement("div", {
    style: {
      position: "relative",
      height: 560
    }
  }, /*#__PURE__*/React.createElement("img", {
    src: F.backdrop,
    alt: F.title,
    style: {
      position: "absolute",
      inset: 0,
      width: "100%",
      height: "100%",
      objectFit: "cover"
    }
  }), /*#__PURE__*/React.createElement("div", {
    style: {
      position: "absolute",
      inset: 0,
      background: "linear-gradient(90deg, rgba(6,17,27,0.95) 0%, rgba(6,17,27,0.7) 38%, rgba(6,17,27,0.1) 75%)"
    }
  }), /*#__PURE__*/React.createElement("div", {
    style: {
      position: "absolute",
      inset: 0,
      background: "linear-gradient(0deg, var(--surface-app) 2%, rgba(6,17,27,0) 40%)"
    }
  }), /*#__PURE__*/React.createElement("div", {
    style: {
      position: "relative",
      padding: "0 56px",
      height: "100%",
      display: "flex",
      flexDirection: "column",
      justifyContent: "center",
      gap: 18,
      maxWidth: 820
    }
  }, /*#__PURE__*/React.createElement("div", {
    className: "m3-label-large",
    style: {
      color: "var(--primary)",
      letterSpacing: 1.4,
      fontWeight: 700
    }
  }, "FEATURED SERIES"), /*#__PURE__*/React.createElement("div", {
    style: {
      fontSize: 72,
      lineHeight: "1.02",
      fontWeight: 700,
      color: "#fff",
      textShadow: "0 4px 24px rgba(0,0,0,0.5)"
    }
  }, F.title), /*#__PURE__*/React.createElement("div", {
    style: {
      display: "flex",
      gap: 10,
      alignItems: "center",
      flexWrap: "wrap"
    }
  }, /*#__PURE__*/React.createElement(Pill, {
    tone: "rating",
    icon: "star"
  }, F.stars), /*#__PURE__*/React.createElement(Pill, null, F.year), /*#__PURE__*/React.createElement(Pill, null, F.runtime), /*#__PURE__*/React.createElement(Pill, null, F.rating), F.tags.map(t => /*#__PURE__*/React.createElement(Pill, {
    key: t,
    tone: "outline"
  }, t))), /*#__PURE__*/React.createElement("div", {
    className: "m3-body-large",
    style: {
      color: "rgba(255,255,255,0.82)",
      maxWidth: 620
    }
  }, F.overview), /*#__PURE__*/React.createElement("div", {
    style: {
      display: "flex",
      gap: 16,
      marginTop: 6,
      flexWrap: "wrap"
    }
  }, /*#__PURE__*/React.createElement(Button, {
    variant: "filled",
    icon: "play",
    size: "lg",
    "data-focusable": true,
    "data-focus-first": true,
    onClick: () => onOpen({
      type: "show",
      id: F.id
    })
  }, "Play S1 E1"), /*#__PURE__*/React.createElement(Button, {
    variant: "glass",
    icon: "info",
    size: "lg",
    "data-focusable": true,
    onClick: () => onOpen({
      type: "show",
      id: F.id
    })
  }, "More info"), /*#__PURE__*/React.createElement(Button, {
    variant: "glass",
    icon: "plus",
    size: "lg",
    "data-focusable": true
  }, "Watchlist"), /*#__PURE__*/React.createElement(Button, {
    variant: "glass",
    icon: fav ? "heart" : "heart",
    size: "lg",
    "data-focusable": true,
    onClick: () => setFav(!fav)
  }, fav ? "Favorited" : "Favorite"), /*#__PURE__*/React.createElement(Button, {
    variant: "glass",
    icon: watched ? "check-check" : "check",
    size: "lg",
    "data-focusable": true,
    onClick: () => setWatched(!watched)
  }, watched ? "Watched" : "Mark watched"), /*#__PURE__*/React.createElement(Button, {
    variant: "glass",
    icon: "ellipsis",
    size: "lg",
    "data-focusable": true,
    onClick: () => setMoreOpen(true)
  }, "More")))), /*#__PURE__*/React.createElement("div", {
    style: {
      marginTop: -40,
      position: "relative",
      paddingBottom: 56
    }
  }, cont.length ? /*#__PURE__*/React.createElement(Shelf, {
    title: "Continue Watching"
  }, cont.map(m => /*#__PURE__*/React.createElement(FocusCard, {
    key: m.id,
    orientation: "landscape",
    width: 340,
    title: m.title,
    subtitle: m.subtitle,
    image: m.image,
    progress: m.progress,
    onClick: () => onOpen(m.show ? {
      type: "show",
      id: m.show
    } : {
      type: "movie",
      id: m.movie
    })
  }))) : null, nextUp.length ? /*#__PURE__*/React.createElement(Shelf, {
    title: "Next Up"
  }, nextUp.map(n => /*#__PURE__*/React.createElement(FocusCard, {
    key: n.id,
    orientation: "landscape",
    width: 340,
    title: n.title,
    subtitle: n.subtitle,
    image: n.image,
    progress: n.progress || null,
    onClick: () => onOpen({
      type: "show",
      id: n.show
    })
  }))) : null, maShelf.length ? /*#__PURE__*/React.createElement(Shelf, {
    title: "Music Assistant"
  }, maShelf.map((t, i) => /*#__PURE__*/React.createElement(MaTile, {
    key: t.id,
    t: t,
    first: i === 0 && cont.length === 0 && nextUp.length === 0,
    onPlay: () => onPlayMaTrack && onPlayMaTrack(t)
  }))) : null, sources.map(src => /*#__PURE__*/React.createElement(Shelf, {
    key: src.id,
    title: src.name,
    onSeeAll: () => onSourceSeeAll && onSourceSeeAll(src)
  }, src.items.map(it => /*#__PURE__*/React.createElement(FocusCard, {
    key: it.id,
    title: it.title,
    subtitle: it.subtitle,
    image: it.image,
    width: 210,
    orientation: src.pluginId === "youtube" ? "landscape" : "portrait",
    onClick: () => onSourceSeeAll && onSourceSeeAll(src)
  })))), /*#__PURE__*/React.createElement(Shelf, {
    title: "TV Shows"
  }, shows.map(s => /*#__PURE__*/React.createElement(FocusCard, {
    key: s.id,
    title: s.title,
    subtitle: s.kind,
    image: s.poster,
    width: 190,
    onClick: () => onOpen({
      type: "show",
      id: s.id
    })
  })), movies.slice(0, 2).map(m => /*#__PURE__*/React.createElement(FocusCard, {
    key: "x" + m.id,
    title: m.title,
    subtitle: "Movie",
    image: m.poster,
    width: 190,
    onClick: () => onOpen({
      type: "movie",
      id: m.id
    })
  }))), /*#__PURE__*/React.createElement(Shelf, {
    title: "Movies"
  }, movies.map(m => /*#__PURE__*/React.createElement(FocusCard, {
    key: m.id,
    title: m.title,
    subtitle: `${m.year} · ${m.runtime}`,
    image: m.poster,
    width: 190,
    progress: m.progress || null,
    onClick: () => onOpen({
      type: "movie",
      id: m.id
    })
  })))), /*#__PURE__*/React.createElement("style", null, `.tv-seeall:focus{outline:3px solid var(--primary);outline-offset:3px;background:var(--surface-container-high);color:var(--text-primary)}`));
}
window.TvHome = TvHome;
})(); } catch (e) { __ds_ns.__errors.push({ path: "ui_kits/tv/TvHomeScreen.jsx", error: String((e && e.message) || e) }); }

// ui_kits/tv/TvLibraryScreen.jsx
try { (() => {
// TV — Library: filter tabs + dense poster grid
function TvLibrary({
  initialFilter = "all",
  onOpen
}) {
  const NS = window.SpatialFinDesignSystem_0d3fe7;
  const {
    FocusCard
  } = NS;
  const [filter, setFilter] = React.useState(initialFilter);
  const all = window.SF_TV_ALL || [];
  const items = filter === "all" ? all : all.filter(it => filter === "movies" ? it.kind === "movie" : it.kind === "show");
  React.useEffect(() => {
    if (window.__tvFocusInit) window.__tvFocusInit();
  }, []);
  const filters = [{
    id: "all",
    label: "All"
  }, {
    id: "movies",
    label: "Movies"
  }, {
    id: "shows",
    label: "TV Shows"
  }];
  return /*#__PURE__*/React.createElement("div", {
    style: {
      padding: "48px 56px 56px 132px",
      minHeight: "100%",
      boxSizing: "border-box"
    }
  }, /*#__PURE__*/React.createElement("div", {
    style: {
      display: "flex",
      alignItems: "center",
      gap: 16,
      marginBottom: 26
    }
  }, /*#__PURE__*/React.createElement("span", {
    style: {
      width: 4,
      height: 28,
      borderRadius: 99,
      background: "var(--primary)"
    }
  }), /*#__PURE__*/React.createElement("span", {
    style: {
      fontSize: 40,
      fontWeight: 700,
      color: "var(--text-primary)"
    }
  }, "Library"), /*#__PURE__*/React.createElement("span", {
    style: {
      flex: 1
    }
  }), /*#__PURE__*/React.createElement("div", {
    "data-row": true,
    style: {
      display: "flex",
      gap: 12
    }
  }, filters.map(f => {
    const on = f.id === filter;
    return /*#__PURE__*/React.createElement("button", {
      key: f.id,
      type: "button",
      tabIndex: 0,
      "data-focusable": "",
      "data-focus-first": f.id === "all" ? "" : undefined,
      onClick: () => setFilter(f.id),
      onMouseEnter: e => e.currentTarget.focus(),
      className: "tv-lib-tab",
      style: {
        height: 48,
        padding: "0 26px",
        borderRadius: "var(--radius-full)",
        border: on ? "1px solid transparent" : "1px solid var(--border-strong)",
        background: on ? "var(--primary)" : "transparent",
        color: on ? "var(--on-primary)" : "var(--text-secondary)",
        fontSize: 16,
        fontWeight: 600,
        cursor: "pointer"
      }
    }, f.label);
  }))), /*#__PURE__*/React.createElement("div", {
    style: {
      display: "grid",
      gridTemplateColumns: "repeat(6, 1fr)",
      gap: "30px 22px"
    }
  }, items.map(it => /*#__PURE__*/React.createElement(FocusCard, {
    key: it.kind + it.id,
    title: it.title,
    subtitle: it.type,
    image: it.image,
    width: "100%",
    progress: it.progress || null,
    badge: it.downloaded ? /*#__PURE__*/React.createElement(NS.Badge, {
      tone: "accent",
      icon: "download"
    }) : null,
    onClick: () => onOpen({
      type: it.kind,
      id: it.id
    })
  }))), /*#__PURE__*/React.createElement("style", null, `.tv-lib-tab:focus{outline:3px solid var(--primary);outline-offset:3px}`));
}
window.TvLibrary = TvLibrary;
})(); } catch (e) { __ds_ns.__errors.push({ path: "ui_kits/tv/TvLibraryScreen.jsx", error: String((e && e.message) || e) }); }

// ui_kits/tv/TvNowPlayingScreen.jsx
try { (() => {
// TV — Music Assistant Now Playing (full screen).
// Tap-target for tapping the MaMiniPlayer; mirrors MaNowPlayingScreen.kt in spirit
// (large artwork, scrubber, transport, SendSpin player tag, queue panel access).
function TvNowPlaying({
  track,
  phase,
  selectedPlayer,
  queue,
  position,
  duration,
  onBack,
  onPlayPause,
  onNext,
  onPrev,
  onStop,
  onPickPlayer,
  onOpenQueue
}) {
  const NS = window.SpatialFinDesignSystem_0d3fe7;
  const {
    Icon,
    ProgressBar
  } = NS;
  if (!track) return null;
  const fmt = s => {
    s = Math.max(0, Math.floor(s));
    return Math.floor(s / 60) + ":" + String(s % 60).padStart(2, "0");
  };
  const ctrl = (icon, label, opts = {}) => /*#__PURE__*/React.createElement("button", {
    "data-focusable": "",
    onClick: opts.onClick,
    onMouseEnter: e => e.currentTarget.focus(),
    "aria-label": label,
    title: label,
    className: "tv-pctrl",
    style: {
      width: opts.big ? 84 : 56,
      height: opts.big ? 84 : 56,
      borderRadius: "50%",
      border: "1px solid var(--glass-border)",
      background: opts.active ? "var(--primary)" : "var(--glass-fill-strong)",
      color: opts.active ? "var(--on-primary)" : "#fff",
      cursor: "pointer",
      display: "inline-flex",
      alignItems: "center",
      justifyContent: "center"
    }
  }, /*#__PURE__*/React.createElement(Icon, {
    name: icon,
    size: opts.big ? 36 : 24
  }));
  return /*#__PURE__*/React.createElement("div", {
    style: {
      position: "absolute",
      inset: 0,
      background: "var(--surface-app)",
      display: "flex",
      alignItems: "center",
      justifyContent: "center",
      overflow: "hidden"
    }
  }, /*#__PURE__*/React.createElement("img", {
    src: track.artwork,
    alt: "",
    style: {
      position: "absolute",
      inset: 0,
      width: "100%",
      height: "100%",
      objectFit: "cover",
      filter: "blur(60px) saturate(1.2)",
      transform: "scale(1.15)",
      opacity: 0.55
    }
  }), /*#__PURE__*/React.createElement("div", {
    style: {
      position: "absolute",
      inset: 0,
      background: "linear-gradient(180deg, rgba(6,17,27,0.3) 0%, rgba(6,17,27,0.8) 100%)"
    }
  }), /*#__PURE__*/React.createElement("button", {
    "data-focusable": "",
    "data-focus-first": true,
    onClick: onBack,
    onMouseEnter: e => e.currentTarget.focus(),
    className: "tv-pctrl",
    "aria-label": "Back",
    style: {
      position: "absolute",
      top: 32,
      left: 56,
      zIndex: 5,
      height: 56,
      padding: "0 22px 0 18px",
      borderRadius: 999,
      border: "1px solid var(--glass-border)",
      background: "var(--glass-fill-strong)",
      color: "#fff",
      display: "inline-flex",
      alignItems: "center",
      gap: 8,
      fontSize: 16,
      fontWeight: 500,
      cursor: "pointer"
    }
  }, /*#__PURE__*/React.createElement(Icon, {
    name: "chevron-down",
    size: 22
  }), " Close"), /*#__PURE__*/React.createElement("div", {
    style: {
      position: "relative",
      display: "flex",
      gap: 56,
      alignItems: "center",
      padding: "0 80px",
      width: "100%",
      maxWidth: 1380,
      boxSizing: "border-box"
    }
  }, /*#__PURE__*/React.createElement("div", {
    style: {
      width: 360,
      height: 360,
      borderRadius: "var(--radius-lg)",
      overflow: "hidden",
      boxShadow: "0 30px 80px -10px rgba(0,0,0,0.6)",
      flexShrink: 0,
      background: "var(--surface-container-low)"
    }
  }, /*#__PURE__*/React.createElement("img", {
    src: track.artwork,
    alt: "",
    style: {
      width: "100%",
      height: "100%",
      objectFit: "cover"
    }
  })), /*#__PURE__*/React.createElement("div", {
    style: {
      flex: 1,
      minWidth: 0,
      display: "flex",
      flexDirection: "column",
      gap: 18
    }
  }, /*#__PURE__*/React.createElement("div", {
    className: "m3-label-large",
    style: {
      color: "var(--primary)",
      letterSpacing: 1.4,
      fontWeight: 700
    }
  }, "NOW PLAYING"), /*#__PURE__*/React.createElement("div", {
    style: {
      fontSize: 56,
      lineHeight: "1.04",
      fontWeight: 700,
      color: "#fff",
      textShadow: "0 2px 18px rgba(0,0,0,0.5)"
    }
  }, track.title), /*#__PURE__*/React.createElement("div", {
    className: "m3-headline-small",
    style: {
      color: "rgba(255,255,255,0.82)",
      fontWeight: 500
    }
  }, track.artist, track.album ? " · " + track.album : ""), /*#__PURE__*/React.createElement("div", {
    style: {
      display: "flex",
      alignItems: "center",
      gap: 16,
      marginTop: 12
    }
  }, /*#__PURE__*/React.createElement("span", {
    style: {
      color: "#fff",
      fontFamily: "var(--font-mono)",
      fontSize: 16,
      minWidth: 50
    }
  }, fmt(position)), /*#__PURE__*/React.createElement("div", {
    style: {
      flex: 1
    }
  }, /*#__PURE__*/React.createElement(ProgressBar, {
    value: position / duration * 100,
    height: 8
  })), /*#__PURE__*/React.createElement("span", {
    style: {
      color: "rgba(255,255,255,0.7)",
      fontFamily: "var(--font-mono)",
      fontSize: 16,
      minWidth: 50,
      textAlign: "right"
    }
  }, fmt(duration))), /*#__PURE__*/React.createElement("div", {
    style: {
      display: "flex",
      alignItems: "center",
      gap: 18,
      marginTop: 10
    }
  }, ctrl("skip-back", "Previous", {
    onClick: onPrev
  }), ctrl(phase === "playing" ? "pause" : "play", phase === "playing" ? "Pause" : "Play", {
    big: true,
    onClick: onPlayPause
  }), ctrl("skip-forward", "Next", {
    onClick: onNext
  }), /*#__PURE__*/React.createElement("span", {
    style: {
      flex: 1
    }
  }), ctrl("square", "Stop", {
    onClick: onStop
  }), ctrl("list-music", "Queue", {
    onClick: onOpenQueue
  }), /*#__PURE__*/React.createElement("button", {
    "data-focusable": "",
    onClick: onPickPlayer,
    onMouseEnter: e => e.currentTarget.focus(),
    className: "tv-pctrl",
    "aria-label": "Send to",
    title: "Send to",
    style: {
      height: 56,
      padding: "0 22px 0 18px",
      borderRadius: 999,
      border: "1px solid var(--glass-border)",
      background: "var(--glass-fill-strong)",
      color: "#fff",
      display: "inline-flex",
      alignItems: "center",
      gap: 10,
      fontSize: 15,
      fontWeight: 600,
      cursor: "pointer"
    }
  }, /*#__PURE__*/React.createElement(Icon, {
    name: "cast",
    size: 20
  }), "SendSpin \xB7 ", selectedPlayer || "This device")), phase === "preparing" ? /*#__PURE__*/React.createElement("div", {
    style: {
      marginTop: 18,
      padding: "12px 16px",
      borderRadius: 12,
      background: "var(--accent-container)",
      color: "var(--on-accent-container)",
      display: "inline-flex",
      alignItems: "center",
      gap: 10,
      fontSize: 14,
      alignSelf: "flex-start"
    }
  }, /*#__PURE__*/React.createElement(Icon, {
    name: "loader",
    size: 18,
    style: {
      animation: "tv-spin 1s linear infinite"
    }
  }), "Preparing audio\u2026 Music Assistant is loading the queue.") : null, queue && queue.length > 1 ? /*#__PURE__*/React.createElement("div", {
    style: {
      marginTop: 22,
      display: "flex",
      alignItems: "center",
      gap: 12
    }
  }, /*#__PURE__*/React.createElement("span", {
    className: "m3-label-large",
    style: {
      color: "var(--text-secondary)",
      letterSpacing: 1,
      marginRight: 6
    }
  }, "UP NEXT"), queue.slice(1, 4).map(t => /*#__PURE__*/React.createElement("span", {
    key: t.id,
    style: {
      display: "inline-flex",
      alignItems: "center",
      gap: 8,
      padding: "6px 12px 6px 6px",
      borderRadius: 999,
      background: "var(--glass-fill-strong)",
      border: "1px solid var(--glass-border)",
      color: "#fff",
      fontSize: 13
    }
  }, /*#__PURE__*/React.createElement("img", {
    src: t.artwork,
    alt: "",
    style: {
      width: 26,
      height: 26,
      borderRadius: 6
    }
  }), /*#__PURE__*/React.createElement("span", {
    style: {
      maxWidth: 180,
      whiteSpace: "nowrap",
      overflow: "hidden",
      textOverflow: "ellipsis"
    }
  }, t.title, " ", /*#__PURE__*/React.createElement("span", {
    style: {
      opacity: 0.7
    }
  }, "\xB7 ", t.artist))))) : null)), /*#__PURE__*/React.createElement("style", null, `@keyframes tv-spin { to { transform: rotate(360deg); } }`));
}
window.TvNowPlaying = TvNowPlaying;
})(); } catch (e) { __ds_ns.__errors.push({ path: "ui_kits/tv/TvNowPlayingScreen.jsx", error: String((e && e.message) || e) }); }

// ui_kits/tv/TvOverflowSheet.jsx
try { (() => {
function _extends() { return _extends = Object.assign ? Object.assign.bind() : function (n) { for (var e = 1; e < arguments.length; e++) { var t = arguments[e]; for (var r in t) ({}).hasOwnProperty.call(t, r) && (n[r] = t[r]); } return n; }, _extends.apply(null, arguments); }
// TV — Overflow ("More actions") sheet used from the Home hero and detail screens.
// Matches the SpatialFin app's overflow menu (MovieScreen / EpisodeScreen):
//   SyncPlay, Playback options, Go to series, Go to season, Edit external IDs,
//   Cast & audio output, Refresh metadata, Share, Delete.
//
// Renders as a centered 10-foot dialog. Rows are focusable for D-pad nav and
// activate on Enter; the dialog itself eats clicks outside the panel.
(function () {
  const NS = window.SpatialFinDesignSystem_0d3fe7;
  function ActionRow({
    icon,
    leading,
    label,
    sub,
    danger,
    admin,
    autoFocus,
    onClick
  }) {
    const ref = React.useRef(null);
    React.useEffect(() => {
      if (autoFocus && ref.current) ref.current.focus();
    }, [autoFocus]);
    const {
      Icon,
      Pill
    } = NS;
    return /*#__PURE__*/React.createElement("button", {
      ref: ref,
      "data-focusable": true,
      type: "button",
      onClick: onClick,
      onMouseEnter: e => e.currentTarget.focus(),
      style: {
        display: "flex",
        alignItems: "center",
        gap: 18,
        width: "100%",
        textAlign: "left",
        padding: "16px 22px",
        border: "none",
        background: "transparent",
        borderRadius: 18,
        color: danger ? "var(--error, #f5667a)" : "var(--text-primary)",
        cursor: "pointer",
        fontFamily: "var(--font-sans)"
      }
    }, leading ? leading : icon ? /*#__PURE__*/React.createElement("span", {
      style: {
        width: 48,
        height: 48,
        borderRadius: "50%",
        flexShrink: 0,
        background: danger ? "color-mix(in srgb, var(--error, #f5667a) 14%, transparent)" : "var(--surface-container-highest)",
        color: danger ? "var(--error, #f5667a)" : "var(--text-secondary)",
        display: "inline-flex",
        alignItems: "center",
        justifyContent: "center"
      }
    }, /*#__PURE__*/React.createElement(Icon, {
      name: icon,
      size: 22
    })) : null, /*#__PURE__*/React.createElement("span", {
      style: {
        flex: 1,
        minWidth: 0
      }
    }, /*#__PURE__*/React.createElement("span", {
      style: {
        display: "block",
        fontSize: 18,
        fontWeight: 600
      }
    }, label), sub ? /*#__PURE__*/React.createElement("span", {
      style: {
        display: "block",
        fontSize: 14,
        color: "var(--text-secondary)",
        marginTop: 2
      }
    }, sub) : null), admin ? /*#__PURE__*/React.createElement(Pill, {
      tone: "outline"
    }, "admin") : /*#__PURE__*/React.createElement(Icon, {
      name: "chevron-right",
      size: 20,
      color: "var(--text-secondary)"
    }));
  }
  function TvOverflowSheet({
    open,
    title,
    subtitle,
    actions,
    onClose
  }) {
    if (!open) return null;
    const {
      Icon
    } = NS;
    // ESC closes
    React.useEffect(() => {
      const onKey = e => {
        if (e.key === "Escape") onClose();
      };
      window.addEventListener("keydown", onKey);
      return () => window.removeEventListener("keydown", onKey);
    }, [onClose]);
    return /*#__PURE__*/React.createElement("div", {
      onClick: onClose,
      style: {
        position: "absolute",
        inset: 0,
        zIndex: 50,
        background: "rgba(0,0,0,0.65)",
        display: "flex",
        alignItems: "center",
        justifyContent: "center",
        animation: "tvOvFade 180ms ease both"
      }
    }, /*#__PURE__*/React.createElement("div", {
      onClick: e => e.stopPropagation(),
      style: {
        width: 640,
        maxHeight: "80%",
        display: "flex",
        flexDirection: "column",
        background: "var(--surface-container)",
        borderRadius: 28,
        border: "1px solid var(--border-subtle)",
        boxShadow: "0 30px 80px rgba(0,0,0,0.55)",
        overflow: "hidden",
        animation: "tvOvIn 240ms cubic-bezier(.2,.85,.25,1) both"
      }
    }, /*#__PURE__*/React.createElement("div", {
      style: {
        display: "flex",
        alignItems: "flex-start",
        gap: 16,
        padding: "22px 26px 14px"
      }
    }, /*#__PURE__*/React.createElement("div", {
      style: {
        flex: 1,
        minWidth: 0
      }
    }, /*#__PURE__*/React.createElement("div", {
      className: "m3-headline-small",
      style: {
        color: "var(--text-primary)",
        fontWeight: 700
      }
    }, title), subtitle ? /*#__PURE__*/React.createElement("div", {
      className: "m3-body-medium",
      style: {
        color: "var(--text-secondary)",
        marginTop: 4
      }
    }, subtitle) : null), /*#__PURE__*/React.createElement("button", {
      "data-focusable": true,
      type: "button",
      onClick: onClose,
      "aria-label": "Close",
      onMouseEnter: e => e.currentTarget.focus(),
      style: {
        width: 44,
        height: 44,
        borderRadius: "50%",
        border: "none",
        cursor: "pointer",
        background: "var(--surface-container-highest)",
        color: "var(--text-primary)",
        display: "inline-flex",
        alignItems: "center",
        justifyContent: "center"
      }
    }, /*#__PURE__*/React.createElement(Icon, {
      name: "x",
      size: 22
    }))), /*#__PURE__*/React.createElement("div", {
      style: {
        overflowY: "auto",
        padding: "4px 12px 18px",
        scrollbarWidth: "none"
      }
    }, (actions || []).map((a, i) => /*#__PURE__*/React.createElement(ActionRow, _extends({
      key: a.id || a.label
    }, a, {
      autoFocus: i === 0,
      onClick: () => {
        onClose();
        a.onClick && a.onClick();
      }
    }))))), /*#__PURE__*/React.createElement("style", null, `
          @keyframes tvOvFade { from { opacity: 0 } to { opacity: 1 } }
          @keyframes tvOvIn { from { opacity: 0; transform: scale(0.96) translateY(8px) } to { opacity: 1; transform: scale(1) translateY(0) } }
        `));
  }

  // Builder — produces the standard SpatialFin overflow action list for a
  // movie or episode, with optional callbacks the host wires up.
  function buildOverflowActions({
    item,
    kind,
    castActive,
    castLabel,
    onSyncPlay,
    onPlaybackOptions,
    onGoToSeries,
    onGoToSeason,
    onEditExternalIds,
    onCast,
    onRefreshMetadata,
    onShare,
    onDelete
  }) {
    const isEpisode = kind === "episode";
    return [{
      id: "syncplay",
      icon: "users",
      label: "SyncPlay",
      sub: "Watch in sync with others",
      onClick: onSyncPlay
    }, {
      id: "playback",
      icon: "sliders",
      label: "Playback options",
      sub: "Default subtitle & audio",
      onClick: onPlaybackOptions
    }, isEpisode ? {
      id: "series",
      icon: "tv",
      label: "Go to series",
      sub: item.seriesTitle,
      onClick: onGoToSeries
    } : null, isEpisode ? {
      id: "season",
      icon: "list",
      label: "Go to season",
      sub: "Season " + (item.season || 1),
      onClick: onGoToSeason
    } : null, {
      id: "extids",
      icon: "id-card",
      label: "Edit external IDs",
      sub: "TMDB · IMDB · TVDB",
      admin: true,
      onClick: onEditExternalIds
    }, {
      id: "cast",
      icon: "cast",
      label: "Cast & audio output",
      sub: castActive ? "On " + castLabel : "This device",
      onClick: onCast
    }, {
      id: "refresh",
      icon: "refresh-cw",
      label: "Refresh metadata",
      sub: "Re-scan from providers",
      admin: true,
      onClick: onRefreshMetadata
    }, {
      id: "share",
      icon: "share-2",
      label: "Share",
      sub: "Send a deeplink",
      onClick: onShare
    }, {
      id: "delete",
      icon: "trash-2",
      label: "Delete",
      sub: "Remove from library",
      admin: true,
      danger: true,
      onClick: onDelete
    }].filter(Boolean);
  }
  Object.assign(window, {
    TvOverflowSheet,
    tvBuildOverflowActions: buildOverflowActions
  });
})();
})(); } catch (e) { __ds_ns.__errors.push({ path: "ui_kits/tv/TvOverflowSheet.jsx", error: String((e && e.message) || e) }); }

// ui_kits/tv/TvPlayerScreen.jsx
try { (() => {
// TV — Media player with 10-foot transport chrome, Up Next autoplay & voice overlay
function TvPlayer({
  item,
  episode,
  onBack,
  onPlayNext
}) {
  const NS = window.SpatialFinDesignSystem_0d3fe7;
  const {
    ProgressBar,
    Pill,
    Icon,
    UpNextCard,
    VoiceFeedback
  } = NS;
  const [playing, setPlaying] = React.useState(true);
  const [voice, setVoice] = React.useState("idle");
  const [upNext, setUpNext] = React.useState(null); // resolved next item or null
  const [remaining, setRemaining] = React.useState(10);
  const title = item.title;
  const sub = episode ? `S${episode.season || 1} E${episode.n} · ${episode.title}` : item.genres;
  const still = episode ? episode.still : item.backdrop || item.poster;

  // resolve what plays next: next episode, else a suggested movie
  const resolveNext = React.useCallback(() => {
    if (episode) {
      const nx = window.SF_TV_NEXT_EP ? window.SF_TV_NEXT_EP(item, episode) : null;
      if (nx) return {
        kind: "episode",
        show: item,
        ep: nx.ep,
        title: nx.ep.title,
        subtitle: `S${nx.season} E${nx.ep.n} · ${item.title}`,
        still: nx.ep.still
      };
    }
    const movies = window.SF_TV_MOVIES || [];
    const other = movies.find(m => m.id !== item.id);
    if (other) return {
      kind: "movie",
      movie: other,
      title: other.title,
      subtitle: `Movie · ${other.year}`,
      still: other.poster
    };
    return null;
  }, [item, episode]);

  // demo: surface the Up Next card a few seconds in (as content "nears the end")
  React.useEffect(() => {
    const n = resolveNext();
    const t = setTimeout(() => {
      if (n) {
        setUpNext(n);
        setRemaining(10);
      }
    }, 5000);
    return () => clearTimeout(t);
  }, [resolveNext]);

  // countdown while Up Next is showing
  React.useEffect(() => {
    if (!upNext) return;
    if (remaining <= 0) {
      advanceNext();
      return;
    }
    const t = setTimeout(() => setRemaining(r => Math.max(0, +(r - 0.1).toFixed(1))), 100);
    return () => clearTimeout(t);
  }, [upNext, remaining]);
  const advanceNext = () => {
    const n = upNext;
    setUpNext(null);
    setRemaining(10);
    if (n && onPlayNext) onPlayNext(n);
  };
  const cancelNext = () => {
    setUpNext(null);
    setRemaining(10);
  };
  const cycleVoice = () => {
    if (voice !== "idle") {
      setVoice("idle");
      return;
    }
    setVoice("listening");
    setTimeout(() => setVoice("processing"), 1500);
    setTimeout(() => setVoice("answered"), 2900);
    setTimeout(() => setVoice("idle"), 5000);
  };
  const ctrl = (icon, label, opts = {}) => /*#__PURE__*/React.createElement("button", {
    "data-focusable": "",
    onClick: opts.onClick,
    onMouseEnter: e => e.currentTarget.focus(),
    className: "tv-pctrl",
    "aria-label": label,
    title: label,
    style: {
      width: 56,
      height: 56,
      borderRadius: "50%",
      border: "1px solid var(--glass-border)",
      background: opts.active ? "var(--primary)" : "var(--glass-fill-strong)",
      color: opts.active ? "var(--on-primary)" : "#fff",
      cursor: "pointer",
      display: "inline-flex",
      alignItems: "center",
      justifyContent: "center"
    }
  }, /*#__PURE__*/React.createElement(Icon, {
    name: icon,
    size: 24
  }));
  return /*#__PURE__*/React.createElement("div", {
    style: {
      position: "absolute",
      inset: 0,
      background: "#000"
    }
  }, /*#__PURE__*/React.createElement("img", {
    src: still,
    alt: "",
    style: {
      position: "absolute",
      inset: 0,
      width: "100%",
      height: "100%",
      objectFit: "cover",
      opacity: 0.92
    }
  }), /*#__PURE__*/React.createElement("div", {
    style: {
      position: "absolute",
      inset: 0,
      background: "linear-gradient(180deg, rgba(0,0,0,0.55) 0%, rgba(0,0,0,0) 22%, rgba(0,0,0,0) 50%, rgba(0,0,0,0.85) 100%)"
    }
  }), /*#__PURE__*/React.createElement("div", {
    style: {
      position: "absolute",
      top: 0,
      left: 0,
      right: 0,
      padding: "40px 56px",
      display: "flex",
      alignItems: "flex-start",
      justifyContent: "space-between"
    }
  }, /*#__PURE__*/React.createElement("div", null, /*#__PURE__*/React.createElement("div", {
    style: {
      fontSize: 40,
      fontWeight: 700,
      color: "#fff",
      textShadow: "0 2px 12px rgba(0,0,0,0.5)"
    }
  }, title), /*#__PURE__*/React.createElement("div", {
    className: "m3-title-medium",
    style: {
      color: "rgba(255,255,255,0.8)",
      marginTop: 6
    }
  }, sub)), /*#__PURE__*/React.createElement("div", {
    style: {
      display: "flex",
      gap: 10
    }
  }, /*#__PURE__*/React.createElement(Pill, {
    tone: "accent"
  }, item.tags && item.tags[0] || "4K"), /*#__PURE__*/React.createElement(Pill, {
    tone: "outline"
  }, "HDR"))), voice !== "idle" ? /*#__PURE__*/React.createElement("div", {
    style: {
      position: "absolute",
      top: 120,
      left: 0,
      right: 0,
      display: "flex",
      justifyContent: "center",
      zIndex: 12
    }
  }, /*#__PURE__*/React.createElement(VoiceFeedback, {
    state: voice === "listening" ? "listening" : voice === "processing" ? "processing" : "answered",
    text: voice === "listening" ? "“turn on subtitles”" : voice === "answered" ? "Subtitles on — English." : undefined
  })) : null, upNext ? /*#__PURE__*/React.createElement("div", {
    style: {
      position: "absolute",
      right: 56,
      bottom: 168,
      zIndex: 14
    }
  }, /*#__PURE__*/React.createElement(UpNextCard, {
    title: upNext.title,
    subtitle: upNext.subtitle,
    still: upNext.still,
    seconds: 10,
    remaining: remaining,
    onPlayNow: advanceNext,
    onCancel: cancelNext
  })) : null, /*#__PURE__*/React.createElement("div", {
    style: {
      position: "absolute",
      left: 0,
      right: 0,
      bottom: 0,
      padding: "0 56px 48px",
      display: "flex",
      flexDirection: "column",
      gap: 22
    }
  }, /*#__PURE__*/React.createElement("div", {
    style: {
      display: "flex",
      alignItems: "center",
      gap: 18
    }
  }, /*#__PURE__*/React.createElement("span", {
    style: {
      color: "#fff",
      fontFamily: "var(--font-mono)",
      fontSize: 16,
      minWidth: 56
    }
  }, "04:12"), /*#__PURE__*/React.createElement("div", {
    style: {
      flex: 1
    }
  }, /*#__PURE__*/React.createElement(ProgressBar, {
    value: episode ? episode.progress || 38 : item.progress || 38,
    height: 8
  })), /*#__PURE__*/React.createElement("span", {
    style: {
      color: "rgba(255,255,255,0.7)",
      fontFamily: "var(--font-mono)",
      fontSize: 16,
      minWidth: 56,
      textAlign: "right"
    }
  }, episode && episode.runtime || item.runtime || "11 min")), /*#__PURE__*/React.createElement("div", {
    style: {
      display: "flex",
      alignItems: "center",
      justifyContent: "space-between"
    }
  }, /*#__PURE__*/React.createElement("div", {
    style: {
      display: "flex",
      alignItems: "center",
      gap: 18
    }
  }, ctrl("rotate-ccw", "Back 10s"), /*#__PURE__*/React.createElement("button", {
    "data-focusable": true,
    "data-focus-first": true,
    onClick: () => setPlaying(!playing),
    onMouseEnter: e => e.currentTarget.focus(),
    className: "tv-pctrl",
    "aria-label": "Play/Pause",
    style: {
      width: 84,
      height: 84,
      borderRadius: "50%",
      border: "1px solid var(--glass-border)",
      background: "var(--primary)",
      color: "var(--on-primary)",
      cursor: "pointer",
      display: "inline-flex",
      alignItems: "center",
      justifyContent: "center"
    }
  }, /*#__PURE__*/React.createElement(Icon, {
    name: playing ? "pause" : "play",
    size: 36
  })), ctrl("rotate-cw", "Forward 10s")), /*#__PURE__*/React.createElement("div", {
    style: {
      display: "flex",
      alignItems: "center",
      gap: 16
    }
  }, ctrl("mic", "Voice", {
    onClick: cycleVoice,
    active: voice === "listening"
  }), ctrl("captions", "Subtitles"), ctrl("audio-lines", "Audio"), ctrl("list-video", "Episodes"), ctrl("settings", "Settings"), ctrl("chevron-left", "Exit", {
    onClick: onBack
  })))), /*#__PURE__*/React.createElement("style", null, `.tv-pctrl{transition:transform var(--duration-fast) var(--ease-standard),outline-color var(--duration-fast)}
        .tv-pctrl:focus{outline:3px solid var(--primary);outline-offset:4px;transform:scale(1.08)}`));
}
window.TvPlayer = TvPlayer;
})(); } catch (e) { __ds_ns.__errors.push({ path: "ui_kits/tv/TvPlayerScreen.jsx", error: String((e && e.message) || e) }); }

// ui_kits/tv/TvSearchScreen.jsx
try { (() => {
// TV — Search: on-screen keyboard + live results grid
function TvSearch({
  onOpen
}) {
  const NS = window.SpatialFinDesignSystem_0d3fe7;
  const {
    TvKeyboard,
    FocusCard,
    Icon
  } = NS;
  const [q, setQ] = React.useState("");
  const all = window.SF_TV_ALL || [];
  const results = q.trim() ? all.filter(it => it.title.toLowerCase().includes(q.trim().toLowerCase())) : all;
  React.useEffect(() => {
    if (window.__tvFocusInit) window.__tvFocusInit();
  }, []);
  return /*#__PURE__*/React.createElement("div", {
    style: {
      display: "flex",
      gap: 48,
      padding: "56px 56px 56px 132px",
      minHeight: "100%",
      boxSizing: "border-box"
    }
  }, /*#__PURE__*/React.createElement("div", {
    style: {
      flexShrink: 0,
      display: "flex",
      flexDirection: "column",
      gap: 22,
      width: 452
    }
  }, /*#__PURE__*/React.createElement("div", {
    style: {
      display: "flex",
      alignItems: "center",
      gap: 14,
      height: 64,
      padding: "0 22px",
      borderRadius: "var(--radius-md)",
      background: "var(--surface-container-high)",
      border: "1px solid var(--border-subtle)"
    }
  }, /*#__PURE__*/React.createElement(Icon, {
    name: "search",
    size: 26,
    color: "var(--text-secondary)"
  }), /*#__PURE__*/React.createElement("span", {
    style: {
      fontSize: 24,
      color: q ? "var(--text-primary)" : "var(--text-disabled)",
      fontWeight: 500,
      letterSpacing: 0.5
    }
  }, q || "Search movies & shows"), /*#__PURE__*/React.createElement("span", {
    className: "tv-caret",
    style: {
      width: 2,
      height: 28,
      background: "var(--primary)",
      marginLeft: -4
    }
  })), /*#__PURE__*/React.createElement(TvKeyboard, {
    value: q,
    onChange: setQ
  }), /*#__PURE__*/React.createElement("style", null, `@keyframes tv-blink{0%,100%{opacity:1}50%{opacity:0}} .tv-caret{animation:tv-blink 1s step-end infinite}`)), /*#__PURE__*/React.createElement("div", {
    style: {
      flex: 1,
      minWidth: 0
    }
  }, /*#__PURE__*/React.createElement("div", {
    className: "m3-title-large",
    style: {
      color: "var(--text-secondary)",
      fontWeight: 600,
      marginBottom: 18
    }
  }, q.trim() ? `Results for “${q.trim()}” · ${results.length}` : `Browse all · ${results.length}`), results.length ? /*#__PURE__*/React.createElement("div", {
    "data-row": true,
    style: {
      display: "grid",
      gridTemplateColumns: "repeat(4, 1fr)",
      gap: "26px 22px"
    }
  }, results.map(it => /*#__PURE__*/React.createElement(FocusCard, {
    key: it.kind + it.id,
    title: it.title,
    subtitle: it.type,
    image: it.image,
    width: "100%",
    progress: it.progress || null,
    onClick: () => onOpen({
      type: it.kind,
      id: it.id
    })
  }))) : /*#__PURE__*/React.createElement("div", {
    style: {
      display: "flex",
      flexDirection: "column",
      alignItems: "center",
      justifyContent: "center",
      height: 360,
      gap: 14,
      color: "var(--text-disabled)"
    }
  }, /*#__PURE__*/React.createElement(Icon, {
    name: "search-x",
    size: 48,
    color: "var(--text-disabled)"
  }), /*#__PURE__*/React.createElement("span", {
    className: "m3-title-medium"
  }, "No titles match \u201C", q.trim(), "\u201D"))));
}
window.TvSearch = TvSearch;
})(); } catch (e) { __ds_ns.__errors.push({ path: "ui_kits/tv/TvSearchScreen.jsx", error: String((e && e.message) || e) }); }

// ui_kits/tv/TvShell.jsx
try { (() => {
// TV — app shell: TvTopBar (home/search/library) + routing + MA mini player + sheets
function TvShell() {
  const NS = window.SpatialFinDesignSystem_0d3fe7;
  const {
    TvTopBar,
    MaMiniPlayer,
    MaPlayerPickerSheet,
    ServerPickerSheet
  } = NS;
  const [route, setRoute] = React.useState({
    screen: "home"
  });
  const scroller = React.useRef(null);

  // dialogs
  const [serverPickerOpen, setServerPickerOpen] = React.useState(false);
  const [playerPickerOpen, setPlayerPickerOpen] = React.useState(false);
  const [userPickerOpen, setUserPickerOpen] = React.useState(false);
  const [currentServer, setCurrentServer] = React.useState(window.SF_CURRENT_SERVER || "home");
  const [currentUser, setCurrentUser] = React.useState(window.SF_CURRENT_USER || "ignacio");
  const servers = window.SF_SERVERS || [];
  const serverName = (servers.find(s => s.id === currentServer) || {
    name: "Jellyfin"
  }).name;
  const users = window.SF_USERS || [];
  const user = users.find(u => u.id === currentUser) || users[0] || null;

  // SendSpin / MA player selection (null = Auto/this device)
  const [maSelectedPlayerId, setMaSelectedPlayerId] = React.useState(null);
  const [maPlayers, setMaPlayers] = React.useState(window.SF_MA_PLAYERS || []);
  const maSelectedPlayer = maPlayers.find(p => p.id === maSelectedPlayerId) || null;
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
    const t = setInterval(() => setMaPos(p => (p + 1) % maDuration), 1000);
    return () => clearInterval(t);
  }, [maPhase]);
  const playMaTrack = (t, queueRest = []) => {
    setMaTrack(t);
    setMaQueue([t, ...(queueRest || [])]);
    setMaPhase("preparing");
    setMaPos(0);
    setTimeout(() => setMaPhase("playing"), 1800);
  };
  const playPauseMa = () => setMaPhase(p => p === "playing" ? "paused" : p === "paused" ? "playing" : p);
  const nextMa = () => {
    if (maQueue.length < 2) return;
    const next = maQueue[1];
    const rest = maQueue.slice(2);
    setMaTrack(next);
    setMaQueue([next, ...rest]);
    setMaPos(0);
    setMaPhase("preparing");
    setTimeout(() => setMaPhase("playing"), 1200);
  };
  const prevMa = () => setMaPos(0);
  const stopMa = () => {
    setMaTrack(null);
    setMaPhase("idle");
    setMaQueue([]);
    setMaPos(0);
  };

  // play a full MA shelf starting at the tapped track
  const onPlayMaTrack = t => {
    const shelf = window.SF_MA_CATALOG || [];
    const i = shelf.findIndex(x => x.id === t.id);
    const rest = i >= 0 ? shelf.slice(i + 1) : [];
    playMaTrack(t, rest);
  };

  // routing helpers
  const findShow = id => (window.SF_TV_SHOWS || []).find(s => s.id === id);
  const findMovie = id => (window.SF_TV_MOVIES || []).find(m => m.id === id);
  const open = sel => {
    if (sel.type === "show") setRoute({
      screen: "show",
      item: findShow(sel.id)
    });else if (sel.type === "movie") setRoute({
      screen: "movie",
      item: findMovie(sel.id)
    });
  };
  const playMovie = m => setRoute({
    screen: "player",
    item: m,
    episode: null
  });
  const playEpisode = (show, ep) => setRoute({
    screen: "player",
    item: show,
    episode: ep
  });
  const playNext = n => {
    if (n.kind === "episode") setRoute({
      screen: "player",
      item: n.show,
      episode: n.ep
    });else if (n.kind === "movie") setRoute({
      screen: "player",
      item: n.movie,
      episode: null
    });
  };
  const goHome = () => setRoute({
    screen: "home"
  });

  // top-bar destinations
  const showTopBar = route.screen === "home" || route.screen === "search" || route.screen === "library" || route.screen === "source";
  // reset scroll + focus on screen change
  React.useEffect(() => {
    if (scroller.current) scroller.current.scrollTop = 0;
    if (window.__tvFocusInit) window.__tvFocusInit();
  }, [route.screen, route.item, route.filter, route.source]);

  // group toggle helper
  const toggleGroupMember = (leaderId, memberId, grouped) => {
    setMaPlayers(all => all.map(p => {
      if (p.id === memberId) return {
        ...p,
        syncedToPlayerId: grouped ? null : leaderId
      };
      if (p.id === leaderId) {
        const members = new Set(p.groupMemberIds || []);
        if (grouped) members.delete(memberId);else members.add(memberId);
        return {
          ...p,
          groupMemberIds: [...members]
        };
      }
      return p;
    }));
  };
  const inPlayer = route.screen === "player" || route.screen === "nowplaying";

  // Overflow (…) action sheet
  const [overflowFor, setOverflowFor] = React.useState(null); // { kind, item }
  const openOverflow = ctx => setOverflowFor(ctx);
  const closeOverflow = () => setOverflowFor(null);
  const overflowActions = overflowFor && window.tvBuildOverflowActions ? window.tvBuildOverflowActions({
    item: overflowFor.item,
    kind: overflowFor.kind === "series" ? "series" : overflowFor.kind === "episode" ? "episode" : "movie",
    castActive: !!maSelectedPlayerId,
    castLabel: maSelectedPlayerName,
    onSyncPlay: () => {},
    onPlaybackOptions: () => {},
    onGoToSeries: () => {
      if (overflowFor.item.seriesId) setRoute({
        screen: "show",
        item: findShow(overflowFor.item.seriesId)
      });
    },
    onGoToSeason: () => {},
    onEditExternalIds: () => {},
    onCast: () => setPlayerPickerOpen(true),
    onRefreshMetadata: () => {},
    onShare: () => {},
    onDelete: () => {}
  }) : [];
  return /*#__PURE__*/React.createElement("div", {
    className: "theme-tv",
    style: {
      position: "relative",
      width: "100%",
      height: "100%",
      background: "var(--surface-app)",
      overflow: "hidden"
    }
  }, /*#__PURE__*/React.createElement("div", {
    ref: scroller,
    "data-scroll": true,
    style: {
      position: "absolute",
      inset: 0,
      overflowY: inPlayer ? "hidden" : "auto",
      overflowX: "hidden",
      scrollbarWidth: "none",
      paddingTop: showTopBar ? 104 : 0
    }
  }, route.screen === "home" && /*#__PURE__*/React.createElement(window.TvHome, {
    onOpen: open,
    onPlayMaTrack: onPlayMaTrack,
    onOpenOverflow: openOverflow,
    onSourceSeeAll: src => setRoute({
      screen: "source",
      source: src
    })
  }), route.screen === "search" && /*#__PURE__*/React.createElement(window.TvSearch, {
    onOpen: open
  }), route.screen === "library" && /*#__PURE__*/React.createElement(window.TvLibrary, {
    initialFilter: route.filter || "all",
    onOpen: open
  }), route.screen === "source" && /*#__PURE__*/React.createElement(window.TvSourceBrowse, {
    source: route.source,
    onBack: goHome
  }), route.screen === "show" && /*#__PURE__*/React.createElement(window.TvShowDetail, {
    show: route.item,
    onBack: goHome,
    onPlay: playEpisode,
    onOpenOverflow: openOverflow
  }), route.screen === "movie" && /*#__PURE__*/React.createElement(window.TvMovieDetail, {
    movie: route.item,
    onBack: goHome,
    onPlay: playMovie,
    onOpenOverflow: openOverflow
  }), route.screen === "player" && /*#__PURE__*/React.createElement(window.TvPlayer, {
    item: route.item,
    episode: route.episode,
    onPlayNext: playNext,
    onBack: () => setRoute(route.episode ? {
      screen: "show",
      item: route.item
    } : {
      screen: "movie",
      item: route.item
    })
  }), route.screen === "nowplaying" && /*#__PURE__*/React.createElement(window.TvNowPlaying, {
    track: maTrack,
    phase: maPhase,
    queue: maQueue,
    position: maPos,
    duration: maDuration,
    selectedPlayer: maSelectedPlayer ? maSelectedPlayer.name : null,
    onBack: () => setRoute({
      screen: "home"
    }),
    onPlayPause: playPauseMa,
    onNext: nextMa,
    onPrev: prevMa,
    onStop: stopMa,
    onPickPlayer: () => setPlayerPickerOpen(true),
    onOpenQueue: () => {}
  })), showTopBar && TvTopBar ? /*#__PURE__*/React.createElement(TvTopBar, {
    serverName: serverName,
    logoSrc: "../../assets/logo-mark.png",
    user: user,
    onServerClick: () => setServerPickerOpen(true),
    onUserClick: () => setUserPickerOpen(true),
    onSearchClick: () => setRoute({
      screen: "search"
    }),
    onSettingsClick: () => {/* settings stub */},
    onCloseClick: () => {/* close stub */},
    onRetryClick: () => {}
  }) : null, !inPlayer && maTrack && MaMiniPlayer ? /*#__PURE__*/React.createElement("div", {
    style: {
      position: "absolute",
      left: 56,
      right: 56,
      bottom: 22,
      zIndex: 18
    }
  }, /*#__PURE__*/React.createElement(MaMiniPlayer, {
    track: maTrack,
    phase: maPhase,
    selectedPlayer: maSelectedPlayerName,
    onPlayPause: playPauseMa,
    onNext: nextMa,
    onStop: stopMa,
    onExpand: () => setRoute({
      screen: "nowplaying"
    })
  })) : null, ServerPickerSheet ? /*#__PURE__*/React.createElement(ServerPickerSheet, {
    open: serverPickerOpen,
    servers: servers,
    currentId: currentServer,
    onPick: setCurrentServer,
    onManage: () => {},
    onDismiss: () => setServerPickerOpen(false)
  }) : null, MaPlayerPickerSheet ? /*#__PURE__*/React.createElement(MaPlayerPickerSheet, {
    open: playerPickerOpen,
    players: maPlayers,
    selectedId: maSelectedPlayerId,
    onPick: setMaSelectedPlayerId,
    onToggleGroupMember: toggleGroupMember,
    onDismiss: () => setPlayerPickerOpen(false)
  }) : null, window.TvOverflowSheet ? /*#__PURE__*/React.createElement(window.TvOverflowSheet, {
    open: userPickerOpen,
    title: "Switch user",
    subtitle: "Choose a Jellyfin profile on this server",
    actions: users.map(u => ({
      id: u.id,
      leading: u.avatar ? /*#__PURE__*/React.createElement("img", {
        src: u.avatar,
        alt: "",
        style: {
          width: 48,
          height: 48,
          borderRadius: "50%",
          objectFit: "cover",
          flexShrink: 0
        }
      }) : /*#__PURE__*/React.createElement("span", {
        style: {
          width: 48,
          height: 48,
          borderRadius: "50%",
          flexShrink: 0,
          background: u.color || "var(--surface-container-highest)",
          color: u.textColor || "#fff",
          fontSize: 17,
          fontWeight: 700,
          display: "inline-flex",
          alignItems: "center",
          justifyContent: "center"
        }
      }, u.initials || u.name[0]),
      label: u.name + (u.id === currentUser ? " (current)" : ""),
      sub: u.role,
      onClick: () => setCurrentUser(u.id)
    })).concat([{
      id: "add",
      icon: "user-plus",
      label: "Add another user",
      sub: "Sign in with a different account",
      onClick: () => {}
    }]),
    onClose: () => setUserPickerOpen(false)
  }) : null, window.TvOverflowSheet ? /*#__PURE__*/React.createElement(window.TvOverflowSheet, {
    open: !!overflowFor,
    title: overflowFor ? "More actions" : "",
    subtitle: overflowFor ? overflowFor.item.title : "",
    actions: overflowActions,
    onClose: closeOverflow
  }) : null);
}
window.TvShell = TvShell;
})(); } catch (e) { __ds_ns.__errors.push({ path: "ui_kits/tv/TvShell.jsx", error: String((e && e.message) || e) }); }

// ui_kits/tv/TvSourceBrowseScreen.jsx
try { (() => {
// TV — Universal Plugin "Source" browse (See all → grid of source items)
function TvSourceBrowse({
  source,
  onBack
}) {
  const NS = window.SpatialFinDesignSystem_0d3fe7;
  const {
    FocusCard,
    Icon
  } = NS;
  React.useEffect(() => {
    if (window.__tvFocusInit) window.__tvFocusInit();
  }, []);
  return /*#__PURE__*/React.createElement("div", {
    style: {
      padding: "48px 56px 56px",
      minHeight: "100%",
      boxSizing: "border-box"
    }
  }, /*#__PURE__*/React.createElement("button", {
    "data-focusable": true,
    "data-focus-first": true,
    onClick: onBack,
    onMouseEnter: e => e.currentTarget.focus(),
    className: "tv-src-back",
    style: {
      display: "inline-flex",
      alignItems: "center",
      gap: 8,
      height: 44,
      padding: "0 18px 0 14px",
      borderRadius: 999,
      border: "1px solid var(--border-strong)",
      background: "var(--surface-container-high)",
      color: "var(--text-primary)",
      cursor: "pointer",
      fontSize: 15,
      fontWeight: 500
    }
  }, /*#__PURE__*/React.createElement(Icon, {
    name: "chevron-left",
    size: 20
  }), " Back"), /*#__PURE__*/React.createElement("div", {
    style: {
      display: "flex",
      alignItems: "baseline",
      gap: 16,
      marginTop: 22,
      marginBottom: 26
    }
  }, /*#__PURE__*/React.createElement("span", {
    style: {
      width: 4,
      height: 28,
      borderRadius: 99,
      background: "var(--primary)"
    }
  }), /*#__PURE__*/React.createElement("span", {
    style: {
      fontSize: 40,
      fontWeight: 700,
      color: "var(--text-primary)"
    }
  }, source.name), /*#__PURE__*/React.createElement("span", {
    style: {
      fontSize: 16,
      color: "var(--text-secondary)"
    }
  }, "via ", source.pluginId, " \xB7 ", source.items.length, " items")), /*#__PURE__*/React.createElement("div", {
    style: {
      display: "grid",
      gridTemplateColumns: "repeat(5, 1fr)",
      gap: "28px 22px"
    }
  }, source.items.map(it => /*#__PURE__*/React.createElement(FocusCard, {
    key: it.id,
    title: it.title,
    subtitle: it.subtitle,
    image: it.image,
    width: "100%",
    orientation: source.pluginId === "youtube" ? "landscape" : "portrait"
  }))), /*#__PURE__*/React.createElement("style", null, `.tv-src-back:focus{outline:3px solid var(--primary);outline-offset:3px;background:var(--primary);color:var(--on-primary)}`));
}
window.TvSourceBrowse = TvSourceBrowse;
})(); } catch (e) { __ds_ns.__errors.push({ path: "ui_kits/tv/TvSourceBrowseScreen.jsx", error: String((e && e.message) || e) }); }

// ui_kits/tv/data.js
try { (() => {
// SpatialFin TV — catalog: shows (seasons/episodes), movies, featured.
const SF_TV_SHOWS = [{
  id: "cosmos",
  title: "Cosmos Laundromat",
  kind: "Series",
  year: "2015–",
  stars: "8.4",
  genres: "Animation, Comedy, Fantasy",
  rating: "TV-PG",
  tags: ["4K", "HDR", "5.1"],
  poster: "../../assets/tv/poster-cosmos.png",
  backdrop: "../../assets/tv/backdrop-cosmos.png",
  overview: "On a desolate island, a suicidal sheep named Franck meets his fairy godmother, who hands him the salvation of his dreams — at the price of his future.",
  seasons: {
    1: [{
      n: 1,
      title: "Emo Goes Wild",
      still: "../../assets/tv/ep-cosmos-1.png",
      runtime: "12 min",
      progress: 100,
      synopsis: "Franck, a suicidal sheep, is offered a way out by a peculiar travelling salesman named Victor."
    }, {
      n: 2,
      title: "The Many Lives of Franck",
      still: "../../assets/tv/ep-cosmos-2.png",
      runtime: "10 min",
      progress: 45,
      synopsis: "Franck wakes up in a strange new dimension and must choose between infinite lives."
    }, {
      n: 3,
      title: "Wisdom of the Cosmos",
      still: "../../assets/tv/ep-cosmos-3.png",
      runtime: "11 min",
      progress: 0,
      synopsis: "Victor's machine begins to fail as Franck questions the cost of a perfect life."
    }],
    2: [{
      n: 1,
      title: "A New Cycle",
      still: "../../assets/tv/ep-cosmos-2.png",
      runtime: "13 min",
      progress: 0,
      synopsis: "The salesman's empire faces its reckoning across a thousand parallel islands."
    }, {
      n: 2,
      title: "The Last Sale",
      still: "../../assets/tv/ep-cosmos-1.png",
      runtime: "12 min",
      progress: 0,
      synopsis: "Franck makes his final choice."
    }]
  }
}, {
  id: "caminandes",
  title: "Caminandes",
  kind: "Series",
  year: "2013–2016",
  stars: "7.8",
  genres: "Animation, Family, Comedy",
  rating: "G",
  tags: ["4K"],
  poster: "../../assets/tv/poster-caminandes.png",
  backdrop: "../../assets/tv/backdrop-caminandes.png",
  overview: "Koro the llama just wants to get to the other side — but a determined road, a hungry winter, and a sly little penguin named Oti keep getting in the way.",
  seasons: {
    1: [{
      n: 1,
      title: "Llama Drama",
      still: "../../assets/tv/ep-cam-1.png",
      runtime: "1 min",
      progress: 100,
      synopsis: "Koro the llama faces his first great obstacle: a road."
    }, {
      n: 2,
      title: "Gran Dillama",
      still: "../../assets/tv/ep-cam-2.png",
      runtime: "2 min",
      progress: 100,
      synopsis: "Hungry in the winter, Koro spots a patch of green grass — behind a fence."
    }, {
      n: 3,
      title: "Llamigos",
      still: "../../assets/tv/ep-cam-3.png",
      runtime: "2 min",
      progress: 60,
      synopsis: "Koro and Oti the penguin battle over the last carrot in a frozen land."
    }]
  }
}];
const SF_TV_MOVIES = window.SF_CATALOG || [];
const SF_TV_FEATURED = {
  id: "cosmos",
  title: "Cosmos Laundromat",
  kind: "Series",
  year: "2015",
  stars: "8.4",
  runtime: "S1 · 3 episodes",
  rating: "TV-PG",
  genres: "Animation, Comedy, Fantasy",
  backdrop: "../../assets/tv/backdrop-cosmos.png",
  tags: ["4K", "HDR", "Atmos"],
  overview: "On a desolate island, a suicidal sheep named Franck meets his fairy godmother — who offers him the salvation of his dreams at the price of his future.",
  isShow: true
};
window.SF_TV_SHOWS = SF_TV_SHOWS;
window.SF_TV_MOVIES = SF_TV_MOVIES;
window.SF_TV_FEATURED = SF_TV_FEATURED;

// Given the current show + episode, return the next episode in the season (or null).
window.SF_TV_NEXT_EP = function (show, ep) {
  if (!show || !ep) return null;
  for (const sk of Object.keys(show.seasons)) {
    const eps = show.seasons[sk];
    const i = eps.findIndex(e => e.n === ep.n && e.title === ep.title);
    if (i >= 0 && i + 1 < eps.length) return {
      ep: eps[i + 1],
      season: sk
    };
  }
  return null;
};
// Continue-watching = landscape items (episodes + partially-watched movies)
window.SF_TV_CONTINUE = [{
  id: "cam-1-3",
  title: "Caminandes",
  subtitle: "S1 E3 · Llamigos",
  image: "../../assets/tv/ep-cam-3.png",
  progress: 60,
  show: "caminandes"
}, {
  id: "cosmos-1-2",
  title: "Cosmos Laundromat",
  subtitle: "S1 E2 · The Many Lives of Franck",
  image: "../../assets/tv/ep-cosmos-2.png",
  progress: 45,
  show: "cosmos"
}, {
  id: "bbb",
  title: "Big Buck Bunny",
  subtitle: "Movie · 79% watched",
  image: "../../assets/media/poster-bbb.png",
  progress: 79,
  movie: "bbb"
}, {
  id: "elephants",
  title: "Elephants Dream",
  subtitle: "Movie · 26% watched",
  image: "../../assets/tv/ep-cosmos-3.png",
  progress: 26,
  movie: "elephants"
}];

// Unified browse entries (shows + movies) for Library + Search.
window.SF_TV_ALL = [...SF_TV_SHOWS.map(s => ({
  kind: "show",
  id: s.id,
  title: s.title,
  type: "Series",
  image: s.poster
})), ...SF_TV_MOVIES.map(m => ({
  kind: "movie",
  id: m.id,
  title: m.title,
  type: "Movie",
  image: m.poster,
  progress: m.progress,
  downloaded: m.downloaded
}))];

// --- Music Assistant catalog -----------------------------------------------
window.SF_MA_CATALOG = [{
  id: "ma-1",
  title: "Midnight City",
  artist: "M83",
  album: "Hurry Up, We're Dreaming",
  duration: "4:03",
  artwork: "../../assets/music/album-cyan.png"
}, {
  id: "ma-2",
  title: "Borderline",
  artist: "Tame Impala",
  album: "The Slow Rush",
  duration: "3:58",
  artwork: "../../assets/music/album-amber.png"
}, {
  id: "ma-3",
  title: "Saturn",
  artist: "Sleeping at Last",
  album: "Atlas: Year One",
  duration: "4:48",
  artwork: "../../assets/music/album-blue.png"
}, {
  id: "ma-4",
  title: "Lost in the Light",
  artist: "Bahamas",
  album: "Barchords",
  duration: "3:43",
  artwork: "../../assets/music/album-warm.png"
}, {
  id: "ma-5",
  title: "Holocene",
  artist: "Bon Iver",
  album: "Bon Iver, Bon Iver",
  duration: "5:36",
  artwork: "../../assets/music/album-mint.png"
}, {
  id: "ma-6",
  title: "Re: Stacks",
  artist: "Bon Iver",
  album: "For Emma, Forever Ago",
  duration: "6:41",
  artwork: "../../assets/music/album-mauve.png"
}];
window.SF_MA_SHELF = window.SF_MA_CATALOG.slice(0, 6);

// SendSpin / Music Assistant players ----------------------------------------
window.SF_MA_PLAYERS = [{
  id: "p-tv",
  name: "Living Room TV",
  provider: "AndroidTV",
  isPlaying: true,
  supportsGrouping: true,
  canGroupWith: ["snapcast", "p-kitchen", "p-office"]
}, {
  id: "p-kitchen",
  name: "Kitchen Speaker",
  provider: "snapcast",
  supportsGrouping: true,
  canGroupWith: ["snapcast", "p-tv"]
}, {
  id: "p-office",
  name: "Office Display",
  provider: "Chromecast",
  supportsGrouping: true,
  canGroupWith: ["chromecast"]
}, {
  id: "p-bedroom",
  name: "Bedroom Sonos",
  provider: "Sonos",
  supportsGrouping: false,
  canGroupWith: []
}];

// Servers (Jellyfin) --------------------------------------------------------
window.SF_SERVERS = [{
  id: "home",
  name: "Home Jellyfin",
  address: "http://192.168.1.42:8096"
}, {
  id: "cabin",
  name: "Cabin",
  address: "https://cabin.example.com"
}, {
  id: "demo",
  name: "Demo Server",
  address: "https://demo.jellyfin.org"
}];
window.SF_CURRENT_SERVER = "home";

// Universal-plugin SOURCES rows (e.g. YouTube, podcast, web-video plugins).
// Each row has a pluginId + a See-all target. Items reuse our poster art.
window.SF_TV_SOURCES = [{
  id: "src-yt",
  name: "From YouTube",
  pluginId: "youtube",
  items: SF_TV_MOVIES.slice(0, 6).map((m, i) => ({
    id: "yt-" + m.id,
    title: m.title.toUpperCase().slice(0, 24),
    image: m.poster,
    subtitle: ["1.2M views", "428k views", "Featured", "Trending", "812k views", "New"][i % 6]
  }))
}, {
  id: "src-podcast",
  name: "Recent Podcasts",
  pluginId: "podcasts",
  items: SF_TV_SHOWS.flatMap(s => Object.values(s.seasons).flat().slice(0, 3).map(e => ({
    id: "pd-" + s.id + "-" + e.n,
    title: s.title + " · Ep " + e.n,
    image: e.still,
    subtitle: e.runtime + " · Today"
  })))
}];
})(); } catch (e) { __ds_ns.__errors.push({ path: "ui_kits/tv/data.js", error: String((e && e.message) || e) }); }

// ui_kits/tv/tvFocus.js
try { (() => {
/* SpatialFin TV — spatial (D-pad) focus engine.
 * Authentic 10-foot navigation: Arrow keys move focus to the nearest
 * [data-focusable] element in that direction (by bounding-box geometry),
 * Enter activates it, and the focused element is revealed inside its
 * horizontal row ([data-row]) and the vertical scroller ([data-scroll]).
 *
 * It deliberately uses manual scrollLeft/scrollTop — never Element.scrollIntoView.
 * Load once per page; call window.__tvFocusInit() after the screen mounts.
 */
(function () {
  function focusables() {
    return [...document.querySelectorAll("[data-focusable]")].filter(el => el.offsetParent !== null && !el.hasAttribute("disabled"));
  }
  const rect = el => el.getBoundingClientRect();
  const center = r => ({
    x: r.left + r.width / 2,
    y: r.top + r.height / 2
  });
  function reveal(el) {
    const row = el.closest("[data-row]");
    if (row) {
      const rr = rect(row),
        r = rect(el);
      if (r.right > rr.right - 48) row.scrollLeft += r.right - rr.right + 96;else if (r.left < rr.left + 48) row.scrollLeft -= rr.left - r.left + 96;
    }
    const sc = el.closest("[data-scroll]") || document.querySelector("[data-scroll]");
    if (sc) {
      const sr = rect(sc),
        r = rect(el);
      if (r.bottom > sr.bottom - 64) sc.scrollTop += r.bottom - sr.bottom + 100;else if (r.top < sr.top + 72) sc.scrollTop -= sr.top - r.top + 100;
    }
  }
  function move(dir) {
    const list = focusables();
    if (!list.length) return;
    const cur = document.activeElement;
    if (!cur || !cur.matches || !cur.matches("[data-focusable]")) {
      list[0].focus({
        preventScroll: true
      });
      reveal(list[0]);
      return;
    }
    const cc = center(rect(cur));
    let best = null,
      bestScore = Infinity;
    for (const el of list) {
      if (el === cur) continue;
      const c = center(rect(el));
      const dx = c.x - cc.x,
        dy = c.y - cc.y;
      let primary, cross;
      if (dir === "left") {
        if (dx > -4) continue;
        primary = -dx;
        cross = Math.abs(dy);
      } else if (dir === "right") {
        if (dx < 4) continue;
        primary = dx;
        cross = Math.abs(dy);
      } else if (dir === "up") {
        if (dy > -4) continue;
        primary = -dy;
        cross = Math.abs(dx);
      } else {
        if (dy < 4) continue;
        primary = dy;
        cross = Math.abs(dx);
      }
      const score = primary + cross * 2.2; // strongly prefer axis alignment
      if (score < bestScore) {
        bestScore = score;
        best = el;
      }
    }
    if (best) {
      best.focus({
        preventScroll: true
      });
      reveal(best);
    }
  }
  window.addEventListener("keydown", function (e) {
    const k = e.key;
    if (k === "ArrowLeft" || k === "ArrowRight" || k === "ArrowUp" || k === "ArrowDown") {
      e.preventDefault();
      move(k.replace("Arrow", "").toLowerCase());
    } else if (k === "Enter") {
      const a = document.activeElement;
      if (a && a.matches && a.matches("[data-focusable]")) a.click();
    }
  });
  window.__tvFocusInit = function () {
    setTimeout(() => {
      const f = focusables();
      const home = document.querySelector("[data-focus-first]") || f[0];
      if (home) {
        home.focus({
          preventScroll: true
        });
        reveal(home);
      }
    }, 120);
  };
})();
})(); } catch (e) { __ds_ns.__errors.push({ path: "ui_kits/tv/tvFocus.js", error: String((e && e.message) || e) }); }

// ui_kits/xr/XrApp.jsx
try { (() => {
// XR (Galaxy XR) — spatial home over passthrough
function XrApp() {
  const NS = window.SpatialFinDesignSystem_0d3fe7;
  const {
    GlassPanel,
    Orbiter,
    PosterCard,
    Pill,
    Button,
    IconButton,
    Badge,
    VoiceFeedback
  } = NS;
  const cat = window.SF_CATALOG,
    F = window.SF_FEATURED;
  const [sel, setSel] = React.useState(F);
  const [voice, setVoice] = React.useState("idle");
  const cycleVoice = () => {
    if (voice !== "idle") {
      setVoice("idle");
      return;
    }
    setVoice("listening");
    setTimeout(() => setVoice("processing"), 1600);
    setTimeout(() => setVoice("answered"), 3000);
    setTimeout(() => setVoice("idle"), 5400);
  };
  const cont = cat.filter(m => m.progress > 0);
  const recent = cat.slice().reverse();

  // Dense browse shelf — small poster tiles, horizontal scroll, click selects.
  const Shelf = ({
    title,
    items,
    count
  }) => /*#__PURE__*/React.createElement("div", {
    style: {
      display: "flex",
      flexDirection: "column",
      gap: 8
    }
  }, /*#__PURE__*/React.createElement("div", {
    style: {
      display: "flex",
      alignItems: "center",
      gap: 9,
      padding: "0 20px"
    }
  }, /*#__PURE__*/React.createElement("span", {
    style: {
      width: 3,
      height: 16,
      borderRadius: 99,
      background: "var(--accent)"
    }
  }), /*#__PURE__*/React.createElement("span", {
    className: "m3-title-medium",
    style: {
      fontWeight: 700
    }
  }, title), /*#__PURE__*/React.createElement("span", {
    className: "m3-body-small",
    style: {
      color: "var(--text-disabled)"
    }
  }, count != null ? count : items.length)), /*#__PURE__*/React.createElement("div", {
    style: {
      display: "flex",
      gap: 12,
      overflowX: "auto",
      padding: "2px 20px 4px",
      scrollbarWidth: "none"
    }
  }, items.map(m => /*#__PURE__*/React.createElement("div", {
    key: m.id,
    style: {
      flex: "0 0 auto"
    }
  }, /*#__PURE__*/React.createElement(PosterCard, {
    title: m.title,
    subtitle: m.progress ? m.progress + "% watched" : m.year,
    poster: m.poster,
    progress: m.progress || null,
    width: 100,
    badge: m.downloaded ? /*#__PURE__*/React.createElement(Badge, {
      tone: "accent",
      icon: "download"
    }) : null,
    style: {
      outline: sel.id === m.id ? "2px solid var(--accent)" : undefined,
      outlineOffset: 2,
      borderRadius: "var(--radius-md)"
    },
    onClick: () => setSel(m)
  })))));
  return /*#__PURE__*/React.createElement("div", {
    className: "xr-stage"
  }, /*#__PURE__*/React.createElement("img", {
    className: "xr-env",
    src: "../../assets/xr-environment.png",
    alt: ""
  }), /*#__PURE__*/React.createElement("div", {
    className: "xr-panel-wrap"
  }, /*#__PURE__*/React.createElement(GlassPanel, {
    tone: "panel",
    padding: "none",
    radius: "lg",
    style: {
      width: "100%",
      height: "86vh",
      maxHeight: 820,
      overflow: "hidden",
      display: "flex",
      flexDirection: "column"
    }
  }, /*#__PURE__*/React.createElement("div", {
    style: {
      display: "flex",
      alignItems: "center",
      gap: 12,
      padding: "12px 20px",
      borderBottom: "1px solid var(--glass-border)",
      flexShrink: 0
    }
  }, /*#__PURE__*/React.createElement("img", {
    src: "../../assets/logo-mark.png",
    alt: "",
    style: {
      width: 26,
      height: 26,
      borderRadius: 7
    }
  }), /*#__PURE__*/React.createElement("span", {
    className: "m3-title-medium",
    style: {
      flex: 1,
      fontWeight: 600
    }
  }, "SpatialFin"), /*#__PURE__*/React.createElement(Button, {
    variant: "glass",
    icon: "search",
    size: "sm"
  }, "Search"), /*#__PURE__*/React.createElement(Button, {
    variant: "glass",
    icon: "settings",
    size: "sm"
  }, "Settings"), /*#__PURE__*/React.createElement(IconButton, {
    icon: "x",
    variant: "glass",
    label: "Close",
    size: "sm"
  })), /*#__PURE__*/React.createElement("div", {
    style: {
      position: "relative",
      height: 184,
      flexShrink: 0,
      borderBottom: "1px solid var(--glass-border)"
    }
  }, /*#__PURE__*/React.createElement("img", {
    src: sel.backdrop,
    alt: sel.title,
    style: {
      position: "absolute",
      inset: 0,
      width: "100%",
      height: "100%",
      objectFit: "cover"
    }
  }), /*#__PURE__*/React.createElement("div", {
    style: {
      position: "absolute",
      inset: 0,
      background: "linear-gradient(90deg, rgba(17,19,24,0.95) 0%, rgba(17,19,24,0.6) 52%, rgba(17,19,24,0.1) 100%)"
    }
  }), /*#__PURE__*/React.createElement("div", {
    style: {
      position: "relative",
      padding: "0 24px",
      display: "flex",
      flexDirection: "column",
      gap: 9,
      height: "100%",
      boxSizing: "border-box",
      justifyContent: "center",
      maxWidth: 600
    }
  }, /*#__PURE__*/React.createElement("div", {
    className: "m3-headline-small",
    style: {
      fontWeight: 700
    }
  }, sel.title), /*#__PURE__*/React.createElement("div", {
    style: {
      display: "flex",
      gap: 7,
      flexWrap: "wrap"
    }
  }, /*#__PURE__*/React.createElement(Pill, {
    tone: "rating",
    icon: "star"
  }, sel.stars), /*#__PURE__*/React.createElement(Pill, null, sel.year), sel.runtime ? /*#__PURE__*/React.createElement(Pill, null, sel.runtime) : null, (sel.tags || []).map(t => /*#__PURE__*/React.createElement(Pill, {
    key: t,
    tone: "outline"
  }, t))), /*#__PURE__*/React.createElement("div", {
    className: "m3-body-small",
    style: {
      color: "var(--text-secondary)",
      maxWidth: 520,
      display: "-webkit-box",
      WebkitLineClamp: 1,
      WebkitBoxOrient: "vertical",
      overflow: "hidden"
    }
  }, sel.overview), /*#__PURE__*/React.createElement("div", {
    style: {
      display: "flex",
      gap: 10,
      marginTop: 2
    }
  }, /*#__PURE__*/React.createElement(Button, {
    variant: "filled",
    icon: "play",
    size: "sm"
  }, "Play"), /*#__PURE__*/React.createElement(Button, {
    variant: "glass",
    icon: "download",
    size: "sm"
  }, "Download"), /*#__PURE__*/React.createElement(Button, {
    variant: "glass",
    icon: "info",
    size: "sm"
  }, "Details")))), /*#__PURE__*/React.createElement("div", {
    style: {
      flex: 1,
      minHeight: 0,
      overflowY: "auto",
      padding: "14px 0 18px",
      display: "flex",
      flexDirection: "column",
      gap: 16,
      scrollbarWidth: "none"
    }
  }, cont.length ? /*#__PURE__*/React.createElement(Shelf, {
    title: "Continue Watching",
    items: cont
  }) : null, /*#__PURE__*/React.createElement(Shelf, {
    title: "Movies",
    items: cat
  }), /*#__PURE__*/React.createElement(Shelf, {
    title: "Recently Added",
    items: recent
  }))), voice !== "idle" ? /*#__PURE__*/React.createElement("div", {
    style: {
      position: "absolute",
      top: -56,
      left: "50%",
      transform: "translateX(-50%)",
      zIndex: 8
    }
  }, /*#__PURE__*/React.createElement(VoiceFeedback, {
    state: voice === "listening" ? "listening" : voice === "processing" ? "processing" : "answered",
    text: voice === "answered" ? "Playing " + sel.title + "." : voice === "listening" ? "“what should I watch?”" : undefined
  })) : null), /*#__PURE__*/React.createElement("div", {
    className: "xr-orbiter"
  }, /*#__PURE__*/React.createElement(Orbiter, {
    items: [{
      icon: "house",
      label: "Home",
      active: true
    }, {
      icon: "clapperboard",
      label: "Movies"
    }, {
      icon: "folder",
      label: "Local"
    }, {
      icon: "download",
      label: "Downloads"
    }, {
      icon: "mic",
      label: "Voice",
      onClick: cycleVoice,
      active: voice === "listening"
    }, {
      icon: "cast",
      label: "Cast"
    }]
  })));
}
window.XrApp = XrApp;
})(); } catch (e) { __ds_ns.__errors.push({ path: "ui_kits/xr/XrApp.jsx", error: String((e && e.message) || e) }); }

__ds_ns.Badge = __ds_scope.Badge;

__ds_ns.Button = __ds_scope.Button;

__ds_ns.Icon = __ds_scope.Icon;

__ds_ns.IconButton = __ds_scope.IconButton;

__ds_ns.Pill = __ds_scope.Pill;

__ds_ns.HeroBanner = __ds_scope.HeroBanner;

__ds_ns.PosterCard = __ds_scope.PosterCard;

__ds_ns.ProgressBar = __ds_scope.ProgressBar;

__ds_ns.SectionHeader = __ds_scope.SectionHeader;

__ds_ns.MaMiniPlayer = __ds_scope.MaMiniPlayer;

__ds_ns.MaPlayerPickerSheet = __ds_scope.MaPlayerPickerSheet;

__ds_ns.NavBar = __ds_scope.NavBar;

__ds_ns.ServerPickerSheet = __ds_scope.ServerPickerSheet;

__ds_ns.OnboardingStepper = __ds_scope.OnboardingStepper;

__ds_ns.PointerHint = __ds_scope.PointerHint;

__ds_ns.QrPairCode = __ds_scope.QrPairCode;

__ds_ns.QuickConnectCode = __ds_scope.QuickConnectCode;

__ds_ns.GlassPanel = __ds_scope.GlassPanel;

__ds_ns.Orbiter = __ds_scope.Orbiter;

__ds_ns.EpisodeCard = __ds_scope.EpisodeCard;

__ds_ns.FocusCard = __ds_scope.FocusCard;

__ds_ns.SeasonTabs = __ds_scope.SeasonTabs;

__ds_ns.TvKeyboard = __ds_scope.TvKeyboard;

__ds_ns.TvNavRail = __ds_scope.TvNavRail;

__ds_ns.TvTopBar = __ds_scope.TvTopBar;

__ds_ns.UpNextCard = __ds_scope.UpNextCard;

__ds_ns.VoiceFab = __ds_scope.VoiceFab;

__ds_ns.VoiceFeedback = __ds_scope.VoiceFeedback;

})();
