const fs = require('fs');
let code = fs.readFileSync('src/api.ts', 'utf8');

const fetchItemCode = `
export async function fetchItem(itemId: string, signal?: AbortSignal): Promise<JellyfinItem> {
  return await requestJson<JellyfinItem>(\`/Users/\${requireUserId()}/Items/\${itemId}\`, {
    signal,
  });
}

export function extractMediaPills(item: JellyfinItem): string[] {
  const pills: string[] = [];
  const source = item.MediaSources?.[0];
  if (source) {
    const videoStream = source.MediaStreams?.find(s => s.Type === 'Video');
    if (videoStream) {
      if (videoStream.Width && videoStream.Width >= 3800) pills.push('4K');
      else if (videoStream.Width && videoStream.Width >= 1900) pills.push('1080p');
      else if (videoStream.Width && videoStream.Width >= 1200) pills.push('720p');
      if (videoStream.Codec) pills.push(videoStream.Codec.toUpperCase());
    } else if (source.VideoCodec) {
      pills.push(source.VideoCodec.toUpperCase());
    }
    const audioStream = source.MediaStreams?.find(s => s.Type === 'Audio');
    if (audioStream && audioStream.ChannelLayout) {
      pills.push(audioStream.ChannelLayout.toUpperCase());
    }
  }
  return pills;
}
`;

code = code.replace(
  "export async function fetchItems(parentId: string): Promise<JellyfinItem[]> {",
  fetchItemCode + "\nexport async function fetchItems(parentId: string): Promise<JellyfinItem[]> {"
);

fs.writeFileSync('src/api.ts', code);
