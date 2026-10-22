import java.awt.*;
import java.awt.image.BufferedImage;

/**
 * Developer test runs (see Dev): -Dxenoguesser.hudtest=step,step,... sets the game up once it
 * starts (the map's size, the compass, the settings, messages, the shop, quarters...), saves
 * pictures of the window or the 3D view at given moments, plays rounds by itself, and quits.
 */
final class DevTestRun {

  private final XenoGuesser app;

  DevTestRun(XenoGuesser app) {
    this.app = app;
  }

  /** Developer aid: the window's Swing panels (not the 3D view) painted to a file. */
  private void saveUiShot(java.io.File file) {
    BufferedImage image = new BufferedImage(Math.max(1, app.layeredPane.getWidth()), Math.max(1, app.layeredPane.getHeight()), BufferedImage.TYPE_INT_RGB);
    Graphics2D g = image.createGraphics();
    app.layeredPane.paint(g);
    g.dispose();
    try {
      javax.imageio.ImageIO.write(image, "png", file);
      System.out.println("[SHOT] Panels saved to " + file);
    } catch (java.io.IOException e) {
      System.err.println("Couldn't save the panels: " + e.getMessage());
    }
  }

  /** Developer aid: -Dxenoguesser.verdictshot=prefix saves each multiplayer verdict as it's shown, as prefix_name.png. */
  void verdictShot(String name) {
    String prefix = Dev.property("xenoguesser.verdictshot");
    if (prefix == null) return;
    XenoGuesser.later(1200, () -> saveUiShot(new java.io.File(prefix + "_" + name.replaceAll("[^A-Za-z0-9]+", "_") + ".png")));
  }

  /**
   * Developer aid: -Dxenoguesser.hudtest=large,compass,settings,shot sets the HUD up
   * once the game starts, and "shot" saves a picture of the whole window a moment later.
   */
  void runHudTest() {
    String test = Dev.property("xenoguesser.hudtest");
    if (test == null) return;
    // A test run leaves the mouse alone
    app.robot = null;
    for (String step : test.split(",")) {
      switch (step.trim()) {
        case "large": app.minimap.toggleSize(); break;
        case "compass": {
          javax.swing.Timer later = new javax.swing.Timer(1200, e -> app.glEventListener.useCompass());
          later.setRepeats(false);
          later.start();
          break;
        }
        case "settings": app.setSettingsOpen(true); break;
        // pause: the settings opened (the game paused) at -Dxenoguesser.pauseat=open,close (ms)
        case "pause": {
          String[] at = Dev.property("xenoguesser.pauseat", "6000,12000").split(",");
          XenoGuesser.later(Integer.parseInt(at[0].trim()), () -> app.setSettingsOpen(true));
          XenoGuesser.later(Integer.parseInt(at[1].trim()), () -> app.setSettingsOpen(false));
          break;
        }
        case "thermometer": XenoGuesser.later(1200, () -> app.glEventListener.useThermometer()); break;
        // lose: the game lost a moment in, back to the menu; restart: a new game from the menu after
        case "lose": XenoGuesser.later(1500, () -> app.endGame(false)); break;
        // month: a month's message (-Dxenoguesser.month, 8 by default), as if it had just begun
        case "month": XenoGuesser.later(1500, () -> {
          app.planLaterMessages();
          app.monthMessage(Dev.integer("xenoguesser.month", XenoGuesser.AUGUST));
        }); break;
        // portraits: the map's pictures of the plants and animals saved, as <-Dxenoguesser.portraits>_*.png, 20 s in
        case "portraits": XenoGuesser.later(20000, () -> app.glEventListener.speciesPictures.devSavePortraits(Dev.property("xenoguesser.portraits", "portrait"))); break;
        // oxygen: the oxygen downgraded straight away (its blur and dizziness)
        case "oxygen": XenoGuesser.later(1500, () -> app.glEventListener.downgradeOxygen()); break;
        // april: April's message, as if April had just begun
        case "april": XenoGuesser.later(1500, () -> app.glEventListener.startAprilMessage(app::showMessage)); break;
        case "restart": XenoGuesser.later(Dev.integer("xenoguesser.restartdelay", 5000), app::startSingleplayer); break;
        // quarter: the end of a quarter's screen, as if its target were missed
        case "quarter": XenoGuesser.later(1500, () -> {
          app.quarterPanel.show(app.gameHUD.getQuarter(), app.gameHUD.getProductivity(), app.gameHUD.getTarget(), false, () -> app.quarterPanel.close());
          app.layoutComponents();
        }); break;
        // (the third and fourth items, had from the start: the altimeter and the identification kit)
        case "altimeter": XenoGuesser.later(1200, () -> app.glEventListener.useItem(2)); break;
        case "kit": XenoGuesser.later(1200, () -> app.glEventListener.useItem(3)); break;
        // Multiplayer: face the partner (level); look down at this player's own body; and
        // frameshot: the drawn frame saved (from the GL, so another window over this one doesn't
        // spoil it) to -Dxenoguesser.frameshot=file, -Dxenoguesser.frameshotdelay ms in (default 4000)
        case "partner": XenoGuesser.later(1500, () -> app.glEventListener.lookAtPartner(0f)); break;
        // quit: quits the game -Dxenoguesser.quitdelay ms in (default 5000), as the settings' Quit game does
        case "quit": XenoGuesser.later(Dev.integer("xenoguesser.quitdelay", 5000), app::shutdownGame); break;
        // autoplay: every -Dxenoguesser.autoplayms (default 4000) the next thing a player would do:
        // press on from the quarter's verdict, else leave the shop (buying nothing), else go on
        // from the results, else guess (see guessat)
        case "autoplay": {
          javax.swing.Timer play = new javax.swing.Timer(Dev.integer("xenoguesser.autoplayms", 4000), e -> {
            if (!app.inGame || app.minimap == null) return;
            if (app.quarterPanel != null && app.quarterPanel.isOpen()) {
              app.quarterPanel.devPress();
              return;
            }
            if (app.shopPanel.isOpen()) app.shopPanel.nextRound();
            else if (app.minimap.isFullScreenRevealMode()) app.minimap.devProceedToShop();
            else app.minimap.devGuess();
          });
          play.start();
          break;
        }
        // uishot: the window's panels (the map, results, shop: Swing, not the 3D view) painted to
        // <-Dxenoguesser.uishot>_<ms>.png at each of -Dxenoguesser.uishotat=ms,ms,... in
        case "uishot": {
          for (String at : Dev.property("xenoguesser.uishotat", "4000").split(",")) {
            int ms = Integer.parseInt(at.trim());
            XenoGuesser.later(ms, () -> saveUiShot(new java.io.File(Dev.property("xenoguesser.uishot", "ui") + "_" + ms + ".png")));
          }
          break;
        }
        // walkpartner: face the partner and walk at them for four seconds (W held), logging how far apart they end up
        case "walkpartner": {
          XenoGuesser.later(1500, () -> {
            app.glEventListener.lookAtPartner(0f);
            System.out.printf("[MP] Walking at the partner from %.2f units%n", app.glEventListener.distanceToPartner());
            app.keyboardInput.w = true;
          });
          XenoGuesser.later(5500, () -> {
            app.keyboardInput.w = false;
            System.out.printf("[MP] Stopped %.2f units from the partner (their cylinders touch at 6)%n", app.glEventListener.distanceToPartner());
          });
          break;
        }
        // walk: W held from 9 s for -Dxenoguesser.walkms (default 8000), logging where it started and how far it got each second
        case "walk": {
          int ms = Dev.integer("xenoguesser.walkms", 8000);
          SpherePosition[] start = new SpherePosition[1];
          XenoGuesser.later(9000, () -> {
            start[0] = app.glEventListener.devPosition();
            app.keyboardInput.w = true;
          });
          for (int t = 1000; t <= ms; t += 1000) {
            int at = t;
            XenoGuesser.later(9000 + at, () -> System.out.printf(java.util.Locale.ROOT, "[WALK] %d ms: %.1f units on, eyes at %.1f%n", at,
                SphereMath.distance(start[0], app.glEventListener.devPosition()), SphereMath.altitude(app.glEventListener.devPosition())));
          }
          XenoGuesser.later(9000 + ms, () -> app.keyboardInput.w = false);
          break;
        }
        // enter: Enter pressed at each of -Dxenoguesser.enterat=ms,... (skipping a message, closing the controls)
        case "enter": {
          for (String at : Dev.property("xenoguesser.enterat", "8000").split(",")) XenoGuesser.later(Integer.parseInt(at.trim()), app::pressEnter);
          break;
        }
        // leaveshop: Next round pressed in the shop at -Dxenoguesser.leaveshopat (ms), if it can be
        case "leaveshop": XenoGuesser.later(Dev.integer("xenoguesser.leaveshopat", 12000), () -> {
          if (app.shopPanel.isOpen() && !app.shopPanel.messagePlaying()) app.shopPanel.nextRound();
          else System.out.println("[TEST] The shop can't be left yet (its message is playing)");
        }); break;
        // pin: a marker put down (not guessed) at -Dxenoguesser.pinat=u,v, -Dxenoguesser.pindelay ms in
        case "pin": XenoGuesser.later(Dev.integer("xenoguesser.pindelay", 5000), () -> app.minimap.devPlacePin()); break;
        // screen: what's really on the screen over the window (the 3D view and all), saved to
        // <-Dxenoguesser.screen>_<ms>.png at each of -Dxenoguesser.screenat=ms,...
        case "screen": {
          for (String at : Dev.property("xenoguesser.screenat", "8000").split(",")) {
            int ms = Integer.parseInt(at.trim());
            XenoGuesser.later(ms, () -> {
              try {
                Point origin = app.getContentPane().getLocationOnScreen();
                BufferedImage image = new java.awt.Robot().createScreenCapture(new Rectangle(origin.x, origin.y, app.getContentPane().getWidth(), app.getContentPane().getHeight()));
                javax.imageio.ImageIO.write(image, "png", new java.io.File(Dev.property("xenoguesser.screen", "screen") + "_" + ms + ".png"));
              } catch (Exception ex) {
                ex.printStackTrace();
              }
            });
          }
          break;
        }
        case "lookdown": XenoGuesser.later(1500, () -> app.glEventListener.lookPitch(-70f)); break;
        // lookup: tilts the view up (to see what's overhead: wires, roofs)
        case "lookup": XenoGuesser.later(1500, () -> app.glEventListener.lookPitch(Float.parseFloat(Dev.property("xenoguesser.lookup", "25")))); break;
        // podview: the landing pod from out in front and to one side (-Dxenoguesser.podview=distance,degrees round,height up)
        case "podview": {
          String[] v = Dev.property("xenoguesser.podview", "75,30,10").split(",");
          XenoGuesser.later(1500, () -> app.glEventListener.devShots.viewPod(Double.parseDouble(v[0]), Double.parseDouble(v[1]), Double.parseDouble(v[2])));
          break;
        }
        case "frameshot": XenoGuesser.later(Dev.integer("xenoguesser.frameshotdelay", 4000),
            () -> app.glEventListener.devShots.saveNextFrame(new java.io.File(Dev.property("xenoguesser.frameshot", "frame.png")))); break;
        // herd: faces the nearest herd (and again each time a frame is saved by frameshots)
        case "herd": XenoGuesser.later(2000, () -> app.glEventListener.devShots.lookAtHerd()); break;
        // frameshots: frames saved at each of -Dxenoguesser.frameshotsat=ms,... as <frameshot>_<ms>.png, facing the herd first
        case "frameshots": {
          for (String at : Dev.property("xenoguesser.frameshotsat", "4000").split(",")) {
            int ms = Integer.parseInt(at.trim());
            XenoGuesser.later(ms - 300, () -> app.glEventListener.devShots.lookAtHerd());
            XenoGuesser.later(ms, () -> app.glEventListener.devShots.saveNextFrame(new java.io.File(Dev.property("xenoguesser.frameshot", "frame") + "_" + ms + ".png")));
          }
          break;
        }
        // zoom: the map zoomed right in (-Dxenoguesser.mapzoomat=u,v to say where; the middle otherwise)
        case "zoom": {
          String[] at = Dev.property("xenoguesser.mapzoomat", "0.5,0.5").split(",");
          XenoGuesser.later(1500, () -> app.minimap.devZoom(Float.parseFloat(Dev.property("xenoguesser.mapzoom", "16")), Float.parseFloat(at[0]), Float.parseFloat(at[1])));
          break;
        }
        // guess: a guess two seconds in (-Dxenoguesser.guessdelay); shop: Proceed to Shop pressed
        // six seconds in (-Dxenoguesser.shopdelay); next: on to the next round, buying nothing,
        // twelve seconds in (-Dxenoguesser.nextdelay)
        case "guess": XenoGuesser.later(Dev.integer("xenoguesser.guessdelay", 2000), () -> app.minimap.devGuess()); break;
        case "shop": XenoGuesser.later(Dev.integer("xenoguesser.shopdelay", 6000), () -> app.minimap.devProceedToShop()); break;
        case "next": XenoGuesser.later(Dev.integer("xenoguesser.nextdelay", 12000), () -> {
          if (app.quarterPanel.isOpen() || app.shopPanel.isOpen()) return;
          app.minimap.devProceedToShop();
          if (app.shopPanel.isOpen()) app.shopPanel.nextRound();
        }); break;
        default: break;
      }
    }
    if (test.contains("shot")) {
      javax.swing.Timer shot = new javax.swing.Timer(Dev.integer("xenoguesser.shotdelay", 2600), e -> {
        try {
          Rectangle area = new Rectangle(app.getLocationOnScreen(), app.getSize());
          javax.imageio.ImageIO.write(new Robot().createScreenCapture(area), "png",
              new java.io.File(RunFiles.WORLD_DIR, "window_shot.png"));
          // The map on its own, drawn straight from the panel (whatever is on screen)
          java.awt.image.BufferedImage map = new java.awt.image.BufferedImage(Math.max(1, app.minimap.getWidth()), Math.max(1, app.minimap.getHeight()),
              java.awt.image.BufferedImage.TYPE_INT_ARGB);
          java.awt.Graphics2D mg = map.createGraphics();
          app.minimap.paint(mg);
          mg.dispose();
          javax.imageio.ImageIO.write(map, "png", new java.io.File(RunFiles.WORLD_DIR, "map_shot.png"));
          // ...and the tick boxes beside it, if they're showing
          MapLayersPanel layers = app.minimap.getLayersPanel();
          if (layers != null && layers.isVisible() && layers.getWidth() > 0) {
            java.awt.image.BufferedImage panel = new java.awt.image.BufferedImage(layers.getWidth(), layers.getHeight(),
                java.awt.image.BufferedImage.TYPE_INT_RGB);
            java.awt.Graphics2D pg = panel.createGraphics();
            layers.paint(pg);
            pg.dispose();
            javax.imageio.ImageIO.write(panel, "png", new java.io.File(RunFiles.WORLD_DIR, "map_layers_shot.png"));
          }
        } catch (Exception ex) {
          ex.printStackTrace();
        }
      });
      shot.setRepeats(false);
      shot.start();
    }
  }
}
