package mock.v0227.net.minecraft.a.c;

import mock.v0227.net.minecraft.a.a.e;

/**
 * 仿 indev-20100227 的 EntityCreature / EntityMob：{@code net.minecraft.a.c.c}。
 *
 * <p>这一档的怪物基类<b>不是 abstract</b>（javap 真机 {@code inf-20100227.jar}：
 * {@code public class net.minecraft.a.c.a.e extends net.minecraft.a.c.c}，
 * {@code a.c.c}/{@code a.c.e} 也都有 {@code (World)} 构造器），所以形状判据本身挑不出它的毛病——
 * 只有看见"下面还有更具体的子类"才该把它踢掉。它下面那批叶类在这个 jar 里
 * <b>连构造器都没有</b>（被 ProGuard 删了），所以真机清单里最后就只剩它一个。
 */
public class c extends a {

    public c(e var1) {
        super(var1);
    }

    public c(e var1, int var2) {
        super(var1, var2);
    }
}
