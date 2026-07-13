declare module '@jellyfin/libass-wasm' {
  interface SubtitlesOctopusOptions {
    video?: HTMLVideoElement;
    canvas?: HTMLCanvasElement;
    subUrl?: string;
    subContent?: string;
    fonts?: string[];
    workerUrl: string;
    fallbackFont: string;
    renderMode?: 'js-blend' | 'wasm-blend' | 'lossy';
    targetFps?: number;
    dropAllAnimations?: boolean;
    onReady?: () => void;
    onError?: (error: unknown) => void;
  }

  export default class SubtitlesOctopus {
    worker: Worker;
    constructor(options: SubtitlesOctopusOptions);
    setCurrentTime(seconds: number): void;
    setIsPaused(paused: boolean, seconds: number): void;
    setRate(rate: number): void;
    resize(width?: number, height?: number, top?: number, left?: number): void;
    dispose(): void;
  }
}
