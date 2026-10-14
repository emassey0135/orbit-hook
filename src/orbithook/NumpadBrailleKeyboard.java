package orbithook;

import java.util.TreeSet;

/**
 * Turns the Orbit Player's phone keypad into a braille keyboard. The left column is dots 1, 2 and 3, with star as
 * dot 7. The right column is dots 4, 5 and 6, with pound as dot 8. The middle column is the space bar:
 *
 * <pre>
 *   1 2 3      dot1 space dot4
 *   4 5 6      dot2 space dot5
 *   7 8 9      dot3 space dot6
 *   * 0 #      dot7 space dot8
 * </pre>
 *
 * <p>The Player doesn't report keys pressed together as one chord. Whenever the keys held down change, it sends a
 * press for each key still held. Letting go of some of the keys sends nothing, and letting go of the last ones sends a
 * release for each of them. So a cell is every key pressed since the cell began, and it ends at the first release.
 */
final class NumpadBrailleKeyboard {

    /** The braille keys in a cell, such as {@code DOT1} or {@code SPACE}, and whether it was held. */
    static final class Cell {
        final TreeSet<String> keys;
        final boolean longPress;

        Cell(TreeSet<String> keys, boolean longPress) {
            this.keys = keys;
            this.longPress = longPress;
        }
    }

    /** Releases this soon after the one that ended a cell are for keys let go at the same time. */
    private static final long SAME_RELEASE_MS = 50;

    /**
     * A cell with no key events for this long never got its release, for example because the keypad went to another
     * app. A held key is pressed again after about a second, so this is only reached when the release was lost.
     */
    private static final long ABANDONED_MS = 3000;

    private TreeSet<String> keys = new TreeSet<>();
    private boolean active;
    private boolean longPress;
    private boolean zero;
    private long lastEventTime;
    private long lastReleaseTime = Long.MIN_VALUE / 2;

    /** The braille key a keypad key stands for, or null if it isn't one of the twelve keypad keys. */
    static String brailleKeyFor(String keyName) {
        switch (keyName) {
            case "1": return "DOT1";
            case "4": return "DOT2";
            case "7": return "DOT3";
            case "3": return "DOT4";
            case "6": return "DOT5";
            case "9": return "DOT6";
            case "*": return "DOT7";
            case "#": return "DOT8";
            case "0": case "2": case "5": case "8": return "SPACE";
            default: return null;
        }
    }

    /** Takes a press (action 0) or release (action 1), and returns the cell if the release ends one. */
    synchronized Cell onKey(String keyName, int action, boolean isLongPress, long now) {
        String key = brailleKeyFor(keyName);
        if (key == null) return null;
        if (action == 0) {
            if (active && now - lastEventTime > ABANDONED_MS) reset();
            active = true;
            add(key, keyName, isLongPress, now);
            return null;
        }
        if (action != 1) return null;
        if (!active && now - lastReleaseTime < SAME_RELEASE_MS) return null;
        // A release with no press before it is a cell of that key alone. Orbit's screen reader keeps the press of 0
        // for itself unless the hook hands it over.
        add(key, keyName, isLongPress, now);
        lastReleaseTime = now;
        Cell cell = new Cell(keys, longPress);
        boolean heldZero = zero && longPress;
        reset();
        // Holding 0 opens Orbit's sleep dialog, so it isn't a cell.
        return heldZero ? null : cell;
    }

    private void add(String key, String keyName, boolean isLongPress, long now) {
        keys.add(key);
        longPress |= isLongPress;
        zero |= keyName.equals("0");
        lastEventTime = now;
    }

    private void reset() {
        keys = new TreeSet<>();
        active = false;
        longPress = false;
        zero = false;
    }
}
