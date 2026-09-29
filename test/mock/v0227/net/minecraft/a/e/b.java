package mock.v0227.net.minecraft.a.e;

import mock.v0227.net.minecraft.a.a.e;

/**
 * 死壳期里唯一"还有 (World) 构造器"的具体叶类（本工具只能靠直写格子把它塞进去）。
 * 挂在怪物基类 {@code a.c.c} 下面——真机 0227 的 {@code net.minecraft.a.c.a.e}（EntityMob）
 * 就是这种"有构造器、但下面本该还有叶类"的格局。
 *
 * <p>顺带记着真机为什么只有一个类型：{@code javap inf-20100227.jar} 显示
 * {@code a.c.a.a/b/c/d/f}（5 只怪）与 {@code a.c.b.b/c}（2 只被动怪）<b>一个构造器都没有</b>
 * （只剩 {@code e()/b()/f()} 这些方法，{@code <init>} 被 ProGuard 删了），
 * 可读源里的 {@code public EntityPig(World)} 是 RetroMCP 反编译时补的，不能当依据。
 * 这一套世界没法复刻那一点——javac 总归要生成一个构造器。
 */
public final class b extends mock.v0227.net.minecraft.a.c.c {
    public int za;

    public b(e var1) {
        super(var1);
        this.bg = "/mob/cow.png";
    }

    public b(e var1, int var2) {
        super(var1, var2);
        this.bg = "/mob/cow.png";
    }
}
