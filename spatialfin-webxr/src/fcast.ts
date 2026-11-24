export function parseSafeHttpUrl(raw: unknown): URL | null {
    if (typeof raw !== 'string') return null;
    const trimmed = raw.trim();
    if (!trimmed) return null;
    try {
        const parsed = new URL(trimmed);
        if (parsed.protocol !== 'http:' && parsed.protocol !== 'https:') {
            return null;
        }
        return parsed;
    } catch {
        return null;
    }
}

export function formatCssBackgroundUrl(url: URL): string {
    return `url(${JSON.stringify(url.href)})`;
}

function redactUrlApiKeys(raw: unknown): unknown {
    if (typeof raw !== 'string') return raw;
    try {
        const parsed = new URL(raw);
        let changed = false;
        for (const key of Array.from(parsed.searchParams.keys())) {
            const lower = key.toLowerCase();
            if (lower === 'api_key' || lower === 'apikey' || lower === 'token') {
                parsed.searchParams.set(key, '[REDACTED]');
                changed = true;
            }
        }
        return changed ? parsed.toString() : raw;
    } catch {
        return raw.replace(/([?&](?:api_key|apikey|token)=)[^&#]*/gi, '$1[REDACTED]');
    }
}

export function redactFcastPlayPayloadForLog(payload: unknown): unknown {
    if (!payload || typeof payload !== 'object') return payload;
    const copy: Record<string, unknown> = { ...(payload as Record<string, unknown>) };
    if ('headers' in copy && copy.headers != null) {
        copy.headers = '[REDACTED]';
    }
    if ('url' in copy) {
        copy.url = redactUrlApiKeys(copy.url);
    }
    if (copy.metadata && typeof copy.metadata === 'object') {
        const meta: Record<string, unknown> = { ...(copy.metadata as Record<string, unknown>) };
        if ('image' in meta) {
            meta.image = redactUrlApiKeys(meta.image);
        }
        if (meta.custom && typeof meta.custom === 'object') {
            const custom: Record<string, unknown> = { ...(meta.custom as Record<string, unknown>) };
            if (Array.isArray(custom.subtitles)) {
                custom.subtitles = custom.subtitles.map((sub: unknown) => {
                    if (!sub || typeof sub !== 'object') return sub;
                    return { ...(sub as Record<string, unknown>), url: redactUrlApiKeys((sub as Record<string, unknown>).url) };
                });
            }
            meta.custom = custom;
        }
        copy.metadata = meta;
    }
    return copy;
}

function isValidIpv4(ip: string): boolean {
    const parts = ip.split('.');
    if (parts.length !== 4) return false;
    return parts.every((p) => /^(?:0|[1-9]\d{0,2})$/.test(p) && Number(p) >= 0 && Number(p) <= 255);
}

function isValidIpv6(ip: string): boolean {
    if (!ip.includes(':')) return false;
    if (!/^[0-9a-fA-F:.]+$/.test(ip)) return false;
    try {
        const parsed = new URL(`ws://[${ip}]:1`);
        return parsed.hostname.startsWith('[') && parsed.hostname.endsWith(']');
    } catch {
        return false;
    }
}

export function buildFCastReceiverWsUrl(ip: string, port: number): string {
    if (!Number.isInteger(port) || port < 1 || port > 65535) {
        throw new Error(`Invalid FCast receiver port: ${port}`);
    }
    const raw = typeof ip === 'string' ? ip.trim() : '';
    const bare = raw.startsWith('[') && raw.endsWith(']') ? raw.slice(1, -1) : raw;
    if (!bare || /[\s/?#@\\]/.test(bare)) {
        throw new Error(`Invalid FCast receiver IP: ${ip}`);
    }
    let hostPart: string;
    if (isValidIpv4(bare)) {
        hostPart = bare;
    } else if (isValidIpv6(bare)) {
        hostPart = `[${bare}]`;
    } else {
        throw new Error(`Invalid FCast receiver IP: ${ip}`);
    }
    const url = new URL(`ws://${hostPart}:${port}`);
    if (url.pathname !== '/' || url.search !== '' || url.hash !== '') {
        throw new Error(`Invalid FCast receiver URL: ${url.href}`);
    }
    return `ws://${hostPart}:${port}`;
}

export class FCastClient {
    private ip: string;
    private port: number;
    private ws: WebSocket | null;
    
    public onMessage: ((msg: {opcode: number, payload: any}) => void) | null = null;
    public onConnect: (() => void) | null = null;
    public onDisconnect: (() => void) | null = null;
    public onError: ((err: Event) => void) | null = null;

    constructor(ip: string, port: number) {
        this.ip = ip;
        this.port = port;
        this.ws = null;
    }

    connect() {
        const wsUrl = buildFCastReceiverWsUrl(this.ip, this.port);
        this.ws = new WebSocket(wsUrl);
        this.ws.binaryType = 'arraybuffer';

        this.ws.onopen = () => {
            if (this.onConnect) this.onConnect();
        };

        this.ws.onclose = () => {
            if (this.onDisconnect) this.onDisconnect();
        };

        this.ws.onerror = (err) => {
            if (this.onError) this.onError(err);
        };

        this.ws.onmessage = (event) => {
            try {
                const data = event.data;
                if (data instanceof ArrayBuffer) {
                    const view = new Uint8Array(data);
                    if (view.length === 0) return;
                    
                    const opcode = view[0];
                    let payload = null;
                    if (view.length > 1) {
                        const decoder = new TextDecoder();
                        const jsonStr = decoder.decode(view.subarray(1));
                        if (jsonStr.trim()) {
                            payload = JSON.parse(jsonStr);
                        }
                    }
                    if (this.onMessage) this.onMessage({ opcode, payload });
                }
            } catch (err) {
                console.error("Error decoding FCast message", err);
            }
        };
    }

    sendMessage(opcode: number, payload: any = null) {
        if (this.ws && this.ws.readyState === WebSocket.OPEN) {
            let payloadBytes = new Uint8Array(0);
            if (payload !== null) {
                const encoder = new TextEncoder();
                payloadBytes = encoder.encode(JSON.stringify(payload));
            }
            
            const message = new Uint8Array(1 + payloadBytes.length);
            message[0] = opcode;
            message.set(payloadBytes, 1);
            
            this.ws.send(message);
        } else {
            console.warn("WebSocket is not open");
        }
    }
    
    disconnect() {
        if (this.ws) {
            this.ws.close();
            this.ws = null;
        }
    }
}
