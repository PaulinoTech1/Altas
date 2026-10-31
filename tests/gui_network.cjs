/* Simulate browser responses for network switches; server tests cover real SQLite scoping. */
const { JSDOM, VirtualConsole } = require("jsdom");
const assert = require("node:assert/strict");
const base = process.env.ATLAS_TEST_URL || "http://127.0.0.1:8877";
const pause = (ms) => new Promise((r) => setTimeout(r, ms));
async function until(check) {
  for (let i = 0; i < 150; i++) {
    if (check()) return;
    await pause(50);
  }
  throw Error("Network GUI state did not update");
}
(async () => {
  const baseline = await (await fetch(base + "/api/v2/overview", {headers:{"X-Atlas-Control":"1".repeat(64)}})).json();
  const contexts = {
    A: {
      id: "A",
      label: "Old context",
      address: "192.168.1.10",
      cidr: "192.168.1.0/24",
      created_at: "2026-10-30T12:00:00Z",
      connected: true,
      devices: 1,
    },
    B: {
      id: "B",
      label: "New context",
      address: "10.0.0.10",
      cidr: "10.0.0.0/24",
      created_at: "2026-10-31T12:00:00Z",
      connected: true,
      devices: 1,
    },
  };
  let active = "A",
    mocking = false,
    delayNext = false,
    pending = null,
    responses = 0;
  const errors = [];
  function snapshot(scope) {
    const data = structuredClone(baseline),
      id = scope === "A" ? "ip:1" : "ip:2";
    data.network = contexts[scope];
    data.active_network_id = active;
    data.network_contexts = Object.values(contexts);
    data.historical = scope !== active;
    data.runtime.network = contexts[active];
    data.runtime.network_pending = false;
    data.runtime.discovery = { running: false };
    data.nodes = [
      baseline.nodes.find((n) => n.id === "local"),
      {
        id,
        label: scope === "A" ? "Old network device" : "New network device",
        host: scope === "A" ? "192.168.1.20" : "10.0.0.20",
        kind: "IP device",
        status: "responding",
        evidence: {},
      },
    ];
    data.edges = [{ from: "local", to: id, kind: "TCP observation" }];
    return data;
  }
  const virtualConsole = new VirtualConsole();
  virtualConsole.on("jsdomError", (e) => {
    if (e.type !== "css-parsing") errors.push(e.message);
  });
  const dom = await JSDOM.fromURL(base, {
    resources: "usable",
    runScripts: "dangerously",
    pretendToBeVisual: true,
    virtualConsole,
    beforeParse(w) {
      w.TextEncoder = TextEncoder;
      w.URL.createObjectURL = () => "blob:fixture";
      w.URL.revokeObjectURL = () => {};
      w.fetch = (path, opts) => {
        const url = new URL(path, base);
        if (mocking && url.pathname === "/api/v2/overview") {
          const data = snapshot(
            url.searchParams.has("network_id")
              ? url.searchParams.get("network_id")
              : active,
          );
          const response = {
            ok: true,
            status: 200,
            json: async () => {
              responses++;
              return data;
            },
            headers: { get: () => null },
          };
          if (delayNext) {
            delayNext = false;
            return new Promise(
              (resolve) => (pending = () => resolve(response)),
            );
          }
          return Promise.resolve(response);
        }
        return fetch(url, opts);
      };
    },
  });
  const w = dom.window,
    d = w.document;
  d.querySelector("#control-token").value="1".repeat(64);
  d.querySelector("#control-unlock").dispatchEvent(new w.Event("submit",{cancelable:true}));
  await until(() => d.querySelector('[data-node="ip:1"]'));
  mocking = true;
  d.querySelector("#map-refresh").click();
  await until(() =>
    d.querySelector("#map-network-status").textContent.includes("Old context"),
  );
  d.querySelector('[data-node="ip:1"]').dispatchEvent(
    new w.MouseEvent("click", { bubbles: true }),
  );
  await until(
    () => d.querySelector("#node-title").textContent === "Old network device",
  );
  active = "B";
  d.querySelector("#map-refresh").click();
  await until(
    () =>
      d.querySelector('[data-node="ip:2"]') &&
      !d.querySelector('[data-node="ip:1"]'),
  );
  assert.equal(d.querySelector("#node-title").textContent, "Inspect a device");
  d.querySelector("#map-network").value = "A";
  d.querySelector("#map-network").dispatchEvent(new w.Event("change"));
  await until(() => d.querySelector('[data-node="ip:1"]'));
  assert.match(
    d.querySelector("#map-network-status").textContent,
    /Historical context/,
  );
  d.querySelector("#map-network").value = "active";
  d.querySelector("#map-network").dispatchEvent(new w.Event("change"));
  await until(() => d.querySelector('[data-node="ip:2"]'));
  active = "A";
  delayNext = true;
  d.querySelector("#map-refresh").click();
  await until(() => pending);
  active = "B";
  const prior = responses;
  d.querySelector("#map-refresh").click();
  await until(() => responses > prior);
  pending();
  await pause(100);
  assert.ok(d.querySelector('[data-node="ip:2"]'));
  assert.equal(
    d.querySelector('[data-node="ip:1"]'),
    null,
    "An older HTTP response must not repaint the previous network",
  );
  active = "A";
  d.dispatchEvent(new w.Event("visibilitychange"));
  await until(() => d.querySelector('[data-node="ip:1"]'));
  active = "B";
  w.dispatchEvent(new w.Event("focus"));
  await until(() => d.querySelector('[data-node="ip:2"]'));
  assert.deepEqual(errors, []);
  w.close();
  process.stdout.write(
    "PASS: current network replacement, inspector reset, historical selection, stale HTTP response rejection, visibility/focus refresh.\n",
  );
})().catch((error) => {
  process.stderr.write(error.stack + "\n");
  process.exit(1);
});
