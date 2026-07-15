import { type JellyfinItem } from './api';

export class OfflineMediaRepository {
  private db: IDBDatabase | null = null;
  private readonly DB_NAME = 'SpatialFinOfflineDb';
  private readonly STORE_ITEMS = 'items';
  private initPromise: Promise<void>;

  constructor() {
    this.initPromise = this.initDb();
  }

  private async initDb(): Promise<void> {
    return new Promise((resolve, reject) => {
      const request = indexedDB.open(this.DB_NAME, 1);
      request.onerror = (event) => {
        console.error('Failed to open Offline DB', event);
        reject(request.error);
      };
      request.onupgradeneeded = (event) => {
        const db = (event.target as IDBOpenDBRequest).result;
        if (!db.objectStoreNames.contains(this.STORE_ITEMS)) {
          db.createObjectStore(this.STORE_ITEMS, { keyPath: 'Id' });
        }
      };
      request.onsuccess = (event) => {
        this.db = (event.target as IDBOpenDBRequest).result;
        resolve();
      };
    });
  }

  private async getStore(mode: IDBTransactionMode): Promise<IDBObjectStore> {
    await this.initPromise;
    return new Promise((resolve, reject) => {
      if (!this.db) {
        reject(new Error('Offline DB not initialized'));
        return;
      }
      const transaction = this.db.transaction([this.STORE_ITEMS], mode);
      resolve(transaction.objectStore(this.STORE_ITEMS));
    });
  }

  public async saveItemMetadata(item: JellyfinItem): Promise<void> {
    const store = await this.getStore('readwrite');
    return new Promise((resolve, reject) => {
      const request = store.put(item);
      request.onsuccess = () => resolve();
      request.onerror = () => reject(request.error);
    });
  }

  public async getItemMetadata(itemId: string): Promise<JellyfinItem | null> {
    const store = await this.getStore('readonly');
    return new Promise((resolve, reject) => {
      const request = store.get(itemId);
      request.onsuccess = () => resolve((request.result as JellyfinItem) || null);
      request.onerror = () => reject(request.error);
    });
  }

  public async getAllDownloadedItems(): Promise<JellyfinItem[]> {
    const store = await this.getStore('readonly');
    return new Promise((resolve, reject) => {
      const request = store.getAll();
      request.onsuccess = () => resolve((request.result as JellyfinItem[]) || []);
      request.onerror = () => reject(request.error);
    });
  }

  public async saveMediaFile(itemId: string, response: Response): Promise<void> {
    try {
      const opfsRoot = await navigator.storage.getDirectory();
      const fileHandle = await opfsRoot.getFileHandle(`media_${itemId}.mp4`, { create: true });
      const writable = await fileHandle.createWritable();
      if (response.body) {
        await response.body.pipeTo(writable);
      }
      console.log(`Saved media file for ${itemId} to OPFS`);
    } catch (e) {
      console.error('Failed to save media file to OPFS', e);
      throw e;
    }
  }

  public async getMediaFileUrl(itemId: string): Promise<string | null> {
    try {
      const opfsRoot = await navigator.storage.getDirectory();
      const fileHandle = await opfsRoot.getFileHandle(`media_${itemId}.mp4`);
      const file = await fileHandle.getFile();
      return URL.createObjectURL(file);
    } catch (e) {
      return null;
    }
  }

  public async isItemDownloaded(itemId: string): Promise<boolean> {
    return (await this.getMediaFileUrl(itemId)) !== null;
  }
}

export const offlineMediaRepository = new OfflineMediaRepository();
