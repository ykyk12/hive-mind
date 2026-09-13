package com.hivemind.evolve;

import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 子优先（child-first）隔离类加载器。
 *
 * 为什么必须子优先：插件目录里若有与父加载器同名的类，子优先能让"候选版本"真正生效；
 * 而插件没带的类（例如 com.hivemind.agent.Tool 接口）会委派给父加载器，
 * 这样 `tool instanceof Tool` 依然成立——否则接口会被加载两份，类型判断全部失效。
 *
 * 生命周期：一次候选加载用完即关（close），避免类加载器泄漏堆积导致 Metaspace 膨胀。
 */
public class IsolatedToolClassLoader extends URLClassLoader {

    private final Path root;

    public IsolatedToolClassLoader(Path root, ClassLoader parent) throws IOException {
        super(new URL[]{root.toUri().toURL()}, parent);
        this.root = root;
    }

    @Override
    protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
        synchronized (getClassLoadingLock(name)) {
            Class<?> loaded = findLoadedClass(name);
            if (loaded == null && isLocal(name)) {
                try {
                    loaded = findClass(name);
                } catch (ClassNotFoundException ignored) {
                    // 本地没有就交给父加载器，保持接口类型一致
                }
            }
            if (loaded == null) {
                loaded = super.loadClass(name, resolve);
            }
            if (resolve) {
                resolveClass(loaded);
            }
            return loaded;
        }
    }

    private boolean isLocal(String className) {
        Path candidate = root.resolve(className.replace('.', '/') + ".class");
        return Files.exists(candidate);
    }
}
