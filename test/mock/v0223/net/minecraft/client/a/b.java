package mock.v0223.net.minecraft.client.a;

import java.util.ArrayList;
import java.util.List;

import mock.v0223.net.minecraft.a.a.d;
import mock.v0223.net.minecraft.a.a.g;

/**
 * 仿 in-20100223 的 RenderEngine：{@code net.minecraft.client.a.b}。
 *
 * <p><b>它是真机注入时把绑定带沟里的头号陷阱。</b>实测这个类
 * {@code implements IWorldAccess}（所以它自己就躺在 {@code World.worldAccesses} 里）、
 * 手里攥着 World、有一个 {@code List} 字段、声明的方法数比真正的 World 还多，
 * 于是"形状像世界 + 方法数选优"的老逻辑会毫不犹豫地选它当 World——
 * 结果登记表永远是空的、生成口/探针全打在渲染引擎上，而窗口标题看着一切正常。
 *
 * <p>本文件的存在就是回归用例：见 {@code MockMain#renderEngineTrap()}。
 */
public final class b implements d {
    public g a;
    public final List c = new ArrayList();
    public final List e = new ArrayList();
    public int f;
    public int g;
    public float h;
    public float i;
    public float j;
    public float k;

    public b(g var1) {
        this.a = var1;
    }

    public void a() {
        this.c.clear();
    }

    public void h() {
        this.c.clear();
    }

    public void a(mock.v0223.net.minecraft.a.c.b var1) {
        this.c.add(var1);
    }

    public void b(mock.v0223.net.minecraft.a.c.b var1) {
        this.c.remove(var1);
    }

    public void a(int var1, int var2, int var3) {
        ++this.f;
    }

    public void a(String var1, float var2, float var3, float var4, float var5, float var6) {
        this.e.add(var1);
    }

    public final void a(g var1, float var2) {
        this.a = var1;
        this.h = var2;
    }

    public final void b(g var1) {
        this.a = var1;
    }

    public final void c(g var1) {
        this.a = var1;
    }

    public final void a(mock.v0223.net.minecraft.a.c.b var1, double var2, double var4, double var6, float var8) {
        this.c.add(var1);
    }

    public final void b(mock.v0223.net.minecraft.a.c.b var1, float var2) {
        this.i = var2;
    }

    public final float g(mock.v0223.net.minecraft.a.c.b var1) {
        return this.j;
    }

    public final void d() {
        ++this.g;
    }

    public final void e() {
        this.k = 0.0F;
    }

    public final void a(float var1, float var2, float var3) {
        this.h = var1;
        this.i = var2;
        this.j = var3;
    }

    public final String f() {
        return "mock-render-engine-0223";
    }
}
