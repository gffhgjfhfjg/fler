// fler gadget 反对抗脚本（运行在被注入 App 进程内，随 gadget script 交互模式加载）。
//
// 作用：重打包必然导致签名变化（v2/v3 校验整个 APK 内容，无法保留原签名），
// 本脚本在 App 自身代码运行前 hook PackageManager / SigningInfo / ZipFile，
// 将「签名信息 / 完整性指纹」伪装成原 APK 的值，绕过最常见的自校验：
//   1. PackageManager.getPackageInfo(GET_SIGNATURES)      → signatures 换成原证书
//   2. PackageManager.getPackageInfo(GET_SIGNING_CERTIFICATES) → SigningInfo 方法返回原证书
//   3. java.util.zip.ZipFile.getEntry                     → 被改动条目返回原 CRC/size
//
// 局限（无法通用对抗，需具体分析）：
//   - 校验签名摘要硬编码在 native 代码 / so 内部
//   - 读 /proc/self/maps、内存扫描（反 Frida）
//   - Play Integrity / 服务端校验
//
// 参数（来自 gadget config 的 interaction.parameters，由 GadgetRepacker 注入）：
//   pkg       本应用包名
//   certB64   原 APK 签名证书 DER 的 base64
//   entryCrcs { "classes.dex": [crc, size, csize], ... } 被修改条目的原始 ZIP 指纹

import Java from 'frida-java-bridge';

const TAG = 'fler-gadget';

let applied = false;

function log(msg) {
  console.log(`${TAG} ${msg}`);
}

function logErr(where, err) {
  console.error(`${TAG} [!] ${where}: ${err}`);
}

rpc.exports = {
  init(stage, parameters) {
    log(`anti-tamper init (stage=${stage})`);
    if (stage === 'late') return; // reload 时不重复安装 hook
    if (applied) return;
    applied = true;

    const pkg = parameters ? parameters.pkg : null;
    const certB64 = parameters ? parameters.certB64 : null;
    const entryCrcs = parameters && parameters.entryCrcs ? parameters.entryCrcs : null;

    Java.perform(() => {
      const Signature = Java.use('android.content.pm.Signature');
      const Base64 = Java.use('android.util.Base64');

      // 原签名证书字节（device 端 base64 解码，避免 JS→Java 大数组转换）
      let certBytes = null;
      if (certB64) {
        try {
          certBytes = Base64.decode(certB64, 0 /* DEFAULT */);
        } catch (e) {
          logErr('Base64.decode', e);
        }
      }

      // ---------------------------------------------------------------
      // 1. getPackageInfo(String, int)
      // ---------------------------------------------------------------
      try {
        const APM = Java.use('android.app.ApplicationPackageManager');
        APM.getPackageInfo.overload('java.lang.String', 'int').implementation =
          function (name, flags) {
            const pi = this.getPackageInfo(name, flags);
            try {
              if (pkg && name === pkg) spoofPackageInfo(pi, flags, Signature, certBytes);
            } catch (e) {
              logErr('getPackageInfo(int)', e);
            }
            return pi;
          };
        log('hooked ApplicationPackageManager.getPackageInfo(String,int)');
      } catch (e) {
        logErr('hook getPackageInfo(String,int)', e);
      }

      // ---------------------------------------------------------------
      // 2. getPackageInfo(String, PackageInfoFlags)  (API 33+)
      // ---------------------------------------------------------------
      try {
        const APM = Java.use('android.app.ApplicationPackageManager');
        APM.getPackageInfo.overload('java.lang.String',
          'android.content.pm.PackageManager$PackageInfoFlags').implementation =
          function (name, flagsObj) {
            const pi = this.getPackageInfo(name, flagsObj);
            try {
              if (pkg && name === pkg && flagsObj !== null) {
                const mask = Number(flagsObj.getMask());
                spoofPackageInfo(pi, mask, Signature, certBytes);
              }
            } catch (e) {
              logErr('getPackageInfo(Flags)', e);
            }
            return pi;
          };
        log('hooked ApplicationPackageManager.getPackageInfo(String,PackageInfoFlags)');
      } catch (e) {
        // API < 33 无此重载，正常
        log('PackageInfoFlags overload not present (pre-33)');
      }

      // ---------------------------------------------------------------
      // 3. SigningInfo 的取值方法（GET_SIGNING_CERTIFICATES 路径）
      // ---------------------------------------------------------------
      try {
        const SigningInfo = Java.use('android.content.pm.SigningInfo');
        if (certBytes !== null) {
          SigningInfo.getApkContentsSigners.implementation = function () {
            try {
              return makeSigArray(Signature, certBytes);
            } catch (e) {
              logErr('getApkContentsSigners', e);
              return this.getApkContentsSigners();
            }
          };
          try {
            SigningInfo.getSigningCertificateHistory.implementation = function () {
              try {
                return makeSigArray(Signature, certBytes);
              } catch (e) {
                logErr('getSigningCertificateHistory', e);
                return this.getSigningCertificateHistory();
              }
            };
          } catch (e) { /* 版本差异，忽略 */ }
          log('hooked SigningInfo.getApkContentsSigners');
        }
      } catch (e) {
        logErr('hook SigningInfo', e);
      }

      // ---------------------------------------------------------------
      // 4. ZipFile 条目指纹（classes.dex / AndroidManifest.xml 被改动）
      // ---------------------------------------------------------------
      if (entryCrcs) {
        try {
          const ZipFile = Java.use('java.util.zip.ZipFile');
          ZipFile.getEntry.overload('java.lang.String').implementation =
            function (name) {
              const entry = this.getEntry(name);
              try {
                if (entry !== null) {
                  const m = entryCrcs[name];
                  if (m) {
                    entry.crc.value = m[0];
                    entry.size.value = m[1];
                    entry.csize.value = m[2];
                  }
                }
              } catch (e) {
                logErr('getEntry patch', e);
              }
              return entry;
            };
          log('hooked ZipFile.getEntry');
        } catch (e) {
          logErr('hook ZipFile.getEntry', e);
        }
      }

      log(`anti-tamper ready (pkg=${pkg}, cert=${certBytes !== null ? 'yes' : 'none'}, ` +
        `crcEntries=${entryCrcs ? Object.keys(entryCrcs).length : 0})`);
    });
  },
};

function makeSigArray(Signature, certBytes) {
  const sig = Signature.$new(certBytes);
  return Java.array('android.content.pm.Signature', [sig]);
}

function spoofPackageInfo(pi, flags, Signature, certBytes) {
  if (certBytes === null) return;
  // PackageManager.GET_SIGNATURES = 0x40
  if ((flags & 0x40) !== 0) {
    pi.signatures.value = makeSigArray(Signature, certBytes);
  }
  // GET_SIGNING_CERTIFICATES = 0x08000000：signingInfo 字段由系统构造（真实新证书），
  // 其取值方法已被 3 号 hook 伪装，无需（也很难）替换对象本身。
}
