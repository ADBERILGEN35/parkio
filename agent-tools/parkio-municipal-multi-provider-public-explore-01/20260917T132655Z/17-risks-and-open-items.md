# 17 — Risks and Open Items

1. **Offsite backup gap** — Civo local backup var; offsite ayrı risk (önceki rapor).
2. **Cross-provider duplicate facilities** — şema fusion yok; aynı fiziksel park iki source’ta görünebilir (bilinçli korunum).
3. **ISPARK scheduler** — ilk rollout’ta OFF kalmalı.
4. **ANPARK/KONYA/KAYSERI/IZELMAN** — mimari hazır; publication otomatik değil.
5. **Vite build-time flag** — web flag değişimi image rebuild ister; bu paket web flag değiştirmedi.
6. **Occupancy N+1** — mevcut desen; indeks/join iyileştirmesi ayrı.
7. **Production ISPARK veri kalitesi** — sync sonrası doğrulanmalı; stale/empty truthful kalmalı.
