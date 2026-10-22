package sayschat.desktop;

import javax.swing.UIManager;
import javax.swing.SwingUtilities;
import java.io.File;

/** SaysChatV4.1.0 电脑客户端入口。 */
public final class SaysChatDesktop {
    public static void main(String[] args) {
        prepareSaveFolders();
        try {
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
        } catch (Exception ignored) {
        }
        SwingUtilities.invokeLater(new Runnable() {
            public void run() {
                new DesktopLogin().setVisible(true);
            }
        });
    }

    /** 在当前工作目录提前创建 SaysChat/Picture 与 SaysChat/Attach。 */
    private static void prepareSaveFolders() {
        File base = new File(System.getProperty("user.dir", "."), "SaysChat");
        new File(base, "Picture").mkdirs();
        new File(base, "Attach").mkdirs();
    }
}
