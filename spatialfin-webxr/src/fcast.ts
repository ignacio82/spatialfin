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
        this.ws = new WebSocket(`ws://${this.ip}:${this.port}`);
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
