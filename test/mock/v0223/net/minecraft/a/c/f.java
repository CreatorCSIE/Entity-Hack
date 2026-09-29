package mock.v0223.net.minecraft.a.c;

import mock.v0223.net.minecraft.a.a.g;

/**
 * 仿 indev-20100223 的 EntityItem（掉落物）：{@code a.c.f}。<b>这个类型就是真机那次崩溃的本体。</b>
 *
 * <p>照可读源 {@code EntityItem.java} 建模：第 13 行是 {@code public ItemStack item;}，
 * 第 33-35 行是那个"空构造" {@code public EntityItem(World world1) { super(world1); }}——
 * 坐标是有了，渲染要用的 {@code item} 却只有带 item 的构造器才会赋值，于是永远是 null。
 * 而 {@code RenderManager.entityRenderMap} 里恰好写着 {@code EntityItem -> RenderItem}
 * （可读源 {@code RenderManager.java:55}），所以这个实体一进登记表，
 * 下一帧 {@code RenderItem.a()} 解引用 null 的 item 就把<b>游戏主线程</b>打成 NPE：
 * 用户贴回来的栈顶 {@code net.minecraft.client.a.a.e.a} 就是它。
 *
 * <p>形状上它是"实体基类的直接子类 + 身上没有任何 {@code .png} 纹理串"的样板：
 * 可生成清单必须把它踢掉，同时又不能误伤 {@code a.e.b} 那种有纹理串的活物。
 */
public final class f extends b {
    /** = ItemStack item（只有带 item 的构造器才填，{@code (World)} 构造器填不了） */
    public Object zz;
    /** = age */
    public int zy;
    /** = delayBeforeCanPickup */
    public int zx;

    public f(g var1) {
        super(var1);
    }

    public f(g var1, float var2, float var3, float var4, Object var5) {
        super(var1);
        this.zz = var5;
        // 实物 EntityItem.java:24 走的是 setPosition(f2,f3,f4)：坐标和盒子一起放
        this.a(var2, var3, var4);
    }

    /**
     * 仿 {@code RenderItem.a(Entity, float, float, float, float, float)}：实物第一行就 deref
     * {@code ((EntityItem)entity).item}。item 为 null 时抛出的就是真机那条 NullPointerException，
     * 测试拿它当"渲染器会不会因为这个类型而崩游戏"的判据。
     */
    public void renderLikeRenderItem() {
        this.zy += this.zz.hashCode();
    }
}
