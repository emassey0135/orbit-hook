package dev.emassey0135.orbithook;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.TreeSet;
import org.junit.Test;

/** The key sequences in these tests are what an Orbit Player sends. */
public class NumpadBrailleKeyboardTest {
  private static final int PRESS = 0;
  private static final int RELEASE = 1;

  private final NumpadBrailleKeyboard keyboard = new NumpadBrailleKeyboard();
  private long now = 1000;

  private NumpadBrailleKeyboard.Cell key(String keyName, int action) {
    return key(keyName, action, false);
  }

  private NumpadBrailleKeyboard.Cell key(String keyName, int action, boolean longPress) {
    now += 10;
    return keyboard.onKey(keyName, action, longPress, now);
  }

  private static TreeSet<String> keys(String... keys) {
    return new TreeSet<>(Arrays.asList(keys));
  }

  @Test
  public void oneKeyIsACellOfThatKey() {
    assertNull(key("1", PRESS));
    NumpadBrailleKeyboard.Cell cell = key("1", RELEASE);
    assertEquals(keys("DOT1"), cell.keys);
    assertFalse(cell.longPress);
  }

  @Test
  public void keysPressedTogetherAreOneCell() {
    // 1 and 4 pressed, then 1 let go first.
    assertNull(key("4", PRESS));
    assertNull(key("1", PRESS));
    assertNull(key("4", PRESS));
    assertNull(key("4", PRESS));
    assertEquals(keys("DOT1", "DOT2"), key("4", RELEASE).keys);
  }

  @Test
  public void keysLetGoTogetherEndOneCell() {
    // 1, 4 and 7 pressed, then 1 let go, then 4 and 7 let go together.
    for (String keyName : new String[] {"4", "7", "1", "4", "7", "4", "7"}) {
      assertNull(key(keyName, PRESS));
    }
    assertEquals(keys("DOT1", "DOT2", "DOT3"), key("4", RELEASE).keys);
    assertNull(key("7", RELEASE));
  }

  @Test
  public void middleColumnIsSpace() {
    // 5, 1 and 4 pressed, then let go one at a time.
    for (String keyName : new String[] {"1", "4", "1", "4", "5", "4", "5", "5"}) {
      assertNull(key(keyName, PRESS));
    }
    assertEquals(keys("DOT1", "DOT2", "SPACE"), key("5", RELEASE).keys);
  }

  @Test
  public void releaseWithoutPressIsACellOfThatKey() {
    assertEquals(keys("SPACE"), key("0", RELEASE).keys);
  }

  @Test
  public void holdingZeroIsNotACell() {
    assertNull(key("0", PRESS));
    assertNull(key("0", PRESS, true));
    assertNull(key("0", RELEASE, true));
  }

  @Test
  public void heldKeyIsALongPress() {
    assertNull(key("*", PRESS));
    assertNull(key("*", PRESS, true));
    NumpadBrailleKeyboard.Cell cell = key("*", RELEASE, true);
    assertEquals(keys("DOT7"), cell.keys);
    assertTrue(cell.longPress);
  }

  @Test
  public void cellWithLostReleaseIsDropped() {
    assertNull(key("1", PRESS));
    now += 5000;
    assertNull(key("4", PRESS));
    assertEquals(keys("DOT2"), key("4", RELEASE).keys);
  }

  @Test
  public void otherKeysAreNotBrailleKeys() {
    assertNull(NumpadBrailleKeyboard.brailleKeyFor("LEFT"));
    assertNull(key("LEFT", PRESS));
    assertNull(key("LEFT", RELEASE));
  }
}
