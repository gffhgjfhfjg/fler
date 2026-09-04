import com.jcraft.jsch.*;
import java.io.InputStream;

public class RelayProbe2 {
    static volatile String found = null;

    public static void main(String[] args) throws Exception {
        String variant = args.length > 0 ? args[0] : "base";
        String host = System.getenv().getOrDefault("PROBE_HOST", "localhost.run");
        int port = Integer.parseInt(System.getenv().getOrDefault("PROBE_PORT", "22"));
        System.out.println("##### VARIANT: " + variant + " host=" + host + ":" + port);
        JSch jsch = new JSch();
        Session session = jsch.getSession("nokey", host, port);
        if (port == 22) session.setProxy(new ProxyHTTP("127.0.0.1", 18080));
        session.setConfig("PreferredAuthentications", "password,keyboard-interactive,publickey");
        session.setConfig("StrictHostKeyChecking", "no");
        session.setServerAliveInterval(15000);
        session.setServerAliveCountMax(4);
        session.setUserInfo(new UserInfo() {
            public String getPassphrase() { return null; }
            public String getPassword() { return ""; }
            public boolean promptYesNo(String message) { return true; }
            public void showMessage(String message) { }
            public boolean promptPassword(String message) { return true; }
            public boolean promptPassphrase(String message) { return false; }
        });
        session.connect(25000);
        System.out.println("[connected]");

        Channel ch;
        switch (variant) {
            case "env": {
                ChannelShell s = (ChannelShell) session.openChannel("shell");
                s.setPty(false);
                s.setEnv("LANG", "C.UTF-8");
                s.setEnv("LC_ALL", "C.UTF-8");
                ch = s;
                break;
            }
            case "pty": {
                ChannelShell s = (ChannelShell) session.openChannel("shell");
                s.setPty(true);
                ch = s;
                break;
            }
            case "exec": {
                ChannelExec s = (ChannelExec) session.openChannel("exec");
                s.setCommand("");
                ch = s;
                break;
            }
            case "order": {
                // 先开 shell，后注册转发
                ChannelShell s = (ChannelShell) session.openChannel("shell");
                s.setPty(false);
                connectChannel(s);
                ch = s;
                break;
            }
            default: {
                ChannelShell s = (ChannelShell) session.openChannel("shell");
                s.setPty(false);
                ch = s;
            }
        }

        if (!variant.equals("order")) {
            session.setPortForwardingR("", 80, "127.0.0.1", 18081);
            System.out.println("[forwarding registered]");
            connectChannel(ch);
        } else {
            session.setPortForwardingR("", 80, "127.0.0.1", 18081);
            System.out.println("[forwarding registered after shell]");
        }

        InputStream out = ch.getInputStream();
        ch.setExtOutputStream(new java.io.OutputStream() {
            public void write(int b) { byte[] o = {(byte) b}; handle("STDERR", o, 0, 1); }
            public void write(byte[] b, int off, int len) { handle("STDERR", b, off, len); }
        });

        long deadline = System.currentTimeMillis() + 20000;
        while (System.currentTimeMillis() < deadline && found == null) {
            InputStream in = ch.getInputStream();
            while (in.available() > 0) {
                byte[] buf = new byte[4096];
                int n = in.read(buf);
                if (n < 0) break;
                handle("STDOUT", buf, 0, n);
            }
            if (ch.isClosed()) { System.out.println("[channel closed]"); break; }
            Thread.sleep(150);
        }
        System.out.println(found != null ? "[RESULT " + variant + "] FOUND: " + found
                                        : "[RESULT " + variant + "] NOT FOUND");
        session.disconnect();
        System.exit(0);
    }

    static void connectChannel(Channel ch) {
        try {
            ch.connect(10000);
            System.out.println("[channel connected]");
        } catch (JSchException e) {
            System.out.println("[channel connect threw: " + e.getMessage() + "]");
        }
    }

    static void handle(String tag, byte[] b, int off, int len) {
        String s = new String(b, off, len, java.nio.charset.StandardCharsets.UTF_8);
        System.out.println("=== " + tag + " ===");
        System.out.print(s.length() > 600 ? s.substring(0, 600) + "..." : s);
        System.out.println("\n=== end " + tag + " ===");
        java.util.regex.Matcher m = java.util.regex.Pattern
            .compile("https?://[A-Za-z0-9-]+(?:\\.[A-Za-z0-9-]+)+").matcher(s);
        while (m.find()) {
            String u = m.group();
            String host = u.replaceFirst("^https?://", "").split("/")[0];
            if (host.endsWith(".lhr.life") && found == null) found = u;
        }
    }
}
