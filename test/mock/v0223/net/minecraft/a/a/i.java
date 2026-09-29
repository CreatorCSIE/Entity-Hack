package mock.v0223.net.minecraft.a.a;

import java.util.ArrayList;
import java.util.List;

import mock.v0223.net.minecraft.a.c.b;

/**
 * 仿 in-20100223 的 EntityMap：{@code net.minecraft.a.a.i}。
 * 完全照可读源 {@code EntityMap.java} 的结构：三个 int 尺寸（width/depth/height，格子边长 8）、
 * {@code List[] entityGrid}、{@code List entities} 主列表；
 * {@code a(Entity)}＝insert、{@code b(Entity)}＝remove、{@code a()}＝updateEntities（每 tick 泵）。
 */
public final class i {
    public int a = 32;
    public int b = 32;
    public int c = 32;
    public List[] d;
    public List e = new ArrayList();
    private List f = new ArrayList();

    public i(int var1, int var2, int var3) {
        this.a = var1 / 8;
        this.b = var2 / 8;
        this.c = var3 / 8;
        this.d = new ArrayList[this.a * this.b * this.c];
        for (int var4 = 0; var4 < this.a; var4++) {
            for (int var5 = 0; var5 < this.b; var5++) {
                for (int var6 = 0; var6 < this.c; var6++) {
                    this.d[(var6 * this.b + var5) * this.a + var4] = new ArrayList();
                }
            }
        }
    }

    private final int a(double var1) {
        return (int) (var1 / 8.0D);
    }

    /**
     * 仿可读源 {@code EntityMapSlot.init(...)}：格子坐标<b>夹紧</b>到 {@code [0, dim-1]}
     * （越界不是丢掉，而是落到最边上那一格），再按 {@code (z*depth + y)*width + x} 取号。
     * 原来这里"越界就返回 {@code f}"，跟实物不一致，会把"传送到世界外"这种操作的后果写歪。
     */
    private final List g(double var1, double var2, double var3) {
        return this.d[(this.clamp(this.a(var3), this.c) * this.b + this.clamp(this.a(var2), this.b))
                * this.a + this.clamp(this.a(var1), this.a)];
    }

    private static int clamp(int var0, int var1) {
        return var0 < 0 ? 0 : (var0 >= var1 ? var1 - 1 : var0);
    }

    /** = insert */
    public final void a(b var1) {
        this.e.add(var1);
        this.g(var1.h, var1.i, var1.j).add(var1);
    }

    /** = remove */
    public final void b(b var1) {
        this.g(var1.e, var1.f, var1.g).remove(var1);
        this.g(var1.h, var1.i, var1.j).remove(var1);
        this.e.remove(var1);
    }

    /** = updateEntities：每 tick 跑一遍；isDead(u) 即从主列表和格子里摘掉。 */
    public final void a() {
        for (int var1 = 0; var1 < this.e.size(); var1++) {
            b var2 = (b) this.e.get(var1);
            var2.e = var2.h;
            var2.f = var2.i;
            var2.g = var2.j;
            var2.a_();
            if (var2.u) {
                this.e.remove(var1--);
                this.g(var2.e, var2.f, var2.g).remove(var2);
            } else {
                // 实物比的是三条轴（EntityMap.java:109-115），只比 x 会把"上下传送"漏掉
                int var3 = this.a(var2.e);
                int var4 = this.a(var2.h);
                int var7 = this.a(var2.f);
                int var8 = this.a(var2.i);
                int var9 = this.a(var2.g);
                int var10 = this.a(var2.j);
                if (var3 != var4 || var7 != var8 || var9 != var10) {
                    this.g(var2.e, var2.f, var2.g).remove(var2);
                    this.g(var2.h, var2.i, var2.j).add(var2);
                }
            }
        }
    }

    public final int h() {
        return this.e.size();
    }

    public final List i() {
        return this.e;
    }
}
