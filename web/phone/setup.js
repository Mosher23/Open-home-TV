import {
    base64 as b64,
    fromBase64 as un64,
    requestEnvelope,
    readReply,
} from "./protocol.js";

const de = navigator.language.startsWith("de");
const text = (en, german) => (de ? german : en);
let keyString = location.hash.slice(1);
history.replaceState(null, "", location.pathname);
let key;
try {
    key = un64(keyString);
    if (key.length !== 32) throw Error();
} catch {
    key = null;
}
keyString = "";
const client = b64(crypto.getRandomValues(new Uint8Array(18)))
    .replace(/\+/g, "-")
    .replace(/\//g, "_")
    .replace(/=/g, "");
const root = document.querySelector("#content"),
    status = document.querySelector("#status"),
    footer = document.querySelector("#footer");
let config,
    info,
    applying = false,
    poll,
    expiryTimer,
    busy = false;
const names = new Map();
function el(tag, content, attrs = {}) {
    const node = document.createElement(tag);
    if (content !== undefined) node.textContent = content;
    for (const [k, v] of Object.entries(attrs)) node.setAttribute(k, v);
    return node;
}
function clear(node) {
    node.replaceChildren();
}
function button(label, action, primary = false) {
    const b = el("button", label, { type: "button" });
    if (primary) b.className = "primary";
    b.addEventListener("click", action);
    return b;
}
function message(value, error = false) {
    status.textContent = value;
    status.className = error ? "error" : "";
}
function field(label, value = "", type = "text", change = () => {}) {
    const l = el("label", label);
    l.className = "field";
    const input = el("input", undefined, { type });
    input.value = value;
    input.addEventListener("input", () => change(input.value));
    l.append(input);
    return { label: l, input };
}
function section(title, hint) {
    const s = el("section");
    s.append(el("h2", title));
    if (hint) s.append(el("p", hint));
    root.append(s);
    return s;
}
async function api(op, args = {}) {
    const iv = crypto.getRandomValues(new Uint8Array(12));
    const envelope = requestEnvelope(key, { op, client, ...args }, iv);
    const controller = new AbortController();
    const timeout = setTimeout(() => controller.abort(), 30000);
    let response;
    try {
        response = await fetch("/api", {
            method: "POST",
            cache: "no-store",
            credentials: "omit",
            headers: { "Content-Type": "application/json" },
            body: JSON.stringify(envelope),
            signal: controller.signal,
        });
    } finally {
        clearTimeout(timeout);
    }
    if (!response.ok)
        throw Error(
            text(
                "Setup ended or the connection was lost. Start setup again on the TV.",
                "Einrichtung beendet oder Verbindung verloren. Auf dem TV neu starten.",
            ),
        );
    const box = await response.json();
    const reply = readReply(key, box, envelope.iv);
    if (!reply.ok)
        throw Error(
            reply.error || text("Request failed", "Anfrage fehlgeschlagen"),
        );
    return reply;
}
async function run(action) {
    if (busy) return;
    busy = true;
    try {
        await action();
    } catch (e) {
        message(e.message, true);
    } finally {
        busy = false;
    }
}
function summary() {
    document.querySelector("#summary").textContent = text(
        `${config.apps.length} apps · ${config.controls.length} controls · ${config.cameras.length} cameras`,
        `${config.apps.length} Apps · ${config.controls.length} Geräte · ${config.cameras.length} Kameras`,
    );
}
function applyInfo(value) {
    info = value;
    config = value.config;
    for (const e of value.selectedEntities || []) names.set(e.id, e.name);
}
async function pair() {
    if (!key) {
        root.append(
            el(
                "h1",
                text("Scan the TV’s QR code", "QR-Code auf dem TV scannen"),
            ),
            el(
                "p",
                text(
                    "This page needs the temporary setup link. Restart setup on your TV and scan its QR code. Reloading this page clears pairing.",
                    "Diese Seite benötigt den temporären Link. Einrichtung auf dem TV starten und QR-Code scannen. Neuladen beendet die Kopplung.",
                ),
            ),
        );
        return;
    }
    clear(root);
    root.append(
        el(
            "h1",
            text("Approve your phone on the TV", "Telefon auf dem TV erlauben"),
        ),
    );
    root.append(
        el(
            "p",
            text(
                "Check that this number matches the TV, then select Allow phone using your remote.",
                "Vergleiche diese Nummer mit dem TV und wähle mit der Fernbedienung Telefon erlauben.",
            ),
        ),
    );
    const code = el("div", "", { class: "code" });
    root.append(code);
    root.append(
        el(
            "p",
            text(
                "Pairing expires after 10 minutes. Keep the setup screen open on the TV.",
                "Die Kopplung läuft nach 10 Minuten ab. Einrichtungsseite auf dem TV offen lassen.",
            ),
        ),
    );
    try {
        async function check() {
            const r = await api("pair");
            if (!expiryTimer)
                expiryTimer = setTimeout(() => {
                    clearInterval(poll);
                    if (key) key.fill(0);
                    key = null;
                    footer.hidden = true;
                    clear(root);
                    message(
                        text(
                            "Setup expired. Start a new pairing on the TV.",
                            "Einrichtung abgelaufen. Auf dem TV eine neue Kopplung starten.",
                        ),
                        true,
                    );
                }, r.expiresIn * 1000);
            code.textContent = r.code;
            if (r.phase === "paired") {
                clearInterval(poll);
                applyInfo(await api("info"));
                render();
            }
        }
        await check();
        if (!config)
            poll = setInterval(
                () =>
                    run(async () => {
                        try {
                            await check();
                        } catch (e) {
                            clearInterval(poll);
                            throw e;
                        }
                    }),
                1600,
            );
    } catch (e) {
        message(e.message, true);
    }
}
function render() {
    clear(root);
    message("");
    footer.hidden = false;
    document.querySelector("#subtitle").textContent = text(
        "Local setup · changes apply together",
        "Lokale Einrichtung · gemeinsam übernehmen",
    );
    root.append(
        el("h1", text("Make this TV yours", "Dein TV. Dein Zuhause.")),
        el(
            "p",
            text(
                "Connect Home Assistant, choose what to show, then apply your dashboard.",
                "Verbinde Home Assistant, wähle die Inhalte und übernimm dein Dashboard.",
            ),
        ),
    );
    const connection = section(
        text("1. Connect Home Assistant", "1. Home Assistant verbinden"),
    );
    const url = field(text("Server URL", "Server-URL"), config.server, "url");
    url.input.placeholder = "http://homeassistant.local:8123";
    url.input.autocomplete = "off";
    url.input.spellcheck = false;
    const token = field(text("Access token", "Zugriffstoken"), "", "password");
    token.input.autocomplete = "off";
    token.input.spellcheck = false;
    connection.append(
        url.label,
        token.label,
        el(
            "p",
            text(
                info.hasToken
                    ? "Leave the token blank to keep it for the same server."
                    : "Paste a long-lived access token from Home Assistant.",
                "Token aus Home Assistant einfügen. Für denselben Server kann das gespeicherte Token weiterverwendet werden.",
            ),
        ),
    );
    const connect = button(
        text(
            info.connected ? "Reload devices" : "Connect and load devices",
            info.connected ? "Geräte neu laden" : "Verbinden und Geräte laden",
        ),
        () =>
            run(async () => {
                connect.disabled = true;
                message(
                    text(
                        "Checking Home Assistant…",
                        "Home Assistant wird geprüft…",
                    ),
                );
                try {
                    const r = await api("connect", {
                        server: url.input.value,
                        token: token.input.value,
                    });
                    token.input.value = "";
                    applyInfo(r);
                    render();
                    message(
                        text(
                            "Connected. Choose your dashboard below.",
                            "Verbunden. Wähle unten deine Inhalte.",
                        ),
                    );
                } finally {
                    connect.disabled = false;
                }
            }),
        true,
    );
    connection.append(connect);
    if (!info.connected)
        connection.append(
            el(
                "p",
                text(
                    "You can also choose apps below and apply without changing your HA connection.",
                    "Du kannst auch nur Apps auswählen und deine HA-Verbindung unverändert lassen.",
                ),
            ),
        );
    else
        connection.append(
            el(
                "p",
                text(
                    "✓ Connection verified · Demo mode will turn off when applied.",
                    "✓ Verbindung geprüft · Demomodus wird beim Übernehmen ausgeschaltet.",
                ),
                { class: "success" },
            ),
        );
    if (info.connected) renderEntities();
    renderApps();
    renderAppearance();
    summary();
}
function sensorSelect(label, keyName, weather = false) {
    const box = el("label", label, { class: "field" }),
        select = el("select");
    box.append(select);
    select.addEventListener("change", () => {
        if (weather) config.weather = select.value;
        else if (select.value) config.energy[keyName] = select.value;
        else delete config.energy[keyName];
    });
    return { box, select, weather, keyName };
}
function fillSelect(target, items) {
    const value = target.weather
        ? config.weather
        : config.energy[target.keyName] || "";
    clear(target.select);
    const none = el("option", text("Not used", "Nicht verwendet"), {
        value: "",
    });
    target.select.append(none);
    if (value && !items.some((e) => e.id === value))
        target.select.append(
            el("option", names.get(value) || value, { value }),
        );
    for (const e of items) {
        names.set(e.id, e.name);
        target.select.append(
            el("option", `${e.name}${e.unit ? " · " + e.unit : ""} — ${e.id}`, {
                value: e.id,
            }),
        );
    }
    target.select.value = value;
}
function renderEntities() {
    const energy = section(
        text("2. Weather & power", "2. Wetter und Leistung"),
        text(
            "Choose live power sensors in W or kW. Cumulative kWh sensors are not suitable.",
            "Aktuelle Leistungssensoren in W oder kW auswählen. Zählerstände in kWh sind nicht geeignet.",
        ),
    );
    const location = field(
        text("Location label", "Ortsname"),
        config.location,
        "text",
        (v) => (config.location = v),
    );
    energy.append(location.label);
    const weather = sensorSelect(
        text("Weather entity", "Wetter-Entität"),
        "weather",
        true,
    );
    energy.append(weather.box);
    const filter = field(
        text("Find power sensors", "Leistungssensoren suchen"),
    );
    filter.input.placeholder = text(
        "Search by name or entity ID",
        "Nach Namen oder Entitäts-ID suchen",
    );
    energy.append(filter.label);
    const grid = el("div", undefined, { class: "grid" });
    energy.append(grid);
    const roles = [
        ["solar", text("Solar", "PV")],
        ["grid", text("Grid", "Netz")],
        ["battery", text("Battery", "Batterie")],
        ["heat_pump", text("Heat pump", "Wärmepumpe")],
        ["ev", text("EV charger", "E-Auto")],
        [
            "household",
            text("Other household consumption", "Übriger Haushaltsverbrauch"),
        ],
    ];
    const selects = roles.map(([id, label]) => {
        const target = sensorSelect(label, id);
        grid.append(target.box);
        return target;
    });
    const count = el("p", "", { class: "note" });
    energy.append(count);
    energy.append(
        el(
            "p",
            text(
                "Grid: positive = import, negative = export. Battery: positive = discharging. Leave unused roles empty.",
                "Netz: positiv = Bezug, negativ = Einspeisung. Batterie: positiv = Entladen. Nicht verwendete Rollen leer lassen.",
            ),
            { class: "note" },
        ),
    );
    let timer,
        version = 0;
    async function loadPower() {
        const n = ++version;
        try {
            const r = await api("entities", {
                domain: "sensor",
                query: filter.input.value,
            });
            if (n !== version) return;
            for (const s of selects) fillSelect(s, r.entities);
            count.textContent = text(
                `${r.entities.length} of ${r.total} sensors shown. Refine the search if needed.`,
                `${r.entities.length} von ${r.total} Sensoren. Bei Bedarf Suche eingrenzen.`,
            );
        } catch (e) {
            message(e.message, true);
        }
    }
    filter.input.addEventListener("input", () => {
        clearTimeout(timer);
        timer = setTimeout(loadPower, 350);
    });
    api("entities", { domain: "weather", query: "" })
        .then((r) => fillSelect(weather, r.entities))
        .catch((e) => message(e.message, true));
    loadPower();
    entityChecks(
        "controls",
        text("3. Lights, switches & scenes", "3. Licht, Schalter und Szenen"),
    );
    entityChecks("camera", text("4. Cameras", "4. Kameras"));
}
function entityChecks(domain, title) {
    const sectionNode = section(
        title,
        domain === "camera"
            ? text(
                  "The first two selected cameras appear on Home. All selected cameras remain accessible in Cameras.",
                  "Die ersten zwei ausgewählten Kameras erscheinen auf Home. Alle ausgewählten Kameras bleiben unter Kameras erreichbar.",
              )
            : text(
                  "Selected entities appear in the matching dashboard panel and Devices screen.",
                  "Ausgewählte Entitäten erscheinen im passenden Panel und unter Geräte.",
              ),
    );
    const chosen = el("div"),
        search = field(text("Search entities", "Entitäten suchen")),
        list = el("div"),
        count = el("p", "", { class: "note" });
    sectionNode.append(chosen, search.label, list, count);
    const ids = () =>
        domain === "camera" ? config.cameras.map((c) => c.id) : config.controls;
    function remove(id) {
        if (domain === "camera")
            config.cameras = config.cameras.filter((c) => c.id !== id);
        else config.controls = config.controls.filter((v) => v !== id);
    }
    function selected() {
        clear(chosen);
        for (const id of ids()) {
            const chip = button(`${names.get(id) || id} ×`, () => {
                remove(id);
                selected();
                load();
                summary();
            });
            chip.className = "chip";
            chosen.append(chip);
        }
        if (domain === "camera") renderEndpoints();
    }
    const endpoints = el("div");
    if (domain === "camera") sectionNode.append(endpoints);
    function renderEndpoints() {
        clear(endpoints);
        for (const c of config.cameras) {
            const detail = el("details");
            detail.append(
                el(
                    "summary",
                    text(
                        `Stream options · ${names.get(c.id) || c.id}`,
                        `Stream-Optionen · ${names.get(c.id) || c.id}`,
                    ),
                ),
            );
            detail.append(
                el(
                    "p",
                    text(
                        "Leave both URLs blank to request HLS from Home Assistant.",
                        "Beide URLs leer lassen, um HLS von Home Assistant anzufordern.",
                    ),
                ),
            );
            detail.append(
                field(
                    text(
                        "go2rtc WebRTC API URL (optional)",
                        "go2rtc WebRTC-API-URL (optional)",
                    ),
                    c.rtc,
                    "url",
                    (v) => (c.rtc = v),
                ).label,
                field(
                    text(
                        "HLS playlist URL (optional)",
                        "HLS-Playlist-URL (optional)",
                    ),
                    c.hls,
                    "url",
                    (v) => (c.hls = v),
                ).label,
            );
            endpoints.append(detail);
        }
    }
    let timer,
        version = 0;
    async function load() {
        const n = ++version;
        try {
            const r = await api("entities", {
                domain,
                query: search.input.value,
            });
            if (n !== version) return;
            clear(list);
            for (const e of r.entities) {
                names.set(e.id, e.name);
                const label = el("label", undefined, { class: "check" }),
                    check = el("input", undefined, { type: "checkbox" });
                check.checked = ids().includes(e.id);
                check.addEventListener("change", () => {
                    if (check.checked) {
                        if (domain === "camera") {
                            if (config.cameras.length >= 12) {
                                check.checked = false;
                                message(
                                    text(
                                        "Choose up to 12 cameras",
                                        "Bis zu 12 Kameras auswählen",
                                    ),
                                    true,
                                );
                                return;
                            }
                            config.cameras.push({ id: e.id, hls: "", rtc: "" });
                        } else config.controls.push(e.id);
                    } else remove(e.id);
                    selected();
                    summary();
                });
                const desc = el("span", e.name);
                desc.append(
                    el(
                        "small",
                        e.id +
                            (e.available
                                ? ""
                                : text(" · unavailable", " · nicht verfügbar")),
                    ),
                );
                label.append(check, desc);
                list.append(label);
            }
            count.textContent = text(
                `${r.entities.length} of ${r.total} entities shown`,
                `${r.entities.length} von ${r.total} Entitäten`,
            );
            selected();
        } catch (e) {
            message(e.message, true);
        }
    }
    search.input.addEventListener("input", () => {
        clearTimeout(timer);
        timer = setTimeout(load, 350);
    });
    selected();
    load();
}
function renderApps() {
    const s = section(
        text("5. Apps on Home", "5. Apps auf Home"),
        text(
            "Only the apps you choose appear on Home. Use arrows to set their order.",
            "Nur ausgewählte Apps erscheinen auf Home. Mit den Pfeilen die Reihenfolge festlegen.",
        ),
    );
    const chosen = el("div"),
        available = el("div");
    s.append(chosen, available);
    function draw() {
        clear(chosen);
        clear(available);
        for (const [index, a] of config.apps.entries()) {
            const row = el("div", undefined, { class: "selected" });
            row.append(
                el(
                    "strong",
                    a.label ||
                        info.apps.find((v) => v.id === a.id)?.name ||
                        a.id,
                ),
            );
            const actions = el("div", undefined, { class: "row" });
            const up = button("↑", () => {
                [config.apps[index - 1], config.apps[index]] = [
                    config.apps[index],
                    config.apps[index - 1],
                ];
                draw();
            });
            up.disabled = index === 0;
            up.setAttribute("aria-label", text("Move up", "Nach oben"));
            const down = button("↓", () => {
                [config.apps[index + 1], config.apps[index]] = [
                    config.apps[index],
                    config.apps[index + 1],
                ];
                draw();
            });
            down.disabled = index === config.apps.length - 1;
            down.setAttribute("aria-label", text("Move down", "Nach unten"));
            actions.append(
                up,
                down,
                button(text("Remove", "Entfernen"), () => {
                    config.apps = config.apps.filter((v) => v.id !== a.id);
                    draw();
                    summary();
                }),
            );
            row.append(actions);
            chosen.append(row);
        }
        for (const app of info.apps.filter(
            (a) => !config.apps.some((v) => v.id === a.id),
        )) {
            const b = button(`+ ${app.name}`, () => {
                config.apps.push({ id: app.id, label: "", icon: "" });
                draw();
                summary();
            });
            b.className = "chip";
            available.append(b);
        }
    }
    draw();
}
function renderAppearance() {
    const s = section(text("6. Appearance", "6. Darstellung"));
    for (const [prop, en, german] of [
        [
            "livePreviews",
            "Show live cameras on Home (up to two)",
            "Livekameras auf Home (höchstens zwei)",
        ],
        ["animate", "Animate power flows", "Energieflüsse animieren"],
        ["fahrenheit", "Display Fahrenheit", "Fahrenheit anzeigen"],
        ["darkPanels", "Darker panels", "Dunklere Panels"],
    ]) {
        const l = el("label", undefined, { class: "check" }),
            c = el("input", undefined, { type: "checkbox" });
        c.checked = config[prop];
        c.addEventListener("change", () => (config[prop] = c.checked));
        l.append(c, el("span", text(en, german)));
        s.append(l);
    }
    s.append(
        el(
            "p",
            text(
                "Changes are saved only when you choose Apply to TV. Leaving setup cancels this draft.",
                "Änderungen werden erst mit Auf TV übernehmen gespeichert. Verlassen verwirft den Entwurf.",
            ),
            { class: "note" },
        ),
    );
}
document.querySelector("#apply").textContent = text(
    "Apply to TV",
    "Auf TV übernehmen",
);
document.querySelector("#apply").addEventListener("click", () =>
    run(async () => {
        if (applying) return;
        applying = true;
        const b = document.querySelector("#apply");
        b.disabled = true;
        message(text("Applying your dashboard…", "Dashboard wird übernommen…"));
        try {
            await api("apply", { config });
            clearInterval(poll);
            clearTimeout(expiryTimer);
            key.fill(0);
            key = null;
            footer.hidden = true;
            clear(root);
            message("");
            root.append(
                el("h1", text("Your TV is ready", "Dein TV ist bereit")),
                el(
                    "p",
                    text(
                        "Your choices are saved. The TV is returning to your dashboard and this setup session has closed.",
                        "Deine Auswahl ist gespeichert. Der TV kehrt zum Dashboard zurück und die Einrichtung wird geschlossen.",
                    ),
                ),
            );
        } catch (e) {
            applying = false;
            b.disabled = false;
            throw e;
        }
    }),
);
window.addEventListener("pagehide", () => {
    clearInterval(poll);
    if (key) key.fill(0);
});
pair();
