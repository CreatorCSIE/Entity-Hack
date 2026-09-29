package mock.v0223.net.minecraft.a.a;

import java.util.ArrayList;
import java.util.List;

import mock.v0223.net.minecraft.a.c.b;

/**
 * 仿 in-20100223 的 World：{@code net.minecraft.a.a.g}（实物 javap：Indev 的 World 就是这个字母）。
 * 与 0413 的三处关键差异，全部照实测建模：
 * <ol>
 *   <li>实体不在 World 自己的 List 字段上，而在 {@code public a.a.i r}（EntityMap：{@code List[]} 格 + 主列表）；</li>
 *   <li>移除口 {@code b(Entity)V}＝releaseEntitySkin 是<b>立即摘除</b>（不是置死等泵）；</li>
 *   <li>{@code worldAccesses} 只在载入/增删实体时被遍历，没有每 tick 的无参回调。</li>
 * </ol>
 * 另外 Indev 的坐标是 <b>float</b>（0413 是 double），位置三元组的判据必须两种都吃得下。
 */
public final class g {
    public final i r;
    private final List f = new ArrayList();
    public b y;
    public int a = 128;
    public int b = 128;
    public int c = 128;
    public long d;
    public int e;
    public int h = 64;
    public int i;
    public int j;
    public int k;
    public final java.util.Random g = new java.util.Random(20100223L);
    public final List m = new ArrayList();

    public g() {
        this.r = new i(this.a, this.c, this.b);
    }

    /** = entityJoinedWorld / spawnEntityInWorld */
    public final void a(b var1) {
        this.r.a(var1);
        var1.d = this;
        for (int var2 = 0; var2 < this.f.size(); var2++) {
            ((d) this.f.get(var2)).a(var1);
        }
    }

    /** = releaseEntitySkin（立即从逻辑表 + 渲染表一起摘掉） */
    public final void b(b var1) {
        this.r.b(var1);
        for (int var2 = 0; var2 < this.f.size(); var2++) {
            ((d) this.f.get(var2)).b(var1);
        }
    }

    public final void a(d var1) {
        for (int var2 = 0; var2 < this.r.e.size(); var2++) {
            var1.a((b) this.r.e.get(var2));
        }
        this.f.add(var1);
    }

    public final void b(d var1) {
        this.f.remove(var1);
    }

    /** = tick()：Indev 每 tick 通过 EntityMap 驱动全部实体 */
    public final void c() {
        this.r.a();
        ++this.d;
    }

    public final int a(Class var0) {
        int var1 = 0;
        for (int var2 = 0; var2 < this.r.e.size(); var2++) {
            if (var0.isAssignableFrom(((b) this.r.e.get(var2)).getClass())) {
                var1++;
            }
        }
        return var1;
    }

    /** 实物实测：World 还有一个"无参返回实体"的方法（{@code i()}），玩家可以从这里拿。 */
    public final b i() {
        return this.y;
    }

    public final void a(b var1, String var2, float var3, float var4) {
        for (int var5 = 0; var5 < this.f.size(); var5++) {
            ((d) this.f.get(var5)).a(var2, var1.h, var1.i, var1.j, var3, var4);
        }
    }

    public final String l() {
        return "mock-world-0223";
    }

    public final java.util.List n() {
        return this.f;
    }
}
