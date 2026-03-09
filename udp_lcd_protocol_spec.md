# UDP 实时 LCD 控制协议规范（详细版）

## 1️⃣ 协议设计目标

- **低延迟实时性**：适合 LCD 实时显示、动画、滚动文字。
- **可选可靠性**：关键命令可使用 ACK 确认，保证命令不丢失。
- **分片支持**：适应大数据传输，如长字符串或自定义字模。
- **完整性校验**：每个包带 CRC-16 校验。
- **版本管理**：协议版本号字段便于向下兼容。
- **双向心跳**：PC 端与设备端互相发送心跳，主动检测链路断开并触发自动重连。

---

## 2️⃣ 数据包总览

每个 UDP 包的结构如下（字节顺序 **小端序**）：

```
+------+-----+-------+-----+----------+-----------+---------+---------+
| VER  | SEQ | FLAGS | CMD | FRAG_IDX | FRAG_TOTAL| LEN     | PAYLOAD |
| 1B   | 2B  | 1B    | 1B  | 1B       | 1B        | 2B      | 0~N B  |
+------+-----+-------+-----+----------+-----------+---------+---------+
                                        +---------+
                                        | CRC16   |
                                        | 2B      |
                                        +---------+
```

- **总长度限制**：建议单包 ≤ 1472B（以太网 MTU 1500B 减去 UDP/IP 头 28B）。  
- **字段说明**：  

| 字段         | 类型     | 长度      | 说明                                    |
| ---------- | ------ | ------- | ------------------------------------- |
| VER        | uint8  | 1B      | 协议版本号，当前为 0x01                        |
| SEQ        | uint16 | 2B      | 数据包序号（0~65535），用于丢包检测和 ACK            |
| FLAGS      | uint8  | 1B      | 标志位：bit0=ACK_REQ, bit1=FRAG, bit2~7保留 |
| CMD        | uint8  | 1B      | 命令码（CMD_LCD_*）                        |
| FRAG_IDX   | uint8  | 1B      | 分片索引，FLAGS.FRAG=1 时有效，从0开始            |
| FRAG_TOTAL | uint8  | 1B      | 分片总数，FLAGS.FRAG=1 时有效                 |
| LEN        | uint16 | 2B      | Payload长度（字节数）                        |
| PAYLOAD    | byte[] | 0~1400B | 命令数据                                  |
| CRC16      | uint16 | 2B      | CRC-16 校验（覆盖 VER 到 PAYLOAD）           |

---

## 3️⃣ FLAGS 字段定义

| Bit | 名称      | 描述                  |
| --- | ------- | ------------------- |
| 0   | ACK_REQ | 1=要求接收端返回 ACK，0=不要求 |
| 1   | FRAG    | 1=分片包，0=完整包         |
| 2-7 | 保留      | 保留备用，用于未来扩展         |

---

## 4️⃣ 分片机制

1. **适用场景**：当单个 Payload > 1400B 时必须分片。  
2. **字段使用**：FRAG_IDX 和 FRAG_TOTAL 指明分片顺序和总片数。  
3. **重组**：
   - 接收端缓存同 SEQ 的分片，按 FRAG_IDX 升序合并。  
   - 所有分片到齐且 CRC 校验通过后再交给应用层。  
4. **丢包策略**：
   - 非关键命令可直接丢弃丢失分片。  
   - 关键命令可选择要求 ACK 并重发丢失分片。  
5. **示例**：
   - 长字符串 4000B，分片长度 1000B → 需 4 个分片：
     
     ```
     FRAG_TOTAL=4, FRAG_IDX=0..3
     SEQ 相同，保证属于同一逻辑命令
     ```

---

## 5️⃣ CRC 校验

- **算法**：CRC-16-CCITT，POLY=0x1021，INIT=0xFFFF。  
- **范围**：覆盖从 VER 到 PAYLOAD 的所有字节，不包括 CRC 字段本身。  
- **校验失败处理**：
  - 可丢弃该包/分片。  
  - 对 ACK_REQ 包，可请求重发。  

---

## 6️⃣ ACK 机制（可选）

### 6.1 ACK 包结构

```
+------+-----+-----+--------+--------+
| VER  | SEQ | CMD | STATUS | CRC16  |
| 1B   | 2B  | 1B  | 1B     | 2B     |
+------+-----+-----+--------+--------+
```

- **字段说明**：
  - VER: 协议版本号  
  - SEQ: 被确认的数据包序号  
  - CMD: 0xFF 表示 ACK  
  - STATUS: 0=成功，1=失败  
  - CRC16: CRC-16 校验（覆盖 VER~STATUS）  

### 6.2 使用流程

1. 发送端设置 FLAGS.ACK_REQ=1 请求 ACK。  
2. 接收端收到包后返回 ACK 包。  
3. 发送端在设定超时时间（如 50ms）内未收到 ACK，可重发。  
4. ACK 可仅用于关键命令（INIT、CURSOR、CUSTOMCHAR）。  

---

## 7️⃣ 命令定义（兼容 TCP 版）

| CMD  | 描述                    | PAYLOAD 说明          | 分片允许 |
| ---- | --------------------- | ------------------- | ---- |
| 0x01 | CMD_LCD_INIT          | col(1B), row(1B)    | ❌    |
| 0x02 | CMD_LCD_SETBACKLIGHT  | value(1B)           | ❌    |
| 0x03 | CMD_LCD_SETCONTRAST   | value(1B)           | ❌    |
| 0x04 | CMD_LCD_SETBRIGHTNESS | value(1B)           | ❌    |
| 0x05 | CMD_LCD_WRITEDATA     | len(2B), data(lenB) | ✅    |
| 0x06 | CMD_LCD_SETCURSOR     | x(1B), y(1B)        | ❌    |
| 0x07 | CMD_LCD_CUSTOMCHAR    | index(1B), font(8B) | ✅    |
| 0x08 | CMD_LCD_WRITECMD      | cmd(1B)             | ❌    |
| 0x0B | CMD_LCD_DE_INIT       | 无                   | ❌    |
| 0x0C | CMD_HEARTBEAT         | 见 §12 心跳机制          | ❌    |
| 0x0D | CMD_LCD_FULLFRAME     | 见 §13 全屏帧同步         | ✅    |
| 0x19 | CMD_ENTER_BOOT        | 无                   | ❌    |
| 0xFF | ACK                   | STATUS(1B)          | ❌    |

---

## 8️⃣ Payload 类型说明

- **字符串数据**：
  - UTF-8 编码。  
  - 长字符串应分片传输。  
- **字模数据**：
  - 每个自定义字符 8B。  
  - 可以分片传输多字符。  
- **数值型命令**：
  - 均为单字节数值（0~255）。  
  - 不建议分片。  

---

## 9️⃣ 发送端流程（概念）

1. 构建命令 Payload。  
2. 判断是否超长，若超长则切分为分片包。  
3. 填写包头：VER、SEQ、FLAGS、CMD、FRAG_IDX、FRAG_TOTAL、LEN。  
4. 计算 CRC16 并附加。  
5. 通过 UDP 发送。  
6. 若 FLAGS.ACK_REQ=1，等待 ACK，超时可重发。  

---

## 🔟 接收端流程（概念）

1. 接收 UDP 包，校验 CRC16。  
2. 若 FLAGS.FRAG=1，缓存分片，等待所有分片到齐后重组 Payload。  
3. 根据 CMD 调用应用层处理逻辑。  
4. 若 FLAGS.ACK_REQ=1，发送 ACK 包。  

---

## 11️⃣ 注意事项

- **序号 SEQ**：0~65535，发送端循环使用。
- **超长字符串**：每片 Payload ≤ 1400B。
- **丢包处理**：实时命令可丢弃，关键命令可要求 ACK 并重发。
- **版本管理**：VER=0x01，目前仅向下兼容。
- **扩展性**：FLAGS 保留位可用于未来扩展，例如压缩、加密标志。

---

## 12️⃣ 双向心跳机制

### 12.1 概述

心跳用于 **主动检测链路存活**。PC 端和设备端各自独立维护心跳定时器，任一方在超时阈值内未收到对方的心跳包，即判定链路断开。

与 `CMD_ECHO` (0x09) 不同，`CMD_HEARTBEAT` (0x0C) 有专属语义：
- 不依赖 ACK 机制（心跳本身就是存活证明，无需再套一层确认）。
- 接收方收到心跳后重置自己的超时计数器，无需回复同一个包。
- 双方各自主动发送，非请求-响应模式。

### 12.2 默认参数

| 参数                    | 符号               | 默认值 | 说明                          |
| --------------------- | ---------------- | --- | --------------------------- |
| 心跳发送间隔                | `HB_INTERVAL`    | 3 s | 每方每隔此时间发送一个心跳包              |
| 连续丢失判定次数              | `HB_MISS_MAX`    | 3   | 连续未收到对方心跳的次数                |
| 断连判定超时                | （推导值）            | 9 s | `HB_INTERVAL × HB_MISS_MAX` |

以上参数在协议层写死，双方编译时使用相同常量，不需要运行时协商。

### 12.3 心跳包结构

心跳使用标准数据包格式，`CMD = 0x0C`，`FLAGS.ACK_REQ = 0`，`FLAGS.FRAG = 0`。

**Payload 定义（4 字节）：**

```
+------------+------------+
| ROLE       | HB_SEQ     |
| 1B         | 1B         |
+------------+------------+
| UPTIME                  |
| 2B (LE)                 |
+-------------------------+
```

| 字段     | 类型     | 长度 | 说明                                       |
| ------ | ------ | -- | ---------------------------------------- |
| ROLE   | uint8  | 1B | 发送方角色：`0x01` = PC 端，`0x02` = 设备端         |
| HB_SEQ | uint8  | 1B | 心跳序号（0~255 循环），用于调试和丢包率统计，不用于可靠性保证       |
| UPTIME | uint16 | 2B | 发送方运行时间（秒），上限 65535 后回绕。PC 端填 DLL 加载后的秒数，设备端填上电后的秒数 |

**完整包示例（PC → 设备，第 42 次心跳，DLL 运行了 126 秒）：**

```
VER=0x01  SEQ=<当前序号>  FLAGS=0x00  CMD=0x0C
FRAG_IDX=0  FRAG_TOTAL=1  LEN=0x0004
PAYLOAD: 01 2A 7E 00
         ── ── ─────
         │  │  └─ UPTIME=126 (0x007E, LE)
         │  └─── HB_SEQ=42
         └────── ROLE=PC(0x01)
CRC16: <计算值>
```

### 12.4 双方行为

#### PC 端（DLL 侧）

```
初始化时:
  miss_count = 0
  启动心跳发送线程（每 HB_INTERVAL 发送一次 CMD_HEARTBEAT）
  启动心跳接收监测（检查 miss_count）

每 HB_INTERVAL:
  发送 CMD_HEARTBEAT (ROLE=0x01)
  miss_count++
  if miss_count >= HB_MISS_MAX:
      判定断连 → globalConnStatus = DISCONNECTED
      触发重连流程

收到设备端 CMD_HEARTBEAT (ROLE=0x02):
  miss_count = 0
  if globalConnStatus == DISCONNECTED:
      判定恢复 → 触发状态恢复流程
```

#### 设备端（MCU 侧）

```
初始化时:
  miss_count = 0
  启动心跳发送定时器（每 HB_INTERVAL 发送一次 CMD_HEARTBEAT）

每 HB_INTERVAL:
  发送 CMD_HEARTBEAT (ROLE=0x02)
  miss_count++
  if miss_count >= HB_MISS_MAX:
      判定 PC 端断开 → 进入待机/屏保模式（可选）

收到 PC 端 CMD_HEARTBEAT (ROLE=0x01):
  miss_count = 0
  if 当前处于待机模式:
      退出待机 → 恢复正常显示
```

### 12.5 与普通命令的交互

- 普通命令（`CMD_LCD_WRITEDATA` 等）的成功发送/接收 **不重置** 心跳超时计数器。心跳是独立通道，与业务命令互不干扰。这样即使业务命令因队列阻塞停滞，心跳仍能准确反映链路状态。
- 心跳包使用 `FLAGS.ACK_REQ = 0`，不触发 ACK 重试，不占用 ACK 等待时间。
- 心跳包 SEQ 与业务命令共享同一序号空间，无需单独维护。

### 12.6 重连后的状态恢复流程

当 PC 端检测到链路从断开恢复为连通（收到设备心跳且之前处于断连状态），按以下顺序恢复：

```
1. UdpInit()                          ← 仅在 socket 已关闭时重建
2. CMD_LCD_INIT + ACK                 ← 重新初始化 LCD 尺寸
3. CMD_LCD_FULLFRAME (dirtyBits=ALL)  ← 一次全帧发送恢复全部状态
```

`CMD_LCD_FULLFRAME` 携带 `dirtyBits = DIRTY_ALL`，此时 CUSTOMCHAR_MASK 包含所有自定义字符，加上屏幕数据、对比度、背光、亮度，一包即可恢复完整显示状态。

**恢复策略：**
- `CMD_LCD_INIT` 使用 ACK 确认。ACK 失败则中止恢复，等待下一次心跳周期重试。
- `CMD_LCD_FULLFRAME` 不使用 ACK（与正常帧发送一致），依赖下一帧覆盖。
- 恢复过程中暂停心跳发送，避免恢复命令和心跳包交叉干扰。恢复完成后立即恢复心跳。

### 12.7 时序图

```
PC 端                                          设备端
  │                                               │
  │──── CMD_HEARTBEAT (ROLE=0x01, HB_SEQ=0) ─────>│  t=0s
  │<─── CMD_HEARTBEAT (ROLE=0x02, HB_SEQ=0) ──────│  t≈0s
  │                                               │
  │──── CMD_LCD_WRITEDATA (业务命令) ──────────────>│  t=1s
  │                                               │
  │──── CMD_HEARTBEAT (ROLE=0x01, HB_SEQ=1) ─────>│  t=3s
  │<─── CMD_HEARTBEAT (ROLE=0x02, HB_SEQ=1) ──────│  t≈3s
  │                                               │
  │──── CMD_HEARTBEAT (ROLE=0x01, HB_SEQ=2) ─────>│  t=6s
  │                    ╳ 网络断开 ╳                  │  t=6.5s
  │──── CMD_HEARTBEAT (ROLE=0x01, HB_SEQ=3) ──X   │  t=9s  miss=1
  │──── CMD_HEARTBEAT (ROLE=0x01, HB_SEQ=4) ──X   │  t=12s miss=2
  │──── CMD_HEARTBEAT (ROLE=0x01, HB_SEQ=5) ──X   │  t=15s miss=3
  │                                               │
  │  PC判定断连 (miss >= HB_MISS_MAX)               │  设备判定断连
  │  globalConnStatus = DISCONNECTED               │  进入待机模式
  │                                               │
  │                    ✓ 网络恢复 ✓                  │  t=20s
  │──── CMD_HEARTBEAT (ROLE=0x01, HB_SEQ=6) ─────>│
  │<─── CMD_HEARTBEAT (ROLE=0x02, HB_SEQ=N) ──────│
  │                                               │
  │  PC 收到设备心跳 → miss_count=0                   │  设备退出待机
  │  触发恢复流程:                                    │
  │──── CMD_LCD_INIT (ACK) ──────────────────────>│
  │<─── ACK ──────────────────────────────────────│
  │──── CMD_LCD_FULLFRAME (DIRTY_ALL) ───────────>│
  │     (含全部自定义字符 + 屏幕数据)                   │
  │                                               │
  │  恢复完成，继续正常心跳                              │
  │──── CMD_HEARTBEAT (ROLE=0x01, HB_SEQ=7) ─────>│  t=23s
  │                                               │
```

---

## 13️⃣ 全屏帧同步机制（CMD_LCD_FULLFRAME）

### 13.1 设计动机

在原有逐命令发送模式下，PC 端每次 `SetCursor` + `WriteData` 都单独发包。由于各行的 UDP 包到达设备端的时间不同，导致 Android 侧出现 **垂直撕裂（tearing）** 现象——用户肉眼可见上半屏为新内容、下半屏为旧内容。

`CMD_LCD_FULLFRAME` 将整个屏幕状态打包为一个 UDP 包发送，设备端在一次 UI 更新中原子性地刷新所有内容，彻底消除撕裂。

### 13.2 PC 端架构：状态快照 + 脏标志

PC 端 DLL 采用 **状态快照 + 脏标志** 模型：

1. 所有 `DISPLAYDLL_*` 导出函数仅更新内存快照（`gLcdState`）并置脏标志位，**立即返回**，不触发任何 UDP 发送。
2. 后台 **FrameSendThread** 每 30ms 轮询脏标志：
   - 若有脏位，原子读取并清除标志，拷贝一份快照。
   - 将快照编码为 `CMD_LCD_FULLFRAME` 包并发送。
3. 同类型连续操作（如快速多次 `Write`）天然"覆盖"——因为只有最新快照被发送。

**脏标志位定义：**

| Bit | 名称              | 触发者                     |
| --- | --------------- | ----------------------- |
| 0   | DIRTY_SCREEN    | `DISPLAYDLL_Write`       |
| 1   | DIRTY_CONTRAST  | `DISPLAYDLL_SetContrast` |
| 2   | DIRTY_BACKLIGHT | `DISPLAYDLL_SetBacklight`|
| 3   | DIRTY_BRIGHTNESS| `DISPLAYDLL_SetBrightness`|
| 4   | DIRTY_CUSTOMCHAR| `DISPLAYDLL_CustomChar`  |
| 5   | DIRTY_INIT      | `DISPLAYDLL_Init`        |

### 13.3 CMD_LCD_FULLFRAME 包结构

**CMD = 0x0D**, `FLAGS.ACK_REQ = 0`, `FLAGS.FRAG` 按需（通常不需要分片）。

**Payload 布局（v2 lean）：**

```
+----------+-----------+------------+-----------------+
| CONTRAST | BACKLIGHT | BRIGHTNESS | CUSTOMCHAR_MASK |
| 1B       | 1B        | 1B         | 1B              |
+----------+-----------+------------+-----------------+
| [INDEX(1B) + FONT(8B)] × N  (N = popcount(CUSTOMCHAR_MASK))    |
+-----------------------------------------------------------------+
| SCREEN_DATA (COL × ROW bytes, 行优先)                            |
+-----------------------------------------------------------------+
```

COL/ROW **不包含在 payload 中**——设备端从 `CMD_LCD_INIT` 获取并缓存屏幕尺寸。

| 字段              | 类型     | 长度          | 说明                                                         |
| --------------- | ------ | ----------- | ---------------------------------------------------------- |
| CONTRAST        | uint8  | 1B          | 对比度 (0~255)                                                |
| BACKLIGHT       | uint8  | 1B          | 背光开关 (0=关, 1=开)                                           |
| BRIGHTNESS      | uint8  | 1B          | 亮度 (0~255)                                                 |
| CUSTOMCHAR_MASK | uint8  | 1B          | 自定义字符位掩码，bit i=1 表示自定义字符 i 包含在本包中。正常帧发送时为 0（无自定义字符数据），仅在自定义字符变化或重连恢复时非零 |
| INDEX           | uint8  | 1B          | 自定义字符索引 (0~7)                                              |
| FONT            | byte[] | 8B          | 5×8 字模数据                                                   |
| SCREEN_DATA     | byte[] | COL×ROW     | 屏幕字符数据，行优先排列                                               |

**CUSTOMCHAR_MASK 的取值逻辑：**
- **正常帧**（`dirtyBits` 中不含 `DIRTY_CUSTOMCHAR`）：CUSTOMCHAR_MASK = 0x00，payload 中不包含任何自定义字符数据，设备端跳过字体重建。
- **自定义字符变化帧**（`dirtyBits` 中含 `DIRTY_CUSTOMCHAR`）：CUSTOMCHAR_MASK 反映当前活跃的自定义字符，payload 中携带对应字体数据。
- **重连恢复帧**（`dirtyBits = DIRTY_ALL`）：CUSTOMCHAR_MASK 包含全部已定义的自定义字符。

**Payload 大小范围：**
- 最小：4 字节（无自定义字符, 无屏幕数据——理论边界）
- 典型：4 + 4×20 = 84 字节（4 行 20 列, 无自定义字符变化）
- 最大：4 + 8×(1+8) + 8×40 = 396 字节（8 行 40 列 + 8 个自定义字符）

### 13.4 设备端处理

设备端收到 `CMD_LCD_FULLFRAME` 后：

1. 解析固定头部（CONTRAST, BACKLIGHT, BRIGHTNESS, CUSTOMCHAR_MASK）——4 字节。
2. 若 CUSTOMCHAR_MASK ≠ 0，按掩码依次读取自定义字符数据（`INDEX + FONT`）。
3. 使用 `CMD_LCD_INIT` 缓存的 COL/ROW 计算 SCREEN_DATA 长度，读取屏幕数据。
4. **在一次 UI 刷新中原子性地**：
   - 仅当 CUSTOMCHAR_MASK ≠ 0 时写入自定义字符（避免不必要的字体重建）。
   - 逐行设置游标并写入屏幕内容（**不调用 setColRow / clearScreen**）。
   - 调用 `invalidate()` 触发重绘。

**关键优化**：正常帧中 CUSTOMCHAR_MASK = 0，设备端完全跳过字体相关处理，仅更新屏幕文字，大幅减少 `CharLcmView` 的渲染开销。

### 13.5 与心跳的关系

- `CMD_LCD_FULLFRAME` 是业务命令，不影响心跳计数器。
- 重连恢复（§12.6）简化为：`CMD_LCD_INIT` → `CMD_LCD_FULLFRAME(DIRTY_ALL)`，一次全帧发送即可恢复全部状态（含自定义字符）。
- `CMD_LCD_INIT` 会使设备端重置 FULLFRAME 序号跟踪（`mLastFullFrameSeq = -1`），避免 PC 端 SEQ 计数器重置后新帧被误判为过期帧。

### 13.6 时序图

```
PC 端（DLL）                                     设备端（Android）
  │                                               │
  │ DISPLAYDLL_SetPosition(1,1)                   │  t=0ms
  │ DISPLAYDLL_Write("Hello")                     │  t=0ms
  │   → 更新快照, dirty |= DIRTY_SCREEN           │
  │                                               │
  │ DISPLAYDLL_SetPosition(1,2)                   │  t=5ms
  │ DISPLAYDLL_Write("World")                     │
  │   → 更新快照, dirty |= DIRTY_SCREEN           │
  │                                               │
  │  [FrameSendThread 唤醒, 30ms 周期]              │  t=30ms
  │  dirty=DIRTY_SCREEN → 拷贝快照 → 清除 dirty     │
  │──── CMD_LCD_FULLFRAME ─────────────────────>│
  │     (ccMask=0x00, 仅屏幕数据, 84字节)            │
  │                                               │  跳过字体重建
  │                                               │  逐行写入 → invalidate
  │                                               │  "Hello" + "World"
  │                                               │  → 无撕裂
  │                                               │
  │ DISPLAYDLL_CustomChar(0, {...})                │  t=50ms
  │   → 更新快照, dirty |= DIRTY_CUSTOMCHAR        │
  │                                               │
  │  [FrameSendThread 唤醒, 30ms 周期]              │  t=60ms
  │  dirty=DIRTY_CUSTOMCHAR → 拷贝快照 → 清除 dirty  │
  │──── CMD_LCD_FULLFRAME ─────────────────────>│
  │     (ccMask=0x01, 含字符0字体数据)               │
  │                                               │  写入自定义字符 0
  │                                               │  逐行写入 → invalidate
  │                                               │
```
