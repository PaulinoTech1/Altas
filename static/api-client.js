/* Shared HTTP/error contract. No automatic retries for mutations. */
(() => {
  class AtlasFailure extends Error {
    constructor(failure, status = 0, requestId = null) {
      super(failure.message || "The operation failed.");
      this.name = "AtlasFailure";
      this.code = failure.code || "ATLAS_INTERNAL";
      this.category = failure.category || "internal";
      this.retryable = !!failure.retryable;
      this.action =
        failure.action || "Retain the request ID and check collection health.";
      this.details = failure.details || {};
      this.status = status;
      this.requestId = requestId;
    }
  }
  let controlToken = "";
  const form = typeof document === "undefined" ? null : document.querySelector("#control-unlock");
  let unlock;
  const ready = form ? new Promise(resolve => { unlock = resolve; }) : Promise.resolve();
  if (form) form.addEventListener("submit", async event => {
    event.preventDefault();
    const value = document.querySelector("#control-token").value;
    try {
      const response = await fetch("/api/control/unlock", {method:"POST", headers:{"Content-Type":"application/json", "X-Atlas-Control":value}, body:"{}"});
      if (!response.ok) {
        const result = await response.json();
        document.querySelector("#control-error").textContent = result.error_code || "ATLAS_CONTROL_AUTH_INVALID";
        return;
      }
      controlToken=value;
      document.querySelector("#control-token").value="";
      form.hidden=true;
      unlock();
    } catch (_) { document.querySelector("#control-error").textContent="ATLAS_NETWORK_UNAVAILABLE"; }
  });
  async function request(path, data) {
    await ready;
    let response;
    try {
      response = await fetch(
        path,
        data === undefined
          ? {headers:{"X-Atlas-Control":controlToken}}
          : {
              method: "POST",
              headers: { "Content-Type": "application/json", "X-Atlas-Control":controlToken },
              body: JSON.stringify(data),
            },
      );
    } catch (_) {
      throw new AtlasFailure({
        code: "ATLAS_NETWORK_UNAVAILABLE",
        message: "Atlas could not be reached.",
        retryable: true,
        action: "Check that the localhost server is running, then retry.",
      });
    }
    let result;
    try {
      result = await response.json();
    } catch (_) {
      throw new AtlasFailure(
        {
          code: "ATLAS_RESPONSE_INVALID",
          message: "Atlas returned an invalid response.",
          action: "Check server diagnostics and retain the request ID.",
        },
        response.status,
        response.headers.get("X-Request-ID"),
      );
    }
    if (!response.ok) {
      throw new AtlasFailure(
        result.failure || {
          code: result.error_code,
          message: result.error,
          retryable: false,
        },
        response.status,
        result.request_id || response.headers.get("X-Request-ID"),
      );
    }
    return result;
  }
  function describe(error) {
    const lines = [
      `${error.code || "ATLAS_INPUT_INVALID"}: ${error.message || "The operation failed."}`,
    ];
    if (error.requestId) lines.push(`Request ID: ${error.requestId}`);
    if (error.status)
      lines.push(`HTTP ${error.status} · ${error.category || "application"}`);
    if (error.action) lines.push(`Action: ${error.action}`);
    if (error.code)
      lines.push(
        error.retryable
          ? "Retry: allowed after the cause is resolved."
          : "Retry: correct the cause first.",
      );
    if (error.details && Object.keys(error.details).length)
      lines.push(`Details: ${JSON.stringify(error.details)}`);
    return lines.join("\n");
  }
  if (typeof document !== "undefined") document.addEventListener("click", async event => {
    const link=event.target.closest("a[href='/api/backup']");
    if (!link) return;
    event.preventDefault();
    await ready;
    try {
      const response=await fetch("/api/backup",{headers:{"X-Atlas-Control":controlToken}});
      if(!response.ok) { const result=await response.json(); throw Error(result.error_code); }
      const url=URL.createObjectURL(await response.blob());
      const a=document.createElement("a");a.href=url;a.download="atlas-backup.sqlite3";a.click();setTimeout(()=>URL.revokeObjectURL(url),1000);
    } catch(error) { const box=document.querySelector("#error"); if(box) box.textContent=error.message; }
  });
  window.AtlasAPI = { request, describe, AtlasFailure };
})();
