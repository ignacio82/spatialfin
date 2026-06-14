// Shared fake catalog for the SpatialFin UI kits.
const SF_CATALOG = [
  {
    id: "bbb", title: "Big Buck Bunny", kind: "Movie", year: "2008", runtime: "9 min",
    rating: "G", stars: "8.0", genres: "Animation, Comedy, Family", progress: 79,
    poster: "../../assets/media/poster-bbb.png", backdrop: "../../assets/media/poster-bbb.png",
    overview: "A giant rabbit with a heart bigger than himself takes revenge on three rodents who tormented him and harassed the woodland creatures.",
    tags: ["4K", "HDR", "5.1"], downloaded: true,
  },
  {
    id: "elephants", title: "Elephants Dream", kind: "Movie", year: "2006", runtime: "10 min",
    rating: "NR", stars: "5.8", genres: "Animation, Science Fiction", progress: 26,
    poster: "../../assets/media/poster-elephants.png", backdrop: "../../assets/media/backdrop-elephants.png",
    overview: "Two strange characters explore a capricious and seemingly infinite machine. The elder, Proog, acts as a tour-guide and protector to the increasingly skeptical Emo.",
    tags: ["4K", "5.1"], downloaded: false,
  },
  {
    id: "spring", title: "Spring", kind: "Movie", year: "2019", runtime: "8 min",
    rating: "NR", stars: "7.9", genres: "Fantasy, Drama", progress: 0,
    poster: "../../assets/media/poster-spring.png", backdrop: "../../assets/media/poster-spring.png",
    overview: "A shepherd girl and her dog face ancient spirits in order to continue the cycle of life and bring the world a fresh new spring.",
    tags: ["4K", "HDR"], downloaded: false,
  },
  {
    id: "sintel", title: "Sintel", kind: "Movie", year: "2010", runtime: "15 min",
    rating: "PG", stars: "7.5", genres: "Animation, Adventure", progress: 0,
    poster: "../../assets/media/poster-sintel.png", backdrop: "../../assets/media/poster-sintel.png",
    overview: "A lonely young woman, Sintel, helps and befriends a dragon she names Scales. When he is kidnapped, she embarks on a dangerous quest to find her lost friend.",
    tags: ["4K"], downloaded: false,
  },
  {
    id: "tears", title: "Tears of Steel", kind: "Movie", year: "2012", runtime: "12 min",
    rating: "PG", stars: "6.9", genres: "Science Fiction, Action", progress: 0,
    poster: "../../assets/media/poster-tears.png", backdrop: "../../assets/media/poster-tears.png",
    overview: "In a dystopian Amsterdam, a group of warriors and scientists gather to stop an army of robots — but their success hinges on a man's painful memories.",
    tags: ["4K", "HDR"], downloaded: false,
  },
  {
    id: "agent327", title: "Agent 327", kind: "Movie", year: "2017", runtime: "4 min",
    rating: "PG", stars: "7.6", genres: "Action, Comedy", progress: 0,
    poster: "../../assets/media/poster-agent327.png", backdrop: "../../assets/media/poster-agent327.png",
    overview: "Secret agent Hendrik IJzerbroot is ambushed in a barbershop. The clues lead toward a global conspiracy — and a very sharp razor.",
    tags: ["4K", "5.1"], downloaded: true,
  },
  {
    id: "coffeerun", title: "Coffee Run", kind: "Movie", year: "2020", runtime: "4 min",
    rating: "NR", stars: "7.4", genres: "Drama, Animation", progress: 0,
    poster: "../../assets/media/poster-coffeerun.png", backdrop: "../../assets/media/poster-coffeerun.png",
    overview: "Fueled by caffeine, a young woman races through the highs and lows of a life lived one cup at a time.",
    tags: ["4K"], downloaded: false,
  },
  {
    id: "charge", title: "Charge", kind: "Movie", year: "2022", runtime: "5 min",
    rating: "PG", stars: "7.1", genres: "Science Fiction", progress: 0,
    poster: "../../assets/media/poster-charge.png", backdrop: "../../assets/media/poster-charge.png",
    overview: "A lone soldier guards humanity's last power source through a long, dangerous night.",
    tags: ["4K", "HDR"], downloaded: false,
  },
  {
    id: "glasshalf", title: "Glass Half", kind: "Movie", year: "2015", runtime: "3 min",
    rating: "G", stars: "6.8", genres: "Comedy", progress: 0,
    poster: "../../assets/media/poster-glasshalf.png", backdrop: "../../assets/media/poster-glasshalf.png",
    overview: "Two art critics argue over a painting until the disagreement spirals gloriously out of hand.",
    tags: ["HD"], downloaded: false,
  },
  {
    id: "wingit", title: "Wing It!", kind: "Movie", year: "2023", runtime: "3 min",
    rating: "G", stars: "7.0", genres: "Adventure, Comedy", progress: 0,
    poster: "../../assets/media/poster-wing.png", backdrop: "../../assets/media/poster-wing.png",
    overview: "A would-be pilot pieces together a ramshackle flying machine and takes a leap of faith off the cliff's edge.",
    tags: ["4K"], downloaded: false,
  },
];
const SF_FEATURED = {
  id: "sprite", title: "Sprite Fright", kind: "Movie", year: "2021", runtime: "10 min",
  rating: "PG", stars: "8.3", genres: "Animation, Comedy, Horror",
  backdrop: "../../assets/media/backdrop-sprite.png",
  poster: "../../assets/media/backdrop-sprite.png",
  overview: "When a rowdy group of teenagers visit the countryside, an encounter with the local wildlife quickly turns into a hilarious fight for survival.",
  tags: ["4K", "HDR", "Atmos"],
};
window.SF_CATALOG = SF_CATALOG;
window.SF_FEATURED = SF_FEATURED;

// --- Cast / "Play on" targets ---------------------------------------------
// A mix of video-capable sinks (TVs, this phone) and audio-only sinks
// (AVRs, speakers, headphones). The split between `video` and `audio`
// capability is what powers separate-output ("split") casting.
window.SF_CAST_DEVICES = [
  { id: "this",       name: "This phone",        type: "phone",      video: true,  audio: true,  hint: "Beam · local" },
  { id: "lr-tv",      name: "Living Room TV",    type: "tv",         video: true,  audio: true,  hint: "Chromecast · 4K HDR" },
  { id: "bedroom",    name: "Bedroom Shield",    type: "tv",         video: true,  audio: true,  hint: "Android TV" },
  { id: "office",     name: "Office Display",    type: "tv",         video: true,  audio: true,  hint: "Chromecast" },
  { id: "avr",        name: "Living Room AVR",   type: "speaker",    video: false, audio: true,  hint: "Atmos 7.1.4" },
  { id: "kitchen",    name: "Kitchen Sonos",     type: "speaker",    video: false, audio: true,  hint: "Sonos" },
  { id: "buds",       name: "Pixel Buds Pro",    type: "headphones", video: false, audio: true,  hint: "Bluetooth" },
];

// --- SyncPlay (watch-together) --------------------------------------------
window.SF_SYNCPLAY_GROUPS = [
  { id: "movie-night", name: "Movie Night",  members: 3, ping: 42 },
  { id: "family-room", name: "Family Room",  members: 2, ping: 28 },
];
window.SF_SYNCPLAY_PEOPLE = [
  { id: "u1", name: "Ignacio", you: true,  color: "#1F4876" },
  { id: "u2", name: "Mara",    color: "#543F5E" },
  { id: "u3", name: "Devin",   color: "#3C4758" },
];

// --- Universal-plugin Sources (phone) -------------------------------------
// Mirrors the TV "Sources" rows plus an on-device "Local files" source.
window.SF_BEAM_SOURCES = [
  { id: "yt",      name: "YouTube",       pluginId: "youtube",  icon: "youtube",   tint: "#C5402B",
    items: SF_CATALOG.slice(0, 6).map((m, i) => ({ id: "yt-" + m.id, title: m.title, image: m.poster,
      subtitle: ["1.2M views", "428K views", "Featured", "Trending", "812K views", "New"][i % 6] })) },
  { id: "podcasts", name: "Podcasts",      pluginId: "podcasts", icon: "podcast",   tint: "#6E4FA3",
    items: SF_CATALOG.slice(3, 9).map((m, i) => ({ id: "pd-" + m.id, title: m.title, image: m.poster,
      subtitle: ["Today · 38 min", "Mon · 1h 02m", "Episode 14", "New episode", "Episode 9", "Yesterday"][i % 6] })) },
  { id: "webdav",   name: "NAS / WebDAV",  pluginId: "webdav",   icon: "hard-drive", tint: "#2C6E5B",
    items: SF_CATALOG.slice(2, 8).map((m) => ({ id: "nas-" + m.id, title: m.title, image: m.poster,
      subtitle: "MKV · 1080p" })) },
  { id: "local",    name: "Local files",  pluginId: "device",   icon: "smartphone", tint: "#9A6A2C",
    items: SF_CATALOG.filter((m) => m.downloaded).map((m) => ({ id: "loc-" + m.id, title: m.title, image: m.poster,
      subtitle: "On this device" })) },
];

// --- Jellyfin servers (switcher) ------------------------------------------
window.SF_SERVERS = [
  { id: "home",  name: "Home Jellyfin", address: "http://192.168.1.42:8096" },
  { id: "cabin", name: "Cabin",         address: "https://cabin.example.com" },
  { id: "demo",  name: "Demo Server",   address: "https://demo.jellyfin.org" },
];
window.SF_CURRENT_SERVER = "home";

// --- Jellyfin users (profile switcher) ------------------------------------
// `avatar` is the Jellyfin profile-image URL when the user has one set;
// otherwise a coloured letter avatar is shown (Jellyfin's default).
window.SF_USERS = [
  { id: "ignacio", name: "Ignacio M.", role: "Administrator", initials: "IM",
    color: "var(--tertiary-container)", textColor: "var(--on-tertiary-container)" },
  { id: "mara",    name: "Mara",       role: "User",          initials: "MA",
    color: "#543F5E", avatar: "../../assets/music/album-mauve.png" },
  { id: "devin",   name: "Devin",      role: "User",          initials: "DV",
    color: "#1F4876", avatar: "../../assets/music/album-blue.png" },
  { id: "kids",    name: "Kids",       role: "Restricted",    initials: "K",
    color: "#2C6E5B" },
];
window.SF_CURRENT_USER = "ignacio";
