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
    return [...document.querySelectorAll("[data-focusable]")].filter(
      (el) => el.offsetParent !== null && !el.hasAttribute("disabled")
    );
  }
  const rect = (el) => el.getBoundingClientRect();
  const center = (r) => ({ x: r.left + r.width / 2, y: r.top + r.height / 2 });

  function reveal(el) {
    const row = el.closest("[data-row]");
    if (row) {
      const rr = rect(row), r = rect(el);
      if (r.right > rr.right - 48) row.scrollLeft += r.right - rr.right + 96;
      else if (r.left < rr.left + 48) row.scrollLeft -= rr.left - r.left + 96;
    }
    const sc = el.closest("[data-scroll]") || document.querySelector("[data-scroll]");
    if (sc) {
      const sr = rect(sc), r = rect(el);
      if (r.bottom > sr.bottom - 64) sc.scrollTop += r.bottom - sr.bottom + 100;
      else if (r.top < sr.top + 72) sc.scrollTop -= sr.top - r.top + 100;
    }
  }

  function move(dir) {
    const list = focusables();
    if (!list.length) return;
    const cur = document.activeElement;
    if (!cur || !cur.matches || !cur.matches("[data-focusable]")) {
      list[0].focus({ preventScroll: true });
      reveal(list[0]);
      return;
    }
    const cc = center(rect(cur));
    let best = null, bestScore = Infinity;
    for (const el of list) {
      if (el === cur) continue;
      const c = center(rect(el));
      const dx = c.x - cc.x, dy = c.y - cc.y;
      let primary, cross;
      if (dir === "left") { if (dx > -4) continue; primary = -dx; cross = Math.abs(dy); }
      else if (dir === "right") { if (dx < 4) continue; primary = dx; cross = Math.abs(dy); }
      else if (dir === "up") { if (dy > -4) continue; primary = -dy; cross = Math.abs(dx); }
      else { if (dy < 4) continue; primary = dy; cross = Math.abs(dx); }
      const score = primary + cross * 2.2; // strongly prefer axis alignment
      if (score < bestScore) { bestScore = score; best = el; }
    }
    if (best) { best.focus({ preventScroll: true }); reveal(best); }
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
      if (home) { home.focus({ preventScroll: true }); reveal(home); }
    }, 120);
  };
})();
