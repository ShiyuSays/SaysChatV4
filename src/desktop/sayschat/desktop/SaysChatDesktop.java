package sayschat.desktop;

import javax.swing.UIManager;
import javax.swing.SwingUtilities;

/** SaysChatV4 电脑客户端入口。 */
public final class SaysChatDesktop {
    public static void main(String[] args) {
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
}
