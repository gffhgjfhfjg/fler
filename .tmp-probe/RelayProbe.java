import com.jcraft.jsch.*;
import java.io.InputStream;

public class RelayProbe {
    static volatile String found = null;

    public static void main(String[] args) throws Exception {
        JSch jsch = new JSch();
        Session session = jsch.getSession("nokey", "localhost.run", 22);
        // 沙箱出口走 HTTP 代理（App 直连，这里仅为了复现服务器侧行为）
        session.setProxy(new ProxyHTTP("127.0.0.1", 18080));
        session.setConfig("PreferredAuthentications", "password,keyboard-interactive,publickey");
        session.setConfig("StrictHostKeyChecking", "no");
        session.setServerAliveInterval(15000);
        session.setServerAliveCountMax(4);
        session.setUserInfo(new UserInfo() {
            public String getPassphrase() { return null; }
            public String getPassword() { return ""; }
            public boolean promptYesNo(String message) { return true; }
            public void showMessage(String message) {
                System.out.println("=== BANNER (showMessage) ===");
                System.out.println(message);
                System.out.println("=== END BANNER ===");
            }
            public boolean promptPassword(String message) { return true; }
            public boolean promptPassphrase(String message) { return false; }
        });
        long t0 = System.currentTimeMillis();
        session.connect(25000);
        System.out.println("[connected in " + (System.currentTimeMillis() - t0) + "ms]");
        session.setPortForwardingR("", 80, "127.0.0.1", 18081);
        System.out.println("[port forwarding registered]");

        ChannelShell ch = (ChannelShell) session.openChannel("shell");
        ch.setPty(false);
        try {
            ch.connect(10000);
            System.out.println("[shell channel connected]");
        } catch (JSchException e) {
            System.out.println("[shell connect threw: " + e.getMessage() + " -- reading stream anyway]");
        }

        InputStream out = ch.getInputStream();
        // shell 通道的扩展数据（stderr）经 setExtOutputStream 接收
        ch.setExtOutputStream(new java.io.OutputStream() {
            @Override public void write(int b) {
                byte[] one = {(byte) b};
                handle("STDERR", one, 0, 1);
            }
            @Override public void write(byte[] b, int off, int len) {
                handle("STDERR", b, off, len);
            }
        });

        Thread tout = new Thread(() -> pump("STDOUT", out));
        tout.setDaemon(true);
        tout.start();

        long deadline = System.currentTimeMillis() + 25000;
        while (System.currentTimeMillis() < deadline && found == null) {
            Thread.sleep(200);
            if (ch.isClosed()) { System.out.println("[channel closed]"); break; }
        }
        System.out.println(found != null ? "[RESULT] URL found: " + found : "[RESULT] URL NOT found");
        Thread.sleep(500); // 让泵线程把余量打完
        session.disconnect();
        System.exit(0);
    }

    static void pump(String tag, InputStream in) {
        try {
            byte[] buf = new byte[4096];
            while (true) {
                int n = in.read(buf);
                if (n < 0) break;
                handle(tag, buf, 0, n);
            }
        } catch (Exception e) {
            System.out.println("[" + tag + " pump] " + e);
        }
    }

    static void handle(String tag, byte[] b, int off, int len) {
        String s = new String(b, off, len, java.nio.charset.StandardCharsets.UTF_8);
        System.out.println("=== " + tag + " chunk ===");
        System.out.print(s);
        System.out.println("=== end " + tag + " chunk ===");
        java.util.regex.Matcher m = java.util.regex.Pattern
            .compile("https?://[A-Za-z0-9-]+(?:\\.[A-Za-z0-9-]+)+")
            .matcher(s);
        while (m.find()) {
            String u = m.group();
            String host = u.replaceFirst("^https?://", "").split("/")[0];
            if (host.endsWith(".lhr.life") && found == null) found = u;
        }
    }
}
