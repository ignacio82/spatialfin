export interface MAServerInfo {
  server_id: string;
  server_version: string;
  schema_version: number;
  min_schema_version?: number;
  base_url?: string;
}

export type MAMediaType = 'artist' | 'album' | 'track' | 'playlist' | 'radio' | 'folder' | 'unknown';

export interface MAMediaItem {
  item_id: string;
  provider: string;
  name: string;
  media_type: MAMediaType;
  uri?: string;
  image_url?: string;
  image?: string;
  version?: string;
  duration?: number;
  artists?: MAMediaItem[];
  album?: MAMediaItem;
  metadata?: {
    description?: string;
    genres?: string[];
    explicit?: boolean;
    copyright?: string;
    lyrics?: string;
    performers?: string[];
    label?: string;
  };
  provider_mappings?: Array<{
    provider_domain: string;
    provider_instance: string;
    item_id: string;
    available: boolean;
    url?: string;
  }>;
  favorite?: boolean;
}

export interface MAArtist extends MAMediaItem {
  media_type: 'artist';
}

export interface MAAlbum extends MAMediaItem {
  media_type: 'album';
  year?: number;
  album_type?: string;
}

export interface MATrack extends MAMediaItem {
  media_type: 'track';
  disc_number?: number;
  track_number?: number;
}

export interface MAPlaylist extends MAMediaItem {
  media_type: 'playlist';
  owner?: string;
  is_editable?: boolean;
}

export interface MARadio extends MAMediaItem {
  media_type: 'radio';
}

export interface MAPlayer {
  player_id: string;
  name: string;
  type: string;
  powered: boolean;
  state: 'playing' | 'paused' | 'idle' | 'off';
  volume_level: number;
  is_muted: boolean;
  available: boolean;
  current_item?: MAMediaItem | null;
  current_media?: MAMediaItem | null;
  elapsed_time?: number;
  elapsed_time_last_updated?: number;
  group_members?: string[];
  synced_to?: string | null;
  active_group?: string | null;
  can_group_with?: string[];
  supported_features?: string[];
  display_name?: string;
}

export interface MAQueueItem {
  queue_item_id: string;
  queue_id: string;
  name: string;
  duration?: number;
  media_item?: MAMediaItem;
  image_url?: string;
  streamdetails?: {
    provider: string;
    item_id: string;
    content_type: string;
    sample_rate?: number;
    bit_depth?: number;
    channels?: number;
    bit_rate?: number;
  };
}

export interface MAQueue {
  queue_id: string;
  active: boolean;
  display_name: string;
  items_count: number;
  current_index?: number;
  current_item?: MAQueueItem | null;
  elapsed_time?: number;
  elapsed_time_last_updated?: number;
  state: 'playing' | 'paused' | 'idle';
  shuffle_enabled: boolean;
  repeat_mode: 'off' | 'one' | 'all';
}

export interface MASearchResults {
  artists?: MAArtist[];
  albums?: MAAlbum[];
  tracks?: MATrack[];
  playlists?: MAPlaylist[];
  radio?: MARadio[];
}

export interface MAConfig {
  serverUrl: string;
  token?: string;
  sendspinEnabled: boolean;
  sendspinName: string;
  preferredPlayerId?: string;
}
