package mock.v0413.net.minecraft.a.c;

import java.util.HashMap;
import java.util.Map;

import mock.v0413.net.minecraft.a.a.g;

/**
 * 仿 inf-20100413 的 EntityList：{@code net.minecraft.a.c.e}（实物 javap 五成员齐全）。
 * 判据用的形状：两个以上 {@code private static final Map} +
 * {@code static Entity method(NBT, World)} + {@code static String method(Entity)}
 * + {@code private static void method(Class, String)}（addMapping）。
 */
public final class e {
    private static final Map a = new HashMap();
    private static final Map b = new HashMap();
    private static final Map c = new HashMap();

    private e() {
    }

    /** = register()：World 的静态块会调它（实物是 {@code static { EntityList.register(); }} 的等价物）。 */
    public static void a() {
        a(mock.v0413.net.minecraft.a.e.b.class, "item");
        a(mock.v0413.net.minecraft.a.e.c.class, "Creeper");
        a(mock.v0413.net.minecraft.client.f.class, "LocalPlayer");
    }

    private static void a(Class var1, String var2) {
        a.put(var2, var1);
        b.put(var1, var2);
        c.put(var1, Integer.valueOf(0x7F7F7F));
    }

    /** = createEntityFromNBT(NBT, World) */
    public static b a(mock.v0413.net.minecraft.a.b.a var1, g var2) {
        String var3 = var1 == null ? null : var1.b("id");
        Class var4 = var3 == null ? null : (Class) a.get(var3);
        if (var4 == null) {
            System.out.println("Skipping unknown entity id " + var3);
            return null;
        }
        try {
            return (b) var4.getConstructor(g.class).newInstance(var2);
        } catch (Exception var5) {
            throw new RuntimeException(var5);
        }
    }

    /** = getEntityString(Entity) */
    public static String a(b var1) {
        return (String) b.get(var1.getClass());
    }

    public static String b() {
        return a.keySet().toString();
    }
}
