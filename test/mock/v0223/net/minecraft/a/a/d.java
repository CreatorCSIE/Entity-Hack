package mock.v0223.net.minecraft.a.a;

import mock.v0223.net.minecraft.a.c.b;

/**
 * 仿 in-20100223 的 IWorldAccess：{@code net.minecraft.a.a.d}
 * （实物 javap：接口里既有 {@code a(Entity)V}/{@code b(Entity)V}＝obtain/releaseEntitySkin，
 * 也有无参的 {@code a()V}＝loadRenderers、{@code h()V}＝unloadWorld）。
 * 实测重点：0223 的 World 只在"载入世界/增删实体"时遍历它，<b>没有</b>每 tick 的无参回调。
 */
public interface d {
    void a();

    void h();

    void a(b var1);

    void b(b var1);

    void a(int var1, int var2, int var3);

    void a(String var1, float var2, float var3, float var4, float var5, float var6);
}
