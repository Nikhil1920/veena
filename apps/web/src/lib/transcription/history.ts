import type { ModelId } from "./models";

export type Entry = {
	model?: ModelId;
	status?: "processing" | "complete" | "failed";
	error?: string;
	id: string;
	date: number;
	language: string;
	title: string;
	text: string;
	rawText: string;
	audio: Blob;
	duration: number;
	elapsed: number;
	saved: boolean;
};

function database(): Promise<IDBDatabase> {
	return new Promise((resolve, reject) => {
		const request = indexedDB.open("veena-transcriptions", 1);
		request.onupgradeneeded = () =>
			request.result.createObjectStore("history", { keyPath: "id" });
		request.onsuccess = () => resolve(request.result);
		request.onerror = () => reject(request.error);
	});
}

async function operation<T>(
	mode: IDBTransactionMode,
	fn: (store: IDBObjectStore) => IDBRequest<T>
): Promise<T> {
	const db = await database();
	return new Promise((resolve, reject) => {
		const transaction = db.transaction("history", mode);
		const request = fn(transaction.objectStore("history"));
		transaction.oncomplete = () => {
			db.close();
			resolve(request.result);
		};
		transaction.onerror = () => {
			db.close();
			reject(transaction.error);
		};
		transaction.onabort = () => {
			db.close();
			reject(transaction.error);
		};
	});
}

export const listHistory = async () =>
	((await operation("readonly", (store) => store.getAll())) as Entry[]).sort(
		(a, b) => b.date - a.date
	);
export const saveEntry = (entry: Entry) =>
	operation("readwrite", (store) => store.put(entry));
export const deleteEntry = (id: string) =>
	operation("readwrite", (store) => store.delete(id));

export async function retainHistory(limit: number) {
	const entries = await listHistory();
	for (const entry of entries.filter((entry) => !entry.saved).slice(limit))
		await deleteEntry(entry.id);
}
