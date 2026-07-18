import * as THREE from 'three';
import * as xb from 'xrblocks';
import {
  fetchEpisodes,
  fetchItemImage,
  fetchItems,
  fetchLatestMedia,
  fetchNextUp,
  fetchResumeItems,
  fetchSimilarItems,
  fetchSuggestions,
  fetchViews,
  extractMediaPills,
  toggleFavorite,
  toggleItemPlayed,
  JellyfinApiError,
  type JellyfinImageType,
  type JellyfinItem,
  type JellyfinView,
} from './api';
import {CanvasView, drawCoverImage, ellipsize, fillRoundedRect, roundedRect} from './CanvasView';
import {getServerUrl, getSession, logout} from './auth';
import {PlayerSpace} from './PlayerSpace';

const PANEL_WIDTH_DP = 1400;
const PANEL_HEIGHT_DP = 824;
// Keep the Android-derived layout coordinates, but give the web panel enough
// real-world size for comfortable headset reading.
const PANEL_WORLD_SCALE = 1.7;
const HERO_COUNT = 3;
const SHELF_COUNT = 5;

const COLORS = {
  background: '#111318',
  surface: '#1d2024',
  surfaceHigh: '#272a2f',
  surfaceHighest: '#32353a',
  onSurface: '#e1e2e8',
  onSurfaceMuted: '#aeb4be',
  primary: '#a4c9fe',
  onPrimary: '#00315c',
  outline: 'rgba(255,255,255,0.15)',
  accent: '#4fc3f7',
  error: '#ffb4ab',
};

interface HomeShelf {
  id: string;
  title: string;
  items: JellyfinItem[];
}

interface HomeModel {
  views: JellyfinView[];
  heroItems: JellyfinItem[];
  shelves: HomeShelf[];
}

type HomeCanvasScreen =
  | {kind: 'loading'; title: string; body: string}
  | {kind: 'home'}
  | {kind: 'details'; item: JellyfinItem; episodes?: JellyfinItem[]; similar?: JellyfinItem[]}
  | {kind: 'library'; title: string; items: JellyfinItem[]}
  | {kind: 'settings'}
  | {kind: 'message'; title: string; body: string};

interface HomeCanvasActions {
  openItem: (item: JellyfinItem) => void;
  playItem: (item: JellyfinItem, startFromBeginning?: boolean) => void;
  togglePlayed: (item: JellyfinItem) => void;
  toggleFavorite: (item: JellyfinItem) => void;
  openSeries: (item: JellyfinItem) => void;
  showHome: () => void;
  showMedia: () => void;
  showLibrary: (view: JellyfinView) => void;
  showSettings: () => void;
  showRemote: () => void;
  refresh: () => void;
  signOut: () => void;
  close: () => void;
}

import type { CanvasPointer } from './CanvasView';



function imageKey(item: JellyfinItem, type: JellyfinImageType): string {
  return `${item.Id}:${type}`;
}

function itemProgress(item: JellyfinItem): number | null {
  const position = item.UserData?.PlaybackPositionTicks ?? 0;
  const runtime = item.RunTimeTicks ?? 0;
  if (position <= 0 || runtime <= 0) return null;
  return THREE.MathUtils.clamp(position / runtime, 0, 1);
}

function serverLabel(): string {
  const server = getServerUrl();
  if (!server) return 'Jellyfin';
  try {
    return new URL(server).hostname || 'Jellyfin';
  } catch {
    return 'Jellyfin';
  }
}

class HomeCanvasView extends CanvasView {
  private model: HomeModel = {views: [], heroItems: [], shelves: []};
  private screen: HomeCanvasScreen = {
    kind: 'loading',
    title: 'SpatialFin',
    body: 'Connecting to Jellyfin…',
  };
  private readonly artwork = new Map<string, ImageBitmap>();
  private appIcon: ImageBitmap | null = null;
  private heroOffset = 0;
  private scrollY = 0;
  private pointerStartY = 0;
  private scrollStartY = 0;
  private readonly actions: HomeCanvasActions;

  constructor(actions: HomeCanvasActions) {
    super(PANEL_WIDTH_DP, PANEL_HEIGHT_DP, {name: 'Android XR home surface'}, 3);
    this.actions = actions;
    this.userData.layout = 'android-xr-home';
    this.userData.logicalSize = {width: PANEL_WIDTH_DP, height: PANEL_HEIGHT_DP};
    this.redraw();
  }

  protected override onCanvasPointerDown(pointer: CanvasPointer): boolean {
    this.pointerStartY = pointer.y;
    this.scrollStartY = this.scrollY;
    return false;
  }

  protected override onCanvasPointerMove(pointer: CanvasPointer): boolean {
    if (this.screen.kind !== 'home') return false;
    const delta = pointer.y - this.pointerStartY;
    if (Math.abs(delta) > 10) {
      this.suppressNextTrigger = true;
    }
    const maxScroll = Math.max(0, 100 + 260 + 200 + this.model.shelves.length * 260 - PANEL_HEIGHT_DP);
    const newScrollY = THREE.MathUtils.clamp(this.scrollStartY - delta, 0, maxScroll);
    if (this.scrollY !== newScrollY) {
      this.scrollY = newScrollY;
      return true;
    }
    return false;
  }

  setAppIcon(icon: ImageBitmap) {
    this.appIcon?.close();
    this.appIcon = icon;
    this.redraw();
  }

  setModel(model: HomeModel) {
    this.model = model;
    this.heroOffset = 0;
    this.screen = {kind: 'home'};
    this.redraw();
  }

  setScreen(screen: HomeCanvasScreen) {
    this.screen = screen;
    this.redraw();
  }

  getScreen(): HomeCanvasScreen {
    return this.screen;
  }

  requestRedraw() {
    this.redraw();
  }

  setArtwork(item: JellyfinItem, type: JellyfinImageType, image: ImageBitmap) {
    const key = imageKey(item, type);
    this.artwork.get(key)?.close();
    this.artwork.set(key, image);
    this.redraw();
  }

  advanceHero() {
    if (this.screen.kind !== 'home' || this.model.heroItems.length <= HERO_COUNT) return;
    this.heroOffset = (this.heroOffset + 1) % this.model.heroItems.length;
    this.redraw();
  }

  private imageFor(item: JellyfinItem, landscape: boolean): ImageBitmap | null {
    if (landscape) {
      const backdrop = this.artwork.get(imageKey(item, 'Backdrop'));
      if (backdrop) return backdrop;
    }
    return this.artwork.get(imageKey(item, 'Primary')) ?? null;
  }

  protected override draw() {
    this.userData.screen = this.screen.kind;
    this.drawRail();
    switch (this.screen.kind) {
      case 'loading':
        this.drawMessage(this.screen.title, this.screen.body, true);
        break;
      case 'home':
        this.drawHome();
        break;
      case 'details':
        this.drawDetails(this.screen);
        break;
      case 'library':
        this.drawLibrary(this.screen.title, this.screen.items);
        break;
      case 'settings':
        this.drawSettings();
        break;
      case 'message':
        this.drawMessage(this.screen.title, this.screen.body, false);
        break;
    }
  }

  private drawRail() {
    const ctx = this.context;
    ctx.save();
    ctx.fillStyle = 'rgba(16,19,24,0.58)';
    ctx.fillRect(0, 0, 112, PANEL_HEIGHT_DP);
    ctx.fillStyle = COLORS.outline;
    ctx.fillRect(111, 0, 1, PANEL_HEIGHT_DP);

    const active = this.screen.kind === 'home' ? 'home' :
      this.screen.kind === 'library' ? 'media' :
        this.screen.kind === 'settings' ? 'profile' : '';
    const items = [
      {id: 'home', icon: '⌂', label: 'Home', y: 28, action: this.actions.showHome},
      {id: 'libraries', icon: '◫', label: 'Libraries', y: 116, action: this.actions.showMedia},
      {id: 'profile', icon: '◎', label: 'Profile', y: 626, action: this.actions.showSettings},
    ];
    for (const item of items) {
      const selected = active === item.id;
      const hovered = this.isHovered(`rail-${item.id}`);
      if (selected || hovered) {
        fillRoundedRect(
          ctx,
          selected ? 'rgba(164,201,254,0.20)' : 'rgba(255,255,255,0.08)',
          14,
          item.y,
          84,
          72,
          28,
        );
      }
      ctx.textAlign = 'center';
      ctx.fillStyle = selected ? COLORS.primary : COLORS.onSurfaceMuted;
      ctx.font = '600 30px system-ui, sans-serif';
      ctx.fillText(item.icon, 56, item.y + 31);
      ctx.font = '600 12px system-ui, sans-serif';
      ctx.fillText(item.label, 56, item.y + 55);
      this.addHitZone({
        id: `rail-${item.id}`,
        x: 14,
        y: item.y,
        width: 84,
        height: 72,
        action: item.action,
      });
    }
    ctx.restore();
  }

  private drawHeader() {
    const ctx = this.context;
    const y = 16;
    this.drawHeaderPill('server', 136, y, 286, 64, () => this.setScreen({
      kind: 'message',
      title: serverLabel(),
      body: 'Connected to Jellyfin. Choose Libraries to browse your media.',
    }), () => {
      if (this.appIcon) {
        ctx.save();
        roundedRect(ctx, 151, y + 10, 44, 44, 22);
        ctx.clip();
        drawCoverImage(ctx, this.appIcon, 151, y + 10, 44, 44);
        ctx.restore();
      } else {
        ctx.fillStyle = COLORS.primary;
        ctx.beginPath();
        ctx.arc(173, y + 32, 20, 0, Math.PI * 2);
        ctx.fill();
      }
      ctx.textAlign = 'left';
      ctx.fillStyle = COLORS.onSurface;
      ctx.font = '650 23px system-ui, sans-serif';
      ctx.fillText(ellipsize(ctx, serverLabel(), 200), 207, y + 40);
    });

    this.drawTextPill('settings', '◎  Settings', 1056, y, 180, 64, this.actions.showSettings);
    this.drawTextPill('remote', '📱  Remote', 1250, y, 170, 64, this.actions.showRemote);
    this.drawTextPill('close', '×  Exit XR', 1434, y, 134, 64, this.actions.close);
  }

  private drawHeaderPill(
    id: string,
    x: number,
    y: number,
    width: number,
    height: number,
    action: () => void,
    content: () => void,
  ) {
    fillRoundedRect(
      this.context,
      this.isHovered(id) ? COLORS.surfaceHighest : COLORS.surfaceHigh,
      x,
      y,
      width,
      height,
      28,
    );
    content();
    this.addHitZone({id, x, y, width, height, action});
  }

  private drawTextPill(
    id: string,
    label: string,
    x: number,
    y: number,
    width: number,
    height: number,
    action: () => void,
  ) {
    this.drawHeaderPill(id, x, y, width, height, action, () => {
      const ctx = this.context;
      ctx.textAlign = 'center';
      ctx.fillStyle = COLORS.onSurface;
      ctx.font = '650 18px system-ui, sans-serif';
      ctx.fillText(label, x + width / 2, y + 39);
    });
  }

  private drawHome() {
    this.drawHeader();
    const heroes = Array.from({length: Math.min(HERO_COUNT, this.model.heroItems.length)}, (_, index) =>
      this.model.heroItems[(this.heroOffset + index) % this.model.heroItems.length],
    );
    const contentX = 136;
    const contentWidth = 1248;
    let currentY = 98 - this.scrollY;

    if (this.model.views.length > 0) {
      this.drawMyMedia(contentX, currentY);
      currentY += 160;
    }

    const gap = 16;
    const heroWidth = (contentWidth - gap * 2) / 3;
    const heroHeight = 228;
    heroes.forEach((item, index) => {
      this.drawHeroCard(item, contentX + index * (heroWidth + gap), currentY, heroWidth, heroHeight);
    });

    if (heroes.length === 0) {
      this.drawEmptyCard(contentX, currentY, contentWidth, heroHeight, 'No suggestions yet');
    }
    currentY += heroHeight + 40;

    this.model.shelves.forEach((shelf) => {
      if (shelf.items.length > 0) {
        this.drawShelf(shelf, currentY);
        currentY += 260;
      }
    });

    this.userData.uiLabels = [
      serverLabel(),
      ...this.model.views.map((v) => v.Name),
      ...heroes.map((item) => item.Name),
      ...this.model.shelves.map((shelf) => shelf.title),
    ];
  }

  private drawMyMedia(x: number, y: number) {
    if (y > PANEL_HEIGHT_DP || y < -150) return;
    const ctx = this.context;
    ctx.fillStyle = COLORS.onSurface;
    ctx.font = '700 20px system-ui, sans-serif';
    ctx.fillText('My Media', x + 14, y + 21);
    
    const cardY = y + 40;
    const width = 160;
    const height = 90;
    const gap = 16;
    this.model.views.slice(0, 7).forEach((view, index) => {
      const cardX = x + index * (width + gap);
      const id = `view-${view.Id}`;
      ctx.save();
      roundedRect(ctx, cardX, cardY, width, height, 12);
      ctx.clip();
      ctx.fillStyle = COLORS.surfaceHigh;
      ctx.fillRect(cardX, cardY, width, height);
      ctx.restore();
      if (this.isHovered(id)) {
        ctx.strokeStyle = COLORS.primary;
        ctx.lineWidth = 3;
        roundedRect(ctx, cardX + 1.5, cardY + 1.5, width - 3, height - 3, 11);
        ctx.stroke();
      }
      ctx.textAlign = 'center';
      ctx.fillStyle = '#ffffff';
      ctx.font = '650 16px system-ui, sans-serif';
      ctx.fillText(ellipsize(ctx, view.Name, width - 10), cardX + width / 2, cardY + height / 2 + 6);
      
      this.addHitZone({id, x: cardX, y: cardY, width, height, action: () => this.actions.showLibrary(view)});
    });
  }

  private drawHeroCard(
    item: JellyfinItem,
    x: number,
    y: number,
    width: number,
    height: number,
  ) {
    const ctx = this.context;
    ctx.save();
    roundedRect(ctx, x, y, width, height, 32);
    ctx.clip();
    const image = this.imageFor(item, true);
    if (image) drawCoverImage(ctx, image, x, y, width, height);
    else {
      const placeholder = ctx.createLinearGradient(x, y, x + width, y + height);
      placeholder.addColorStop(0, '#26384a');
      placeholder.addColorStop(1, '#111318');
      ctx.fillStyle = placeholder;
      ctx.fillRect(x, y, width, height);
    }
    const gradient = ctx.createLinearGradient(x, y, x, y + height);
    gradient.addColorStop(0, 'rgba(0,0,0,0.08)');
    gradient.addColorStop(0.46, 'rgba(0,0,0,0.34)');
    gradient.addColorStop(1, 'rgba(0,0,0,0.88)');
    ctx.fillStyle = gradient;
    ctx.fillRect(x, y, width, height);

    let badgeRight = x + width - 14;
    if (item.UserData?.Played) {
      badgeRight -= 26;
      fillRoundedRect(ctx, '#e53935', badgeRight, y + 14, 26, 26, 13);
      ctx.textAlign = 'center';
      ctx.fillStyle = '#ffffff';
      ctx.font = '700 14px system-ui, sans-serif';
      ctx.fillText('✓', badgeRight + 13, y + 32);
      badgeRight -= 6;
    }
    if (item.UserData?.IsFavorite) {
      badgeRight -= 26;
      fillRoundedRect(ctx, '#e53935', badgeRight, y + 14, 26, 26, 13);
      ctx.textAlign = 'center';
      ctx.fillStyle = '#ffffff';
      ctx.font = '700 14px system-ui, sans-serif';
      ctx.fillText('♥', badgeRight + 13, y + 32);
      badgeRight -= 6;
    }
    if (item.UserData?.UnplayedItemCount && item.UserData.UnplayedItemCount > 0) {
      const unplayedLabel = `${item.UserData.UnplayedItemCount}`;
      ctx.font = '700 12px system-ui, sans-serif';
      const unplayedWidth = ctx.measureText(unplayedLabel).width + 14;
      badgeRight -= unplayedWidth;
      fillRoundedRect(ctx, COLORS.primary, badgeRight, y + 14, unplayedWidth, 26, 13);
      ctx.textAlign = 'center';
      ctx.fillStyle = COLORS.onPrimary;
      ctx.fillText(unplayedLabel, badgeRight + unplayedWidth / 2, y + 31);
    }

    const rating = item.CommunityRating?.toFixed(1) ??
      (item.CriticRating !== undefined ? `${Math.round(item.CriticRating)}%` : null);
    let pillX = x + 20;
    if (rating) pillX += this.drawMiniPill(`★ ${rating}`, pillX, y + height - 116) + 8;
    for (const pill of extractMediaPills(item)) {
      pillX += this.drawMiniPill(pill, pillX, y + height - 116) + 8;
    }
    
    const genres = (item.Genres ?? []).slice(0, 3).join(', ');
    ctx.textAlign = 'left';
    ctx.fillStyle = '#d4d8de';
    ctx.font = '500 15px system-ui, sans-serif';
    ctx.fillText(ellipsize(ctx, genres || item.Type || 'Video', width - 40), x + 20, y + height - 78);
    ctx.fillStyle = '#ffffff';
    ctx.font = '650 25px system-ui, sans-serif';
    ctx.fillText(ellipsize(ctx, item.Name, width - 40), x + 20, y + height - 46);

    const progress = itemProgress(item);
    if (progress !== null) {
      ctx.fillStyle = 'rgba(0,0,0,0.6)';
      ctx.fillRect(x + 20, y + height - 40, width - 40, 4);
      ctx.fillStyle = COLORS.primary;
      ctx.fillRect(x + 20, y + height - 40, (width - 40) * progress, 4);
    }
    ctx.restore();

    const btnY = y + height - 38;
    const playText = progress !== null ? '▶ Resume' : '▶ Play';
    this.drawSmallActionButton(`hero-play-${item.Id}`, playText, x + 20, btnY, 92, 28, true, () => this.actions.playItem(item));
    this.drawSmallActionButton(`hero-watched-${item.Id}`, item.UserData?.Played ? '✓ Watched' : '○ Mark', x + 120, btnY, 94, 28, item.UserData?.Played ?? false, () => this.actions.togglePlayed(item));
    this.drawSmallActionButton(`hero-fav-${item.Id}`, item.UserData?.IsFavorite ? '♥ Fav' : '♡ Fav', x + 222, btnY, 70, 28, item.UserData?.IsFavorite ?? false, () => this.actions.toggleFavorite(item));

    const id = `hero-${item.Id}`;
    if (this.isHovered(id)) {
      ctx.strokeStyle = COLORS.primary;
      ctx.lineWidth = 4;
      roundedRect(ctx, x + 2, y + 2, width - 4, height - 4, 30);
      ctx.stroke();
    }
    this.addHitZone({id, x, y, width, height: height - 42, action: () => this.actions.openItem(item)});
  }

  private drawSmallActionButton(
    id: string,
    label: string,
    x: number,
    y: number,
    width: number,
    height: number,
    active: boolean,
    action: () => void,
  ) {
    const ctx = this.context;
    const hovered = this.isHovered(id);
    const bg = active
      ? (hovered ? '#64b5f6' : COLORS.primary)
      : (hovered ? 'rgba(255,255,255,0.28)' : 'rgba(0,0,0,0.6)');
    fillRoundedRect(ctx, bg, x, y, width, height, height / 2);
    ctx.textAlign = 'center';
    ctx.fillStyle = active ? COLORS.onPrimary : COLORS.onSurface;
    ctx.font = '600 12px system-ui, sans-serif';
    ctx.fillText(label, x + width / 2, y + height / 2 + 4);
    this.addHitZone({id, x, y, width, height, action});
  }

  private drawMiniPill(label: string, x: number, y: number): number {
    const ctx = this.context;
    ctx.font = '700 13px system-ui, sans-serif';
    const width = ctx.measureText(label).width + 18;
    fillRoundedRect(ctx, 'rgba(15,18,22,0.76)', x, y, width, 25, 13);
    ctx.textAlign = 'center';
    ctx.fillStyle = COLORS.onSurface;
    ctx.fillText(label, x + width / 2, y + 18);
    return width;
  }

  private drawShelf(shelf: HomeShelf, y: number) {
    const ctx = this.context;
    const x = 136;
    ctx.fillStyle = COLORS.primary;
    fillRoundedRect(ctx, COLORS.primary, x, y + 4, 4, 20, 2);
    ctx.textAlign = 'left';
    ctx.fillStyle = COLORS.onSurface;
    ctx.font = '700 20px system-ui, sans-serif';
    ctx.fillText(shelf.title, x + 14, y + 21);
    ctx.fillStyle = 'rgba(225,226,232,0.5)';
    ctx.font = '500 14px system-ui, sans-serif';
    ctx.fillText(String(shelf.items.length), x + 22 + ctx.measureText(shelf.title).width, y + 20);

    const cardY = y + 40;
    const width = 236;
    const imageHeight = 133;
    const gap = 16;
    shelf.items.slice(0, SHELF_COUNT).forEach((item, index) => {
      const cardX = x + index * (width + gap);
      const id = `shelf-${shelf.id}-${item.Id}`;
      ctx.save();
      roundedRect(ctx, cardX, cardY, width, imageHeight, 12);
      ctx.clip();
      const image = this.imageFor(item, true);
      if (image) drawCoverImage(ctx, image, cardX, cardY, width, imageHeight);
      else {
        ctx.fillStyle = COLORS.surfaceHigh;
        ctx.fillRect(cardX, cardY, width, imageHeight);
      }

      let shelfBadgeRight = cardX + width - 8;
      if (item.UserData?.Played) {
        shelfBadgeRight -= 22;
        fillRoundedRect(ctx, '#e53935', shelfBadgeRight, cardY + 8, 22, 22, 11);
        ctx.textAlign = 'center';
        ctx.fillStyle = '#ffffff';
        ctx.font = '700 13px system-ui, sans-serif';
        ctx.fillText('✓', shelfBadgeRight + 11, cardY + 23);
        shelfBadgeRight -= 6;
      }
      if (item.UserData?.IsFavorite) {
        shelfBadgeRight -= 22;
        fillRoundedRect(ctx, '#e53935', shelfBadgeRight, cardY + 8, 22, 22, 11);
        ctx.textAlign = 'center';
        ctx.fillStyle = '#ffffff';
        ctx.font = '700 13px system-ui, sans-serif';
        ctx.fillText('♥', shelfBadgeRight + 11, cardY + 23);
        shelfBadgeRight -= 6;
      }
      if (item.UserData?.UnplayedItemCount && item.UserData.UnplayedItemCount > 0) {
        const countStr = `${item.UserData.UnplayedItemCount}`;
        ctx.font = '700 11px system-ui, sans-serif';
        const badgeW = ctx.measureText(countStr).width + 12;
        shelfBadgeRight -= badgeW;
        fillRoundedRect(ctx, COLORS.primary, shelfBadgeRight, cardY + 8, badgeW, 22, 11);
        ctx.textAlign = 'center';
        ctx.fillStyle = COLORS.onPrimary;
        ctx.fillText(countStr, shelfBadgeRight + badgeW / 2, cardY + 22);
      }

      const progress = itemProgress(item);
      if (progress !== null) {
        ctx.fillStyle = 'rgba(0,0,0,0.58)';
        ctx.fillRect(cardX + 8, cardY + imageHeight - 12, width - 16, 6);
        ctx.fillStyle = COLORS.primary;
        ctx.fillRect(cardX + 8, cardY + imageHeight - 12, (width - 16) * progress, 6);
      }
      ctx.restore();
      if (this.isHovered(id)) {
        ctx.strokeStyle = COLORS.primary;
        ctx.lineWidth = 3;
        roundedRect(ctx, cardX + 1.5, cardY + 1.5, width - 3, imageHeight - 3, 11);
        ctx.stroke();
      }
      ctx.textAlign = 'left';
      ctx.fillStyle = COLORS.onSurface;
      ctx.font = '650 16px system-ui, sans-serif';
      const label = item.Type === 'Episode' && item.SeriesName ? item.SeriesName : item.Name;
      ctx.fillText(ellipsize(ctx, label, width), cardX, cardY + imageHeight + 24);
      if (item.Type === 'Episode') {
        const episode = [
          item.ParentIndexNumber !== undefined ? `S${item.ParentIndexNumber}` : null,
          item.IndexNumber !== undefined ? `E${item.IndexNumber}` : null,
          item.Name,
        ].filter(Boolean).join(' · ');
        ctx.fillStyle = COLORS.onSurfaceMuted;
        ctx.font = '500 13px system-ui, sans-serif';
        ctx.fillText(ellipsize(ctx, episode, width), cardX, cardY + imageHeight + 44);
      }
      this.addHitZone({id, x: cardX, y: cardY, width, height: imageHeight + 48, action: () => this.actions.openItem(item)});
    });
  }

  private drawDetails(detailsScreen: {item: JellyfinItem; episodes?: JellyfinItem[]; similar?: JellyfinItem[]}) {
    const item = detailsScreen.item;
    const episodes = detailsScreen.episodes ?? [];
    const similar = detailsScreen.similar ?? [];
    const ctx = this.context;
    const x = 112;
    const width = PANEL_WIDTH_DP - x;
    const backdropHeight = 390;
    const backdrop = this.imageFor(item, true);
    ctx.save();
    roundedRect(ctx, x, 0, width, backdropHeight, 28);
    ctx.clip();
    if (backdrop) drawCoverImage(ctx, backdrop, x, 0, width, backdropHeight);
    else {
      ctx.fillStyle = COLORS.surfaceHigh;
      ctx.fillRect(x, 0, width, backdropHeight);
    }
    const gradient = ctx.createLinearGradient(x, 0, x, backdropHeight);
    gradient.addColorStop(0, 'rgba(0,0,0,0.08)');
    gradient.addColorStop(0.52, 'rgba(0,0,0,0.42)');
    gradient.addColorStop(1, COLORS.background);
    ctx.fillStyle = gradient;
    ctx.fillRect(x, 0, width, backdropHeight);
    ctx.restore();

    this.drawCircleButton('detail-back', '←', 138, 28, 52, () => this.actions.showHome());
    const poster = this.imageFor(item, false);
    if (poster) {
      ctx.save();
      roundedRect(ctx, 158, 154, 176, 266, 12);
      ctx.clip();
      drawCoverImage(ctx, poster, 158, 154, 176, 266);
      ctx.restore();
    }
    const infoX = poster ? 364 : 158;
    ctx.textAlign = 'left';
    ctx.fillStyle = '#ffffff';
    ctx.font = '650 42px system-ui, sans-serif';
    ctx.fillText(ellipsize(ctx, item.Name, 930), infoX, 208);

    const metadata = [
      item.ProductionYear?.toString(),
      item.RunTimeTicks ? `${Math.max(1, Math.round(item.RunTimeTicks / 600_000_000))} min` : null,
      item.OfficialRating,
      ...extractMediaPills(item),
      ...(item.Genres ?? []).slice(0, 3),
    ].filter((value): value is string => Boolean(value));
    let pillX = infoX;
    for (const value of metadata) {
      ctx.font = '600 14px system-ui, sans-serif';
      const pillWidth = ctx.measureText(value).width + 24;
      fillRoundedRect(ctx, 'rgba(25,28,32,0.78)', pillX, 228, pillWidth, 32, 16);
      ctx.fillStyle = COLORS.onSurface;
      ctx.textAlign = 'center';
      ctx.fillText(value, pillX + pillWidth / 2, 249);
      pillX += pillWidth + 8;
    }

    ctx.textAlign = 'left';
    ctx.fillStyle = '#e2e4e8';
    ctx.font = '500 18px system-ui, sans-serif';
    this.drawWrappedText(item.Overview || 'No synopsis is available.', infoX, 288, 910, 25, 3);

    const isSeries = item.Type === 'Series';
    const hasProgress = itemProgress(item) !== null;
    this.drawActionButton(
      'detail-play',
      isSeries ? '▶  Episodes' : hasProgress ? '▶  Resume' : '▶  Play',
      158,
      454,
      210,
      68,
      true,
      () => isSeries ? this.actions.openSeries(item) : this.actions.playItem(item),
    );
    if (hasProgress) {
      this.drawActionButton('detail-restart', '↺', 384, 454, 68, 68, false, () => this.actions.playItem(item, true));
    }
    const watchedX = hasProgress ? 468 : 384;
    const favX = watchedX + 84;
    const castX = favX + 84;
    this.drawActionButton(
      'detail-watched',
      item.UserData?.Played ? '✓' : '○',
      watchedX,
      454,
      68,
      68,
      item.UserData?.Played ?? false,
      () => this.actions.togglePlayed(item),
    );
    this.drawActionButton(
      'detail-favorite',
      item.UserData?.IsFavorite ? '♥' : '♡',
      favX,
      454,
      68,
      68,
      item.UserData?.IsFavorite ?? false,
      () => this.actions.toggleFavorite(item),
    );
    this.drawActionButton('detail-cast', '▧', castX, 454, 68, 68, false, () => this.actions.showRemote());

    ctx.fillStyle = COLORS.onSurface;
    ctx.font = '700 18px system-ui, sans-serif';
    ctx.fillText('Overview', 158, 584);
    ctx.fillStyle = COLORS.onSurfaceMuted;
    ctx.font = '500 17px system-ui, sans-serif';
    this.drawWrappedText(item.Overview || 'No synopsis is available.', 158, 620, 1160, 27, 4);

    let currentSectionY = 740;
    if (item.People && item.People.length > 0) {
      ctx.fillStyle = COLORS.onSurface;
      ctx.font = '700 18px system-ui, sans-serif';
      ctx.fillText('Cast & Crew', 158, currentSectionY);
      let personX = 158;
      item.People.slice(0, 12).forEach((person) => {
        ctx.fillStyle = COLORS.surfaceHigh;
        ctx.beginPath();
        ctx.arc(personX + 32, currentSectionY + 40, 32, 0, Math.PI * 2);
        ctx.fill();
        ctx.textAlign = 'center';
        ctx.fillStyle = COLORS.onSurface;
        ctx.font = '500 12px system-ui, sans-serif';
        ctx.fillText(ellipsize(ctx, person.Name || '', 70), personX + 32, currentSectionY + 84);
        if (person.Role) {
          ctx.fillStyle = COLORS.onSurfaceMuted;
          ctx.font = '500 11px system-ui, sans-serif';
          ctx.fillText(ellipsize(ctx, person.Role, 70), personX + 32, currentSectionY + 98);
        }
        personX += 80;
      });
      ctx.textAlign = 'left';
      currentSectionY += 120;
    }

    if (episodes.length > 0) {
      ctx.fillStyle = COLORS.onSurface;
      ctx.font = '700 18px system-ui, sans-serif';
      ctx.fillText(`Episodes (${episodes.length})`, 158, currentSectionY);
      currentSectionY += 28;
      const epW = 216;
      const epH = 122;
      const epGap = 16;
      episodes.slice(0, 5).forEach((ep, idx) => {
        const epX = 158 + idx * (epW + epGap);
        const epId = `detail-ep-${ep.Id}`;
        ctx.save();
        roundedRect(ctx, epX, currentSectionY, epW, epH, 12);
        ctx.clip();
        const epImg = this.imageFor(ep, true);
        if (epImg) drawCoverImage(ctx, epImg, epX, currentSectionY, epW, epH);
        else {
          ctx.fillStyle = COLORS.surfaceHigh;
          ctx.fillRect(epX, currentSectionY, epW, epH);
        }
        if (ep.UserData?.Played) {
          fillRoundedRect(ctx, '#e53935', epX + epW - 28, currentSectionY + 8, 22, 22, 11);
          ctx.textAlign = 'center';
          ctx.fillStyle = '#ffffff';
          ctx.font = '700 13px system-ui, sans-serif';
          ctx.fillText('✓', epX + epW - 17, currentSectionY + 23);
        }
        ctx.restore();
        if (this.isHovered(epId)) {
          ctx.strokeStyle = COLORS.primary;
          ctx.lineWidth = 3;
          roundedRect(ctx, epX + 1.5, currentSectionY + 1.5, epW - 3, epH - 3, 11);
          ctx.stroke();
        }
        ctx.textAlign = 'left';
        ctx.fillStyle = COLORS.onSurface;
        ctx.font = '650 14px system-ui, sans-serif';
        const epName = [
          ep.ParentIndexNumber !== undefined ? `S${ep.ParentIndexNumber}` : null,
          ep.IndexNumber !== undefined ? `E${ep.IndexNumber}` : null,
          ep.Name,
        ].filter(Boolean).join(' · ');
        ctx.fillText(ellipsize(ctx, epName, epW), epX, currentSectionY + epH + 22);
        this.addHitZone({id: epId, x: epX, y: currentSectionY, width: epW, height: epH + 28, action: () => this.actions.playItem(ep)});
      });
      currentSectionY += epH + 48;
    }

    if (similar.length > 0) {
      ctx.fillStyle = COLORS.onSurface;
      ctx.font = '700 18px system-ui, sans-serif';
      ctx.fillText('More Like This', 158, currentSectionY);
      currentSectionY += 28;
      const simW = 216;
      const simH = 122;
      const simGap = 16;
      similar.slice(0, 5).forEach((simItem, idx) => {
        const simX = 158 + idx * (simW + simGap);
        const simId = `detail-sim-${simItem.Id}`;
        ctx.save();
        roundedRect(ctx, simX, currentSectionY, simW, simH, 12);
        ctx.clip();
        const simImg = this.imageFor(simItem, true);
        if (simImg) drawCoverImage(ctx, simImg, simX, currentSectionY, simW, simH);
        else {
          ctx.fillStyle = COLORS.surfaceHigh;
          ctx.fillRect(simX, currentSectionY, simW, simH);
        }
        ctx.restore();
        if (this.isHovered(simId)) {
          ctx.strokeStyle = COLORS.primary;
          ctx.lineWidth = 3;
          roundedRect(ctx, simX + 1.5, currentSectionY + 1.5, simW - 3, simH - 3, 11);
          ctx.stroke();
        }
        ctx.textAlign = 'left';
        ctx.fillStyle = COLORS.onSurface;
        ctx.font = '650 14px system-ui, sans-serif';
        ctx.fillText(ellipsize(ctx, simItem.Name, simW), simX, currentSectionY + simH + 22);
        this.addHitZone({id: simId, x: simX, y: currentSectionY, width: simW, height: simH + 28, action: () => this.actions.openItem(simItem)});
      });
    }

    this.userData.uiLabels = [item.Name, isSeries ? 'Episodes' : 'Play', 'Overview'];
  }

  private drawLibrary(title: string, items: JellyfinItem[]) {
    const ctx = this.context;
    this.drawCircleButton('library-back', '←', 136, 22, 54, this.actions.showHome);
    ctx.textAlign = 'left';
    ctx.fillStyle = COLORS.onSurface;
    ctx.font = '650 34px system-ui, sans-serif';
    ctx.fillText(title, 214, 61);
    ctx.fillStyle = COLORS.onSurfaceMuted;
    ctx.font = '500 15px system-ui, sans-serif';
    ctx.fillText(`${items.length} items`, 214, 84);

    const columns = 5;
    const cardWidth = 224;
    const imageHeight = 126;
    const gapX = 20;
    const rowHeight = 218;
    items.slice(0, 15).forEach((item, index) => {
      const column = index % columns;
      const row = Math.floor(index / columns);
      const x = 144 + column * (cardWidth + gapX);
      const y = 116 + row * rowHeight;
      const id = `library-${item.Id}`;
      ctx.save();
      roundedRect(ctx, x, y, cardWidth, imageHeight, 12);
      ctx.clip();
      const image = this.imageFor(item, true);
      if (image) drawCoverImage(ctx, image, x, y, cardWidth, imageHeight);
      else {
        ctx.fillStyle = COLORS.surfaceHigh;
        ctx.fillRect(x, y, cardWidth, imageHeight);
      }
      ctx.restore();
      if (this.isHovered(id)) {
        ctx.strokeStyle = COLORS.primary;
        ctx.lineWidth = 3;
        roundedRect(ctx, x + 1.5, y + 1.5, cardWidth - 3, imageHeight - 3, 11);
        ctx.stroke();
      }
      ctx.textAlign = 'left';
      ctx.fillStyle = COLORS.onSurface;
      ctx.font = '650 17px system-ui, sans-serif';
      ctx.fillText(ellipsize(ctx, item.Name, cardWidth), x, y + imageHeight + 25);
      ctx.fillStyle = COLORS.onSurfaceMuted;
      ctx.font = '500 13px system-ui, sans-serif';
      ctx.fillText((item.Genres ?? []).slice(0, 2).join(' · ') || item.Type || 'Video', x, y + imageHeight + 47);
      this.addHitZone({id, x, y, width: cardWidth, height: imageHeight + 56, action: () => this.actions.openItem(item)});
    });
    this.userData.uiLabels = [title, ...items.slice(0, 15).map((item) => item.Name)];
  }

  private drawSettings() {
    const session = getSession();
    this.drawCircleButton('settings-back', '←', 136, 22, 54, this.actions.showHome);
    const ctx = this.context;
    ctx.textAlign = 'left';
    ctx.fillStyle = COLORS.onSurface;
    ctx.font = '650 38px system-ui, sans-serif';
    ctx.fillText('Settings', 214, 65);
    ctx.fillStyle = COLORS.onSurfaceMuted;
    ctx.font = '500 18px system-ui, sans-serif';
    ctx.fillText('WebXR session', 214, 94);
    fillRoundedRect(ctx, COLORS.surfaceHigh, 160, 144, 1160, 286, 32);
    if (this.appIcon) {
      ctx.save();
      roundedRect(ctx, 198, 184, 112, 112, 56);
      ctx.clip();
      drawCoverImage(ctx, this.appIcon, 198, 184, 112, 112);
      ctx.restore();
    }
    ctx.fillStyle = COLORS.onSurface;
    ctx.font = '650 28px system-ui, sans-serif';
    ctx.fillText(session?.userName || 'Jellyfin user', 350, 216);
    ctx.fillStyle = COLORS.onSurfaceMuted;
    ctx.font = '500 17px system-ui, sans-serif';
    ctx.fillText(serverLabel(), 350, 250);
    ctx.fillText('SpatialFin WebXR · Material 3 dark theme', 350, 284);
    this.drawActionButton('refresh', '↻  Refresh home', 350, 326, 220, 64, false, this.actions.refresh);
    this.drawActionButton('sign-out', 'Sign out', 588, 326, 180, 64, false, this.actions.signOut);
    ctx.fillStyle = COLORS.onSurface;
    ctx.font = '700 20px system-ui, sans-serif';
    ctx.fillText('Player', 160, 496);
    ctx.fillStyle = COLORS.onSurfaceMuted;
    ctx.font = '500 17px system-ui, sans-serif';
    ctx.fillText('Flat cinema screen · movable at a fixed depth · spatial glass controls', 160, 536);
    this.userData.uiLabels = ['Settings', session?.userName, 'Refresh home', 'Sign out'];
  }

  private drawMessage(title: string, body: string, loading: boolean, top = 0) {
    const ctx = this.context;
    if (top === 0 && !loading) this.drawCircleButton('message-back', '←', 136, 22, 54, this.actions.showHome);
    const centerX = 756;
    const centerY = top || 372;
    if (loading) {
      ctx.strokeStyle = COLORS.primary;
      ctx.lineWidth = 8;
      ctx.beginPath();
      ctx.arc(centerX, centerY - 74, 34, -Math.PI / 2, Math.PI * 1.1);
      ctx.stroke();
    }
    ctx.textAlign = 'center';
    ctx.fillStyle = COLORS.onSurface;
    ctx.font = '650 36px system-ui, sans-serif';
    ctx.fillText(title, centerX, centerY);
    ctx.fillStyle = COLORS.onSurfaceMuted;
    ctx.font = '500 18px system-ui, sans-serif';
    this.drawWrappedText(body, centerX - 330, centerY + 38, 660, 28, 4, 'center');
    if (!loading && top === 0) {
      this.drawActionButton('message-home', 'Back home', centerX - 105, centerY + 154, 210, 64, true, this.actions.showHome);
    }
    this.userData.uiLabels = [title, body];
  }

  private drawEmptyCard(x: number, y: number, width: number, height: number, text: string) {
    fillRoundedRect(this.context, COLORS.surfaceHigh, x, y, width, height, 32);
    this.context.textAlign = 'center';
    this.context.fillStyle = COLORS.onSurfaceMuted;
    this.context.font = '600 22px system-ui, sans-serif';
    this.context.fillText(text, x + width / 2, y + height / 2 + 8);
  }

  private drawCircleButton(id: string, label: string, x: number, y: number, size: number, action: () => void) {
    const ctx = this.context;
    ctx.fillStyle = this.isHovered(id) ? COLORS.surfaceHighest : COLORS.surfaceHigh;
    ctx.beginPath();
    ctx.arc(x + size / 2, y + size / 2, size / 2, 0, Math.PI * 2);
    ctx.fill();
    ctx.textAlign = 'center';
    ctx.fillStyle = COLORS.onSurface;
    ctx.font = '600 27px system-ui, sans-serif';
    ctx.fillText(label, x + size / 2, y + size * 0.68);
    this.addHitZone({id, x, y, width: size, height: size, action});
  }

  private drawActionButton(
    id: string,
    label: string,
    x: number,
    y: number,
    width: number,
    height: number,
    primary: boolean,
    action: () => void,
  ) {
    const hovered = this.isHovered(id);
    fillRoundedRect(
      this.context,
      primary ? (hovered ? '#c5dcff' : COLORS.primary) : (hovered ? COLORS.surfaceHighest : COLORS.surfaceHigh),
      x,
      y,
      width,
      height,
      height / 2,
    );
    this.context.textAlign = 'center';
    this.context.fillStyle = primary ? COLORS.onPrimary : COLORS.onSurface;
    this.context.font = '700 18px system-ui, sans-serif';
    this.context.fillText(label, x + width / 2, y + height / 2 + 7);
    this.addHitZone({id, x, y, width, height, action});
  }

  private drawWrappedText(
    text: string,
    x: number,
    y: number,
    maxWidth: number,
    lineHeight: number,
    maxLines: number,
    align: CanvasTextAlign = 'left',
  ) {
    const ctx = this.context;
    const words = text.replace(/\s+/g, ' ').trim().split(' ');
    const lines: string[] = [];
    let line = '';
    for (const word of words) {
      const candidate = line ? `${line} ${word}` : word;
      if (ctx.measureText(candidate).width <= maxWidth || !line) line = candidate;
      else {
        lines.push(line);
        line = word;
        if (lines.length >= maxLines) break;
      }
    }
    if (line && lines.length < maxLines) lines.push(line);
    if (lines.length === maxLines && words.join(' ').length > lines.join(' ').length) {
      lines[maxLines - 1] = ellipsize(ctx, `${lines[maxLines - 1]}…`, maxWidth);
    }
    ctx.textAlign = align;
    const drawX = align === 'center' ? x + maxWidth / 2 : x;
    lines.forEach((entry, index) => ctx.fillText(entry, drawX, y + index * lineHeight));
  }

  override dispose() {
    for (const image of this.artwork.values()) image.close();
    this.artwork.clear();
    this.appIcon?.close();
    this.appIcon = null;
    super.dispose();
  }
}

export class HomeSpace extends xb.Script {
  private canvas: HomeCanvasView | null = null;
  private model: HomeModel = {views: [], heroItems: [], shelves: []};
  private requestGeneration = 0;
  private abortController = new AbortController();
  private heroInterval: number | null = null;
  private disposed = false;

  override init() {
    this.name = 'SpatialFin Home';
    this.disposed = false;
    this.add(new THREE.HemisphereLight(0xffffff, 0x24303d, 1.8));
    this.createPanel();
    void this.loadAppIcon();
    void this.loadHome();
    // Keep browser automation deterministic while production mirrors Android's
    // five-second auto-paging carousel.
    if (!new URLSearchParams(window.location.search).has('xrAutomation')) {
      this.heroInterval = window.setInterval(() => this.canvas?.advanceHero(), 5_000);
    }
  }

  private createPanel() {
    const panel = new xb.SpatialPanel({
      width: xb.View.dpToMeters(PANEL_WIDTH_DP) * PANEL_WORLD_SCALE,
      height: xb.View.dpToMeters(PANEL_HEIGHT_DP) * PANEL_WORLD_SCALE,
      backgroundColor: '#111318a8',
      borderWidth: 0.012,
      showHighlights: true,
      dragFacingCamera: true,
    });
    panel.name = 'SpatialFin Android XR home panel';
    panel.position.set(0, Math.max(xb.user.height - 0.08, 1.3), -1.75);
    panel.userData.androidDpSize = {width: PANEL_WIDTH_DP, height: PANEL_HEIGHT_DP};
    panel.userData.worldScale = PANEL_WORLD_SCALE;

    const canvas = new HomeCanvasView({
      openItem: (item) => void this.openItem(item),
      playItem: (item, startFromBeginning) => this.playItem(item, startFromBeginning),
      togglePlayed: (item) => void this.togglePlayed(item),
      toggleFavorite: (item) => void this.toggleFavorite(item),
      openSeries: (item) => void this.openSeries(item),
      showHome: () => canvas.setScreen({kind: 'home'}),
      showMedia: () => this.showAllMedia('Media'),
      showLibrary: (view) => void this.openLibrary(view),
      showSettings: () => this.showSettings(),
      showRemote: () => this.showRemote(),
      refresh: () => void this.loadHome(),
      signOut: () => void logout(),
      close: () => {
        window.dispatchEvent(new Event('spatialfin:exit-xr'));
      },
    });
    panel.add(canvas);
    panel.updateLayouts();
    this.add(panel);
    this.canvas = canvas;
  }

  private async togglePlayed(item: JellyfinItem) {
    const newPlayed = !(item.UserData?.Played);
    if (!item.UserData) item.UserData = {};
    item.UserData.Played = newPlayed;
    this.canvas?.requestRedraw();
    try {
      await toggleItemPlayed(item.Id, newPlayed);
    } catch (error) {
      console.warn('Failed to update watched status:', error);
      item.UserData.Played = !newPlayed;
      this.canvas?.requestRedraw();
    }
  }

  private async toggleFavorite(item: JellyfinItem) {
    const newFavorite = !(item.UserData?.IsFavorite);
    if (!item.UserData) item.UserData = {};
    item.UserData.IsFavorite = newFavorite;
    this.canvas?.requestRedraw();
    try {
      await toggleFavorite(item.Id, newFavorite);
    } catch (error) {
      console.warn('Failed to update favorite status:', error);
      item.UserData.IsFavorite = !newFavorite;
      this.canvas?.requestRedraw();
    }
  }

  private async loadAppIcon() {
    try {
      const response = await fetch('/app-icon.png', {signal: this.abortController.signal});
      if (!response.ok) return;
      const image = await createImageBitmap(await response.blob());
      if (this.disposed) image.close();
      else this.canvas?.setAppIcon(image);
    } catch (error) {
      if (!this.abortController.signal.aborted) console.warn('Could not load app icon:', error);
    }
  }

  private async loadHome() {
    const generation = ++this.requestGeneration;
    this.canvas?.setScreen({kind: 'loading', title: 'SpatialFin', body: 'Loading your media…'});
    try {
      const views = await fetchViews();
      const [suggestions, resume, nextUp, latest] = await Promise.all([
        fetchSuggestions().catch(() => []),
        fetchResumeItems().catch(() => []),
        fetchNextUp().catch(() => []),
        Promise.all(
          views.slice(0, 4).map(async (view) => ({
            view,
            items: await fetchLatestMedia(view.Id).catch(() => fetchItems(view.Id)),
          })),
        ),
      ]);
      if (generation !== this.requestGeneration || this.disposed) return;

      const latestItems = latest.flatMap((entry) => entry.items);
      const heroItems = (suggestions.length > 0 ? suggestions : latestItems).slice(0, 9);
      const shelves: HomeShelf[] = [];
      if (resume.length > 0) shelves.push({id: 'resume', title: 'Continue watching', items: resume});
      if (nextUp.length > 0) shelves.push({id: 'next-up', title: 'Next up', items: nextUp});
      for (const entry of latest) {
        if (entry.items.length > 0) shelves.push({id: `view-${entry.view.Id}`, title: `Latest ${entry.view.Name}`, items: entry.items});
      }
      this.model = {views, heroItems, shelves};
      this.canvas?.setModel(this.model);
      void this.loadArtwork([...heroItems, ...shelves.flatMap((shelf) => shelf.items.slice(0, 15))]);
    } catch (error) {
      if (generation !== this.requestGeneration || this.disposed) return;
      const message = error instanceof JellyfinApiError
        ? error.message
        : 'An unexpected Jellyfin error occurred.';
      this.canvas?.setScreen({kind: 'message', title: 'Couldn’t load home', body: message});
    }
  }

  private showAllMedia(title: string) {
    const items = this.uniqueItems(this.model.shelves.flatMap((shelf) => shelf.items));
    this.canvas?.setScreen({kind: 'library', title, items});
    void this.loadArtwork(items.slice(0, 15));
  }

  private async openLibrary(view: JellyfinView) {
    const generation = ++this.requestGeneration;
    this.canvas?.setScreen({kind: 'loading', title: view.Name, body: 'Loading library…'});
    try {
      const items = await fetchItems(view.Id);
      if (generation !== this.requestGeneration || this.disposed) return;
      this.canvas?.setScreen({kind: 'library', title: view.Name, items});
      void this.loadArtwork(items.slice(0, 15));
    } catch (error) {
      if (generation !== this.requestGeneration || this.disposed) return;
      this.canvas?.setScreen({
        kind: 'message',
        title: view.Name,
        body: error instanceof Error ? error.message : 'Library could not be loaded.',
      });
    }
  }

  private async openItem(item: JellyfinItem) {
    const generation = ++this.requestGeneration;
    this.canvas?.setScreen({kind: 'details', item});
    void this.loadArtwork([item, ...(item.People ?? []).map(() => item)]);
    try {
      const [episodes, similar] = await Promise.all([
        item.Type === 'Series' ? fetchEpisodes(item.Id).catch(() => []) : Promise.resolve([]),
        fetchSimilarItems(item.Id).catch(() => []),
      ]);
      if (generation !== this.requestGeneration || this.disposed) return;
      this.canvas?.setScreen({kind: 'details', item, episodes, similar});
      void this.loadArtwork([...episodes.slice(0, 10), ...similar.slice(0, 10)]);
    } catch {
      // Background load failed
    }
  }

  private playItem(item: JellyfinItem, startFromBeginning = false) {
    let playItemObj = item;
    if (startFromBeginning && item.UserData) {
      playItemObj = {
        ...item,
        UserData: {
          ...item.UserData,
          PlaybackPositionTicks: 0,
        },
      };
    }
    this.removeFromParent();
    xb.add(new PlayerSpace(playItemObj));
  }

  private async openSeries(item: JellyfinItem) {
    const generation = ++this.requestGeneration;
    this.canvas?.setScreen({kind: 'loading', title: item.Name, body: 'Loading episodes…'});
    try {
      const episodes = await fetchEpisodes(item.Id);
      if (generation !== this.requestGeneration || this.disposed) return;
      this.canvas?.setScreen({kind: 'library', title: `${item.Name} · Episodes`, items: episodes});
      void this.loadArtwork(episodes.slice(0, 15));
    } catch (error) {
      if (generation !== this.requestGeneration || this.disposed) return;
      this.canvas?.setScreen({
        kind: 'message',
        title: item.Name,
        body: error instanceof Error ? error.message : 'Episodes could not be loaded.',
      });
    }
  }

  private async loadArtwork(items: JellyfinItem[]) {
    const unique = this.uniqueItems(items);
    await Promise.all(unique.flatMap((item) => {
      const types: JellyfinImageType[] = ['Primary'];
      if (item.BackdropImageTags?.length) types.unshift('Backdrop');
      return types.map((type) => this.loadArtworkType(item, type));
    }));
  }

  private async loadArtworkType(item: JellyfinItem, type: JellyfinImageType) {
    try {
      const blob = await fetchItemImage(item, type, this.abortController.signal);
      if (!blob) return;
      const image = await createImageBitmap(blob);
      if (this.disposed || this.abortController.signal.aborted) image.close();
      else this.canvas?.setArtwork(item, type, image);
    } catch (error) {
      if (!this.abortController.signal.aborted) {
        console.warn(`Could not load ${type.toLowerCase()} artwork for ${item.Name}:`, error);
      }
    }
  }

  private uniqueItems(items: JellyfinItem[]): JellyfinItem[] {
    return [...new Map(items.map((item) => [item.Id, item])).values()];
  }

  private showSettings() {
    this.canvas?.setScreen({kind: 'settings'});
  }

  private showRemote() {
    import('./RemoteControlSpace').then(({RemoteControlSpace}) => {
      let existing = Array.from(xb.scene.children).find(c => c instanceof RemoteControlSpace);
      if (!existing) {
        xb.add(new RemoteControlSpace());
      }
    });
  }

  override dispose() {
    this.disposed = true;
    this.requestGeneration++;
    this.abortController.abort();
    this.abortController = new AbortController();
    if (this.heroInterval !== null) {
      window.clearInterval(this.heroInterval);
      this.heroInterval = null;
    }
    this.canvas = null;
  }
}
