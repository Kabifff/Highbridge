# SPDX-License-Identifier: GPL-3.0-or-later
# Copyright (C) 2026 Kabifff and the Highbridge contributors
"""Reticulum / LXMF engine for Highbridge, run inside the app through Chaquopy.

Kotlin calls the functions at the bottom of this file. Events go back through the Java class
org.highbridge.rns.RnsBridge.

The RNS and LXMF packages are NOT shipped inside Highbridge: their Reticulum License adds use
restrictions that the GPL doesn't allow in a combined work. The app downloads them from PyPI
only when the user chooses to turn Reticulum on, and this module imports them from that folder.
"""
import hashlib
import os
import sys
import time
import threading

from java import jclass

RNS = None
LXMF = None


def set_ext_path(path):
    """Folder holding the RNS and LXMF packages the user downloaded."""
    if path not in sys.path:
        sys.path.insert(0, path)


def _ensure():
    global RNS, LXMF
    if RNS is None:
        import RNS as _rns
        import LXMF as _lxmf
        RNS = _rns
        LXMF = _lxmf

APP_NAME = "highbridge"
_bridge = None
_reticulum = None
_router = None
_identity = None
_lxmf_dest = None
_groups = {}          # channel index -> (RNS.Destination IN, RNS.Destination OUT)
_rnode_iface = None
_pending = {}         # lxmessage hash hex -> LXMessage
_lock = threading.Lock()

RNODE_INTERFACE_SOURCE = r'''
class HighbridgeRNodeInterface(Interface):
    DEFAULT_IFAC_SIZE = 8

    def __init__(self, owner, configuration):
        super().__init__()
        c = Interface.get_config_obj(configuration)
        self.owner = owner
        self.name = c["name"]
        self.HW_MTU = 508
        self.bitrate = int(c["bitrate"]) if "bitrate" in c else 1200
        self.online = False
        self.IN = True
        import highbridge_rns
        highbridge_rns._rnode_iface = self

    def process_incoming(self, data):
        self.rxb += len(data)
        self.owner.inbound(data, self)

    def process_outgoing(self, data):
        if not self.online:
            return
        from java import jclass
        jclass("org.highbridge.rns.RnsBridge").rnodeWrite(bytes(data))
        self.txb += len(data)

    def should_ingress_limit(self):
        return False

    def __str__(self):
        return "HighbridgeRNodeInterface[" + self.name + "]"

interface_class = HighbridgeRNodeInterface
'''


def _b():
    global _bridge
    if _bridge is None:
        _bridge = jclass("org.highbridge.rns.RnsBridge")
    return _bridge


def _log(line):
    try:
        _b().onLog(str(line))
    except Exception:
        pass


def _write_config(configdir, enable_auto, tcp_hosts, use_rnode, rnode_bitrate):
    os.makedirs(os.path.join(configdir, "interfaces"), exist_ok=True)
    with open(os.path.join(configdir, "interfaces", "HighbridgeRNodeInterface.py"), "w") as f:
        f.write(RNODE_INTERFACE_SOURCE)
    lines = [
        "[reticulum]",
        "  enable_transport = No",
        "  share_instance = No",
        "  panic_on_interface_error = No",
        "",
        "[logging]",
        "  loglevel = 3",
        "",
        "[interfaces]",
        "  [[Wi-Fi Auto]]",
        "    type = AutoInterface",
        "    enabled = " + ("yes" if enable_auto else "no"),
    ]
    for i, host in enumerate([h.strip() for h in tcp_hosts.split(",") if h.strip()]):
        name, _, port = host.partition(":")
        lines += ["  [[TCP %d]]" % i, "    type = TCPClientInterface", "    enabled = yes",
                  "    target_host = " + name, "    target_port = " + (port or "4242")]
    # The RNode interface is always configured; it goes online only while an RNode is connected.
    # (Reticulum can't be restarted inside one process, so interfaces are switched instead.)
    if True:
        lines += ["  [[RNode]]", "    type = HighbridgeRNodeInterface", "    enabled = yes",
                  "    bitrate = %d" % int(rnode_bitrate)]
    with open(os.path.join(configdir, "config"), "w") as f:
        f.write("\n".join(lines) + "\n")


class _AnnounceHandler:
    def __init__(self, aspect):
        self.aspect_filter = aspect

    def received_announce(self, destination_hash, announced_identity, app_data):
        name = ""
        try:
            if app_data:
                name = LXMF.display_name_from_app_data(app_data) if self.aspect_filter == "lxmf.delivery" else app_data.decode("utf-8", "ignore")
        except Exception:
            name = ""
        hops = RNS.Transport.hops_to(destination_hash)
        _b().onAnnounce(RNS.hexrep(destination_hash, delimit=False), name or "", self.aspect_filter, int(hops))


def _on_lxmf(message):
    try:
        content = message.content_as_string() if hasattr(message, "content_as_string") else message.content.decode("utf-8", "ignore")
        title = message.title_as_string() if hasattr(message, "title_as_string") else ""
        snr = message.snr if getattr(message, "snr", None) is not None else -999.0
        _b().onMessage(RNS.hexrep(message.source_hash, delimit=False), title or "", content or "",
                       int(message.timestamp * 1000), float(snr))
    except Exception as e:
        _log("LXMF receive error: %s" % e)


def _on_group_packet(index):
    def cb(data, packet):
        try:
            text = data.decode("utf-8", "ignore")
            sep = text.find("\x1f")
            sender, body = (text[:sep], text[sep + 1:]) if sep > 0 else ("?", text)
            _b().onGroupMessage(int(index), sender, body)
        except Exception as e:
            _log("Group receive error: %s" % e)
    return cb


# ---------------- called from Kotlin ----------------

def start(configdir, enable_auto, tcp_hosts, use_rnode, rnode_bitrate, display_name):
    global _reticulum, _router, _identity, _lxmf_dest
    _ensure()
    with _lock:
        if _reticulum is not None:
            _set_rnode(use_rnode, rnode_bitrate)
            return RNS.hexrep(_lxmf_dest.hash, delimit=False)
        _write_config(configdir, bool(enable_auto), str(tcp_hosts), bool(use_rnode), int(rnode_bitrate))
        RNS.logdest = RNS.LOG_CALLBACK
        RNS.logcall = _log
        _reticulum = RNS.Reticulum(configdir=configdir, loglevel=RNS.LOG_NOTICE, logdest=RNS.LOG_CALLBACK)
        id_path = os.path.join(configdir, "identity")
        if os.path.isfile(id_path):
            _identity = RNS.Identity.from_file(id_path)
        else:
            _identity = RNS.Identity()
            _identity.to_file(id_path)
        _router = LXMF.LXMRouter(identity=_identity, storagepath=os.path.join(configdir, "lxmf"))
        _lxmf_dest = _router.register_delivery_identity(_identity, display_name=str(display_name))
        _router.register_delivery_callback(_on_lxmf)
        RNS.Transport.register_announce_handler(_AnnounceHandler("lxmf.delivery"))
        RNS.Transport.register_announce_handler(_AnnounceHandler("nomadnetwork.node"))
        _set_rnode(use_rnode, rnode_bitrate)
        _lxmf_dest.announce()
        return RNS.hexrep(_lxmf_dest.hash, delimit=False)


def _set_rnode(on, bitrate):
    iface = _rnode_iface
    if iface is not None:
        iface.online = bool(on)
        if int(bitrate) > 0:
            iface.bitrate = int(bitrate)


def stop():
    """Takes the RNode offline. Reticulum itself keeps running until the app closes."""
    _set_rnode(False, 0)


def announce():
    if _lxmf_dest is not None:
        _lxmf_dest.announce()


def rnode_inbound(data):
    iface = _rnode_iface
    if iface is not None:
        iface.process_incoming(bytes(data))


def send_lxmf(dest_hex, text, title=""):
    dest_hash = bytes.fromhex(dest_hex)
    identity = RNS.Identity.recall(dest_hash)
    if identity is None:
        RNS.Transport.request_path(dest_hash)
        deadline = time.time() + 15
        while identity is None and time.time() < deadline:
            time.sleep(0.25)
            identity = RNS.Identity.recall(dest_hash)
    if identity is None:
        raise Exception("Unknown destination. Wait for it to announce, then try again.")
    dest = RNS.Destination(identity, RNS.Destination.OUT, RNS.Destination.SINGLE, "lxmf", "delivery")
    msg = LXMF.LXMessage(dest, _lxmf_dest, str(text), str(title), desired_method=LXMF.LXMessage.DIRECT)
    msg.try_propagation_on_fail = True
    h = None

    def delivered(m):
        _b().onDelivery(RNS.hexrep(m.hash, delimit=False), True)

    def failed(m):
        _b().onDelivery(RNS.hexrep(m.hash, delimit=False), False)

    msg.register_delivery_callback(delivered)
    msg.register_failed_callback(failed)
    _router.handle_outbound(msg)
    h = RNS.hexrep(msg.hash, delimit=False) if msg.hash else ("lxm-%d" % int(time.time() * 1000))
    return h


def join_group(index, name, key_bytes):
    """A 512-bit RNS GROUP destination; everyone with the same key and name hears it.

    RNS gives an inbound destination with no identity a random one, which would make every
    member's group address different. So the group identity is derived from the key and name,
    and all members end up with the same destination hash.
    """
    key = bytes(key_bytes)
    seed = hashlib.sha512(b"highbridge-group:" + key + str(name).encode("utf-8")).digest()
    ident = RNS.Identity.from_bytes(seed)
    for idx, pair in list(_groups.items()):
        if idx == int(index) or RNS.Destination.hash(ident, APP_NAME, "group", str(name)) == pair[0].hash:
            try:
                RNS.Transport.deregister_destination(pair[0])
            except Exception:
                pass
            del _groups[idx]
    d_in = RNS.Destination(ident, RNS.Destination.IN, RNS.Destination.GROUP, APP_NAME, "group", str(name))
    d_in.load_private_key(key)
    d_in.set_packet_callback(_on_group_packet(index))
    d_out = RNS.Destination(ident, RNS.Destination.OUT, RNS.Destination.GROUP, APP_NAME, "group", str(name))
    d_out.load_private_key(key)
    _groups[int(index)] = (d_in, d_out)
    return RNS.hexrep(d_in.hash, delimit=False)


def send_group(index, sender, text):
    pair = _groups.get(int(index))
    if pair is None:
        raise Exception("Join the group first")
    data = (str(sender) + "\x1f" + str(text)).encode("utf-8")
    RNS.Packet(pair[1], data).send()
    return "grp-%d" % int(time.time() * 1000)


def fetch_page(node_hex, path, timeout_sec):
    """Fetch a NomadNet page (Micron markup) from a node over an RNS Link."""
    node_hash = bytes.fromhex(node_hex)
    if not RNS.Transport.has_path(node_hash):
        RNS.Transport.request_path(node_hash)
    deadline = time.time() + float(timeout_sec)
    while not RNS.Transport.has_path(node_hash) and time.time() < deadline:
        time.sleep(0.25)
    identity = RNS.Identity.recall(node_hash)
    if identity is None:
        raise Exception("No path to that node yet")
    dest = RNS.Destination(identity, RNS.Destination.OUT, RNS.Destination.SINGLE, "nomadnetwork", "node")
    result = {"data": None, "done": threading.Event()}

    def established(link):
        def response(receipt):
            result["data"] = receipt.response
            result["done"].set()
            link.teardown()

        def failed(receipt):
            result["done"].set()
            link.teardown()
        link.request(str(path), data=None, response_callback=response, failed_callback=failed)

    link = RNS.Link(dest, established_callback=established)
    result["done"].wait(float(timeout_sec))
    data = result["data"]
    if data is None:
        try:
            link.teardown()
        except Exception:
            pass
        raise Exception("The page didn't load in time")
    if isinstance(data, bytes):
        return data.decode("utf-8", "ignore")
    return str(data)


def status():
    if _reticulum is None:
        return "stopped"
    names = [str(i) for i in RNS.Transport.interfaces]
    return "running · interfaces: " + ", ".join(names)
