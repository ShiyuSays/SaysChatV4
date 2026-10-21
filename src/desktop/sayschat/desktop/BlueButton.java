package sayschat.desktop;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;

/**
 * 统一的蓝色主按钮：自绘蓝底白字，不受系统 Swing 外观配色影响。
 * 登录窗口的“连接服务器”和聊天窗口的“发送”共用此组件。
 */
final class BlueButton extends JButton {
    private static final long serialVersionUID = 1L;

    private static final Color BLUE = new Color(0x1976D2);
    private static final Color BLUE_HOVER = new Color(0x1565C0);
    private static final Color BLUE_PRESSED = new Color(0x0D47A1);
    private static final Color BLUE_DISABLED = new Color(0x90CAF9);

    BlueButton(String text) {
        super(text);
        // 使用基础 UI 绘制，避免 Windows/macOS 系统外观覆盖自绘蓝底。
        setUI(new javax.swing.plaf.basic.BasicButtonUI());
        setOpaque(false);
        setContentAreaFilled(false);
        setBorderPainted(false);
        setFocusPainted(false);
        setRolloverEnabled(true);
        setForeground(Color.WHITE);
        setFont(getFont().deriveFont(Font.BOLD, 14f));
        setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        setBorder(BorderFactory.createEmptyBorder(6, 14, 6, 14));
    }

    @Override
    protected void paintComponent(Graphics g) {
        Graphics2D g2 = (Graphics2D) g.create();
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                    RenderingHints.VALUE_ANTIALIAS_ON);
            Color fill;
            if (!isEnabled()) {
                fill = BLUE_DISABLED;
            } else if (getModel().isPressed()) {
                fill = BLUE_PRESSED;
            } else if (getModel().isRollover()) {
                fill = BLUE_HOVER;
            } else {
                fill = BLUE;
            }
            g2.setColor(fill);
            g2.fillRoundRect(0, 0, getWidth(), getHeight(), 12, 12);
        } finally {
            g2.dispose();
        }
        super.paintComponent(g);
    }
}
