package mock.v0227.net.minecraft.a.a;

import java.util.ArrayList;
import java.util.List;

/**
 * 仿 inf-20100227 的 EntityMap：{@code net.minecraft.a.a.h}（实测：只有尺寸＋格子＋查询，
 * <b>没有任何 insert/remove/泵</b>——这就是"死壳期"）。
 * 主列表 {@code e} 在这个年代已经退化成查询用的临时缓冲（实物里每次查询先 clear()）。
 */
public final class h {
    public int a = 32;
    public int b = 32;
    public int c = 32;
    public List[] d;
    private List e = new ArrayList();

    public h() {
        this.d = new ArrayList[this.a * this.b * this.c];
        for (int var1 = 0; var1 < this.a; var1++) {
            for (int var2 = 0; var2 < this.b; var2++) {
                for (int var3 = 0; var3 < this.c; var3++) {
                    this.d[(var3 * this.b + var2) * this.a + var1] = new ArrayList();
                }
            }
        }
    }

    public final List a(mock.v0227.net.minecraft.a.c.b var1, mock.v0227.net.minecraft.a.d.b var2) {
        this.e.clear();
        int var3 = (int) (var2.a / 8.0D);
        int var4 = (int) (var2.b / 8.0D);
        int var5 = (int) (var2.c / 8.0D);
        for (int var6 = var3 - 1; var6 <= (int) (var2.d / 8.0D) + 1; var6++) {
            for (int var7 = var4 - 1; var7 <= (int) (var2.e / 8.0D) + 1; var7++) {
                for (int var8 = var5 - 1; var8 <= (int) (var2.f / 8.0D) + 1; var8++) {
                    if (var6 >= 0 && var7 >= 0 && var8 >= 0 && var6 < this.a && var7 < this.b && var8 < this.c) {
                        List var9 = this.d[(var8 * this.b + var7) * this.a + var6];
                        for (int var10 = 0; var10 < var9.size(); var10++) {
                            Object var11 = var9.get(var10);
                            if (var11 != var1) {
                                this.e.add(var11);
                            }
                        }
                    }
                }
            }
        }
        return this.e;
    }

    public final int b() {
        return this.d.length;
    }
}
