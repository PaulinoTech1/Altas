/* Local investigation UI. Evidence is inserted as escaped text. */
(() => {
  const v2 = (op, args = {}, write = false) =>
    write
      ? api("/api/v2/" + op, args)
      : api("/api/v2/" + op + "?" + new URLSearchParams(args));
  let view = "map",
    overview = null,
    selectedNode = null,
    mapPage = 0,
    mapView = [0, 0, 1100, 700];
  let sessions = [],
    packetCursor = null,
    nextPacketCursor = null,
    sessionPoints = [],
    playTimer = null;
  const safe =
    (fn) =>
    async (...args) => {
      try {
        return await fn(...args);
      } catch (e) {
        error(e);
      }
    };
  function download(content, name, type = "text/plain") {
    const url = URL.createObjectURL(new Blob([content], { type }));
    const a = document.createElement("a");
    a.href = url;
    a.download = name;
    a.click();
    setTimeout(() => URL.revokeObjectURL(url), 1000);
  }
  async function report(args) {
    const r = await v2("report", args);
    download(r.html, r.filename, "text/html");
  }
  document.querySelectorAll("[data-view]").forEach(
    (button) =>
      (button.onclick = safe(async () => {
        view = button.dataset.view;
        stopPlay();
        document
          .querySelectorAll(".workspace")
          .forEach((p) => (p.hidden = p.id !== view + "-view"));
        document.querySelectorAll("[data-view]").forEach((b) => {
          const active = b === button;
          b.classList.toggle("active", active);
          b.setAttribute("aria-pressed", String(active));
        });
        await loadView();
      })),
  );
  async function loadView() {
    if (view === "map") await loadMap();
    if (view === "captures") await loadSessions();
    if (view === "alerts") await loadAlerts();
    if (view === "sensors") await loadSensors();
    if (view === "health") await loadHealth();
    if (view === "research") await loadResearchSources();
  }
  function color(n) {
    return n.status === "responding"
      ? "#cae68a"
      : n.kind === "sensor"
        ? "#92c7ca"
        : n.status === "imported"
          ? "#b9a8d4"
          : "#a7b4aa";
  }
  function filteredNodes() {
    const q = $("#map-search").value.toLowerCase(),
      kind = $("#map-kind").value;
    return (overview?.nodes || [])
      .filter((n) => n.kind !== "sensor")
      .filter(
        (n) =>
          kind === "all" ||
          (kind === "ble" ? n.id.startsWith("ble:") : !n.id.startsWith("ble:")),
      )
      .filter((n) => JSON.stringify(n).toLowerCase().includes(q));
  }
  function drawMap() {
    if (!overview) return;
    const focusedId = document.activeElement?.getAttribute("data-node");
    const matches = filteredNodes(),
      pages = Math.max(1, Math.ceil(matches.length / 36));
    mapPage = Math.min(mapPage, pages - 1);
    const devices = matches.slice(mapPage * 36, (mapPage + 1) * 36),
      ids = new Set(devices.map((n) => n.id));
    const edges = overview.edges.filter((e) => ids.has(e.to)),
      sourceIds = new Set(["local", ...edges.map((e) => e.from)]);
    const sources = overview.nodes.filter(
      (n) => n.kind === "sensor" && sourceIds.has(n.id),
    );
    const positions = new Map();
    sources.forEach((n, i) => positions.set(n.id, [120, 90 + i * 90]));
    devices.forEach((n, i) =>
      positions.set(n.id, [350 + (i % 4) * 195, 70 + Math.floor(i / 4) * 74]),
    );
    const height = Math.max(
      700,
      100 + sources.length * 90,
      100 + Math.ceil(devices.length / 4) * 74,
    );
    $("#network-map").setAttribute("data-height", String(height));
    if (mapView[2] === 1100 && mapView[0] === 0) mapView[3] = height;
    const lines = edges
      .map((e) => {
        const a = positions.get(e.from),
          b = positions.get(e.to);
        if (!a || !b) return "";
        return `<path d="M${a[0]} ${a[1]} C220 ${a[1]},240 ${b[1]},${b[0]} ${b[1]}" fill="none" stroke="#344d3c" stroke-width="1.5" ${e.kind.includes("Imported") ? 'stroke-dasharray="5 5"' : ""}><title>${esc(e.kind)}</title></path>`;
      })
      .join("");
    const nodes = [...sources, ...devices]
      .map((n) => {
        const [x, y] = positions.get(n.id),
          isSensor = n.kind === "sensor",
          selected = n.id === selectedNode;
        const shape = isSensor
          ? `<rect x="-19" y="-19" width="38" height="38" rx="4"`
          : n.status !== "responding" && n.status !== "imported"
            ? '<path d="M0 -16L16 0L0 16L-16 0Z"'
            : `<circle r="${selected ? 18 : 13}"`;
        return `<g class="map-node" data-node="${esc(n.id)}" tabindex="0" role="button" aria-label="Inspect ${esc(n.label)}; ${esc(n.status || "unknown")}" transform="translate(${x} ${y})"><title>${esc(n.label)} · ${esc(n.host || n.kind)} · ${esc(n.status || "unknown")}</title>${shape} fill="#17271c" stroke="${color(n)}" stroke-width="${selected ? 4 : 2}"/><text text-anchor="middle" y="32" fill="#e9eee6" font-family="system-ui" font-size="11">${esc(String(n.label).slice(0, 24))}</text><text text-anchor="middle" y="46" fill="#acbcb0" font-family="system-ui" font-size="10">${esc(n.host || n.status || "observer")}</text></g>`;
      })
      .join("");
    $("#network-map").innerHTML = lines + nodes;
    applyView();
    $("#network-map")
      .querySelectorAll("[data-node]")
      .forEach((node) => {
        node.onclick = safe(() => inspect(node.dataset.node));
        node.onkeydown = (e) => {
          if (e.key === "Enter" || e.key === " ") {
            e.preventDefault();
            safe(() => inspect(node.dataset.node))();
          }
        };
      });
    if (focusedId) {
      const focusNode = [
        ...$("#network-map").querySelectorAll("[data-node]"),
      ].find((n) => n.dataset.node === focusedId);
      focusNode?.focus();
    }
    $("#map-count").textContent =
      `${matches.length} matching device observations · page ${mapPage + 1}/${pages} · ${sources.length} observers. ${overview.counts.unread_alerts} unacknowledged alerts.`;
    $("#map-prev").disabled = mapPage === 0;
    $("#map-next").disabled = mapPage >= pages - 1;
  }
  function applyView() {
    $("#network-map").setAttribute("viewBox", mapView.join(" "));
  }
  let mapRequest = 0;
  async function loadMap() {
    const request = ++mapRequest;
    const selector = $("#map-network").value;
    const data = await v2(
      "overview",
      selector === "active" ? {} : { network_id: selector },
    );
    if (request !== mapRequest || selector !== $("#map-network").value) return;
    if (
      selector === "active" &&
      data.runtime?.network?.id !== undefined &&
      data.runtime.network.id !== data.active_network_id
    ) {
      setTimeout(() => safe(loadMap)(), 100);
      return;
    }
    const changed = overview?.network?.id !== data.network?.id;
    overview = data;
    const select = $("#map-network");
    select.innerHTML =
      '<option value="active">Current Wi-Fi</option>' +
      (data.network_contexts || [])
        .map(
          (n) =>
            `<option value="${esc(n.id)}">${esc(n.label)} · ${esc(date(n.created_at))} · ${n.devices} devices</option>`,
        )
        .join("");
    select.value = selector;
    if (changed) {
      selectedNode = null;
      mapPage = 0;
      mapView = [0, 0, 1100, 700];
      $("#map-search").value = "";
      $("#map-kind").value = "all";
      $("#node-title").textContent = "Inspect a device";
      $("#node-summary").textContent =
        "Network context changed. Select a device from the updated map.";
      $("#node-actions").innerHTML = "";
      $("#node-evidence").innerHTML = "";
      $("#node-timeline").innerHTML = "";
    }
    const network = data.network || {},
      runtime = data.runtime || {},
      discovery = runtime.discovery || {};
    let status = data.historical ? "Historical context: " : "Current context: ";
    status +=
      (network.label || "Network unknown") +
      (network.address ? " · phone " + network.address : "");
    if (!data.historical) {
      if (!network.connected)
        status += " · Wi-Fi disconnected or not yet detected";
      else if (
        discovery.running &&
        discovery.network_id === data.active_network_id
      )
        status += ` · discovering ${discovery.checked}/${discovery.total} addresses · ${discovery.found.length} found`;
      else if (runtime.network_pending)
        status += " · waiting for the previous discovery to finish";
      else if (runtime.network_failure)
        status +=
          " · " +
          runtime.network_failure.code +
          ": " +
          runtime.network_failure.message;
      else
        status += ` · map refresh 5s · next discovery in ${runtime.next_discovery_in_seconds ?? 0}s`;
    } else status += " · showing prior observations, not current reachability";
    $("#map-network-status").textContent = status;
    $("#map-discover").disabled =
      !!data.historical || !!discovery.running || !network.connected;
    $("#map-network-reset").disabled = !!data.historical;
    drawMap();
  }
  $("#map-network").onchange = safe(loadMap);
  $("#map-discover").onclick = safe(async () => {
    await api("/api/discover", {});
    await loadMap();
  });
  $("#map-network-reset").onclick = safe(async () => {
    $("#map-network").value = "active";
    await api("/api/network/reset", {});
    await loadMap();
  });
  async function inspect(id) {
    selectedNode = id;
    drawMap();
    const node = overview.nodes.find((n) => n.id === id);
    if (!node) return;
    $("#node-title").textContent = node.label;
    $("#node-summary").textContent =
      `${node.host || "Observer"} · ${node.kind} · ${node.status || "unknown"}${node.observed_at ? " · " + date(node.observed_at) : ""}`;
    $("#node-evidence").innerHTML =
      `<details><summary>Recorded evidence</summary><pre>${esc(JSON.stringify(node.evidence, null, 2))}</pre></details>`;
    $("#node-actions").innerHTML = "";
    $("#node-timeline").innerHTML = '<p class="hint">Loading timeline…</p>';
    if (id.startsWith("ip:") || id.startsWith("ble:")) {
      const button = document.createElement("button");
      button.textContent = "Download device report";
      button.className = "secondary";
      button.onclick = safe(() => report({ entity: id }));
      $("#node-actions").append(button);
      const details = await v2("detail", { entity: id });
      if (selectedNode !== id) return;
      $("#node-timeline").innerHTML = details.history.length
        ? details.history
            .map(
              (h) =>
                `<details><summary>${esc(date(h.at))}</summary><pre>${esc(JSON.stringify(h.data, null, 2))}</pre></details>`,
            )
            .join("")
        : '<p class="hint">No identity snapshots yet.</p>';
      if (details.events.length)
        $("#node-timeline").innerHTML +=
          "<h3>Recent network changes</h3>" +
          details.events
            .slice(0, 30)
            .map(
              (e) =>
                `<p class="compact">${esc(e.message)}<br><time>${esc(date(e.at))}</time></p>`,
            )
            .join("");
    } else
      $("#node-timeline").innerHTML =
        '<p class="hint">This node identifies an observation source.</p>';
  }
  $("#map-refresh").onclick = safe(loadMap);
  for (const id of ["map-search", "map-kind"])
    $("#" + id).addEventListener(
      id === "map-search" ? "input" : "change",
      () => {
        mapPage = 0;
        drawMap();
      },
    );
  $("#map-prev").onclick = () => {
    mapPage--;
    drawMap();
  };
  $("#map-next").onclick = () => {
    mapPage++;
    drawMap();
  };
  function zoom(factor) {
    const [x, y, w, h] = mapView;
    const nw = Math.min(4400, Math.max(350, w * factor)),
      nh = (h * nw) / w;
    mapView = [x + (w - nw) / 2, y + (h - nh) / 2, nw, nh];
    applyView();
  }
  $("#map-zoom-in").onclick = () => zoom(0.8);
  $("#map-zoom-out").onclick = () => zoom(1.25);
  $("#map-reset").onclick = () => {
    mapView = [0, 0, 1100, Number($("#network-map").dataset.height) || 700];
    applyView();
  };
  let pan = null;
  $("#network-map").addEventListener("pointerdown", (e) => {
    if (e.target.closest("[data-node]")) return;
    pan = { x: e.clientX, y: e.clientY, view: [...mapView] };
    e.currentTarget.setPointerCapture(e.pointerId);
  });
  $("#network-map").addEventListener("pointermove", (e) => {
    if (!pan) return;
    const rect = e.currentTarget.getBoundingClientRect(),
      scale = Math.max(pan.view[2] / rect.width, pan.view[3] / rect.height);
    mapView = [
      pan.view[0] - (e.clientX - pan.x) * scale,
      pan.view[1] - (e.clientY - pan.y) * scale,
      pan.view[2],
      pan.view[3],
    ];
    applyView();
  });
  for (const name of ["pointerup", "pointercancel"])
    $("#network-map").addEventListener(name, () => {
      pan = null;
    });
  $("#network-map").addEventListener(
    "wheel",
    (e) => {
      e.preventDefault();
      zoom(e.deltaY > 0 ? 1.1 : 0.9);
    },
    { passive: false },
  );

  function activeSession() {
    return Number($("#session-select").value) || 0;
  }
  async function loadSessions(preferred) {
    sessions = await v2("sessions");
    for (const id of ["session-select", "compare-a", "compare-b"]) {
      const select = $("#" + id),
        previous = select.value;
      select.innerHTML =
        (id === "session-select"
          ? '<option value="0">All sessions</option>'
          : '<option value="">Select a session</option>') +
        sessions
          .map(
            (s) =>
              `<option value="${s.id}">${esc(s.name || s.source)} · ${s.packets} packets · ${esc(date(s.imported_at))}</option>`,
          )
          .join("");
      select.value =
        id === "session-select" ? String(preferred || previous || 0) : previous;
    }
    updateSessionInfo();
    packetCursor = null;
    await loadPackets();
  }
  function updateSessionInfo() {
    const s = sessions.find((s) => s.id === activeSession());
    $("#session-info").textContent = s
      ? `${s.devices} addresses · ${s.packets} packets · ${s.rejected} unmatched lines · sensor ${s.sensor} · source ${s.source}`
      : "Packet search spans all stored sessions.";
    $("#session-source").disabled = !s;
    $("#session-report").disabled = !s;
    stopPlay();
    $("#session-slider").disabled = true;
    $("#session-play").disabled = true;
    $("#session-chart").innerHTML = "";
  }
  async function importSession(content, source) {
    if (new TextEncoder().encode(content).length > 1000000)
      throw Error("Choose an export under 1 MB");
    const args = { text: content, source, name: $("#session-name").value };
    if ($("#session-policy").value === "new")
      args.session_key = crypto.randomUUID();
    const r = await v2("import", args, true);
    $("#session-import-result").textContent =
      `Session ${r.session_id}: ${r.imported} packets imported, ${r.duplicates} already stored, ${r.rejected_lines} unmatched lines${r.limit_reached ? " · packet limit reached" : ""}.`;
    await loadSessions(r.session_id);
  }
  $("#session-file").onchange = safe(async (e) => {
    const file = e.target.files[0];
    if (file) {
      if (file.size > 1000000) throw Error("Choose an export under 1 MB");
      await importSession(await file.text(), file.name);
    }
  });
  $("#session-paste").onclick = safe(() =>
    importSession($("#session-text").value, "pasted log"),
  );
  $("#sessions-refresh").onclick = safe(() => loadSessions());
  $("#session-select").onchange = safe(async () => {
    updateSessionInfo();
    packetCursor = null;
    await loadPackets();
  });
  async function loadPackets() {
    const args = {
      session_id: activeSession(),
      q: $("#packet-query").value,
      address: $("#packet-address").value.trim(),
      rssi_min: Number($("#packet-rssi").value),
      limit: 50,
    };
    if (packetCursor) args.cursor = packetCursor;
    const r = await v2("packets", args);
    nextPacketCursor = r.next_cursor;
    $("#packet-rows").innerHTML =
      r.packets
        .map(
          (p) =>
            `<tr><td>${p.seq + 1} / ${p.session_id}</td><td>${esc(p.data.time)}<br>${(p.elapsed_ms / 1000).toFixed(3)} s</td><td>${esc(p.name || "Unnamed")}<br>${esc(p.address)}</td><td>${p.rssi} dBm</td><td><details><summary>${esc(p.raw.slice(0, 24))}${p.raw.length > 24 ? "…" : ""}</summary><pre>${esc(JSON.stringify(p.data.advertisement, null, 2))}</pre><code>${esc(p.raw)}</code></details></td></tr>`,
        )
        .join("") ||
      '<tr><td colspan="5">No packets match. Import a real scan export or change the filters.</td></tr>';
    $("#packet-count").textContent =
      `${r.packets.length} packets on this page${nextPacketCursor ? " · older matching packets available" : ""}`;
    $("#packet-next").disabled = !nextPacketCursor;
  }
  $("#packet-form").onsubmit = safe(async (e) => {
    e.preventDefault();
    packetCursor = null;
    await loadPackets();
  });
  $("#packet-reset").onclick = safe(async () => {
    packetCursor = null;
    await loadPackets();
  });
  $("#packet-next").onclick = safe(async () => {
    packetCursor = nextPacketCursor;
    await loadPackets();
  });
  $("#session-source").onclick = safe(async () => {
    const r = await v2("source", { session_id: activeSession() });
    if (r.text === null) throw Error(r.note);
    download(r.text, `atlas-session-${activeSession()}.txt`);
  });
  $("#session-report").onclick = safe(() =>
    report({ session_id: activeSession() }),
  );
  $("#compare-run").onclick = safe(async () => {
    const r = await v2("compare", {
      a: $("#compare-a").value,
      b: $("#compare-b").value,
    });
    const right = new Map(r.right.map((s) => [s.address, s]));
    const delta = r.left
      .filter((s) => right.has(s.address))
      .map(
        (s) =>
          `${s.address}: ${s.mean_rssi} → ${right.get(s.address).mean_rssi} dBm mean`,
      );
    $("#compare-result").innerHTML =
      `<p>${r.added_addresses.length} added addresses · ${r.missing_addresses.length} missing · ${r.shared_addresses.length} shared</p><pre>${esc(["Added: " + (r.added_addresses.join(", ") || "none"), "Missing: " + (r.missing_addresses.join(", ") || "none"), ...delta].join("\n"))}</pre><p class="hint">${esc(r.identity_note)}</p>`;
  });
  function stopPlay() {
    if (playTimer) clearInterval(playTimer);
    playTimer = null;
    $("#session-play").textContent = "Play";
  }
  function sessionX(p) {
    const first = sessionPoints[0].elapsed_ms,
      last = sessionPoints.at(-1).elapsed_ms;
    return 40 + (820 * (p.elapsed_ms - first)) / Math.max(1, last - first);
  }
  function replayPosition() {
    const i = Number($("#session-slider").value),
      p = sessionPoints[i];
    if (!p) return;
    $("#session-position").textContent =
      `${i + 1}/${sessionPoints.length} · ${p.name || p.address} · ${p.rssi} dBm · elapsed ${(p.elapsed_ms / 1000).toFixed(3)} s`;
    const marker = $("#session-marker");
    if (marker) {
      marker.setAttribute("x1", String(sessionX(p)));
      marker.setAttribute("x2", String(sessionX(p)));
    }
  }
  $("#session-replay-load").onclick = safe(async () => {
    stopPlay();
    const r = await v2("replay", {
      session_id: activeSession(),
      address: $("#packet-address").value.trim(),
    });
    sessionPoints = r.points;
    $("#session-replay-note").textContent =
      `Showing ${r.points.length}/${r.total} packets. ${r.time_basis}${r.truncated ? " · Replay limit reached." : ""}`;
    $("#session-slider").disabled = !r.points.length;
    $("#session-slider").max = String(Math.max(0, r.points.length - 1));
    $("#session-slider").value = "0";
    $("#session-play").disabled = r.points.length < 2;
    const groups = new Map();
    r.points.forEach((p) => {
      if (!groups.has(p.address)) groups.set(p.address, []);
      groups.get(p.address).push(p);
    });
    const colors = ["#cae68a", "#92c7ca", "#ffca92", "#b9a8d4"];
    let i = 0;
    $("#session-chart").innerHTML =
      '<path d="M40 20V185H860" fill="none" stroke="#acbcb0"/><text x="2" y="25" fill="#acbcb0" font-size="12">20</text><text x="0" y="185" fill="#acbcb0" font-size="12">−127</text>' +
      [...groups.entries()]
        .map(
          ([address, points]) =>
            `<polyline points="${points.map((p) => `${sessionX(p)},${185 - ((p.rssi + 127) * 165) / 147}`).join(" ")}" fill="none" stroke="${colors[i++ % colors.length]}" stroke-width="2"><title>${esc(address)}</title></polyline>`,
        )
        .join("") +
      '<line id="session-marker" x1="40" x2="40" y1="20" y2="185" stroke="#ffca92"/>';
    replayPosition();
  });
  $("#session-slider").oninput = () => {
    stopPlay();
    replayPosition();
  };
  $("#session-play").onclick = () => {
    if (playTimer) {
      stopPlay();
      return;
    }
    if (Number($("#session-slider").value) >= sessionPoints.length - 1)
      $("#session-slider").value = "0";
    $("#session-play").textContent = "Pause";
    playTimer = setInterval(() => {
      const s = $("#session-slider");
      if (Number(s.value) + 1 >= sessionPoints.length) {
        stopPlay();
        return;
      }
      s.value = String(Number(s.value) + 1);
      replayPosition();
    }, 150);
  };

  async function loadAlerts() {
    overview = await v2("overview");
    const rule = overview.rules;
    $("#rules-enabled").checked = !!rule.enabled;
    $("#rules-missed").value = rule.missed_checks;
    $("#rules-stale").value = rule.stale_minutes;
    $("#alert-list").innerHTML =
      overview.alerts
        .map(
          (a) =>
            `<article class="panel alert-card ${a.acknowledged ? "acknowledged" : ""}"><h3>${esc(a.kind.replaceAll("_", " "))} · ${esc(a.entity)}</h3><p>${esc(a.message)}</p><time>${esc(date(a.at))}</time>${a.acknowledged ? '<p class="hint">Acknowledged</p>' : `<p><button class="alert-ack secondary" data-alert="${a.id}">Acknowledge</button></p>`}</article>`,
        )
        .join("") ||
      "<p>No alerts recorded. Collection and map refreshes build the evidence.</p>";
    document.querySelectorAll("[data-alert]").forEach(
      (b) =>
        (b.onclick = safe(async () => {
          await v2("ack", { id: Number(b.dataset.alert) }, true);
          await loadAlerts();
        })),
    );
  }
  $("#alerts-refresh").onclick = safe(loadAlerts);
  $("#rules-form").onsubmit = safe(async (e) => {
    e.preventDefault();
    await v2(
      "rules",
      {
        enabled: $("#rules-enabled").checked,
        missed_checks: Number($("#rules-missed").value),
        stale_minutes: Number($("#rules-stale").value),
      },
      true,
    );
    $("#rules-result").textContent = "Alert rules saved.";
  });
  async function loadSensors() {
    const sensors = await v2("sensors");
    $("#sensor-list").innerHTML =
      sensors
        .map(
          (s) =>
            `<article class="panel"><h3>${esc(s.name)}</h3><p>ID ${esc(s.id)} · ${s.enabled ? "Enabled" : "Revoked"}<br>Last submission: ${s.last_seen ? esc(date(s.last_seen)) : "none"}</p>${s.enabled ? `<button class="secondary" data-revoke="${esc(s.id)}">Revoke credential</button>` : ""}</article>`,
        )
        .join("") || "<p>No external sensors registered.</p>";
    document.querySelectorAll("[data-revoke]").forEach(
      (b) =>
        (b.onclick = safe(async () => {
          await v2("sensor_revoke", { id: b.dataset.revoke }, true);
          await loadSensors();
        })),
    );
  }
  $("#sensor-form").onsubmit = safe(async (e) => {
    e.preventDefault();
    const s = await v2(
      "sensor_create",
      { name: $("#sensor-name").value },
      true,
    );
    $("#sensor-created").innerHTML =
      `<p>Save this credential; it is shown only now.</p><code class="token">${esc(s.token)}</code><p class="hint">${esc(s.note)}</p>`;
    await loadSensors();
  });
  async function loadHealth() {
    const h = await v2("health"),
      running = [
        h.runtime.scanning ? "TCP" : null,
        h.runtime.discovery.running ? "Discovery" : null,
        h.runtime.enrichment.running ? "Enrichment" : null,
      ].filter(Boolean);
    $("#health-summary").innerHTML =
      `<article class="panel"><h3>Database</h3><strong class="health-number">${(h.database_bytes / 1048576).toFixed(2)} MiB</strong><p>Journal: ${esc(h.journal_mode)} · history retained</p></article><article class="panel"><h3>Collectors</h3><strong class="health-number">${running.length ? esc(running.join(", ")) : "Idle"}</strong><p>${h.stale_or_unobserved_targets} targets older than 30 minutes or unobserved · checks every ${h.runtime.interval}s</p></article><article class="panel"><h3>Process</h3><p>Started ${esc(date(h.runtime.started_at))}<br>Last error: ${esc(h.runtime.last_error || h.runtime.investigation_error || "none")}</p></article><article class="panel"><h3>Bounded work</h3><p>${h.limits.packet_page} packets/page · ${h.limits.replay_points} replay points<br>${esc(h.engine)}</p></article>`;
    const failures = [
      h.runtime.last_failure,
      h.runtime.metrics_failure,
      h.runtime.investigation_failure,
      h.runtime.discovery.failure,
      h.runtime.enrichment.failure,
    ].filter(Boolean);
    if (failures.length)
      $("#health-summary").innerHTML +=
        '<article class="panel"><h3>Failure diagnostics</h3><pre>' +
        esc(
          failures
            .map((f) =>
              window.AtlasAPI.describe(new window.AtlasAPI.AtlasFailure(f)),
            )
            .join("\n\n"),
        ) +
        "</pre></article>";
    const observer = h.runtime.observer;
    if (observer) $("#health-summary").innerHTML += `<article class="panel"><h3>Observation freshness</h3><p>Last successful run: ${esc(observer.last_successful_collection || "none")}<br>Wall clock: ${esc(observer.wall_clock_at)}<br>Age: ${esc(observer.seconds_since_success === null ? "unknown" : Math.round(observer.seconds_since_success) + " seconds")} · ${observer.overdue ? "overdue" : "within interval or unknown"}</p><p>Android may suspend Termux. Successful run completion does not mean a device responded.</p></article>`;
    $("#health-runs").innerHTML =
      h.runs
        .map(
          (r) =>
            `<tr><td>${esc(r.kind)}</td><td>${esc(date(r.finished_at))}</td><td>${(r.duration_ms / 1000).toFixed(2)}s</td><td>${r.items}</td><td>${esc(r.error || "none")}</td></tr>`,
        )
        .join("") ||
      '<tr><td colspan="5">No collection runs recorded since this update.</td></tr>';
  }
  $("#health-refresh").onclick = safe(loadHealth);
  $("#inventory-report").onclick = safe(() => report({}));
  document.addEventListener("visibilitychange", () => {
    if (!document.hidden && view === "map") safe(loadMap)();
  });
  window.addEventListener("focus", () => {
    if (view === "map") safe(loadMap)();
  });
  safe(loadMap)();
  setInterval(() => {
    if (!document.hidden && view === "map") safe(loadMap)();
  }, 5000);

  let researchSources = null,
    lastResearch = null,
    lastResearchArgs = null;
  const numeric = (v) =>
    typeof v === "number" && Number.isFinite(v) ? v.toFixed(3) : "undefined";
  async function loadResearchSources() {
    const selector = $("#research-network"),
      scope = selector.value;
    researchSources = await v2(
      "research_sources",
      scope && scope !== "active" ? { network_id: scope } : {},
    );
    const target = $("#research-target").value,
      session = $("#research-session").value;
    selector.innerHTML =
      '<option value="active">Current network</option>' +
      researchSources.contexts
        .map(
          (n) =>
            `<option value="${esc(n.id)}">${esc(n.label)} · ${esc(date(n.created_at))} · ${n.devices} targets</option>`,
        )
        .join("");
    selector.value = scope || "active";
    $("#research-target").innerHTML =
      researchSources.targets
        .map(
          (t) =>
            `<option value="${t.id}">${esc(t.label)} · ${esc(t.host)}</option>`,
        )
        .join("") || '<option value="">No targets in this context</option>';
    if (researchSources.targets.some((t) => String(t.id) === target))
      $("#research-target").value = target;
    $("#research-session").innerHTML =
      researchSources.sessions
        .map(
          (s) =>
            `<option value="${s.id}">${esc(s.name || s.source)} · ${s.packets} packets</option>`,
        )
        .join("") ||
      '<option value="">Import a scan in Capture lab first</option>';
    if (researchSources.sessions.some((s) => String(s.id) === session))
      $("#research-session").value = session;
  }
  function researchArgs() {
    const args = {
      dataset: $("#research-dataset").value,
      window: Number($("#research-window").value),
    };
    if (args.dataset === "network") {
      args.target_id = Number($("#research-target").value);
      args.port = Number($("#research-port").value);
      args.threshold_ms = Number($("#research-threshold-ms").value);
      if (!args.target_id)
        throw Error(
          "Select a monitored target or a historical network context.",
        );
    } else {
      args.session_id = Number($("#research-session").value);
      args.address = $("#research-address").value.trim();
      args.threshold_db = Number($("#research-threshold-db").value);
      if (!args.session_id)
        throw Error(
          "Import a supported capture in Capture lab, then select it here.",
        );
    }
    return args;
  }
  function drawResearchPlot(points, valueField, label) {
    if (!points?.length)
      return '<p class="hint">No eligible samples for this plot.</p>';
    const times = points.map((p) => p.elapsed_ms),
      values = points.map((p) => p[valueField]);
    const minT = Math.min(...times),
      spanT = Math.max(1, Math.max(...times) - minT),
      minV = Math.min(...values),
      spanV = Math.max(1, Math.max(...values) - minV);
    const coordinates = points
      .map(
        (p) =>
          `${40 + ((p.elapsed_ms - minT) / spanT) * 720},${180 - ((p[valueField] - minV) / spanV) * 150}`,
      )
      .join(" ");
    return `<svg viewBox="0 0 800 220" role="img" aria-label="${esc(label)}" class="research-chart"><path d="M40 20V180H760" fill="none" stroke="var(--muted)"/><polyline points="${coordinates}" fill="none" stroke="var(--accent)" stroke-width="2"/><text x="40" y="210" fill="var(--muted)" font-size="12">${esc(label)} · elapsed recorded time · ${numeric(minV)} to ${numeric(minV + spanV)}</text></svg>`;
  }
  function renderResearch(result) {
    const network = result.dataset === "network";
    let html = `<section class="panel"><h3>Method & provenance</h3><p>${esc(result.method_version)} · ${esc(result.backend)}</p><p class="hint">${result.interpretation.map(esc).join("<br>")}</p><details><summary>Reproduction settings and source</summary><pre>${esc(
      JSON.stringify(
        {
          settings: result.settings,
          build: result.build,
          session: result.session,
          target: result.target,
          provenance: result.provenance,
          limits: result.limits,
        },
        null,
        2,
      ),
    )}</pre></details></section>`;
    if (network) {
      const signal = result.signal;
      html += `<section class="panel"><h3>${esc(result.target.label)} · TCP ${result.port}</h3><p>${signal.samples} accepted samples · mean ${numeric(signal.mean_ms)} ms · sample SD ${numeric(signal.sample_stddev_ms)} ms · p95 ${numeric(signal.p95_ms)} ms</p><p class="hint">${result.missing_selected_service_or_latency} observations lacked accepted timing for this port · ${result.excluded_samples} invalid/out-of-range samples excluded</p>${drawResearchPlot(result.plot, "latency_ms", "TCP connect latency (ms)")}<h4>Quality notes</h4><p>${result.quality.length ? result.quality.map(esc).join("<br>") : "No listed quality flags."}</p><h4>Candidate timing shifts</h4><pre>${esc(JSON.stringify(result.candidate_timing_shifts, null, 2))}</pre></section>`;
    } else {
      html += `<p class="hint">${result.analyzed_packets} analyzed packets · ${result.devices_in_sample} sampled addresses${result.packet_limit_reached ? " · packet limit reached" : ""}${result.device_limit_reached ? " · device limit reached; select an exact address" : ""}</p>`;
      html +=
        result.devices
          .map((d) => {
            const signal = d.signal;
            return `<section class="panel"><h3>${esc(d.address)}</h3><p>${signal.samples} samples · mean ${numeric(signal.mean_dbm)} dBm · sample SD ${numeric(signal.sample_stddev_db)} dB · median ${numeric(signal.median_dbm)} dBm</p>${drawResearchPlot(d.plot, "rssi", "RSSI (dBm)")}<h4>Observed RSSI distribution</h4><div class="research-histogram">${signal.histogram.map((b) => `<span title="${b.dbm} dBm: ${b.count} samples">${b.dbm}: ${b.count}</span>`).join(" ")}</div><p>Lag-1 packet correlation: ${numeric(signal.lag1_packet_correlation)} · RSSI distribution entropy: ${numeric(signal.rssi_entropy_bits)} bits</p><p>Payloads: ${d.payload.unique_payloads} unique · bit-change fraction ${numeric(d.payload.bit_change_fraction)} · ${d.payload.length_changes} length changes</p><h4>Quality notes</h4><p>${d.quality.length ? d.quality.map(esc).join("<br>") : "No listed quality flags."}</p><details><summary>Timing and candidate signal shifts</summary><pre>${esc(JSON.stringify({ timing: d.timing, candidates: d.candidate_signal_shifts }, null, 2))}</pre></details></section>`;
          })
          .join("") || "<p>No matching packets. Check the address filter.</p>";
    }
    $("#research-results").innerHTML = html;
    $("#research-json").disabled = false;
    $("#research-html").disabled = false;
  }
  $("#research-refresh").onclick = safe(loadResearchSources);
  $("#research-network").onchange = safe(loadResearchSources);
  $("#research-dataset").onchange = () => {
    const network = $("#research-dataset").value === "network";
    $("#research-network-fields").hidden = !network;
    $("#research-ble-fields").hidden = network;
  };
  $("#research-form").onsubmit = safe(async (event) => {
    event.preventDefault();
    const args = researchArgs();
    lastResearch = null;
    lastResearchArgs = null;
    $("#research-json").disabled = true;
    $("#research-html").disabled = true;
    $("#research-results").innerHTML = "";
    $("#research-run").disabled = true;
    $("#research-status").textContent = "Analyzing stored evidence…";
    try {
      const result = await v2("research", args);
      lastResearch = result;
      lastResearchArgs = args;
      if (result.dataset === "network")
        lastResearchArgs.through_id =
          result.provenance.through_observation_id ?? 0;
      renderResearch(result);
      $("#research-status").textContent =
        "Analysis complete. These are descriptive measurements, not causal conclusions.";
    } catch (failure) {
      $("#research-status").textContent =
        "Analysis failed; see the diagnostic message above.";
      throw failure;
    } finally {
      $("#research-run").disabled = false;
    }
  });
  $("#research-json").onclick = () => {
    if (lastResearch)
      download(
        JSON.stringify(lastResearch, null, 2),
        "atlas-research.json",
        "application/json",
      );
  };
  $("#research-html").onclick = safe(async () => {
    if (!lastResearchArgs) return;
    const report = await v2("research_report", lastResearchArgs);
    download(report.html, report.filename, "text/html");
  });
  $("#research-benchmark").onclick = safe(async () => {
    const button = $("#research-benchmark");
    button.disabled = true;
    $("#research-benchmark-result").textContent =
      "Running bounded local kernel benchmarks…";
    try {
      const result = await v2("research_benchmark", {}, true);
      $("#research-benchmark-result").innerHTML =
        `<p>${esc(result.backend)}</p><div class="table-wrap"><table><thead><tr><th>Kernel</th><th>Native batch ms</th><th>Rust batch ms</th><th>Rust/native</th></tr></thead><tbody>${result.results.map((r) => `<tr><td>${esc(r.kernel)}</td><td>${numeric(r.native_median_ms)}</td><td>${numeric(r.rust_reference_median_ms)}</td><td>${numeric(r.reference_over_native)}</td></tr>`).join("")}</tbody></table></div><p class="hint">${esc(result.method)}<br>${esc(result.interpretation)}</p>`;
    } catch (failure) {
      $("#research-benchmark-result").textContent =
        "Benchmark failed; see the diagnostic message above.";
      throw failure;
    } finally {
      button.disabled = false;
    }
  });
})();
