(() => {
  'use strict';

  // 1. Mobile navigation drawer
  const toggleBtn = document.querySelector('[data-nav-toggle]');
  const mobileNav = document.getElementById('mobile-nav');

  if (toggleBtn && mobileNav) {
    const setDrawerOpen = (open) => {
      mobileNav.hidden = !open;
      toggleBtn.setAttribute('aria-expanded', open ? 'true' : 'false');
    };

    toggleBtn.addEventListener('click', () => {
      const isOpen = toggleBtn.getAttribute('aria-expanded') === 'true';
      setDrawerOpen(!isOpen);
    });

    mobileNav.addEventListener('click', (event) => {
      if (event.target instanceof Element && event.target.closest('a')) {
        setDrawerOpen(false);
      }
    });

    document.addEventListener('keydown', (event) => {
      if (event.key === 'Escape' && toggleBtn.getAttribute('aria-expanded') === 'true') {
        setDrawerOpen(false);
        toggleBtn.focus();
      }
    });
  }

  // 2. Copy-to-clipboard buttons for code blocks
  const codeBlocks = document.querySelectorAll('pre > code');
  codeBlocks.forEach((codeEl) => {
    const pre = codeEl.parentElement;
    if (!pre || !pre.parentElement) return;

    const wrapper = document.createElement('div');
    wrapper.className = 'code-block-wrap';
    pre.parentElement.insertBefore(wrapper, pre);
    wrapper.appendChild(pre);

    const copyBtn = document.createElement('button');
    copyBtn.type = 'button';
    copyBtn.className = 'copy-btn';
    copyBtn.textContent = 'Copy';
    copyBtn.setAttribute('aria-label', 'Copy code snippet');

    copyBtn.addEventListener('click', async () => {
      const text = codeEl.textContent || '';
      try {
        await navigator.clipboard.writeText(text);
        copyBtn.textContent = 'Copied';
      } catch {
        copyBtn.textContent = 'Copy failed';
      }
      window.setTimeout(() => {
        copyBtn.textContent = 'Copy';
      }, 1800);
    });

    wrapper.appendChild(copyBtn);
  });

  // 3. Click-to-load YouTube trailer facade
  const facades = document.querySelectorAll('[data-youtube-id]');
  facades.forEach((facade) => {
    const videoId = facade.getAttribute('data-youtube-id') || '';
    if (!/^[A-Za-z0-9_-]{11}$/.test(videoId)) return;

    const trigger = facade.querySelector('button') || facade;
    trigger.addEventListener('click', () => {
      const iframe = document.createElement('iframe');
      iframe.src = `https://www.youtube-nocookie.com/embed/${videoId}?autoplay=1`;
      iframe.title = facade.getAttribute('data-video-title') || 'SpatialFin video trailer';
      iframe.allow = 'accelerometer; autoplay; clipboard-write; encrypted-media; gyroscope; picture-in-picture';
      iframe.allowFullscreen = true;
      facade.innerHTML = '';
      facade.appendChild(iframe);
    });
  });
})();
