import java.awt.Color;
import java.awt.Cursor;
import java.awt.Graphics;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import javax.swing.JComponent;
import javax.swing.JFrame;

/**
 * One of the grab edges round the window in windowed mode (the window has no system frame):
 * dragging it resizes the window, the game area under the title bar keeping the screen's shape.
 * Each edge is a thin strip; the ends of each strip work as the corners.
 */
public class WindowResizer extends JComponent {
    public static final int THICKNESS = 5;
    // The smallest the game area may be made, across
    private static final int SMALLEST = 640;

    /** Which edge: -1/0/1 for left/none/right, and top/none/bottom. */
    private final int sx, sy;
    private final JFrame frame;
    private final float aspect;      // the game area's width over its height
    private final int titleHeight;
    private Rectangle startBounds;
    private Point startMouse;
    private int dragSx, dragSy;

    public WindowResizer(JFrame frame, int sx, int sy, float aspect, int titleHeight) {
        this.frame = frame;
        this.sx = sx;
        this.sy = sy;
        this.aspect = aspect;
        this.titleHeight = titleHeight;
        setFocusable(false);
        MouseAdapter mouse = new MouseAdapter() {
            @Override
            public void mouseMoved(MouseEvent e) {
                int[] d = direction(e);
                setCursor(Cursor.getPredefinedCursor(cursorFor(d[0], d[1])));
            }

            @Override
            public void mousePressed(MouseEvent e) {
                int[] d = direction(e);
                dragSx = d[0];
                dragSy = d[1];
                startBounds = frame.getBounds();
                startMouse = e.getLocationOnScreen();
            }

            @Override
            public void mouseDragged(MouseEvent e) {
                if (startBounds == null) return;
                Point now = e.getLocationOnScreen();
                int dx = (now.x - startMouse.x) * dragSx, dy = (now.y - startMouse.y) * dragSy;
                // The game area's new width, from whichever way it's pulled further (the height follows)
                int areaW = startBounds.width, areaH = startBounds.height - titleHeight;
                float wanted = Math.max(areaW + dx, (areaH + dy) * aspect);
                if (dragSx == 0) wanted = (areaH + dy) * aspect;
                if (dragSy == 0) wanted = areaW + dx;
                int w = Math.max(SMALLEST, Math.round(wanted)), h = Math.round(w / aspect) + titleHeight;
                // Pulled from the left or top, the opposite side stays put
                int x = dragSx < 0 ? startBounds.x + startBounds.width - w : startBounds.x;
                int y = dragSy < 0 ? startBounds.y + startBounds.height - h : startBounds.y;
                frame.setBounds(x, y, w, h);
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                startBounds = null;
            }
        };
        addMouseListener(mouse);
        addMouseMotionListener(mouse);
    }

    /** The way a point on this strip pulls: its own edge, or a corner near the strip's ends. */
    private int[] direction(MouseEvent e) {
        int corner = 18;
        int dx = sx, dy = sy;
        if (sx == 0) dx = e.getX() < corner ? -1 : e.getX() > getWidth() - corner ? 1 : 0;
        if (sy == 0) dy = e.getY() < corner ? -1 : e.getY() > getHeight() - corner ? 1 : 0;
        return new int[] { dx, dy };
    }

    private static int cursorFor(int dx, int dy) {
        if (dx < 0 && dy < 0) return Cursor.NW_RESIZE_CURSOR;
        if (dx > 0 && dy < 0) return Cursor.NE_RESIZE_CURSOR;
        if (dx < 0 && dy > 0) return Cursor.SW_RESIZE_CURSOR;
        if (dx > 0 && dy > 0) return Cursor.SE_RESIZE_CURSOR;
        if (dx != 0) return dx < 0 ? Cursor.W_RESIZE_CURSOR : Cursor.E_RESIZE_CURSOR;
        return dy < 0 ? Cursor.N_RESIZE_CURSOR : Cursor.S_RESIZE_CURSOR;
    }

    @Override
    protected void paintComponent(Graphics g) {
        g.setColor(new Color(14, 16, 22));
        g.fillRect(0, 0, getWidth(), getHeight());
    }
}
