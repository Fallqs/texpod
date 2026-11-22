# 会话与页面协议（v0 提案）

这里定义首版 v0 语义和当前实现布局，尚不承诺跨版本兼容。Android `WireProtocolTest` 与固件 `TextPlayerWire.DecodesSharedAndroidFrameVector` 共享一个独立固定帧向量，校验字节序、字段布局和 CRC；真机互操作仍需单独验收。

## 当前 v0 实现

实现使用 BLE GATT：服务 `6f2d4a3e-3f5d-4e8f-a7b2-c9d10e4f6a70`，手机写入特征后缀 `…71`，设备通知特征后缀 `…72`。打开阅读器上的 `Texpod` 即开始广播，断开后重新广播，退出或休眠时停止；使用 Secure Connections、MITM 和数字比对。用户在设备与手机上确认同一个六位码，加密认证完成前不接收正文。

每个分片以 14 字节头开始，均为 little-endian：`version:u8=0`、`type:u8`、`flags:u8`（位 0=`START`、位 1=`END`）、保留 `u8=0`、`sessionId:u32`、`objectId:u32`、`offset:u16`。`offset` 是正文偏移，不含首帧元数据。`START` 帧负载先放 `bodyLength:u16` 和正文 `crc32:u32`，再直接放正文；首帧元数据共 6 字节，因此 ATT MTU 23、20 字节负载也能接收。对象最长 65,535 字节，CRC 使用标准 IEEE 多项式（`123456789` 的校验值 `0xCBF43926`）。同一时刻只有一个对象；乱序、重复偏移、提前 `END`、CRC 不符或超限均拒绝，5 秒无进展会中止当前对象。

消息体的当前布局如下：

- `HELLO`：空。`CAPABILITIES` 共 38 字节：`maxFrame:u16`、`maxObject:u16`、安全区 `x/y/width/height:i32`（偏移 4–19）、`maxSlots:u16`、`glyphPoolBytes:u32`、`maxTokens:u16`、`gridVersion:u8=2`、`encodingMask:u8=3`（网格＋位图）、全视口 `width/height:i32`（偏移 30、34）。`gridVersion=2` 表示支持 `ASCII_RUN`；手机明确拒绝旧版网格，不向只支持版本 1 的固件发送新操作符。`maxFrame` 是完整特征值长度；手机据此和 `MTU - 3` 的较小者限制分片，网格位必须支持，位图位只在资源足够时置位。
- `START_SESSION` 共 28 字节：网格 `originX/Y:i32`、`columns/rows/cellWidth/cellHeight:u16`、`baselineOffsetX/Y:i16`、`glyphPoolBytes:u32`、`maxSlots:u16`、`maxTokens:u16`。手机不得超出设备能力。
- `PAGE_GRID` 头共 32 字节：`requestId/pageId/baseEpoch/baseRevision/targetEpoch/targetRevision:u32`、`bindingCount/tokenCount:u16`、`flags:u16`（位 0=整表重置）、保留 `u16=0`。随后是每个 20 字节的绑定：`slotId:u16`、`poolOffset:u32`、`byteLength:u32`、`width/height/rowStride:u16`、`offsetX/Y:i16`；再依次放新增图块位图和完整 `int16` token 流，`EOS` 必须最后出现且计在 `tokenCount` 内。`frame.objectId=pushSeq`。
- `PAGE_BITMAP` 头共 16 字节：`requestId/pageId:u32`、`rowStride/width/height:u16`、保留 `u16=0`，后接 MSB-first 的 1bpp 逐行位图。宽高必须与当前逻辑视口一致，行只含必要填充位。
- `REQUEST_PAGE` 共 9 字节：`basePageId/basePushSeq:u32`、`direction:u8`（0=后退、1=前进）；`frame.objectId=requestId`。`NO_PAGE` 为空并以 `requestId` 为对象号。`DISPLAYED` 共 20 字节：`pushSeq/pageId/cacheEpoch/cacheRevision/occupiedSlots:u32`，对象号同为 `pushSeq`。`ERROR` 为 `code:u32`，对象号尽量携带相关请求或推送。

Android「阅读器性能」使用可选的页耗时扩展。`CAPABILITIES` 偏移 29 的位 2 声明支持基本计时，位 3 声明支持详细计时，位 4 声明支持连接间隔采样（位 0、1 的网格和位图含义不变）。手机仅在支持时在 `START_SESSION` 的 28 字节正文后追加标志字节：位 0 启用基本计时，位 1 启用详细计时且须同时置位 0，位 2 启用连接间隔采样且须同时置位 0、1，位 3 独立启用下述页面流控，其余位为零。`DISPLAYED` 分别为 20（无）、40（基本）、52（详细）或 56（详细含连接间隔）字节。计时字段为 little-endian `u32`；连接间隔字段为 little-endian `u16`。

- 20–39：五个毫秒值，依次为首片处理至 SD 暂存完成（含 CRC 和关闭文件）、页面准备、等待绘制、绘制（位图模式包括逐行读卡）、屏幕刷新。
- 40–51：首尾分片到达阅读器 GATT 回调的时间跨度（毫秒）、逐片暂存处理耗时之和（微秒，含组装、CRC 和 SD 文件操作，不含片间等待）、末片到达 GATT 回调至暂存完成（毫秒）。这些是应用回调时间，不是无线空口时间。
- 52–55：首片、末片到达阅读器 GATT 回调时协商生效的 BLE 连接间隔，单位 1.25 毫秒，0 表示未取得采样。无需额外通知；较旧的手机与固件仍按已协商的长度交换确认。

设备重发确认时复用原页耗时和连接间隔采样。写入、到达跨度、暂存处理及末片收尾会重叠，不能相加。暂存完成至刷新结束则是页面准备、等待绘制、绘制和刷新的合计。

当前 C3 预算是 256 槽位、16 KiB 位图池、2048 个原始 token、单图块最多 1024 字节、单帧最多 240 字节；这些是首轮真机测量起点，不是所有目标的稳定性承诺。接收对象顺序写入 `/.crosspoint/text-player-page.tmp`，显示后删除，不分配第二个全屏 RAM 缓冲。

### 页面写入流控（可选）

`CAPABILITIES` 偏移 29 的位 5 声明支持页面流控。手机确认写特征支持 Write Without Response 后，才在 `START_SESSION` 标志字节中置位 3。未启用时沿用逐片带 ATT 应答的写入；控制消息始终带 ATT 应答。

启用后，`PAGE_GRID` 和 `PAGE_BITMAP` 使用无 ATT 应答的写命令，每页先隐式放行六片。阅读器按原顺序暂存每片；每接受六个非末尾分片便发送 `PAGE_CREDIT`（类型 11），其会话号、对象号与页面相同，正文为已暂存正文的累计字节数 `u16`（little-endian）。手机只有在此偏移等于下一片正文偏移时才能继续发下一批。最后一批不发信用通知，即使该批正好满六片。信用通知不证明整页 CRC 正确，也不改变 `DISPLAYED` 的长度或提交语义。写操作或信用通知无进展、偏移不匹配、暂存失败时中止，不在不完整的暂存对象中重传。

## 传输与能力

- 设备为 BLE GATT Server/Peripheral，手机为 GATT Client/Central。手机写入页面数据，设备通过通知报告翻页请求和提交结果。
- `HELLO/CAPABILITIES` 协商协议版本、支持的页编码、当前方向下的逻辑视口和可用区域、1bpp 像素格式、最大帧与对象长度、字形字节及槽位上限。位图回退仅在设备有足够的在途暂存资源时声明可用。
- 可用 ATT MTU 可能只有 23。首版同一时刻只接收一个页面对象，分片携带对象身份和偏移，按协商上限验证总长度、分片范围及完整对象 CRC。顺序接收不要求为乱序片另建缓冲；重复、缺片、超时及重试都必须有界。CRC 只检错，不能替代授权与加密。
- 页面对象是逻辑上的整体，不要求把所有分片拼进一块大 RAM。格网指令有独立长度上限；字形可分片写入字典的暂存空间；位图可顺序暂存并逐段校验后绘制。只在对象全部校验通过后尝试提交显示。

## 标识

| 标识 | 用法 |
|---|---|
| `sessionId` | 当前连接与布局的投读会话。重连、换内容或改变视口、字体、字号等布局参数后开始新会话。旧会话的推送一律拒绝。 |
| `pageId` | 手机赋予同一会话内稳定版面的标识；不用于比较传输新旧。设备不自行计算相邻页。 |
| `requestId` | 设备生成的前后翻请求编号。同一请求重试沿用原编号，不再次翻页。 |
| `pushSeq` | 手机生成的推送序号，在会话内独立递增。即使 `pageId` 回退，同一推送的重发仍用原序号和原载荷。 |
| `cacheEpoch` / `cacheRevision` | 已显示确认的设备 LRU 纪元与修订号；纪元用于整表重置，修订号随每个成功网格页递增。位图页不改变两者。 |
| `slotId` | 当前纪元内的短槽位号，可由页面候选显式淘汰重绑；手机镜像与设备在 `DISPLAYED` 时同步提交。见[双端 LRU 字形缓存](./glyph-cache.md)。 |

## 前后翻共用推送路径

```text
HELLO / CAPABILITIES → START_SESSION（空字典，固定网格参数）
  → 首次推送锚点页 → DISPLAYED
  → REQUEST_PAGE(requestId, basePageId, basePushSeq, direction)
  → PAGE_GRID / PAGE_BITMAP，或 NO_PAGE(requestId)
  → DISPLAYED（仅在页面刷屏提交成功后）
```

- 会话首个推送无需设备翻页请求；之后每个页面都必须匹配设备当前等待的 `requestId`，并携带 `pageId` 和 `pushSeq`。首版不主动预推后续页或重放设备旧页。新的请求以当前已显示的 `basePageId/basePushSeq` 为锚；`direction` 只有前进或后退。
- 手机对同一请求重发同一结果（同一个推送或 `NO_PAGE`）。迟到结果不能覆盖新页面；设备只保留最后一次成功提交的标识和确认信息，同一推送重复到达只重发 `DISPLAYED`，不再次写字典、更新 LRU、增加修订号或刷屏。推送序号和页面标识都按会话区分，不以页面编号推断新旧。
- `DISPLAYED(sessionId, pushSeq, pageId, cacheEpoch, cacheRevision, occupiedSlots)` 确认屏幕、字形绑定与 LRU 的已提交状态，以 `GfxRenderer::displayCommitted()` 的结果为准，不等于收包完成。若 `DISPLAYED` 丢失，下一请求的基页与推送序号只有精确匹配手机唯一未确认的推送时，才可补作该次显示与缓存确认；否则先重新对齐，不猜测偏移。
- `NO_PAGE` 只表示手机当前没有可推的目标页。缺文、朗读中断与自然结束采用同一应答：本次请求结束、画面和位置保持、用户回手机处理。设备不维护内容结束标志。断连、超时或资源不足另按传输错误处理，不推进位置。
- 设备一次只允许一个在途翻页请求。旧协议在接收或刷屏期间不累计新按键；启用 SD 字形流翻页扩展后使用下面的相对偏移机制。重试有截止时间；状态无法确认时结束会话、丢弃在途数据与 LRU，重新握手并由手机推送最后确认的锚点。没有得到显示确认或可替代证据的目标不作为新锚点。

## `PAGE_GRID`：固定网格

会话开始时定义一个从左到右、从上到下的矩形网格。方向或版式变化开新会话，不维护多网格缓存。

```text
originX, originY
columns, rows
cellWidth, cellHeight
baselineOffsetX, baselineOffsetY
```

逻辑单元 `i` 的基线锚点为：

```text
x(i) = originX + (i % columns) × cellWidth + baselineOffsetX
y(i) = originY + floor(i / columns) × cellHeight + baselineOffsetY
```

字形图块加上相对基线的偏移后贴在锚点；图块按页面可用区域裁剪，不机械地截在格内。像素混合以黑色覆盖、背景透明为准，不用空白像素擦掉相邻字形。

`PAGE_GRID` 逻辑上包含推送身份、基 `(cacheEpoch, cacheRevision)`、目标状态、整表重置标志、显式槽位绑定及位图池分配、全部新增图块、token 数和 token 流。绑定携带明确的 `slotId`，不再按 `occupiedSlots` 隐式追加；设备拒绝淘汰本页工作集、未使用的新绑定或基修订号不匹配的候选。建议 token 使用 little-endian `int16_t`：

| token | 行为 |
|---|---|
| `0 … 32767` | 绘制对应的 `slotId` 并前进一格。实际槽位数受设备上限约束。 |
| `-1`（`SPACE`） | 留空并前进一格，不代表原文中的具体字符。 |
| `-2`（`NEWLINE`） | 留空到下一行首：`cursor = (floor(cursor / columns) + 1) × columns`。 |
| `-3`（`EOS`） | 留空到网格末尾并结束指令流。只表示本页网格结束，不表示朗读或书籍结束。 |
| `-4`（`ASCII_RUN`） | 后接 `slotId:int16` 和 `advanceCells:int16` 两项元数据；将完整 ASCII 文本图块绘制一次，再按实测跨度前进。三项都计入原始 `tokenCount`，但只把 `slotId` 计为一次字形引用。 |

`ASCII_RUN` 的槽位必须非负，跨度必须为正、不超过整行列数，且不得跨越当前行末；手机在必要时显式发送 `NEWLINE`。手机用测量和栅格化同一 `Paint` 生成文本图块，按 `ceil(measuredWidth / cellWidth)` 计算跨度，单词能放入整行时不从词中换行。原始 token 预算与屏幕单元预算分别检查，避免三项编码绕过设备上限。

`cursor` 从零开始；刚好填满一行时已经移到下一行首，不再发 `NEWLINE`，除非明确要再留一整行。例：六列网格的 `A B SPACE C D NEWLINE E F EOS` 将 `E` 放在第二行首。`EOS` 必须恰好一次且在最后；即使网格恰好填满也要发。普通字形与 `SPACE` 不能在末格之后执行，`NEWLINE` 不能越过网格末尾。

接收端在绘制前检查行列、乘加坐标、图块尺寸和长度、token 数、推进范围、纪元和引用槽位。拒绝截断、未知 token、尾随数据及任何缺字形引用，不进入逐字请求或显示半页。

## `PAGE_BITMAP`：基线与回退

同样遵守会话、请求、推送与显示确认规则，不依赖或改变字典。首版以协商后的逻辑视口表示 1bpp 逐行位图；大小由视口和协议行跨度推导并核对。可用小缓冲和有上限的临时文件完成校验与绘制，不允许 BLE 回调直接写当前帧缓冲，也不分配第二块全屏 RAM。该暂存对象不会用于后续翻页。

接收、校验或绘制失败时不提交候选帧，不发送 `DISPLAYED`。若失败发生在帧缓冲已开始绘制之后，未完成的缓冲不得用于后续刷屏或保存睡眠画面；应由后续完整页面或正常界面重建。不支持 `PAGE_RUNS`、自由坐标和灰度编码。

## SD glyph-stream extension

Reader capability bit 6 and `START_SESSION` flag bit 4 select SD-backed glyph
rendering. In that mode `START_SESSION` has its existing 29-byte body followed
by the 16-byte TPF1 generation ID. The grid in that session is used by every
page. Only `PAGE_GLYPHS` may draw in this mode; the reader has no bitmap fallback
and the phone does not mirror its hot cache. Display ACK cache epoch/revision
are zero; occupied slots can be reported for diagnostics.

`PAGE_GLYPHS` (12) retains the existing request ID and page ID (`u32` each),
then has a `u16` command count bounded by the session token limit. Commands
start with the following byte; remaining operands are little-endian:

| Opcode | Operands and effect |
|---|---|
| `0x00..0x0f` | `u16` glyph ID from face `opcode`, draw at the cursor and advance one cell. |
| `0x10..0x1f` | `u16` glyph ID from face `opcode & 15`, `i16 dx`, `i16 dy`. Draw relative to the cursor without advancing. |
| `0x20` | `u16` positive cell advance; must fit in the current row. |
| `0x21` | Move to the next row start without overshooting the grid. |

Android 0.4.1 uses half-cell grid columns. A full-width glyph draws and advances
one cell, then advances the second; an ASCII run draws at its shaped offsets and
advances by its measured half-cell span. An opening double quote uses the
positioned form to align its ink to the right edge of the em. As of Android
0.4.4, a closing quote at the end of a merged punctuation pair uses the same
alignment within the pair's shared em. These are layout choices, not new opcodes.

The exact command count ends the stream. Unknown commands, truncated operands,
trailing bytes, invalid glyph IDs, missing/corrupt glyphs, and CRC failures
cannot refresh the panel. Accepted commands may draw into the existing RAM
framebuffer before END, but the reader refreshes only after validating the
entire object. The normal receive credit window applies.

The font setup messages run outside the page stream: `FONT_QUERY` (13) carries
one 16-byte generation ID; `FONT_BEGIN` (14) carries the ID followed by `u32`
file length and `u32` whole-file CRC32; `FONT_CHUNK` (15) carries `u32` file
offset and up to 8192 consecutive file bytes (nonempty); `FONT_END` (16) has an
empty body. Commands have a matched `FONT_STATUS` (17) response: a status byte
and `u32` validated cumulative file offset. Status codes are 0 ready, 1 missing,
2 uploading, 3 rejected. Use session ID zero and a nonzero object ID for each
font command before starting a reading session. Bulk chunks use the existing
fragment credit window, but only `FONT_STATUS` advances the committed chunk
offset. The upload must be validated before the reader activates the generation.
`ERROR` code 34 means the negotiated pack or a glyph could not be loaded: it
requires font sync, not a bitmap retransmission. An interrupted or rejected
page leaves the panel unchanged; manual refresh and screenshots do not use
the provisional framebuffer. Sleep discards a provisional framebuffer before
compositing an overlay.

With capability bit 7 and session flag bit 5, an SD glyph session can extend
its 56-byte link-profiled `DISPLAYED` to 68 bytes. The suffix is `u32` reader
button-action-to-refresh-complete milliseconds (`0xffffffff` for the initial
anchor), `u32` cumulative SD glyph lookup microseconds (seek, read and record
validation), `u16` RAM cache hits, and `u16` RAM cache misses. These values are
per page; lookup time is already included in fragment processing. The flag
requires SD glyph mode and all three existing timing flags. Without it the
ACK size and meanings are unchanged.

### Adjacent-page preparation and rapid turns

A client that supports extensions sends the same empty `HELLO` with object ID 1. A
supporting reader includes a 39th `CAPABILITIES` byte with bit 0 set; legacy peers
still use the 38-byte body. `START_SESSION` flag bit 6 enables this extension only
in SD-glyph mode. `REQUEST_PAGE` keeps the first eight bytes (the last displayed
anchor) and then carries a signed one-byte relative offset and a mode byte (0 turn,
1 prepare an adjacent page). An idle reader requests next then previous. Prepared
objects are bounded to 2 KiB; the phone caches their commands and the reader warms
its glyph LRU without drawing into the framebuffer. No preparation advances the
anchor or consumes text. `PAGE_PREPARED` (type 18, empty, keyed by push sequence)
completes a preparation or discards a superseded turn. `DISPLAYED` is still sent
only after a real refresh. A new press invalidates the previous turn before the
refresh is committed; an already-started e-ink refresh cannot be interrupted.
An in-flight superseded object is drained and validated in order but stops drawing.
