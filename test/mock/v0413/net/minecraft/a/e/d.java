package mock.v0413.net.minecraft.a.e;

import mock.v0413.net.minecraft.a.a.g;

/**
 * 仿 EntitySkeleton 的"构造器剥除案"叶类：实物 0413/0414 里七个 mob 叶类<b>没有</b> {@code (World)} 构造器
 * （只留了 {@code (World, int)} 那一半），所以本工具的"配方 A"标准反射生成对它们无效——
 * mock 用同样的形状把这条限制固定进测试里。
 */
public final class d extends a {
    private int da;

    public d(g var1, int var2) {
        super(var1);
        this.da = var2;
        this.bg = "/mob/skeleton.png";
    }

    public int e() {
        return this.da;
    }
}
