package rootcap;
import java.lang.reflect.*;
public class ListApi {
    public static void main(String[] a) {
        try {
            Class<?> dm = Class.forName("android.hardware.display.DisplayManager");
            for (Method m : dm.getDeclaredMethods()) {
                if (!m.getName().contains("createVirtualDisplay")) continue;
                StringBuilder sb = new StringBuilder(m.getName()).append("(");
                Class<?>[] ps = m.getParameterTypes();
                for (int i = 0; i < ps.length; i++) {
                    if (i > 0) sb.append(", ");
                    sb.append(ps[i].getSimpleName());
                }
                System.out.println(sb.append(")"));
            }
        } catch (Throwable t) { System.out.println("ERR " + t); }
    }
}
