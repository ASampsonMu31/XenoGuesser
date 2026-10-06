import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import javax.swing.JComponent;

/**
 * The card above the enlarged map showing the animal or plant whose range is on the map,
 * turning round: its name, what it is, and its picture going round frame by frame.
 */
public class PreviewCard extends JComponent {
    public static final int WIDTH = 250, LEAF_WIDTH = 170;

    private volatile String name, kind;
    private volatile BufferedImage[] frames;
    // A plant's leaf, shown still beside it to compare (null for an animal)
    private volatile BufferedImage leaf;
    private int frame;
    private final javax.swing.Timer timer;

    public PreviewCard() {
        setOpaque(false);
        setFocusable(false);
        timer = new javax.swing.Timer(60, e -> {
            BufferedImage[] f = frames;
            if (f != null && f.length > 0 && isShowing()) {
                frame = (frame + 1) % f.length;
                repaint();
            }
        });
        timer.start();
    }

    /** What to show (frames null while still being drawn; name null for nothing). */
    public void show(String name, String kind, BufferedImage[] frames, BufferedImage leaf) {
        this.name = name;
        this.leaf = leaf;
        this.kind = kind;
        this.frames = frames;
        this.frame = 0;
        repaint();
    }

    /** How wide it wants to be: wider with a leaf beside the plant. */
    public int wantedWidth() {
        return leaf != null ? WIDTH + LEAF_WIDTH : WIDTH;
    }

    /** Whether there's anything to show. */
    public boolean hasSubject() {
        return name != null;
    }

    @Override
    protected void paintComponent(Graphics g) {
        String n = name;
        if (n == null) return;
        Graphics2D g2 = (Graphics2D) g.create();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        int w = getWidth(), h = getHeight();
        g2.setColor(new Color(22, 23, 27, 232));
        g2.fillRoundRect(0, 0, w - 1, h - 1, 14, 14);
        g2.setColor(new Color(255, 255, 255, 40));
        g2.drawRoundRect(0, 0, w - 1, h - 1, 14, 14);
        g2.setFont(new Font("Arial", Font.BOLD, 15));
        g2.setColor(Color.WHITE);
        g2.drawString(n, 12, 22);
        g2.setFont(new Font("Arial", Font.PLAIN, 11));
        g2.setColor(new Color(150, 195, 255));
        g2.drawString(kind == null ? "" : kind.toUpperCase(), 12, 38);
        // The picture, as large as the card allows below the name (a leaf's beside it)
        BufferedImage l = leaf;
        int pictureWidth = l != null ? WIDTH : w;
        int top = 44, box = Math.min(pictureWidth - 16, h - top - 8);
        if (l != null) {
            g2.setColor(new Color(255, 255, 255, 30));
            g2.drawLine(WIDTH, 12, WIDTH, h - 12);
            g2.setFont(new Font("Arial", Font.PLAIN, 11));
            g2.setColor(new Color(150, 195, 255));
            g2.drawString("LEAF", WIDTH + 12, 38);
            int leafBox = Math.min(LEAF_WIDTH - 24, h - top - 8);
            g2.drawImage(l, WIDTH + (LEAF_WIDTH - leafBox) / 2, top + (h - top - 8 - leafBox) / 2, leafBox, leafBox, null);
        }
        BufferedImage[] f = frames;
        if (f != null && f.length > 0) {
            g2.drawImage(f[frame % f.length], (pictureWidth - box) / 2, top, box, box, null);
        } else {
            g2.setColor(new Color(200, 200, 210));
            g2.drawString("Drawing…", pictureWidth / 2 - 22, top + box / 2);
        }
        g2.dispose();
    }
}
