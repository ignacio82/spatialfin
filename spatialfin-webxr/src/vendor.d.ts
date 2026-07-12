declare module '@jellyfin/libass-wasm' {
  interface SubtitlesOctopusOptions {
    video: HTMLVideoElement;
    canvas: HTMLCanvasElement;
    workerUrl: string;
    fallbackFont: string;
  }

  export default class SubtitlesOctopus {
    constructor(options: SubtitlesOctopusOptions);
    dispose(): void;
  }
}
