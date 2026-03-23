package com.hmdp.utils;

import com.hmdp.entity.Shop;

import java.util.function.Supplier;

public interface ShopCacheStrategy {

    public Shop queryById(Long id, Supplier<Shop> dbFallback) throws InterruptedException;

}
