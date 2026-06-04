import java.awt.event.*;


class MyKeyboardInput implements KeyListener {
  public boolean w, a, s, d;

  // Explicit constructor with NO camera parameter required!
  public MyKeyboardInput() {
    this.w = false;
    this.a = false;
    this.s = false;
    this.d = false;
  }

  @Override
  public void keyPressed(KeyEvent e) {
    setKey(e.getKeyCode(), true);
  }

  @Override
  public void keyReleased(KeyEvent e) {
    setKey(e.getKeyCode(), false);
  }

  @Override
  public void keyTyped(KeyEvent e) {}

  private void setKey(int keyCode, boolean isPressed) {
    switch (keyCode) {
      case KeyEvent.VK_W: w = isPressed; break;
      case KeyEvent.VK_S: s = isPressed; break;
      case KeyEvent.VK_A: a = isPressed; break;
      case KeyEvent.VK_D: d = isPressed; break;
    }
  }
}