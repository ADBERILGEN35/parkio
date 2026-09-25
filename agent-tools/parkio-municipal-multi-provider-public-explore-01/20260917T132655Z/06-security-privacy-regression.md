# 06 — Security / Privacy Regression

| Kontrol | Sonuç |
|---------|--------|
| Anonim Public Explore | feature ON iken mevcut rota |
| Feature OFF | controller bean yok (mevcut) |
| Private municipal detail/nearby | dokunulmadı |
| Community precise | dokunulmadı |
| communitySpotCountInScope eşik 3 | korundu |
| Kaynak seçimi HTTP param | yok; yalnızca server policy |
| SQL injection | parametreli IN; invalid config query’ye ulaşmaz |
| PA-06 | dokunulmadı (ayrı bulgu) |
| Gateway trust | değişmedi |

Anonim veri genişletilmedi; yalnızca reviewed source set genişletilebilir hale geldi.
