module.exports = [
  {
    "name": "list_analyses",
    "description": "列出分析记录",
    "inputSchema": {
      "type": "object",
      "properties": {}
    }
  },
  {
    "name": "repack_apk",
    "description": "把（已补丁的）so 回打进所属项目的源 APK：替换 lib/<abi>/ 下对应条目 + 未压缩条目 16KB/4B 自动对齐 + 重签名（默认 v1+v2+v3、内置 debug 密钥；可关签名或换自定义密钥）。",
    "inputSchema": {
      "properties": {
        "soPath": {
          "type": "string",
          "description": "so 文件绝对路径（需已应用补丁）"
        },
        "sign": {
          "type": "boolean",
          "description": "是否重签名（默认 true）"
        },
        "v1": {
          "type": "boolean",
          "description": "v1 JAR 签名（默认 true）"
        },
        "v2": {
          "type": "boolean",
          "description": "v2 APK 签名（默认 true）"
        },
        "v3": {
          "type": "boolean",
          "description": "v3 APK 签名（默认 true）"
        },
        "useCustomKey": {
          "type": "boolean",
          "description": "使用自定义密钥（默认 false）"
        },
        "alias": {
          "type": "string",
          "description": "自定义密钥别名（可选）"
        },
        "storePass": {
          "type": "string",
          "description": "自定义密钥库密码"
        },
        "keyPass": {
          "type": "string",
          "description": "自定义密钥密码（可选）"
        },
        "destDir": {
          "type": "string",
          "description": "目标目录绝对路径（可选）"
        },
        "destName": {
          "type": "string",
          "description": "导出 APK 文件名（可选）"
        }
      },
      "required": [
        "soPath"
      ],
      "type": "object"
    }
  },
  {
    "name": "gadget_repack_apk",
    "description": "非 root Frida：把 frida-gadget 注入任意 APK 并重签名（免 root 动态分析）。",
    "inputSchema": {
      "properties": {
        "apkPath": {
          "type": "string",
          "description": "源 APK 绝对路径（任意 APK）"
        },
        "mode": {
          "type": "string",
          "description": "listen（默认）| script"
        },
        "port": {
          "type": "integer",
          "description": "listen 端口（默认 27042）"
        },
        "onLoad": {
          "type": "string",
          "description": "listen 模式 on_load：wait | resume"
        },
        "sign": {
          "type": "boolean",
          "description": "是否重签名（默认 true）"
        },
        "v1": {
          "type": "boolean",
          "description": "v1 签名（默认 true）"
        },
        "v2": {
          "type": "boolean",
          "description": "v2 签名（默认 true）"
        },
        "v3": {
          "type": "boolean",
          "description": "v3 签名（默认 true）"
        },
        "useCustomKey": {
          "type": "boolean",
          "description": "使用自定义密钥（默认 false）"
        },
        "storePass": {
          "type": "string",
          "description": "密钥库密码"
        },
        "alias": {
          "type": "string",
          "description": "密钥别名（可选）"
        },
        "keyPass": {
          "type": "string",
          "description": "密钥密码（可选）"
        },
        "destDir": {
          "type": "string",
          "description": "目标目录（可选）"
        },
        "destName": {
          "type": "string",
          "description": "导出文件名（默认 <原APK名>_gadget.apk）"
        }
      },
      "required": [
        "apkPath"
      ],
      "type": "object"
    }
  }
]