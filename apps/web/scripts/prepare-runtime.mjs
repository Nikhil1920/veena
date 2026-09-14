import { copyFile, mkdir } from "node:fs/promises";
import { createRequire } from "node:module";
import { dirname, resolve } from "node:path";

const require = createRequire(import.meta.url);
const ort = dirname(require.resolve("onnxruntime-web"));
await mkdir("public/runtime", { recursive: true });
for (const file of [
	"ort-wasm-simd-threaded.wasm",
	"ort-wasm-simd-threaded.mjs",
]) {
	await copyFile(resolve(ort, file), resolve("public/runtime", file));
}
console.log("Prepared matching ONNX Runtime Web assets.");
