/*
 * Myspoti player bridge.
 *
 * Runs inside the official Web Player page and exposes its player through one
 * small, host-agnostic contract. The same file is loaded by the Android WebView
 * and by desktop Chrome over CDP, so every command can be tested on a PC.
 *
 * Host -> page: window.Myspoti.call(id, method, args)  (also returns a Promise)
 * Page -> host: one JSON string per message, sent to the first host channel found:
 *   - window.MyspotiHost.post(json)   Android @JavascriptInterface
 *   - window.__mspHostPost(json)      CDP Runtime.addBinding
 * Messages: {type: "ready"} | {type: "state", state} | {type: "queue", queue}
 *         | {type: "result", id, ok, value | error} | {type: "error", error}
 *
 * The bridge only reads the page's own service registry; it patches nothing.
 */
(() => {
  // Injected again (each page load event): say ready again instead of staying silent.
  if (window.Myspoti && window.Myspoti.version) {
    if (window.Myspoti.announce) window.Myspoti.announce();
    return;
  }

  const VERSION = 1;
  const READY_TIMEOUT_MS = 60000;

  const post = msg => {
    const json = JSON.stringify(msg);
    try {
      if (window.MyspotiHost && window.MyspotiHost.post) return window.MyspotiHost.post(json);
      if (typeof window.__mspHostPost === 'function') return window.__mspHostPost(json);
    } catch (e) { /* host gone: nothing to report to */ }
  };

  // --- service lookup -------------------------------------------------------

  const findPlatform = () => {
    const host = document.getElementById('main') || document.body;
    const roots = [host, ...host.querySelectorAll('*')];
    for (const el of roots) {
      const key = Object.keys(el).find(k => k.startsWith('__reactContainer$'));
      if (!key) continue;
      const stack = [el[key]];
      while (stack.length) {
        const f = stack.pop();
        const v = f.memoizedProps && f.memoizedProps.value;
        if (v && typeof v === 'object' && typeof v.getRegistry === 'function') return v;
        if (f.sibling) stack.push(f.sibling);
        if (f.child) stack.push(f.child);
      }
    }
    return null;
  };

  let services = null;
  const resolveServices = () => {
    const platform = findPlatform();
    if (!platform) return null;
    const registry = platform.getRegistry();
    const byName = {};
    for (const key of registry._map.keys()) {
      if (key && key.description) byName[key.description] = key;
    }
    const get = name => {
      if (!byName[name]) throw new Error(`service ${name} not found`);
      return registry.resolve(byName[name]);
    };
    const player = get('PlayerAPI');
    if (!player || typeof player.getState !== 'function') return null;
    return {
      platform,
      player,
      playback: get('PlaybackAPI'),
      library: get('LibraryAPI'),
      token: get('TokenProvider'),
      graphql: platform.getGraphQLLoader(),
      byName,
      resolve: name => get(name),
    };
  };

  // --- state mapping --------------------------------------------------------

  const image = images => {
    if (!images || !images.length) return null;
    return [...images].sort((a, b) => (b.width || 0) - (a.width || 0))[0].url;
  };

  // Ads come in the same queue as tracks, with `type: "ad"` and a title instead of a name.
  const kind = item => item.type === 'ad' || /^spotify:ad:/.test(item.uri) ? 'ad'
    : /^spotify:episode:/.test(item.uri) ? 'episode' : 'track';

  const track = item => item && {
    kind: kind(item),
    uri: item.uri,
    uid: item.uid || null,
    name: item.name || item.title || '',
    advertiser: item.advertiser || null,
    durationMs: item.duration ? item.duration.milliseconds : null,
    artists: (item.artists || []).map(a => ({ uri: a.uri, name: a.name })),
    album: item.album ? { uri: item.album.uri, name: item.album.name } : null,
    image: image(item.images || (item.album && item.album.images)),
    explicit: !!item.isExplicit,
    mediaType: item.mediaType || 'audio',
    provider: item.provider || null,
  };

  const lastReal = items => (items || []).filter(i => kind(i) !== 'ad');
  const mapState = s => s && {
    track: track(s.item),
    contextUri: s.context ? s.context.uri : null,
    contextName: s.context && s.context.metadata ? s.context.metadata.context_description || null : null,
    previous: track(lastReal(s.previousItems).slice(-1)[0]),
    next: track(lastReal(s.nextItems)[0]),
    isPaused: !!s.isPaused,
    isBuffering: !!s.isBuffering,
    positionMs: s.positionAsOfTimestamp,
    timestamp: s.timestamp,
    durationMs: s.duration,
    shuffle: !!s.shuffle,
    repeat: s.repeat, // 0 off, 1 context, 2 track
    speed: s.speed,
    restrictions: s.restrictions || {},
    playbackId: s.playbackId || null,
  };

  const mapQueue = q => q && {
    current: track(q.current),
    queued: (q.queued || []).map(track),
    nextUp: (q.nextUp || []).map(track),
  };

  // --- serialization --------------------------------------------------------

  const methodsOf = obj => {
    const names = new Set();
    for (let p = obj; p && p !== Object.prototype; p = Object.getPrototypeOf(p)) {
      for (const n of Object.getOwnPropertyNames(p)) {
        if (n === 'constructor' || n.startsWith('_')) continue;
        try { if (typeof p[n] === 'function') names.add(n); } catch (e) { /* getter threw */ }
      }
    }
    return [...names].sort();
  };

  // Plain JSON copy of whatever a service returns: drops functions, breaks cycles,
  // turns Maps/Sets into objects/arrays and BigInts into strings.
  const toJson = (value, depth = 0, seen = new WeakSet()) => {
    if (value === null || value === undefined) return null;
    const t = typeof value;
    if (t === 'bigint') return value.toString();
    if (t === 'function' || t === 'symbol') return undefined;
    if (t !== 'object') return value;
    if (depth > 12 || seen.has(value)) return null;
    seen.add(value);
    if (value instanceof Date) return value.toISOString();
    if (value instanceof Map) value = Object.fromEntries(value);
    if (value instanceof Set) value = [...value];
    if (ArrayBuffer.isView(value)) return Array.from(value);
    if (Array.isArray(value)) return value.map(v => { const j = toJson(v, depth + 1, seen); return j === undefined ? null : j; });
    const out = {};
    for (const k of Object.keys(value)) {
      if (k.startsWith('_')) continue;
      const j = toJson(value[k], depth + 1, seen);
      if (j !== undefined) out[k] = j;
    }
    return out;
  };

  // --- commands -------------------------------------------------------------

  const need = () => {
    if (!services) throw new Error('not ready');
    return services;
  };

  const commands = {
    'bridge.version': () => VERSION,

    'player.state': () => mapState(need().player.getState()),
    'player.queue': () => mapQueue(need().player.getQueue()),
    // {contextUri, trackUri?, trackUid?, index?, positionMs?}
    'player.play': a => {
      const skipTo = {};
      if (a.trackUri) skipTo.uri = a.trackUri;
      if (a.trackUid) skipTo.uid = a.trackUid;
      if (Number.isInteger(a.index)) skipTo.index = a.index;
      const extra = {};
      if (Object.keys(skipTo).length) extra.skipTo = skipTo;
      if (a.positionMs) extra.seekTo = a.positionMs;
      // A given order (sorted playlist): the context plays as one page of those songs.
      const context = Array.isArray(a.order) && a.order.length
        ? { uri: a.contextUri, url: 'context://' + a.contextUri + '?order=' + Date.now(), pages: [{ items: a.order.map(uri => ({ uri })) }] }
        : { uri: a.contextUri };
      return need().player.play(context, {}, extra);
    },
    'player.pause': () => need().player.pause(),
    'player.resume': () => need().player.resume(),
    'player.seek': a => need().player.seekTo(a.positionMs),
    'player.next': () => need().player.skipToNext(),
    'player.previous': () => need().player.skipToPrevious(),
    'player.setShuffle': a => need().player.setShuffle(!!a.on),
    'player.setRepeat': a => need().player.setRepeat(a.mode),
    'player.setSpeed': a => need().player.setSpeed(a.speed),
    // Streaming quality, as the desktop app's setting: "auto", "low", "normal", "high",
    // "very_high". The page's own player (PlayerSDK.harmony) picks the files with it.
    'player.setAudioQuality': async a => {
      const sdk = need().resolve('PlayerSDK');
      const harmony = sdk && sdk.harmony;
      // The page's own streamer first: the public entry refuses while Spotify Connect
      // calls another device the active one ("remote player"), yet this page plays.
      const target = [harmony && harmony._streamer, harmony]
        .find(t => t && typeof t.setMediaPlaybackQuality === 'function');
      if (!target) throw new Error('no audio quality setting');
      return !!(await target.setMediaPlaybackQuality({ audio: a.level }));
    },
    'queue.add': a => need().player.addToQueue(a.uris.map(uri => ({ uri }))),
    'queue.remove': a => need().player.removeFromQueue(a.items),
    'queue.clear': () => need().player.clearQueue(),
    // Jumps to a queued or upcoming item.
    'queue.skipTo': a => need().player.skipToNext({ uri: a.uri, uid: a.uid }),
    // {uris, before: {uri, uid}} : "play next" puts the tracks before the first queued item.
    'queue.insert': a => need().player.insertIntoQueue(a.uris.map(uri => ({ uri })), { before: a.before }),
    // {items: [{uri, uid}], before | after: {uri, uid}}
    'queue.reorder': a => need().player.reorderQueue(a.items, a.after ? { after: a.after } : { before: a.before }),

    'volume.get': () => need().playback.getVolume(),
    'volume.set': a => need().playback.setVolume(a.level),

    'library.contains': a => need().library.contains(...a.uris),
    'library.add': a => need().library.add({ uris: a.uris }),
    'library.remove': a => need().library.remove({ uris: a.uris }),

    // Generic access to the page's services (PlaylistAPI, RootlistAPI, ShowAPI, ConnectAPI...).
    // Typed wrappers live on the host side; this keeps the bridge small.
    'service.list': () => {
      const out = {};
      for (const name of Object.keys(need().byName)) {
        let v;
        try { v = need().resolve(name); } catch (e) { continue; }
        if (v && typeof v === 'object') out[name] = methodsOf(v);
      }
      return out;
    },
    // {service, method, args[]}
    'service.call': async a => {
      const svc = need().resolve(a.service);
      if (!svc || typeof svc[a.method] !== 'function' || a.method.startsWith('_')) {
        throw new Error(`no method ${a.service}.${a.method}`);
      }
      return toJson(await svc[a.method](...(a.args || [])));
    },

    // Any persisted GraphQL operation of the Web Player, sent by the page's own loader
    // (its auth, client token and retries). {name, hash, variables}
    'gql.query': async a => {
      const def = { name: a.name, operation: a.operation || 'query', sha256Hash: a.hash, value: null };
      return need().graphql(def, a.variables || {});
    },

    // Web token for the HTTP API, refreshed by the page itself.
    'token.get': async () => {
      const t = await need().token.getToken();
      // clientId and appVersion let the host mint its own client token for direct HTTP calls.
      return t && {
        accessToken: t.accessToken,
        expiresAtMs: t.accessTokenExpirationTimestampMs,
        clientId: t.clientId,
        appVersion: need().platform.version,
        isAnonymous: !!t.isAnonymous,
      };
    },
  };

  const call = async (id, method, args) => {
    try {
      const fn = commands[method];
      if (!fn) throw new Error(`unknown method ${method}`);
      const value = await fn(args || {});
      const out = value === undefined ? null : value;
      post({ type: 'result', id, ok: true, value: out });
      return out;
    } catch (e) {
      post({ type: 'result', id, ok: false, error: String(e && e.message || e) });
      throw e;
    }
  };

  // --- events ---------------------------------------------------------------

  let lastState = '';
  const pushState = () => {
    if (!services) return;
    const state = mapState(services.player.getState());
    const json = JSON.stringify(state);
    if (json === lastState) return;
    lastState = json;
    post({ type: 'state', state });
  };

  const pushQueue = () => {
    if (services) post({ type: 'queue', queue: mapQueue(services.player.getQueue()) });
  };

  const attach = () => {
    const events = services.player.getEvents();
    events.addListener('update', pushState);
    events.addListener('queue_update', pushQueue);
  };

  // --- boot -----------------------------------------------------------------

  // --- diagnostics (read-only) ----------------------------------------------
  // Playback requests with their status and body size, and uncaught page errors,
  // so the host log shows why a play command did nothing.
  const PLAYBACK = /player\/command|track-playback|connect-state\/v1\/devices|widevine-license|storage-resolve|audio-.*\.scdn\.co|apresolve/;
  try {
    new PerformanceObserver(list => {
      for (const e of list.getEntries()) {
        if (!PLAYBACK.test(e.name)) continue;
        post({ type: 'net', url: e.name.split('?')[0].slice(0, 160), status: e.responseStatus || 0, bytes: e.encodedBodySize || e.transferSize || 0, ms: Math.round(e.duration) });
      }
    }).observe({ type: 'resource', buffered: true });
  } catch (e) { /* no resource timing */ }
  window.addEventListener('error', e => post({ type: 'jserror', error: String(e.message || e.error || '').slice(0, 300) }));
  window.addEventListener('unhandledrejection', e => post({ type: 'jserror', error: String((e.reason && (e.reason.stack || e.reason.message)) || e.reason || '').slice(0, 300) }));

  const announce = () => {
    if (!services) return;
    post({ type: 'ready', version: VERSION });
    lastState = '';
    pushState();
  };

  window.Myspoti = { version: VERSION, call, announce, methods: Object.keys(commands) };

  const started = Date.now();
  const boot = () => {
    try {
      services = resolveServices();
    } catch (e) {
      services = null;
    }
    if (services) {
      attach();
      post({ type: 'ready', version: VERSION });
      pushState();
      return;
    }
    if (Date.now() - started > READY_TIMEOUT_MS) {
      post({ type: 'error', error: 'player services not found' });
      return;
    }
    setTimeout(boot, 250);
  };
  boot();
})();
