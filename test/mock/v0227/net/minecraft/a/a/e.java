package mock.v0227.net.minecraft.a.a;

import mock.v0227.net.minecraft.a.c.b;

/**
 * 仿 inf-20100227 的 World：{@code net.minecraft.a.a.e}——<b>死壳</b>。
 * 实测只认得三件事：{@code public a.a.h a}（EntityMap，只有格子）、
 * {@code public a.c.b b}（playerEntity）、{@code f()} 返回玩家。
 * <b>没有任何</b> add/remove/tick 实体的方法，也没有 addWorldAccess，
 * 所以这个年代只能"按格子索引公式直写/直删"（配方 D），且拿不到游戏线程。
 */
public final class e {
    public final h a;
    public b b;
    public int c = 256;
    public int d = 128;
    public int f = 256;
    public long g;
    public int i;
    public int j;
    public int k;
    public final java.util.Random l = new java.util.Random(20100227L);

    public e() {
        this.a = new h();
    }

    /** 实物这个年代每 tick 只走方块/时间，不碰实体。 */
    public final void a() {
        ++this.g;
        this.i = (int) (this.g % 24000L);
    }

    public final b f() {
        return this.b;
    }

    public final String d() {
        return "mock-world-0227";
    }

    public final int b(int var1, int var2, int var3) {
        return 0;
    }
}
