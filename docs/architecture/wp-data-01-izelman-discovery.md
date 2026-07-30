# IZELMAN discovery report (DATA-WP-01)

Status: **importer not implemented / disabled**

## Verified portal dataset

- Title: Izelman Otoparklari Lokasyon, Kapasite ve Calisma Saati Verisi
- URL: https://acikveri.bizizmir.com/dataset/izelman-otopark-lokasyon-kapasite-ve-calisma-saati-verisi
- Author/maintainer: IZELMAN A.S. Otoparklar Mudurlugu
- Publisher: Izmir Buyuksehir Belediyesi
- Resources: CSV (roadside, covered, open-area, subscriber barrier)
- Portal last updated: 2022-11-28
- Update frequency: irregular

## Decision for WP-01

Treat as slower-changing inventory/tariff candidate, not live occupancy.
Do not scrape HTML. Do not import until:

1. Product/legal accept aged CSV as non-guaranteed inventory
2. Schema fixture captured deterministically
3. Separate importer (not mixed into IZUM occupancy DTO)
4. Source age and last-verified date exposed to clients

Live occupancy remains IZUM Open API only.