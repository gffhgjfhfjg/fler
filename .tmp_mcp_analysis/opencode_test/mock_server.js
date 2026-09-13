// 复刻 Android McpHttpServer.kt 行为的模拟服务器（字节级一致）
// 用法: node mock_server.js [port] [toolsFile.json]
const net = require("net");
const crypto = require("crypto");

const PORT = parseInt(process.argv[2] || "8765", 10);
const TOOLS_FILE = process.argv[3];
const SUPPORTED_VERSIONS = ["2025-03-26", "2024-11-05", "2025-06-18"];
const HEARTBEAT_MS = 5000;

let TOOLS = [
  {
    name: "list_analyses",
    description: "列出 App 内所有 Blutter 分析记录",
    inputSchema: { type: "object", properties: {} },
  },
  {
    name: "get_analysis",
    description: "获取某次分析的详情",
    inputSchema: {
      type: "object",
      properties: {
        analysisId: { type: "integer", description: "分析记录 ID" },
      },
      required: ["analysisId"],
    },
  },
  {
    name: "engine_read_bytes",
    description: "读取 so 字节",
    inputSchema: {
      type: "object",
      properties: {
        offset: { type: "string", description: "起始偏移", _required: true },
        size: { type: "integer", description: "字节数", default: 1048576 },
      },
      required: ["offset"],
    },
  },
  {
    name: "frida_attach",
    description: "附加到进程",
    inputSchema: {
      type: "object",
      properties: {
        session: { type: "string", description: "会话句柄" },
        pid: { type: "string", description: "进程 ID" },
      },
    },
  },
];
if (TOOLS_FILE) {
  TOOLS = require(require("path").resolve(TOOLS_FILE));
}

function ts() {
  return new Date().toISOString().slice(11, 23);
}

function log(level, msg) {
  console.log(`[${ts()}] [${level}] ${msg}`);
}

function handle(bodyStr) {
  let req;
  try {
    req = JSON.parse(bodyStr);
  } catch (e) {
    return {
      jsonrpc: "2.0",
      id: null,
      error: { code: -32700, message: "JSON 解析失败" },
    };
  }
  const method = req.method;
  log("I", `请求: ${method} → ${JSON.stringify(req.params ?? {})}`);
  if (!method) {
    return { jsonrpc: "2.0", id: req.id ?? null, error: { code: -32600, message: "缺少 method" } };
  }
  if (method.startsWith("notifications/")) return null;
  if (method === "initialize") {
    const requested = req.params?.protocolVersion;
    const version =
      requested && SUPPORTED_VERSIONS.includes(requested) ? requested : SUPPORTED_VERSIONS[0];
    return {
      jsonrpc: "2.0",
      id: req.id,
      result: {
        protocolVersion: version,
        capabilities: {
          tools: { listChanged: false },
          resources: { subscribe: false },
          prompts: { listChanged: false },
          notifications: {},
        },
        serverInfo: { name: "fler-mcp", version: "1.3.0" },
      },
    };
  }
  if (method === "ping") return { jsonrpc: "2.0", id: req.id, result: {} };
  if (method === "tools/list") {
    return { jsonrpc: "2.0", id: req.id, result: { tools: TOOLS } };
  }
  if (method === "tools/call") {
    return {
      jsonrpc: "2.0",
      id: req.id,
      result: {
        content: [{ type: "text", text: "mock" }],
        isError: false,
      },
    };
  }
  return { jsonrpc: "2.0", id: req.id ?? null, error: { code: -32601, message: `方法未找到: ${method}` } };
}

function readRequest(sock, cb) {
  let buf = Buffer.alloc(0);
  let state = "headers";
  let contentLength = 0;
  let headerText = "";
  sock.on("data", (chunk) => {
    buf = Buffer.concat([buf, chunk]);
    while (true) {
      if (state === "headers") {
        const idx = buf.indexOf("\r\n\r\n");
        if (idx === -1) return;
        headerText = buf.slice(0, idx).toString("latin1");
        buf = buf.slice(idx + 4);
        contentLength = 0;
        const m = headerText.match(/content-length:\s*(\d+)/i);
        if (m) contentLength = parseInt(m[1], 10);
        state = "body";
      }
      if (state === "body") {
        if (buf.length < contentLength) return;
        const body = buf.slice(0, contentLength).toString("utf8");
        buf = buf.slice(contentLength);
        state = "headers";
        cb(headerText, body);
        if (buf.length === 0) return;
      }
    }
  });
}

const server = net.createServer((sock) => {
  const remote = sock.remoteAddress || "?";
  let sseTimer = null;
  let sseSessionId = null;
  sock.setNoDelay(true);

  readRequest(sock, (headerText, body) => {
    const lines = headerText.split("\r\n");
    const [method, fullPath] = lines[0].split(" ");
    const headers = {};
    for (const line of lines.slice(1)) {
      const c = line.indexOf(":");
      if (c > 0) headers[line.slice(0, c).trim().toLowerCase()] = line.slice(c + 1).trim();
    }
    const path = fullPath.split("?")[0];
    log("D", `${remote} ${method} ${fullPath}`);

    if (method === "POST" && path === "/mcp") {
      const accept = headers["accept"] || "";
      const sessionIdHeader = headers["mcp-session-id"] || null;
      const response = handle(body);
      if (response === null) {
        // notifications: 202
        const head =
          "HTTP/1.1 202 Accepted\r\n" +
          "Content-Type: text/plain\r\n" +
          "Content-Length: 0\r\n" +
          "Connection: close\r\n\r\n";
        sock.write(head, "latin1");
        setTimeout(() => sock.destroy(), 50);
      } else if (accept.includes("text/event-stream")) {
        const head =
          "HTTP/1.1 200 OK\r\n" +
          "Content-Type: text/event-stream\r\n" +
          "Cache-Control: no-cache\r\n" +
          "Connection: keep-alive\r\n" +
          (sessionIdHeader ? `Mcp-Session-Id: ${sessionIdHeader}\r\n` : "") +
          "\r\n";
        sock.write(head, "latin1");
        sock.write(`event: message\ndata: ${JSON.stringify(response)}\n\n`, "utf8");
        setTimeout(() => sock.destroy(), 50);
      } else {
        const bytes = Buffer.from(JSON.stringify(response), "utf8");
        const head =
          "HTTP/1.1 200 OK\r\n" +
          "Content-Type: application/json\r\n" +
          `Content-Length: ${bytes.length}\r\n` +
          "Connection: close\r\n" +
          (sessionIdHeader ? `Mcp-Session-Id: ${sessionIdHeader}\r\n` : "") +
          "\r\n";
        sock.write(head, "latin1");
        sock.write(bytes);
        setTimeout(() => sock.destroy(), 50);
      }
    } else if (method === "GET" && path === "/mcp") {
      // Streamable HTTP GET SSE 流
      sseSessionId = crypto.randomUUID();
      const head =
        "HTTP/1.1 200 OK\r\n" +
        "Content-Type: text/event-stream\r\n" +
        "Cache-Control: no-cache\r\n" +
        "Connection: keep-alive\r\n" +
        `Mcp-Session-Id: ${sseSessionId}\r\n` +
        "\r\n";
      sock.write(head, "latin1");
      log("I", `SSE 会话建立: ${sseSessionId.slice(0, 8)}（Streamable HTTP）`);
      sseTimer = setInterval(() => {
        try {
          sock.write(": ping\n\n", "utf8");
        } catch (e) {
          clearInterval(sseTimer);
        }
      }, HEARTBEAT_MS);
      sock.on("close", () => {
        if (sseTimer) clearInterval(sseTimer);
        if (sseSessionId) log("I", `SSE 会话断开: ${sseSessionId.slice(0, 8)}`);
      });
    } else {
      const head =
        "HTTP/1.1 404 Not Found\r\n" +
        "Content-Type: text/plain\r\n" +
        "Content-Length: 9\r\n" +
        "Connection: close\r\n\r\n";
      sock.write(head + "not found", "latin1");
      sock.destroy();
    }
  });

  sock.on("error", () => {});
});

server.listen(PORT, "127.0.0.1", () => {
  log("I", `模拟 MCP 服务器已启动: 127.0.0.1:${PORT}（工具数: ${TOOLS.length}）`);
});
