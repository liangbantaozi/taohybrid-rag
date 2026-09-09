# Upload Benchmark

This benchmark measures browser chunk upload and MinIO merge time only. It deliberately skips parsing, chunking, embedding, and Kafka processing.

## Guardrails

- The backend switch is disabled by default: `UPLOAD_BENCHMARK_ENABLED=false`.
- Benchmark requests are rejected unless the backend switch is explicitly enabled.
- The frontend only sends the benchmark flag in Vite development mode and when its local switch is enabled.
- Normal knowledge-base uploads keep their existing processing behavior.

## Local setup

Add this local-only value to the backend environment file, then restart the backend:

```text
UPLOAD_BENCHMARK_ENABLED=true
```

Create `frontend/.env.local` with one of the following settings, then restart the frontend dev server:

```text
VITE_UPLOAD_BENCHMARK_ENABLED=Y
VITE_UPLOAD_BENCHMARK_CONCURRENCY=1
```

Use `1` for the serial baseline and `4` for the current parallel implementation. The value is limited to 1-8 and falls back to the normal value of 4 when the benchmark switch is off.

## Test protocol

1. Use unique valid `.txt` files. Each run must differ in content so it has a different MD5.
2. Run each file size and concurrency configuration at least three times. Use five runs for formal P95 reporting.
3. Upload from the knowledge-base page. A successful benchmark response says that parsing and vectorization were skipped.
4. Copy the JSON records from browser local storage key `tao-hybrid:upload-benchmark-results` into the experiment report.
5. Compare mean, P50, P95, failure rate, and upload MiB/s. Do not compare results across different computers or network conditions.

## Result fields

- `chunkUploadMs`: first chunk request through all chunks uploaded.
- `mergeMs`: request time for MinIO object merge.
- `totalMs`: chunk upload plus merge.
- `uploadMiBPerSecond`: file size divided by `chunkUploadMs`.
