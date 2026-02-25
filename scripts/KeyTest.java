import org.lwjgl.input.Keyboard;
public class KeyTest {
    public static void main(String[] args) {
        System.out.println("KEY_GRAVE=" + Keyboard.KEY_GRAVE);
        for (int i = 0; i < 256; i++) {
            String n = Keyboard.getKeyName(i);
            if (n != null && (n.contains("GRAVE") || n.contains("TILDE") || n.contains("BACK"))) {
                System.out.println(i + "=" + n);
            }
        }
    }
}
