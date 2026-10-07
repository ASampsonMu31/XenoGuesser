import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import java.awt.BasicStroke;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.RenderingHints;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.Path2D;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Beside the enlarged map: tick boxes for the markings drawn over the land and sea (contours,
 * roads, buildings, shops), and a choice of at most one heatmap to colour the land with under them, each
 * row with its own icon. Nothing here takes the keyboard, so walking keys keep working.
 */
public class MapLayersPanel extends JPanel {
    public static final int WIDTH = 260;
    private static final Color BACKGROUND = new Color(22, 23, 27, 232);
    private static final Color TEXT = new Color(232, 234, 240);
    private static final Color HEADING = new Color(150, 195, 255);
    private static final Color ACCENT = new Color(95, 165, 255);
    private static final Color HOVER = new Color(255, 255, 255, 18);
    private static final Color CHOSEN = new Color(95, 165, 255, 38);
    private static final Font ITEM_FONT = new Font("Arial", Font.PLAIN, 13);
    private static final Font HEADING_FONT = new Font("Arial", Font.BOLD, 12);
    private static final int ROW_HEIGHT = 34, BOX = 18, ICON = 24;

    // (as wide as the space beside its scroll bar, so nothing in a row runs under the bar)
    private final JPanel list = new ScrollableList();

    private static final class ScrollableList extends JPanel implements javax.swing.Scrollable {
        @Override
        public Dimension getPreferredScrollableViewportSize() {
            return getPreferredSize();
        }

        @Override
        public int getScrollableUnitIncrement(java.awt.Rectangle visible, int orientation, int direction) {
            return 16;
        }

        @Override
        public int getScrollableBlockIncrement(java.awt.Rectangle visible, int orientation, int direction) {
            return Math.max(16, visible.height - 32);
        }

        @Override
        public boolean getScrollableTracksViewportWidth() {
            return true;
        }

        @Override
        public boolean getScrollableTracksViewportHeight() {
            return false;
        }
    }
    private final Function<String, Image> icons;
    private final List<Row> overlayRows = new ArrayList<>();
    private String chosenOverlay;

    /**
     * Only what the player has (bought in the shop) is listed: a heading with nothing under it
     * is left out, and so is the overlays' None while there are no overlays.
     *
     * @param onLayer told whenever a layer is ticked or unticked
     * @param layers the layers to list
     * @param overlays the overlays on offer, by name, in groups: those under "" listed on their
     *                 own, then each other group in a dropdown headed with the group's name
     * @param chosen the overlay chosen to begin with, or null
     * @param onOverlay told the overlay chosen, or null for none
     * @param icons each row's icon, by its layer's, overlay's or dropdown's name (looked up as
     *              it's drawn, so a picture made later turns up; null for none)
     */
    public MapLayersPanel(BiConsumer<MapPanel.Layer, Boolean> onLayer, List<MapPanel.Layer> layers, java.util.Set<MapPanel.Layer> ticked,
                          java.util.LinkedHashMap<String, List<String>> overlays, String chosen, Consumer<String> onOverlay,
                          Function<String, Image> icons) {
        this.icons = icons;
        this.chosenOverlay = chosen;
        setOpaque(false);
        setLayout(new BorderLayout());
        list.setOpaque(false);
        list.setLayout(new BoxLayout(list, BoxLayout.Y_AXIS));
        list.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

        if (!layers.isEmpty()) heading("MARKINGS");
        for (MapPanel.Layer layer : layers) {
            Row row = new Row(layer.label, layer.label, false);
            row.on = ticked.contains(layer);
            row.whenClicked = () -> {
                row.on = !row.on;
                row.repaint();
                onLayer.accept(layer, row.on);
            };
            list.add(row);
        }
        boolean anyOverlay = overlays.values().stream().anyMatch(group -> !group.isEmpty());
        if (anyOverlay) {
            if (!layers.isEmpty()) list.add(spacer());
            heading("HEATMAPS");
            List<String> first = new ArrayList<>();
            first.add(null);
            first.addAll(overlays.getOrDefault("", List.of()));
            for (String name : first) overlayRow(name, chosen, onOverlay);
        }
        // The rest in dropdowns, open to begin with only if the chosen one is in it
        for (java.util.Map.Entry<String, List<String>> group : overlays.entrySet()) {
            if (group.getKey().isEmpty() || group.getValue().isEmpty()) continue;
            Group header = new Group(group.getKey());
            list.add(header);
            for (String name : group.getValue()) header.members.add(overlayRow(name, chosen, onOverlay));
            header.setOpen(chosen != null && group.getValue().contains(chosen));
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

    /** One overlay's row, the only one chosen of them all once clicked. */
    private Row overlayRow(String name, String chosen, Consumer<String> onOverlay) {
        Row row = new Row(name == null ? "None" : name, name == null ? "None" : name, true);
        row.on = name == null ? chosen == null : name.equals(chosen);
        row.whenClicked = () -> {
            chosenOverlay = name;
            for (Row r : overlayRows) {
                r.on = r == row;
                r.repaint();
            }
            onOverlay.accept(name);
        };
        overlayRows.add(row);
        list.add(row);
        return row;
    }

    /** A dropdown's heading: an arrow, its icon, its name and how many it holds; clicking opens or shuts it. */
    private final class Group extends JComponent {
        final String text;
        final List<Row> members = new ArrayList<>();
        boolean open, hovered;

        Group(String text) {
            this.text = text;
            setAlignmentX(Component.LEFT_ALIGNMENT);
            setPreferredSize(new Dimension(WIDTH - 20, ROW_HEIGHT + 4));
            setMaximumSize(new Dimension(Integer.MAX_VALUE, ROW_HEIGHT + 4));
            setFocusable(false);
            addMouseListener(new MouseAdapter() {
                @Override
                public void mouseEntered(MouseEvent e) {
                    hovered = true;
                    repaint();
                }

                @Override
                public void mouseExited(MouseEvent e) {
                    hovered = false;
                    repaint();
                }

                @Override
                public void mousePressed(MouseEvent e) {
                    setOpen(!open);
                }
            });
        }

        void setOpen(boolean open) {
            this.open = open;
            for (Row r : members) {
                r.indent = 16;
                r.setVisible(open);
            }
            list.revalidate();
            list.repaint();
            repaint();
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            int w = getWidth(), h = getHeight();
            g2.setColor(hovered ? new Color(255, 255, 255, 26) : new Color(255, 255, 255, 12));
            g2.fillRoundRect(0, 3, w, h - 6, 10, 10);
            // The arrow: pointing right when shut, down when open
            g2.setColor(HEADING);
            int ax = 17, ay = h / 2;
            Path2D arrow = new Path2D.Float();
            if (open) {
                arrow.moveTo(ax - 5, ay - 3);
                arrow.lineTo(ax + 5, ay - 3);
                arrow.lineTo(ax, ay + 4);
            } else {
                arrow.moveTo(ax - 3, ay - 5);
                arrow.lineTo(ax + 4, ay);
                arrow.lineTo(ax - 3, ay + 5);
            }
            arrow.closePath();
            g2.fill(arrow);
            Image icon = icons == null ? null : icons.apply(text);
            int ix = 8 + BOX + 10, iy = (h - ICON) / 2;
            if (icon != null) g2.drawImage(icon, ix, iy, ICON, ICON, null);
            int baseline = (h + g2.getFontMetrics(ITEM_FONT).getAscent() - g2.getFontMetrics(ITEM_FONT).getDescent()) / 2;
            g2.setFont(ITEM_FONT.deriveFont(Font.BOLD));
            g2.setColor(TEXT);
            g2.drawString(text, ix + ICON + 10, baseline);
            g2.setFont(HEADING_FONT);
            g2.setColor(new Color(150, 155, 165));
            String count = String.valueOf(members.size());
            // (clear of the scroll bar beside the list)
            g2.drawString(count, w - 14 - g2.getFontMetrics().stringWidth(count), baseline);
            g2.dispose();
        }
    }

    private void heading(String text) {
        JLabel label = new JLabel(text);
        label.setFont(HEADING_FONT);
        label.setForeground(HEADING);
        label.setAlignmentX(Component.LEFT_ALIGNMENT);
        label.setBorder(BorderFactory.createEmptyBorder(2, 6, 6, 6));
        label.setMaximumSize(new Dimension(Integer.MAX_VALUE, label.getPreferredSize().height));
        list.add(label);
    }

    private static Component spacer() {
        JPanel gap = new JPanel();
        gap.setOpaque(false);
        gap.setMaximumSize(new Dimension(WIDTH, 12));
        gap.setPreferredSize(new Dimension(WIDTH, 12));
        gap.setAlignmentX(Component.LEFT_ALIGNMENT);
        return gap;
    }

    /**
     * One choice: a tick box (or, for an overlay, a round button), its icon and its name, the
     * whole row lighting up under the mouse and when chosen.
     */
    private final class Row extends JComponent {
        final String text, iconKey;
        final boolean round;
        boolean on, hovered;
        // How far in it's set (rows in a dropdown are indented)
        int indent;
        Runnable whenClicked;

        Row(String text, String iconKey, boolean round) {
            this.text = text;
            this.iconKey = iconKey;
            this.round = round;
            setAlignmentX(Component.LEFT_ALIGNMENT);
            setPreferredSize(new Dimension(WIDTH - 20, ROW_HEIGHT));
            setMaximumSize(new Dimension(Integer.MAX_VALUE, ROW_HEIGHT));
            setFocusable(false);
            addMouseListener(new MouseAdapter() {
                @Override
                public void mouseEntered(MouseEvent e) {
                    hovered = true;
                    repaint();
                }

                @Override
                public void mouseExited(MouseEvent e) {
                    hovered = false;
                    repaint();
                }

                @Override
                public void mousePressed(MouseEvent e) {
                    if (whenClicked != null) whenClicked.run();
                }
            });
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            int w = getWidth(), h = getHeight();
            if (on && round) {
                g2.setColor(CHOSEN);
                g2.fillRoundRect(indent, 2, w - indent, h - 4, 10, 10);
            } else if (hovered) {
                g2.setColor(HOVER);
                g2.fillRoundRect(indent, 2, w - indent, h - 4, 10, 10);
            }
            // The box or button
            int bx = 8 + indent, by = (h - BOX) / 2;
            g2.setStroke(new BasicStroke(2f));
            if (round) {
                g2.setColor(on ? ACCENT : new Color(150, 155, 165));
                g2.drawOval(bx, by, BOX, BOX);
                if (on) g2.fillOval(bx + 5, by + 5, BOX - 10, BOX - 10);
            } else {
                if (on) {
                    g2.setColor(ACCENT);
                    g2.fillRoundRect(bx, by, BOX, BOX, 6, 6);
                    g2.setColor(Color.WHITE);
                    g2.setStroke(new BasicStroke(2.4f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                    Path2D tick = new Path2D.Float();
                    tick.moveTo(bx + 4, by + BOX * 0.52f);
                    tick.lineTo(bx + BOX * 0.42f, by + BOX - 5);
                    tick.lineTo(bx + BOX - 4, by + 5);
                    g2.draw(tick);
                } else {
                    g2.setColor(new Color(150, 155, 165));
                    g2.drawRoundRect(bx, by, BOX, BOX, 6, 6);
                }
            }
            // The icon
            int ix = bx + BOX + 10, iy = (h - ICON) / 2;
            Image icon = icons == null ? null : icons.apply(iconKey);
            if (icon != null) g2.drawImage(icon, ix, iy, ICON, ICON, null);
            // The name
            g2.setFont(ITEM_FONT);
            g2.setColor(TEXT);
            int tx = ix + ICON + 10;
            g2.drawString(text, tx, (h + g2.getFontMetrics().getAscent() - g2.getFontMetrics().getDescent()) / 2);
            g2.dispose();
        }
    }

    @Override
    protected void paintComponent(Graphics g) {
        Graphics2D g2 = (Graphics2D) g.create();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g2.setColor(BACKGROUND);
        g2.fillRoundRect(0, 0, getWidth() - 1, getHeight() - 1, 14, 14);
        g2.setColor(new Color(255, 255, 255, 40));
        g2.drawRoundRect(0, 0, getWidth() - 1, getHeight() - 1, 14, 14);
        g2.dispose();
        super.paintComponent(g);
    }
}
