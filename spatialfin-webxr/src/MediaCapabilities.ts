/**
 * Client media capabilities detector for SpatialFin WebXR.
 * Inspects runtime browser/headset capabilities for video codecs (HEVC, AV1, VP9),
 * HDR formats (HDR10, HLG, Dolby Vision), surround audio (5.1 / 7.1), and Dolby Atmos.
 */

export interface ClientMediaCapabilities {
  supportsHevc: boolean;
  supportsAv1: boolean;
  supportsVp9Hdr: boolean;
  supportsHdr10: boolean;
  supportsHlg: boolean;
  supportsDolbyVision: boolean;
  supportsEac3: boolean;
  supportsAc3: boolean;
  supportsOpusSurround: boolean;
  supportsFlacSurround: boolean;
  maxAudioChannels: number;
}

let cachedCapabilities: ClientMediaCapabilities | null = null;

export async function detectClientCapabilities(): Promise<ClientMediaCapabilities> {
  if (cachedCapabilities) {
    return cachedCapabilities;
  }

  // Create a silent fallback element for standard HTMLMediaElement.canPlayType tests
  const video = document.createElement('video');

  const supportsHevc =
    video.canPlayType('video/mp4; codecs="hvc1.1.6.L153.B0"').length > 0 ||
    video.canPlayType('video/mp4; codecs="hev1.1.6.L153.B0"').length > 0;

  const supportsAv1 =
    video.canPlayType('video/mp4; codecs="av01.0.08M.10"').length > 0 ||
    video.canPlayType('video/webm; codecs="av01.0.08M.10"').length > 0;

  const supportsVp9Hdr =
    video.canPlayType('video/webm; codecs="vp09.02.10.10.01.09.16.09.00"').length > 0 ||
    video.canPlayType('video/mp4; codecs="vp09.02.10.10.01.09.16.09.00"').length > 0;

  const supportsEac3 =
    video.canPlayType('audio/mp4; codecs="ec-3"').length > 0 ||
    video.canPlayType('audio/mp4; codecs="eac3"').length > 0;

  const supportsAc3 =
    video.canPlayType('audio/mp4; codecs="ac-3"').length > 0 ||
    video.canPlayType('audio/mp4; codecs="ac3"').length > 0;

  const supportsOpusSurround =
    video.canPlayType('audio/webm; codecs="opus"').length > 0 ||
    video.canPlayType('audio/mp4; codecs="opus"').length > 0;

  const supportsFlacSurround =
    video.canPlayType('audio/flac').length > 0 ||
    video.canPlayType('audio/mp4; codecs="flac"').length > 0;

  // Check MediaCapabilities API for HDR and 4K decoding performance
  let supportsHdr10 = false;
  let supportsHlg = false;
  let supportsDolbyVision = false;

  if ('mediaCapabilities' in navigator && navigator.mediaCapabilities?.decodingInfo) {
    try {
      if (supportsHevc) {
        const hdrInfo = await navigator.mediaCapabilities.decodingInfo({
          type: 'media-source',
          video: {
            contentType: 'video/mp4; codecs="hvc1.2.4.L153.B0"',
            width: 3840,
            height: 2160,
            bitrate: 25000000,
            framerate: 60,
          },
        });
        supportsHdr10 = hdrInfo.supported;
      }
    } catch {
      // Fall back gracefully if mediaCapabilities fails or throws
      supportsHdr10 = supportsHevc;
    }
  } else {
    supportsHdr10 = supportsHevc || supportsAv1 || supportsVp9Hdr;
  }

  // HLG is supported on systems supporting HDR10 or HEVC 10-bit
  supportsHlg = supportsHdr10 || supportsHevc;

  // Dolby Vision Profile 5/8 check (Safari / Apple VisionOS / certified screens)
  supportsDolbyVision =
    video.canPlayType('video/mp4; codecs="dvh1.05.01"').length > 0 ||
    video.canPlayType('video/mp4; codecs="dvhe.05.01"').length > 0 ||
    video.canPlayType('video/mp4; codecs="dvh1.08.01"').length > 0;

  // Maximum channel count determination:
  // 8 channels for 7.1 / Atmos, 6 for 5.1 surround sound.
  // Standard web engines handle multi-channel AAC, Opus, FLAC, AC3, EAC3 up to 8 channels.
  const maxAudioChannels = supportsEac3 || supportsAc3 || supportsOpusSurround || supportsFlacSurround ? 8 : 6;

  cachedCapabilities = {
    supportsHevc,
    supportsAv1,
    supportsVp9Hdr,
    supportsHdr10,
    supportsHlg,
    supportsDolbyVision,
    supportsEac3,
    supportsAc3,
    supportsOpusSurround,
    supportsFlacSurround,
    maxAudioChannels,
  };

  return cachedCapabilities;
}
