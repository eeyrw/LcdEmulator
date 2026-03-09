#!/usr/bin/env python3
"""
UDP LCD Protocol Test Client (PC Side)
=======================================
Implements the full UDP LCD control protocol as specified in the protocol spec.
Features:
  - All CMD_LCD_* commands
  - CRC-16-CCITT packet integrity
  - Fragmentation for large payloads
  - ACK mechanism with timeout/retry
  - Bidirectional heartbeat with auto-reconnect
  - State snapshot + dirty flags with FULLFRAME encoding
  - Interactive CLI for debugging

Usage:
    python udp_lcd_client.py [--host HOST] [--port PORT] [--cols COLS] [--rows ROWS]
"""

import argparse
import socket
import struct
import threading
import time
import sys
import copy
import logging
try:
    import readline
except ImportError:
    readline = None  # Windows: readline unavailable
from enum import IntEnum, IntFlag
from collections import defaultdict
from typing import Optional, Callable

# ---------------------------------------------------------------------------
# Constants
# ---------------------------------------------------------------------------

PROTOCOL_VERSION = 0x01
MAX_PAYLOAD_SIZE = 1400  # per-fragment payload limit
MAX_PACKET_SIZE = 1472   # MTU 1500 - UDP/IP header 28
ACK_TIMEOUT_S = 0.05     # 50ms
ACK_MAX_RETRIES = 3
HB_INTERVAL_S = 3.0      # heartbeat send interval
HB_MISS_MAX = 3           # consecutive misses before disconnect
FRAME_SEND_INTERVAL_S = 0.030  # 30ms frame send period


class Cmd(IntEnum):
    """Command codes."""
    LCD_INIT          = 0x01
    LCD_SETBACKLIGHT  = 0x02
    LCD_SETCONTRAST   = 0x03
    LCD_SETBRIGHTNESS = 0x04
    LCD_WRITEDATA     = 0x05
    LCD_SETCURSOR     = 0x06
    LCD_CUSTOMCHAR    = 0x07
    LCD_WRITECMD      = 0x08
    LCD_DE_INIT       = 0x0B
    HEARTBEAT         = 0x0C
    LCD_FULLFRAME     = 0x0D
    ENTER_BOOT        = 0x19
    ACK               = 0xFF


class Flag(IntFlag):
    """FLAGS field bits."""
    NONE    = 0x00
    ACK_REQ = 0x01  # bit 0
    FRAG    = 0x02  # bit 1


class DirtyBit(IntFlag):
    """Dirty flags for FULLFRAME state tracking."""
    NONE       = 0
    SCREEN     = 1 << 0
    CONTRAST   = 1 << 1
    BACKLIGHT  = 1 << 2
    BRIGHTNESS = 1 << 3
    CUSTOMCHAR = 1 << 4
    INIT       = 1 << 5
    ALL        = 0x3F


class ConnStatus(IntEnum):
    """Connection status."""
    DISCONNECTED = 0
    CONNECTED    = 1


class Role(IntEnum):
    """Heartbeat role identifier."""
    PC     = 0x01
    DEVICE = 0x02


# ---------------------------------------------------------------------------
# Readline-safe log handler
# ---------------------------------------------------------------------------

class _ReadlineLogHandler(logging.StreamHandler):
    """Log handler that preserves the interactive prompt.

    When a background thread emits a log message while the user is at an
    ``input('lcd> ')`` prompt, the handler clears the current terminal line,
    prints the log message, and then redisplays the prompt together with any
    partially typed text.

    On platforms where ``readline`` is available (Linux / macOS), the full
    line buffer and cursor position are restored.  On Windows (no readline),
    the handler still uses ANSI escapes to clear and reprint the prompt,
    which works in Windows Terminal and modern cmd.exe (Win 10+).
    """

    def emit(self, record):
        try:
            msg = self.format(record)
            stream = self.stream
            if readline is not None:
                buf = readline.get_line_buffer()
            else:
                buf = ''
            # \r moves cursor to column 0, \033[K clears to end of line
            stream.write(f'\r\033[K{msg}\n')
            # Reprint the prompt + any partially typed input
            stream.write(f'lcd> {buf}')
            stream.flush()
            if readline is not None:
                readline.redisplay()
        except Exception:
            self.handleError(record)


# ---------------------------------------------------------------------------
# CRC-16-CCITT
# ---------------------------------------------------------------------------

def _crc16_ccitt_table():
    """Pre-compute CRC-16-CCITT lookup table (POLY=0x1021, INIT=0xFFFF)."""
    table = []
    for i in range(256):
        crc = i << 8
        for _ in range(8):
            if crc & 0x8000:
                crc = (crc << 1) ^ 0x1021
            else:
                crc <<= 1
            crc &= 0xFFFF
        table.append(crc)
    return table


_CRC_TABLE = _crc16_ccitt_table()


def crc16_ccitt(data: bytes, init: int = 0xFFFF) -> int:
    """Calculate CRC-16-CCITT over *data* with given initial value."""
    crc = init
    for b in data:
        crc = ((_CRC_TABLE[((crc >> 8) ^ b) & 0xFF]) ^ (crc << 8)) & 0xFFFF
    return crc


# ---------------------------------------------------------------------------
# Packet Builder / Parser
# ---------------------------------------------------------------------------

# Packet header: VER(1) + SEQ(2) + FLAGS(1) + CMD(1) + FRAG_IDX(1) + FRAG_TOTAL(1) + LEN(2) = 9 bytes
HEADER_FMT = '<BHBBBBH'  # little-endian
HEADER_SIZE = struct.calcsize(HEADER_FMT)  # 9
CRC_SIZE = 2  # uint16 LE


def build_packet(seq: int, flags: int, cmd: int, payload: bytes,
                 frag_idx: int = 0, frag_total: int = 1) -> bytes:
    """Build a single protocol packet with CRC-16 appended."""
    header = struct.pack(
        HEADER_FMT,
        PROTOCOL_VERSION,
        seq & 0xFFFF,
        flags & 0xFF,
        cmd & 0xFF,
        frag_idx & 0xFF,
        frag_total & 0xFF,
        len(payload) & 0xFFFF,
    )
    body = header + payload
    crc = crc16_ccitt(body)
    return body + struct.pack('<H', crc)


def build_ack_packet(seq: int, status: int = 0) -> bytes:
    """Build a compact ACK packet: VER(1) + SEQ(2) + CMD(1) + STATUS(1) + CRC(2)."""
    body = struct.pack('<BHBB', PROTOCOL_VERSION, seq & 0xFFFF, Cmd.ACK, status & 0xFF)
    crc = crc16_ccitt(body)
    return body + struct.pack('<H', crc)


def parse_packet(data: bytes) -> Optional[dict]:
    """Parse a received packet. Returns dict on success, None on CRC failure."""
    if len(data) < HEADER_SIZE + CRC_SIZE:
        # Could be an ACK packet (7 bytes): VER(1)+SEQ(2)+CMD(1)+STATUS(1)+CRC(2)
        if len(data) == 7:
            return _parse_ack(data)
        return None

    body = data[:-CRC_SIZE]
    crc_recv = struct.unpack_from('<H', data, len(data) - CRC_SIZE)[0]
    if crc16_ccitt(body) != crc_recv:
        return None

    ver, seq, flags, cmd, frag_idx, frag_total, plen = struct.unpack_from(HEADER_FMT, body)
    payload = body[HEADER_SIZE:HEADER_SIZE + plen]
    return {
        'ver': ver, 'seq': seq, 'flags': flags, 'cmd': cmd,
        'frag_idx': frag_idx, 'frag_total': frag_total,
        'len': plen, 'payload': payload,
    }


def _parse_ack(data: bytes) -> Optional[dict]:
    """Parse a 7-byte ACK packet."""
    body = data[:5]
    crc_recv = struct.unpack_from('<H', data, 5)[0]
    if crc16_ccitt(body) != crc_recv:
        return None
    ver, seq, cmd, status = struct.unpack('<BHBB', body)
    if cmd != Cmd.ACK:
        return None
    return {'ver': ver, 'seq': seq, 'cmd': cmd, 'status': status, 'is_ack': True}


def fragment_payload(payload: bytes, max_size: int = MAX_PAYLOAD_SIZE) -> list[bytes]:
    """Split payload into fragments of at most *max_size* bytes."""
    if len(payload) <= max_size:
        return [payload]
    chunks = []
    for offset in range(0, len(payload), max_size):
        chunks.append(payload[offset:offset + max_size])
    return chunks


# ---------------------------------------------------------------------------
# LCD State Snapshot
# ---------------------------------------------------------------------------

class LcdState:
    """In-memory LCD state snapshot, mirroring the DLL-side gLcdState."""

    def __init__(self, cols: int = 20, rows: int = 4):
        self.cols = cols
        self.rows = rows
        self.contrast: int = 128
        self.backlight: int = 1
        self.brightness: int = 255
        self.cursor_x: int = 0
        self.cursor_y: int = 0
        # Screen buffer: rows x cols, filled with spaces (0x20)
        self.screen: list[bytearray] = [bytearray(b' ' * cols) for _ in range(rows)]
        # Custom characters: index 0-7, each 8 bytes
        self.custom_chars: dict[int, bytes] = {}
        # Dirty flags
        self.dirty: DirtyBit = DirtyBit.NONE
        self._lock = threading.Lock()

    def set_cursor(self, x: int, y: int):
        with self._lock:
            self.cursor_x = max(0, min(x, self.cols - 1))
            self.cursor_y = max(0, min(y, self.rows - 1))

    def write_text(self, text: str):
        with self._lock:
            data = text.encode('utf-8')
            for b in data:
                if self.cursor_x < self.cols and self.cursor_y < self.rows:
                    self.screen[self.cursor_y][self.cursor_x] = b
                    self.cursor_x += 1
            self.dirty |= DirtyBit.SCREEN

    def set_contrast(self, value: int):
        with self._lock:
            self.contrast = value & 0xFF
            self.dirty |= DirtyBit.CONTRAST

    def set_backlight(self, value: int):
        with self._lock:
            self.backlight = value & 0xFF
            self.dirty |= DirtyBit.BACKLIGHT

    def set_brightness(self, value: int):
        with self._lock:
            self.brightness = value & 0xFF
            self.dirty |= DirtyBit.BRIGHTNESS

    def set_custom_char(self, index: int, font_data: bytes):
        assert 0 <= index <= 7 and len(font_data) == 8
        with self._lock:
            self.custom_chars[index] = font_data
            self.dirty |= DirtyBit.CUSTOMCHAR

    def clear_screen(self):
        with self._lock:
            for row in self.screen:
                for i in range(len(row)):
                    row[i] = 0x20
            self.cursor_x = 0
            self.cursor_y = 0
            self.dirty |= DirtyBit.SCREEN

    def snapshot_and_clear_dirty(self) -> tuple['DirtyBit', dict]:
        """Atomically read dirty flags and return a snapshot. Clears dirty bits."""
        with self._lock:
            d = self.dirty
            if d == DirtyBit.NONE:
                return d, {}
            snap = {
                'contrast': self.contrast,
                'backlight': self.backlight,
                'brightness': self.brightness,
                'screen': [bytearray(row) for row in self.screen],
                'custom_chars': dict(self.custom_chars),
                'cols': self.cols,
                'rows': self.rows,
            }
            self.dirty = DirtyBit.NONE
            return d, snap

    def mark_all_dirty(self):
        """Mark all bits dirty (used for reconnect recovery)."""
        with self._lock:
            self.dirty = DirtyBit.ALL

    def encode_fullframe(self, dirty: DirtyBit, snap: dict) -> bytes:
        """Encode a CMD_LCD_FULLFRAME payload from snapshot."""
        contrast = snap['contrast']
        backlight = snap['backlight']
        brightness = snap['brightness']

        # Determine CUSTOMCHAR_MASK
        cc_mask = 0x00
        if dirty & DirtyBit.CUSTOMCHAR or dirty == DirtyBit.ALL:
            for idx in snap['custom_chars']:
                cc_mask |= (1 << idx)

        buf = bytearray()
        buf.append(contrast & 0xFF)
        buf.append(backlight & 0xFF)
        buf.append(brightness & 0xFF)
        buf.append(cc_mask & 0xFF)

        # Custom character data (ordered by index)
        if cc_mask:
            for idx in range(8):
                if cc_mask & (1 << idx):
                    font = snap['custom_chars'].get(idx, b'\x00' * 8)
                    buf.append(idx & 0xFF)
                    buf.extend(font)

        # Screen data (row-major)
        for row in snap['screen']:
            buf.extend(row)

        return bytes(buf)

    def display_preview(self) -> str:
        """Return a text representation of the current screen.

        Custom character bytes (0x00-0x07) are rendered using their stored
        5x8 bitmap data so the terminal output approximates the real LCD.
        """
        # Map custom char index to a compact 2-line glyph symbol for terminal
        CC_SYMBOLS = {
            0: '\u2665',  # Heart
            1: '\u263a',  # Smiley
            2: '\u2191',  # Arrow Up
            3: '\u2193',  # Arrow Down
            4: '\u266a',  # Bell (music note as fallback)
            5: '\u266b',  # Note
            6: '\u2713',  # Check
            7: '\u2717',  # Cross
        }
        border = '+' + '-' * self.cols + '+'
        lines = [border]
        with self._lock:
            for row in self.screen:
                display = ''
                for b in row:
                    if 0x20 <= b <= 0x7E:
                        display += chr(b)
                    elif b <= 0x07 and b in self.custom_chars:
                        display += CC_SYMBOLS.get(b, f'{b}')
                    elif b <= 0x07:
                        # Custom char defined but no symbol mapping; show index
                        display += str(b)
                    else:
                        display += '.'
                lines.append('|' + display + '|')
        lines.append(border)

        # If any custom chars are defined, append a legend rendering
        # each glyph as a vertical 5x8 bitmap grid.  Up to 4 chars are
        # placed side-by-side per row to keep the output compact.
        if self.custom_chars:
            lines.append('')
            lines.append('Custom char legend:')
            sorted_idxs = sorted(self.custom_chars)
            chars_per_row = 4
            for batch_start in range(0, len(sorted_idxs), chars_per_row):
                batch = sorted_idxs[batch_start:batch_start + chars_per_row]
                # Header line: [idx] symbol
                hdr_parts = []
                for idx in batch:
                    sym = CC_SYMBOLS.get(idx, '?')
                    hdr_parts.append(f'[{idx}] {sym}  ')
                lines.append('  ' + '   '.join(hdr_parts))
                # 8 bitmap rows
                for row_i in range(8):
                    row_parts = []
                    for idx in batch:
                        fb = self.custom_chars[idx][row_i]
                        bits = ''
                        for bit in range(4, -1, -1):
                            bits += '#' if (fb >> bit) & 1 else '.'
                        row_parts.append(bits)
                    lines.append('  ' + '      '.join(row_parts))
                lines.append('')  # blank line between batches

        return '\n'.join(lines)


# ---------------------------------------------------------------------------
# UDP LCD Client
# ---------------------------------------------------------------------------

class UdpLcdClient:
    """PC-side UDP LCD protocol client with heartbeat, ACK, and FULLFRAME support."""

    def __init__(self, host: str, port: int, cols: int = 20, rows: int = 4,
                 log_level: int = logging.INFO):
        self.host = host
        self.port = port
        self.state = LcdState(cols, rows)

        # Logging -- use readline-safe handler so background thread output
        # never corrupts the interactive 'lcd> ' prompt.
        self.log = logging.getLogger('UdpLcdClient')
        self.log.setLevel(log_level)
        if not self.log.handlers:
            h = _ReadlineLogHandler(sys.stdout)
            h.setFormatter(logging.Formatter('[%(asctime)s][%(levelname)s] %(message)s',
                                              datefmt='%H:%M:%S'))
            self.log.addHandler(h)

        # Socket
        self._sock: Optional[socket.socket] = None

        # Sequence counter (shared between heartbeat and business commands)
        self._seq: int = 0
        self._seq_lock = threading.Lock()

        # Connection status
        self.conn_status = ConnStatus.DISCONNECTED
        self._conn_lock = threading.Lock()

        # Heartbeat
        self._hb_seq: int = 0
        self._hb_miss_count: int = 0
        self._start_time = time.monotonic()

        # ACK tracking: seq -> threading.Event
        self._ack_events: dict[int, threading.Event] = {}
        self._ack_results: dict[int, int] = {}  # seq -> status
        self._ack_lock = threading.Lock()

        # Threads
        self._running = False
        self._hb_send_thread: Optional[threading.Thread] = None
        self._recv_thread: Optional[threading.Thread] = None
        self._frame_thread: Optional[threading.Thread] = None
        self._frame_send_enabled = True

        # Stats
        self._stats = {
            'packets_sent': 0,
            'packets_recv': 0,
            'ack_timeouts': 0,
            'crc_errors': 0,
            'hb_sent': 0,
            'hb_recv': 0,
            'frames_sent': 0,
        }
        self._stats_lock = threading.Lock()

        # Callbacks
        self.on_status_change: Optional[Callable[[ConnStatus], None]] = None

    # -- Sequence management --

    def _next_seq(self) -> int:
        with self._seq_lock:
            s = self._seq
            self._seq = (self._seq + 1) & 0xFFFF
            return s

    # -- Socket --

    def connect(self):
        """Create UDP socket and start all background threads."""
        if self._sock is not None:
            return
        self._sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        self._sock.settimeout(0.5)  # recv timeout for clean shutdown
        self._running = True
        self._start_time = time.monotonic()

        self._recv_thread = threading.Thread(target=self._recv_loop, daemon=True, name='recv')
        self._recv_thread.start()

        self._hb_send_thread = threading.Thread(target=self._hb_send_loop, daemon=True, name='hb')
        self._hb_send_thread.start()

        self._frame_thread = threading.Thread(target=self._frame_send_loop, daemon=True, name='frame')
        self._frame_thread.start()

        self.log.info(f'Client started, target {self.host}:{self.port}')

    def disconnect(self):
        """Stop threads and close socket."""
        self._running = False
        if self._hb_send_thread:
            self._hb_send_thread.join(timeout=2)
        if self._recv_thread:
            self._recv_thread.join(timeout=2)
        if self._frame_thread:
            self._frame_thread.join(timeout=2)
        if self._sock:
            try:
                self._sock.close()
            except OSError:
                pass
            self._sock = None
        self.log.info('Client stopped')

    # -- Low-level send --

    def _send_raw(self, data: bytes):
        """Send raw bytes to the device."""
        if self._sock is None:
            return
        try:
            self._sock.sendto(data, (self.host, self.port))
            with self._stats_lock:
                self._stats['packets_sent'] += 1
        except OSError as e:
            self.log.warning(f'Send error: {e}')

    def _send_command(self, cmd: int, payload: bytes, ack_req: bool = False,
                      allow_frag: bool = False) -> bool:
        """Build and send a command. Returns True if ACK received (when requested)."""
        fragments = fragment_payload(payload) if allow_frag else [payload]
        seq = self._next_seq()
        frag_total = len(fragments)

        flags = Flag.NONE
        if ack_req:
            flags |= Flag.ACK_REQ
        if frag_total > 1:
            flags |= Flag.FRAG

        success = True
        for frag_idx, frag_data in enumerate(fragments):
            pkt = build_packet(seq, flags, cmd, frag_data, frag_idx, frag_total)
            self.log.debug(f'TX cmd=0x{cmd:02X} seq={seq} frag={frag_idx}/{frag_total} '
                           f'len={len(frag_data)} ack={ack_req}')

            if ack_req:
                if not self._send_with_ack(seq, pkt):
                    success = False
            else:
                self._send_raw(pkt)

        return success

    def _send_with_ack(self, seq: int, pkt: bytes) -> bool:
        """Send packet and wait for ACK. Retry up to ACK_MAX_RETRIES times."""
        evt = threading.Event()
        with self._ack_lock:
            self._ack_events[seq] = evt

        for attempt in range(ACK_MAX_RETRIES + 1):
            self._send_raw(pkt)
            if evt.wait(timeout=ACK_TIMEOUT_S):
                with self._ack_lock:
                    status = self._ack_results.pop(seq, 1)
                    self._ack_events.pop(seq, None)
                if status == 0:
                    return True
                self.log.warning(f'ACK status=FAIL for seq={seq}')
                return False
            if attempt < ACK_MAX_RETRIES:
                self.log.debug(f'ACK timeout for seq={seq}, retry {attempt + 1}')

        with self._ack_lock:
            self._ack_events.pop(seq, None)
            self._ack_results.pop(seq, None)
        with self._stats_lock:
            self._stats['ack_timeouts'] += 1
        self.log.warning(f'ACK timeout for seq={seq} after {ACK_MAX_RETRIES} retries')
        return False

    # -- Receive loop --

    def _recv_loop(self):
        """Background thread: receive and dispatch incoming packets."""
        while self._running:
            try:
                data, addr = self._sock.recvfrom(2048)
            except socket.timeout:
                continue
            except OSError:
                if self._running:
                    self.log.warning('Socket error in recv loop')
                break

            with self._stats_lock:
                self._stats['packets_recv'] += 1

            pkt = parse_packet(data)
            if pkt is None:
                with self._stats_lock:
                    self._stats['crc_errors'] += 1
                self.log.debug(f'CRC error or malformed packet ({len(data)} bytes)')
                continue

            if pkt.get('is_ack'):
                self._handle_ack(pkt)
            elif pkt['cmd'] == Cmd.HEARTBEAT:
                self._handle_device_heartbeat(pkt)
            else:
                self.log.debug(f'RX cmd=0x{pkt["cmd"]:02X} seq={pkt["seq"]}')

    def _handle_ack(self, pkt: dict):
        """Process an incoming ACK packet."""
        seq = pkt['seq']
        status = pkt.get('status', 1)
        self.log.debug(f'RX ACK seq={seq} status={status}')
        with self._ack_lock:
            self._ack_results[seq] = status
            evt = self._ack_events.get(seq)
            if evt:
                evt.set()

    def _handle_device_heartbeat(self, pkt: dict):
        """Process a heartbeat from the device."""
        payload = pkt.get('payload', b'')
        if len(payload) >= 4:
            role, hb_seq, uptime = struct.unpack_from('<BBH', payload)
            self.log.debug(f'RX HB device hb_seq={hb_seq} uptime={uptime}s')
        else:
            self.log.debug('RX HB device (short payload)')

        with self._stats_lock:
            self._stats['hb_recv'] += 1

        self._hb_miss_count = 0

        with self._conn_lock:
            prev = self.conn_status
            self.conn_status = ConnStatus.CONNECTED
            if prev == ConnStatus.DISCONNECTED:
                self.log.info('Link RESTORED - triggering recovery')
                threading.Thread(target=self._recovery_sequence, daemon=True).start()

    # -- Heartbeat send --

    def _hb_send_loop(self):
        """Background thread: send heartbeat every HB_INTERVAL_S."""
        while self._running:
            time.sleep(HB_INTERVAL_S)
            if not self._running:
                break
            self._send_heartbeat()
            self._hb_miss_count += 1
            if self._hb_miss_count >= HB_MISS_MAX:
                with self._conn_lock:
                    if self.conn_status != ConnStatus.DISCONNECTED:
                        self.conn_status = ConnStatus.DISCONNECTED
                        self.log.warning(f'Link DOWN (miss_count={self._hb_miss_count})')
                        if self.on_status_change:
                            self.on_status_change(ConnStatus.DISCONNECTED)

    def _send_heartbeat(self):
        """Build and send a CMD_HEARTBEAT packet."""
        uptime = int(time.monotonic() - self._start_time) & 0xFFFF
        payload = struct.pack('<BBH', Role.PC, self._hb_seq & 0xFF, uptime)
        seq = self._next_seq()
        pkt = build_packet(seq, Flag.NONE, Cmd.HEARTBEAT, payload)
        self._send_raw(pkt)
        with self._stats_lock:
            self._stats['hb_sent'] += 1
        self.log.debug(f'TX HB hb_seq={self._hb_seq} uptime={uptime}s')
        self._hb_seq = (self._hb_seq + 1) & 0xFF

    # -- Recovery --

    def _recovery_sequence(self):
        """Re-initialize LCD after link restored."""
        self.log.info('Recovery: sending CMD_LCD_INIT with ACK...')
        ok = self.cmd_init(self.state.cols, self.state.rows, ack=True)
        if not ok:
            self.log.warning('Recovery: INIT ACK failed, will retry next heartbeat cycle')
            return
        self.log.info('Recovery: sending FULLFRAME (DIRTY_ALL)...')
        self.state.mark_all_dirty()
        dirty, snap = self.state.snapshot_and_clear_dirty()
        if snap:
            payload = self.state.encode_fullframe(dirty, snap)
            self._send_command(Cmd.LCD_FULLFRAME, payload, allow_frag=True)
            with self._stats_lock:
                self._stats['frames_sent'] += 1
        self.log.info('Recovery complete')

    # -- Frame send loop (30ms polling) --

    def _frame_send_loop(self):
        """Background thread: poll dirty flags and send FULLFRAME packets."""
        while self._running:
            time.sleep(FRAME_SEND_INTERVAL_S)
            if not self._running or not self._frame_send_enabled:
                continue
            dirty, snap = self.state.snapshot_and_clear_dirty()
            if dirty == DirtyBit.NONE:
                continue
            payload = self.state.encode_fullframe(dirty, snap)
            self._send_command(Cmd.LCD_FULLFRAME, payload, allow_frag=True)
            with self._stats_lock:
                self._stats['frames_sent'] += 1
            self.log.debug(f'TX FULLFRAME dirty=0x{int(dirty):02X} size={len(payload)}')

    # ===================================================================
    # Public command API
    # ===================================================================

    def cmd_init(self, cols: int = 20, rows: int = 4, ack: bool = True) -> bool:
        """CMD_LCD_INIT: Initialize LCD dimensions."""
        self.state = LcdState(cols, rows)
        payload = struct.pack('BB', cols & 0xFF, rows & 0xFF)
        return self._send_command(Cmd.LCD_INIT, payload, ack_req=ack)

    def cmd_deinit(self) -> bool:
        """CMD_LCD_DE_INIT: De-initialize LCD."""
        return self._send_command(Cmd.LCD_DE_INIT, b'')

    def cmd_set_backlight(self, value: int):
        """CMD_LCD_SETBACKLIGHT: Set backlight (0=off, 1=on)."""
        self.state.set_backlight(value)
        # Dirty flag triggers FULLFRAME, but also send direct command for compat
        self._send_command(Cmd.LCD_SETBACKLIGHT, struct.pack('B', value & 0xFF))

    def cmd_set_contrast(self, value: int):
        """CMD_LCD_SETCONTRAST: Set contrast (0~255)."""
        self.state.set_contrast(value)
        self._send_command(Cmd.LCD_SETCONTRAST, struct.pack('B', value & 0xFF))

    def cmd_set_brightness(self, value: int):
        """CMD_LCD_SETBRIGHTNESS: Set brightness (0~255)."""
        self.state.set_brightness(value)
        self._send_command(Cmd.LCD_SETBRIGHTNESS, struct.pack('B', value & 0xFF))

    def cmd_set_cursor(self, x: int, y: int):
        """CMD_LCD_SETCURSOR: Set cursor position."""
        self.state.set_cursor(x, y)
        self._send_command(Cmd.LCD_SETCURSOR, struct.pack('BB', x & 0xFF, y & 0xFF))

    def cmd_write_data(self, text: str):
        """CMD_LCD_WRITEDATA: Write text at current cursor position."""
        self.state.write_text(text)
        data = text.encode('utf-8')
        payload = struct.pack('<H', len(data)) + data
        self._send_command(Cmd.LCD_WRITEDATA, payload, allow_frag=True)

    def cmd_custom_char(self, index: int, font_data: bytes):
        """CMD_LCD_CUSTOMCHAR: Define a custom character (index 0~7, 8 bytes font)."""
        if len(font_data) != 8:
            self.log.error('Font data must be exactly 8 bytes')
            return
        self.state.set_custom_char(index, font_data)
        payload = struct.pack('B', index & 0xFF) + font_data
        self._send_command(Cmd.LCD_CUSTOMCHAR, payload, allow_frag=True)

    def cmd_write_cmd(self, raw_cmd: int):
        """CMD_LCD_WRITECMD: Send raw LCD command byte."""
        self._send_command(Cmd.LCD_WRITECMD, struct.pack('B', raw_cmd & 0xFF))

    def cmd_enter_boot(self):
        """CMD_ENTER_BOOT: Enter bootloader mode."""
        self._send_command(Cmd.ENTER_BOOT, b'')

    def cmd_send_fullframe(self):
        """Force-send a FULLFRAME with all dirty bits."""
        self.state.mark_all_dirty()
        dirty, snap = self.state.snapshot_and_clear_dirty()
        if snap:
            payload = self.state.encode_fullframe(dirty, snap)
            self._send_command(Cmd.LCD_FULLFRAME, payload, allow_frag=True)
            with self._stats_lock:
                self._stats['frames_sent'] += 1
            self.log.info(f'Sent FULLFRAME (forced), size={len(payload)}')

    def get_stats(self) -> dict:
        with self._stats_lock:
            return dict(self._stats)


# ---------------------------------------------------------------------------
# Built-in Test Scenarios
# ---------------------------------------------------------------------------

class TestRunner:
    """Collection of automated test scenarios for protocol verification."""

    def __init__(self, client: UdpLcdClient):
        self.client = client
        self._stop_flag = threading.Event()

    def stop(self):
        self._stop_flag.set()

    def _is_stopped(self) -> bool:
        return self._stop_flag.is_set()

    # ---- test: Frame rate benchmark ----

    def test_frame_rate(self, duration_s: float = 5.0, interval_ms: float = 30.0):
        """Measure actual FULLFRAME send throughput.

        Rapidly updates the screen buffer and lets the FrameSendThread deliver
        frames at its natural 30 ms cadence. Reports achieved FPS and total
        bytes sent.
        """
        c = self.client
        cols, rows = c.state.cols, c.state.rows
        print(f'[test_frame_rate] Starting {duration_s}s benchmark, target interval={interval_ms}ms ...')

        # Disable auto frame thread; we drive the sending ourselves for precise measurement
        old_enabled = c._frame_send_enabled
        c._frame_send_enabled = False

        frames_sent = 0
        total_bytes = 0
        latencies: list[float] = []
        t_start = time.monotonic()
        frame_num = 0
        self._stop_flag.clear()

        try:
            while (time.monotonic() - t_start) < duration_s and not self._is_stopped():
                # Update screen with a counter pattern
                frame_num += 1
                line0 = f'Frame #{frame_num:<10d}'[:cols]
                line1 = time.strftime('%H:%M:%S')
                line2 = f'FPS test running...'[:cols]
                bar_len = int((frame_num % (cols + 1)))
                line3 = ('=' * bar_len).ljust(cols)[:cols]

                c.state.set_cursor(0, 0)
                c.state.write_text(line0.ljust(cols)[:cols])
                c.state.set_cursor(0, 1 % rows)
                c.state.write_text(line1.ljust(cols)[:cols])
                if rows > 2:
                    c.state.set_cursor(0, 2)
                    c.state.write_text(line2.ljust(cols)[:cols])
                if rows > 3:
                    c.state.set_cursor(0, 3)
                    c.state.write_text(line3.ljust(cols)[:cols])

                # Manually build and send FULLFRAME (screen-only, no custom chars
                # to avoid triggering expensive CGRAM font rebuild on device)
                dirty, snap = c.state.snapshot_and_clear_dirty()
                if snap:
                    payload = c.state.encode_fullframe(dirty, snap)
                    t0 = time.monotonic()
                    c._send_command(Cmd.LCD_FULLFRAME, payload, allow_frag=True)
                    t1 = time.monotonic()
                    latencies.append((t1 - t0) * 1000.0)
                    frames_sent += 1
                    total_bytes += len(payload)

                # Pace at target interval
                elapsed = time.monotonic() - t_start
                expected = frame_num * (interval_ms / 1000.0)
                sleep_t = expected - elapsed
                if sleep_t > 0:
                    time.sleep(sleep_t)
        finally:
            c._frame_send_enabled = old_enabled

        t_total = time.monotonic() - t_start
        fps = frames_sent / t_total if t_total > 0 else 0
        avg_lat = sum(latencies) / len(latencies) if latencies else 0
        max_lat = max(latencies) if latencies else 0
        min_lat = min(latencies) if latencies else 0
        p95_lat = sorted(latencies)[int(len(latencies) * 0.95)] if latencies else 0

        print(f'[test_frame_rate] Results:')
        print(f'  Duration       : {t_total:.2f}s')
        print(f'  Frames sent    : {frames_sent}')
        print(f'  Achieved FPS   : {fps:.1f}')
        print(f'  Total payload  : {total_bytes} bytes ({total_bytes/1024:.1f} KB)')
        print(f'  Send latency   : avg={avg_lat:.3f}ms  min={min_lat:.3f}ms  '
              f'max={max_lat:.3f}ms  p95={p95_lat:.3f}ms')

    # ---- test: Custom character display ----

    def test_custom_chars(self):
        """Define all 8 custom characters with recognizable patterns and display them.

        Character patterns (5x8 pixel grid):
          0: Heart        1: Smiley      2: Arrow up    3: Arrow down
          4: Bell         5: Note        6: Check       7: Cross
        """
        patterns = {
            0: ('Heart',      bytes([0x00, 0x0A, 0x1F, 0x1F, 0x0E, 0x04, 0x00, 0x00])),
            1: ('Smiley',     bytes([0x00, 0x0A, 0x0A, 0x00, 0x11, 0x0E, 0x00, 0x00])),
            2: ('Arrow Up',   bytes([0x04, 0x0E, 0x15, 0x04, 0x04, 0x04, 0x04, 0x00])),
            3: ('Arrow Down', bytes([0x04, 0x04, 0x04, 0x04, 0x15, 0x0E, 0x04, 0x00])),
            4: ('Bell',       bytes([0x04, 0x0E, 0x0E, 0x0E, 0x1F, 0x00, 0x04, 0x00])),
            5: ('Note',       bytes([0x02, 0x03, 0x02, 0x0E, 0x1E, 0x0C, 0x00, 0x00])),
            6: ('Check',      bytes([0x00, 0x01, 0x03, 0x16, 0x1C, 0x08, 0x00, 0x00])),
            7: ('Cross',      bytes([0x00, 0x11, 0x0A, 0x04, 0x0A, 0x11, 0x00, 0x00])),
        }

        c = self.client
        cols, rows = c.state.cols, c.state.rows
        print(f'[test_custom_chars] Defining 8 custom characters ...')

        for idx, (name, font) in patterns.items():
            c.cmd_custom_char(idx, font)
            # Print 5x8 bitmap preview
            print(f'  Char {idx} ({name}):')
            for row_byte in font:
                bits = ''
                for bit in range(4, -1, -1):
                    bits += '#' if (row_byte >> bit) & 1 else '.'
                print(f'    {bits}')

        # Write the characters to screen
        time.sleep(0.05)
        c.state.clear_screen()

        # Row 0: header
        c.state.set_cursor(0, 0)
        c.state.write_text('Custom Chars 0-7:'[:cols])

        # Row 1: display each custom char by placing bytes 0x00-0x07 directly
        # On a real LCD, byte values 0x00-0x07 map to CGRAM custom characters.
        # We write them directly into the screen buffer.
        if rows > 1:
            with c.state._lock:
                col = 0
                for i in range(8):
                    if col + 3 > cols:
                        break
                    # Write "N:" as ASCII
                    c.state.screen[1][col] = ord(str(i))
                    c.state.screen[1][col + 1] = ord(':')
                    # Write the custom char index byte (0x00-0x07)
                    c.state.screen[1][col + 2] = i
                    col += 4  # "N:X " spacing
                c.state.dirty |= DirtyBit.SCREEN

        # Row 2: CUSTOMCHAR_MASK info
        if rows > 2:
            c.state.set_cursor(0, 2)
            cc_mask = 0
            for idx in patterns:
                cc_mask |= (1 << idx)
            c.state.write_text(f'ccMask=0x{cc_mask:02X} (all 8)'[:cols])

        # Row 3: show that these are CGRAM bytes
        if rows > 3:
            c.state.set_cursor(0, 3)
            c.state.write_text('Bytes: 00-07 = CGRAM'[:cols])

        # Force FULLFRAME with custom chars
        c.cmd_send_fullframe()
        print(f'[test_custom_chars] Sent FULLFRAME with all 8 custom characters')
        print(f'  CUSTOMCHAR_MASK = 0xFF (bits 0-7 all set)')
        print(c.state.display_preview())

    # ---- test: Scrolling text ----

    def test_scroll_text(self, text: str = '', speed_ms: int = 150, loops: int = 2):
        """Horizontal scrolling marquee on row 0.

        Demonstrates continuous screen updates with consistent frame pacing.
        """
        c = self.client
        cols = c.state.cols

        if not text:
            text = 'Hello UDP LCD Protocol! -- Scrolling marquee test -- '

        padded = text + ' ' * cols  # pad so text scrolls off completely
        total_steps = len(padded)
        print(f'[test_scroll] Scrolling "{text[:30]}..." speed={speed_ms}ms, loops={loops}')
        self._stop_flag.clear()

        for loop in range(loops):
            if self._is_stopped():
                break
            for step in range(total_steps):
                if self._is_stopped():
                    break
                window = padded[step:step + cols]
                if len(window) < cols:
                    window = window + padded[:cols - len(window)]
                c.state.set_cursor(0, 0)
                c.state.write_text(window[:cols])
                time.sleep(speed_ms / 1000.0)

        print(f'[test_scroll] Done')

    # ---- test: Full screen fill patterns ----

    def test_fill_patterns(self, hold_ms: int = 500):
        """Cycle through several full-screen fill patterns.

        Patterns: all spaces, all '#', checkerboard, row numbers, column numbers,
        diagonal, border box.
        """
        c = self.client
        cols, rows = c.state.cols, c.state.rows

        def fill(char: str):
            for r in range(rows):
                c.state.set_cursor(0, r)
                c.state.write_text((char * cols)[:cols])

        patterns = []

        # 1: blank
        def p_blank():
            fill(' ')
        patterns.append(('Blank', p_blank))

        # 2: solid fill
        def p_solid():
            fill('#')
        patterns.append(('Solid #', p_solid))

        # 3: checkerboard
        def p_checker():
            for r in range(rows):
                c.state.set_cursor(0, r)
                line = ''
                for col_i in range(cols):
                    line += '#' if (r + col_i) % 2 == 0 else ' '
                c.state.write_text(line)
        patterns.append(('Checkerboard', p_checker))

        # 4: row index display
        def p_rows():
            for r in range(rows):
                c.state.set_cursor(0, r)
                c.state.write_text(f'Row {r}: {"="*(cols-7)}'[:cols])
        patterns.append(('Row index', p_rows))

        # 5: column ruler
        def p_ruler():
            ruler = ''
            for i in range(cols):
                ruler += str(i % 10)
            for r in range(rows):
                c.state.set_cursor(0, r)
                c.state.write_text(ruler)
        patterns.append(('Column ruler', p_ruler))

        # 6: diagonal
        def p_diag():
            fill(' ')
            for r in range(rows):
                c.state.set_cursor(0, r)
                line = [' '] * cols
                for d in range(cols):
                    if (d + r) % 4 == 0:
                        line[d] = '\\'
                c.state.write_text(''.join(line))
        patterns.append(('Diagonal', p_diag))

        # 7: border box
        def p_border():
            fill(' ')
            for r in range(rows):
                c.state.set_cursor(0, r)
                if r == 0 or r == rows - 1:
                    c.state.write_text('+' + '-' * (cols - 2) + '+')
                else:
                    c.state.write_text('|' + ' ' * (cols - 2) + '|')
        patterns.append(('Border box', p_border))

        # 8: custom character fill
        # Define 8 custom chars with distinct bitmaps, then tile them across
        # the entire screen so the device actually renders CGRAM glyphs.
        cc_fonts = {
            0: bytes([0x00, 0x0A, 0x1F, 0x1F, 0x0E, 0x04, 0x00, 0x00]),  # Heart
            1: bytes([0x00, 0x0A, 0x0A, 0x00, 0x11, 0x0E, 0x00, 0x00]),  # Smiley
            2: bytes([0x04, 0x0E, 0x15, 0x04, 0x04, 0x04, 0x04, 0x00]),  # Arrow Up
            3: bytes([0x04, 0x04, 0x04, 0x04, 0x15, 0x0E, 0x04, 0x00]),  # Arrow Down
            4: bytes([0x04, 0x0E, 0x0E, 0x0E, 0x1F, 0x00, 0x04, 0x00]),  # Bell
            5: bytes([0x02, 0x03, 0x02, 0x0E, 0x1E, 0x0C, 0x00, 0x00]),  # Note
            6: bytes([0x00, 0x01, 0x03, 0x16, 0x1C, 0x08, 0x00, 0x00]),  # Check
            7: bytes([0x00, 0x11, 0x0A, 0x04, 0x0A, 0x11, 0x00, 0x00]),  # Cross
        }

        def p_custom():
            # Define all 8 custom chars via protocol commands
            for idx, font in cc_fonts.items():
                c.cmd_custom_char(idx, font)
            # Fill screen buffer with custom char bytes 0x00-0x07 tiled
            with c.state._lock:
                for r in range(rows):
                    for col_i in range(cols):
                        c.state.screen[r][col_i] = (r * cols + col_i) % 8
                c.state.dirty |= DirtyBit.SCREEN
            # Force FULLFRAME with DIRTY_ALL so both custom char data and
            # screen data are sent in one atomic frame
            c.state.mark_all_dirty()
        patterns.append(('Custom chars (CGRAM)', p_custom))

        print(f'[test_fill] Cycling {len(patterns)} patterns, hold={hold_ms}ms each ...')
        self._stop_flag.clear()

        for name, fn in patterns:
            if self._is_stopped():
                break
            fn()
            print(f'  Pattern: {name}')
            print(c.state.display_preview())
            time.sleep(hold_ms / 1000.0)

        print(f'[test_fill] Done')

    # ---- test: Rapid write stress test ----

    def test_stress(self, count: int = 200, interval_ms: float = 10.0):
        """Send a burst of FULLFRAME packets as fast as possible.

        Measures packet loss from the sender's perspective (send errors)
        and reports throughput.
        """
        c = self.client
        cols, rows = c.state.cols, c.state.rows

        old_enabled = c._frame_send_enabled
        c._frame_send_enabled = False

        print(f'[test_stress] Sending {count} FULLFRAME packets at {interval_ms}ms intervals ...')
        errors = 0
        t_start = time.monotonic()
        stats_before = c.get_stats()
        self._stop_flag.clear()

        for i in range(count):
            if self._is_stopped():
                count = i
                break
            # Vary content each frame
            c.state.set_cursor(0, 0)
            c.state.write_text(f'Stress #{i+1:<6d}'.ljust(cols)[:cols])
            for r in range(1, rows):
                c.state.set_cursor(0, r)
                ch = chr(0x21 + (i + r) % 93)  # cycle through printable ASCII
                c.state.write_text((ch * cols)[:cols])

            # Only mark DIRTY_SCREEN; avoid DIRTY_CUSTOMCHAR which triggers
            # expensive CGRAM font rebuild on the Android device side
            dirty, snap = c.state.snapshot_and_clear_dirty()
            if snap:
                payload = c.state.encode_fullframe(dirty, snap)
                try:
                    c._send_command(Cmd.LCD_FULLFRAME, payload, allow_frag=True)
                except Exception:
                    errors += 1

            if interval_ms > 0:
                time.sleep(interval_ms / 1000.0)

        t_total = time.monotonic() - t_start
        stats_after = c.get_stats()
        pkts = stats_after['packets_sent'] - stats_before['packets_sent']
        pps = pkts / t_total if t_total > 0 else 0

        c._frame_send_enabled = old_enabled

        print(f'[test_stress] Results:')
        print(f'  Frames requested : {count}')
        print(f'  Packets sent     : {pkts}')
        print(f'  Send errors      : {errors}')
        print(f'  Duration         : {t_total:.3f}s')
        print(f'  Throughput       : {pps:.1f} pkt/s')

    # ---- test: Packet integrity / hex dump ----

    def test_packet_dump(self):
        """Build one packet of each command type and print hex dump for manual verification."""
        print('[test_packet_dump] Generating hex dumps for all command types ...')
        print()

        test_cases = [
            ('LCD_INIT (20x4)',            Cmd.LCD_INIT,          struct.pack('BB', 20, 4), Flag.ACK_REQ),
            ('LCD_SETBACKLIGHT (on)',       Cmd.LCD_SETBACKLIGHT,  struct.pack('B', 1), Flag.NONE),
            ('LCD_SETCONTRAST (200)',       Cmd.LCD_SETCONTRAST,   struct.pack('B', 200), Flag.NONE),
            ('LCD_SETBRIGHTNESS (128)',     Cmd.LCD_SETBRIGHTNESS, struct.pack('B', 128), Flag.NONE),
            ('LCD_WRITEDATA "Hi"',         Cmd.LCD_WRITEDATA,     struct.pack('<H', 2) + b'Hi', Flag.NONE),
            ('LCD_SETCURSOR (5,2)',         Cmd.LCD_SETCURSOR,     struct.pack('BB', 5, 2), Flag.NONE),
            ('LCD_CUSTOMCHAR (idx=0)',      Cmd.LCD_CUSTOMCHAR,    struct.pack('B', 0) + bytes(8), Flag.NONE),
            ('LCD_WRITECMD (0x01)',         Cmd.LCD_WRITECMD,      struct.pack('B', 0x01), Flag.NONE),
            ('LCD_DE_INIT',                Cmd.LCD_DE_INIT,       b'', Flag.NONE),
            ('HEARTBEAT (PC, seq=0, 10s)', Cmd.HEARTBEAT,         struct.pack('<BBH', 0x01, 0, 10), Flag.NONE),
            ('ENTER_BOOT',                 Cmd.ENTER_BOOT,        b'', Flag.NONE),
            ('ACK (seq=42, ok)',           None,                  None, None),  # special case
        ]

        for i, (desc, cmd, payload, flags) in enumerate(test_cases):
            seq = i + 1
            if cmd is None:
                # ACK packet
                pkt = build_ack_packet(seq=42, status=0)
                desc = 'ACK (seq=42, ok)'
            else:
                pkt = build_packet(seq, flags, cmd, payload)

            hex_str = pkt.hex()
            # Format as grouped hex
            grouped = ' '.join(hex_str[j:j+2] for j in range(0, len(hex_str), 2))
            print(f'  {desc}')
            print(f'    Size: {len(pkt)} bytes')
            print(f'    Hex : {grouped}')

            # Verify roundtrip
            parsed = parse_packet(pkt)
            if parsed:
                if parsed.get('is_ack'):
                    print(f'    Parse: ACK seq={parsed["seq"]} status={parsed["status"]}')
                else:
                    print(f'    Parse: ver={parsed["ver"]} seq={parsed["seq"]} '
                          f'cmd=0x{parsed["cmd"]:02X} flags=0x{parsed["flags"]:02X} '
                          f'payload={parsed["payload"].hex()}')
            else:
                print(f'    Parse: FAILED (CRC error)')
            print()

    # ---- test: FULLFRAME encoding verification ----

    def test_fullframe_encoding(self):
        """Test FULLFRAME payload encoding with various dirty flag combinations."""
        print('[test_fullframe_encoding] Verifying FULLFRAME encoding ...')
        print()

        # Case 1: screen-only dirty
        s1 = LcdState(20, 4)
        s1.set_cursor(0, 0)
        s1.write_text('ABCDEFGHIJKLMNOPQRST')
        d1, snap1 = s1.snapshot_and_clear_dirty()
        ff1 = s1.encode_fullframe(d1, snap1)
        assert ff1[3] == 0x00, 'ccMask should be 0 for screen-only'
        assert len(ff1) == 4 + 20 * 4
        print(f'  Case 1 (screen-only):  size={len(ff1)}, ccMask=0x{ff1[3]:02X}  OK')

        # Case 2: custom char dirty (2 chars)
        s2 = LcdState(20, 4)
        s2.set_custom_char(1, bytes([0x0A]*8))
        s2.set_custom_char(5, bytes([0x15]*8))
        d2, snap2 = s2.snapshot_and_clear_dirty()
        ff2 = s2.encode_fullframe(d2, snap2)
        assert ff2[3] == 0x22, f'ccMask expected 0x22, got 0x{ff2[3]:02X}'  # bit1 + bit5
        cc_data_len = 2 * (1 + 8)
        assert len(ff2) == 4 + cc_data_len + 20 * 4
        print(f'  Case 2 (2 custom chars): size={len(ff2)}, ccMask=0x{ff2[3]:02X}  OK')

        # Case 3: DIRTY_ALL with 8 custom chars (max payload)
        s3 = LcdState(40, 8)
        for i in range(8):
            s3.set_custom_char(i, bytes([i * 0x10 + j for j in range(8)]))
        s3.mark_all_dirty()
        d3, snap3 = s3.snapshot_and_clear_dirty()
        ff3 = s3.encode_fullframe(d3, snap3)
        assert ff3[3] == 0xFF
        expected = 4 + 8 * 9 + 40 * 8
        assert len(ff3) == expected, f'Expected {expected}, got {len(ff3)}'
        print(f'  Case 3 (40x8 + 8 cc):  size={len(ff3)}, ccMask=0x{ff3[3]:02X}  OK')

        # Case 4: minimal (no screen data effectively, but state has 1x1)
        s4 = LcdState(1, 1)
        s4.set_cursor(0, 0)
        s4.write_text('X')
        d4, snap4 = s4.snapshot_and_clear_dirty()
        ff4 = s4.encode_fullframe(d4, snap4)
        assert len(ff4) == 4 + 1, f'Expected 5, got {len(ff4)}'
        assert ff4[4:5] == b'X'
        print(f'  Case 4 (1x1 minimal):  size={len(ff4)}, data="{chr(ff4[4])}"  OK')

        # Case 5: verify custom char ordering in payload
        s5 = LcdState(4, 1)
        s5.set_custom_char(7, bytes([0x77]*8))
        s5.set_custom_char(0, bytes([0x00]*8))
        s5.set_custom_char(3, bytes([0x33]*8))
        d5, snap5 = s5.snapshot_and_clear_dirty()
        ff5 = s5.encode_fullframe(d5, snap5)
        # ccMask = bit0 + bit3 + bit7 = 0x89
        assert ff5[3] == 0x89, f'ccMask expected 0x89, got 0x{ff5[3]:02X}'
        # Verify ordering: index 0, then 3, then 7
        off = 4
        assert ff5[off] == 0, f'First cc index should be 0'
        off += 9
        assert ff5[off] == 3, f'Second cc index should be 3'
        off += 9
        assert ff5[off] == 7, f'Third cc index should be 7'
        print(f'  Case 5 (cc ordering):  ccMask=0x{ff5[3]:02X}, '
              f'order=[{ff5[4]},{ff5[13]},{ff5[22]}]  OK')

        print()
        print('[test_fullframe_encoding] All encoding tests passed')

    # ---- test: Fragmentation ----

    def test_fragmentation(self):
        """Verify large payload fragmentation and packet construction."""
        print('[test_fragmentation] Testing fragmentation logic ...')
        print()

        test_sizes = [100, 1400, 1401, 2800, 4200, 5000]
        for size in test_sizes:
            data = bytes(range(256)) * (size // 256 + 1)
            data = data[:size]
            frags = fragment_payload(data, MAX_PAYLOAD_SIZE)
            total_reassembled = b''.join(frags)
            frag_count = len(frags)
            frag_sizes = [len(f) for f in frags]

            assert total_reassembled == data, f'Reassembly mismatch for size={size}'
            if size <= MAX_PAYLOAD_SIZE:
                assert frag_count == 1
            else:
                assert frag_count == -(-size // MAX_PAYLOAD_SIZE)  # ceil division

            # Build actual packets and verify CRC roundtrip
            seq = 1000
            for idx, frag in enumerate(frags):
                flags = Flag.FRAG if frag_count > 1 else Flag.NONE
                pkt = build_packet(seq, flags, Cmd.LCD_WRITEDATA, frag, idx, frag_count)
                parsed = parse_packet(pkt)
                assert parsed is not None, f'CRC failed for frag {idx} of size {size}'
                assert parsed['frag_idx'] == idx
                assert parsed['frag_total'] == frag_count
                assert parsed['payload'] == frag

            print(f'  {size:5d}B -> {frag_count} frag(s): {frag_sizes}  OK')

        print()
        print('[test_fragmentation] All fragmentation tests passed')

    # ---- test: Animation demo (bouncing ball) ----

    def test_animation(self, duration_s: float = 8.0, fps: int = 15):
        """Bouncing ball animation to demonstrate smooth frame updates."""
        c = self.client
        cols, rows = c.state.cols, c.state.rows
        print(f'[test_animation] Bouncing ball on {cols}x{rows} for {duration_s}s at {fps} FPS ...')

        ball_x, ball_y = 1.0, 1.0
        dx, dy = 1.0, 0.5
        interval = 1.0 / fps
        t_start = time.monotonic()
        frame_count = 0
        self._stop_flag.clear()

        while (time.monotonic() - t_start) < duration_s and not self._is_stopped():
            # Clear and draw
            c.state.clear_screen()

            # Draw border
            for col_i in range(cols):
                with c.state._lock:
                    c.state.screen[0][col_i] = ord('-')
                    c.state.screen[rows - 1][col_i] = ord('-')
            for row_i in range(rows):
                with c.state._lock:
                    c.state.screen[row_i][0] = ord('|')
                    c.state.screen[row_i][cols - 1] = ord('|')
            # Corners
            with c.state._lock:
                for r, cl in [(0,0), (0,cols-1), (rows-1,0), (rows-1,cols-1)]:
                    c.state.screen[r][cl] = ord('+')

            # Update ball position
            ball_x += dx
            ball_y += dy
            if ball_x <= 1 or ball_x >= cols - 2:
                dx = -dx
                ball_x = max(1, min(cols - 2, ball_x))
            if ball_y <= 1 or ball_y >= rows - 2:
                dy = -dy
                ball_y = max(1, min(rows - 2, ball_y))

            bx, by = int(ball_x), int(ball_y)
            if 0 <= by < rows and 0 <= bx < cols:
                with c.state._lock:
                    c.state.screen[by][bx] = ord('O')
                    c.state.dirty |= DirtyBit.SCREEN

            frame_count += 1
            time.sleep(interval)

        actual_fps = frame_count / (time.monotonic() - t_start) if frame_count else 0
        print(f'[test_animation] Done: {frame_count} frames, {actual_fps:.1f} FPS achieved')
        print(c.state.display_preview())

    # ---- test: CRC integrity ----

    def test_crc_integrity(self):
        """Verify CRC-16-CCITT correctness against known vectors and corruption detection."""
        print('[test_crc_integrity] Running CRC verification ...')
        print()

        # Known test vector
        crc = crc16_ccitt(b'123456789')
        ok = crc == 0x29B1
        print(f'  Vector "123456789"  : 0x{crc:04X} (expected 0x29B1)  {"OK" if ok else "FAIL"}')

        # Empty data
        crc_empty = crc16_ccitt(b'')
        print(f'  Vector ""           : 0x{crc_empty:04X} (expected 0xFFFF)  '
              f'{"OK" if crc_empty == 0xFFFF else "FAIL"}')

        # Single byte
        crc_zero = crc16_ccitt(b'\x00')
        print(f'  Vector 0x00         : 0x{crc_zero:04X}')

        # Corruption detection: flip each bit in a packet and verify CRC catches it
        test_payload = b'Test CRC'
        pkt = build_packet(1, Flag.NONE, Cmd.LCD_WRITEDATA,
                           struct.pack('<H', len(test_payload)) + test_payload)
        corruptions_detected = 0
        corruptions_total = len(pkt) * 8
        for byte_idx in range(len(pkt)):
            for bit in range(8):
                corrupted = bytearray(pkt)
                corrupted[byte_idx] ^= (1 << bit)
                result = parse_packet(bytes(corrupted))
                if result is None:
                    corruptions_detected += 1

        # Some bit flips in the CRC field itself might accidentally produce a valid CRC
        # for a different message, but this is extremely rare
        detection_rate = corruptions_detected / corruptions_total * 100
        print(f'  Bit-flip detection  : {corruptions_detected}/{corruptions_total} '
              f'({detection_rate:.1f}%)')
        print()
        print(f'[test_crc_integrity] Complete')

    # ---- test: Frame interval jitter analysis ----

    def test_jitter(self, duration_s: float = 5.0, target_ms: float = 30.0):
        """Measure frame send interval uniformity.

        Sends FULLFRAME packets at the target interval and records the actual
        inter-frame timestamps. Reports jitter statistics: mean interval, stddev,
        min/max deviation from target, and a histogram of deviations.

        Jitter is the enemy of smooth animation. On a well-behaved system the
        stddev should be < 2ms for a 30ms target.
        """
        c = self.client
        cols, rows = c.state.cols, c.state.rows
        target_s = target_ms / 1000.0
        print(f'[test_jitter] Measuring frame interval jitter for {duration_s}s, '
              f'target={target_ms}ms ...')

        old_enabled = c._frame_send_enabled
        c._frame_send_enabled = False

        timestamps: list[float] = []
        t_start = time.monotonic()
        frame_num = 0
        self._stop_flag.clear()

        try:
            while (time.monotonic() - t_start) < duration_s and not self._is_stopped():
                frame_num += 1

                # Minimal screen update to keep it realistic
                c.state.set_cursor(0, 0)
                c.state.write_text(f'Jitter #{frame_num:<8d}'[:cols])
                if rows > 1:
                    c.state.set_cursor(0, 1)
                    elapsed_ms = (time.monotonic() - t_start) * 1000
                    c.state.write_text(f't={elapsed_ms:>8.1f}ms'[:cols])

                dirty, snap = c.state.snapshot_and_clear_dirty()
                if snap:
                    payload = c.state.encode_fullframe(dirty, snap)
                    c._send_command(Cmd.LCD_FULLFRAME, payload, allow_frag=True)
                    timestamps.append(time.monotonic())

                # Pace precisely using cumulative target
                expected_time = t_start + frame_num * target_s
                now = time.monotonic()
                sleep_t = expected_time - now
                if sleep_t > 0:
                    time.sleep(sleep_t)
        finally:
            c._frame_send_enabled = old_enabled

        # Analyze inter-frame intervals
        if len(timestamps) < 2:
            print('[test_jitter] Not enough frames to analyze')
            return

        intervals_ms = [(timestamps[i+1] - timestamps[i]) * 1000.0
                        for i in range(len(timestamps) - 1)]
        n = len(intervals_ms)
        mean = sum(intervals_ms) / n
        variance = sum((x - mean) ** 2 for x in intervals_ms) / n
        stddev = variance ** 0.5
        min_iv = min(intervals_ms)
        max_iv = max(intervals_ms)

        deviations = [iv - target_ms for iv in intervals_ms]
        abs_devs = [abs(d) for d in deviations]
        mean_abs_dev = sum(abs_devs) / n
        max_abs_dev = max(abs_devs)
        p95_abs_dev = sorted(abs_devs)[int(n * 0.95)]
        p99_abs_dev = sorted(abs_devs)[min(int(n * 0.99), n - 1)]

        # Count frames within tolerance bands
        within_1ms = sum(1 for d in abs_devs if d <= 1.0)
        within_2ms = sum(1 for d in abs_devs if d <= 2.0)
        within_5ms = sum(1 for d in abs_devs if d <= 5.0)

        print(f'[test_jitter] Results ({n} intervals):')
        print(f'  Target interval  : {target_ms:.1f}ms')
        print(f'  Actual mean      : {mean:.3f}ms')
        print(f'  Std deviation    : {stddev:.3f}ms')
        print(f'  Min / Max        : {min_iv:.3f}ms / {max_iv:.3f}ms')
        print(f'  Mean |deviation| : {mean_abs_dev:.3f}ms')
        print(f'  Max  |deviation| : {max_abs_dev:.3f}ms')
        print(f'  P95  |deviation| : {p95_abs_dev:.3f}ms')
        print(f'  P99  |deviation| : {p99_abs_dev:.3f}ms')
        print(f'  Within 1ms       : {within_1ms}/{n} ({within_1ms/n*100:.1f}%)')
        print(f'  Within 2ms       : {within_2ms}/{n} ({within_2ms/n*100:.1f}%)')
        print(f'  Within 5ms       : {within_5ms}/{n} ({within_5ms/n*100:.1f}%)')

        # Histogram: bucket deviations into 1ms bins from -10 to +10
        print()
        print('  Deviation histogram (ms):')
        bucket_range = 10
        buckets = [0] * (2 * bucket_range + 1)
        for d in deviations:
            bi = int(round(d))
            bi = max(-bucket_range, min(bucket_range, bi))
            buckets[bi + bucket_range] += 1

        max_count = max(buckets) if buckets else 1
        for i, count in enumerate(buckets):
            label = i - bucket_range
            bar_len = int(count / max_count * 30) if max_count > 0 else 0
            bar = '#' * bar_len
            if count > 0:
                print(f'    {label:+4d}ms [{count:4d}] {bar}')

        # Verdict
        print()
        if stddev < 1.0:
            verdict = 'EXCELLENT (stddev < 1ms)'
        elif stddev < 2.0:
            verdict = 'GOOD (stddev < 2ms)'
        elif stddev < 5.0:
            verdict = 'ACCEPTABLE (stddev < 5ms)'
        else:
            verdict = 'POOR (stddev >= 5ms) - may cause visible stutter'
        print(f'  Verdict: {verdict}')

    # ---- test: Tearing detection ----

    def test_tearing(self, duration_s: float = 5.0, interval_ms: float = 30.0):
        """Vertical tearing detection test.

        Simulates the exact scenario that causes tearing on the device side:
        rapid alternation between two visually distinct full-screen patterns.
        Each frame is entirely pattern A or pattern B. If the device displays
        a mix of both patterns simultaneously, tearing is occurring.

        This test operates in two modes:
          Phase 1 - FULLFRAME mode (should be tear-free):
            Sends complete frames via CMD_LCD_FULLFRAME.
          Phase 2 - Per-row mode (demonstrates tearing):
            Sends each row as a separate CMD_LCD_SETCURSOR + CMD_LCD_WRITEDATA
            with a deliberate inter-row delay, simulating the old approach.

        The test itself cannot detect tearing (that requires visual inspection
        on the device). It reports packet counts and timing so the operator can
        correlate with what they observe on-screen.
        """
        c = self.client
        cols, rows = c.state.cols, c.state.rows
        target_s = interval_ms / 1000.0

        # Define two visually distinct patterns
        pattern_a = [('A' * cols)[:cols] for _ in range(rows)]
        pattern_b = [('B' * cols)[:cols] for _ in range(rows)]
        # Make patterns more visually distinct with alternating chars
        for r in range(rows):
            pattern_a[r] = (''.join('A' if (r + c_) % 2 == 0 else '.' for c_ in range(cols)))[:cols]
            pattern_b[r] = (''.join('B' if (r + c_) % 2 == 1 else ':' for c_ in range(cols)))[:cols]

        old_enabled = c._frame_send_enabled
        c._frame_send_enabled = False

        # ------ Phase 1: FULLFRAME (atomic) ------
        print(f'[test_tearing] Phase 1: FULLFRAME mode (atomic refresh) for {duration_s}s ...')
        print(f'  Alternating pattern A / B every frame at {interval_ms}ms')
        print(f'  Pattern A (even frames):')
        for r in range(min(rows, 3)):
            print(f'    |{pattern_a[r]}|')
        if rows > 3:
            print(f'    ... ({rows} rows total)')
        print(f'  Pattern B (odd frames):')
        for r in range(min(rows, 3)):
            print(f'    |{pattern_b[r]}|')
        if rows > 3:
            print(f'    ... ({rows} rows total)')

        frames_p1 = 0
        t_start = time.monotonic()
        self._stop_flag.clear()

        try:
            while (time.monotonic() - t_start) < duration_s and not self._is_stopped():
                pattern = pattern_a if frames_p1 % 2 == 0 else pattern_b
                # Write full screen atomically
                for r in range(rows):
                    c.state.set_cursor(0, r)
                    c.state.write_text(pattern[r])

                dirty, snap = c.state.snapshot_and_clear_dirty()
                if snap:
                    payload = c.state.encode_fullframe(dirty, snap)
                    c._send_command(Cmd.LCD_FULLFRAME, payload, allow_frag=True)

                frames_p1 += 1
                # Pace
                expected = t_start + frames_p1 * target_s
                sleep_t = expected - time.monotonic()
                if sleep_t > 0:
                    time.sleep(sleep_t)
        finally:
            pass

        t_p1 = time.monotonic() - t_start
        fps_p1 = frames_p1 / t_p1 if t_p1 > 0 else 0

        print(f'  Result: {frames_p1} frames in {t_p1:.2f}s ({fps_p1:.1f} FPS)')
        print(f'  Expect: NO tearing (device receives atomic frames)')
        print()

        # ------ Phase 2: Per-row send (tear-prone) ------
        row_delay_ms = 3.0  # deliberate inter-row delay to maximize tearing window
        print(f'[test_tearing] Phase 2: Per-row mode (tear-prone) for {duration_s}s ...')
        print(f'  Sending each row as separate SETCURSOR+WRITEDATA')
        print(f'  Inter-row delay: {row_delay_ms}ms (simulates sequential UDP sends)')

        frames_p2 = 0
        packets_p2 = 0
        t_start2 = time.monotonic()
        self._stop_flag.clear()

        try:
            while (time.monotonic() - t_start2) < duration_s and not self._is_stopped():
                pattern = pattern_a if frames_p2 % 2 == 0 else pattern_b

                for r in range(rows):
                    # Send SETCURSOR
                    c._send_command(Cmd.LCD_SETCURSOR,
                                    struct.pack('BB', 0, r & 0xFF))
                    packets_p2 += 1

                    # Send WRITEDATA
                    row_bytes = pattern[r].encode('utf-8')
                    payload = struct.pack('<H', len(row_bytes)) + row_bytes
                    c._send_command(Cmd.LCD_WRITEDATA, payload)
                    packets_p2 += 1

                    # Inter-row delay: this is the tearing window
                    if r < rows - 1:
                        time.sleep(row_delay_ms / 1000.0)

                frames_p2 += 1
                # Pace between frames
                expected2 = t_start2 + frames_p2 * target_s
                sleep_t2 = expected2 - time.monotonic()
                if sleep_t2 > 0:
                    time.sleep(sleep_t2)
        finally:
            c._frame_send_enabled = old_enabled

        t_p2 = time.monotonic() - t_start2
        fps_p2 = frames_p2 / t_p2 if t_p2 > 0 else 0
        tear_window = row_delay_ms * (rows - 1)

        print(f'  Result: {frames_p2} frames, {packets_p2} packets in {t_p2:.2f}s '
              f'({fps_p2:.1f} FPS)')
        print(f'  Packets per frame: {rows * 2} (SETCURSOR + WRITEDATA per row)')
        print(f'  Tearing window   : {tear_window:.1f}ms per frame '
              f'({row_delay_ms}ms x {rows-1} row gaps)')
        print(f'  Expect: VISIBLE tearing (device updates rows at different times)')
        print()

        # ------ Summary ------
        print(f'[test_tearing] Comparison:')
        print(f'  {"Mode":<20s} {"Frames":>7s} {"Pkts/Frame":>10s} '
              f'{"FPS":>6s} {"Tearing":>10s}')
        print(f'  {"FULLFRAME":<20s} {frames_p1:>7d} {"1":>10s} '
              f'{fps_p1:>6.1f} {"None":>10s}')
        print(f'  {"Per-Row":<20s} {frames_p2:>7d} {rows*2:>10d} '
              f'{fps_p2:>6.1f} {f"{tear_window:.0f}ms win":>10s}')
        print()
        print('  Observe the device screen during both phases.')
        print('  Phase 1 should show clean A/B alternation.')
        print('  Phase 2 should show mixed A+B rows (tearing).')


# ---------------------------------------------------------------------------
# Interactive CLI
# ---------------------------------------------------------------------------

HELP_TEXT = """\
=== UDP LCD Test Client - Interactive Commands ===

  Basic Commands:
    init [cols] [rows]        Initialize LCD (default 20x4)
    deinit                    De-initialize LCD
    cursor <x> <y>            Set cursor position
    write <text>              Write text at cursor
    clear                     Clear screen
    backlight <0|1>           Set backlight off/on
    contrast <0~255>          Set contrast
    brightness <0~255>        Set brightness
    customchar <idx> <hex>    Define custom char (e.g. customchar 0 0E1111110E00)
    writecmd <byte>           Send raw LCD command byte
    fullframe                 Force-send a FULLFRAME packet
    boot                      Enter bootloader mode

  Display & Debug:
    preview                   Show screen buffer preview
    status                    Show connection status and stats
    log <debug|info|warning>  Set log level

  Test Scenarios:
    test fps [dur_s] [intv_ms]    Frame rate benchmark (default: 5s, 30ms)
    test customchar               Define & display all 8 custom characters
    test scroll [speed_ms] [loops] Scrolling marquee (default: 150ms, 2 loops)
    test fill [hold_ms]           Cycle through fill patterns (default: 500ms)
    test stress [count] [intv_ms] Burst FULLFRAME packets (default: 200, 10ms)
    test animation [dur_s] [fps]  Bouncing ball animation (default: 8s, 15fps)
    test dump                     Hex dump of all command packet types
    test encoding                 Verify FULLFRAME encoding logic
    test fragment                 Verify fragmentation logic
    test crc                      CRC-16 integrity verification
    test jitter [dur_s] [tgt_ms]  Frame interval jitter analysis (default: 5s, 30ms)
    test tearing [dur_s] [intv_ms] Vertical tearing detection test (default: 5s, 30ms)
    test all                      Run all offline tests sequentially

  help                      Show this help
  quit / exit               Disconnect and exit
"""


def interactive_cli(client: UdpLcdClient):
    """Run the interactive command-line interface."""
    print(HELP_TEXT)
    runner = TestRunner(client)

    while True:
        try:
            line = input('lcd> ').strip()
        except (EOFError, KeyboardInterrupt):
            print()
            break
        if not line:
            continue

        parts = line.split(maxsplit=1)
        cmd = parts[0].lower()
        args = parts[1] if len(parts) > 1 else ''

        try:
            if cmd in ('quit', 'exit'):
                break
            elif cmd == 'help':
                print(HELP_TEXT)
            elif cmd == 'init':
                tokens = args.split()
                cols = int(tokens[0]) if len(tokens) > 0 else 20
                rows = int(tokens[1]) if len(tokens) > 1 else 4
                ok = client.cmd_init(cols, rows, ack=True)
                print(f'INIT {cols}x{rows} -> {"OK" if ok else "FAIL (no ACK)"}')
            elif cmd == 'deinit':
                client.cmd_deinit()
                print('DE_INIT sent')
            elif cmd == 'cursor':
                tokens = args.split()
                if len(tokens) < 2:
                    print('Usage: cursor <x> <y>')
                    continue
                x, y = int(tokens[0]), int(tokens[1])
                client.cmd_set_cursor(x, y)
                print(f'Cursor -> ({x}, {y})')
            elif cmd == 'write':
                if not args:
                    print('Usage: write <text>')
                    continue
                client.cmd_write_data(args)
                print(f'Wrote: {args}')
            elif cmd == 'clear':
                client.state.clear_screen()
                print('Screen cleared')
            elif cmd == 'backlight':
                val = int(args) if args else 1
                client.cmd_set_backlight(val)
                print(f'Backlight -> {val}')
            elif cmd == 'contrast':
                val = int(args) if args else 128
                client.cmd_set_contrast(val)
                print(f'Contrast -> {val}')
            elif cmd == 'brightness':
                val = int(args) if args else 255
                client.cmd_set_brightness(val)
                print(f'Brightness -> {val}')
            elif cmd == 'customchar':
                tokens = args.split()
                if len(tokens) < 2:
                    print('Usage: customchar <index 0~7> <16-hex-chars>')
                    continue
                idx = int(tokens[0])
                hex_str = tokens[1]
                font = bytes.fromhex(hex_str)
                if len(font) != 8:
                    print(f'Font data must be 8 bytes (16 hex chars), got {len(font)}')
                    continue
                client.cmd_custom_char(idx, font)
                print(f'CustomChar[{idx}] defined')
            elif cmd == 'writecmd':
                val = int(args, 0) if args else 0
                client.cmd_write_cmd(val)
                print(f'WriteCMD -> 0x{val:02X}')
            elif cmd == 'fullframe':
                client.cmd_send_fullframe()
            elif cmd == 'boot':
                client.cmd_enter_boot()
                print('ENTER_BOOT sent')
            elif cmd == 'preview':
                print(client.state.display_preview())
            elif cmd == 'status':
                st = client.conn_status
                stats = client.get_stats()
                print(f'Connection : {st.name}')
                print(f'Target     : {client.host}:{client.port}')
                print(f'LCD Size   : {client.state.cols}x{client.state.rows}')
                print(f'HB miss    : {client._hb_miss_count}/{HB_MISS_MAX}')
                for k, v in stats.items():
                    print(f'  {k:20s}: {v}')
            elif cmd == 'log':
                level_map = {'debug': logging.DEBUG, 'info': logging.INFO, 'warning': logging.WARNING}
                lvl = level_map.get(args.lower().strip(), None)
                if lvl is None:
                    print('Usage: log <debug|info|warning>')
                else:
                    client.log.setLevel(lvl)
                    print(f'Log level -> {args.upper().strip()}')
            elif cmd == 'test':
                _handle_test_command(runner, args)
            else:
                print(f'Unknown command: {cmd}. Type "help" for available commands.')
        except Exception as e:
            print(f'Error: {e}')


def _handle_test_command(runner: TestRunner, args: str):
    """Dispatch test sub-commands."""
    tokens = args.split()
    if not tokens:
        print('Usage: test <fps|customchar|scroll|fill|stress|animation|dump|encoding|fragment|crc|jitter|tearing|all>')
        return

    sub = tokens[0].lower()
    params = tokens[1:]

    if sub == 'fps':
        dur = float(params[0]) if len(params) > 0 else 5.0
        intv = float(params[1]) if len(params) > 1 else 30.0
        runner.test_frame_rate(duration_s=dur, interval_ms=intv)
    elif sub == 'customchar':
        runner.test_custom_chars()
    elif sub == 'scroll':
        speed = int(params[0]) if len(params) > 0 else 150
        loops = int(params[1]) if len(params) > 1 else 2
        runner.test_scroll_text(speed_ms=speed, loops=loops)
    elif sub == 'fill':
        hold = int(params[0]) if len(params) > 0 else 500
        runner.test_fill_patterns(hold_ms=hold)
    elif sub == 'stress':
        count = int(params[0]) if len(params) > 0 else 200
        intv = float(params[1]) if len(params) > 1 else 10.0
        runner.test_stress(count=count, interval_ms=intv)
    elif sub == 'animation':
        dur = float(params[0]) if len(params) > 0 else 8.0
        fps = int(params[1]) if len(params) > 1 else 15
        runner.test_animation(duration_s=dur, fps=fps)
    elif sub == 'dump':
        runner.test_packet_dump()
    elif sub == 'encoding':
        runner.test_fullframe_encoding()
    elif sub == 'fragment':
        runner.test_fragmentation()
    elif sub == 'crc':
        runner.test_crc_integrity()
    elif sub == 'jitter':
        dur = float(params[0]) if len(params) > 0 else 5.0
        tgt = float(params[1]) if len(params) > 1 else 30.0
        runner.test_jitter(duration_s=dur, target_ms=tgt)
    elif sub == 'tearing':
        dur = float(params[0]) if len(params) > 0 else 5.0
        intv = float(params[1]) if len(params) > 1 else 30.0
        runner.test_tearing(duration_s=dur, interval_ms=intv)
    elif sub == 'all':
        print('=' * 60)
        print('Running all offline tests ...')
        print('=' * 60)
        for name, fn in [
            ('CRC Integrity', runner.test_crc_integrity),
            ('Packet Dump', runner.test_packet_dump),
            ('FULLFRAME Encoding', runner.test_fullframe_encoding),
            ('Fragmentation', runner.test_fragmentation),
        ]:
            print()
            print(f'--- {name} ---')
            try:
                fn()
            except Exception as e:
                print(f'  FAILED: {e}')
        print()
        print('=' * 60)
        print('All offline tests complete')
        print('=' * 60)
    else:
        print(f'Unknown test: {sub}')
        print('Available: fps, customchar, scroll, fill, stress, animation, dump, encoding, fragment, crc, jitter, tearing, all')


# ---------------------------------------------------------------------------
# Entry Point
# ---------------------------------------------------------------------------

def main():
    parser = argparse.ArgumentParser(
        description='UDP LCD Protocol Test Client (PC Side)',
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog='Example:\n  python udp_lcd_client.py --host 192.168.1.100 --port 12345 --cols 20 --rows 4'
    )
    parser.add_argument('--host', default='127.0.0.1', help='Device IP address (default: 127.0.0.1)')
    parser.add_argument('--port', type=int, default=12345, help='Device UDP port (default: 12345)')
    parser.add_argument('--cols', type=int, default=20, help='LCD columns (default: 20)')
    parser.add_argument('--rows', type=int, default=4, help='LCD rows (default: 4)')
    parser.add_argument('--log', default='info', choices=['debug', 'info', 'warning'],
                        help='Log level (default: info)')
    parser.add_argument('--no-frame-thread', action='store_true',
                        help='Disable automatic FULLFRAME send thread')
    args = parser.parse_args()

    level_map = {'debug': logging.DEBUG, 'info': logging.INFO, 'warning': logging.WARNING}

    client = UdpLcdClient(
        host=args.host,
        port=args.port,
        cols=args.cols,
        rows=args.rows,
        log_level=level_map[args.log],
    )
    if args.no_frame_thread:
        client._frame_send_enabled = False

    client.connect()

    try:
        interactive_cli(client)
    finally:
        client.disconnect()
        print('Bye.')


if __name__ == '__main__':
    main()
