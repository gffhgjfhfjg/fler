import com.jcraft.jsch.*;
import java.io.InputStream;

public class RelayProbe3 {
    static volatile String found = null;
    static volatile int totalChunks = 0;

    public static void main(String[] args) throws Exception {
        JSch jsch = new JSch();
        Session session = jsch.getSession("nokey", "localhost.run", 22);
        session.setProxy(new ProxyHTTP("127.0.0.1", 18080));
        session.setConfig("PreferredAuthentications", "password,keyboard-interactive,publickey");
        session.setConfig("StrictHostKeyChecking", "no");
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
        session.setPortForwardingR("", 80, "127.0.0.1", 18081);
        System.out.println("[forwarding registered]");

        ChannelShell ch = (ChannelShell) session.openChannel("shell");
        ch.setPty(false);

        // 关键：connect 之前就取流并挂上读取线程
        InputStream in = ch.getInputStream();
        Thread pump = new Thread(() -> {
            try {
                byte[] buf = new byte[4096];
                while (true) {
                    int n = in.read(buf);
                    if (n < 0) break;
                    totalChunks++;
                    String s = new String(buf, 0, n, java.nio.charset.StandardCharsets.UTF_8);
                    System.out.println("=== STDOUT chunk#" + totalChunks + " (" + n + "B) ===");
                    System.out.print(s.length() > 500 ? s.substring(0, 500) + "..." : s);
                    System.out.println("\n=== end chunk ===");
                    java.util.regex.Matcher m = java.util.regex.Pattern
                        .compile("https?://[A-Za-z0-9-]+(?:\\.[A-Za-z0-9-]+)+").matcher(s);
                    while (m.find()) {
                        String u = m.group();
                        String host = u.replaceFirst("^https?://", "").split("/")[0];
                        if (host.endsWith(".lhr.life") && found == null) found = u;
                    }
                }
            } catch (Exception e) {
                System.out.println("[pump] " + e);
            }
        });
        pump.setDaemon(true);
        pump.start();

        try {
            ch.connect(10000);
            System.out.println("[channel connected]");
        } catch (JSchException e) {
            System.out.println("[channel connect threw: " + e.getMessage() + "] -- continuing");
        }

        long deadline = System.currentTimeMillis() + 20000;
        while (System.currentTimeMillis() < deadline && found == null) {
            Thread.sleep(150);
        }
        System.out.println(found != null ? "[RESULT] FOUND: " + found
                                        : "[RESULT] NOT FOUND (chunks=" + totalChunks + ")");
        session.disconnect();
        System.exit(0);
    }
}
