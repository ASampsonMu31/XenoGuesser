import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.ButtonGroup;
import javax.swing.JCheckBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JRadioButton;
import javax.swing.JScrollPane;
import javax.swing.AbstractButton;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * Beside the enlarged map: tick boxes for what's drawn over the land and sea (contours,
 * roads, buildings, shops, nations), and a choice of at most one gradient map to colour
 * the land with. Nothing here takes the keyboard, so walking keys keep working.
 */
public class MapLayersPanel extends JPanel {
    public static final int WIDTH = 240;
    private static final Color BACKGROUND = new Color(25, 25, 27, 225);
    private static final Color TEXT = new Color(235, 235, 238);
    private static final Color HEADING = new Color(160, 200, 255);
    private static final Font ITEM_FONT = new Font("Arial", Font.PLAIN, 12);
    private static final Font HEADING_FONT = new Font("Arial", Font.BOLD, 13);

    private final JPanel list = new JPanel();

    /**
     * @param onLayer told whenever a layer is ticked or unticked
     * @param gradients the gradient maps on offer, by name
     * @param onGradient told the gradient chosen, or null for none
     */
    public MapLayersPanel(BiConsumer<MapPanel.Layer, Boolean> onLayer, java.util.Set<MapPanel.Layer> ticked,
                          List<String> gradients, String chosen, Consumer<String> onGradient) {
        setOpaque(false);
        setLayout(new BorderLayout());
        list.setOpaque(false);
        list.setLayout(new BoxLayout(list, BoxLayout.Y_AXIS));
        list.setBorder(BorderFactory.createEmptyBorder(10, 12, 10, 12));

        heading("Show on map");
        for (MapPanel.Layer layer : MapPanel.Layer.values()) {
            JCheckBox box = new JCheckBox(layer.label, ticked.contains(layer));
            style(box);
            box.addActionListener(e -> onLayer.accept(layer, box.isSelected()));
            list.add(box);
        }
        list.add(spacer());
        heading("Overlay");
        ButtonGroup group = new ButtonGroup();
        JRadioButton none = new JRadioButton("None", chosen == null);
        style(none);
        none.addActionListener(e -> onGradient.accept(null));
        group.add(none);
        list.add(none);
        for (String name : gradients) {
            JRadioButton option = new JRadioButton(name, name.equals(chosen));
            style(option);
            option.addActionListener(e -> onGradient.accept(name));
            group.add(option);
            list.add(option);
        }

        JScrollPane scroll = new JScrollPane(list, JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED, JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        scroll.setOpaque(false);
        scroll.getViewport().setOpaque(false);
        scroll.setBorder(null);
        scroll.setFocusable(false);
        scroll.getVerticalScrollBar().setFocusable(false);
        scroll.getVerticalScrollBar().setUnitIncrement(16);
        add(scroll, BorderLayout.CENTER);
        setFocusable(false);
    }

    private void heading(String text) {
        JLabel label = new JLabel(text);
        label.setFont(HEADING_FONT);
        label.setForeground(HEADING);
        label.setAlignmentX(Component.LEFT_ALIGNMENT);
        label.setBorder(BorderFactory.createEmptyBorder(0, 0, 4, 14));
        label.setMaximumSize(new Dimension(Integer.MAX_VALUE, label.getPreferredSize().height));
        list.add(label);
    }

    private static Component spacer() {
        JPanel gap = new JPanel();
        gap.setOpaque(false);
        gap.setMaximumSize(new Dimension(WIDTH, 10));
        gap.setPreferredSize(new Dimension(WIDTH, 10));
        gap.setAlignmentX(Component.LEFT_ALIGNMENT);
        return gap;
    }

    private static void style(AbstractButton button) {
        button.setFont(ITEM_FONT);
        button.setForeground(TEXT);
        button.setOpaque(false);
        button.setFocusable(false);
        button.setFocusPainted(false);
        button.setAlignmentX(Component.LEFT_ALIGNMENT);
        button.setBorder(BorderFactory.createEmptyBorder(1, 0, 1, 14));
        // As wide as the panel allows, so no label is cut short
        button.setMaximumSize(new Dimension(Integer.MAX_VALUE, button.getPreferredSize().height));
    }

    /** The height it would like, all of it showing. */
    public int wantedHeight() {
        return list.getPreferredSize().height + 4;
    }

    @Override
    protected void paintComponent(Graphics g) {
        Graphics2D g2 = (Graphics2D) g.create();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g2.setColor(BACKGROUND);
        g2.fillRoundRect(0, 0, getWidth() - 1, getHeight() - 1, 12, 12);
        g2.setColor(new Color(255, 255, 255, 45));
        g2.drawRoundRect(0, 0, getWidth() - 1, getHeight() - 1, 12, 12);
        g2.dispose();
        super.paintComponent(g);
    }
}
