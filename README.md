# HA TV

Aplikacja na Android TV do podglądu i sterowania Home Assistantem pilotem.

* **Kafelki encji** – światła, przełączniki, rolety, ogrzewanie, multimedia, sceny, czujniki…
  OK = włącz/wyłącz (otwórz/zamknij, uruchom), długie OK / MENU = szczegóły (jasność, temperatura, pozycja, głośność ◀ ▶).
* **Dashboardy jak na PC** – prawdziwy interfejs Home Assistanta z kursorem sterowanym strzałkami,
  P+ / P− przełącza karty (widoki), OK klika.
* **Wbudowany serwer ustawień** – `http://IP_TELEWIZORA:5003` (adres HA, token, wybór kafelków i dashboardów).
* Połączenie przez WebSocket – stany zmieniają się na ekranie od razu.

## Instalacja

1. Pobierz `ha-tv.apk` z zakładki **Releases → latest**.
2. Z folderu z `adb`:
   ```
   adb connect IP_TELEWIZORA:5555
   adb install -r ha-tv.apk
   adb shell am start -n pl.kamil.hatv/.MainActivity
   ```
3. Na telefonie/komputerze otwórz adres widoczny u góry ekranu aplikacji (`http://IP_TELEWIZORA:5003`),
   wpisz adres Home Assistanta i token długoterminowy, wybierz encje i dashboardy.

## Pilot

| Gdzie | Przycisk | Działanie |
|---|---|---|
| Kafelki | OK | główna akcja |
| Kafelki | długie OK / MENU / żółty | szczegóły encji |
| Kafelki | P+ / P− | przewijanie |
| Dashboard | strzałki | kursor |
| Dashboard | OK | kliknięcie (przytrzymanie = długie) |
| Dashboard | P+ / P− / 1–9 | karty |
| Dashboard | ⏪ ⏩ | przewijanie |
| wszędzie | WSTECZ | zamknij / wróć |
