// SpatialFin TV — catalog: shows (seasons/episodes), movies, featured.
const SF_TV_SHOWS = [
  {
    id: "cosmos", title: "Cosmos Laundromat", kind: "Series", year: "2015–", stars: "8.4",
    genres: "Animation, Comedy, Fantasy", rating: "TV-PG", tags: ["4K", "HDR", "5.1"],
    poster: "../../assets/tv/poster-cosmos.png", backdrop: "../../assets/tv/backdrop-cosmos.png",
    overview: "On a desolate island, a suicidal sheep named Franck meets his fairy godmother, who hands him the salvation of his dreams — at the price of his future.",
    seasons: {
      1: [
        { n: 1, title: "Emo Goes Wild", still: "../../assets/tv/ep-cosmos-1.png", runtime: "12 min", progress: 100, synopsis: "Franck, a suicidal sheep, is offered a way out by a peculiar travelling salesman named Victor." },
        { n: 2, title: "The Many Lives of Franck", still: "../../assets/tv/ep-cosmos-2.png", runtime: "10 min", progress: 45, synopsis: "Franck wakes up in a strange new dimension and must choose between infinite lives." },
        { n: 3, title: "Wisdom of the Cosmos", still: "../../assets/tv/ep-cosmos-3.png", runtime: "11 min", progress: 0, synopsis: "Victor's machine begins to fail as Franck questions the cost of a perfect life." },
      ],
      2: [
        { n: 1, title: "A New Cycle", still: "../../assets/tv/ep-cosmos-2.png", runtime: "13 min", progress: 0, synopsis: "The salesman's empire faces its reckoning across a thousand parallel islands." },
        { n: 2, title: "The Last Sale", still: "../../assets/tv/ep-cosmos-1.png", runtime: "12 min", progress: 0, synopsis: "Franck makes his final choice." },
      ],
    },
  },
  {
    id: "caminandes", title: "Caminandes", kind: "Series", year: "2013–2016", stars: "7.8",
    genres: "Animation, Family, Comedy", rating: "G", tags: ["4K"],
    poster: "../../assets/tv/poster-caminandes.png", backdrop: "../../assets/tv/backdrop-caminandes.png",
    overview: "Koro the llama just wants to get to the other side — but a determined road, a hungry winter, and a sly little penguin named Oti keep getting in the way.",
    seasons: {
      1: [
        { n: 1, title: "Llama Drama", still: "../../assets/tv/ep-cam-1.png", runtime: "1 min", progress: 100, synopsis: "Koro the llama faces his first great obstacle: a road." },
        { n: 2, title: "Gran Dillama", still: "../../assets/tv/ep-cam-2.png", runtime: "2 min", progress: 100, synopsis: "Hungry in the winter, Koro spots a patch of green grass — behind a fence." },
        { n: 3, title: "Llamigos", still: "../../assets/tv/ep-cam-3.png", runtime: "2 min", progress: 60, synopsis: "Koro and Oti the penguin battle over the last carrot in a frozen land." },
      ],
    },
  },
];

const SF_TV_MOVIES = (window.SF_CATALOG || []);
const SF_TV_FEATURED = {
  id: "cosmos", title: "Cosmos Laundromat", kind: "Series", year: "2015", stars: "8.4",
  runtime: "S1 · 3 episodes", rating: "TV-PG", genres: "Animation, Comedy, Fantasy",
  backdrop: "../../assets/tv/backdrop-cosmos.png", tags: ["4K", "HDR", "Atmos"],
  overview: "On a desolate island, a suicidal sheep named Franck meets his fairy godmother — who offers him the salvation of his dreams at the price of his future.",
  isShow: true,
};

window.SF_TV_SHOWS = SF_TV_SHOWS;
window.SF_TV_MOVIES = SF_TV_MOVIES;
window.SF_TV_FEATURED = SF_TV_FEATURED;

// Given the current show + episode, return the next episode in the season (or null).
window.SF_TV_NEXT_EP = function (show, ep) {
  if (!show || !ep) return null;
  for (const sk of Object.keys(show.seasons)) {
    const eps = show.seasons[sk];
    const i = eps.findIndex((e) => e.n === ep.n && e.title === ep.title);
    if (i >= 0 && i + 1 < eps.length) return { ep: eps[i + 1], season: sk };
  }
  return null;
};
// Continue-watching = landscape items (episodes + partially-watched movies)
window.SF_TV_CONTINUE = [
  { id: "cam-1-3", title: "Caminandes", subtitle: "S1 E3 · Llamigos", image: "../../assets/tv/ep-cam-3.png", progress: 60, show: "caminandes" },
  { id: "cosmos-1-2", title: "Cosmos Laundromat", subtitle: "S1 E2 · The Many Lives of Franck", image: "../../assets/tv/ep-cosmos-2.png", progress: 45, show: "cosmos" },
  { id: "bbb", title: "Big Buck Bunny", subtitle: "Movie · 79% watched", image: "../../assets/media/poster-bbb.png", progress: 79, movie: "bbb" },
  { id: "elephants", title: "Elephants Dream", subtitle: "Movie · 26% watched", image: "../../assets/tv/ep-cosmos-3.png", progress: 26, movie: "elephants" },
];

// Unified browse entries (shows + movies) for Library + Search.
window.SF_TV_ALL = [
  ...SF_TV_SHOWS.map((s) => ({ kind: "show", id: s.id, title: s.title, type: "Series", image: s.poster })),
  ...SF_TV_MOVIES.map((m) => ({ kind: "movie", id: m.id, title: m.title, type: "Movie", image: m.poster, progress: m.progress, downloaded: m.downloaded })),
];

// --- Music Assistant catalog -----------------------------------------------
window.SF_MA_CATALOG = [
  { id: "ma-1", title: "Midnight City", artist: "M83", album: "Hurry Up, We're Dreaming", duration: "4:03", artwork: "../../assets/music/album-cyan.png" },
  { id: "ma-2", title: "Borderline", artist: "Tame Impala", album: "The Slow Rush", duration: "3:58", artwork: "../../assets/music/album-amber.png" },
  { id: "ma-3", title: "Saturn", artist: "Sleeping at Last", album: "Atlas: Year One", duration: "4:48", artwork: "../../assets/music/album-blue.png" },
  { id: "ma-4", title: "Lost in the Light", artist: "Bahamas", album: "Barchords", duration: "3:43", artwork: "../../assets/music/album-warm.png" },
  { id: "ma-5", title: "Holocene", artist: "Bon Iver", album: "Bon Iver, Bon Iver", duration: "5:36", artwork: "../../assets/music/album-mint.png" },
  { id: "ma-6", title: "Re: Stacks", artist: "Bon Iver", album: "For Emma, Forever Ago", duration: "6:41", artwork: "../../assets/music/album-mauve.png" },
];
window.SF_MA_SHELF = window.SF_MA_CATALOG.slice(0, 6);

// SendSpin / Music Assistant players ----------------------------------------
window.SF_MA_PLAYERS = [
  { id: "p-tv", name: "Living Room TV", provider: "AndroidTV", isPlaying: true, supportsGrouping: true, canGroupWith: ["snapcast", "p-kitchen", "p-office"] },
  { id: "p-kitchen", name: "Kitchen Speaker", provider: "snapcast", supportsGrouping: true, canGroupWith: ["snapcast", "p-tv"] },
  { id: "p-office", name: "Office Display", provider: "Chromecast", supportsGrouping: true, canGroupWith: ["chromecast"] },
  { id: "p-bedroom", name: "Bedroom Sonos", provider: "Sonos", supportsGrouping: false, canGroupWith: [] },
];

// Servers (Jellyfin) --------------------------------------------------------
window.SF_SERVERS = [
  { id: "home", name: "Home Jellyfin", address: "http://192.168.1.42:8096" },
  { id: "cabin", name: "Cabin", address: "https://cabin.example.com" },
  { id: "demo", name: "Demo Server", address: "https://demo.jellyfin.org" },
];
window.SF_CURRENT_SERVER = "home";

// Universal-plugin SOURCES rows (e.g. YouTube, podcast, web-video plugins).
// Each row has a pluginId + a See-all target. Items reuse our poster art.
window.SF_TV_SOURCES = [
  { id: "src-yt", name: "From YouTube", pluginId: "youtube", items: SF_TV_MOVIES.slice(0, 6).map((m, i) => ({
      id: "yt-" + m.id, title: m.title.toUpperCase().slice(0, 24), image: m.poster, subtitle: ["1.2M views", "428k views", "Featured", "Trending", "812k views", "New"][i % 6] })) },
  { id: "src-podcast", name: "Recent Podcasts", pluginId: "podcasts", items: SF_TV_SHOWS.flatMap((s) =>
      Object.values(s.seasons).flat().slice(0, 3).map((e) => ({
        id: "pd-" + s.id + "-" + e.n, title: s.title + " · Ep " + e.n, image: e.still, subtitle: e.runtime + " · Today" })))
  },
];
