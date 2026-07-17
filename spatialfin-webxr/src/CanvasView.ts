import * as THREE from 'three';
import * as xb from 'xrblocks';

export interface CanvasHitZone {
  id: string;
  x: number;
  y: number;
  width: number;
  height: number;
  action: () => void;
  disabled?: boolean;
}

export interface CanvasPointer {
  id: number;
  x: number;
  y: number;
  controller: THREE.Object3D;
}

/**
 * A crisp, interactive 2D canvas hosted by an XRBlocks View.
 *
 * Android's XR surfaces are Compose panels measured in dp. Drawing the web
 * counterpart in the same logical coordinate system keeps typography, radii,
 * spacing, gradients, and card composition faithful while XRBlocks still owns
 * spatial placement, ray hit testing, and controller/hand input.
 */
export abstract class CanvasView extends xb.View {
  readonly logicalWidth: number;
  readonly logicalHeight: number;
  readonly canvas: HTMLCanvasElement;
  readonly context: CanvasRenderingContext2D;
  readonly texture: THREE.CanvasTexture;

  protected hitZones: CanvasHitZone[] = [];
  protected hoveredZoneId: string | null = null;
  protected pointerDown: CanvasPointer | null = null;
  protected suppressNextTrigger = false;

  constructor(
    logicalWidth: number,
    logicalHeight: number,
    options: xb.ViewOptions = {},
    pixelRatio = 2,
  ) {
    const canvas = document.createElement('canvas');
    canvas.width = Math.round(logicalWidth * pixelRatio);
    canvas.height = Math.round(logicalHeight * pixelRatio);
    const context = canvas.getContext('2d', {alpha: true});
    if (!context) throw new Error('A 2D canvas is required for the spatial UI.');
    context.scale(pixelRatio, pixelRatio);

    const texture = new THREE.CanvasTexture(canvas);
    texture.colorSpace = THREE.SRGBColorSpace;
    texture.minFilter = THREE.LinearFilter;
    texture.magFilter = THREE.LinearFilter;
    texture.generateMipmaps = false;

    const material = new THREE.MeshBasicMaterial({
      map: texture,
      transparent: true,
      depthWrite: false,
      side: THREE.FrontSide,
      toneMapped: false,
    });
    super(
      {
        width: 1,
        height: 1,
        selectable: true,
        draggingMode: xb.DragMode.DO_NOT_DRAG,
        ...options,
      },
      new THREE.PlaneGeometry(1, 1),
      material,
    );

    this.logicalWidth = logicalWidth;
    this.logicalHeight = logicalHeight;
    this.canvas = canvas;
    this.context = context;
    this.texture = texture;
    this.name = options.name ?? 'Canvas UI';

    Object.defineProperty(this, 'draggingMode', {
      get: () => {
        for (let id = 0; id < this.ux.hovered.length; id++) {
          if (this.ux.hovered[id]) {
            const pointer = this.pointerForId(id);
            if (pointer && this.zoneAt(pointer.x, pointer.y)) {
              return xb.DragMode.DO_NOT_DRAG;
            }
          }
        }
        return xb.DragMode.TRANSLATING;
      },
      set: () => {},
      configurable: true,
      enumerable: true,
    });
  }

  protected abstract draw(): void;

  protected redraw() {
    this.hitZones = [];
    this.context.clearRect(0, 0, this.logicalWidth, this.logicalHeight);
    this.draw();
    this.texture.needsUpdate = true;
  }

  protected addHitZone(zone: CanvasHitZone) {
    this.hitZones.push(zone);
  }

  protected isHovered(id: string): boolean {
    return this.hoveredZoneId === id;
  }

  protected zoneAt(x: number, y: number): CanvasHitZone | undefined {
    for (let index = this.hitZones.length - 1; index >= 0; index--) {
      const zone = this.hitZones[index];
      if (
        !zone.disabled &&
        x >= zone.x &&
        x <= zone.x + zone.width &&
        y >= zone.y &&
        y <= zone.y + zone.height
      ) {
        return zone;
      }
    }
    return undefined;
  }

  protected pointerForController(
    controller: THREE.Object3D,
  ): CanvasPointer | null {
    const id = controller.userData.id as number | undefined;
    if (id === undefined) return null;
    const uv = this.ux.uvs[id];
    if (!uv) return null;
    return {
      id,
      x: THREE.MathUtils.clamp(uv.x, 0, 1) * this.logicalWidth,
      y: (1 - THREE.MathUtils.clamp(uv.y, 0, 1)) * this.logicalHeight,
      controller,
    };
  }

  protected pointerForId(id: number): CanvasPointer | null {
    const uv = this.ux.uvs[id];
    if (!uv) return null;
    return {
      id,
      x: THREE.MathUtils.clamp(uv.x, 0, 1) * this.logicalWidth,
      y: (1 - THREE.MathUtils.clamp(uv.y, 0, 1)) * this.logicalHeight,
      controller: new THREE.Object3D(),
    };
  }

  protected onCanvasPointerDown(_pointer: CanvasPointer): boolean {
    return false;
  }

  protected onCanvasPointerMove(_pointer: CanvasPointer): boolean {
    return false;
  }

  protected onCanvasPointerUp(_pointer: CanvasPointer | null): boolean {
    return false;
  }

  override onObjectSelectStart(event: xb.SelectEvent): boolean {
    const pointer = this.pointerForController(event.target);
    this.pointerDown = pointer;
    this.suppressNextTrigger = pointer
      ? this.onCanvasPointerDown(pointer)
      : false;
    // Always consume input on the canvas so a button or scrub never drags the
    // SpatialPanel behind it. The panel's visible edge remains a move handle.
    return true;
  }

  override onObjectSelectEnd(event: xb.SelectEvent): boolean {
    const pointer = this.pointerForController(event.target);
    this.suppressNextTrigger =
      this.onCanvasPointerUp(pointer) || this.suppressNextTrigger;
    this.pointerDown = null;
    return true;
  }

  override onTriggered(id: number) {
    if (this.suppressNextTrigger) {
      this.suppressNextTrigger = false;
      return;
    }
    const pointer = this.pointerForId(id);
    if (!pointer) return;
    this.zoneAt(pointer.x, pointer.y)?.action();
  }

  override updateLayout() {
    super.updateLayout();
    // View lays out the container; the plane itself must expand from a square
    // to the container's actual aspect ratio.
    this.mesh?.scale.set(this.rangeX, this.rangeY, 1);
  }

  override update() {
    let hovered: string | null = null;
    const [firstController] = this.ux.getPrimaryTwoControllerIds();
    if (firstController !== null) {
      const pointer = this.pointerForId(firstController);
      if (pointer) hovered = this.zoneAt(pointer.x, pointer.y)?.id ?? null;
    }

    let shouldRedraw = hovered !== this.hoveredZoneId;
    this.hoveredZoneId = hovered;
    if (this.pointerDown) {
      const pointer = this.pointerForController(this.pointerDown.controller);
      if (pointer && this.onCanvasPointerMove(pointer)) shouldRedraw = true;
    }
    if (shouldRedraw) this.redraw();
  }

  override dispose() {
    this.texture.dispose();
    this.mesh?.geometry.dispose();
    const material = this.mesh?.material;
    if (Array.isArray(material)) material.forEach((entry) => entry.dispose());
    else material?.dispose();
  }
}

export function roundedRect(
  context: CanvasRenderingContext2D,
  x: number,
  y: number,
  width: number,
  height: number,
  radius: number,
) {
  const r = Math.min(radius, width / 2, height / 2);
  context.beginPath();
  context.roundRect(x, y, width, height, r);
}

export function fillRoundedRect(
  context: CanvasRenderingContext2D,
  color: string | CanvasGradient,
  x: number,
  y: number,
  width: number,
  height: number,
  radius: number,
) {
  roundedRect(context, x, y, width, height, radius);
  context.fillStyle = color;
  context.fill();
}

export function drawCoverImage(
  context: CanvasRenderingContext2D,
  image: CanvasImageSource,
  x: number,
  y: number,
  width: number,
  height: number,
) {
  const source = image as CanvasImageSource & {
    naturalWidth?: number;
    naturalHeight?: number;
    videoWidth?: number;
    videoHeight?: number;
    width?: number;
    height?: number;
  };
  const sourceWidth =
    source.naturalWidth ?? source.videoWidth ?? source.width ?? width;
  const sourceHeight =
    source.naturalHeight ?? source.videoHeight ?? source.height ?? height;
  const scale = Math.max(width / sourceWidth, height / sourceHeight);
  const drawWidth = sourceWidth * scale;
  const drawHeight = sourceHeight * scale;
  context.drawImage(
    image,
    x + (width - drawWidth) / 2,
    y + (height - drawHeight) / 2,
    drawWidth,
    drawHeight,
  );
}

export function ellipsize(
  context: CanvasRenderingContext2D,
  value: string,
  maxWidth: number,
): string {
  if (context.measureText(value).width <= maxWidth) return value;
  let result = value;
  while (result.length > 1 && context.measureText(`${result}…`).width > maxWidth) {
    result = result.slice(0, -1);
  }
  return `${result}…`;
}

export function formatClock(seconds: number): string {
  if (!Number.isFinite(seconds) || seconds < 0) return '0:00';
  const total = Math.floor(seconds);
  const hours = Math.floor(total / 3600);
  const minutes = Math.floor((total % 3600) / 60);
  const remaining = total % 60;
  return hours > 0
    ? `${hours}:${String(minutes).padStart(2, '0')}:${String(remaining).padStart(2, '0')}`
    : `${minutes}:${String(remaining).padStart(2, '0')}`;
}
