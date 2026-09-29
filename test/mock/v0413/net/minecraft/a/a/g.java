package mock.v0413.net.minecraft.a.a;

import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;

import mock.v0413.net.minecraft.a.c.b;

/**
 * 仿 inf-20100413 的 World：{@code net.minecraft.a.a.g}。
 * 形状与方法语义全部取自本机实物（javap -p/-c + 可读源双向核对）：
 * <ul>
 *   <li>{@code a(Entity)V} = spawnEntityInWorld（{@code loadedEntityList.add}）</li>
 *   <li>{@code static b(Entity)V} = setEntityDead（只把 {@code u} 置真）</li>
 *   <li>{@code c()V} = levelEntities（每 tick 泵：{@code u} 真即 {@code remove(i--)}）</li>
 *   <li>{@code i()V} = restartTimeOfDay —— <b>只有</b> skylightSubtracted 变化时才遍历部件表调
 *       {@code IWorldAccess.d()}。这正是"IWorldAccess 不能当每 tick 泵"的实测依据。</li>
 *   <li>{@code a(IWorldAccess)V} / {@code b(IWorldAccess)V} = add/removeWorldAccess</li>
 *   <li>{@code a(Class)I} = 类别闸（{@code entitiesInLevelList}）</li>
 * </ul>
 */
public final class g {

    /** loadedEntityList */
    public final List a = new ArrayList();
    /** unloadedEntityList（装的是 NextTickListEntry，不是实体） */
    private final List b = new LinkedList();
    /** loadedTileEntityList */
    public final List m = new ArrayList();
    /** worldAccesses */
    private final List n = new ArrayList();
    public b d;
    public long c;
    public long l;
    public int e;
    public int h;
    public int i;
    public int j;
    public boolean k;
    public int r;
    public int s;
    public int t;
    public final java.util.Random g = new java.util.Random(20100413L);

    static {
        // 与实物一致：EntityList 的静态块负责注册类型 id
        mock.v0413.net.minecraft.a.c.e.a();
    }

    public g() {
        this.n.add(new mock.v0413.net.minecraft.a.a.b());
    }

    /** = spawnEntityInWorld */
    public final void a(b var1) {
        this.a.add(var1);
        var1.d = this;
    }

    /** = setEntityDead */
    public static void b(b var1) {
        var1.u = true;
    }

    public final void a(a var1) {
        if (!this.n.contains(var1)) {
            this.n.add(var1);
        }
    }

    public final void b(a var1) {
        this.n.remove(var1);
    }

    /** = levelEntities：每 tick 遍历登记表，dead 就摘。 */
    public final void c() {
        for (int i0 = 0; i0 < this.a.size(); i0++) {
            b var2 = (b) this.a.get(i0);
            if (!var2.u) {
                var2.b_();
                int var3 = (int) (var2.h / 16.0D);
                int var4 = (int) (var2.j / 16.0D);
                if (var3 != var4 && this.c > 4096L) {
                    this.b.add(var2);
                }
            }
            if (var2.u) {
                this.a.remove(i0--);
            }
        }
        for (int i5 = 0; i5 < this.m.size(); i5++) {
            this.m.get(i5);
        }
    }

    /** = restartTimeOfDay：光照档位变了才通知部件表（实测！不是每 tick 都通知）。 */
    public final void i() {
        ++this.c;
        float var1 = (float) Math.cos((double) ((float) this.c / 24000.0F) * Math.PI * 2.0F) * 2.0F + 0.5F;
        var1 = 1.0F - var1;
        if (var1 < 0.0F) {
            var1 = 0.0F;
        }
        if (var1 > 1.0F) {
            var1 = 1.0F;
        }
        int var2 = (int) (var1 * 13.0F);
        if (var2 != this.r) {
            this.r = var2;
            for (int var3 = 0; var3 < this.n.size(); var3++) {
                ((a) this.n.get(var3)).d();
            }
        }
    }

    /** = entitiesInLevelList(Class) */
    public final int a(Class var0) {
        int n0 = 0;
        for (int i1 = 0; i1 < this.a.size(); i1++) {
            if (var0.isAssignableFrom(((b) this.a.get(i1)).getClass())) {
                n0++;
            }
        }
        return n0;
    }

    /** = playerEntity getter（0223 靠这类方法拿玩家，0413 也保留） */
    public final b e() {
        return this.d;
    }

    public final void a(b var1, String var2, float var3, float var4) {
        for (int i5 = 0; i5 < this.n.size(); i5++) {
            ((a) this.n.get(i5)).a(var2, var1.h, var1.i, var1.j, var3, var4);
        }
    }

    public final void a(String var1, double var2, double var4, double var6, double var8, double var10, double var12) {
        for (int i14 = 0; i14 < this.n.size(); i14++) {
            ((a) this.n.get(i14)).a(var1, var2, var4, var6, var8, var10, var12);
        }
    }

    public final List a(b var1, mock.v0413.net.minecraft.a.d.b var2) {
        return this.a;
    }

    public final mock.v0413.net.minecraft.a.d.b c(b var1) {
        return var1.r;
    }

    public final String d() {
        return "mock-world-0413";
    }

    public final boolean b(int var1, int var2, int var3) {
        return true;
    }

    public final int g() {
        return this.n.size();
    }

    public final java.util.List h() {
        return this.n;
    }

    public final java.util.List f() {
        return this.b;
    }
}
