# SLMtune для Android

Версия **4.1 beta**: VPN-клиент на ядре [sing-box](https://github.com/SagerNet/sing-box) 1.14.4 в цветах и логике Windows-версии SLMtune.

## Что умеет

- **Серверы:** ссылки из буфера, QR-код камерой, «Поделиться» из Telegram, ссылки `slmtune://import?link=…` и `slmtune://subscribe?url=…`, подписки с остатком трафика и сроком, бесплатный Cloudflare WARP в одно нажатие, пинг.
- **Протоколы:** VLESS (Reality, TLS, WebSocket, gRPC, HTTP/2, HTTPUpgrade), VMess, Trojan, Shadowsocks и Shadowsocks 2022, ShadowTLS v3, Hysteria2 (с прыжками по портам), Hysteria, TUIC, AnyTLS, NaiveProxy, SOCKS5, WireGuard и WARP.
- **Группы** («самый быстрый», «резерв») и **каскад**: свой «через» у каждого сервера или входной сервер для всех.
- **Маршруты:** пресеты «Всё», «Кроме РФ», «Только заблокированное» (те же списки, что в Windows-версии), ИИ через VPN с переключателем у каждого сервиса, игры и звонки по приложениям, свои правила для сайтов, IP и приложений.
- **Приложения:** через VPN только выбранные, или выбранные мимо VPN.
- **Блокировка** рекламы, трекеров и сайтов 18+.
- **Kill switch** — штатный режим Android «Постоянная VPN + блокировать без VPN» (кнопка ведёт в настройки).
- **Правила по Wi-Fi**, плитка в шторке, уведомление со скоростью, внешний IP после подключения.

- **Отдельные ядра (полная версия):** Xray для VLESS XHTTP и VLESS Encryption, официальный клиент Hysteria 2, Tor с мостами (Snowflake, obfs4, WebTunnel, meek, свои), TrustTunnel (`tt://`, только ARM64, экспериментально). Каждое работает как локальный SOCKS за sing-box: правила, DNS и VPN остаются общими.
- **Мои серверы:** свой VPS по SSH — 12 протоколов (VLESS Reality, Hysteria2, Shadowsocks 2022, NaiveProxy, TrustTunnel, Trojan, TUIC, AnyTLS, VLESS WS/gRPC, SOCKS5, ShadowTLS), пользователи с лимитами и сроком, ссылки для друзей, инструменты (обновление sing-box, защита SSH, swap, проверка сети). Скрипты те же, что в Windows-версии. Ключ сервера проверяется по отпечатку, вход — по собственному ed25519-ключу приложения.

Чего нет по сравнению с ПК: официальный клиент NaiveProxy (под Android его нет; NaiveProxy работает через sing-box), Zapret (без root невозможен).

## Полная и лёгкая версии

| | Полная (`full`) | Лёгкая (`lite`) |
|---|---|---|
| sing-box, все протоколы, маршруты, «Мои серверы» | да | да |
| Xray, Hysteria 2, Tor | да | нет |
| TrustTunnel | ARM64 | нет |
| Размер (ARM64) | ~67 МБ | ~34 МБ |

Отдельные ядра лежат в APK как `lib<имя>.so` (Android 10+ запускает программы только из папки библиотек приложения). Пока они работают, само приложение исключено из VPN, иначе их трафик зациклился бы в туннель; проверка внешнего IP идёт через локальный вход sing-box.

## Лицензия

GNU GPL версии 3 (файл `LICENSE`), как и ядро sing-box, которое входит в приложение. Полная версия также содержит неизменённые сборки Xray-core (MPL-2.0, github.com/XTLS/Xray-core), Hysteria (MIT, github.com/apernet/hysteria), Tor и lyrebird из Tor Expert Bundle (Tor собран под GPL-3.0, gitlab.torproject.org), клиента TrustTunnel (Apache-2.0, github.com/TrustTunnel/TrustTunnelClient); SSH — sshj (Apache-2.0) и Bouncy Castle (MIT). Код службы VPN частично взят из [sing-box-for-android](https://github.com/SagerNet/sing-box-for-android) (GPL-3.0); такие файлы помечены в начале. Исходники нужно выкладывать вместе с каждым опубликованным APK.

## Сборка

Нужны JDK 17 и 21, Android SDK (platform 37.1, build-tools 37), NDK r29, Go 1.25+.

1. Отдельные ядра (для полной версии): `py -3 tools/fetch_cores.py` скачивает последние релизы, сверяет SHA-256 с опубликованными проектами и кладёт их в `app/src/full/jniLibs` и `app/src/full/assets`.
2. Ядро: исходники sing-box `v1.14.4`, затем `go run ./cmd/internal/build_libbox -target android` с `JAVA_HOME` на OpenJDK 17. Готовый `libbox.aar` положить в `app/libs/`.
3. Приложение: `gradle assembleFullRelease assembleLiteRelease`. APK — в `app/build/outputs/apk/<full|lite>/release/`: отдельно для arm64-v8a (почти все телефоны), armeabi-v7a, x86_64 и универсальный.
4. Проверки: `gradle testFullDebugUnitTest`. Если указать `SLM_SINGBOX` на sing-box.exe, каждый сгенерированный конфиг проверяется самим sing-box; рядом лежащий `xray.exe` проверяет конфиги Xray. `SLM_SSH_TEST="порт|authorized_keys|ключ_сервера.pub"` включает живую проверку SSH против тестового сервера.
5. Скрипты сервера (`app/src/main/assets/server`) генерируются из `ServerSetup.cs` Windows-версии, чтобы обе программы настраивали сервер одинаково.

### Подпись

Release подписывается ключом `release.jks`, пароль лежит в `keystore.properties`. Оба файла не входят в исходники (`.gitignore`): без них сборка из исходников подписывается отладочным ключом. Сертификат официальных APK: `CN=SLMtune`, SHA-256 `7f9d733f881b061774757b13730f00b11a541b2fb046990aa34fa73b6686b8c9`.

Ключ нельзя терять: обновления ставятся поверх установленного приложения только с тем же ключом. Храните копию `release.jks` и `keystore.properties` отдельно от этого компьютера.
