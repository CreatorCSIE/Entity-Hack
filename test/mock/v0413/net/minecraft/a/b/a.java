package mock.v0413.net.minecraft.a.b;

import java.util.HashMap;
import java.util.Map;

/** 仿 NBTTagCompound：{@code net.minecraft.a.b.a}，只要能按字符串取值就够了。 */
public final class a {
    private final Map b = new HashMap();

    public final void a(String var1, String var2) {
        this.b.put(var1, var2);
    }

    public final String b(String var1) {
        Object v = this.b.get(var1);
        return v == null ? null : String.valueOf(v);
    }
}
