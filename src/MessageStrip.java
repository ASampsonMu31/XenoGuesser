import javax.swing.JComponent;
import javax.swing.Timer;
import java.awt.Color;
import java.awt.Graphics;
import java.awt.Graphics2D;

/**
 * A message from Xenocorp played during a round: its words scrolling along a strip at the
 * bottom of the window, over the map and everything else, in step with the voice, until it's
 * been said (then the strip goes).
 */
public class MessageStrip extends JComponent {
    private MessageTicker ticker;
    private final Timer frames = new Timer(16, e -> {
        if (ticker != null && ticker.isDone()) stop();
        repaint();
    });

    public MessageStrip() {
        setOpaque(true);
        setVisible(false);
    }

    public void play(TransmissionMessage message) {
        stop();
        ticker = new MessageTicker(message);
        setVisible(true);
        frames.start();
    }

    /** Stops the message and hides the strip. */
    public void stop() {
        frames.stop();
        if (ticker != null) ticker.stop();
        ticker = null;
        setVisible(false);
    }

    @Override
    protected void paintComponent(Graphics g0) {
        MessageTicker playing = ticker;
        if (playing == null) return;
        Graphics2D g = (Graphics2D) g0.create();
        // (solid underneath: it lies over the 3D view, which can't show through)
        g.setColor(new Color(6, 9, 14));
        g.fillRect(0, 0, getWidth(), getHeight());
        playing.draw(g, getWidth(), 0, getHeight());
        g.dispose();
    }
}
