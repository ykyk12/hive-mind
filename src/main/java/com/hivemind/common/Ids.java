package com.hivemind.common;

import java.util.UUID;

/** id 生成：短 id 用于 trace，全 id 用于实体主键。 */
public final class Ids {

    private Ids() {
    }

    public static String shortId() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    }

    public static String newId() {
        return UUID.randomUUID().toString();
    }
}
