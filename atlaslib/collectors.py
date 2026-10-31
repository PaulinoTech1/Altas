from .errors import AtlasError

"""Network observation primitives: no storage or scheduling."""
import errno
import fcntl
import struct
import hashlib
import http.client
import ipaddress
import re
import socket
import ssl
import tempfile
import xml.etree.ElementTree as ET
from urllib.parse import urlsplit
import time


def wifi_network():
    try:
        with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as sock:
            name = struct.pack("256s", b"wlan0")
            addr = socket.inet_ntoa(fcntl.ioctl(sock.fileno(), 35093, name)[20:24])
            mask = socket.inet_ntoa(fcntl.ioctl(sock.fileno(), 35099, name)[20:24])
        network = ipaddress.ip_network(addr + "/" + mask, strict=False)
        return {"address": addr, "cidr": str(network)}
    except OSError:
        return None


def device_description(host, location):
    url = urlsplit(location)
    if url.scheme != "http" or url.hostname != host or url.username or url.password:
        raise AtlasError(
            "ATLAS_METADATA_FAILED", "Description must be HTTP on the advertising IP."
        )
    ipaddress.ip_address(host)
    conn = http.client.HTTPConnection(host, url.port or 80, timeout=2)
    try:
        conn.request("GET", url.path + ("?" + url.query if url.query else "") or "/")
        response = conn.getresponse()
        if response.status != 200:
            raise AtlasError(
                "ATLAS_METADATA_FAILED", details={"http_status": response.status}
            )
        body = response.read(65537)
        if len(body) > 65536:
            raise AtlasError("ATLAS_METADATA_FAILED", details={"max_bytes": 65536})
        if b"<!DOCTYPE" in body.upper() or b"<!ENTITY" in body.upper():
            raise AtlasError("ATLAS_METADATA_FAILED", "XML entities are unsupported.")
        root = ET.fromstring(body)
        fields = {}
        allowed = {
            "friendlyName",
            "manufacturer",
            "modelName",
            "modelNumber",
            "deviceType",
            "serialNumber",
            "UDN",
        }
        for node in root.iter():
            tag = node.tag.rsplit("}", 1)[-1]
            if tag in allowed and tag not in fields:
                fields[tag] = (node.text or "").strip()[:300]
        return fields
    finally:
        conn.close()


def probe(address, port, timeout):
    started = time.monotonic()
    result = {"port": port, "state": "unreachable"}
    try:
        with socket.create_connection((address, port), timeout) as s:
            result["service_hint"] = {
                22: "SSH",
                80: "HTTP",
                443: "HTTPS",
                1883: "MQTT",
                139: "NetBIOS",
                445: "SMB",
                554: "RTSP",
                631: "IPP printing",
                9100: "Raw printing",
                8008: "HTTP / media",
                8009: "Media control",
            }.get(port, "TCP")
            result.update(
                state="open", latency_ms=round((time.monotonic() - started) * 1000, 1)
            )
            if port == 22:
                result["banner"] = s.recv(256).decode("utf-8", "replace").strip()
        if port in (80, 443, 8000, 8080, 8443):
            secure = port in (443, 8443)
            context = ssl._create_unverified_context() if secure else None
            conn = (
                http.client.HTTPSConnection(
                    address, port, timeout=timeout, context=context
                )
                if secure
                else http.client.HTTPConnection(address, port, timeout=timeout)
            )
            try:
                conn.connect()
                if secure:
                    der = conn.sock.getpeercert(binary_form=True)
                    result["certificate_sha256"] = hashlib.sha256(der).hexdigest()
                    result["tls_version"] = conn.sock.version()
                    result["tls_cipher"] = conn.sock.cipher()[0]
                    with tempfile.NamedTemporaryFile(mode="w", suffix=".pem") as cert:
                        cert.write(ssl.DER_cert_to_PEM_cert(der))
                        cert.flush()
                        result["certificate"] = ssl._ssl._test_decode_cert(cert.name)
                conn.request(
                    "GET",
                    "/",
                    headers={"User-Agent": "Atlas/1.0", "Connection": "close"},
                )
                response = conn.getresponse()
                result["http_status"] = response.status
                result["http_headers"] = {
                    k: v[:500]
                    for k, v in response.getheaders()
                    if k.lower()
                    in (
                        "server",
                        "content-type",
                        "location",
                        "www-authenticate",
                        "x-powered-by",
                        "strict-transport-security",
                    )
                }
                result["redirect_followed"] = False
                result["server"] = response.getheader("Server", "")[:200]
                body = response.read(32768).decode("utf-8", "replace")
                title = re.search("<title[^>]*>(.*?)</title>", body, re.I | re.S)
                if title:
                    result["title"] = re.sub("\\s+", " ", title.group(1)).strip()[:200]
            except (OSError, http.client.HTTPException):
                failure = AtlasError("ATLAS_METADATA_FAILED")
                result["metadata_error"] = failure.message
                result["metadata_failure"] = failure.failure()
            finally:
                conn.close()
    except OSError as e:
        code = (
            "ATLAS_TCP_TIMEOUT"
            if isinstance(e, TimeoutError)
            else (
                "ATLAS_TCP_REFUSED"
                if e.errno == errno.ECONNREFUSED
                else "ATLAS_TCP_UNREACHABLE"
            )
        )
        failure = AtlasError(
            "ATLAS_METADATA_FAILED" if result["state"] == "open" else code
        )
        if result["state"] == "open":
            result["metadata_error"] = failure.message
            result["metadata_failure"] = failure.failure()
        else:
            result["reason"] = failure.message
            result["failure"] = failure.failure()
    return result
